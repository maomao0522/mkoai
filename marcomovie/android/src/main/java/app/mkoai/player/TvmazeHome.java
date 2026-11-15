package app.mkoai.player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: TVmaze tonight schedule + show lookup via the Cloudflare
 * Worker (GET /api/tvmaze?list=schedule&country=US&date=… and
 * /api/tvmaze?id=…), plus direct TVmaze episode fetches (no key needed).
 *
 * <p>Sorting / time conversion ALWAYS uses {@code airstamp} (the exact
 * UTC instant). Never {@code airdate}: for late-night shows airdate is
 * a day later than the real air time.
 *
 * <p>All methods are static and blocking — call from a background thread.
 */
final class TvmazeHome {
    private static final int MAX_ITEMS = 20;
    private static final TimeZone TAIPEI = TimeZone.getTimeZone("Asia/Taipei");

    /** One schedule row, kept until it is turned into an Item. */
    private static final class Entry {
        JSONObject show;
        int showId;
        int season;
        int number;
        long instantMs; // airstamp as epoch millis
    }

    /**
     * Tonight's US schedule (date = Taipei today), one card per show
     * (a show with several episodes tonight keeps only its earliest
     * airstamp). Item.airInfo carries "今晚 HH:MM · S{season}E{number}"
     * (or "明天 …" when the Taipei time falls on the next day).
     * Throws on failure.
     */
    public static List<TmdbClient.Item> fetchTonightItems() throws IOException {
        return fetchTonightItems(null);
    }

