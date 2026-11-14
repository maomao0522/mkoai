package app.mkoai.player;

import org.json.*;
import java.util.*;

/** Device-local bookmarks. Resolve fresh media URLs; never persist signed playback URLs. */
final class WatchHistory {
    static final int LIMIT = 60;

    static final class Entry {
        String site, id, title, poster, line, episode;
        int index, position, duration;
        long updated;

        String key() {
            // 2.0.43: sourceless records (synced in without site/id) still
            // need a unique key — "@" + syncKey, so upsert/remove can tell
            // two different titles apart. Sourced records keep the old
            // site + "\n" + id format unchanged.
            if (site == null || site.isEmpty()) return "@" + syncKey(this);
            return site + "\n" + id;
        }

        boolean complete() {
            return duration > 0 && position >= duration - Math.min(5000, duration / 20);
        }

        int resume() {
            return complete() ? 0 : Math.max(0, position);
        }

        JSONObject json() throws JSONException {
            return new JSONObject()
                    .put("site", site)
                    .put("id", id)
                    .put("title", title)
                    .put("poster", poster)
                    .put("line", line)
                    .put("episode", episode)
                    .put("index", index)
                    .put("position", position)
                    .put("duration", duration)
                    .put("updated", updated);
        }
    }

    static List<Entry> read(String text) {
        List<Entry> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        try {
            JSONArray a = new JSONArray(text);
            for (int i = 0; i < a.length() && out.size() < LIMIT; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.site = o.optString("site");
                e.id = o.optString("id");
                e.title = o.optString("title");
                e.poster = o.optString("poster");
                e.line = o.optString("line");
                e.episode = o.optString("episode");
                e.index = Math.max(0, o.optInt("index"));
                e.position = Math.max(0, o.optInt("position"));
                e.duration = Math.max(0, o.optInt("duration"));
                e.updated = o.optLong("updated");
                // 2.0.43: a titled record counts even when site/id are empty
                // (sync can bring in sourceless records). Untitled records
                // still need site+id, as before.
                if ((!e.title.isEmpty() || (!e.site.isEmpty() && !e.id.isEmpty()))
                        && keys.add(e.key())) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static String upsert(String text, Entry entry) {
        List<Entry> entries = read(text);
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().key().equals(entry.key())) it.remove();
        }
        entries.add(0, entry);
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(LIMIT, entries.size()); i++)
            try {
                a.put(entries.get(i).json());
            } catch (Exception ignored) {
            }
        return a.toString();
    }

    static String remove(String text, String key) {
        JSONArray a = new JSONArray();
        for (Entry e : read(text))
            if (!e.key().equals(key))
                try {
                    a.put(e.json());
                } catch (Exception ignored) {
                }
        return a.toString();
    }

    /**
     * 2.0.43: cross-worker sync key — normalized title (Traditional ->
     * Simplified, then Cms.normTitle) + "\n" + normalized episode. Empty
     * title -> "" (no key). Same title+episode from different sources (or
     * with no source at all) maps to the same key.
     */
    static String syncKey(Entry e) {
        if (e == null) return "";
        String title = Cms.normTitle(ChineseConvert.t2s(e.title));
        if (title.isEmpty()) return "";
        return title + "\n" + normEpisode(e.episode);
    }

    /** 2.0.43: true once the stream really played — a position or a total length exists. A failed-source attempt (position 0, duration 0) never counts. */
    static boolean everPlayed(Entry e) {
        return e != null && (e.position > 0 || e.duration > 0);
    }

    /**
     * 2.0.41: display-only dedupe for the Continue Watching row, the home
     * history section, and the history page. Same show + same episode
     * (normalized title + normalized episode label — see normEpisode:
     * leading zeros stripped, 集/期/話/回 unified) keeps ONE entry — the
     * MOST RECENTLY UPDATED one (by `updated`). NOT by furthest progress: a
     * later viewing may legitimately stop earlier, and the latest update
     * still wins. Raw records are untouched (a new list is returned);
     * first-appearance order is preserved (the list is most-recent-first).
     * 2.0.43: records that never really played (position 0 AND duration 0
     * — a failed source's attempt) are filtered out here too, so they never
     * show up anywhere; storage is untouched.
     */
    static List<Entry> deduped(List<Entry> entries) {
        if (entries == null) return new ArrayList<>();
        LinkedHashMap<String, Entry> best = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e == null || !everPlayed(e)) continue;
            String key = dedupeKey(e);
            Entry prev = best.get(key);
            if (prev == null) best.put(key, e);
            else if (e.updated > prev.updated) best.put(key, e);
        }
        return new ArrayList<>(best.values());
    }

    private static String dedupeKey(Entry e) {
        String key = syncKey(e);
        if (key.isEmpty()) return e.key(); // untitled: never merge
        return key;
    }

    /**
     * 2.0.42: normalize an episode label before dedupe, so the same episode
     * recorded under different spellings merges into one card. Strips
     * leading zeros (第01集 → 第1集, bare 01 → 1) and unifies the
     * counter character (第1期 / 第1話 / 第1回 → 第1集). Duration is
     * deliberately NOT compared — the same episode can legitimately have
     * different total lengths across sources.
     */
    /**
     * 2.0.41: canonical episode label — leading zeros stripped, 集/期/話/回
     * unified to 集. 2.0.44: ALL whitespace stripped too: 巨量 names episodes
     * 「第 1 集」 (with spaces) while xgzy uses 「第01集」 and others use
     * 「第1集」 — all three are the same episode and must match, dedupe,
     * and sync-align as one. Used only for keys/matching, never display.
     */
    static String normEpisode(String episode) {
        if (episode == null) return "";
        String e = episode.trim();
        if (e.isEmpty()) return "";
        e = e.replaceAll("\\s+", "");
        e = e.replaceAll("第0+(\\d+)", "第$1");
        e = e.replaceAll("^0+(\\d+)$", "$1");
        e = e.replaceAll("[期話回]", "集");
        return e;
    }
}
