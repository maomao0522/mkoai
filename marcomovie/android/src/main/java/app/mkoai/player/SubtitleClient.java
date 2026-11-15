package app.mkoai.player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: subtitle search/download client, through the Cloudflare Worker. Pure logic, no
 * UI. All network goes through {@link WorkerApi}; no API key lives in the app.
 *
 * <p>Search: GET /api/subtitles/search?q=&type=movie|tv&season=&episode=&languages=ZH_BG,EN →
 * 200 {@code {source, query, providers_tried, providers_missing, cached, generated_at,
 * results:[...]}}. SubDL item: {@code {provider:"subdl", name, release_name, language, format, hi,
 * season, episode, url:"https://dl.subdl.com/...", is_pack, unpack_files:[{name, language, format,
 * season, episode, url}]}} — the url is always an absolute dl.subdl.com address; when
 * {@code is_pack=true} the whole-season pack must be replaced by the single-episode files in
 * {@code unpack_files}. OpenSubtitles item: {@code {provider:"opensubtitles", name, language,
 * file_id, download_count, files:[{file_id, file_name}]}} — no direct url; download needs the
 * {@code file_id}. Both providers failing still returns 200 with empty results (no 502).
 *
 * <p>Download: GET /api/subtitles/download?provider=subdl&url=.. |
 * provider=opensubtitles&file_id=.. → success is plain WebVTT ({@code text/vtt}, starts with
 * {@code WEBVTT}); failure is JSON: 415 {@code {error:"subtitle_is_archive"}} (zip pack), 415
 * {@code {error:"subtitle_format_unsupported"}} (ASS / non-SRT), 502 subtitle_download_failed.
 */
public final class SubtitleClient {

    /** One subtitle hit. */
    public static final class SubtitleResult {
        public String provider;
        public String name;
        public String language;
        public String format;
        public String url; // subdl only; empty for opensubtitles (use fileId)
        public String fileId; // opensubtitles only
        public int season;
        public int episode;
        public boolean isPack;
    }

    private SubtitleClient() {}

    /**
     * Search subtitles for a title. {@code type} is "movie" or "tv"; season/episode are only sent
     * when type is "tv" and the value is > 0. Languages are always "ZH_BG,EN" (SubDL Traditional
     * Chinese is Big5, code ZH_BG). Results are sorted with ZH_BG / zh-TW first, then EN, the rest
     * in their original (stable) order. Throws {@link IOException} on network failure; an empty
     * result list means "no subtitles found", not an error.
     */
    public static List<SubtitleResult> search(
            String title, String type, int season, int episode) throws IOException {
        if (title == null) title = "";
        if (type == null || (!"movie".equals(type) && !"tv".equals(type))) type = "movie";
        StringBuilder path =
                new StringBuilder("/api/subtitles/search?q=")
                        .append(WorkerApi.encode(title))
                        .append("&type=")
                        .append(type);
        if ("tv".equals(type)) {
            if (season > 0) path.append("&season=").append(season);
            if (episode > 0) path.append("&episode=").append(episode);
        }
        path.append("&languages=ZH_BG,EN");

        JSONObject envelope = WorkerApi.getJson(path.toString());
        JSONArray results = envelope == null ? null : envelope.optJSONArray("results");
        return parseResults(results);
    }

