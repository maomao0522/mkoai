// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import android.content.SharedPreferences;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

/**
 * mkoai 2.0.66: quota-hide — a feature disappears only when the Worker
 * says its quota is gone, and comes back when the quota resets.
 *
 * <p><b>The rule (user 2026-11-15 ~20:32):</b> only a clear Worker
 * quota error (429 quota / retry_after) hides a feature. A network
 * failure (non-429, timeout, IOException) NEVER hides anything — the
 * button stays and the next tap retries normally. The Worker shapes
 * (verified against the live Worker source, route table 33):
 * <ul>
 * <li>transient limits (Trakt, Jikan): 429
 * {@code {error:"upstream_rate_limited", source:"&lt;service&gt;",
 * retry_after:&lt;seconds&gt;}} — hide only for the retry_after window;
 * <li>daily quotas (YouTube, OMDb): 429 {@code {error:
 * "upstream_rate_limited", source:"youtube"|"omdb", retry_after:null}}
 * — hide until the next reset (YouTube: midnight Pacific; OMDb:
 * midnight UTC);
 * <li>DeepL: 429 {@code {error:"deepl_quota_exhausted"}} with NO
 * retry_after — the 1M characters are a one-time total, not a daily
 * budget, so there is no reset to wait for.
 * </ul>
 *
 * <p><b>DeepL recovery (chosen mechanism): 24h auto-restore +
 * re-test.</b> A DeepL quota hide lasts 24 hours, then the button
 * returns on its own; the next tap re-tests DeepL — still quota, it
 * hides again for 24h; key replaced, it just works. Chosen over a
 * Settings "re-check" entry because it adds no new UI surface to
 * build, translate or maintain, needs no manual step the user has to
 * remember, and is self-healing: the worst case is one day of a
 * hidden button and one tap to find out the key is fresh. The 24h
 * window also matches the daily-quota cadence the user already knows.
 *
 * <p><b>Storage:</b> one Prefs long per feature,
 * {@code quota_hide_&lt;feature&gt;} = hideUntil epoch ms. These keys
 * are deliberately NOT in {@link Prefs#KEYS} — runtime quota state,
 * not a user setting, never synced (same as {@code ad_marks} and
 * {@code posthog_distinct_id}). An expired entry self-clears on the
 * next {@link #isHidden} read.
 *
 * <p><b>Limitations, documented not hidden:</b> the subtitle
 * <em>download</em> quota has no Worker 429 mapping (502s only) and
 * the subtitle <em>search</em> absorbs provider 429s (tries the other
 * provider, returns 200) — so no 2.0.66 writer can detect it, and the
 * subtitle entry is never hidden for quota. Detecting it needs a
 * Worker quota marker, which is out of scope for this version.
 * Fanart is skipped (2/s transient, poster backfill only) and PostHog
 * has no quota issue.
 *
 * <p>The classification and reset math are pure Java and JVM-tested;
 * only {@link #prefsStore} touches Android, and it is never called by
 * the JVM tests (compiled only, against android.jar).
 */
final class QuotaState2066 {
    private QuotaState2066() {}

    // ---- Features (the key is "quota_hide_" + feature) ----
    static final String TRANSLATE = "translate";
    static final String TRAILER = "trailer";
    static final String SUBTITLE = "subtitle";
    static final String OMDB = "omdb";
    static final String NOTIFY = "notify";
    static final String TRAKT = "trakt";
    static final String SIMKL = "simkl";
    static final String TVMAZE = "tvmaze";

    static final String[] FEATURES = {
        TRANSLATE, TRAILER, SUBTITLE, OMDB, NOTIFY, TRAKT, SIMKL, TVMAZE
    };

    /** The Worker's DeepL quota error (upstream 456 &rarr; 429, no retry_after). */
    static final String ERR_DEEPL_QUOTA = "deepl_quota_exhausted";

    /** The Worker's transient-limit error. */
    static final String ERR_UPSTREAM_RATE_LIMITED = "upstream_rate_limited";

    /** DeepL hide window: 24h, then auto-restore + re-test (see class doc). */
    static final long DEEPL_HIDE_MS = 24L * 60 * 60 * 1000;

    /** Transient hide when the Worker gives no retry_after: 60s. */
    static final long DEFAULT_RETRY_MS = 60L * 1000;

    /** Prefs key prefix. Never add these keys to Prefs.KEYS. */
    static final String KEY_PREFIX = "quota_hide_";

