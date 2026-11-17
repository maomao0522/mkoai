package app.mkoai.player;

import android.app.UiModeManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2.0.77: Google TV "Continue Watching" (Watch Next) row.
 *
 * When the user watches on the TV, the in-progress title is published to
 * the TV launcher's Continue Watching row (TvContract.WatchNextPrograms),
 * so the launcher shows an mkoai card next to the iQIYI / YouTube cards.
 * Tapping the card fires mkoai://watch-next?key=... back into
 * MainActivity, which looks the history entry up by key and resumes it
 * through the same path as the in-app Continue Watching row
 * (CatalogScreen.resumeHistory). A finished title, or one removed from
 * the watch history, has its row deleted; clearing the whole history
 * deletes all of our rows.
 *
 * The provider holds only OUR rows (INTERNAL_PROVIDER_ID "mkoai"); we
 * never touch another app's rows. Everything here is fail-soft: any
 * provider error is logged and playback continues (the launcher row is
 * a bonus, never a dependency). Publishing happens only on TV devices
 * (UiModeManager UI_MODE_TYPE_TELEVISION) — phones have no Watch Next
 * provider and skip all of this.
 *
 * Pure helpers (contentIdFor / intentUriFor / keyFromIntentUri /
 * shouldPublish / rowFor) use only inlined TvContract constants, so
 * they run on the plain JVM under WatchNext2077Checks. The provider
 * calls (upsert / remove / removeAll / reconcile) are Android-only and
 * are pinned by static checks, not by JVM tests.
 */
final class WatchNext2077 {
    /** Our rows in the provider, so a clear-all deletes only ours. */
    static final String PROVIDER_ID = "mkoai";
    static final String SCHEME = "mkoai";
    static final String HOST = "watch-next";
    /** The launcher row shows the most recent in-progress titles only. */
    static final int MAX_ROWS = 10;
    /** Reconcile (heal phone-synced progress onto the TV row) at most this often. */
    static final long RECONCILE_INTERVAL_MS = 10 * 60 * 1000L;
    static long lastReconcileMs = 0;

    // ---- pure helpers (JVM-testable) ----

    /** Stable provider id for one history entry. The key is unique per title+episode. */
    static String contentIdFor(WatchHistory.Entry e) {
        return PROVIDER_ID + ":" + e.key();
    }

    /** Percent-encode a history key for the intent URI (UTF-8, RFC 3986 unreserved kept). */
    static String percentEncode(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xff;
            if ((v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z')
                    || (v >= '0' && v <= '9') || v == '-' || v == '.' || v == '_' || v == '~') {
                out.append((char) v);
            } else {
                out.append('%');
                out.append(Character.forDigit((v >> 4) & 0xf, 16));
                out.append(Character.forDigit(v & 0xf, 16));
            }
        }
        return out.toString();
    }

