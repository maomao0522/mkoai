package app.mkoai.player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * mkoai awwrated home (2.0.26: the home tab is awwrated only). Fetches the
 * awwrated.com/zh-tw/screen page and builds one Cms.Video per title: the
 * correct Taiwan Traditional name (lang.zhtw.title), the TMDB poster, and
 * the awwrated rating. Each card's id is "msearch:&lt;title&gt;", so tapping
 * it runs the normal cross-source search for that title (and auto-plays
 * the best match) — the same pattern as DoubanHome.
 *
 * <p>The page is a Next.js app; the data is embedded in the __NEXT_DATA__
 * JSON (Apollo state), same source AwwratedTitles uses for its en -&gt; zhtw
 * title map. The video list is cached on disk (awwrated-home.json): a home
 * load returns the cache instantly and refreshes the disk copy in the
 * background, so repeat launches are instant and survive offline use.
 * On failure fetchHome returns an empty list (never throws) so the home
 * shows its empty state instead of crashing.
 *
 * <p>2.0.28 home filters (user: home must only show titles the user can
 * actually watch): (1) platform — Netflix (category databaseId 2) or
 * Disney+ (category databaseId 1050, or a non-null disneyPlusId);
 * 2.0.57: a title with no platform is still kept when its region is
 * China or Korea (the region is only known at the source check, so
 * the platform rule is enforced in the region-aware filters, not at
 * parse time); other regions still need a Netflix/Disney+ platform.
 * (2) release delay — a title must be at least 48h old before it
 * appears, so the user's source has time to index it; (3) source
 * availability — only titles the user's own source already carries
 * (title-matched against a live source search, not just "results
 * non-empty": the CMS always returns ~20 loosely-matched rows, so a
 * plain non-empty check would keep everything); (4) region — mainly
 * Taiwan / China / Korea, plus American titles capped at ~25% of the
 * final list. Filters 1+2 run at parse time (live and cache); filters
 * 3+4 run in applySourceFilters against the current source engine.
 * 2.0.33: filters 3+4 are STRICT — a title shows only when it is
 * CONFIRMED present in the source (matched region TW/CN/KR/US); a
 * title that is absent, from another region, or simply unverified
 * (check timed out or failed) is dropped. The only fail-open left is
 * the never-blank safety net: if strict filtering would empty the
 * list, the unfiltered list is kept instead.
 *
 * <p>2.0.32 availability disk cache: applySourceFilters remembers
 * every checked title's region on disk (awwrated-availability-v2.json,
 * keyed by the source's api URL, 24h TTL), so a title confirmed
 * ABSENT for one source stays off the home across launches instead
 * of being re-checked from scratch every time — and a title present
 * on one source never leaks into another source's result. The same
 * disk cache feeds prefilterVisible, which verifies the titles the
 * user will actually see first (hero + Top 10) BEFORE the first
 * render, so an unlisted title never appears even on the very first
 * launch when the disk cache is still empty.
 *
 * <p>2.0.40 cross-source home (2a) + instant first paint (2b):
 * prefilterVisible / applySourceFilters above still check only the
 * current source and are kept as legacy. The home path now uses
 * prefilterVisibleDiskCross (disk-only, no network — the first
 * screen paints immediately from fetchHome + disk, never waiting
 * for live checks) and applySourceFiltersCross (live rounds across
 * all configured sources, current first — a title counts when ANY
 * source confirms it, and the card opens on the confirming source).
 * The strict rule is unchanged in both: only confirmed-present
 * titles survive the refinement, with the never-blank safety net.
 */
final class AwwratedHome {
    private static final String CACHE_FILE = "awwrated-home.json";
    private static volatile boolean refreshing = false;

    // awwrated Post category ids (verified live 2026-11-12: 44 posts carry
    // only id 2, 32 carry only id 1050, 4 collections carry none).
    private static final int CAT_NETFLIX = 2;
    private static final int CAT_DISNEY = 1050;
    private static final String PLATFORM_NETFLIX = "netflix";
    private static final String PLATFORM_DISNEY = "disney+";

    // A title must be at least this old before it appears on the home.
    // mkoai 2.0.45: 24h -> 48h (user rule: give the source two days to
    // index a new release before it shows in a recommendation row).
    private static final long RELEASE_DELAY_MS = 172_800_000L; // 48h

    // Source-availability check (filters 3+4): parallel searches, whole
    // check bounded so a slow source never stalls the home load. Titles
    // whose check does not finish in time are kept (fail open).
    // 2.0.32: 6 threads / 30s so more titles verify per session, and
    // results persist to disk (see AVAIL_FILE) so the next launch
    // reuses them instead of re-checking from scratch.
    private static final int CHECK_THREADS = 6;
    private static final long CHECK_BUDGET_MS = 30_000L;

    // Availability disk cache (2.0.32): top-level JSONObject keyed by
    // source api URL; each source value maps Traditional title ->
    // {"r": region, "ts": epochMillis}. A title confirmed ABSENT (or
    // present) for one source is remembered across launches; entries
    // expire after 24h and are re-checked live.
    // 2.0.42: v2 file — the 2.0.41 file holds loose-match ("prefix counts")
    // records; renaming discards them so every title is re-checked with
    // the strict matcher. Never reuse the v1 filename for v2 records.
    // 2.0.46: v3 file — v2 entries predate the source-poster ("po")
    // field, so a title confirmed before 2.0.45 keeps a blank card
    // until its 24h entry expires (the user saw 《艾倫的宇宙》 blank
    // in every row). Renaming discards them so every title is
    // re-checked once and its source poster is recorded from the start.
    // 2.0.57: bumped to v4 — regionOf now recognises the two-letter
    // region codes (TW/CN/KR/US), so entries cached as OTHER under the
    // old mapping (e.g. a Taiwan title whose source says "TW") are
    // discarded and re-checked instead of staying dropped for 24h.
    private static final String AVAIL_FILE = "awwrated-availability-v4.json";
    private static final long AVAIL_TTL_MS = 86_400_000L; // 24h

    // Pre-paint check (2.0.32, prefilterVisible; 2.0.33 strict): verify
    // the titles the user will see first before the first render. Check
    // in list order until 6 titles are CONFIRMED PRESENT, or 30 titles
    // have been checked, whichever comes first; the whole prefilter is
    // bounded by 5s. Only confirmed-present titles are kept — absent,
    // other-region and unverified (UNKNOWN) titles are all dropped.
    // 2.0.34: KEEP 12 -> 6 and BUDGET 10s -> 5s. The splash now waits at
    // most 1.5s for the preload, and the session home cache (below)
    // makes repeat visits free — the prefilter only needs to cover the
    // hero + the first Top 10 row, not half the page.
    private static final long PREFILTER_BUDGET_MS = 5_000L;
    private static final int PREFILTER_KEEP = 6;
    private static final int PREFILTER_MAX_CHECK = 30;

    // Region codes for the session availability cache. UNKNOWN is package-visible:
    // MobileShell's TMDB row filter reads it (2.0.38).
    private static final String REGION_TW = "TW";
    private static final String REGION_CN = "CN";
    private static final String REGION_KR = "KR";
    private static final String REGION_US = "US";
    private static final String REGION_OTHER = "OTHER";
    static final String REGION_UNKNOWN = "UNKNOWN";
    private static final String REGION_ABSENT = "ABSENT";

    // Session cache: Traditional title -> matched region ("TW"/"CN"/"KR"/
    // "US"/"OTHER"/"UNKNOWN") or "ABSENT" when the source does not carry
    // it. Repeat home visits in the same session skip the network.
    private static final ConcurrentHashMap<String, String> availabilityCache =
            new ConcurrentHashMap<>();

    // Session cache: detail-id cache key -> matched source video id (the
    // real CMS id from the source search). When present, a home card tap
    // opens the detail page directly instead of going through a search
    // ("msearch:"). Filled by checkTitle alongside availabilityCache.
    // 2.0.34: the key is source-keyed (sourceKey + "\n" + title) — the
    // same title has a different real id on every source, and a
    // title-keyed entry could hand a card the wrong source's id.
    private static final ConcurrentHashMap<String, String> detailIdCache =
            new ConcurrentHashMap<>();

    // mkoai 2.0.45: source-poster cache, same keying as detailIdCache
    // (sourceKey + "\n" + title). Filled by checkTitle alongside the
    // detail id, persisted to disk by mergeAndSaveAvail as "po", and
    // read back by posterFor / freshDiskPoster. Lets a TMDB row card
    // show the confirming source's poster when TMDB has none.
    private static final ConcurrentHashMap<String, String> posterCache =
            new ConcurrentHashMap<>();

    /** Remember one title's source poster (session cache). Never throws. */
    static void rememberPoster(String sourceKey, String title, String poster) {
        try {
            if (sourceKey == null || title == null || poster == null || poster.isEmpty()) return;
            posterCache.put(detailKey(sourceKey, title), poster);
        } catch (Throwable ignored) {
        }
    }

    /** A title's source poster (session cache first, then disk), or null. */
    static String posterFor(File filesDir, String sourceKey, String title) {
        if (title == null) return null;
        String poster =
                sourceKey == null
                        ? null
                        : posterCache.get(detailKey(sourceKey, title));
        if (poster != null && !poster.isEmpty()) return poster;
        if (filesDir == null || sourceKey == null || sourceKey.isEmpty()) return null;
        JSONObject disk = loadAvailDisk(filesDir);
        return freshDiskPoster(
                disk.optJSONObject(sourceKey), title, System.currentTimeMillis());
    }

    /** Detail-id cache key: source-scoped so ids never leak across sources. */
    private static String detailKey(String sourceKey, String title) {
        return (sourceKey == null ? "" : sourceKey) + "\n" + (title == null ? "" : title);
    }

