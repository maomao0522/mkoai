package app.mkoai.player;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai account sync on the MarcoMovie base (Supabase PostgREST).
 *
 * <p>Local data is keyed by (site, video_id) — NOT TMDB — so this uses its own tables:
 * {@code mkoai_marco_history} and {@code mkoai_marco_watchlist}. Settings reuse
 * {@code mkoai_app_settings}. The user must run the updated
 * {@code supabase-schema-mkoai.sql} once before sync works.
 *
 * <p>Merge rule (history): furthest-wins — the larger position wins per title+episode.
 * History rows carry NO source: the cloud key uses user_id, deletion generation, and SHA-256 of the
 * title+episode sync key), so the same episode watched on different sources (or
 * different devices) merges into one row. Local records keep their own site/id/line;
 * only progress/time is taken from remote. Watchlist: union. Settings:
 * last-write-wins per key. Runs on a background thread; never on the UI thread.
 */
final class SupabaseSync {
    private static final String REST = SupabaseAuth.SUPABASE_URL + "/rest/v1/";
    private static final String HISTORY_TABLE = "mkoai_marco_history";
    private static final String WATCHLIST_TABLE = "mkoai_marco_watchlist";
    private static final String SETTINGS_TABLE = "mkoai_app_settings";

    /** While true, local pref writes come from applying remote data — do not trigger an upload. */
    static volatile boolean applyingRemote = false;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Handler DEBOUNCE = new Handler(Looper.getMainLooper());
    private static Runnable pendingUpload;
    private static volatile boolean syncing = false;

    private SupabaseSync() {}

    // ---------- low-level PostgREST ----------