    static String percentDecode(String s) {
        if (s == null) return "";
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[raw.length];
        int n = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                int hi = Character.digit((char) raw[i + 1], 16);
                int lo = Character.digit((char) raw[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    buf[n++] = (byte) ((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            buf[n++] = raw[i];
        }
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }

    /** Deep link the launcher fires when the user taps our card. */
    static String intentUriFor(WatchHistory.Entry e) {
        return SCHEME + "://" + HOST + "?key=" + percentEncode(e.key());
    }

    /** The history key carried by a watch-next deep link, or "" when it is not one. */
    static String keyFromIntentUri(String uri) {
        if (uri == null) return "";
        int q = uri.indexOf("?key=");
        if (q < 0) q = uri.indexOf("&key=");
        if (q < 0) return "";
        int start = q + 5;
        int end = uri.indexOf('&', start);
        if (end < 0) end = uri.length();
        if (start >= end) return "";
        return percentDecode(uri.substring(start, end));
    }

    /** A title is worth a launcher card only while it is really in progress. */
    static boolean shouldPublish(WatchHistory.Entry e) {
        if (e == null) return false;
        if (e.title == null || e.title.isEmpty()) return false;
        if (e.duration <= 0) return false;
        if (e.resume() <= 0) return false;
        return !e.complete();
    }

    /** Movie (no episode) vs TV episode — the launcher only uses this for its icon treatment. */
    static int typeFor(WatchHistory.Entry e) {
        return (e.episode == null || e.episode.isEmpty())
                ? TvContract.WatchNextPrograms.TYPE_MOVIE
                : TvContract.WatchNextPrograms.TYPE_TV_EPISODE;
    }

    /**
     * The provider row for one entry, as plain values (column name -> value).
     * The launcher reads TITLE, POSTER_ART_URI, the two millis columns and
     * INTENT_URI; the rest identify and order the row.
     */
    static Map<String, Object> rowFor(WatchHistory.Entry e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(TvContract.WatchNextPrograms.COLUMN_CONTENT_ID, contentIdFor(e));
        row.put(TvContract.WatchNextPrograms.COLUMN_TITLE, e.title);
        row.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, e.episode);
        row.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, e.poster);
        row.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                TvContract.WatchNextPrograms.ASPECT_RATIO_2_3);
        row.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (long) e.duration);
        row.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                (long) e.resume());
        row.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                e.updated);
        row.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentUriFor(e));
        row.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, PROVIDER_ID);
        row.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
        row.put(TvContract.WatchNextPrograms.COLUMN_TYPE, typeFor(e));
        row.put(TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1);
        return row;
    }

    // ---- Android layer (TV only, fail-soft) ----

    static boolean isTv(Context c) {
        try {
            UiModeManager ui =
                    (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            return ui != null
                    && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Publish the card, or delete it when the title is finished / never started. */
    static void publishOrRemove(Context c, WatchHistory.Entry e) {
        if (c == null || !isTv(c) || e == null) return;
        try {
            if (shouldPublish(e)) upsert(c, e);
            else remove(c, e);
        } catch (Exception error) {
            AppLog.w("WatchNext2077", "publishOrRemove failed", error);
        }
    }

    static void upsert(Context c, WatchHistory.Entry e) {
        if (c == null || !isTv(c) || e == null) return;
        try {
            ContentValues values = toContentValues(rowFor(e));
            ContentResolver resolver = c.getContentResolver();
            String id = contentIdFor(e);
            Uri existing = findRowUri(resolver, id);
            if (existing != null) {
                resolver.update(existing, values, null, null);
            } else {
                resolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values);
            }
        } catch (Exception error) {
            AppLog.w("WatchNext2077", "upsert failed", error);
        }
    }

    static void remove(Context c, WatchHistory.Entry e) {
        if (c == null || !isTv(c) || e == null) return;
        try {
            c.getContentResolver()
                    .delete(
                            TvContract.WatchNextPrograms.CONTENT_URI,
                            TvContract.WatchNextPrograms.COLUMN_CONTENT_ID + "=?",
                            new String[] {contentIdFor(e)});
        } catch (Exception error) {
            AppLog.w("WatchNext2077", "remove failed", error);
        }
    }

    /** Clearing the whole watch history takes all of our launcher cards with it. */
    static void removeAll(Context c) {
        if (c == null || !isTv(c)) return;
        try {
            c.getContentResolver()
                    .delete(
                            TvContract.WatchNextPrograms.CONTENT_URI,
                            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID + "=?",
                            new String[] {PROVIDER_ID});
        } catch (Exception error) {
            AppLog.w("WatchNext2077", "removeAll failed", error);
        }
    }

    /**
     * Heal the launcher row from the stored history: progress synced in
     * from the phone shows up on the TV row too, and rows for finished /
     * removed titles go away. Throttled — at most once per
     * RECONCILE_INTERVAL_MS per process.
     */
    static void reconcileIfDue(Context c) {
        if (c == null || !isTv(c)) return;
        long now = System.currentTimeMillis();
        if (now - lastReconcileMs < RECONCILE_INTERVAL_MS) return;
        lastReconcileMs = now;
        try {
            List<WatchHistory.Entry> history =
                    WatchHistory.read(
                            c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                                    .getString(Prefs.WATCH_HISTORY, "[]"));
            List<WatchHistory.Entry> live = new ArrayList<>();
            for (WatchHistory.Entry e : history) {
                if (shouldPublish(e)) live.add(e);
            }
            live.sort((x, y) -> Long.compare(y.updated, x.updated));
            if (live.size() > MAX_ROWS) live = live.subList(0, MAX_ROWS);
            java.util.Set<String> keep = new java.util.HashSet<>();
            for (WatchHistory.Entry e : live) {
                keep.add(contentIdFor(e));
                upsert(c, e);
            }
            ContentResolver resolver = c.getContentResolver();
            Cursor cursor = null;
            try {
                cursor =
                        resolver.query(
                                TvContract.WatchNextPrograms.CONTENT_URI,
                                new String[] {
                                    TvContract.WatchNextPrograms.COLUMN_CONTENT_ID
                                },
                                TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                                        + "=?",
                                new String[] {PROVIDER_ID},
                                null);
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String id = cursor.getString(0);
                        if (id != null && !keep.contains(id)) {
                            resolver.delete(
                                    TvContract.WatchNextPrograms.CONTENT_URI,
                                    TvContract.WatchNextPrograms.COLUMN_CONTENT_ID
                                            + "=?",
                                    new String[] {id});
                        }
                    }
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        } catch (Exception error) {
            AppLog.w("WatchNext2077", "reconcile failed", error);
        }
    }

    private static Uri findRowUri(ContentResolver resolver, String contentId) {
        Cursor cursor = null;
        try {
            cursor =
                    resolver.query(
                            TvContract.WatchNextPrograms.CONTENT_URI,
                            new String[] {TvContract.BaseTvColumns._ID},
                            TvContract.WatchNextPrograms.COLUMN_CONTENT_ID + "=?",
                            new String[] {contentId},
                            null);
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(
                        TvContract.WatchNextPrograms.CONTENT_URI, cursor.getLong(0));
            }
            return null;
        } catch (Exception error) {
            AppLog.w("WatchNext2077", "findRowUri failed", error);
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static ContentValues toContentValues(Map<String, Object> row) {
        ContentValues values = new ContentValues();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof String) values.put(entry.getKey(), (String) v);
            else if (v instanceof Long) values.put(entry.getKey(), (Long) v);
            else if (v instanceof Integer) values.put(entry.getKey(), (Integer) v);
            else if (v != null) values.put(entry.getKey(), String.valueOf(v));
        }
        return values;
    }
}
