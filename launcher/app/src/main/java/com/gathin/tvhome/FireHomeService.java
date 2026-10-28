package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS keeps Home for Amazon's launcher and ignores set-home-activity, so whenever Amazon's home screen
 * comes up, open this launcher instead. Home pressed twice within ESCAPE_MS stays on Amazon's home.
 *
 * Remote app buttons go through the Appstore's AppLaunchActivity. For a disabled app (the Amazon Music button
 * once com.amazon.bueller.music is off) the Appstore then shows its details page; when that page follows a
 * button launch within BUTTON_MS, open YouTube instead.
 *
 * Window-state events only, from Amazon's launcher and Appstore: no screen content, no key filtering, so held
 * keys keep repeating. Ported from bansiman's Home Return (github.com/bansiman/cleanyourtv, my-tv branch).
 */
public class FireHomeService extends AccessibilityService {
    private static final String TAG = "FireHome";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final String APP_LAUNCH = "com.amazon.venezia.pdi.AppLaunchActivity";
    private static final String APP_DETAILS = "com.amazon.venezia.details.AppDetailsActivity";
    private static final ComponentName BUTTON_APP =
            new ComponentName("com.amazon.firetv.youtube", "dev.cobalt.app.MainActivity");
    private static final long ESCAPE_MS = 3000;
    private static final long BUTTON_MS = 5000;

    private long lastRedirect;
    private long buttonLaunch;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        long now = SystemClock.elapsedRealtime();
        if (APP_LAUNCH.equals(cls)) {
            buttonLaunch = now;
        } else if (APP_DETAILS.equals(cls)) {
            if (buttonLaunch != 0 && now - buttonLaunch < BUTTON_MS) {
                buttonLaunch = 0;
                open(BUTTON_APP);
            }
        } else if (AMAZON_HOME.equals(cls)) {
            if (now - lastRedirect < ESCAPE_MS) {
                lastRedirect = 0;
                Log.i(TAG, "Amazon home again within " + ESCAPE_MS + " ms: staying on it");
                return;
            }
            lastRedirect = now;
            open(new ComponentName(this, MainActivity.class));
        }
    }

    private void open(ComponentName target) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setComponent(target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open " + target.flattenToShortString() + ": " + e);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
