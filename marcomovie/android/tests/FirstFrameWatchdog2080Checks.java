package app.mkoai.player;

/** JVM checks for the 2.0.80 first-frame watchdog (pure timing state, fake clock). */
public class FirstFrameWatchdog2080Checks {
    static int total = 0;
    static int failed = 0;

    static void check(boolean cond, String name) {
        total++;
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    public static void main(String[] args) {
        // ---- the window ----
        check(FirstFrameWatchdog2080.TIMEOUT_MS == 20_000L, "TIMEOUT_MS is 20000 (user-approved 20s)");

        // ---- fresh instance is disarmed ----
        FirstFrameWatchdog2080 w = new FirstFrameWatchdog2080();
        check(!w.isArmed(), "fresh watchdog is disarmed");
        check(!w.shouldFire(0L), "disarmed never fires at t=0");
        check(!w.shouldFire(1_000_000L), "disarmed never fires at t=1000000");
        check(w.remainingMs(0L) == -1, "remainingMs is -1 while disarmed");

        // ---- the reported bug: 118s of loading, no frame ----
        w.arm(1000L);
        check(w.isArmed(), "arm() arms the watchdog");
        check(!w.shouldFire(1000L), "no fire at the arm instant");
        check(!w.shouldFire(20_999L), "no fire 19999ms after arming");
        check(w.shouldFire(21_000L), "fires exactly at armedAt + 20000");
        check(w.shouldFire(119_000L), "fires at 118s after arming (the reported hang)");
        check(w.shouldFire(10_000_000L), "fires long after the window (never self-cancels)");
        check(w.remainingMs(1000L) == 20_000L, "remainingMs is 20000 at the arm instant");
        check(w.remainingMs(11_000L) == 10_000L, "remainingMs is 10000 halfway");
        check(w.remainingMs(21_000L) == 0, "remainingMs is 0 when due");
        check(w.remainingMs(119_000L) == 0, "remainingMs stays 0 past the window");

        // ---- a healthy start: frame inside the window disarms ----
        w.arm(5000L);
        w.disarm();
        check(!w.isArmed(), "disarm() disarms the watchdog");
        check(!w.shouldFire(25_000L), "no fire after disarm at the old deadline");
        check(!w.shouldFire(125_000L), "no fire after disarm long past the deadline");
        check(w.remainingMs(25_000L) == -1, "remainingMs is -1 after disarm");

        // ---- re-arm resets the window (every hop gets a fresh 20s) ----
        w.arm(0L);
        check(!w.shouldFire(19_999L), "first window: no fire at 19999");
        w.arm(30_000L);
        check(!w.shouldFire(30_000L + 19_999L), "re-arm: no fire 19999ms after the second arm");
        check(w.shouldFire(30_000L + 20_000L), "re-arm: fires 20000ms after the second arm");
        check(!w.shouldFire(19_999L), "re-arm: the old deadline no longer fires");

        // ---- a slow but healthy start: frame at 19s disarms before the deadline ----
        w.arm(0L);
        check(!w.shouldFire(19_000L), "no fire at 19s (slow healthy start)");
        w.disarm();
        check(!w.shouldFire(20_000L), "disarmed at 19s: no fire at 20s");

        // ---- arm is idempotent-ish: a second arm restarts the window ----
        w.arm(100_000L);
        w.arm(100_000L);
        check(!w.shouldFire(119_999L), "double arm at the same instant: no fire at 19999");
        check(w.shouldFire(120_000L), "double arm at the same instant: fires at 20000");

        // ---- monotonic clock: a backwards jump cannot fire early ----
        w.arm(50_000L);
        check(!w.shouldFire(40_000L), "clock before the arm instant does not fire");
        check(w.remainingMs(40_000L) == 20_000L + 10_000L,
                "remainingMs past the full window when the clock runs backwards");

        System.out.println("\n" + (total - failed) + " FirstFrameWatchdog2080 checks passed");
        if (failed > 0) System.out.println(failed + " FAILED");
    }
}
