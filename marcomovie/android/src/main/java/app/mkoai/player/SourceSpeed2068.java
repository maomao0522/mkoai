package app.mkoai.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * mkoai 2.0.68: per-source speed + health, and the probe-verdict cache.
 * Learned from Maoki 2.5.48/2.5.50, ported to mkoai's CMS-API world:
 *
 * <p>Maoki's rules and their mkoai mapping (see AutoSourceFallback and
 * PlaybackController for the call sites):
 * <ul>
 *   <li>Speed ranking — Maoki {@code SourceHealth} keeps a per-source
 *       resolve-latency EMA (0.7/0.3) plus success/failure counts, and
 *       {@code AutoSourcePolicy.score()} folds them into the ranking.
 *       mkoai's probe already measures the round-trip
 *       ({@code AutoSourceFallback.Probe.latencyMs}) and threw it away;
 *       now every completed probe records it here (EMA 0.7/0.3, same
 *       weights as Maoki). The ranking use is a tiebreaker ONLY — after
 *       episode-carrying, after the ad-rule source priority, after
 *       probe-verified — so the ad-free sources (巨量/360/魔都動漫/xgzy)
 *       always keep their position. Maoki has no ad-rule concept; mkoai
 *       must not push ad-free sources back to learn speed.</li>
 *   <li>Video-URL cache 6h — Maoki {@code VideoUrlCache} (Hive, key
 *       profile|source|bangumi|episode, TTL 6h, pageUrl must match,
 *       invalidate on playback failure). mkoai's play URL is the episode
 *       URL from the detail, so what is cached is the PROBE VERDICT for
 *       that URL (verified + latency + quality height). A hit skips the
 *       network probe entirely. Only verified verdicts are stored — a
 *       failed probe is re-probed next time (it may have been
 *       transient). A playback failure invalidates the entry, exactly
 *       like Maoki's next()-invalidate.</li>
 *   <li>Slow sources — Maoki {@code SlowSourcePolicy}: EMA &gt; 3s is
 *       slow; slow sources get +2 pre-resolve slots. Same 3s threshold
 *       here ({@link #SLOW_MS}), same +2 slots in the detail-page warm.</li>
 *   <li>Cooldown — Maoki cools a source down for 5 minutes after a
 *       playback failure (-60 in the score). Here a source that failed
 *       playback within {@link #COOLDOWN_MS} sinks below a healthy one
 *       at the same priority (tiebreaker, never a rejection — the probe
 *       stays ranking-only).</li>
 *   <li>Startup error grace — Maoki {@code StartupFailureGate}: one
 *       transient error inside 20s of a source starting is forgiven; a
 *       second one switches. mkoai's Media3 player does NOT recover by
 *       itself (an error the listener swallows leaves the video stuck),
 *       so "forgiven" here means ONE same-URL retry (a fresh prepare),
 *       not swallowing the error — see PlaybackController. Decode errors
 *       bypass the grace, exactly like Maoki's bypass.</li>
 * </ul>
 *
 * <p>What is deliberately NOT ported: Maoki's next-episode best-source
 * switch (pre-load the best source for episode N+1 while N plays).
 * mkoai's boundary cost is the sniffer resolve of a parse-type URL, and
 * a background sniffer is a hidden WebView — the exact thing Maoki 2.5.50
 * removed for competing with the player. The probe cache already covers
 * the direct-URL case at the boundary; the parse case stays a foreground
 * resolve.
 *
 * <p>Storage: two local-only SharedPreferences strings (NOT in
 * {@code Prefs.KEYS}, never synced — device-specific measurements, same
 * as {@code ad_marks}). The health map is one JSON object keyed by site
 * key ({@code {"ms":EMA,"ok":n,"fail":n,"streak":n,"lastFail":ms}});
 * the verdict cache is one JSON object keyed by {@link #cacheKey}
 * ({@code {"v":true,"ms":n,"q":height,"at":ms}}). All pure functions
 * below take the JSON string and return the new one, so the JVM checks
 * can verify them without Android. The {@code MainActivity} wrappers
 * ({@link #recordProbe}, {@link #recordPlaybackFailure},
 * {@link #lookupCached}, {@link #storeCached}, {@link #invalidateForUrl})
 * are the only places that touch prefs.
 */
final class SourceSpeed2068 {
    /** A source whose probe EMA is above this is slow (Maoki: 3000ms). */
    static final long SLOW_MS = 3000L;

    /** Startup error grace window (Maoki StartupFailureGate: 20s). */
    static final long STARTUP_GRACE_MS = 20_000L;

    /** Playback-failure cooldown (Maoki: 5 minutes). */
    static final long COOLDOWN_MS = 5 * 60_000L;

    /** Probe-verdict cache TTL (Maoki VideoUrlCache: 6h). */
    static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;

    /** Max cached verdicts (Maoki: 256). Oldest drops first. */
    static final int MAX_CACHE_ENTRIES = 256;

    /** Prepared-playable freshness (Maoki _preparedReady: 3 min). */
    static final long PREPARED_FRESH_MS = 3L * 60L * 1000L;

    /** Pre-resolve slots for the fastest candidates (Maoki preResolveParallelism = 3). */
    static final int PRERESOLVE_SLOTS = 3;

    /** Extra pre-resolve slots for slow sources (Maoki preResolveExtraSlots = 2). */
    static final int PRERESOLVE_SLOW_EXTRA = 2;

    private SourceSpeed2068() {}

    /** One source's learned health. All fields survive a missing key as "no data". */
    static final class Health {
        /** Probe-latency EMA (ms); -1 when no probe has ever completed. */
        long probeMs = -1;
        int probeOk;
        int probeFail;
        /** Consecutive playback failures (resets on a recorded success — see recordProbe ok). */
        int failStreak;
        /** Wall-clock ms of the last playback failure; 0 when never. */
        long lastFailMs;

        boolean hasProbe() {
            return probeMs >= 0;
        }
    }

    /** EMA update (Maoki weights 0.7/0.3). First sample seeds the EMA. */
    static long ema(long prevMs, long sampleMs) {
        if (prevMs < 0) return sampleMs;
        return Math.round(prevMs * 0.7 + sampleMs * 0.3);
    }

    /** All keys of a JSONObject (org.json 20180813 has no getNames()). */
    private static List<String> keysOf(JSONObject root) {
        List<String> out = new ArrayList<>();
        java.util.Iterator<String> it = root.keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    /** Read the health map from its prefs JSON string. Never throws. */
    static Map<String, Health> readHealth(String json) {
        Map<String, Health> out = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(json);
            for (String key : keysOf(root)) {
                JSONObject o = root.optJSONObject(key);
                if (o == null) continue;
                Health h = new Health();
                h.probeMs = o.optLong("ms", -1);
                h.probeOk = o.optInt("ok", 0);
                h.probeFail = o.optInt("fail", 0);
                h.failStreak = o.optInt("streak", 0);
                h.lastFailMs = o.optLong("lastFail", 0);
                out.put(key, h);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Serialize a health map back to its prefs JSON string. */
    static String writeHealth(Map<String, Health> all) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Health> e : all.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("ms", e.getValue().probeMs);
                o.put("ok", e.getValue().probeOk);
                o.put("fail", e.getValue().probeFail);
                o.put("streak", e.getValue().failStreak);
                o.put("lastFail", e.getValue().lastFailMs);
                root.put(e.getKey(), o);
            }
            return root.toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /**
     * Record one COMPLETED probe. Only completed probes are recorded — a
     * probe cancelled because another source already won is a lower
     * bound, not a latency, and would make a slow source look fast
     * (Maoki SourceHealth.recordResolveLatency). The caller checks
     * {@code Thread.currentThread().isInterrupted()} first; this method
     * itself is pure. A successful probe also clears the playback
     * failure streak — the source proved itself again.
     */
    static String recordProbeJson(String json, String siteKey, long latencyMs, boolean ok) {
        if (siteKey == null || siteKey.isEmpty() || latencyMs < 0) {
            return json == null ? "{}" : json;
        }
        Map<String, Health> all = readHealth(json);
        Health h = all.get(siteKey);
        if (h == null) {
            h = new Health();
            all.put(siteKey, h);
        }
        h.probeMs = ema(h.probeMs, latencyMs);
        if (ok) {
            h.probeOk++;
            h.failStreak = 0;
        } else {
            h.probeFail++;
        }
        return writeHealth(all);
    }

    /** Record one playback failure (the player errored on this source). Pure. */
    static String recordPlaybackFailureJson(String json, String siteKey, long nowMs) {
        if (siteKey == null || siteKey.isEmpty()) {
            return json == null ? "{}" : json;
        }
        Map<String, Health> all = readHealth(json);
        Health h = all.get(siteKey);
        if (h == null) {
            h = new Health();
            all.put(siteKey, h);
        }
        h.failStreak++;
        h.lastFailMs = nowMs;
        return writeHealth(all);
    }

    /** Is this source known-slow (probe EMA above the threshold)? No data means "not proven slow". */
    static boolean isSlow(Health h) {
        return h != null && h.probeMs > SLOW_MS;
    }

    /** Is this source inside its post-failure cooldown? */
    static boolean coolingDown(Health h, long nowMs) {
        return h != null && h.lastFailMs > 0 && nowMs - h.lastFailMs < COOLDOWN_MS;
    }

    /**
     * Speed value for ranking: the learned EMA when present, else this
     * round's probe latency, else "unknown" (sorts last). Maoki's score
     * uses the stored resolve EMA the same way.
     */
    static long speedOfEma(long emaMs, long roundLatencyMs) {
        if (emaMs >= 0) return emaMs;
        if (roundLatencyMs >= 0) return roundLatencyMs;
        return Long.MAX_VALUE;
    }

    /** Same, from a Health record. */
    static long speedOf(Health h, long roundLatencyMs) {
        return speedOfEma(h == null ? -1 : h.probeMs, roundLatencyMs);
    }

    /** Should the first error inside the startup window be forgiven (retried, not hopped)? Pure. */
    static boolean shouldForgiveStartupError(long startedAtMs, long firstErrorAtMs, long nowMs) {
        return firstErrorAtMs == 0 && nowMs - startedAtMs < STARTUP_GRACE_MS;
    }

    // ---- probe-verdict cache ----

    /**
     * Cache key for one probed episode URL. Title is normalized the same
     * way as the history sync key, the episode goes through
     * {@link WatchHistory#normEpisode}, and the URL itself is part of the
     * key — if the source changes the URL, the old verdict misses (Maoki
     * VideoUrlCache's pageUrl check). Empty key means "do not cache".
     */
    static String cacheKey(String siteKey, String title, String episode, String url) {
        if (siteKey == null || siteKey.isEmpty()) return "";
        if (url == null || url.isEmpty()) return "";
        String t = Cms.normTitle(ChineseConvert.t2s(title == null ? "" : title));
        if (t.isEmpty()) return "";
        return siteKey + "\n" + t + "\n" + WatchHistory.normEpisode(episode) + "\n" + url.trim();
    }

    /** One cached verdict. */
    static final class Verdict {
        boolean verified;
        long latencyMs;
        int qualityHeight;
        long atMs;
    }

    /** Look a verdict up. Null on miss, expiry, or URL mismatch (the key holds the URL, so a mismatch cannot happen for a well-formed key — the check is defense in depth). */
    static Verdict lookupCacheJson(String json, String key, long nowMs) {
        if (json == null || key == null || key.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject o = root.optJSONObject(key);
            if (o == null) return null;
            long at = o.optLong("at", 0);
            if (at <= 0 || nowMs - at > CACHE_TTL_MS) return null;
            if (!o.optBoolean("v", false)) return null;
            Verdict v = new Verdict();
            v.verified = true;
            v.latencyMs = o.optLong("ms", -1);
            v.qualityHeight = o.optInt("q", 0);
            v.atMs = at;
            return v;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Store a verdict. Only verified verdicts are stored — a failed probe
     * is re-probed next time (it may have been transient). Returns the new
     * JSON string; expired and oldest-over-cap entries are pruned on write.
     */
    static String storeCacheJson(
            String json, String key, boolean verified, long latencyMs, int qualityHeight, long nowMs) {
        if (key == null || key.isEmpty() || !verified) {
            return json == null ? "{}" : json;
        }
        try {
            JSONObject root = json == null || json.isEmpty() ? new JSONObject() : new JSONObject(json);
            JSONObject o = new JSONObject();
            o.put("v", true);
            o.put("ms", latencyMs);
            o.put("q", qualityHeight);
            o.put("at", nowMs);
            root.put(key, o);
            return pruneCacheJson(root.toString(), nowMs);
        } catch (Exception ignored) {
            return json == null ? "{}" : json;
        }
    }

    /** Drop one entry (a playback failure invalidates it). Returns the new JSON string. */
    static String invalidateCacheJson(String json, String key) {
        if (json == null || key == null || key.isEmpty()) {
            return json == null ? "{}" : json;
        }
        try {
            JSONObject root = new JSONObject(json);
            root.remove(key);
            return root.toString();
        } catch (Exception ignored) {
            return json;
        }
    }

    /** Drop expired entries; over the cap, drop the oldest first (Maoki VideoUrlCache._prune). Pure. */
    static String pruneCacheJson(String json, long nowMs) {
        if (json == null || json.isEmpty()) return "{}";
        try {
            JSONObject root = new JSONObject(json);
            List<String> expired = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (String key : keysOf(root)) {
                JSONObject o = root.optJSONObject(key);
                long at = o == null ? 0 : o.optLong("at", 0);
                if (at <= 0 || nowMs - at > CACHE_TTL_MS) expired.add(key);
                else keys.add(key);
            }
            for (String key : expired) root.remove(key);
            if (keys.size() > MAX_CACHE_ENTRIES) {
                keys.sort(
                        (x, y) -> {
                            long ax = root.optJSONObject(x).optLong("at", 0);
                            long ay = root.optJSONObject(y).optLong("at", 0);
                            return Long.compare(ax, ay);
                        });
                for (int i = 0; i < keys.size() - MAX_CACHE_ENTRIES; i++) {
                    root.remove(keys.get(i));
                }
            }
            return root.toString();
        } catch (Exception ignored) {
            return json;
        }
    }

    // ---- MainActivity wrappers (the only places that touch prefs) ----

    /** Record one completed probe for a site (skips when the calling thread was interrupted — a cancelled probe). */
    static void recordProbe(MainActivity a, String siteKey, long latencyMs, boolean ok) {
        try {
            if (a == null || a.prefs == null) return;
            if (Thread.currentThread().isInterrupted()) return;
            String raw = a.prefs.getString(Prefs.SOURCE_SPEED_2068, "{}");
            a.prefs.edit()
                    .putString(Prefs.SOURCE_SPEED_2068, recordProbeJson(raw, siteKey, latencyMs, ok))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Record one playback failure for a site (feeds the cooldown ranking). */
    static void recordPlaybackFailure(MainActivity a, String siteKey) {
        try {
            if (a == null || a.prefs == null) return;
            String raw = a.prefs.getString(Prefs.SOURCE_SPEED_2068, "{}");
            a.prefs.edit()
                    .putString(
                            Prefs.SOURCE_SPEED_2068,
                            recordPlaybackFailureJson(raw, siteKey, System.currentTimeMillis()))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Cached verdict for one episode URL, or null. */
    static Verdict lookupCached(MainActivity a, String key) {
        try {
            if (a == null || a.prefs == null) return null;
            String raw = a.prefs.getString(Prefs.SOURCE_URL_CACHE_2068, "{}");
            return lookupCacheJson(raw, key, System.currentTimeMillis());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Store a verified verdict for one episode URL. */
    static void storeCached(
            MainActivity a, String key, boolean verified, long latencyMs, int qualityHeight) {
        try {
            if (a == null || a.prefs == null) return;
            String raw = a.prefs.getString(Prefs.SOURCE_URL_CACHE_2068, "{}");
            a.prefs.edit()
                    .putString(
                            Prefs.SOURCE_URL_CACHE_2068,
                            storeCacheJson(
                                    raw, key, verified, latencyMs, qualityHeight,
                                    System.currentTimeMillis()))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Invalidate the cached verdict for one episode URL (playback failed on it). */
    static void invalidateForUrl(MainActivity a, String siteKey, String title, String episode, String url) {
        try {
            if (a == null || a.prefs == null) return;
            String key = cacheKey(siteKey, title, episode, url);
            if (key.isEmpty()) return;
            String raw = a.prefs.getString(Prefs.SOURCE_URL_CACHE_2068, "{}");
            a.prefs.edit()
                    .putString(Prefs.SOURCE_URL_CACHE_2068, invalidateCacheJson(raw, key))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Learned health for one site, or null when nothing was ever recorded. */
    static Health healthFor(MainActivity a, String siteKey) {
        try {
            if (a == null || a.prefs == null || siteKey == null) return null;
            String raw = a.prefs.getString(Prefs.SOURCE_SPEED_2068, "{}");
            return readHealth(raw).get(siteKey);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