    private static String request(Context c, String method, String path, String body, boolean needAuth) throws Exception {
        return request(c, method, path, body, needAuth, "resolution=merge-duplicates,return=minimal");
    }
    private static String request(
            Context c, String method, String path, String body, boolean needAuth, String preference)
            throws Exception {
        String token = needAuth ? SupabaseAuth.validAccessToken(c) : null;
        if (needAuth && token == null) throw new IllegalStateException("not signed in");
        URL url = new URL(REST + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("apikey", SupabaseAuth.ANON_KEY);
        if (needAuth) conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method) || "PATCH".equals(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Prefer", preference);
        }
        if (body != null) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try {
                return SupabaseAuth.readAll(conn.getInputStream());
            } catch (Exception e) {
                return "";
            }
        }
        String err = "";
        try {
            err = SupabaseAuth.readAll(conn.getErrorStream());
        } catch (Exception ignored) {
        }
        throw new RuntimeException("HTTP " + code + " " + method + " " + path + " " + err);
        } finally { conn.disconnect(); }
    }

    private static String clearKey(Context c) {
        String uid = SupabaseAuth.userId(c);
        return "history_clear_" + (uid == null ? "local" : uid);
    }
    private static long clearedAt(Context c) {
        return SupabaseAuth.prefs(c).getLong(clearKey(c), 0);
    }
    private static long parseUtc(String ts) throws Exception { return HistoryClearPolicy.parseUtc(ts); }
    private static String utc(long ms) { return HistoryClearPolicy.utc(ms); }
    private static final Object HISTORY_LOCK = new Object();
    static void clearHistory(Context c) {
        // Persist immediately, including offline clears; every merge and upload observes this barrier.
        long mark = System.currentTimeMillis();
        synchronized (HISTORY_LOCK) {
            SupabaseAuth.prefs(c).edit().putLong(clearKey(c), mark)
                    .putBoolean(clearKey(c) + "_pending", true).remove(Prefs.WATCH_HISTORY).apply();
        }
        scheduleUpload(c);
    }
    private static void flushHistoryClear(Context c, String uid) throws Exception {
        android.content.SharedPreferences p = SupabaseAuth.prefs(c);
        String key = clearKey(c);
        if (!p.getBoolean(key + "_pending", false)) return;
        long mark = p.getLong(key, 0);
        // An empty-title sentinel uses the existing schema and propagates deletion to other devices.
        JSONObject row = new JSONObject().put("user_id", uid).put("site", "")
                .put("video_id", "__history_clear__").put("title", "").put("episode", "")
                .put("position", 0).put("duration", 0).put("poster", "").put("updated_at", utc(mark));
        String owner = "user_id=eq." + URLEncoder.encode(uid, "UTF-8");
        // Insert-if-absent, then advance monotonically: an older offline clear cannot overwrite a newer one.
        request(c, "POST", HISTORY_TABLE + "?on_conflict=user_id,site,video_id", new JSONArray().put(row).toString(), true,
                "resolution=ignore-duplicates,return=minimal");
        String sentinel = HISTORY_TABLE + "?" + owner + "&site=eq.&video_id=eq.__history_clear__";
        request(c, "PATCH", sentinel + "&updated_at=lt." + URLEncoder.encode(utc(mark), "UTF-8"),
                new JSONObject().put("updated_at", utc(mark)).toString(), true);
        JSONArray confirmed = new JSONArray(request(c, "GET", sentinel + "&select=*", null, true));
        if (confirmed.length() > 0) mark = Math.max(mark, historyFromRow(confirmed.getJSONObject(0)).updated);
        synchronized (HISTORY_LOCK) {
            if (mark > p.getLong(key, 0)) p.edit().putLong(key, mark).apply();
        }
        // Generation filtering also rejects malformed future timestamps written by older releases.
        // Keep newer clear generations if another device clears while this request is in flight.
        String generation = HistoryClearPolicy.generation(mark);
        request(c, "DELETE", HISTORY_TABLE + "?" + owner
                + "&video_id=neq.__history_clear__&or=(site.not.like.__history_generation__*,site.lt."
                + generation + ")", null, true);
        if (p.getLong(key, 0) == mark) p.edit().putBoolean(key + "_pending", false).apply();
    }
    private static List<WatchHistory.Entry> afterClear(Context c, List<WatchHistory.Entry> rows) {
        long mark = clearedAt(c);
        List<WatchHistory.Entry> out = new ArrayList<>();
        for (WatchHistory.Entry e : rows) if (HistoryClearPolicy.localTime(e.updated, mark, System.currentTimeMillis())) out.add(e);
        return out;
    }

    private static List<WatchHistory.Entry> remoteForGeneration(Context c, List<WatchHistory.Entry> rows) {
        List<WatchHistory.Entry> out = new ArrayList<>();
        long mark = clearedAt(c);
        for (WatchHistory.Entry e : rows) if (HistoryClearPolicy.remoteGeneration(e.site, mark)) out.add(e);
        return afterClear(c, out);
    }
    private static List<WatchHistory.Entry> mergeRemoteHistory(Context c, List<WatchHistory.Entry> remote) {
        synchronized (HISTORY_LOCK) {
            // Re-read local state under the deletion lock; never commit a pre-clear local snapshot.
            List<WatchHistory.Entry> merged = mergeHistory(readLocalHistory(c), remoteForGeneration(c, remote));
            writeLocalHistory(c, merged);
            return merged;
        }
    }

    // ---------- history (source-free: keyed by title + episode) ----------

    /**
     * 2.0.43: the cloud identity of a history row is NOT its source. site is source-free (empty before a clear, deletion-generation metadata afterwards)
     * and video_id is "k" + the first 32 hex chars of the SHA-256 of
     * {@link WatchHistory#syncKey} (normalized title + "\n" + normalized episode), so the
     * same title+episode from any source or device lands on the same row and the
     * on_conflict (user_id, site, video_id) key is effectively title+episode. No SQL
     * change is needed: the table PK is already (user_id, site, video_id).
     */
    private static String syncVideoId(WatchHistory.Entry e) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(WatchHistory.syncKey(e).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 16; i++)
            sb.append(String.format(java.util.Locale.US, "%02x", digest[i]));
        return "k" + sb.toString();
    }

    /**
     * 2.0.43: upload only title, episode, position, duration, poster, updated_at.
     * line (a source's line name) and episode_index (a source-internal number) are
     * never sent — they do not carry across devices. The original site/video_id are
     * not sent either; the local record keeps them untouched.
     */
    private static JSONObject historyRow(Context c, String uid, WatchHistory.Entry e) throws Exception {
        return new JSONObject()
                .put("user_id", uid)
                .put("site", HistoryClearPolicy.generation(clearedAt(c)))
                .put("video_id", syncVideoId(e))
                .put("episode", e.episode == null ? "" : e.episode)
                .put("position", e.position)
                .put("duration", e.duration)
                .put("title", e.title == null ? "" : e.title)
                .put("poster", e.poster == null ? "" : e.poster)
                .put("updated_at", utc(e.updated == 0 ? System.currentTimeMillis() : e.updated));
    }

    private static WatchHistory.Entry historyFromRow(JSONObject o) {
        WatchHistory.Entry e = new WatchHistory.Entry();
        e.site = o.optString("site");
        e.id = o.optString("video_id");
        e.episode = o.optString("episode");
        e.index = o.optInt("episode_index");
        e.position = o.optInt("position");
        e.duration = o.optInt("duration");
        e.title = o.optString("title");
        e.poster = o.optString("poster");
        e.line = o.optString("line");
        e.updated = System.currentTimeMillis();
        try {
            String ts = o.optString("updated_at");
            if (!ts.isEmpty()) {
                e.updated =
                        parseUtc(ts);
            }
        } catch (Exception ignored) {
        }
        return e;
    }

    private static void uploadHistory(Context c, String uid, List<WatchHistory.Entry> local)
            throws Exception {
        flushHistoryClear(c, uid);
        local = readLocalHistory(c);
        // 2.0.43: untitled entries have no stable source-free key — never upload them.
        // Collapse same title+episode duplicates to one row (furthest-wins), so one
        // POST never carries two rows with the same (user_id, "", video_id).
        Map<String, WatchHistory.Entry> byId = new HashMap<>();
        for (WatchHistory.Entry e : local) {
            if (e.title == null || e.title.trim().isEmpty()) continue;
            String vid = syncVideoId(e);
            WatchHistory.Entry cur = byId.get(vid);
            if (cur == null
                    || e.position > cur.position
                    || (e.position == cur.position && e.updated > cur.updated)) {
                byId.put(vid, e);
            }
        }
        if (byId.isEmpty()) return;
        JSONArray rows = new JSONArray();
        for (WatchHistory.Entry e : byId.values()) rows.put(historyRow(c, uid, e));
        request(
                c,
                "POST",
                HISTORY_TABLE + "?on_conflict=user_id,site,video_id",
                rows.toString(),
                true);
    }

    /**
     * 2.0.43: accept BOTH new source-free rows (site "" / video_id "k"+hash) and old
     * rows that still carry a site/video_id — both are aligned by syncKey on merge.
     * Rows with an empty title are dropped: they have no stable key.
     */
    private static List<WatchHistory.Entry> downloadHistory(Context c) throws Exception {
        // The deletion marker must not fall out of the paginated history window after 200 new episodes.
        String uid = SupabaseAuth.userId(c);
        if (uid != null) {
            JSONArray markers = new JSONArray(request(c, "GET", HISTORY_TABLE
                    + "?user_id=eq." + URLEncoder.encode(uid, "UTF-8")
                    + "&site=eq.&video_id=eq.__history_clear__&select=*", null, true));
            if (markers.length() > 0) {
                long remoteMark = historyFromRow(markers.getJSONObject(0)).updated;
                synchronized (HISTORY_LOCK) {
                    if (remoteMark > clearedAt(c)) SupabaseAuth.prefs(c).edit().putLong(clearKey(c), remoteMark).apply();
                }
            }
        }
        String resp =
                request(
                        c,
                        "GET",
                        HISTORY_TABLE + "?select=*&order=updated_at.desc&limit=200",
                        null,
                        true);
        List<WatchHistory.Entry> out = new ArrayList<>();
        JSONArray a = new JSONArray(resp);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            WatchHistory.Entry e = historyFromRow(o);
            if ("__history_clear__".equals(o.optString("video_id"))) {
                synchronized (HISTORY_LOCK) {
                    long mark = Math.max(clearedAt(c), e.updated);
                    SupabaseAuth.prefs(c).edit().putLong(clearKey(c), mark).apply();
                }
                continue;
            }
            if (e.title == null || e.title.trim().isEmpty()) continue;
            out.add(e);
        }
        return remoteForGeneration(c, out);
    }

    /**
     * Merge key: the source-free sync key (normalized title + normalized episode).
     * Untitled entries can never merge safely (every untitled episode would collide),
     * so they fall back to their local site+id key and are never merged or uploaded.
     */
    private static String mergeKey(WatchHistory.Entry e) {
        if (e.title == null || e.title.trim().isEmpty()) return "\0" + e.key();
        return WatchHistory.syncKey(e);
    }

    /**
     * Merge remote history into local, aligned by syncKey (title + episode) instead of
     * site+id, so the same episode from different sources (or an old sourced row and
     * a new source-free row) merges into one record. Furthest-wins: the larger
     * position wins; on a tie the newer update wins. The local record keeps its own
     * site/id/line — only progress/time (and a richer title/poster when local lacks
     * one) is taken from remote. A remote record with no local match becomes a
     * source-free local record (site "" / id ""). Returns the merged list.
     */
    static List<WatchHistory.Entry> mergeHistory(
            List<WatchHistory.Entry> local, List<WatchHistory.Entry> remote) {
        Map<String, WatchHistory.Entry> map = new HashMap<>();
        for (WatchHistory.Entry e : local) {
            String k = mergeKey(e);
            WatchHistory.Entry cur = map.get(k);
            if (cur == null
                    || e.position > cur.position
                    || (e.position == cur.position && e.updated > cur.updated)) {
                map.put(k, e);
            }
        }
        for (WatchHistory.Entry r : remote) {
            if (r.title == null || r.title.trim().isEmpty()) continue;
            String k = mergeKey(r);
            WatchHistory.Entry cur = map.get(k);
            if (cur == null) {
                WatchHistory.Entry nr = new WatchHistory.Entry();
                nr.site = "";
                nr.id = "";
                nr.title = r.title;
                nr.poster = r.poster;
                nr.line = "";
                nr.episode = r.episode;
                nr.index = 0;
                nr.position = r.position;
                nr.duration = r.duration;
                nr.updated = r.updated;
                map.put(k, nr);
            } else if (r.position > cur.position
                    || (r.position == cur.position && r.updated > cur.updated)) {
                // Remote is further (or same spot but newer): take its progress/time
                // only — local site/id/line stay untouched.
                if ((cur.title == null || cur.title.isEmpty()) && r.title != null)
                    cur.title = r.title;
                if ((cur.poster == null || cur.poster.isEmpty()) && r.poster != null)
                    cur.poster = r.poster;
                cur.position = r.position;
                cur.duration = r.duration;
                cur.updated = r.updated;
            }
        }
        List<WatchHistory.Entry> merged = new ArrayList<>(map.values());
        merged.sort((x, y) -> Long.compare(y.updated, x.updated));
        return merged;
    }

    // ---------- watchlist ----------

    private static JSONObject watchlistRow(String uid, Favorites.Entry e) throws Exception {
        return new JSONObject()
                .put("user_id", uid)
                .put("site", e.site)
                .put("video_id", e.id)
                .put("title", e.title == null ? "" : e.title)
                .put("poster", e.poster == null ? "" : e.poster)
                .put("updated_at", new java.text.SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                        .format(new java.util.Date(System.currentTimeMillis())));
    }

    private static void uploadWatchlist(Context c, String uid, List<Favorites.Entry> local)
            throws Exception {
        if (local.isEmpty()) return;
        JSONArray rows = new JSONArray();
        for (Favorites.Entry e : local) rows.put(watchlistRow(uid, e));
        request(
                c,
                "POST",
                WATCHLIST_TABLE + "?on_conflict=user_id,site,video_id",
                rows.toString(),
                true);
    }

    private static List<Favorites.Entry> downloadWatchlist(Context c) throws Exception {
        String resp =
                request(
                        c,
                        "GET",
                        WATCHLIST_TABLE + "?select=*&order=updated_at.desc&limit=500",
                        null,
                        true);
        List<Favorites.Entry> out = new ArrayList<>();
        JSONArray a = new JSONArray(resp);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Favorites.Entry e = new Favorites.Entry();
            e.site = o.optString("site");
            e.id = o.optString("video_id");
            e.title = o.optString("title");
            e.poster = o.optString("poster");
            if (!e.site.isEmpty() && !e.id.isEmpty()) out.add(e);
        }
        return out;
    }

    static List<Favorites.Entry> mergeWatchlist(
            List<Favorites.Entry> local, List<Favorites.Entry> remote) {
        Map<String, Favorites.Entry> map = new HashMap<>();
        for (Favorites.Entry e : local) map.put(e.key(), e);
        for (Favorites.Entry r : remote) map.putIfAbsent(r.key(), r);
        return new ArrayList<>(map.values());
    }

    // ---------- settings (small subset, last-write-wins per key) ----------

    private static final String[] SYNC_SETTING_KEYS = {
        Prefs.PLAY_SPEED,
        Prefs.SUBTITLE_SIZE,
        Prefs.UI_SCALE_PERCENT,
        Prefs.DANMAKU_DEFAULT,
        Prefs.AUTO_NEXT,
        Prefs.EPISODES_DESCENDING,
    };

    private static void uploadSettings(Context c, String uid, SharedPreferences prefs)
            throws Exception {
        JSONArray rows = new JSONArray();
        for (String key : SYNC_SETTING_KEYS) {
            if (!prefs.contains(key)) continue;
            Object v = prefs.getAll().get(key);
            rows.put(new JSONObject()
                    .put("user_id", uid)
                    .put("key", "marco_" + key)
                    .put("value", v == null ? JSONObject.NULL : v));
        }
        if (rows.length() == 0) return;
        request(
                c,
                "POST",
                SETTINGS_TABLE + "?on_conflict=user_id,key",
                rows.toString(),
                true);
    }

    private static void downloadSettings(Context c, SharedPreferences prefs) throws Exception {
        String resp =
                request(
                        c,
                        "GET",
                        SETTINGS_TABLE
                                + "?select=key,value&key=like.marco_*&order=updated_at.desc&limit=100",
                        null,
                        true);
        JSONArray a = new JSONArray(resp);
        if (a.length() == 0) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String key = o.optString("key");
            if (!key.startsWith("marco_")) continue;
            String realKey = key.substring("marco_".length());
            Object v = o.opt("value");
            if (v == null || v == JSONObject.NULL) continue;
            if (v instanceof Number) {
                // Distinguish int vs float by the stored value.
                double d = ((Number) v).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) ed.putInt(realKey, (int) d);
                else ed.putFloat(realKey, (float) d);
            } else if (v instanceof Boolean) {
                ed.putBoolean(realKey, (Boolean) v);
            } else {
                ed.putString(realKey, String.valueOf(v));
            }
        }
        ed.apply();
    }

    // ---------- local read/write ----------

    static List<WatchHistory.Entry> readLocalHistory(Context c) {
        SharedPreferences p = SupabaseAuth.prefs(c);
        return afterClear(c, WatchHistory.read(p.getString(Prefs.WATCH_HISTORY, "[]")));
    }

    static List<Favorites.Entry> readLocalWatchlist(Context c) {
        SharedPreferences p = SupabaseAuth.prefs(c);
        return Favorites.read(p.getString(Prefs.FAVORITES, "[]"));
    }

    private static void writeLocalHistory(Context c, List<WatchHistory.Entry> list) {
        synchronized (HISTORY_LOCK) {
        list = afterClear(c, list);
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(WatchHistory.LIMIT, list.size()); i++) {
            try {
                a.put(list.get(i).json());
            } catch (Exception ignored) {
            }
        }
        applyingRemote = true;
        try {
            SupabaseAuth.prefs(c).edit().putString(Prefs.WATCH_HISTORY, a.toString()).apply();
        } finally {
            applyingRemote = false;
        }
        }
    }

    private static void writeLocalWatchlist(Context c, List<Favorites.Entry> list) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(Favorites.LIMIT, list.size()); i++) {
            try {
                a.put(list.get(i).json());
            } catch (Exception ignored) {
            }
        }
        applyingRemote = true;
        try {
            SupabaseAuth.prefs(c).edit().putString(Prefs.FAVORITES, a.toString()).apply();
        } finally {
            applyingRemote = false;
        }
    }

    // ---------- public sync entry points (background thread) ----------

    /** Full sync: download + merge, then upload the merged state. Returns a short status message. */
    static String syncAll(Context c) {
        if (!SupabaseAuth.isSignedIn(c)) return "尚未登入";
        String uid = SupabaseAuth.userId(c);
        if (uid == null) return "尚未登入";
        try {
            SharedPreferences prefs = SupabaseAuth.prefs(c);
            // Download + merge.
            flushHistoryClear(c, uid);
            List<WatchHistory.Entry> remoteHistory = downloadHistory(c);
            List<WatchHistory.Entry> mergedHistory = mergeRemoteHistory(c, remoteHistory);
            List<Favorites.Entry> remoteList = downloadWatchlist(c);
            List<Favorites.Entry> mergedList = mergeWatchlist(readLocalWatchlist(c), remoteList);
            writeLocalWatchlist(c, mergedList);
            // 2.0.43: a full sync also counts as a fresh pull for the foreground throttle.
            prefs.edit().putLong("supabase_last_pull_ms", System.currentTimeMillis()).apply();
            try {
                downloadSettings(c, prefs);
            } catch (Exception e) {
                AppLog.w("SupabaseSync", "settings download failed", e);
            }
            // Upload merged state.
            uploadHistory(c, uid, mergedHistory);
            uploadWatchlist(c, uid, mergedList);
            try {
                uploadSettings(c, uid, prefs);
            } catch (Exception e) {
                AppLog.w("SupabaseSync", "settings upload failed", e);
            }
            prefs.edit().putLong("supabase_last_sync_ms", System.currentTimeMillis()).apply();
            return "同步完成";
        } catch (Exception e) {
            AppLog.w("SupabaseSync", "sync failed", e);
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // Missing table (PostgREST 404 + 42P01, or "relation ... does not exist").
            if (msg.contains("42P01")
                    || msg.contains("does not exist")
                    || (msg.contains("HTTP 404") && msg.contains("mkoai_marco")))
                return "同步失敗：請先在 Supabase 執行更新後的 SQL";
            if (msg.contains("401") || msg.contains("not signed in"))
                return "同步失敗：登入已過期，請重新登入";
            if (msg.contains("UnknownHost")
                    || msg.contains("timeout")
                    || msg.contains("Timeout")
                    || msg.contains("ConnectException")
                    || msg.contains("Network"))
                return "同步失敗：連不上 Supabase，請檢查網路後重試";
            return "同步失敗，請檢查網路後重試";
        }
    }

    private static void toast(Activity a, String msg) {
        try {
            android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    /** Run a full sync on a worker thread, toast the result. */
    static void syncNow(Activity a) {
        if (syncing) return;
        if (!SupabaseAuth.isSignedIn(a)) {
            toast(a, "請先登入帳號");
            return;
        }
        syncing = true;
        toast(a, "正在同步…");
        new Thread(
                        () -> {
                            String result = syncAll(a.getApplicationContext());
                            syncing = false;
                            MAIN.post(() -> toast(a, result));
                        })
                .start();
    }

    /** Auto-download on app start (merge remote into local, no upload yet). */
    static void downloadOnStart(Context c) {
        if (!SupabaseAuth.isSignedIn(c) || syncing) return;
        Context app = c.getApplicationContext();
        new Thread(
                        () -> {
                            try {
                                if (SupabaseAuth.userId(app) == null) return;
                                downloadAndMerge(app);
                            } catch (Exception e) {
                                AppLog.w("SupabaseSync", "start download failed", e);
                            }
                        })
                .start();
    }

    /**
     * 2.0.43: auto-download when returning to the foreground. Throttled to at most once
     * per 60s (via supabase_last_pull_ms) so rapid resume cycles never hammer the
     * server; the caller (MainActivity.onResume) also skips it while video is
     * playing. Runs on a worker thread and never touches playback.
     */
    static void downloadOnResume(Context c) {
        if (!SupabaseAuth.isSignedIn(c) || syncing) return;
        SharedPreferences prefs = SupabaseAuth.prefs(c);
        long lastPull = prefs.getLong("supabase_last_pull_ms", 0);
        if (System.currentTimeMillis() - lastPull < 60000) return;
        prefs.edit().putLong("supabase_last_pull_ms", System.currentTimeMillis()).apply();
        Context app = c.getApplicationContext();
        new Thread(
                        () -> {
                            try {
                                if (SupabaseAuth.userId(app) == null) return;
                                downloadAndMerge(app);
                            } catch (Exception e) {
                                AppLog.w("SupabaseSync", "resume download failed", e);
                            }
                        })
                .start();
    }

    /**
     * Download remote history/watchlist and merge into local (no upload). Shared by
     * downloadOnStart and downloadOnResume. Records the pull time so the foreground
     * throttle knows a fresh download just happened.
     */
    private static void downloadAndMerge(Context c) throws Exception {
        String uid = SupabaseAuth.userId(c);
        if (uid != null) flushHistoryClear(c, uid);
        List<WatchHistory.Entry> remote = downloadHistory(c);
        mergeRemoteHistory(c, remote);
        List<Favorites.Entry> remoteList = downloadWatchlist(c);
        List<Favorites.Entry> mergedList = mergeWatchlist(readLocalWatchlist(c), remoteList);
        writeLocalWatchlist(c, mergedList);
        SupabaseAuth.prefs(c).edit().putLong("supabase_last_pull_ms", System.currentTimeMillis()).apply();
    }

    /** Debounced auto-upload after a local history/favorite change (3s). Call from the UI thread. */
    static void scheduleUpload(Context c) {
        if (!SupabaseAuth.isSignedIn(c) || applyingRemote) return;
        if (pendingUpload != null) DEBOUNCE.removeCallbacks(pendingUpload);
        pendingUpload =
                () -> {
                    if (syncing) return;
                    syncing = true;
                    new Thread(
                                    () -> {
                                        try {
                                            String uid = SupabaseAuth.userId(c);
                                            if (uid == null) return;
                                            Context app = c.getApplicationContext();
                                            uploadHistory(app, uid, readLocalHistory(app));
                                            uploadWatchlist(app, uid, readLocalWatchlist(app));
                                        } catch (Exception e) {
                                            AppLog.w("SupabaseSync", "auto upload failed", e);
                                        } finally {
                                            syncing = false;
                                        }
                                    })
                            .start();
                };
        DEBOUNCE.postDelayed(pendingUpload, 3000);
    }

    static String lastSyncText(Context c) {
        long ms = SupabaseAuth.prefs(c).getLong("supabase_last_sync_ms", 0);
        if (ms == 0) return "尚未同步";
        long mins = (System.currentTimeMillis() - ms) / 60000;
        if (mins < 1) return "剛剛同步";
        if (mins < 60) return mins + " 分鐘前同步";
        long hours = mins / 60;
        if (hours < 24) return hours + " 小時前同步";
        return (hours / 24) + " 天前同步";
    }
}
