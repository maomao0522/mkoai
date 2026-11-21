package app.mkoai.player;

/**
 * mkoai 2.0.80: first-frame watchdog — a stalled line must not load forever.
 *
 * <p>Diagnosed 2026-11-21: 巨量《蜘蛛俠：嶄新之日國語》 sat on 「正在載入 HD...」 for
 * 118s — never played, no error, no auto-switch. Its only line was a dead m3u8
 * (403 access_denied), but the player never fired onPlayerError, so the whole
 * fallback chain in PlaybackController (next quality → 2.0.68 startup-grace retry
 * → next line → next source) never ran: it only runs after an error.
 *
 * <p>This class is the pure timing state for that watchdog. The wiring
 * (PlaybackController) arms it when a VOD playback prepares and posts one delayed
 * check on the existing UI handler; if the window passes with no first frame and
 * the player is not prepared yet, the check drives the existing error path
 * (MoviePlayer.fireFirstFrameWatchdog), so the existing chain runs unchanged —
 * no parallel fallback is invented. Each hop re-enters playerPage and re-arms a
 * fresh window; the existing counters (_startupRetry once, _lineHopCount,
 * _sourceHopCount) bound the total hops, so the watchdog cannot loop forever.
 *
 * <p>Pure Java, no Android classes: the clock is injected (callers pass
 * SystemClock.elapsedRealtime(), which is monotonic), so the JVM tests drive it
 * with a fake clock. No threads — the only scheduling is the caller's postDelayed.
 */
final class FirstFrameWatchdog2080 {

    /** User-approved window: 20s without a first frame = treat as a playback failure. */
    static final long TIMEOUT_MS = 20_000L;

    /** -1 = disarmed. */
    private long armedAtMs = -1;

    void arm(long nowMs) {
        armedAtMs = nowMs;
    }

    void disarm() {
        armedAtMs = -1;
    }

    boolean isArmed() {
        return armedAtMs >= 0;
    }

    /**
     * True only while armed and the full window has elapsed. A first frame (or
     * STATE_READY — an audio-only stream has no frame to render) disarms the
     * watchdog via {@link #disarm()} or via the caller's own prepared check, so
     * a healthy playback never fires, however slow its first buffer is.
     */
    boolean shouldFire(long nowMs) {
        return armedAtMs >= 0 && nowMs - armedAtMs >= TIMEOUT_MS;
    }

    /** Milliseconds left until the watchdog would fire; -1 when disarmed, 0 when due. */
    long remainingMs(long nowMs) {
        if (armedAtMs < 0) return -1;
        long left = TIMEOUT_MS - (nowMs - armedAtMs);
        return left < 0 ? 0 : left;
    }
}