    static String key(String feature) {
        return KEY_PREFIX + feature;
    }

    /** Minimal storage seam — SharedPreferences in the app, a Map in tests. */
    interface Store {
        long getLong(String key, long def);
        void putLong(String key, long value);
        void remove(String key);
    }

    /** A Map-backed Store (tests, and any in-memory use). */
    static Store mapStore(final Map<String, Long> map) {
        return new Store() {
            @Override public long getLong(String key, long def) {
                Long v = map.get(key);
                return v == null ? def : v.longValue();
            }
            @Override public void putLong(String key, long value) { map.put(key, value); }
            @Override public void remove(String key) { map.remove(key); }
        };
    }

    /** The app's SharedPreferences as a Store (never called by JVM tests). */
    static Store prefsStore(final SharedPreferences prefs) {
        return new Store() {
            @Override public long getLong(String key, long def) {
                return prefs.getLong(key, def);
            }
            @Override public void putLong(String key, long value) {
                prefs.edit().putLong(key, value).apply();
            }
            @Override public void remove(String key) {
                prefs.edit().remove(key).apply();
            }
        };
    }

    /**
     * Classifies a Worker failure into a hideUntil epoch ms, or -1
     * when the feature must NOT hide. {@code error} / {@code source}
     * are the Worker's body fields (may be null when the body could
     * not be read); {@code retryAfterSeconds} is the Worker's
     * {@code retry_after} (null when absent). Rules:
     * <ul>
     * <li>DeepL quota error &rarr; now + 24h (auto-restore + re-test);
     * <li>daily quota (youtube/omdb, retry_after null) &rarr; the next
     * reset (YouTube midnight Pacific, OMDb midnight UTC);
     * <li>transient limit with a retry_after &rarr; now + retry_after
     * (0/negative &rarr; 60s default);
     * <li>anything else — no quota signal, unknown source, retry_after
     * null on a transient source &rarr; -1, never hide (fail-open).
     * </ul>
     * Callers only invoke this for a Worker 429 / quota-shaped
     * failure; a plain IOException / timeout / non-429 never reaches
     * it, so a network failure can never hide a feature.
     */
    static long classifyHideUntil(String error, String source,
                                  Integer retryAfterSeconds, long nowMs) {
        if (ERR_DEEPL_QUOTA.equals(error)) return nowMs + DEEPL_HIDE_MS;
        if (retryAfterSeconds != null) {
            long s = retryAfterSeconds.intValue() <= 0
                    ? DEFAULT_RETRY_MS / 1000 : retryAfterSeconds.intValue();
            return nowMs + s * 1000;
        }
        if ("youtube".equals(source)) return nextMidnightPacific(nowMs);
        if ("omdb".equals(source)) return nextMidnightUtc(nowMs);
        return -1;
    }

    /**
     * Records a quota failure for a feature: classifies it and, when
     * it is a real quota signal, stores hideUntil. Returns the stored
     * hideUntil, or -1 when nothing was recorded (not a quota error).
     */
    static long record(Store store, String feature, String error, String source,
                       Integer retryAfterSeconds, long nowMs) {
        long until = classifyHideUntil(error, source, retryAfterSeconds, nowMs);
        if (until > 0) store.putLong(key(feature), until);
        return until;
    }

    /** Raw stored hideUntil (0 = never hidden). */
    static long hideUntil(Store store, String feature) {
        return store.getLong(key(feature), 0);
    }

    /**
     * True while the feature is hidden. An expired entry is removed
     * (self-cleaning) and the feature shows again on the next render —
     * the next call then runs normally, which is the re-test.
     */
    static boolean isHidden(Store store, String feature, long nowMs) {
        long until = store.getLong(key(feature), 0);
        if (until <= 0) return false;
        if (nowMs >= until) {
            store.remove(key(feature));
            return false;
        }
        return true;
    }

    /** Next midnight America/Los_Angeles after {@code nowMs} (YouTube reset). */
    static long nextMidnightPacific(long nowMs) {
        return nextMidnight(nowMs, TimeZone.getTimeZone("America/Los_Angeles"));
    }

    /** Next midnight UTC after {@code nowMs} (OMDb reset). */
    static long nextMidnightUtc(long nowMs) {
        return nextMidnight(nowMs, TimeZone.getTimeZone("UTC"));
    }

    private static long nextMidnight(long nowMs, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(nowMs);
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
