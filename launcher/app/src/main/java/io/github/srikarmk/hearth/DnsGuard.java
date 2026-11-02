package io.github.srikarmk.hearth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.net.InetAddress;

/**
 * Gets a Fire TV with strict Private DNS (install.sh DNS=1) back online after a power cut.
 *
 * A Fire TV keeps no time while unplugged and comes back weeks behind. If the DNS-over-TLS server's certificate
 * is newer than that, every lookup fails, and Fire OS sets its clock from Amazon's servers, which need a lookup
 * too: the TV shows Wi-Fi connected with nothing loading, and the clock never catches up. The guard sees strict
 * mode with a network that doesn't validate, drops to automatic DNS until the clock is set, then puts strict mode
 * back (off, then hostname: setting hostname again on its own leaves the resolver in its failure backoff). If
 * strict mode still fails, the TV stays on automatic DNS and tries again later, so being online beats ad-blocking.
 *
 * Only acts in strict mode, and stops as soon as the mode is changed by anyone else. Needs WRITE_SECURE_SETTINGS,
 * which install.sh grants with pm grant; without it the guard does nothing.
 */
final class DnsGuard {
    private static final String TAG = "DnsGuard";
    static final String MODE = "private_dns_mode";
    static final String STRICT = "hostname", AUTOMATIC = "opportunistic", OFF = "off";

    /** What the machine reads and changes on the TV; a fake in tests. */
    interface Tv {
        String mode();
        /** False when the TV refused (no WRITE_SECURE_SETTINGS). */
        boolean setMode(String mode);
        /** The default network has passed Android's internet check. */
        boolean validated();
        /** A real lookup through the current DNS answers. */
        boolean resolves();
        /** Kept across a reboot: the guard, not the user, took the TV off strict mode. */
        void remember(boolean loosened);
        void log(String message);
    }

    enum State { WATCH, SUSPECT, LOOSENED, OFF, RESTORED, BACKOFF, DISABLED }

    static final long SUSPECT_MS = 30_000;       // validation after a boot or a Wi-Fi change takes a few seconds
    static final long TIME_SETTLE_MS = 5_000;    // after Fire OS sets the clock, before going back to strict
    static final long TIME_WAIT_MS = 120_000;    // when the clock was right all along, no time change comes
    static final long OFF_MS = 4_000;
    static final long RESTORE_GRACE_MS = 15_000; // the old VALIDATED can outlive the switch back for a moment
    static final long RESTORE_MS = 60_000;
    static final long BACKOFF_MS = 30 * 60_000L, BACKOFF_MAX_MS = 6 * 60 * 60_000L;

    /** The decisions, with no Android in them. step() runs every few seconds; timeSet() when the clock changes. */
    static final class Machine {
        State state = State.WATCH;
        long since;
        long timeSetAt = -1;
        long backoff = BACKOFF_MS;

        void timeSet(long now) {
            if (state == State.LOOSENED) timeSetAt = now;
        }

        /** After a reboot in the middle of a repair: carry on from automatic DNS. */
        void resume(Tv tv, long now) {
            String mode = tv.mode();
            if (AUTOMATIC.equals(mode)) {
                go(State.LOOSENED, now);
            } else if (OFF.equals(mode)) {
                go(State.OFF, now);   // rebooted between off and strict
            } else {
                tv.remember(false);
            }
        }

        void step(Tv tv, long now) {
            String mode = tv.mode();
            switch (state) {
                case WATCH:
                    if (STRICT.equals(mode) && !tv.validated()) go(State.SUSPECT, now);
                    break;
                case SUSPECT:
                    if (!STRICT.equals(mode) || tv.validated()) {
                        go(State.WATCH, now);
                    } else if (now - since >= SUSPECT_MS) {
                        if (!set(tv, AUTOMATIC)) break;
                        tv.log("strict Private DNS has no internet: automatic DNS until the clock is set");
                        tv.remember(true);
                        go(State.LOOSENED, now);
                    }
                    break;
                case LOOSENED:
                    if (!AUTOMATIC.equals(mode)) {
                        userChanged(tv, now);
                    } else if (tv.validated() && (timeSetAt >= 0 ? now - timeSetAt >= TIME_SETTLE_MS
                            : now - since >= TIME_WAIT_MS)) {
                        if (set(tv, OFF)) go(State.OFF, now);
                    }
                    break;
                case OFF:
                    if (!OFF.equals(mode)) {
                        userChanged(tv, now);
                    } else if (now - since >= OFF_MS) {
                        if (set(tv, STRICT)) go(State.RESTORED, now);
                    }
                    break;
                case RESTORED:
                    if (!STRICT.equals(mode)) {
                        userChanged(tv, now);
                    } else if (now - since >= RESTORE_GRACE_MS && tv.validated() && tv.resolves()) {
                        tv.log("strict Private DNS works again");
                        tv.remember(false);
                        backoff = BACKOFF_MS;
                        go(State.WATCH, now);
                    } else if (now - since >= RESTORE_MS) {
                        if (!set(tv, AUTOMATIC)) break;
                        tv.log("strict Private DNS still fails: automatic DNS, next try in " + backoff / 60_000 + " min");
                        go(State.BACKOFF, now);
                    }
                    break;
                case BACKOFF:
                    if (!AUTOMATIC.equals(mode)) {
                        userChanged(tv, now);
                    } else if (now - since >= backoff) {
                        backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
                        if (set(tv, OFF)) go(State.OFF, now);
                    }
                    break;
                case DISABLED:
                    break;
            }
        }

