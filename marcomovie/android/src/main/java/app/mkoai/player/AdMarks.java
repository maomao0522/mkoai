package app.mkoai.player;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.60: manual ad marks. While watching, the user taps {@code 標記廣告}
 * once to record the ad start and again to record the ad end; the range is
 * stored per title + episode + source (local only, never synced) and the
 * player auto-skips it on replay of the same title/episode/source.
 *
 * <p>Storage is a single JSON array string (insertion-ordered, so the
 * oldest range drops first when the cap is hit), one object per range:
 * {@code [{"k":"<key>","s":startMs,"e":endMs}, ...]}. Pure Java so the JVM
 * checks can verify the store and the skip math without Android framework
 * dependencies.
 *
 * <p>Behaviour (fixed by the 2.0.60 spec, documented here so the UI and the
 * tests agree):
 * <ul>
 *   <li>Marking: tap 1 records the start (held in memory only); tap 2
 *       records the end and saves the range. The end must be strictly after
 *       the start (at least 1s later) or the tap is ignored.
 *   <li>Clearing: tapping {@code 標記廣告} while a full range already exists
 *       for this title/episode/source removes that range. Re-marking is
 *       clear (1 tap) then mark start + mark end (2 taps).
 *   <li>Auto-skip (replay): while the position is inside a saved range
 *       {@code [start, end)}, the player seeks to {@code end} once per
 *       entry. The caller re-arms by tracking whether the position has
 *       left the range since (see {@link #inRange(int, int[])} and the
 *       {@code adSkipArmed} flag in PlaybackControls).
 *   <li>One-tap {@code 跳過廣告}: {@link #skipTarget(int, int, int[])} jumps
 *       to the end of the marked range when the current position is inside
 *       the range; otherwise it skips forward 30s (clamped to the duration).
 *       Positions before the marked range do NOT jump to its end (that
 *       would skip unrelated content); they get the plain +30s skip.
 * </ul>
 */
final class AdMarks {
    /** One-tap skip distance when there is no mark to jump to (ms). */
    static final int SKIP_FORWARD_MS = 30_000;

    /** Maximum stored ranges (bounds the prefs string). Oldest entry drops first. */
    static final int MAX_RANGES = 200;

    private AdMarks() {}

    /**
     * Storage key for one title/episode/source. Title is normalized the same
     * way as the history sync key (Traditional -> Simplified, then
     * {@link Cms#normTitle}), the episode goes through
     * {@link WatchHistory#normEpisode} (leading zeros stripped,
     * 集/期/話/回 unified, whitespace stripped), and the source is the
     * site key (falling back to the api host when the key is empty).
     */
    static String key(String title, String episode, String siteKey) {
        String t = Cms.normTitle(ChineseConvert.t2s(title == null ? "" : title));
        if (t.isEmpty()) return "";
        String site = siteKey == null ? "" : siteKey.trim();
        return t + "\n" + WatchHistory.normEpisode(episode) + "\n" + site;
    }

    /** Saved range for a key, or null when there is none / it is invalid. */
    static int[] get(String json, String key) {
        if (json == null || key == null || key.isEmpty()) return null;
        int[] range = readAll(json).get(key);
        return range == null ? null : new int[] {range[0], range[1]};
    }

    /** Store (or replace) a range. Returns the new JSON string. */
    static String put(String json, String key, int startMs, int endMs) {
        if (key == null || key.isEmpty() || startMs < 0 || endMs <= startMs) {
            return json == null ? "[]" : json;
        }
        Map<String, int[]> all = readAll(json);
        all.remove(key);
        all.put(key, new int[] {startMs, endMs});
        while (all.size() > MAX_RANGES) {
            String eldest = all.keySet().iterator().next();
            all.remove(eldest);
        }
        return writeAll(all);
    }

    /** Remove a range. Returns the new JSON string. */
    static String remove(String json, String key) {
        if (json == null || key == null || key.isEmpty()) return json == null ? "[]" : json;
        Map<String, int[]> all = readAll(json);
        all.remove(key);
        return writeAll(all);
    }

    /** True when the position is inside the saved range [start, end). */
    static boolean inRange(int positionMs, int[] range) {
        return range != null && positionMs >= range[0] && positionMs < range[1];
    }

    /**
     * One-tap {@code 跳過廣告} target. Inside a saved range -> the end of
     * that range. Otherwise -> 30s forward, clamped to the duration
     * (duration &lt;= 0 means unknown: no clamp).
     */
    static int skipTarget(int positionMs, int durationMs, int[] range) {
        if (inRange(positionMs, range)) return range[1];
        int target = positionMs + SKIP_FORWARD_MS;
        if (durationMs > 0) target = Math.min(target, durationMs);
        return Math.max(0, target);
    }

    private static Map<String, int[]> readAll(String json) {
        Map<String, int[]> all = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return all;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String k = o.optString("k", "");
                int start = o.optInt("s", -1);
                int end = o.optInt("e", -1);
                if (!k.isEmpty() && start >= 0 && end > start) all.put(k, new int[] {start, end});
            }
        } catch (Exception ignored) {
        }
        return all;
    }

    private static String writeAll(Map<String, int[]> all) {
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<String, int[]> e : all.entrySet()) {
                a.put(
                        new JSONObject()
                                .put("k", e.getKey())
                                .put("s", e.getValue()[0])
                                .put("e", e.getValue()[1]));
            }
            return a.toString();
        } catch (Exception ignored) {
            return "[]";
        }
    }
}