    /** mkoai 2.0.66: same, recording a quota 429 into {@code quota} (phone home rows). */
    public static List<TmdbClient.Item> fetchTonightItems(QuotaState2066.Store quota)
            throws IOException {
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dayFmt.setTimeZone(TAIPEI);
        Date now = new Date();
        String today = dayFmt.format(now);
        String tomorrow = dayFmt.format(new Date(now.getTime() + 24L * 3600 * 1000));
        JSONObject res;
        try {
            res = WorkerApi.getJson("/api/tvmaze?list=schedule&country=US&date=" + today);
        } catch (WorkerApi.RateLimited limited) {
            // mkoai 2.0.66: 限流就記進 QuotaState 再拋出，首頁在
            // retry_after 內不再重試。
            if (quota != null) {
                QuotaState2066.record(quota, QuotaState2066.TVMAZE,
                        limited.error, limited.source, limited.retryAfterOrNull,
                        System.currentTimeMillis());
            }
            throw limited;
        }
        JSONArray data = res.optJSONArray("data");
        List<Entry> entries = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                JSONObject show = o.optJSONObject("show");
                if (show == null) continue;
                long instant = parseAirstamp(o.optString("airstamp", ""));
                if (instant < 0) continue; // 沒有精確播出時間就無法排序，跳過
                Entry e = new Entry();
                e.show = show;
                e.showId = show.optInt("id", 0);
                e.season = o.optInt("season", 0);
                e.number = o.optInt("number", 0);
                e.instantMs = instant;
                entries.add(e);
            }
        }
        // airstamp 最早在前，再按 show.id 去重（同一劇只留最早一集）。
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(a.instantMs, b.instantMs);
            }
        });
        List<Entry> deduped = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Entry e : entries) {
            if (e.showId != 0 && !seen.add(e.showId)) continue;
            deduped.add(e);
            if (deduped.size() >= MAX_ITEMS) break;
        }
        List<String> titles = new ArrayList<>();
        for (Entry e : deduped) titles.add(e.show.optString("name", "").trim());
        List<String> displayTitles = TitleTranslator.toTraditionalAll(titles);
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
        timeFmt.setTimeZone(TAIPEI);
        SimpleDateFormat monthDayFmt = new SimpleDateFormat("M月d日", Locale.US);
        monthDayFmt.setTimeZone(TAIPEI);
        List<TmdbClient.Item> out = new ArrayList<>();
        for (int i = 0; i < deduped.size(); i++) {
            Entry e = deduped.get(i);
            JSONObject show = e.show;
            String title = displayTitles.get(i);
            if (title == null || title.isEmpty()) continue;
            TmdbClient.Item it = new TmdbClient.Item();
            it.type = "tv";
            it.title = title;
            JSONObject image = show.optJSONObject("image");
            String poster = "";
            if (image != null) {
                poster = image.optString("original", "").trim();
                if (poster.isEmpty()) poster = image.optString("medium", "").trim();
            }
            it.poster = poster;
            String premiered = show.optString("premiered", "").trim();
            it.year = yearOf(premiered);
            JSONObject rating = show.optJSONObject("rating");
            it.vote = rating == null ? 0 : rating.optDouble("average", 0);
            it.date = ""; // 避開 2 天延遲檢查
            it.overview = stripHtml(show.optString("summary", ""));
            it.backdrop = "";
            it.cast = "";
            // 播出資訊：airstamp（UTC）換算成台北時間。
            String taipeiDay = dayFmt.format(new Date(e.instantMs));
            String label;
            if (taipeiDay.equals(today)) {
                label = "今晚";
            } else if (taipeiDay.equals(tomorrow)) {
                label = "明天";
            } else {
                label = monthDayFmt.format(new Date(e.instantMs));
            }
            String airInfo = label + " " + timeFmt.format(new Date(e.instantMs));
            if (e.season > 0 || e.number > 0) {
                airInfo += " · S" + e.season + "E" + e.number;
            }
            it.airInfo = airInfo;
            out.add(it);
        }
        return out;
    }

    /**
     * One show by TVmaze id. Returns the show object inside {@code data},
     * or null when the Worker answers 404. Other failures throw.
     */
    public static JSONObject fetchShow(int tvmazeId) throws IOException {
        try {
            JSONObject res = WorkerApi.getJson("/api/tvmaze?id=" + tvmazeId);
            return res.optJSONObject("data");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) return null;
            throw e;
        }
    }

    /**
     * One episode by its absolute TVmaze URL (e.g.
     * {@code _links.nextepisode.href}). TVmaze needs no key, so this goes
     * direct, not through the Worker. Returns the episode object, or null
     * on any failure (never throws for a bad episode link).
     */
    public static JSONObject fetchEpisode(String url) throws IOException {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            return new JSONObject(readAll(c.getInputStream()));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Show search via the Worker (GET /api/tvmaze?q=…). Returns the first
     * result's show object, or null when there is no result (never throws
     * for an empty result). Other failures throw.
     */
    public static JSONObject searchShow(String title) throws IOException {
        if (title == null || title.trim().isEmpty()) return null;
        JSONObject res = WorkerApi.getJson("/api/tvmaze?q=" + WorkerApi.encode(title.trim()));
        JSONArray data = res.optJSONArray("data");
        if (data == null || data.length() == 0) return null;
        JSONObject first = data.optJSONObject(0);
        if (first == null) return null;
        JSONObject show = first.optJSONObject("show");
        return show != null ? show : first;
    }

    /** Parse an airstamp like "2026-11-15T06:02:00+00:00" to epoch millis.
     * Parsed by hand (no SimpleDateFormat "X" pattern, which needs a
     * newer API level); the offset suffix is applied explicitly. -1 when
     * unparseable. Package-visible so the 2.0.62 JVM checks can feed it
     * canned airstamps (incl. the airdate/airstamp day-lag case). */
    static long parseAirstamp(String s) {
        if (s == null || s.length() < 19) return -1;
        try {
            SimpleDateFormat f =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            long t = f.parse(s.substring(0, 19)).getTime();
            if (s.length() > 19) {
                char sign = s.charAt(19);
                if (sign == 'Z') return t;
                if ((sign == '+' || sign == '-') && s.length() >= 25) {
                    int oh = Integer.parseInt(s.substring(20, 22));
                    int om = Integer.parseInt(s.substring(23, 25));
                    long off = (oh * 3600L + om * 60L) * 1000L;
                    return sign == '+' ? t - off : t + off;
                }
            }
            return t;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** First 4 digits of a yyyy-MM-dd date, or "" when absent. Package-visible for the 2.0.62 JVM checks. */
    static String yearOf(String date) {
        if (date == null || date.length() < 4) return "";
        String y = date.substring(0, 4);
        for (int i = 0; i < 4; i++) {
            if (y.charAt(i) < '0' || y.charAt(i) > '9') return "";
        }
        return y;
    }

    /** Strip HTML tags + common entities from a TVmaze summary. Package-visible for the 2.0.62 JVM checks. */
    static String stripHtml(String s) {
        if (s == null) return "";
        String out = s.replaceAll("<[^>]*>", "");
        out = out.replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        return out.trim();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
