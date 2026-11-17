package app.mkoai.player;

import java.util.ArrayList;
import java.util.List;

/**
 * mkoai 2.0.76: 推薦優先地區 — one region pick for dramas (劇） and one
 * for movies (電影）, set on the search page below 最近搜尋 (one UI for
 * phone + TV). The picks are region codes ("" = 不限， "TW" / "CN" /
 * "KR" / "JP" / "US") stored in Prefs (RECOMMEND_DRAMA_REGION /
 * RECOMMEND_MOVIE_REGION, local per device).
 *
 * reorder() is a stable partition, never a filter: titles whose kind
 * matches their pick's region move to the front (relative order kept);
 * everything else keeps its relative order behind them. A title with
 * an unknown/missing area, an unclassifiable kind, or a blank/unknown
 * pick never moves — and nothing is ever dropped. The region of a title
 * comes from AwwratedHome.regionOf (2.0.76 adds the 日本 → JP branch
 * there; JP behaves exactly like OTHER in the home filter's
 * isPresent / platformPasses / isAnyRegion, so the branch changes no
 * home-filter behaviour). Pure Java (no Android imports) so the
 * plain-JVM checks can run it.
 */
final class RecommendRegion2076 {
    /** Prefs values, in UI order. */
    static final String[] REGION_CODES = {"", "TW", "CN", "KR", "JP", "US"};
    static final String[] REGION_LABELS = {"不限", "台灣", "中國", "韓國", "日本", "美國"};

    static String labelFor(String code) {
        if (code == null) return REGION_LABELS[0];
        for (int i = 0; i < REGION_CODES.length; i++) {
            if (REGION_CODES[i].equals(code)) return REGION_LABELS[i];
        }
        return REGION_LABELS[0];
    }

    static boolean isRegionCode(String code) {
        if (code == null || code.isEmpty()) return false;
        for (String c : REGION_CODES) {
            if (c.equals(code)) return true;
        }
        return false;
    }

    /**
     * Stable reorder of an already-filtered recommendation list (new
     * list; null in → empty out). Both picks blank → the input order
     * unchanged (new list). A pick that is not a known code is treated
     * as blank (fail-open).
     */
    static List<Cms.Video> reorder(List<Cms.Video> in, String dramaRegion, String movieRegion) {
        List<Cms.Video> out = new ArrayList<>();
        if (in == null) return out;
        String drama = isRegionCode(dramaRegion) ? dramaRegion : "";
        String movie = isRegionCode(movieRegion) ? movieRegion : "";
        if (drama.isEmpty() && movie.isEmpty()) {
            out.addAll(in);
            return out;
        }
        List<Cms.Video> front = new ArrayList<>();
        List<Cms.Video> rest = new ArrayList<>();
        for (Cms.Video v : in) {
            if (v != null && matches(v, drama, movie)) front.add(v);
            else rest.add(v);
        }
        out.addAll(front);
        out.addAll(rest);
        return out;
    }

    private static boolean matches(Cms.Video v, String dramaRegion, String movieRegion) {
        int kind = VideoKind2061.kindOf(v);
        String region = AwwratedHome.regionOf(v.area);
        if (kind == VideoKind2061.SERIES) {
            return !dramaRegion.isEmpty() && dramaRegion.equals(region);
        }
        if (kind == VideoKind2061.MOVIE) {
            return !movieRegion.isEmpty() && movieRegion.equals(region);
        }
        return false;
    }
}
