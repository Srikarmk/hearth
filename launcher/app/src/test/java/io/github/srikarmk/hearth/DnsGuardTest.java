package io.github.srikarmk.hearth;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The power-cut repair: strict Private DNS that can't get online is loosened until the clock is set, then restored. */
public class DnsGuardTest {

    /** A TV whose strict DNS works only once the clock is right, as with a DoT certificate newer than the TV's date. */
    private static final class FakeTv implements DnsGuard.Tv {
        String mode = DnsGuard.STRICT;
        boolean clockRight;
        boolean strictWorksWhenClockRight = true;
        boolean writable = true;
        boolean loosened;
        final List<String> modes = new ArrayList<>();

        @Override public String mode() { return mode; }
        @Override public boolean setMode(String m) {
            if (!writable) return false;
            mode = m;
            modes.add(m);
            return true;
        }
        @Override public boolean validated() {
            if (DnsGuard.AUTOMATIC.equals(mode)) return true;
            return DnsGuard.STRICT.equals(mode) && clockRight && strictWorksWhenClockRight;
        }
        @Override public boolean resolves() { return validated(); }
        @Override public void remember(boolean l) { loosened = l; }
        @Override public void log(String message) { }
    }

    /** Steps every 5 s from `from` for `ms`; returns the time reached. */
    private static long run(DnsGuard.Machine m, FakeTv tv, long from, long ms) {
        long t = from;
        for (; t <= from + ms; t += 5_000) m.step(tv, t);
        return t;
    }

    @Test
    public void afterAPowerCutItLoosensUntilTheClockIsSetThenGoesBackToStrict() {
        FakeTv tv = new FakeTv();
        DnsGuard.Machine m = new DnsGuard.Machine();
        long t = run(m, tv, 0, 35_000);
        assertEquals(DnsGuard.AUTOMATIC, tv.mode);
        assertTrue(tv.loosened);
        assertEquals(DnsGuard.State.LOOSENED, m.state);

        tv.clockRight = true;          // Fire OS got the time from Amazon
        m.timeSet(t);
        t = run(m, tv, t, 30_000);
        assertEquals(List.of(DnsGuard.AUTOMATIC, DnsGuard.OFF, DnsGuard.STRICT), tv.modes);
        t = run(m, tv, t, 20_000);
        assertEquals(DnsGuard.State.WATCH, m.state);
        assertEquals(DnsGuard.STRICT, tv.mode);
        assertFalse(tv.loosened);
    }

    @Test
    public void aSlowBootIsNotAFault() {
        FakeTv tv = new FakeTv();
        DnsGuard.Machine m = new DnsGuard.Machine();
        run(m, tv, 0, 20_000);
        tv.clockRight = true;          // validated within 30 s
        run(m, tv, 25_000, 30_000);
        assertTrue(tv.modes.isEmpty());
        assertEquals(DnsGuard.State.WATCH, m.state);
    }

    @Test
    public void withoutATimeChangeItWaitsTwoMinutesBeforeRestoring() {
        FakeTv tv = new FakeTv();
        tv.clockRight = true;
        tv.strictWorksWhenClockRight = false;   // say the DoT server is down for a while
        DnsGuard.Machine m = new DnsGuard.Machine();
        long t = run(m, tv, 0, 35_000);
        assertEquals(DnsGuard.State.LOOSENED, m.state);
        t = run(m, tv, t, 100_000);
        assertEquals(DnsGuard.State.LOOSENED, m.state);
        run(m, tv, t, 30_000);
        assertTrue(tv.modes.contains(DnsGuard.OFF));
    }

    @Test
    public void strictThatStillFailsFallsBackToAutomaticAndRetriesLater() {
        FakeTv tv = new FakeTv();
        tv.clockRight = true;
        tv.strictWorksWhenClockRight = false;
        DnsGuard.Machine m = new DnsGuard.Machine();
        long t = run(m, tv, 0, 35_000);
        m.timeSet(t);
        t = run(m, tv, t, 90_000);
        assertEquals(DnsGuard.State.BACKOFF, m.state);
        assertEquals(DnsGuard.AUTOMATIC, tv.mode);
        assertTrue(tv.loosened);

        tv.strictWorksWhenClockRight = true;    // the server is back
        t = run(m, tv, t, DnsGuard.BACKOFF_MS + 40_000);
        assertEquals(DnsGuard.State.WATCH, m.state);
        assertEquals(DnsGuard.STRICT, tv.mode);
    }

    @Test
    public void aModeTheUserPicksIsLeftAlone() {
        FakeTv tv = new FakeTv();
        DnsGuard.Machine m = new DnsGuard.Machine();
        long t = run(m, tv, 0, 35_000);
        tv.mode = DnsGuard.OFF;                 // the user turns Private DNS off in Settings mid-repair
        tv.modes.clear();
        run(m, tv, t, 10 * 60_000);
        assertTrue(tv.modes.isEmpty());
        assertFalse(tv.loosened);

        FakeTv plain = new FakeTv();
        plain.mode = DnsGuard.AUTOMATIC;        // never strict: nothing to guard
        DnsGuard.Machine m2 = new DnsGuard.Machine();
        run(m2, plain, 0, 10 * 60_000);
        assertTrue(plain.modes.isEmpty());
    }

    @Test
    public void aRebootMidRepairCarriesOn() {
        FakeTv tv = new FakeTv();
        tv.mode = DnsGuard.AUTOMATIC;
        tv.loosened = true;
        DnsGuard.Machine m = new DnsGuard.Machine();
        m.resume(tv, 0);
        assertEquals(DnsGuard.State.LOOSENED, m.state);
        tv.clockRight = true;
        m.timeSet(1_000);
        run(m, tv, 0, 60_000);
        assertEquals(DnsGuard.STRICT, tv.mode);
        assertEquals(DnsGuard.State.WATCH, m.state);
    }

    @Test
    public void withoutThePermissionItStops() {
        FakeTv tv = new FakeTv();
        tv.writable = false;
        DnsGuard.Machine m = new DnsGuard.Machine();
        run(m, tv, 0, 60_000);
        assertEquals(DnsGuard.State.DISABLED, m.state);
        assertEquals(DnsGuard.STRICT, tv.mode);
        assertFalse(tv.loosened);
    }
}