    /**
     * mkoai 2.0.46: poster fallback chain for a card, synchronous steps
     * only — (a) TMDB poster, (b) cached confirming-source poster,
     * (d) TMDB backdrop. Step (c), the live source fetch, is async and
     * lives in fetchPosterLive plus the caller's Phase B top-up pass, so
     * this helper never touches the network. Returns null when nothing
     * is available (the caller then leaves the card to the view-level
     * enrichment). Never throws.
     */
    static String resolvePoster(
            File filesDir, String tmdbPoster, String sourceKey, String title, String backdrop) {
        try {
            if (tmdbPoster != null && !tmdbPoster.isEmpty()) return tmdbPoster;
            String cached = posterFor(filesDir, sourceKey, title);
            if (cached != null && !cached.isEmpty()) return cached;
            if (backdrop != null && !backdrop.isEmpty()) return backdrop;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * mkoai 2.0.46: live source-poster fetch for one title — step (c) of
     * the poster fallback chain. Same search + title match as checkTitle,
     * but returns the matched result's poster URL (or null). Never throws.
     * mkoai 2.0.48: delegates to fetchPosterLiveResult; see that method
     * for the absent-vs-transient distinction.
     */
    static String fetchPosterLive(SourceEngine eng, String sourceKey, String title) {
        PosterResult r = fetchPosterLiveResult(eng, sourceKey, title);
        return r == null ? null : r.poster;
    }

    /**
     * mkoai 2.0.48: live source-poster fetch with the outcome spelled
     * out, so the caller can tell "this source definitely has no poster
     * for this title" (search completed, no match) from "the fetch
     * failed transiently" (engine / network / interrupt). Only the
     * first may be remembered as a miss — a transient failure must be
     * retried on the next card build, never poisoned for the whole
     * process (2.0.47's posterLiveMiss recorded every failure,
     * including transient ones, so one slow moment blanked a card
     * until the app was restarted).
     */
    static final class PosterResult {
        final String poster;
        /** True only when the search completed with no match. */
        final boolean absent;

        PosterResult(String poster, boolean absent) {
            this.poster = poster;
            this.absent = absent;
        }
    }

    static PosterResult fetchPosterLiveResult(
            SourceEngine eng, String sourceKey, String title) {
        if (eng == null || title == null) return new PosterResult(null, false);
        try {
            Cms.Video m = findMatch(eng, sourceKey, title);
            if (m == null) return new PosterResult(null, true);
            if ((m.poster == null || m.poster.isEmpty() || ImageLoader.failed(m.poster))
                    && m.id != null && !m.id.isEmpty()) {
                // Some feeds omit artwork from search rows but include it on detail rows.
                // A present title with no artwork is not an absent title.
                for (Cms.Video detail : Cms.listing(eng.detail(m.id)).videos) {
                    if (detail.poster != null && !detail.poster.isEmpty()
                            && !ImageLoader.failed(detail.poster)
                            && (detail.title == null || detail.title.isEmpty() || titlesMatch(title, detail.title))) {
                        rememberPoster(sourceKey, title, detail.poster);
                        return new PosterResult(detail.poster, false);
                    }
                }
            }
            if (m.poster == null || m.poster.isEmpty() || ImageLoader.failed(m.poster)) return new PosterResult(null, false);
            return new PosterResult(m.poster, false);
        } catch (Throwable ignored) {
            return new PosterResult(null, false);
        }
    }

    // ---- mkoai 2.0.49: resolve posters BEFORE the first paint ----
    //
    // Why this exists: 2.0.46/2.0.47/2.0.48 all filled a TMDB-poster-less
    // card's poster AFTER it was drawn (Phase B top-up, view-level
    // maybeEnrichPoster, deliverPoster) — and the user still saw
    // 《艾倫的宇宙》 blank in all three rows on 2.0.48. The async chain
    // has too many silent failure points (artwork-pool queue behind
    // dozens of slow live searches; ImageLoader's generation guards
    // dropping a bitmap load after any navigation; resume()'s
    // rekindlePosters only restarting views whose tag is already a
    // poster URL), and the 2.0.48 data-level gap: a title with a fresh
    // v3 disk entry (region present, no "po" — exactly what 2.0.46/47
    // wrote) takes the disk-hit short-circuit in
    // applySourceFiltersCross, so findMatch never runs, the session
    // poster cache is never filled, and the ONLY remaining poster
    // source is the async maybeEnrichPoster step (c). No disk "absent
    // poster" record exists anywhere (posterAbsent is session-only;
    // cachedAbsentEverywhere needs EVERY source ABSENT, impossible
    // for a title Juliang carries) — the stale-absent hypothesis is
    // false; the real defect is resolve-after-draw.
    //
    // resolvePostersLive runs the full fallback chain synchronously
    // for the first maxTitles poster-less videos, IN PARALLEL (6
    // threads) under ONE shared budget, and writes each resolved
    // poster onto the Video BEFORE the caller renders it — so the
    // card is born with its poster and never depends on a later
    // re-render, delivery, or rekindle. Titles that do not resolve
    // inside the budget keep a blank poster (they still render, and
    // the existing maybeEnrichPoster backstop keeps trying). The
    // chain per title: (b) cached source poster / backdrop (no
    // network), (c) live source fetch from the confirming source —
    // or the first priority source （巨量） when the card has none
    // yet — (d) TMDB poster, (d2) Douban poster (placeholder
    // filtered), (d3) TMDB backdrop. A live hit is persisted via
    // recordPoster so the next cold start finds it on disk. Never
    // throws; never blocks past budgetMs (invokeAll cancels the
    // stragglers). Callers pass the engines they already built for
    // the availability check — no engine is created here.
    static final int RESOLVE_POSTERS_THREADS = 6;

    static void resolvePostersLive(
            List<Cms.Video> videos,
            List<SourceEngine> engs,
            List<String> sourceKeys,
            File filesDir,
            long budgetMs,
            int maxTitles) {
        if (videos == null
                || videos.isEmpty()
                || engs == null
                || engs.isEmpty()
                || budgetMs <= 0
                || maxTitles <= 0) return;
        List<Cms.Video> targets = new ArrayList<>();
        for (Cms.Video v : videos) {
            if (v == null || v.title == null || v.title.isEmpty()) continue;
            if (v.poster != null && !v.poster.isEmpty()) continue;
            targets.add(v);
            if (targets.size() >= maxTitles) break;
        }
        if (targets.isEmpty()) return;
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        RESOLVE_POSTERS_THREADS,
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "poster-resolve");
                                t.setDaemon(true);
                                return t;
                            }
                        });
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (Cms.Video v : targets) {
                final Cms.Video vf = v;
                tasks.add(
                        () -> {
                            try {
                                return resolveOnePoster(vf, engs, sourceKeys, filesDir);
                            } catch (Throwable ignored) {
                                return null;
                            }
                        });
            }
            List<Future<String>> futures =
                    pool.invokeAll(tasks, budgetMs, TimeUnit.MILLISECONDS);
            for (int i = 0; i < targets.size(); i++) {
                try {
                    Future<String> f = futures.get(i);
                    if (f.isDone() && !f.isCancelled()) {
                        String p = f.get();
                        if (p != null && !p.isEmpty()) targets.get(i).poster = p;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            pool.shutdownNow();
        }
    }

    /** One title's poster via the full chain (2.0.49, background thread). Null when unresolved. */
    private static String resolveOnePoster(
            Cms.Video v, List<SourceEngine> engs, List<String> sourceKeys, File filesDir) {
        String title = v.title;
        // (b) cached source poster / TMDB backdrop — no network.
        String cached =
                resolvePoster(filesDir, null, v.sourceApi, title, v.backdrop);
        if (cached != null && !cached.isEmpty()) return cached;
        // (c) live source fetch: confirming source first, else the
        // first priority source （巨量）, else the first engine.
        SourceEngine eng = null;
        String key = null;
        if (v.sourceApi != null && !v.sourceApi.isEmpty()) {
            for (int i = 0; i < sourceKeys.size() && i < engs.size(); i++) {
                if (v.sourceApi.equals(sourceKeys.get(i))) {
                    eng = engs.get(i);
                    key = sourceKeys.get(i);
                    break;
                }
            }
        }
        if (eng == null) {
            for (int i = 0; i < sourceKeys.size() && i < engs.size(); i++) {
                if (SourcePreferences.PRIORITY_SOURCE_APIS.length > 0
                        && SourcePreferences.PRIORITY_SOURCE_APIS[0].equals(
                                sourceKeys.get(i))) {
                    eng = engs.get(i);
                    key = sourceKeys.get(i);
                    break;
                }
            }
        }
        if (eng == null) {
            eng = engs.get(0);
            key = sourceKeys.isEmpty() ? "" : sourceKeys.get(0);
        }
        if (eng != null && key != null && !key.isEmpty()) {
            PosterResult live = fetchPosterLiveResult(eng, key, title);
            if (live != null && live.poster != null && !live.poster.isEmpty()) {
                recordPoster(filesDir, key, title, live.poster);
                return live.poster;
            }
        }
        // (d) TMDB poster, (d2) Douban poster, (d3) TMDB backdrop.
        try {
            TmdbClient.Item hit = TmdbClient.search(filesDir, title, v.year);
            if (hit != null && hit.poster != null && !hit.poster.isEmpty()) {
                return hit.poster;
            }
            String douban = fetchDoubanPoster(title, v.year);
            if (douban != null && !douban.isEmpty()) return douban;
            if (hit != null && hit.backdrop != null && !hit.backdrop.isEmpty()) {
                return hit.backdrop;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * mkoai 2.0.48: persist one title's source poster ("po") onto its
     * existing fresh availability entry, so a poster found by a live
     * top-up / view-level fetch survives the next cold start instead
     * of needing another live fetch (2.0.46/2.0.47's Phase B top-up
     * only set the in-memory Video poster — the disk entry kept no
     * "po", so every cold start had to re-fetch live). Disk-only: it
     * deliberately does NOT touch the session poster cache (source
     * posters are remembered there by findMatch; TMDB/Douban posters
     * are not source posters). No-op when there is no fresh entry:
     * availability is never invented — the next availability check
     * persists "po" via recordChecked, which reads posterCache.
     * Never throws.
     */
    static void recordPoster(File filesDir, String sourceKey, String title, String poster) {
        try {
            if (filesDir == null
                    || sourceKey == null
                    || sourceKey.isEmpty()
                    || title == null
                    || poster == null
                    || poster.isEmpty()) return;
            JSONObject disk = loadAvailDisk(filesDir);
            JSONObject srcDisk = disk.optJSONObject(sourceKey);
            if (srcDisk == null) return;
            if (freshDiskRegion(srcDisk, title, System.currentTimeMillis()) == null) return;
            JSONObject e = srcDisk.optJSONObject(title);
            if (e == null) return;
            e.put("po", poster);
            saveAvailDisk(filesDir, disk);
        } catch (Throwable ignored) {
        }
    }

    // ---- mkoai 2.0.48: Douban poster fallback (chain step (d2)) ----
    //
    // The app already uses Douban suggest (TitleTranslator,
    // ImmersivePoster); this reuses that same public API for posters:
    // https://movie.douban.com/j/subject_suggest?q=<title> — no key.
    // A suggest hit's img is usually a small poster (s_ratio_poster);
    // it is upgraded to m_ratio_poster when safe. A placeholder img
    // (tv_default_small.png / movie-static/pics/) means Douban has no
    // real poster for this title and must be skipped, never displayed.
    static final String DOUBAN_SUGGEST_URL =
            "https://movie.douban.com/j/subject_suggest?q=";
    private static final int DOUBAN_POSTER_TIMEOUT_MS = 4000;
    /** Titles already tried against Douban this session (one attempt each). */
    static final java.util.Set<String> doubanPosterTried =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** Session cache: title -> Douban poster URL (hits only). */
    static final java.util.Map<String, String> doubanPosterCache =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /** Douban placeholder images carry no real poster — never display one. */
    static boolean isDoubanPlaceholder(String img) {
        if (img == null || img.isEmpty()) return true;
        return img.contains("tv_default_small.png") || img.contains("movie-static/pics/");
    }

    /** Douban suggest returns a small poster; prefer the medium one when safe. */
    static String upgradeDoubanPoster(String img) {
        if (img == null) return null;
        if (img.contains("s_ratio_poster")) {
            return img.replace("s_ratio_poster", "m_ratio_poster");
        }
        return img;
    }

    /** First 4-digit year in a string, or 0. */
    static int parseDoubanYear(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(s);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group());
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Main title before a subtitle separator (： : - — （ (). */
    static String doubanMainTitle(String title) {
        if (title == null) return "";
        String t = title.trim();
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '：' || c == ':' || c == '-' || c == '—' || c == '（' || c == '(') {
                cut = i;
                break;
            }
        }
        return t.substring(0, cut).trim();
    }

    /**
     * Parse one Douban suggest response (pure, no network — unit-testable).
     * Returns the first genuinely matching item's poster, or null. A
     * placeholder img counts as no poster. Suggest sometimes answers
     * {"error": "008"} (login required) or non-JSON when blocked — any
     * non-array response is a soft null, never an exception.
     */
    static String parseDoubanSuggest(String json, String title, String year) {
        try {
            if (json == null || title == null) return null;
            String t = json.trim();
            if (!t.startsWith("[")) return null;
            JSONArray arr = new JSONArray(t);
            String wantKey = matchKey(title);
            String wantMainKey = matchKey(doubanMainTitle(title));
            if (wantKey.isEmpty()) return null;
            int wantYear = parseDoubanYear(year);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String itTitle = item.optString("title", "");
                String itSub = item.optString("sub_title", "");
                // Strict match, or main-title match (query "九月五日：
                // 人質危機" vs Douban "九月五日"), or English sub-title
                // match for foreign titles (query "Alan's Universe").
                boolean titleOk =
                        titlesMatch(title, itTitle)
                                || (!wantMainKey.isEmpty()
                                        && wantMainKey.equals(matchKey(itTitle)));
                boolean subOk = false;
                if (!titleOk && itSub != null && !itSub.isEmpty()) {
                    String subKey = Cms.normTitle(ChineseConvert.t2s(itSub));
                    String wantSub = Cms.normTitle(ChineseConvert.t2s(title));
                    subOk = !subKey.isEmpty() && subKey.equals(wantSub);
                }
                if (!titleOk && !subOk) continue;
                int itYear = parseDoubanYear(item.optString("year", ""));
                if (wantYear > 0 && itYear > 0 && Math.abs(wantYear - itYear) > 1) continue;
                String img = item.optString("img", "").trim();
                if (isDoubanPlaceholder(img)) continue;
                return upgradeDoubanPoster(img);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Douban poster for one title (background thread only — it does
     * network). Lightweight: one suggest request, 4s timeouts, one
     * attempt per title per session (failures are remembered, never
     * retried in a loop). Never throws; null means "no Douban poster".
     */
    static String fetchDoubanPoster(String title, String year) {
        try {
            if (title == null || title.trim().isEmpty()) return null;
            String key = title.trim();
            String hit = doubanPosterCache.get(key);
            if (hit != null && !hit.isEmpty()) return hit;
            if (doubanPosterTried.contains(key)) return null;
            doubanPosterTried.add(key);
            String url = DOUBAN_SUGGEST_URL + URLEncoder.encode(key, "UTF-8");
            String json = doubanHttpGet(url);
            if (json == null) return null;
            String poster = parseDoubanSuggest(json, key, year);
            if (poster != null && !poster.isEmpty()) doubanPosterCache.put(key, poster);
            return poster;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** One short Douban GET (Chrome UA + Douban referer, 4s timeouts). Null on any failure. */
    static String doubanHttpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(DOUBAN_POSTER_TIMEOUT_MS);
            conn.setReadTimeout(DOUBAN_POSTER_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            + " (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setRequestProperty("Referer", "https://movie.douban.com/");
            try {
                RequestScope.attach(conn);
            } catch (Throwable ignored) {
                // No RequestScope on this thread (unit tests): proceed unattached.
            }
            try (InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 512 * 1024) break;
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
            try {
                RequestScope.detach(conn);
            } catch (Throwable ignored) {
            }
        }
    }

    // 2.0.33 home preload: MainActivity starts fetchHome + prefilterVisible
    // on a background thread during the splash and parks the prefiltered
    // list here; MobileShell consumes it on the first home load so the
    // first paint needs no network and shows no loading spinner. The
    // holder is cleared on consume, so a later refresh re-fetches.
    // homePreloadDone is set (success or failure) when the preload
    // finishes, so the splash fade never waits forever.
    public static volatile List<Cms.Video> preloadedHome;
    public static volatile boolean homePreloadDone;

    // 2.0.34 session home cache: the fully filtered home list for the
    // current source, kept for 10 minutes. Switching tabs used to re-run
    // fetchHome + prefilterVisible from scratch (~17s on a cold launch,
    // because the prefilter re-checked every title). The cache makes a
    // return to the home tab instant. Pull-refresh / reloadCategory
    // clears it, and a source switch (different site key) misses.
    private static volatile List<Cms.Video> sessionHome;
    private static volatile String sessionHomeSiteKey;
    private static volatile long sessionHomeAt;
    private static final long SESSION_HOME_TTL_MS = 600_000L; // 10 min

    /** The cached home list for this source, or null when absent/expired. Never throws. */
    static List<Cms.Video> sessionHomeFor(String siteKey) {
        try {
            List<Cms.Video> home = sessionHome;
            if (home == null
                    || siteKey == null
                    || !siteKey.equals(sessionHomeSiteKey)
                    || System.currentTimeMillis() - sessionHomeAt > SESSION_HOME_TTL_MS) {
                return null;
            }
            return new ArrayList<>(home);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Remember a freshly filtered home list for this source. Never throws. */
    static void rememberSessionHome(String siteKey, List<Cms.Video> home) {
        try {
            if (siteKey == null || home == null) return;
            sessionHome = new ArrayList<>(home);
            sessionHomeSiteKey = siteKey;
            sessionHomeAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    /** Drop the session home cache (pull-refresh, reload, source switch). Never throws. */
    static void clearSessionHome() {
        try {
            sessionHome = null;
            sessionHomeSiteKey = null;
            sessionHomeAt = 0;
        } catch (Throwable ignored) {
        }
    }

    /** Confirmed present in the source (TW/CN/KR/US). ABSENT, OTHER and UNKNOWN are not. */
    private static boolean isPresent(String region) {
        return REGION_TW.equals(region)
                || REGION_CN.equals(region)
                || REGION_KR.equals(region)
                || REGION_US.equals(region);
    }

    /**
     * 2.0.57: the platform rule, enforced where the region is known
     * (not in fetchLive — the region is not known there). A title with
     * a Netflix/Disney+ platform always passes; a title with no platform
     * passes only when its region is China or Korea. Other regions keep
     * the old Netflix/Disney+ requirement. Package-visible for the
     * plain-JVM checks.
     */
    static boolean platformPasses(String platform, String region) {
        if (platform != null && !platform.isEmpty()) return true;
        return REGION_CN.equals(region) || REGION_KR.equals(region);
    }

    // ---- mkoai 2.0.38: helpers for TMDB home rows (same strict filter,
    // reused instead of duplicating the availability logic). ----

    /**
     * mkoai 2.0.39: confirmed present in ANY region — for TMDB rows
     * only. The awwrated home keeps the TW/CN/KR/US filter (the user's
     * "mainly TW/CN/KR + some US" rule), but the TMDB rows are
     * category-specific: a 日劇/日漫/英劇 row is Japanese/British by
     * definition, so a confirmed title counts whatever its region is.
     * Diagnosis (VM, trending_tv): 5 of the first 15 titles matched in
     * 7-9 sources each yet were dropped by the region filter — all
     * Japanese anime or British shows. ABSENT and UNKNOWN still don't
     * count — the strict "only titles confirmed in a source" rule is
     * unchanged.
     */
    static boolean isAnyRegion(String region) {
        return region != null
                && !REGION_ABSENT.equals(region)
                && !REGION_UNKNOWN.equals(region);
    }

    /** Fresh disk region for one title under one source, or null when unknown/expired. */
    static String cachedRegion(File filesDir, String sourceKey, String title) {
        if (filesDir == null || sourceKey == null || sourceKey.isEmpty() || title == null) {
            return null;
        }
        JSONObject disk = loadAvailDisk(filesDir);
        return freshDiskRegion(disk.optJSONObject(sourceKey), title, System.currentTimeMillis());
    }

    /** One live title check (never throws; UNKNOWN on failure). */
    static String checkOne(SourceEngine eng, String sourceKey, String title) {
        if (eng == null || title == null) return REGION_UNKNOWN;
        try {
            return checkTitle(eng, sourceKey, title);
        } catch (Throwable ignored) {
            return REGION_UNKNOWN;
        }
    }

    /** Real source id for a title (session cache first, then disk), or null. */
    static String detailIdFor(File filesDir, String sourceKey, String title) {
        if (title == null) return null;
        String id =
                sourceKey == null
                        ? null
                        : detailIdCache.get(detailKey(sourceKey, title));
        if (id != null && !id.isEmpty()) return id;
        if (filesDir == null || sourceKey == null || sourceKey.isEmpty()) return null;
        JSONObject disk = loadAvailDisk(filesDir);
        return freshDiskDetailId(
                disk.optJSONObject(sourceKey), title, System.currentTimeMillis());
    }

    /** Persist freshly checked regions (+ their detail ids) to the disk cache. */
    static void recordChecked(
            File filesDir, String sourceKey, Map<String, String> regions) {
        if (filesDir == null || sourceKey == null || sourceKey.isEmpty() || regions == null) {
            return;
        }
        JSONObject disk = loadAvailDisk(filesDir);
        Map<String, String> detailIds = new HashMap<>();
        for (String t : regions.keySet()) {
            String id = detailIdCache.get(detailKey(sourceKey, t));
            if (id != null && !id.isEmpty()) detailIds.put(t, id);
        }
        mergeAndSaveAvail(filesDir, disk, sourceKey, regions, detailIds,
                System.currentTimeMillis());
    }

    // ---- mkoai 2.0.39: cross-source availability (TMDB rows) ----
    //
    // 2.0.38 checked each TMDB title against the CURRENT source only, so
    // a row could only ever show what that one source carries (the user
    // saw "熱門劇集: 共 1 部"). The TMDB rows now check ALL configured
    // sources: a title counts as playable when ANY source has it, and the
    // card remembers which source confirmed it (so a tap opens the detail
    // page there, where the id actually exists). The strict rule is
    // unchanged — a title with no confirming source never appears.

    /** One cross-source hit: present in this source, with its real id. */
    static final class CrossHit {
        final String region;
        final String sourceKey;
        final String detailId;
        // mkoai 2.0.45: the confirming source's own poster for this
        // title (may be "" when the source has none). TMDB rows use it
        // when TMDB itself has no poster, so the card is never blank.
        final String poster;

        CrossHit(String region, String sourceKey, String detailId) {
            this(region, sourceKey, detailId, "");
        }

        CrossHit(String region, String sourceKey, String detailId, String poster) {
            this.region = region;
            this.sourceKey = sourceKey;
            this.detailId = detailId;
            this.poster = poster == null ? "" : poster;
        }
    }

    /**
     * Disk-only cross-source lookup: the first source (in given order)
     * with a fresh CONFIRMED-PRESENT entry for this title. Returns null
     * when no source confirms it on disk. Never throws.
     */
    static CrossHit cachedHitAny(
            JSONObject disk, List<String> sourceKeys, String title, long now) {
        if (disk == null || sourceKeys == null || title == null) return null;
        for (String key : sourceKeys) {
            if (key == null || key.isEmpty()) continue;
            String region = freshDiskRegion(disk.optJSONObject(key), title, now);
            if (region != null && isAnyRegion(region)) {
                return new CrossHit(
                        region,
                        key,
                        freshDiskDetailId(disk.optJSONObject(key), title, now),
                        freshDiskPoster(disk.optJSONObject(key), title, now));
            }
        }
        return null;
    }

    /**
     * 2.0.40: disk-only cross-source lookup with the awwrated region
     * rule (TW/CN/KR/US only — unlike cachedHitAny, which accepts any
     * confirmed region for TMDB rows). Returns the first source (in
     * given order) with a fresh CONFIRMED-PRESENT entry for this title,
     * or null. Never throws.
     */
    static CrossHit cachedHitPresent(
            JSONObject disk, List<String> sourceKeys, String title, long now) {
        if (disk == null || sourceKeys == null || title == null) return null;
        for (String key : sourceKeys) {
            if (key == null || key.isEmpty()) continue;
            String region = freshDiskRegion(disk.optJSONObject(key), title, now);
            if (region != null && isPresent(region)) {
                return new CrossHit(
                        region,
                        key,
                        freshDiskDetailId(disk.optJSONObject(key), title, now),
                        freshDiskPoster(disk.optJSONObject(key), title, now));
            }
        }
        return null;
    }

    /**
     * 2.0.40: disk-only first paint for the awwrated home (2b). No
     * network at all, so the first screen never waits for live checks:
     * titles with a fresh CONFIRMED-PRESENT disk entry in ANY source
     * keep their confirming source (id + sourceApi rewritten so a tap
     * opens the detail page there); titles confirmed ABSENT in EVERY
     * source are dropped now; everything else is kept as-is (pending)
     * for the live cross-source refinement in phase 2. Never returns
     * an empty list for a non-empty input (safety net) and never
     * throws. mkoai 2.0.51: the old code dropped the pending titles
     * here, so the first paint was empty whenever the disk cache had
     * no fresh PRESENT entry (e.g. after switching sources) — the
     * user saw "當前分類暫無影片" until phase 2 finished. Pending
     * titles are now kept on the first paint.
     */
    static List<Cms.Video> prefilterVisibleDiskCross(
            List<Cms.Video> videos, List<String> sourceKeys, File filesDir) {
        if (videos == null || videos.isEmpty()) return new ArrayList<>();
        List<Cms.Video> input = new ArrayList<>(videos);
        List<Cms.Video> titled = new ArrayList<>();
        for (Cms.Video v : input) {
            if (v.title != null && !v.title.trim().isEmpty()) titled.add(v);
        }
        if (titled.isEmpty()) return new ArrayList<>();
        // No sources configured: fail open, never blank the home.
        if (sourceKeys == null || sourceKeys.isEmpty()) return new ArrayList<>(titled);
        long now = System.currentTimeMillis();
        JSONObject disk = filesDir == null ? new JSONObject() : loadAvailDisk(filesDir);
        List<Cms.Video> out = new ArrayList<>();
        for (Cms.Video v : titled) {
            CrossHit hit = cachedHitPresent(disk, sourceKeys, v.title, now);
            if (hit != null) {
                availabilityCache.put(v.title, hit.region);
                if (hit.detailId != null && !hit.detailId.isEmpty()) {
                    detailIdCache.put(detailKey(hit.sourceKey, v.title), hit.detailId);
                    v.id = hit.detailId;
                }
                v.sourceApi = hit.sourceKey;
                // mkoai 2.0.46: a disk-confirmed title also carries its
                // source poster — a TMDB-poster-less card is filled here
                // instead of waiting for a live re-check.
                if ((v.poster == null || v.poster.isEmpty())
                        && hit.poster != null && !hit.poster.isEmpty()) {
                    v.poster = hit.poster;
                }
                out.add(v);
            } else if (cachedAbsentEverywhere(disk, sourceKeys, v.title, now)) {
                // Known absent everywhere: drop now, no live check needed.
            } else {
                // mkoai 2.0.51: unknown candidates stay ON the home as
                // pending — phase 2 refines them with the live check.
                // Dropping them here blanked the first paint whenever
                // the disk cache had no fresh PRESENT entry.
                out.add(v);
            }
        }
        // Safety net: never return an empty list for a non-empty input.
        if (out.isEmpty()) return new ArrayList<>(titled);
        return out;
    }

    /**
     * 2.0.40: filters 3+4 across ALL configured sources (2a) — the
     * cross-source replacement for applySourceFilters. A title counts
     * as playable when ANY source confirms it (title-matched, region
     * TW/CN/KR/US); the card remembers the confirming source
     * (v.sourceApi + real id) so a tap opens the detail page there.
     * The strict rule is unchanged: titles confirmed in no source
     * (absent, other-region, or unverified) are dropped, and the
     * safety net keeps the unfiltered list when strict filtering
     * would empty the home. US titles are capped at ~25% of the final
     * list, as before.
     *
     * <p>Disk first (all sources, current first): a fresh PRESENT entry
     * keeps the title without a live check, and fresh ABSENT everywhere
     * drops it without one. The rest are live-checked in rounds, one
     * source at a time (current first), bounded by CHECK_BUDGET_MS
     * shared across rounds; results persist to disk per source via
     * recordChecked. Runs on the caller's (background) thread.
     *
     * @param engs engines for the check sources, in the same order as
     *     sourceKeys (dead engines already skipped by the caller).
     */
    static List<Cms.Video> applySourceFiltersCross(
            List<Cms.Video> videos,
            List<SourceEngine> engs,
            List<String> sourceKeys,
            File filesDir) {
        if (videos == null || videos.isEmpty()) return new ArrayList<>();
        List<Cms.Video> input = new ArrayList<>(videos);
        // No source configured: skip filters 3+4 so the home never goes blank.
        // mkoai 2.0.51: fail open — return the unfiltered list, not an
        // empty one (the old empty return re-blanked the home in phase 2
        // when every engine was dead).
        if (engs == null || engs.isEmpty() || sourceKeys == null || sourceKeys.isEmpty()) {
            return new ArrayList<>(input);
        }
        long now = System.currentTimeMillis();
        JSONObject disk = filesDir == null ? new JSONObject() : loadAvailDisk(filesDir);

        Map<String, CrossHit> hitByTitle = new HashMap<>();
        List<Cms.Video> toCheck = new ArrayList<>();
        for (Cms.Video v : input) {
            if (v.title == null) {
                toCheck.add(v);
                continue;
            }
            CrossHit hit = cachedHitPresent(disk, sourceKeys, v.title, now);
            if (hit != null) {
                hitByTitle.put(v.title, hit);
                availabilityCache.put(v.title, hit.region);
                if (hit.detailId != null && !hit.detailId.isEmpty()) {
                    detailIdCache.put(detailKey(hit.sourceKey, v.title), hit.detailId);
                }
            } else if (cachedAbsentEverywhere(disk, sourceKeys, v.title, now)) {
                // Known absent everywhere: dropped, never live-checked.
            } else {
                toCheck.add(v);
            }
        }

        if (!toCheck.isEmpty()) {
            ExecutorService pool =
                    Executors.newFixedThreadPool(
                            CHECK_THREADS,
                            new ThreadFactory() {
                                @Override
                                public Thread newThread(Runnable r) {
                                    Thread t = new Thread(r, "awwrated-availability-cross");
                                    t.setDaemon(true);
                                    return t;
                                }
                            });
            long deadline = System.currentTimeMillis() + CHECK_BUDGET_MS;
            try {
                for (int si = 0; si < engs.size() && !toCheck.isEmpty(); si++) {
                    final SourceEngine eng = engs.get(si);
                    final String skey =
                            si < sourceKeys.size() ? sourceKeys.get(si) : "";
                    if (eng == null || skey.isEmpty()) continue;
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 1000) break;
                    final List<Cms.Video> batch = new ArrayList<>(toCheck);
                    List<Callable<String>> tasks = new ArrayList<>();
                    for (Cms.Video v : batch) {
                        final String title = v.title;
                        tasks.add(
                                new Callable<String>() {
                                    @Override
                                    public String call() {
                                        try {
                                            return checkTitle(eng, skey, title);
                                        } catch (Throwable ignored) {
                                            return REGION_UNKNOWN;
                                        }
                                    }
                                });
                    }
                    Map<String, String> roundRegions = new HashMap<>();
                    try {
                        List<Future<String>> futures =
                                pool.invokeAll(tasks, remain, TimeUnit.MILLISECONDS);
                        for (int i = 0; i < batch.size(); i++) {
                            Cms.Video v = batch.get(i);
                            String region = REGION_UNKNOWN;
                            try {
                                Future<String> f = futures.get(i);
                                if (f.isDone() && !f.isCancelled()) region = f.get();
                            } catch (Throwable ignored) {
                            }
                            if (region == null) region = REGION_UNKNOWN;
                            if (v.title != null) availabilityCache.put(v.title, region);
                            roundRegions.put(v.title, region);
                        }
                    } catch (Throwable ignored) {
                        for (Cms.Video v : batch) {
                            roundRegions.put(v.title, REGION_UNKNOWN);
                        }
                    }
                    recordChecked(filesDir, skey, roundRegions);
                    List<Cms.Video> stillPending = new ArrayList<>();
                    for (Cms.Video v : batch) {
                        String region = roundRegions.get(v.title);
                        if (isPresent(region)) {
                            String id = detailIdFor(filesDir, skey, v.title);
                            // mkoai 2.0.46: a live-confirmed title also
                            // carries the source poster checkTitle just
                            // remembered — the 2.0.45 build dropped it
                            // here (3-arg CrossHit), so live-confirmed
                            // cards never got their fallback poster.
                            hitByTitle.put(
                                    v.title,
                                    new CrossHit(
                                            region,
                                            skey,
                                            id,
                                            posterFor(filesDir, skey, v.title)));
                        } else {
                            stillPending.add(v);
                        }
                    }
                    toCheck = stillPending;
                }
            } catch (Throwable ignored) {
                // A failed round leaves its titles unconfirmed -> dropped by the strict filter below.
            } finally {
                pool.shutdownNow();
            }
        }

        // Strict: keep only titles confirmed present in some source.
        // A kept title's card points at the confirming source (real id
        // + sourceApi) so a tap opens the detail page there directly.
        List<Cms.Video> kept = new ArrayList<>();
        for (Cms.Video v : input) {
            CrossHit hit = hitByTitle.get(v.title);
            if (hit == null) continue;
            // 2.0.57: platform rule (see platformPasses) — a
            // platform-less title is kept only when its region is
            // China or Korea.
            if (!platformPasses(v.platform, hit.region)) continue;
            if (hit.detailId != null && !hit.detailId.isEmpty()) v.id = hit.detailId;
            v.sourceApi = hit.sourceKey;
            // mkoai 2.0.46: carry the confirming source's poster onto
            // the card when TMDB has none (same fill as prefilter).
            if ((v.poster == null || v.poster.isEmpty())
                    && hit.poster != null && !hit.poster.isEmpty()) {
                v.poster = hit.poster;
            }
            kept.add(v);
        }
        // mkoai 2.0.51: fail open — when strict filtering would empty
        // the home, keep the unfiltered list instead of a blank home.
        // The old code returned the empty list here, so a slow or
        // failing cross-source check left "當前分類暫無影片" on screen
        // even though the awwrated fetch had returned titles. This is
        // the safety net the method doc already promised.
        if (kept.isEmpty()) return new ArrayList<>(input);

        int usCount = 0;
        for (Cms.Video v : kept) {
            CrossHit hit = hitByTitle.get(v.title);
            if (hit != null && REGION_US.equals(hit.region)) usCount++;
        }
        int usAllowed = Math.max(1, (int) Math.round(kept.size() * 0.25));
        if (usCount > usAllowed) {
            List<Cms.Video> capped = new ArrayList<>();
            int usKept = 0;
            for (Cms.Video v : kept) {
                CrossHit hit = hitByTitle.get(v.title);
                if (hit != null && REGION_US.equals(hit.region)) {
                    if (usKept >= usAllowed) continue;
                    usKept++;
                }
                capped.add(v);
            }
            if (!capped.isEmpty()) kept = capped;
        }
        return kept;
    }

    /**
     * True when EVERY listed source has a fresh ABSENT entry for this
     * title, so a live check would only repeat known failures. Never
     * throws.
     */
    static boolean cachedAbsentEverywhere(
            JSONObject disk, List<String> sourceKeys, String title, long now) {
        if (disk == null || sourceKeys == null || sourceKeys.isEmpty() || title == null) {
            return false;
        }
        for (String key : sourceKeys) {
            if (key == null || key.isEmpty()) continue;
            if (!REGION_ABSENT.equals(freshDiskRegion(disk.optJSONObject(key), title, now))) {
                return false;
            }
        }
        return true;
    }

    /** Whole availability disk cache (empty object on any failure). Never throws. */
    static JSONObject loadAvailDisk(File filesDir) {
        JSONObject out = new JSONObject();
        if (filesDir == null) return out;
        try {
            File f = new File(filesDir, AVAIL_FILE);
            if (!f.exists() || f.length() <= 0 || f.length() > 8L*1024*1024) return out;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return new JSONObject(new String(buf, "UTF-8"));
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    /** Write the whole availability disk cache. Best-effort, never throws. */
    private static void saveAvailDisk(File filesDir, JSONObject disk) {
        if (filesDir == null || disk == null) return;
        try {
            try (FileOutputStream out =
                    new FileOutputStream(new File(filesDir, AVAIL_FILE))) {
                out.write(disk.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Fresh disk region for one title under one source, or null when unknown/expired. */
    private static String freshDiskRegion(JSONObject srcDisk, String title, long now) {
        if (srcDisk == null || title == null) return null;
        JSONObject e = srcDisk.optJSONObject(title);
        if (e == null) return null;
        long ts = e.optLong("ts", 0);
        if (ts <= 0 || now - ts > AVAIL_TTL_MS) return null;
        String r = e.optString("r", "");
        // 2.0.33: a stored UNKNOWN (written by 2.0.32) is not a result —
        // treat it as no entry so the title is re-checked live.
        if (r.isEmpty() || REGION_UNKNOWN.equals(r)) return null;
        return r;
    }

    /**
     * mkoai 2.0.34: the detail id stored alongside a fresh disk entry, or
     * null when the entry is unknown/expired or carries no id. The id is
     * what lets a home card tap open the detail page directly on a warm
     * launch (2.0.33 only had it in the session cache, so warm launches
     * kept falling back to the search page).
     */
    private static String freshDiskDetailId(JSONObject srcDisk, String title, long now) {
        if (freshDiskRegion(srcDisk, title, now) == null) return null;
        JSONObject e = srcDisk.optJSONObject(title);
        if (e == null) return null;
        String id = e.optString("id", "");
        return id.isEmpty() ? null : id;
    }

    /**
     * mkoai 2.0.45: the source poster stored alongside a fresh disk
     * entry ("po"), or null when the entry is unknown/expired or
     * carries no poster. Old (pre-2.0.45) entries simply have no "po"
     * key — optString returns "" — so existing caches keep working.
     */
    private static String freshDiskPoster(JSONObject srcDisk, String title, long now) {
        if (freshDiskRegion(srcDisk, title, now) == null) return null;
        JSONObject e = srcDisk.optJSONObject(title);
        if (e == null) return null;
        String poster = e.optString("po", "");
        return poster.isEmpty() ? null : poster;
    }

    /**
     * Merge regions into the disk cache for one source (refreshing ts to
     * now), prune expired entries for that source only, and save.
     * Other sources' entries are untouched. Best-effort, never throws.
     * 2.0.34: detailIds (title -> real source id, may be null) is written
     * alongside each region as "id", so warm launches can open the
     * detail page without a live check.
     */
    private static void mergeAndSaveAvail(
            File filesDir, JSONObject disk, String sourceKey,
            Map<String, String> regions, Map<String, String> detailIds, long now) {
        if (filesDir == null || disk == null || sourceKey == null || sourceKey.isEmpty()) return;
        try {
            JSONObject srcDisk = disk.optJSONObject(sourceKey);
            if (srcDisk == null) {
                srcDisk = new JSONObject();
                disk.put(sourceKey, srcDisk);
            }
            List<String> stale = new ArrayList<>();
            Iterator<String> keys = srcDisk.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject e = srcDisk.optJSONObject(k);
                long ts = e == null ? 0 : e.optLong("ts", 0);
                if (ts <= 0 || now - ts > AVAIL_TTL_MS) stale.add(k);
            }
            for (String k : stale) srcDisk.remove(k);
            for (Map.Entry<String, String> en : regions.entrySet()) {
                if (en.getKey() == null || en.getValue() == null) continue;
                // 2.0.33: UNKNOWN is not a result — never write it to disk.
                // Only confirmed ABSENT / present regions are recorded, so
                // an unverified title is re-checked live on the next launch
                // instead of being remembered as "unknown".
                if (REGION_UNKNOWN.equals(en.getValue())) continue;
                // mkoai 2.0.49: keep the OLD entry's poster/id when the
                // new write has none. The old code built a brand-new
                // JSONObject, so any recordChecked round that rewrote a
                // title while the session poster cache did not hold it
                // silently wiped a previously persisted "po" (e.g. one
                // written by recordPoster after a live fetch) — the next
                // cold start then had to re-fetch live again.
                JSONObject old = srcDisk.optJSONObject(en.getKey());
                JSONObject e = new JSONObject();
                e.put("r", en.getValue());
                e.put("ts", now);
                String oldId = old == null ? "" : old.optString("id", "");
                String oldPo = old == null ? "" : old.optString("po", "");
                if (detailIds != null) {
                    String id = detailIds.get(en.getKey());
                    if (id != null && !id.isEmpty()) e.put("id", id);
                    else if (!oldId.isEmpty()) e.put("id", oldId);
                } else if (!oldId.isEmpty()) {
                    e.put("id", oldId);
                }
                // mkoai 2.0.45: persist the confirming source's poster
                // ("po", optional — old entries without it still read).
                String poster = posterCache.get(detailKey(sourceKey, en.getKey()));
                if (poster != null && !poster.isEmpty()) e.put("po", poster);
                else if (!oldPo.isEmpty()) e.put("po", oldPo);
                srcDisk.put(en.getKey(), e);
            }
            saveAvailDisk(filesDir, disk);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Home videos. Disk cache first (instant, plus a background refresh so
     * the next load is fresh); live fetch when there is no cache. Never
     * throws — returns an empty list on failure.
     */
    static List<Cms.Video> fetchHome(File filesDir) {
        List<Cms.Video> cached = loadCache(filesDir);
        // mkoai 2.0.45: the disk cache may have been written under the
        // old 24h delay — re-apply the (now 48h) release delay here so a
        // title that is only 24-48h old never leaks onto the home from
        // a stale cache. Unknown dates (releaseTs 0) still fail open.
        if (!cached.isEmpty()) {
            List<Cms.Video> freshEnough = new ArrayList<>();
            for (Cms.Video v : cached) {
                if (isOldEnough(v.releaseTs)) freshEnough.add(v);
            }
            cached = freshEnough;
        }
        if (!cached.isEmpty()) {
            if (cacheHasPlatformInfo(cached)) {
                refreshInBackground(filesDir);
                return cached;
            }
            // Legacy cache (written before 2.0.28, no platform/release
            // fields): try a live fetch so filters 1+2 apply on first
            // load; fall back to the legacy list when offline so the
            // home never goes blank.
            try {
                List<Cms.Video> fresh = fetchLive();
                if (!fresh.isEmpty()) {
                    saveCache(filesDir, fresh);
                    return fresh;
                }
            } catch (Throwable ignored) {
            }
            refreshInBackground(filesDir);
            return cached;
        }
        try {
            List<Cms.Video> fresh = fetchLive();
            if (!fresh.isEmpty()) saveCache(filesDir, fresh);
            return fresh;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** Fetch the live page and build the video list. Throws on failure. */
    static List<Cms.Video> fetchLive() throws Exception {
        String html = Cms.fetch(AwwratedTitles.PAGE_URL, userAgentHeaders());
        int start = html.indexOf("<script id=\"__NEXT_DATA__\"");
        if (start < 0) throw new IllegalStateException("no __NEXT_DATA__");
        start = html.indexOf('>', start) + 1;
        int end = html.indexOf("</script>", start);
        if (end < 0) throw new IllegalStateException("no __NEXT_DATA__ end");
        JSONObject root = new JSONObject(html.substring(start, end));
        JSONObject props = root.optJSONObject("props");
        JSONObject pageProps = props == null ? null : props.optJSONObject("pageProps");
        JSONObject state =
                pageProps == null ? null : pageProps.optJSONObject("__APOLLO_STATE__");
        if (state == null) throw new IllegalStateException("no __APOLLO_STATE__");
        List<Cms.Video> out = new ArrayList<>();
        Iterator<String> keys = state.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!k.startsWith("Post:")) continue;
            JSONObject post = state.optJSONObject(k);
            if (post == null) continue;
            // Filter 1 (2.0.57): the platform is recorded here, NOT
            // enforced — it is enforced where the region is known
            // (applySourceFilters / prefilterVisible / Cross variants).
            // A title with no Netflix/Disney+ platform is kept when
            // its region is China or Korea; other regions still need
            // a Netflix/Disney+ platform. Dropping platform-less
            // titles here would discard CN/KR titles before their
            // region is ever determined.
            String platform = platformOf(post);
            JSONObject data = post.optJSONObject("awwratedData");
            if (data == null) continue;
            long releaseTs = parseLong(data.optString("releaseDate", ""));
            // Filter 2: a title must be at least 48h old before it
            // appears, so the user's source has time to index it.
            if (!isOldEnough(releaseTs)) continue;
            JSONObject lang = data.optJSONObject("lang");
            if (lang == null) continue;
            JSONObject zhObj = lang.optJSONObject("zhtw");
            String title = zhObj == null ? "" : zhObj.optString("title", "").trim();
            if (title.isEmpty()) continue;
            String poster = data.optString("poster", "").trim();
            Cms.Video v = new Cms.Video();
            // mkoai: tap cross-searches this Traditional title, same as
            // DoubanHome — awwrated titles are already Traditional, no
            // conversion of any kind.
            v.id = "msearch:" + title;
            v.title = title;
            v.poster = poster;
            double rate = parseDouble(data.optString("averageReview", ""));
            v.remarks = rate > 0 ? trimRating(rate) + " 分" : "";
            v.year = yearFromTs(releaseTs);
            v.tag = "awwrated";
            v.platform = platform;
            v.releaseTs = releaseTs;
            out.add(v);
        }
        if (out.isEmpty()) throw new java.io.IOException("awwrated 無資料");
        return out;
    }

    /**
     * Filters 3+4: keep only titles the user's source already carries
     * (title-matched against a live search of that source) and whose
     * matched region is Taiwan / China / Korea / USA, with American
     * titles capped at ~25% of the final list. Runs on the caller's
     * thread (the home load already runs on a background thread);
     * the whole check is bounded by CHECK_BUDGET_MS. Never returns
     * null and never returns an empty list for a non-empty input.
     * 2.0.33 STRICT: only CONFIRMED present titles are kept — absent,
     * other-region and unverified (UNKNOWN: check timed out or failed)
     * titles are all dropped. The only fail-open left is the safety
     * net: if strict filtering would empty the list, the unfiltered
     * list is returned instead, so the home never goes blank.
     *
     * <p>2.0.32: results persist to disk (awwrated-availability-v2.json,
     * keyed by sourceKey = the source's api URL, 24h TTL). A title
     * with a fresh disk entry for this source skips the live check
     * entirely — so a title confirmed ABSENT stays off the home
     * across launches, and a title present on one source never leaks
     * into another source's result. Live-check results are merged
     * back to disk (both freshly checked and disk-loaded regions,
     * ts refreshed to now). 2.0.34: the real source id is persisted
     * alongside the region ("id"), so a warm launch can open the
     * detail page directly without a live check.
     */
    static List<Cms.Video> applySourceFilters(
            List<Cms.Video> videos, SourceEngine eng, String sourceKey, File filesDir) {
        if (videos == null || videos.isEmpty()) return new ArrayList<>();
        List<Cms.Video> input = new ArrayList<>(videos);
        // No source configured: skip filters 3+4 so the home never goes blank.
        // mkoai 2.0.51: fail open — return the unfiltered list, not an
        // empty one (same safety net as the cross-source variant).
        if (eng == null) return new ArrayList<>(input);

        long now = System.currentTimeMillis();
        JSONObject disk = null;
        JSONObject srcDisk = null;
        boolean useDisk = sourceKey != null && !sourceKey.isEmpty() && filesDir != null;
        if (useDisk) {
            disk = loadAvailDisk(filesDir);
            srcDisk = disk.optJSONObject(sourceKey);
        }

        Map<String, String> regionByTitle = new HashMap<>();
        List<Cms.Video> toCheck = new ArrayList<>();
        for (Cms.Video v : input) {
            if (v.title == null) {
                regionByTitle.put(v.title, REGION_UNKNOWN);
                continue;
            }
            // Disk first (source-keyed): a fresh entry is authoritative
            // for this source and also refreshes the session cache, so
            // the session cache stays consistent with the current source.
            // 2.0.34: a stored detail id is restored into the session
            // cache too, so a warm launch can open the detail page
            // without a live check.
            String diskRegion = freshDiskRegion(srcDisk, v.title, now);
            if (diskRegion != null) {
                availabilityCache.put(v.title, diskRegion);
                regionByTitle.put(v.title, diskRegion);
                String diskId = freshDiskDetailId(srcDisk, v.title, now);
                if (diskId != null) {
                    detailIdCache.put(detailKey(sourceKey, v.title), diskId);
                }
            } else {
                toCheck.add(v);
            }
        }
        if (!toCheck.isEmpty()) {
            ExecutorService pool =
                    Executors.newFixedThreadPool(
                            CHECK_THREADS,
                            new ThreadFactory() {
                                @Override
                                public Thread newThread(Runnable r) {
                                    Thread t = new Thread(r, "awwrated-availability");
                                    t.setDaemon(true);
                                    return t;
                                }
                            });
            try {
                List<Callable<String>> tasks = new ArrayList<>();
                for (Cms.Video v : toCheck) {
                    final String title = v.title;
                    tasks.add(
                            new Callable<String>() {
                                @Override
                                public String call() {
                                    try {
                                        return checkTitle(eng, sourceKey, title);
                                    } catch (Throwable ignored) {
                                        return REGION_UNKNOWN; // unverified -> dropped by the strict filter
                                    }
                                }
                            });
                }
                List<Future<String>> futures =
                        pool.invokeAll(tasks, CHECK_BUDGET_MS, TimeUnit.MILLISECONDS);
                for (int i = 0; i < toCheck.size(); i++) {
                    Cms.Video v = toCheck.get(i);
                    String region = REGION_UNKNOWN; // unchecked / failed -> dropped by the strict filter
                    try {
                        Future<String> f = futures.get(i);
                        if (f.isDone() && !f.isCancelled()) region = f.get();
                    } catch (Throwable ignored) {
                    }
                    if (region == null) region = REGION_UNKNOWN;
                    if (v.title != null) availabilityCache.put(v.title, region);
                    regionByTitle.put(v.title, region);
                }
            } catch (Throwable ignored) {
                // Whole check failed: every unchecked title is UNKNOWN -> dropped by the strict filter (safety net aside).
                for (Cms.Video v : toCheck) regionByTitle.put(v.title, REGION_UNKNOWN);
            } finally {
                pool.shutdownNow();
            }
        }
        // Persist every region now known for this source (freshly
        // checked and disk-loaded alike, ts refreshed), prune expired
        // entries for this source, save. Never affects the returned list.
        // 2.0.34: the detail ids known for this source (live-checked or
        // restored from disk) are persisted alongside the regions.
        Map<String, String> detailIds = new HashMap<>();
        for (String t : regionByTitle.keySet()) {
            String id = detailIdCache.get(detailKey(sourceKey, t));
            if (id != null && !id.isEmpty()) detailIds.put(t, id);
        }
        if (useDisk) mergeAndSaveAvail(filesDir, disk, sourceKey, regionByTitle, detailIds, now);

        // 2.0.33 strict: keep only CONFIRMED present titles (TW/CN/KR/US).
        // ABSENT, OTHER and UNKNOWN (unchecked / timed out / failed) are
        // all dropped, so an unverified title never appears on the home.
        List<Cms.Video> kept = new ArrayList<>();
        for (Cms.Video v : input) {
            String region = regionByTitle.get(v.title);
            if (!isPresent(region)) continue;
            // 2.0.57: platform rule — a platform-less title is kept
            // only when its region is China or Korea.
            if (!platformPasses(v.platform, region)) continue;
            // Direct detail link: the source check found this title's real
            // id, so point the card at it — a tap opens the detail page
            // instead of a search. Every kept title was confirmed present,
            // so every kept card gets its real id when one was found.
            // 2.0.34: the id lookup is source-keyed, and a warm launch
            // restores it from disk (see the disk-hit branch above).
            String detailId =
                    v.title == null ? null : detailIdCache.get(detailKey(sourceKey, v.title));
            if (detailId != null && !detailId.isEmpty()) v.id = detailId;
            kept.add(v);
        }
        // mkoai 2.0.51: fail open — the safety net the method doc
        // promises: when strict filtering would empty the list, return
        // the unfiltered list so the home never goes blank.
        if (kept.isEmpty()) return new ArrayList<>(input);

        // Cap American titles at ~25% of the final list ("some", not the
        // majority). Taiwan / China / Korea titles are uncapped. (Only
        // confirmed US titles reach here — the strict filter above
        // already dropped UNKNOWN.)
        int usCount = 0;
        for (Cms.Video v : kept) {
            if (REGION_US.equals(regionByTitle.get(v.title))) usCount++;
        }
        int usAllowed = Math.max(1, (int) Math.round(kept.size() * 0.25));
        if (usCount > usAllowed) {
            List<Cms.Video> capped = new ArrayList<>();
            int usKept = 0;
            for (Cms.Video v : kept) {
                if (REGION_US.equals(regionByTitle.get(v.title))) {
                    if (usKept >= usAllowed) continue;
                    usKept++;
                }
                capped.add(v);
            }
            if (!capped.isEmpty()) kept = capped;
        }
        return kept;
    }

    /**
     * Pre-paint check (2.0.32): verify the titles the user will actually
     * see first (hero + Top 10) BEFORE the first render, so a title
     * confirmed absent for the current source never appears even on the
     * very first launch, when the disk cache is still empty. Called by
     * the phase-1 home load right after fetchHome, before the list is
     * rendered.
     *
     * <p>Checks titles in list order until 12 are CONFIRMED PRESENT, or
     * 30 titles have been checked, whichever comes first. Each title
     * uses the disk cache first (fresh entry for this source), else a
     * live checkTitle call; the session caches and the disk file are
     * updated the same way as applySourceFilters, so a title verified
     * here is also recorded on disk. The whole prefilter is bounded
     * by PREFILTER_BUDGET_MS (10s). 2.0.33 STRICT: only confirmed-
     * present titles are kept — absent, other-region and unverified
     * (UNKNOWN: check timed out or failed) titles are all dropped,
     * as are titles past the examined window (never checked means
     * not confirmed). Never returns null and never returns an empty
     * list for a non-empty input (the safety net returns the input
     * when strict filtering would empty it); never throws.
     */
    static List<Cms.Video> prefilterVisible(
            List<Cms.Video> videos, SourceEngine eng, String sourceKey, File filesDir) {
        if (videos == null || videos.isEmpty()) return new ArrayList<>();
        List<Cms.Video> input = new ArrayList<>(videos);
        if (eng == null) return new ArrayList<>();

        long now = System.currentTimeMillis();
        JSONObject disk = null;
        JSONObject srcDisk = null;
        boolean useDisk = sourceKey != null && !sourceKey.isEmpty() && filesDir != null;
        if (useDisk) {
            disk = loadAvailDisk(filesDir);
            srcDisk = disk.optJSONObject(sourceKey);
        }

        Map<String, String> regionByTitle = new HashMap<>();
        List<Cms.Video> liveList = new ArrayList<>();
        int examined = 0;
        int present = 0;
        for (Cms.Video v : input) {
            if (examined >= PREFILTER_MAX_CHECK) break;
            if (present >= PREFILTER_KEEP) break;
            if (v.title == null) {
                regionByTitle.put(v.title, REGION_UNKNOWN);
                examined++;
                continue;
            }
            String diskRegion = freshDiskRegion(srcDisk, v.title, now);
            examined++;
            if (diskRegion != null) {
                availabilityCache.put(v.title, diskRegion);
                regionByTitle.put(v.title, diskRegion);
                // 2.0.34: restore a stored detail id into the session
                // cache so a warm launch can open the detail page.
                String diskId = freshDiskDetailId(srcDisk, v.title, now);
                if (diskId != null) {
                    detailIdCache.put(detailKey(sourceKey, v.title), diskId);
                }
                if (isPresent(diskRegion)) present++;
            } else {
                liveList.add(v);
            }
        }
        if (!liveList.isEmpty()) {
            ExecutorService pool =
                    Executors.newFixedThreadPool(
                            CHECK_THREADS,
                            new ThreadFactory() {
                                @Override
                                public Thread newThread(Runnable r) {
                                    Thread t = new Thread(r, "awwrated-prefilter");
                                    t.setDaemon(true);
                                    return t;
                                }
                            });
            try {
                List<Callable<String>> tasks = new ArrayList<>();
                for (Cms.Video v : liveList) {
                    final String title = v.title;
                    tasks.add(
                            new Callable<String>() {
                                @Override
                                public String call() {
                                    try {
                                        return checkTitle(eng, sourceKey, title);
                                    } catch (Throwable ignored) {
                                        return REGION_UNKNOWN; // unverified -> dropped by the strict filter
                                    }
                                }
                            });
                }
                List<Future<String>> futures =
                        pool.invokeAll(tasks, PREFILTER_BUDGET_MS, TimeUnit.MILLISECONDS);
                for (int i = 0; i < liveList.size(); i++) {
                    Cms.Video v = liveList.get(i);
                    String region = REGION_UNKNOWN; // unchecked / failed -> dropped by the strict filter
                    try {
                        Future<String> f = futures.get(i);
                        if (f.isDone() && !f.isCancelled()) region = f.get();
                    } catch (Throwable ignored) {
                    }
                    if (region == null) region = REGION_UNKNOWN;
                    if (v.title != null) availabilityCache.put(v.title, region);
                    regionByTitle.put(v.title, region);
                }
            } catch (Throwable ignored) {
                for (Cms.Video v : liveList) regionByTitle.put(v.title, REGION_UNKNOWN);
            } finally {
                pool.shutdownNow();
            }
        }
        // Record what was resolved for this source (disk-loaded and
        // live-checked alike), prune expired entries, save. Best-effort.
        // 2.0.34: persist the detail ids alongside the regions.
        Map<String, String> detailIds = new HashMap<>();
        for (String t : regionByTitle.keySet()) {
            String id = detailIdCache.get(detailKey(sourceKey, t));
            if (id != null && !id.isEmpty()) detailIds.put(t, id);
        }
        if (useDisk) mergeAndSaveAvail(filesDir, disk, sourceKey, regionByTitle, detailIds, now);

        // 2.0.33 strict: keep only titles CONFIRMED present in this
        // source. ABSENT, OTHER and UNKNOWN are dropped, and so are
        // titles past the examined window (never checked = not
        // confirmed). A kept title's card id is rewritten to its real
        // source id when checkTitle found one, so a tap opens the
        // detail page directly instead of going through a search.
        List<Cms.Video> out = new ArrayList<>();
        for (Cms.Video v : input) {
            if (!isPresent(regionByTitle.get(v.title))) continue;
            // 2.0.57: platform rule (see platformPasses).
            if (!platformPasses(v.platform, regionByTitle.get(v.title))) continue;
            // 2.0.34: source-keyed id lookup (restored from disk on warm launches).
            String detailId =
                    v.title == null ? null : detailIdCache.get(detailKey(sourceKey, v.title));
            if (detailId != null && !detailId.isEmpty()) v.id = detailId;
            out.add(v);
        }
        if (out.isEmpty()) return out; // Empty is accurate; never substitute unverified candidates.
        return out;
    }

    /**
     * Search the user's source for one title and return the matched
     * result (or null). The CMS always returns ~20 loosely-matched rows,
     * so "results non-empty" proves nothing — a result only counts when
     * its title matches the queried title (script-insensitive).
     * Derivative rows （解說 / short-drama spin-offs) do not count.
     * On a match the real source id and poster are remembered in the
     * session caches (detailIdCache / posterCache). Shared by checkTitle
     * (region) and fetchPosterLive (poster) so both see the same match.
     */
    private static Cms.Video findMatch(SourceEngine eng, String sourceKey, String title)
            throws Exception {
        String term = searchTerm(title);
        if (term.isEmpty()) return null;
        // 2.0.39: also try the known Taiwan <-> mainland aliases (e.g.
        // 蜘蛛人 -> 蜘蛛侠, 快打旋風 -> 街頭霸王) — a title the source
        // lists under its mainland name would otherwise never match
        // (diagnosis: 蜘蛛人：全新的一天 and 快打旋風 only exist in the
        // sources under 蜘蛛侠 / 街頭霸王). Each alias is matched against
        // its own term, not the original query. aliasVariants returns at
        // most [term, one alias], so this adds at most 3 extra searches
        // and only when the original term finds nothing.
        List<String> queries = SearchVariants.aliasVariants(term);
        for (String q : queries) {
            // Simplified first: the user's source stores Simplified titles,
            // so a Traditional query alone never matches (verified live:
            // "絕命毒師" finds nothing, "绝命毒师" finds all five seasons).
            List<String> variants = SearchVariants.scriptVariants(q);
            String simplified = ChineseConvert.t2s(q);
            if (!simplified.equals(q)) {
                variants.remove(simplified);
                variants.add(0, simplified);
            }
            for (String variant : variants) {
                String raw = eng.search(variant, 1);
                List<Cms.Video> results = Cms.listing(raw).videos;
                for (Cms.Video r : results) {
                    if (SearchVariants.isComicOrShortDrama(r)) continue;
                    // 2.0.34: a trailer-only or commentary-only entry must not
                    // confirm the title as present — it is not the show.
                    if (SearchVariants.isSearchJunk(r)) continue;
                    if (titlesMatch(q, r.title)) {
                        // Remember the real source id so a home card tap opens
                        // this title's detail page directly (no search step).
                        // 2.0.34: source-keyed, and persisted to disk by the
                        // caller alongside the region.
                        if (r.id != null && !r.id.isEmpty()) {
                            detailIdCache.put(detailKey(sourceKey, title), r.id);
                        }
                        // mkoai 2.0.45: remember the source's own poster
                        // too — TMDB rows fall back to it when TMDB has
                        // no poster for this title.
                        rememberPoster(sourceKey, title, r.poster);
                        return r;
                    }
                }
            }
        }
        return null;
    }

    /** One title's matched region, via findMatch. Returns REGION_ABSENT when no result matches. */
    private static String checkTitle(SourceEngine eng, String sourceKey, String title)
            throws Exception {
        Cms.Video m = findMatch(eng, sourceKey, title);
        return m == null ? REGION_ABSENT : regionOf(m.area);
    }

    /** Platform of one Post: "netflix", "disney+", or "" (no platform). */
    private static String platformOf(JSONObject post) {
        JSONObject cats = post.optJSONObject("categories");
        JSONArray nodes = cats == null ? null : cats.optJSONArray("nodes");
        if (nodes != null) {
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) continue;
                int id = node.optInt("databaseId", -1);
                if (id == CAT_NETFLIX) return PLATFORM_NETFLIX;
                if (id == CAT_DISNEY) return PLATFORM_DISNEY;
            }
        }
        // Disney+ confirmed by its own id as well (every cat-1050 post
        // carries one; no Netflix post does).
        JSONObject dp = post.optJSONObject("disneyPlusData");
        if (dp != null && !dp.optString("disneyPlusId", "").trim().isEmpty()) {
            return PLATFORM_DISNEY;
        }
        return "";
    }

    /** True when the title is at least 48h old (or its date is unknown). */
    static boolean isOldEnough(long releaseTsSeconds) {
        if (releaseTsSeconds <= 0) return true; // unknown date: keep (fail open)
        return releaseTsSeconds * 1000L <= System.currentTimeMillis() - RELEASE_DELAY_MS;
    }

    /**
     * mkoai 2.0.45: the 2-day release delay for a TMDB item date
     * ("yyyy-MM-dd", may be ""). The date is the release day (midnight
     * UTC); a title counts as old enough once 48h have passed since
     * that midnight. Empty / unparseable dates fail open (kept), exactly
     * like isOldEnough. Pure — tested by TestHomeDelay2045.
     */
    static boolean isOldEnoughDate(String isoDate) {
        if (isoDate == null) return true;
        String d = isoDate.trim();
        if (d.length() < 10) return true; // unknown date: keep (fail open)
        try {
            int year = Integer.parseInt(d.substring(0, 4));
            int month = Integer.parseInt(d.substring(5, 7));
            int day = Integer.parseInt(d.substring(8, 10));
            if (d.charAt(4) != '-' || d.charAt(7) != '-') return true;
            if (month < 1 || month > 12 || day < 1 || day > 31) return true;
            java.util.Calendar cal =
                    java.util.Calendar.getInstance(
                            java.util.TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(year, month - 1, day, 0, 0, 0);
            return cal.getTimeInMillis()
                    <= System.currentTimeMillis() - RELEASE_DELAY_MS;
        } catch (Throwable ignored) {
            return true; // unparseable date: keep (fail open)
        }
    }

    /** Strip the "(共N季)" suffix awwrated appends to series titles. */
    private static String searchTerm(String title) {
        if (title == null) return "";
        return title.trim().replaceAll("\\s*[\\(\\（]共\\d+季[\\)\\）]\\s*$", "").trim();
    }

    /** Script-insensitive match key: Simplified, no punctuation/spaces. */
    private static String matchKey(String s) {
        if (s == null) return "";
        return Cms.normTitle(ChineseConvert.t2s(searchTerm(s)));
    }

    /**
     * Title match, tolerant of season suffixes on either side
     * ("绝命毒师 第一季" matches "绝命毒师").
     * 2.0.42: strict — see SearchVariants.strictTitleMatch. The old
     * q.startsWith(r) clause let a shorter result match a longer query
     * (source result 「交換身分」 matched query 「交換身分過聖誕」), so a
     * title no source has passed the strict home filter.
     */
    private static boolean titlesMatch(String queryTitle, String resultTitle) {
        return SearchVariants.strictTitleMatch(
                matchKey(queryTitle), matchKey(resultTitle));
    }

    /**
     * Normalize a source area string to a region code. Taiwan / China /
     * Korea / USA are kept; anything else (Japan, Hong Kong, France,
     * Thailand, 其它 …) is OTHER; a missing area is UNKNOWN (kept).
     * 2.0.57: also recognise the two-letter region codes (TW/CN/KR/US)
     * some sources return instead of a Chinese area name, so a Taiwan
     * title whose source says "TW" is not dropped as OTHER. The code
     * is matched as a whole token, so "AUS" (Australia) does not match
     * "US". Package-visible for the plain-JVM checks.
     */
    static String regionOf(String area) {
        if (area == null || area.trim().isEmpty()) return REGION_UNKNOWN;
        String a = ChineseConvert.t2s(area).replaceAll("\\s", "");
        if (a.contains("台湾")) return REGION_TW;
        if (a.contains("大陆") || a.contains("中国") || a.contains("香港")) return REGION_CN;
        if (a.contains("韩国")) return REGION_KR;
        if (a.contains("美国")) return REGION_US;
        String upper = a.toUpperCase(java.util.Locale.ROOT);
        for (String token : upper.split("[,、/;|]+")) {
            if ("TW".equals(token)) return REGION_TW;
            if ("CN".equals(token)) return REGION_CN;
            if ("KR".equals(token)) return REGION_KR;
            if ("US".equals(token)) return REGION_US;
        }
        return REGION_OTHER;
    }

    private static boolean cacheHasPlatformInfo(List<Cms.Video> videos) {
        for (Cms.Video v : videos) {
            if (v.platform != null && !v.platform.isEmpty()) return true;
        }
        return false;
    }

    /** Disk-cached home list (may be empty when never fetched). Never throws. */
    static List<Cms.Video> loadCache(File filesDir) {
        List<Cms.Video> out = new ArrayList<>();
        if (filesDir == null) return out;
        try {
            File f = new File(filesDir, CACHE_FILE);
            if (!f.exists() || f.length() <= 0 || f.length() > 8L*1024*1024) return out;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            JSONArray arr = new JSONArray(new String(buf, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = o.optString("t", "").trim();
                if (title.isEmpty()) continue;
                Cms.Video v = new Cms.Video();
                v.id = "msearch:" + title;
                v.title = title;
                v.poster = o.optString("p", "");
                v.remarks = o.optString("r", "");
                v.year = o.optString("y", "");
                v.tag = "awwrated";
                v.platform = o.optString("pf", "");
                v.releaseTs = o.optLong("rd", 0);
                out.add(v);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveCache(File filesDir, List<Cms.Video> videos) {
        if (filesDir == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Cms.Video v : videos) {
                JSONObject o = new JSONObject();
                o.put("t", v.title == null ? "" : v.title);
                o.put("p", v.poster == null ? "" : v.poster);
                o.put("r", v.remarks == null ? "" : v.remarks);
                o.put("y", v.year == null ? "" : v.year);
                o.put("pf", v.platform == null ? "" : v.platform);
                o.put("rd", v.releaseTs);
                arr.put(o);
            }
            try (FileOutputStream out =
                    new FileOutputStream(new File(filesDir, CACHE_FILE))) {
                out.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    /** One background refresh at a time; updates the disk cache only. */
    private static void refreshInBackground(final File filesDir) {
        if (refreshing) return;
        refreshing = true;
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    List<Cms.Video> fresh = fetchLive();
                                    if (!fresh.isEmpty()) saveCache(filesDir, fresh);
                                } catch (Throwable ignored) {
                                } finally {
                                    refreshing = false;
                                }
                            }
                        },
                        "awwrated-home-refresh")
                .start();
    }

    private static Map<String, String> userAgentHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; POCO F5) AppleWebKit/537.36"
                        + " (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        return headers;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String trimRating(double r) {
        String s = Double.toString(Math.round(r * 10) / 10.0);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Year from a unix-seconds timestamp; "" when unknown. */
    private static String yearFromTs(long tsSeconds) {
        if (tsSeconds <= 0) return "";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(tsSeconds * 1000L);
        return String.valueOf(c.get(java.util.Calendar.YEAR));
    }
}