        /** Busy states check every few seconds; watching only needs the occasional look. */
        long nextStepMs() {
            return state == State.WATCH ? 60_000 : state == State.BACKOFF ? 60_000 : 5_000;
        }

        private boolean set(Tv tv, String mode) {
            if (tv.setMode(mode)) return true;
            tv.log("can't change Private DNS (WRITE_SECURE_SETTINGS not granted): guard off");
            go(State.DISABLED, 0);
            return false;
        }

        private void userChanged(Tv tv, long now) {
            tv.remember(false);
            backoff = BACKOFF_MS;
            go(State.WATCH, now);
        }

        private void go(State next, long now) {
            state = next;
            since = now;
            if (next == State.LOOSENED) timeSetAt = -1;
        }
    }

    private static volatile DnsGuard running;

    private final Context ctx;
    private final Machine machine = new Machine();
    private final Handler handler;
    private final Tv tv;
    private final Runnable tick = this::tick;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            if (machine.state == State.WATCH) poke();
        }
    };
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.post(() -> machine.timeSet(SystemClock.elapsedRealtime()));
        }
    };

    private DnsGuard(Context context) {
        ctx = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("dns-guard");
        thread.start();
        handler = new Handler(thread.getLooper());
        tv = new AndroidTv(ctx);
    }

    /** From FireHomeService, which Fire OS starts at boot. */
    static synchronized void start(Context context) {
        if (running != null) return;
        DnsGuard g = new DnsGuard(context);
        running = g;
        g.handler.post(() -> {
            if (g.ctx.getSharedPreferences(TAG, Context.MODE_PRIVATE).getBoolean("loosened", false)) {
                g.machine.resume(g.tv, SystemClock.elapsedRealtime());
            }
            g.tick();
        });
        g.ctx.registerReceiver(g.timeReceiver, new IntentFilter(Intent.ACTION_TIME_CHANGED), null, g.handler);
        ConnectivityManager cm = g.ctx.getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(g.networkCallback, g.handler);
    }

    private void poke() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void tick() {
        handler.removeCallbacks(tick);
        try {
            machine.step(tv, SystemClock.elapsedRealtime());
        } catch (RuntimeException e) {
            Log.w(TAG, "step failed: " + e);
        }
        if (machine.state != State.DISABLED) handler.postDelayed(tick, machine.nextStepMs());
    }

    private static final class AndroidTv implements Tv {
        private final Context ctx;
        private final ConnectivityManager cm;

        AndroidTv(Context ctx) {
            this.ctx = ctx;
            cm = ctx.getSystemService(ConnectivityManager.class);
        }

        @Override
        public String mode() {
            return Settings.Global.getString(ctx.getContentResolver(), MODE);
        }

        @Override
        public boolean setMode(String mode) {
            try {
                return Settings.Global.putString(ctx.getContentResolver(), MODE, mode);
            } catch (SecurityException e) {
                return false;
            }
        }

        @Override
        public boolean validated() {
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }

        @Override
        public boolean resolves() {
            Network n = cm == null ? null : cm.getActiveNetwork();
            if (n == null) return false;
            try {
                InetAddress[] found = n.getAllByName("www.amazon.com");
                return found.length > 0;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void remember(boolean loosened) {
            SharedPreferences p = ctx.getSharedPreferences(TAG, Context.MODE_PRIVATE);
            p.edit().putBoolean("loosened", loosened).apply();
        }

        @Override
        public void log(String message) {
            Log.i(TAG, message);
        }
    }
}
