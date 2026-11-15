package app.mkoai.player;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai 2.0.62: OMDb ratings/detail client, through the Cloudflare Worker
 * (GET /api/omdb). Pure logic, no UI. All network goes through
 * {@link WorkerApi}; no API key lives in the app.
 *
 * <p>OMDb response shape (Worker): 200
 * {@code {source, mode:"id"|"title", cached, generated_at, data:{Title, Year, Rated, Runtime,
 * Genre, Director, Actors, Plot, Poster, Ratings:[{Source, Value}], Metascore, imdbRating,
 * imdbVotes, imdbID, Type, Response:"True"}}}. A missing rating is omitted from the Ratings
 * array outright (not null); a missing imdbRating is the string "N/A". Not found is 404
 * {@code {error:"omdb_not_found"}}.
 */
public final class OmdbClient {

    private OmdbClient() {}

    /**
     * Fetch the OMDb data object by IMDb id (e.g. "tt3896198"). Returns the {@code data} object on
     * success, or {@code null} on 404 / any failure (never throws).
     */
    public static JSONObject fetchByImdbId(String imdbId) {
        if (imdbId == null || imdbId.isEmpty()) return null;
        try {
            return fetchByImdbIdOrThrow(imdbId);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * mkoai 2.0.66: throwing variant of {@link #fetchByImdbId} — the
     * same fetch, but a Worker quota 429 (or any failure other than
     * 404) is thrown instead of swallowed, so the detail page can
     * record it and hide the ratings area until the daily reset. A 404
     * (no such title) still returns null: "not found" is not a quota
     * failure and must not hide anything.
     */
    public static JSONObject fetchByImdbIdOrThrow(String imdbId) throws IOException {
        if (imdbId == null || imdbId.isEmpty()) return null;
        try {
            JSONObject envelope = WorkerApi.getJson("/api/omdb?id=" + WorkerApi.encode(imdbId));
            return envelope == null ? null : envelope.optJSONObject("data");
        } catch (WorkerApi.HttpError e) {
            if (e.status == 404) return null;
            throw e;
        }
    }

    /**
     * Fetch the OMDb data object by title. Returns the {@code data} object on success, or
     * {@code null} on 404 / any failure (never throws).
     */
    public static JSONObject fetchByTitle(String title) {
        if (title == null || title.isEmpty()) return null;
        try {
            return fetchByTitleOrThrow(title);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * mkoai 2.0.66: throwing variant of {@link #fetchByTitle} — see
     * {@link #fetchByImdbIdOrThrow}.
     */
    public static JSONObject fetchByTitleOrThrow(String title) throws IOException {
        if (title == null || title.isEmpty()) return null;
        try {
            JSONObject envelope = WorkerApi.getJson("/api/omdb?title=" + WorkerApi.encode(title));
            return envelope == null ? null : envelope.optJSONObject("data");
        } catch (WorkerApi.HttpError e) {
            if (e.status == 404) return null;
            throw e;
        }
    }

    /**
     * Look up one rating value inside a {@code data} object: finds the entry in {@code Ratings}
     * whose {@code Source} exactly equals {@code source} and returns its {@code Value}.
     *
     * <p>Fixed source spellings: "Internet Movie Database", "Rotten Tomatoes", "Metacritic".
     * Returns {@code null} when the source is absent or its value is "N/A".
     */
    public static String ratingValue(JSONObject data, String source) {
        if (data == null || source == null) return null;
        JSONArray ratings = data.optJSONArray("Ratings");
        if (ratings == null) return null;
        for (int i = 0; i < ratings.length(); i++) {
            JSONObject entry = ratings.optJSONObject(i);
            if (entry == null) continue;
            if (source.equals(entry.optString("Source", null))) {
                String value = entry.optString("Value", null);
                if (value == null || value.isEmpty() || "N/A".equals(value)) return null;
                return value;
            }
        }
        return null;
    }
}
