package app.mkoai.player;

import android.media.tv.TvContract;
import java.util.Map;

/** JVM checks for the 2.0.77 Google TV Watch Next helpers (pure part only). */
public class WatchNext2077Checks {
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

    static WatchHistory.Entry entry(
            String site, String id, String title, String poster, String episode,
            int position, int duration) {
        WatchHistory.Entry e = new WatchHistory.Entry();
        e.site = site;
        e.id = id;
        e.title = title;
        e.poster = poster;
        e.episode = episode;
        e.position = position;
        e.duration = duration;
        e.updated = 1795000000000L;
        return e;
    }

    public static void main(String[] args) {
        // contentIdFor
        WatchHistory.Entry sourced = entry("巨量", "633544", "艾倫的宇宙", "https://x/p.jpg", "", 1000, 60000);
        check("mkoai:巨量\n633544".equals(WatchNext2077.contentIdFor(sourced)),
                "contentId sourced = mkoai:<site>\\n<id>");
        WatchHistory.Entry sourceless =
                entry("", "", "錦心似玉", "https://x/q.jpg", "第18集", 1000, 60000);
        check(WatchNext2077.contentIdFor(sourceless).startsWith("mkoai:@"),
                "contentId sourceless starts with mkoai:@");
        check(WatchNext2077.contentIdFor(sourceless)
                        .equals("mkoai:@" + WatchHistory.syncKey(sourceless)),
                "contentId sourceless = mkoai:@syncKey");

        // intent URI round-trip (CJK + specials in the key)
        WatchHistory.Entry nasty = entry(
                "subocj速播", "id & 100%+x", "錦心似玉 & 測試+100%", "https://x/n.jpg",
                "第 3 集", 120000, 3600000);
        String uri = WatchNext2077.intentUriFor(nasty);
        check(uri.startsWith("mkoai://watch-next?key="), "intent URI scheme/host");
        check(!uri.contains("\n") && !uri.contains(" ")
                        && uri.indexOf('&', uri.indexOf("?key=") + 5) < 0,
                "intent URI has no raw newline/space/ampersand after ?key=");
        check(nasty.key().equals(WatchNext2077.keyFromIntentUri(uri)),
                "intent URI round-trips the key (CJK + specials)");
        check("".equals(WatchNext2077.keyFromIntentUri(null)), "keyFromIntentUri null -> empty");
        check("".equals(WatchNext2077.keyFromIntentUri("mkoai://login-callback#x")),
                "keyFromIntentUri non-watch-next -> empty");
        check("".equals(WatchNext2077.keyFromIntentUri("mkoai://watch-next")),
                "keyFromIntentUri no query -> empty");

        // percent encode/decode round-trip
        String raw = "巨量\n633544 & 100% + test ~x";
        check(raw.equals(WatchNext2077.percentDecode(WatchNext2077.percentEncode(raw))),
                "percent round-trip (CJK + newline + specials)");
        check("abc-._~XYZ019".equals(WatchNext2077.percentEncode("abc-._~XYZ019")),
                "percent leaves unreserved chars alone");
        check("".equals(WatchNext2077.percentEncode(null)), "percentEncode null -> empty");

        // shouldPublish
        check(!WatchNext2077.shouldPublish(null), "shouldPublish null -> false");
        check(!WatchNext2077.shouldPublish(entry("巨量", "1", "", "https://x/p.jpg", "", 1000, 60000)),
                "shouldPublish empty title -> false");
        check(!WatchNext2077.shouldPublish(entry("巨量", "1", "片名", "https://x/p.jpg", "", 1000, 0)),
                "shouldPublish no duration -> false");
        check(!WatchNext2077.shouldPublish(entry("巨量", "1", "片名", "https://x/p.jpg", "", 0, 60000)),
                "shouldPublish never started -> false");
        check(!WatchNext2077.shouldPublish(entry("巨量", "1", "片名", "https://x/p.jpg", "", 3599000, 3600000)),
                "shouldPublish finished (within 5s of end) -> false");
        check(WatchNext2077.shouldPublish(entry("巨量", "1", "片名", "https://x/p.jpg", "第3集", 1800000, 3600000)),
                "shouldPublish mid-title -> true");
        check(WatchNext2077.shouldPublish(entry("巨量", "1", "片名", "https://x/p.jpg", "", 3594000, 3600000)),
                "shouldPublish just before the finish window -> true");

        // typeFor
        check(WatchNext2077.typeFor(entry("巨量", "1", "電影", "https://x/p.jpg", "", 1000, 60000))
                        == TvContract.WatchNextPrograms.TYPE_MOVIE,
                "typeFor movie (no episode) = TYPE_MOVIE");
        check(WatchNext2077.typeFor(entry("巨量", "1", "劇", "https://x/p.jpg", "第3集", 1000, 60000))
                        == TvContract.WatchNextPrograms.TYPE_TV_EPISODE,
                "typeFor episode = TYPE_TV_EPISODE");

        // rowFor
        WatchHistory.Entry e = entry(
                "巨量", "633544", "錦心似玉", "https://x/poster.jpg", "第18集",
                1200000, 3600000);
        Map<String, Object> row = WatchNext2077.rowFor(e);
        check("錦心似玉".equals(row.get(TvContract.WatchNextPrograms.COLUMN_TITLE)),
                "row title");
        check("https://x/poster.jpg".equals(row.get(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI)),
                "row poster URI");
        check("第18集".equals(row.get(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE)),
                "row episode title");
        check(Long.valueOf(3600000L).equals(row.get(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS)),
                "row duration millis (long)");
        check(Long.valueOf(1200000L).equals(
                row.get(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)),
                "row position = resume()");
        check(Long.valueOf(1795000000000L).equals(
                row.get(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)),
                "row engagement = updated");
        check(WatchNext2077.intentUriFor(e).equals(row.get(TvContract.WatchNextPrograms.COLUMN_INTENT_URI)),
                "row intent URI");
        check("mkoai".equals(row.get(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)),
                "row provider id = mkoai");
        check(Integer.valueOf(TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .equals(row.get(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)),
                "row watch-next type = CONTINUE");
        check(Integer.valueOf(TvContract.WatchNextPrograms.ASPECT_RATIO_2_3)
                .equals(row.get(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO)),
                "row poster aspect = 2:3");
        check(Integer.valueOf(1).equals(row.get(TvContract.WatchNextPrograms.COLUMN_BROWSABLE)),
                "row browsable = 1");
        check(WatchNext2077.contentIdFor(e).equals(row.get(TvContract.WatchNextPrograms.COLUMN_CONTENT_ID)),
                "row content id");
        // a finished entry's row would carry position 0 (resume() == 0) — the
        // publish gate, not rowFor, keeps it out of the provider.
        WatchHistory.Entry done = entry("巨量", "1", "片名", "https://x/p.jpg", "", 3599000, 3600000);
        check(Long.valueOf(0L).equals(
                WatchNext2077.rowFor(done).get(
                        TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)),
                "row position for a finished entry = 0 (resume())");
        // null poster stays null in the row (the provider column is optional).
        WatchHistory.Entry noPoster = entry("巨量", "1", "片名", null, "", 1000, 60000);
        check(WatchNext2077.rowFor(noPoster).get(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI) == null,
                "row poster null stays null");

        System.out.println("\n" + (total - failed) + " WatchNext2077 checks passed");
        if (failed > 0) System.out.println(failed + " FAILED");
    }
}