    /**
     * mkoai 2.0.62: turn one /api/subtitles/search {@code results} array
     * into sorted hits. Pure (no network), so the JVM checks can feed it
     * canned SubDL (incl. an is_pack with unpack_files) and OpenSubtitles
     * payloads. Rules: SubDL packs expand to their unpack_files (the pack
     * itself is dropped); OpenSubtitles uses file_id (or files[0].file_id);
     * anything without a downloadable url/file_id is skipped; results are
     * stable-sorted ZH_BG/zh-TW first, then EN, the rest in original order.
     */
    static List<SubtitleResult> parseResults(JSONArray results) {
        List<SubtitleResult> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String provider = item.optString("provider", null);
            if ("subdl".equals(provider)) {
                if (item.optBoolean("is_pack", false)) {
                    JSONArray files = item.optJSONArray("unpack_files");
                    if (files != null) {
                        for (int j = 0; j < files.length(); j++) {
                            JSONObject file = files.optJSONObject(j);
                            if (file == null) continue;
                            SubtitleResult r = new SubtitleResult();
                            r.provider = "subdl";
                            r.name = file.optString("name", item.optString("name", null));
                            r.language =
                                    file.optString(
                                            "language", item.optString("language", null));
                            r.format =
                                    file.optString("format", item.optString("format", null));
                            r.url = file.optString("url", null);
                            r.fileId = null;
                            r.season = file.optInt("season", item.optInt("season", 0));
                            r.episode = file.optInt("episode", item.optInt("episode", 0));
                            r.isPack = false;
                            if (r.url == null || r.url.isEmpty()) continue;
                            out.add(r);
                        }
                    }
                } else {
                    SubtitleResult r = new SubtitleResult();
                    r.provider = "subdl";
                    r.name = item.optString("name", null);
                    r.language = item.optString("language", null);
                    r.format = item.optString("format", null);
                    r.url = item.optString("url", null);
                    r.fileId = null;
                    r.season = item.optInt("season", 0);
                    r.episode = item.optInt("episode", 0);
                    r.isPack = false;
                    if (r.url == null || r.url.isEmpty()) continue;
                    out.add(r);
                }
            } else if ("opensubtitles".equals(provider)) {
                SubtitleResult r = new SubtitleResult();
                r.provider = "opensubtitles";
                r.name = item.optString("name", null);
                r.language = item.optString("language", null);
                r.format = item.optString("format", null);
                r.url = null;
                r.fileId = item.optString("file_id", null);
                if (r.fileId == null || r.fileId.isEmpty()) {
                    JSONArray files = item.optJSONArray("files");
                    if (files != null && files.length() > 0) {
                        JSONObject first = files.optJSONObject(0);
                        if (first != null) r.fileId = first.optString("file_id", null);
                    }
                }
                r.season = item.optInt("season", 0);
                r.episode = item.optInt("episode", 0);
                r.isPack = false;
                if (r.fileId == null || r.fileId.isEmpty()) continue;
                out.add(r);
            }
        }
        // Stable sort: ZH_BG / zh-TW first, then EN, the rest keep original order.
        Collections.sort(
                out,
                new Comparator<SubtitleResult>() {
                    @Override
                    public int compare(SubtitleResult a, SubtitleResult b) {
                        return Integer.compare(langRank(a.language), langRank(b.language));
                    }
                });
        return out;
    }

    private static int langRank(String language) {
        if (language == null) return 2;
        String lang = language.trim();
        if ("ZH_BG".equalsIgnoreCase(lang) || "zh-TW".equalsIgnoreCase(lang)) return 0;
        if ("EN".equalsIgnoreCase(lang) || "en".equalsIgnoreCase(lang)) return 1;
        return 2;
    }

    /**
     * Download one subtitle as WebVTT text. SubDL: the search-returned url is passed straight
     * through to the endpoint — never append any credential to it (the url never carries one).
     * OpenSubtitles: downloads by file_id. Success returns the VTT text; 415/502 throw
     * {@link IOException} whose message is the returned JSON {@code error} field (e.g.
     * subtitle_is_archive / subtitle_format_unsupported).
     */
    public static String downloadVtt(SubtitleResult r) throws IOException {
        if (r == null) throw new IOException("subtitle_download_failed");
        String path;
        if ("subdl".equals(r.provider)) {
            if (r.url == null || r.url.isEmpty()) throw new IOException("subtitle_download_failed");
            path = "/api/subtitles/download?provider=subdl&url=" + WorkerApi.encode(r.url);
        } else if ("opensubtitles".equals(r.provider)) {
            if (r.fileId == null || r.fileId.isEmpty()) {
                throw new IOException("subtitle_download_failed");
            }
            path =
                    "/api/subtitles/download?provider=opensubtitles&file_id="
                            + WorkerApi.encode(r.fileId);
        } else {
            throw new IOException("subtitle_download_failed");
        }
        String text = WorkerApi.getText(path);
        if (text == null) throw new IOException("subtitle_download_failed");
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            // An error payload came back as text: surface its error field.
            try {
                String error = new JSONObject(trimmed).optString("error", null);
                if (error != null && !error.isEmpty()) throw new IOException(error);
            } catch (IOException e) {
                throw e;
            } catch (Exception ignored) {
                // Not parseable JSON after all — fall through and return the text.
            }
        }
        return text;
    }
}
