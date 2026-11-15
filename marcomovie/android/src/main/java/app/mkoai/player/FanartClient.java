package app.mkoai.player;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: Fanart.tv artwork client, through the Cloudflare Worker (GET /api/fanart). Pure
 * logic, no UI. All network goes through {@link WorkerApi}; no API key lives in the app.
 *
 * <p>Worker shape: 200 {@code {source, type, id, cached, generated_at, data:{...}}}. Movies keys:
 * moviebackground / movieposter / moviebanner / hdmovielogo / moviethumb. TV keys: tvposter /
 * tvbanner / tvthumb / hdtvlogo / clearart / characterart / seasonposter. Each image is
 * {@code {id, url, lang, likes}}. <b>A missing id returns 200 with an empty {@code data:{}} —
 * that means "no artwork", not success.</b> A genuine upstream 404 is 404 fanart_not_found.
 */
public final class FanartClient {

    private FanartClient() {}

    /**
     * Fetch the artwork {@code data} object for a title. {@code type} is only "movies" or "tv"
     * (movies take a TMDB id, tv takes a TVDB id). Returns {@code null} for an invalid type, an
     * empty {@code data} object (no artwork), or any failure (never throws).
     */
    public static JSONObject fetchArt(String type, String id) {
        if (!"movies".equals(type) && !"tv".equals(type)) return null;
        if (id == null || id.isEmpty()) return null;
        try {
            JSONObject envelope =
                    WorkerApi.getJson(
                            "/api/fanart?type=" + type + "&id=" + WorkerApi.encode(id));
            if (envelope == null) return null;
            JSONObject data = envelope.optJSONObject("data");
            if (data == null || data.length() == 0) return null;
            return data;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Backdrop: moviebackground, then showbackground, then tvbanner. */
    public static String pickBackground(JSONObject data) {
        String url = pickFirst(data, "moviebackground");
        if (url != null) return url;
        url = pickFirst(data, "showbackground");
        if (url != null) return url;
        return pickFirst(data, "tvbanner");
    }

    /** Logo: hdmovielogo, then hdtvlogo, then clearart. */
    public static String pickLogo(JSONObject data) {
        String url = pickFirst(data, "hdmovielogo");
        if (url != null) return url;
        url = pickFirst(data, "hdtvlogo");
        if (url != null) return url;
        return pickFirst(data, "clearart");
    }

    /** Poster: movieposter, then tvposter. */
    public static String pickPoster(JSONObject data) {
        String url = pickFirst(data, "movieposter");
        if (url != null) return url;
        return pickFirst(data, "tvposter");
    }

    /**
     * Pick one URL from an artwork array: first entry with {@code lang == "en"} and a non-empty
     * url, otherwise the first entry with a non-empty url. {@code null} when the key is absent or
     * every url is empty.
     */
    private static String pickFirst(JSONObject data, String key) {
        if (data == null) return null;
        JSONArray arr = data.optJSONArray(key);
        if (arr == null) return null;
        String fallback = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", null);
            if (url == null || url.isEmpty()) continue;
            if (fallback == null) fallback = url;
            if ("en".equals(item.optString("lang", null))) return url;
        }
        return fallback;
    }
}
