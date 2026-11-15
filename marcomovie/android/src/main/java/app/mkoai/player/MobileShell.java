package app.mkoai.player;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dedicated Android Phone & Tablet application shell styled 1:1 after the iPhone Portrait Liquid
 * Glass UI (`AdaptiveShell` + `AppleLiquidGlassDock`):
 *
 * <ul>
 *   <li>Edge-to-edge transparent status bar over an emerald-obsidian ambient canvas
 *   <li>Floating iOS 27 Liquid Glass 4-Tab Pill Dock (`主页` · `直播` · `片库` · `设置`) with a translucent
 *       selection lens
 *   <li>Scrollable Home header with brand logo, source capsule, netdisk capsule, and search button
 *   <li>3-column `2:3` portrait poster grid with bottom gradient `remarks` overlay and two-line
 *       `title` + `year · area` metadata below
 * </ul>
 */
final class MobileShell {
    static int activeTab = 0;
    static String pendingSearchKeyword = "";

    /** Persistent search session state so returning from Detail keeps search results resident. */
    static final class CachedSearchHit {
        final Cms.Site site;
        final Cms.Video video;

        CachedSearchHit(Cms.Site site, Cms.Video video) {
            this.site = site;
            this.video = video;
        }
    }

    static String cachedSearchKeyword = "";
    static String cachedSearchStatus = "";
    static String cachedSearchSiteFilter = "";
    static final List<CachedSearchHit> cachedSearchHits = new ArrayList<>();
    /**
     * mkoai 2.0.48: (sourceApi, title) pairs whose live poster fetch
     * COMPLETED with no match this session (maybeEnrichPoster step
     * (c)). Only a completed search with no match is remembered — a
     * transient failure (engine not ready, slow network, interrupt)
     * is NOT recorded, so the next card build retries it. 2.0.47's
     * posterLiveMiss recorded every failure and poisoned the pair
     * for the whole process.
     */
    static final java.util.Set<String> posterAbsent =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /**
     * mkoai 2.0.48: live ImageViews waiting for a poster, by title.
     * Every poster-less card registers its ImageView here at build
     * time; when a poster resolves, it is delivered to whichever
     * view is CURRENTLY showing that title — never to a stale
     * instance detached by a re-render (2.0.47 held the one
     * ImageView built with the card, so a poster fetched after a
     * Phase B / Phase 2 re-render landed on a detached view and the
     * new card stayed blank). Weak references: dead views drop
     * out on their own, no manual unregister.
     */
    static final java.util.Map<String, java.util.Set<java.lang.ref.WeakReference<ImageView>>>
            posterWaiters =
                    new java.util.concurrent.ConcurrentHashMap<
                            String,
                            java.util.Set<java.lang.ref.WeakReference<ImageView>>>();
    static int cachedSearchScrollY = 0;
    static int cachedSiteFilterScrollX = 0;
    static SearchCoordinator activeSearchCoordinator;
    static boolean cachedSearchPrivate;

    static void clearSearchSession() {
        if (activeSearchCoordinator != null) activeSearchCoordinator.close();
        activeSearchCoordinator = null;
        cachedSearchKeyword =
                pendingSearchKeyword = cachedSearchStatus = cachedSearchSiteFilter = "";
        cachedSearchHits.clear();
        cachedSearchScrollY = cachedSiteFilterScrollX = 0;
    }

    /**
     * Sub-page of the 设置 tab:
     * 0 = Main 5-card index, 1 = 片源与直播配置, 2 = 播放、解码与字幕, 3 = 界面外观与偏好, 4 = 备份、更新与诊断.
     */
    static int settingsSubPage = 0;

    private final MainActivity a;
    private FrameLayout contentContainer;
    private AmbientCanvasView ambientCanvas;
    // mkoai 2.0.38 (Emby style): hero-following ambient background.
    private HomeAmbient homeAmbientView;
    // mkoai 2.0.63: tap-vs-swipe for the hero carousel — set by the pager's
    // touch handling when a gesture proves itself a drag; the hero card's
    // click listener ignores the tap in that case, so a swipe never opens
    // the detail page. Reset on every ACTION_DOWN.
    private boolean heroSwipeDragged;

    // mkoai 2.0.63: hero diffusion (擴散） — the phone-home hero's colours
    // bleed into the page below (Emby reference). The home ScrollView
    // background is the current hero's muted + darkened tone; the hero
    // card's bottom scrim fades into exactly that colour, so there is no
    // hard edge and no black band. Sampling runs off the UI thread and
    // never blocks a tap; one tone is cached per hero item.
    // mkoai 2.0.64: the background is a vertical bloom, not one flat
    // colour — the tone at its strongest near the hero, fading outward
    // into a deeper pass of the same hue further down — so the hero's
    // colour spills past the image's edges instead of ending at a line.
    private int heroDiffusionShown = HomeRows2061.HOME_SLATE_BG;
    private int heroDiffusionTarget = HomeRows2061.HOME_SLATE_BG;
    private int heroDiffusionPage = -1;
    private int heroDiffusionGen = 0;
    // mkoai 2.0.65: the bloom's tone hold, as a fraction of the
    // viewport height — the hero's bottom edge at rest (see
    // HeroDiffusion2063.heroBottomFraction). Recomputed every time the
    // carousel is rebuilt; the animator tints the bloom from it so the
    // rest state already looks like the scrolled state.
    private float heroDiffusionHoldFraction = 0.40f;
    private ValueAnimator heroDiffusionAnimator;
    /**
     * mkoai 2.0.64: the page-background bloom — one GradientDrawable,
     * created once and re-tinted per animation frame (never re-created),
     * so the home ScrollView background is the bloom instead of a flat
     * colour. Null until the first diffusion frame paints it.
     */
    private android.graphics.drawable.GradientDrawable heroBloomGradient;
    // mkoai 2.0.64: the phone-home top bar + the top-edge fade follow the
    // hero diffusion too, so the bar is not a separate dark band. The bar
    // is rebuilt on every home render (homeTopBar is re-pointed in
    // buildHomeTopHeaderRow); the fade lives on the app root and outlives
    // the home tab, so it is only tinted while the home tab is active.
    private LinearLayout homeTopBar;
    private TopEdgeFadeView homeTopFade;
    // mkoai 2.0.66: the bar is a floating overlay now — homeTopBar
    // points at the overlay row, and the tone-following parts below are
    // re-tinted in retintTopOverlay().
    // The floating top overlay's tone-following parts — the pill +
    // circles whose backgrounds take the page tone, the texts whose
    // colour follows it, and the glyphs whose tint does. Rebuilt on
    // every home render (cleared in buildHomeTopHeaderRow).
    private LinearLayout homeTopPill;
    private final java.util.List<View> homeTopToneBgs = new ArrayList<>();
    private final java.util.List<TextView> homeTopToneTexts = new ArrayList<>();
    private final java.util.List<NavGlyph> homeTopGlyphs = new ArrayList<>();
    // mkoai 2.0.66: home text that follows the page tone — (view, role)
    // pairs re-tinted when the light/dark state flips or a fresh build
    // lands, never per animation frame. Roles: 0 = primary (section /
    // card / hero title), 1 = muted (year line / subtitle / view-all /
    // status). Views whose text sits on poster art (card titles, hero
    // text, badges) are never registered — they keep their white +
    // shadow look on the photo.
    private static final int HOME_TEXT_PRIMARY = 0;
    private static final int HOME_TEXT_MUTED = 1;
    private final java.util.List<View> homeTextViews = new ArrayList<>();
    private final java.util.List<Integer> homeTextRoles = new ArrayList<>();
    private boolean homeTextDirty = false;
    private boolean homeIsLight = false;
    // mkoai 2.0.66: Continue Watching progress tracks, re-tinted with
    // the page tone in the flip block (same pruning as the text list).
    private final java.util.List<View> homeProgressTracks = new ArrayList<>();
    private final java.util.List<ImageView> heroArtworks = new ArrayList<>();
    private final java.util.List<View> heroScrims = new ArrayList<>();
    private final java.util.List<Cms.Video> heroCandidates = new ArrayList<>();
    private final java.util.Map<String, Integer> heroToneCache = new HashMap<>();
    // mkoai 2.0.66: the hero ratings rows, one per carousel page (same
    // order as heroScrims) — the lazy backdrop/ratings lookup reveals
    // one when the TMDB disk cache yields a vote. And the artwork keys
    // the lazy lookup has already resolved, so it runs once per page,
    // never on every rotation.
    private final java.util.List<TextView> heroRatings = new ArrayList<>();
    private final java.util.Set<String> heroBackdropResolved = new java.util.HashSet<>();
    private LinearLayout floatingDock;
    // mkoai 2.0.66: the bottom-edge veil behind the dock (kept as a field
    // so the flip block in applyDiffusionFrame can re-tint it with the
    // page tone; it used to be a render-local with a fixed dark base).
    private BottomEdgeFadeView bottomEdgeFade;
    private final View[] navTabs = new View[5];
    private final TextView[] navLabels = new TextView[5];
    private final NavGlyph[] navIcons = new NavGlyph[5];
    // mkoai 2.0.38: 片庫 is now 收藏 (favorites only).
    private static final String[] TAB_NAMES = {"主頁", "搜尋", "直播", "收藏", "設定"};
    private static final int[] DOCK_TAB_INDICES = {0, 2, 3, 4};

    // mkoai 2.0.38: the 直播 tab only exists while a live source is
    // configured. sourcesFor() parses the whole config JSON, so the
    // result is cached per config object + custom URL (a non-empty
    // custom URL alone counts as a live source).
    private static Cms.Config cachedLiveConfig;
    private static String cachedLiveUrl;
    private static boolean cachedHasLive;

    private boolean hasLiveSources() {
        String liveUrl = a.prefs.getString(Prefs.LIVE_URL, "");
        if (liveUrl != null && !liveUrl.trim().isEmpty()) return true;
        if (a.config == cachedLiveConfig && liveUrl.equals(cachedLiveUrl)) {
            return cachedHasLive;
        }
        boolean has = false;
        try {
            has = !LiveController.sourcesFor(a).isEmpty();
        } catch (Throwable ignored) {
        }
        cachedLiveConfig = a.config;
        cachedLiveUrl = liveUrl;
        cachedHasLive = has;
        return has;
    }

    /** Dock tabs for the current state (直播 dropped when no live source exists). */
    private int[] dockTabs() {
        return hasLiveSources() ? DOCK_TAB_INDICES : new int[] {0, 3, 4};
    }

    void applyAppearancePreferences() {
        if (ambientCanvas != null) {
            ambientCanvas.reloadWallpaper();
            ambientCanvas.invalidate();
        }
        if (floatingDock != null) {
            floatingDock.setBackground(dockBackground());
            floatingDock.invalidate();
        }
    }

    /**
     * mkoai 2.0.66: the floating dock's background. On the home tab it
     * follows the page tone — a light pill (near-white gradient) on a
     * light page, the fixed dark glass on a dark page — so the dock is
     * never a dead-white pill on a light page or invisible on a dark
     * one. Anywhere else it is the fixed dark glass (today's look).
     * The glass-intensity alphas are respected in both cases, and the
     * entries, size and tap handling never change.
     */
    private GradientDrawable dockBackground() {
        int intensity =
                a.prefs.getInt(
                        Prefs.MOBILE_GLASS_INTENSITY,
                        a.prefs.getBoolean(Prefs.REDUCED_TRANSPARENCY, false) ? 100 : 65);
        int topAlpha = Math.round(0x88 + (intensity / 100f) * 0x6c);
        int botAlpha = Math.round(0x7c + (intensity / 100f) * 0x74);
        int topRgb;
        int botRgb;
        int stroke;
        if (activeTab == 0) {
            topRgb = HomeTone2066.dockTopRgb(heroDiffusionShown);
            botRgb = HomeTone2066.dockBottomRgb(heroDiffusionShown);
            stroke = HomeTone2066.dockStroke(heroDiffusionShown);
        } else {
            topRgb = 0x001b2421;
            botRgb = 0x00111715;
            stroke = 0x36ffffff;
        }
        return glassBg(
                (topAlpha << 24) | topRgb,
                (botAlpha << 24) | botRgb,
                a.dp(999),
                a.dp(0.9f),
                stroke);
    }

    /** A dock label / glyph colour for the current page tone and selected state. */
    private int dockLabelColor(boolean selected) {
        int tone = activeTab == 0 ? heroDiffusionShown : HomeRows2061.HOME_SLATE_BG;
        return HomeTone2066.dockLabel(tone, selected);
    }

    /** The active-tab lens for the current page tone (dark glass on a light pill, white glass otherwise). */
    private GradientDrawable dockLensBg() {
        int tone = activeTab == 0 ? heroDiffusionShown : HomeRows2061.HOME_SLATE_BG;
        return glassBg(
                HomeTone2066.dockLensTop(tone),
                HomeTone2066.dockLensBottom(tone),
                a.dp(20),
                a.dp(0.8f),
                HomeTone2066.dockLensStroke(tone));
    }

    /** Re-tints the dock's tabs (lens / labels / glyphs / weight) for the current tone + selection. */
    private void retintDockTabs() {
        // When on Search tab (1), highlight Home (0) in the 4-tab dock.
        int highlightedDockTab = activeTab == 1 ? 0 : activeTab;
        int[] dockTabList = dockTabs();
        for (int pos = 0; pos < dockTabList.length; pos++) {
            int i = dockTabList[pos];
            boolean selected = i == highlightedDockTab;
            if (navTabs[i] != null) {
                navTabs[i].setBackground(selected ? dockLensBg() : null);
            }
            if (navLabels[i] != null) {
                navLabels[i].setTextColor(dockLabelColor(selected));
                navLabels[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            }
            if (navIcons[i] != null) {
                navIcons[i].setSelectedState(selected);
                // setIconTint overrides the glyph's own selected colour,
                // so the tint must be selected-aware too (see dockLabel).
                navIcons[i].setIconTint(dockLabelColor(selected));
            }
        }
    }

    /**
     * mkoai 2.0.66: re-tints the floating bottom dock for the current
     * page tone — background, active-tab lens, labels and glyphs.
     * Called from applyDiffusionFrame's flip block and from switchTab
     * (selection changes re-tint the tabs). Cheap setBackground /
     * setTextColor / setIconTint calls only; a tap never waits for any
     * of this, and the dock never blocks content (it is a floating
     * pill with a bottom margin, as before).
     */
    private void retintDock() {
        if (floatingDock != null) {
            floatingDock.setBackground(dockBackground());
            floatingDock.invalidate();
        }
        retintDockTabs();
    }

    // Home tab state (persisted across MobileShell re-renders for the same active site)
    private Cms.Site currentSite;
    static String cachedCategorySiteKey = "";
    static String selectedCategoryId = "";
    private int currentPage = 1;
    private int totalPages = 1;
    private final List<Cms.Video> currentVideos = new ArrayList<>();
    static List<Cms.Category> currentCategories = new ArrayList<>();
    static String filterSchema = "{}";
    static final java.util.Map<String, String> selectedFilters = new java.util.LinkedHashMap<>();
    static int cachedCatScrollX = 0;
    private int homeRequest;
    private boolean homeReady;
    private Runnable resumeHome;
    Runnable reloadCategory;
    private FrameLayout retainedRoot;
    private int retainedWidth, retainedHeight, retainedEpoch;
    private boolean renderedPortrait = true;

    // mkoai 2.0.38 (Emby style): TMDB home rows + "我的媒體" cards.
    // Row state lives on the shell so phase 2 (background live checks)
    // can update the rendered rows in place. tmdbRowsTicket is the
    // homeRequest the rows belong to; a newer home load drops stale work.
    private LinearLayout tmdbRowsContainerRef;
    private LinearLayout mediaCardsContainerRef;
    private List<TmdbRow> tmdbRows = new ArrayList<>();
    private int tmdbRowsTicket = -1;
    private int tmdbRowsRenderEpoch;
    private boolean tmdbRowsLoading = false;
    // mkoai 2.0.51: the home status line, so the TMDB settle pass can
    // show the honest empty copy only once the checks have finished.
    private TextView homeStatusTextRef;

    /** Any TMDB row with something to show (confirmed, else pending fail-open). */
    private boolean hasVisibleTmdbRows() {
        for (TmdbRow row : tmdbRows) {
            if (!row.display().isEmpty()) return true;
        }
        return false;
    }

    /**
     * mkoai 2.0.51: decides the home status line once the awwrated page
     * has loaded. While the TMDB availability checks are still running,
     * the home must not claim "暫無影片" — the recommendation rows are
     * still on their way (or already showing fail-open titles). The
     * empty copy appears only when the checks have settled AND the home
     * is genuinely empty everywhere. Runs on the UI thread.
     */
    private void refreshHomeEmptyState() {
        if (homeStatusTextRef == null || a.isFinishing() || activeTab != 0) return;
        if (!selectedCategoryId.isEmpty() || currentPage != 1) return;
        if (!currentVideos.isEmpty()) {
            homeStatusTextRef.setVisibility(View.GONE);
            return;
        }
        if (tmdbRowsLoading) {
            homeStatusTextRef.setVisibility(View.VISIBLE);
            homeStatusTextRef.setText("正在加载推荐…");
            return;
        }
        if (hasVisibleTmdbRows()) {
            homeStatusTextRef.setVisibility(View.GONE);
            return;
        }
        homeStatusTextRef.setVisibility(View.VISIBLE);
        homeStatusTextRef.setText(
                selectedFilters.isEmpty()
                        ? "当前分类暂无影片"
                        : "当前筛选暂无匹配，可继续加载更多影片");
    }

    // 2.0.41: live TMDB list page — the open list page reads the live
    // TmdbRow (not a tap-time snapshot), so titles confirmed by the
    // background cross-source check appear as they arrive. Static
    // because a new MobileShell is created per render; only the row +
    // screen identity are kept (no View refs — the grid/count are found
    // by tag on refresh, so nothing leaks the activity).
    private static TmdbRow openListRow;
    private static Cms.Site openListSite;
    private static MainActivity.Screen openListScreen;
    private static int openListShown = -1;

    /**
     * mkoai 2.0.67: the home tone the open TMDB list page was opened
     * with — captured in openTmdbList at open time from the hero tone
     * then showing (heroDiffusionShown), so the view-all page follows
     * the home's current colour. A refresh while the page is open
     * keeps this tone (it re-takes it on the next open); a hero
     * rotation behind the page never re-tints it. DARK until the
     * first capture — a wallpaper home has no tone to follow, so it
     * keeps today's dark page.
     */
    private static int openListTone = MainActivity.DARK;

    /** One TMDB home row: its list kind, its confirmed titles, and titles still unchecked. */
    private static final class TmdbRow {
        final String title;
        final String kind;
        final String extra;
        final List<Cms.Video> confirmed = new ArrayList<>();
        final List<Cms.Video> pending = new ArrayList<>();
        boolean listsLoaded = false;

        /**
         * mkoai 2.0.51: what the home actually shows for this row —
         * confirmed titles when there are any, otherwise the pending
         * (not yet source-checked) titles as fail-open recommendations.
         * The home never goes blank while checks are running or when
         * no source confirms anything. Returns a copy: Phase B mutates
         * the live lists on a background thread.
         */
        List<Cms.Video> display() {
            List<Cms.Video> shown = !confirmed.isEmpty() ? confirmed : pending;
            return new ArrayList<>(shown);
        }

        TmdbRow(String title, String kind, String extra) {
            this.title = title;
            this.kind = kind;
            this.extra = extra;
        }
    }

    // mkoai 2.0.61: TMDB home row definitions moved to HomeRows2061
    // (Simplified labels, reference-app order, movies → dramas →
    // variety). kind/extra feed TmdbClient.list(); "latest" merges
    // now_playing + airing_today, "top_rated" merges movie+tv top
    // rated, "documentary" merges movie+tv genre 99. The "douban_top"
    // kind is fetched from DoubanHome, not TmdbClient (see the
    // metadata-job loop below).
    private static final String[][] TMDB_ROW_DEFS = HomeRows2061.TMDB_ROW_DEFS;

    private static final int TMDB_ROW_MAX_ITEMS = 20;
    private static final int TMDB_ROW_SHOW_ITEMS = 12;
    // mkoai 2.0.39: cross-source availability — 8 threads, 60s total
    // budget shared across source rounds, 120 titles collected
    // round-robin across rows (2.0.38: 6 threads / 30s / 80 titles,
    // first rows only — later rows never got a live check).
    private static final int TMDB_CHECK_THREADS = 8;
    private static final long TMDB_CHECK_BUDGET_MS = 60_000L;
    private static final int TMDB_CHECK_MAX_TITLES = 120;
    // mkoai 2.0.51: no cap on live-checked sources — ALL configured
    // sources are home candidates (was: at most 10, so the 11th+
    // sources never confirmed anything and the home could stay empty).
    // Live rounds are still bounded by the shared 60s budget, 8
    // threads and 120 titles, and stop as soon as every title is
    // confirmed, so checking more sources does not slow the first
    // paint (which never waits for the live check).

    private String retainedSources = "";
    private String enabledSourceFingerprint() {
        StringBuilder key = new StringBuilder();
        if (a.config != null) for (Cms.Site site : a.config.sites) {
            if (site != null && a.available(site)) key.append(site.key).append('|').append(site.api).append('\n');
        }
        return key.toString();
    }
    boolean resume() {
        if (!homeReady
                || activeTab != 0
                || retainedRoot == null
                || !retainedSources.equals(enabledSourceFingerprint())
                || retainedEpoch != a.engineEpoch
                || retainedWidth != a.windowWidth()
                || retainedHeight != a.windowHeight()
                || renderedPortrait != a.isPortrait()) return false;
        a.cancelPageRequests();
        a.cancelArtwork();
        a.releasePlayer();
        a.generation++;
        ++homeRequest;
        if (resumeHome != null) resumeHome.run();
        refreshHeaderNetdiskCapsule();
        a.root = retainedRoot;
        a.setContentView(retainedRoot);
        a.watchRootLayout(retainedRoot);
        a.applyMobileSystemBars(false);
        // mkoai 2.0.48: re-kick poster loads on the retained cards.
        // An ImageLoader load whose generation ticket was bumped by a
        // navigation is dropped silently, and resume() re-attaches the
        // retained root without rebuilding cards — without this, a card
        // whose poster URL was already known stayed blank for the rest
        // of the visit (2.0.47). Views whose tag is a poster URL get
        // their load restarted (memory-cache hits are instant);
        // mkoai 2.0.49: poster-less cards ("tmdb-poster:" tag) are
        // re-kicked too — rekindlePosters re-runs maybeEnrichPoster
        // for them, since the in-flight artwork-pool fetch alone
        // cannot repaint a retained card after a generation bump.
        rekindlePosters(retainedRoot);
        a.scroll = homeScroll;
        a.body = homeBody;
        if (a.current != null) a.current.home = true;
        return true;
    }

    /**
     * mkoai 2.0.48: restart image loads for every ImageView under
     * {@code root} whose tag is already a poster URL (set by
     * ImageLoader.poster at build time). Used by resume(), which
     * re-attaches the retained home without rebuilding cards.
     * mkoai 2.0.49: ALSO re-kick poster-less cards (tag
     * "tmdb-poster:&lt;title&gt;"). The 2.0.48 version only restarted
     * URL-tagged views, so a card whose poster fetch was still in
     * flight — or had never started — when the user navigated away
     * never got enrichment again for the rest of the visit: the
     * fetch itself survives on the artwork pool, but its ImageLoader
     * load is dropped by the generation guard, and nothing re-ran
     * maybeEnrichPoster for the retained card. Re-running it here
     * is bounded by posterAbsent (a completed no-match is not
     * retried) and re-registers the live view as a waiter, so a
     * poster that lands later still reaches the card on screen.
     */
    private void rekindlePosters(android.view.View root) {
        try {
            if (root == null) return;
            if (root instanceof ImageView) {
                Object tag = root.getTag();
                if (tag instanceof String) {
                    String t = (String) tag;
                    if (t.startsWith("http://") || t.startsWith("https://")) {
                        a.poster(t, (ImageView) root);
                    } else if (t.startsWith("tmdb-poster:")) {
                        String title = t.substring("tmdb-poster:".length());
                        if (!title.isEmpty()) {
                            maybeEnrichPoster((ImageView) root, title, null);
                        }
                    }
                }
                return;
            }
            if (root instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) rekindlePosters(g.getChildAt(i));
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshHeaderNetdiskCapsule() {
        if (retainedRoot == null) return;
        View existing = retainedRoot.findViewWithTag("home-netdisk-button");
        if (existing == null || !(existing.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) existing.getParent();
        int idx = parent.indexOfChild(existing);
        if (idx < 0) return;
        ViewGroup.LayoutParams lp = existing.getLayoutParams();
        View replacement =
                AccountPanel.buildHeaderCapsule(
                        a,
                        () -> {
                            if (a.config == null) showMobileImportDialog(a);
                            else AccountPanel.show(a, null);
                        });
        parent.removeViewAt(idx);
        parent.addView(replacement, idx, lp);
    }

    MobileShell(MainActivity activity) {
        this.a = activity;
    }

    static void show(MainActivity a) {
        new MobileShell(a).render();
    }

    static void openSearchTab(MainActivity a, String initialKeyword) {
        activeTab = 1;
        String kw = initialKeyword == null ? "" : initialKeyword.trim();
        if (!kw.isEmpty()) {
            pendingSearchKeyword = kw;
        }
        if (a.current == null || !a.current.home) {
            boolean pushBack = a.current != null && !a.current.redirect;
            a.open(
                    new MainActivity.Screen(
                            () -> {
                                activeTab = 1;
                                new MobileShell(a).render();
                            }),
                    pushBack);
        } else {
            new MobileShell(a).render();
        }
    }

    private int statusBarHeight() {
        return a.statusBarHeight();
    }

    static GradientDrawable glassBg(
            int topColor, int bottomColor, float radiusPx, float strokePx, int strokeColor) {
        GradientDrawable gd =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM, new int[] {topColor, bottomColor});
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            gd.setStroke(Math.max(1, Math.round(strokePx)), strokeColor);
        }
        return gd;
    }

    /**
     * Bottom-sheet drawer selector for all customizable settings on mobile (replaces tap-to-toggle
     * and modal dialogs with a unified slide-up drawer).
     */
    static void showDrawerPicker(
            MainActivity a,
            String title,
            String[] options,
            int selectedIndex,
            PlayerBubble.IntAction onSelect) {
        showDrawerPicker(a, title, "", options, selectedIndex, onSelect);
    }

    static void showDrawerPicker(
            MainActivity a,
            String title,
            String subtitle,
            String[] options,
            int selectedIndex,
            PlayerBubble.IntAction onSelect) {
        final android.app.Dialog dlg = new android.app.Dialog(a);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().getDecorView().setTag("app-dialog");
            dlg.getWindow()
                    .setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dlg.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dlg.getWindow()
                    .setLayout(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            android.view.WindowManager.LayoutParams.MATCH_PARENT);
        }

        FrameLayout backdrop = new FrameLayout(a);
        backdrop.setMinimumWidth(a.windowWidth());
        backdrop.setMinimumHeight(a.windowHeight());
        backdrop.setBackgroundColor(0x96000000);
        backdrop.setOnClickListener(v -> dlg.dismiss());

        int cardWidth = Math.min(a.windowWidth() - a.dp(32), a.dp(410));
        LinearLayout sheet = a.vertical();
        sheet.setTag("mobile-drawer");
        sheet.setClickable(true);
        sheet.setMinimumWidth(cardWidth);
        sheet.setPadding(a.dp(20), a.dp(18), a.dp(20), a.dp(16));
        GradientDrawable sheetBg =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0xf41c2421, 0xf8111715});
        sheetBg.setCornerRadius(a.dp(26));
        sheetBg.setStroke(Math.max(1, a.dp(1)), 0x36ffffff);
        sheet.setBackground(sheetBg);

        LinearLayout headerRow = a.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = a.vertical();
        TextView titleView = a.text(title, 18, Color.WHITE);
        titleView.setTypeface(null, Typeface.BOLD);
        titleCol.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = a.text(subtitle, 12.5f, MainActivity.MUTED);
            subView.setPadding(0, a.dp(4), 0, 0);
            titleCol.addView(subView);
        }
        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView closeBtn = a.text("✕", 13.5f, 0xffe5e7eb);
        closeBtn.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.OVAL);
        closeBg.setColor(0x22ffffff);
        closeBg.setStroke(Math.max(1, a.dp(1)), 0x28ffffff);
        closeBtn.setBackground(closeBg);
        MobileDetailScreen.bindTouchFeedback(closeBtn);
        closeBtn.setOnClickListener(v -> dlg.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(a.dp(32), a.dp(32));
        closeLp.leftMargin = a.dp(8);
        headerRow.addView(closeBtn, closeLp);
        sheet.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        a.gap(sheet, 14);

        LinearLayout list = a.vertical();
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            boolean active = (i == selectedIndex);
            LinearLayout optRow = a.row();
            optRow.setGravity(Gravity.CENTER_VERTICAL);
            optRow.setPadding(a.dp(16), a.dp(13), a.dp(16), a.dp(13));
            optRow.setBackground(
                    active
                            ? glassBg(0x380a84ff, 0x260a84ff, a.dp(16), a.dp(1f), 0x885ac8fa)
                            : glassBg(0xb8202926, 0xb818201d, a.dp(16), a.dp(0.7f), 0x22ffffff));
            MobileDetailScreen.bindTouchFeedback(optRow);

            TextView label = a.text(options[i], 14.5f, active ? Color.WHITE : 0xffe5e7eb);
            label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            optRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView check = a.text(active ? "✓" : "", 16, 0xff5ac8fa);
            check.setTypeface(null, Typeface.BOLD);
            optRow.addView(check, new LinearLayout.LayoutParams(-2, -2));

            optRow.setOnClickListener(
                    v -> {
                        dlg.dismiss();
                        if (onSelect != null) onSelect.accept(idx);
                    });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = i == options.length - 1 ? a.dp(2) : a.dp(8);
            list.addView(optRow, lp);
        }
        int maxScrollHeight = Math.max(a.dp(180), Math.round(a.windowHeight() * 0.58f));
        list.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth - a.dp(40), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        sheet.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        -1,
                        Math.min(maxScrollHeight, Math.max(a.dp(48), list.getMeasuredHeight()))));

        FrameLayout.LayoutParams sheetLp =
                new FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER);
        sheetLp.topMargin = a.dp(24);
        sheetLp.bottomMargin = a.dp(24);
        backdrop.addView(sheet, sheetLp);

        dlg.setContentView(
                backdrop,
                new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow()
                    .setLayout(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            android.view.WindowManager.LayoutParams.MATCH_PARENT);
            dlg.getWindow().setGravity(Gravity.CENTER);
        }
        if (!a.reduced) {
            sheet.setScaleX(0.95f);
            sheet.setScaleY(0.95f);
            sheet.setAlpha(0f);
            sheet.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(165)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    static void showMobileImportDialog(MainActivity a) {
        LinearLayout panel = a.vertical();
        panel.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(4));

        TextView tip =
                a.text("輸入或貼上配置網址（JSON / JS / PY / TXT / M3U / ZIP）或原始碼文字：", 13, MainActivity.MUTED);
        panel.addView(tip);
        a.gap(panel, 10);

        final EditText input = new EditText(a);
        input.setHint("https://…/config.json 或 .js / .py / .txt / .zip");
        input.setHintTextColor(0xff6b7280);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setShowSoftInputOnFocus(true);
        input.setLongClickable(true);
        input.setTextIsSelectable(true);
        input.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        input.setBackground(a.bg(0xff1e2623, 12));
        String savedOrigin = a.prefs.getString(Prefs.ORIGIN, "");
        if (!savedOrigin.isEmpty()) {
            input.setText(savedOrigin);
            input.setSelection(savedOrigin.length());
        }
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 12);

        TextView pasteBtn = a.text("一鍵讀取手機剪貼簿並直接匯入", 13, Color.WHITE);
        pasteBtn.setTypeface(null, Typeface.BOLD);
        pasteBtn.setGravity(Gravity.CENTER);
        pasteBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        pasteBtn.setBackground(a.bg(0xffe34b58, 999));
        MobileDetailScreen.bindTouchFeedback(pasteBtn);
        panel.addView(pasteBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView fileBtn = a.text("選擇本地配置或本地包 (.json/.js/.py/.txt/.zip)", 13, MainActivity.WHITE);
        fileBtn.setGravity(Gravity.CENTER);
        fileBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        fileBtn.setBackground(a.bg(0xff1e2623, 999));
        MobileDetailScreen.bindTouchFeedback(fileBtn);
        panel.addView(fileBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(panel, 8);

        TextView liveBtn = a.text("匯入或管理電視直播源 (M3U / TXT / JSON)", 13, MainActivity.WHITE);
        liveBtn.setGravity(Gravity.CENTER);
        liveBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        liveBtn.setBackground(a.bg(0xff1e2623, 999));
        MobileDetailScreen.bindTouchFeedback(liveBtn);
        panel.addView(liveBtn, new LinearLayout.LayoutParams(-1, -2));

        final AppDialog dialog =
                new AppDialog.Builder(a)
                        .setTitle("匯入或更新配置")
                        .setView(panel)
                        .setNegativeButton("取消", null)
                        .setNeutralButton("連線 Emby", (d, w) -> EmbyEngine.connect(a))
                        .setPositiveButton(
                                "匯入輸入內容",
                                (d, w) -> {
                                    String raw = input.getText().toString().trim();
                                    if (raw.isEmpty()) {
                                        a.toast("請輸入配置網址或點選剪貼簿匯入");
                                        return;
                                    }
                                    importTextOrUrl(a, raw);
                                })
                        .create();

        pasteBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    pasteConfigFromClipboard(a);
                });
        fileBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    a.pickConfig();
                });
        liveBtn.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    SourceController.showLiveImportDialog(a);
                });
        dialog.show();
    }

    static void pasteConfigFromClipboard(MainActivity a) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = "";
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                text = clip.getItemAt(0).getText().toString().trim();
            }
        }
        if (text.isEmpty()) {
            a.toast("剪貼簿為空，請先複製配置網址或原始碼內容");
            return;
        }
        importTextOrUrl(a, text);
    }

    static void importTextOrUrl(MainActivity a, String rawInput) {
        final String pasted = rawInput == null ? "" : rawInput.trim();
        if (pasted.isEmpty()) {
            a.toast("請輸入有效的配置網址或原始碼內容");
            return;
        }
        a.toast("正在讀取並解析配置…");
        a.worker.execute(
                () -> {
                    try {
                        String origin = a.configOrigin;
                        String rawJson = pasted;
                        if (Cms.isHttp(pasted)) {
                            origin = Cms.normalizeHttp(pasted);
                            String lowerOrigin = origin.toLowerCase();
                            if (lowerOrigin.endsWith(".zip") || lowerOrigin.contains(".zip?")) {
                                java.net.HttpURLConnection conn =
                                        (java.net.HttpURLConnection)
                                                new java.net.URL(origin).openConnection();
                                conn.setConnectTimeout(15000);
                                conn.setReadTimeout(25000);
                                conn.setRequestProperty("User-Agent", Cms.USER_AGENT);
                                byte[] bytes;
                                try (java.io.InputStream in = conn.getInputStream();
                                        java.io.ByteArrayOutputStream bos =
                                                new java.io.ByteArrayOutputStream()) {
                                    byte[] buf = new byte[8192];
                                    int n;
                                    while ((n = in.read(buf)) != -1) {
                                        if (bos.size() + n > 28 * 1024 * 1024)
                                            throw new IOException("遠端包超過 28 MB 限制");
                                        bos.write(buf, 0, n);
                                    }
                                    bytes = bos.toByteArray();
                                } finally {
                                    conn.disconnect();
                                }
                                LocalSourcePackage pkg =
                                        LocalSourcePackage.open(a.getFilesDir(), bytes);
                                a.ui.post(
                                        () -> {
                                            try {
                                                SourceController.applyLocalPackage(a, pkg);
                                            } catch (Exception ex) {
                                                a.toast("儲存配置失敗：" + a.message(ex));
                                            }
                                        });
                                return;
                            } else {
                                rawJson = SourceController.fetchImport(a, origin);
                                origin = SourceController.importOrigin(origin);
                            }
                        }
                        try {
                            Cms.Config parsed = Cms.parseConfig(rawJson, origin);
                            final String savedOrigin = origin;
                            a.ui.post(
                                    () -> {
                                        try {
                                            a.saveConfig(parsed, savedOrigin);
                                            a.toast("配置匯入成功（共 " + parsed.sites.size() + " 個片源）");
                                        } catch (Exception ex) {
                                            a.toast("儲存配置失敗：" + a.message(ex));
                                        }
                                    });
                        } catch (Exception parseEx) {
                            String trimmed = rawJson == null ? "" : rawJson.trim();
                            if (trimmed.startsWith("#EXTM3U")
                                    || trimmed.contains("#genre#")
                                    || trimmed.contains(",http://")
                                    || trimmed.contains(",https://")
                                    || trimmed.contains(",rtmp://")
                                    || trimmed.contains(",rtsp://")) {
                                SourceController.importLiveTextOrUrl(a, pasted);
                                return;
                            }
                            throw parseEx;
                        }
                    } catch (Exception e) {
                        a.ui.post(() -> a.toast("配置讀取失敗：" + a.message(e)));
                    }
                });
    }

    void render() {
        a.clearPlaybackLoading();
        a.cancelArtwork();
        a.cancelPageRequests();
        if (a.current != null) a.current.home = true;
        if (a.searchScreen != null) {
            a.searchScreen.close();
            a.searchScreen = null;
        }
        a.stopWeb();
        a.stopMagnet();
        a.releasePlayer();
        a.generation++;

        // Edge-to-edge transparent status bar over the iPhone emerald-obsidian ambient canvas
        a.applyMobileSystemBars(false);

        resolveActiveSite();

        a.root = new FrameLayout(a);
        a.root.setBackgroundColor(0xff141414);
        a.setContentView(a.root);
        a.watchRootLayout(a.root);
        renderedPortrait = a.isPortrait();
        final boolean builtPortrait = renderedPortrait;
        final FrameLayout shellRoot = a.root;
        shellRoot.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> {
                    int w = r - l, h = b - t;
                    if (w <= 0 || h <= 0 || a.root != shellRoot) return;
                    a.usableWidth = w;
                    a.usableHeight = h;
                    if ((w < h) != builtPortrait) {
                        a.ui.post(
                                () -> {
                                    if (!a.isFinishing()
                                            && !a.isDestroyed()
                                            && a.root == shellRoot
                                            && a.current != null
                                            && a.current.home) {
                                        MobileShell.show(a);
                                    }
                                });
                    }
                });

        // Layer 0: iOS 27 Emerald-Obsidian Ambient Background / Custom Wallpaper
        ambientCanvas = new AmbientCanvasView(a);
        a.root.addView(ambientCanvas, new FrameLayout.LayoutParams(-1, -1));

        // mkoai 2.0.38 (Emby style): hero-following ambient background.
        // Sits above the canvas so the saturated hero wash tints the whole
        // home; skipped when the user has a custom wallpaper (same rule
        // as the renderPage ambient).
        homeAmbientView = null;
        if (a.prefs.getBoolean(Prefs.HOME_AMBIENT, true)
                && !a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) {
            homeAmbientView = new HomeAmbient(a);
            a.root.addView(homeAmbientView, new FrameLayout.LayoutParams(-1, -1));
        }

        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        boolean tabletSideRail = widthDp >= 640 && !a.isPortrait();
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;

        if (tabletSideRail) {
            LinearLayout mainRow = a.row();
            mainRow.setGravity(Gravity.TOP);
            a.root.addView(mainRow, new FrameLayout.LayoutParams(-1, -1));

            contentContainer = new FrameLayout(a);
            mainRow.addView(contentContainer, new LinearLayout.LayoutParams(0, -1, 1));

            FrameLayout railWrap = new FrameLayout(a);
            railWrap.setPadding(a.dp(8), statusBarHeight() + a.dp(12), a.dp(12), a.dp(12));
            LinearLayout sideRail = buildNavigationBar(true);
            floatingDock = sideRail;
            FrameLayout.LayoutParams railLp =
                    new FrameLayout.LayoutParams(
                            a.dp(72 * navScale), a.dp(296 * navScale), Gravity.CENTER_VERTICAL);
            railWrap.addView(sideRail, railLp);
            mainRow.addView(railWrap, new LinearLayout.LayoutParams(-2, -1));
        } else {
            // Layer 1: Full-bleed page container
            contentContainer = new FrameLayout(a);
            a.root.addView(contentContainer, new FrameLayout.LayoutParams(-1, -1));

            // Layer 2: Soft top status-bar edge fade (matching iPhone ShaderMask)
            TopEdgeFadeView topFade = new TopEdgeFadeView(a);
            // mkoai 2.0.64: the fade is retinted with the hero tone while
            // the home tab is active (see applyDiffusionFrame).
            homeTopFade = topFade;
            topFade.setClickable(false);
            topFade.setFocusable(false);
            a.root.addView(
                    topFade,
                    new FrameLayout.LayoutParams(-1, statusBarHeight() + a.dp(24), Gravity.TOP));

            // Layer 3: Bottom edge subtle vignette behind the floating dock
            BottomEdgeFadeView bottomFade = new BottomEdgeFadeView(a);
            bottomEdgeFade = bottomFade;
            bottomFade.setClickable(false);
            bottomFade.setFocusable(false);
            a.root.addView(
                    bottomFade,
                    new FrameLayout.LayoutParams(-1, a.dp(92 * navScale), Gravity.BOTTOM));

            // Layer 4: iPhone Floating Liquid Glass 4-Tab Pill Dock
            LinearLayout bottomDock = buildNavigationBar(false);
            floatingDock = bottomDock;
            int dockWidthPx =
                    Math.min(a.dp(286 * navScale), Math.max(a.dp(244), a.windowWidth() - a.dp(32)));
            FrameLayout.LayoutParams dockLp =
                    new FrameLayout.LayoutParams(
                            dockWidthPx,
                            a.dp(64 * navScale),
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dockLp.bottomMargin = a.dp(16) + a.navigationBarHeight();
            a.root.addView(bottomDock, dockLp);
        }

        switchTab(activeTab);
        retainedRoot = a.root;
        retainedWidth = a.windowWidth();
        retainedHeight = a.windowHeight();
        retainedEpoch = a.engineEpoch;
        retainedSources = enabledSourceFingerprint();
        if (a.current != null) a.current.mobileHome = this;
    }

    private void resolveActiveSite() {
        currentSite = null;
        if (a.config != null && !a.config.sites.isEmpty()) {
            String wanted = a.prefs.getString(Prefs.SELECTED_SITE, "");
            for (Cms.Site s : a.config.sites) {
                if (s.key.equals(wanted) && a.available(s)) {
                    currentSite = s;
                    break;
                }
            }
            if (currentSite == null) {
                for (Cms.Site s : a.config.sites) {
                    if (a.available(s)) {
                        currentSite = s;
                        a.prefs.edit().putString(Prefs.SELECTED_SITE, s.key).apply();
                        break;
                    }
                }
            }
        }
        String nextSiteKey = currentSite != null ? currentSite.key : "";
        if (!nextSiteKey.equals(cachedCategorySiteKey)) {
            cachedCategorySiteKey = nextSiteKey;
            selectedCategoryId = "";
            currentCategories = new ArrayList<>();
            filterSchema = "{}";
            selectedFilters.clear();
            cachedCatScrollX = 0;
        }
    }

    /**
     * mkoai 2.0.66: the floating top overlay (Emby style) — one tone
     * pill (logo + source name) + separate floating round buttons
     * (search / ⋯ / history / account) over the hero art. It is added
     * to contentContainer (the FrameLayout holding the ScrollView), not
     * to the scroll body, so the hero starts at the screen top and the
     * overlay floats over it without scrolling away. The pill and the
     * circles take solid tone backgrounds (not the dark a.glass), and
     * their text / glyphs are re-tinted with the page tone in
     * retintTopOverlay(). Layout order, sizes and actions are unchanged.
     */
    private View buildHomeTopHeaderRow() {
        LinearLayout bar = a.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);

        // The tone pill: brand + source in one floating pill (the
        // Emby Terminus+C pill). Flexible width — it takes what the
        // circles leave, and the source name truncates inside it.
        LinearLayout pill = a.row();
        pill.setTag("home-top-pill");
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(a.dp(6), 0, a.dp(6), 0);
        pill.setElevation(a.dp(4));
        homeTopPill = pill;
        homeTopToneBgs.clear();
        homeTopToneTexts.clear();
        homeTopGlyphs.clear();
        homeTopToneBgs.add(pill);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(0, a.dp(48), 1f);
        pillLp.rightMargin = a.dp(6);
        bar.addView(pill, pillLp);

        // Left: Brand Logo + Title
        LinearLayout brandRow = a.row();
        brandRow.setTag("home-brand");
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(a.dp(28), a.dp(28));
        logoLp.rightMargin = a.dp(8);
        brandRow.addView(logo, logoLp);

        boolean compactBrand =
                a.windowWidth() < a.dp(360) || a.getResources().getConfiguration().fontScale > 1.2f;
        TextView brandTitle =
                a.text(
                        a.privateMode ? "隱私 · 退出" : compactBrand ? "mkoai" : "mkoai",
                        compactBrand ? 12 : 14,
                        Color.WHITE);
        brandTitle.setTypeface(null, Typeface.BOLD);
        brandTitle.setSingleLine(!compactBrand);
        brandTitle.setMaxLines(compactBrand ? 2 : 1);
        brandTitle.setContentDescription(a.privateMode ? "退出隱私空間" : "mkoai");
        brandRow.addView(brandTitle);
        brandRow.setMinimumHeight(a.dp(44));
        brandRow.setOnClickListener(
                v -> {
                    if (a.privateMode) PrivacySpace.switchSpace(a, false);
                    else PrivacySpace.logoTap(a);
                });
        homeTopToneTexts.add(brandTitle);
        pill.addView(brandRow, new LinearLayout.LayoutParams(-2, -2));

        // Flexible middle slot: keeps `sitePill` right-aligned and guarantees it shrinks/truncates
        // before ever pushing `netdiskPill` or `searchCircle` off the right edge of the screen.
        FrameLayout siteSlot = new FrameLayout(a);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(0, a.dp(34), 1f);
        slotLp.leftMargin = a.dp(8);
        slotLp.rightMargin = a.dp(6);
        pill.addView(siteSlot, slotLp);

        // Right 1: Site selector Liquid Glass capsule (`source-switcher`)
        String siteLabel = currentSite != null ? currentSite.name + " ▾" : "選擇片源 ▾";
        TextView sitePill = a.text(siteLabel, 12.5f, Color.WHITE);
        sitePill.setTag("source-switcher");
        sitePill.setTypeface(null, Typeface.BOLD);
        sitePill.setSingleLine(true);
        sitePill.setEllipsize(TextUtils.TruncateAt.END);
        int maxSiteWidth = Math.max(a.dp(48), Math.min(a.dp(136), a.windowWidth() - a.dp(250)));
        sitePill.setMaxWidth(maxSiteWidth);
        sitePill.setGravity(Gravity.CENTER);
        sitePill.setPadding(a.dp(11), a.dp(7), a.dp(11), a.dp(7));
        // mkoai 2.0.66: no own glass background — the pill carries the
        // tone, the source name is plain text on it (the Emby look).
        homeTopToneTexts.add(sitePill);
        // mkoai 2.0.61: green ▶ prefix (reference app top pill). The ▶
        // span is applied after a.text() creates the view — a.text takes
        // a plain String, so styled text is set here.
        android.text.SpannableString pillText =
                new android.text.SpannableString("▶ " + siteLabel);
        pillText.setSpan(
                new android.text.style.ForegroundColorSpan(0xff4ade80),
                0,
                1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sitePill.setText(pillText);
        MobileDetailScreen.bindTouchFeedback(sitePill);
        sitePill.setOnClickListener(
                v -> {
                    if (a.config == null) showMobileImportDialog(a);
                    else a.chooseSources();
                });
        siteSlot.addView(
                sitePill,
                new FrameLayout.LayoutParams(-2, a.dp(34), Gravity.END | Gravity.CENTER_VERTICAL));

        // Right 3: Circular Search button (`nav-search`) — a floating
        // round button over the hero now, tone background (2.0.66).
        FrameLayout searchCircle = new FrameLayout(a);
        searchCircle.setTag("nav-search");
        searchCircle.setBackground(topPillBg(heroDiffusionShown));
        searchCircle.setElevation(a.dp(4));
        homeTopToneBgs.add(searchCircle);
        MobileDetailScreen.bindTouchFeedback(searchCircle);
        searchCircle.setOnClickListener(v -> switchTab(1));
        NavGlyph searchGlyph = new NavGlyph(a, 1);
        searchGlyph.setIconTint(0xfff3f4f6);
        homeTopGlyphs.add(searchGlyph);
        searchCircle.addView(
                searchGlyph, new FrameLayout.LayoutParams(a.dp(17), a.dp(17), Gravity.CENTER));
        searchCircle.setContentDescription("搜尋影片");
        bar.addView(searchCircle, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));

        // mkoai 2.0.61: "⋯" menu circle (reference app top bar) — opens
        // Settings for now (no new menu UI in this first try).
        FrameLayout moreCircle = new FrameLayout(a);
        moreCircle.setTag("nav-more");
        moreCircle.setContentDescription("更多");
        moreCircle.setBackground(topPillBg(heroDiffusionShown));
        moreCircle.setElevation(a.dp(4));
        homeTopToneBgs.add(moreCircle);
        MobileDetailScreen.bindTouchFeedback(moreCircle);
        moreCircle.setOnClickListener(v -> switchTab(4));
        TextView moreGlyph = a.text("⋯", 17, 0xfff3f4f6);
        homeTopToneTexts.add(moreGlyph);
        moreGlyph.setGravity(Gravity.CENTER);
        moreCircle.addView(moreGlyph, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(a.dp(48), a.dp(48));
        moreLp.leftMargin = a.dp(6);
        bar.addView(moreCircle, moreLp);

        // mkoai 2.0.38: history entry, next to search. Opens the full
        // 觀看歷史 page (same WATCH_HISTORY the home row reads).
        FrameLayout historyCircle = new FrameLayout(a);
        historyCircle.setTag("nav-history");
        historyCircle.setContentDescription("觀看紀錄");
        historyCircle.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) historyCircle.setTooltipText("觀看紀錄");
        historyCircle.setBackground(topPillBg(heroDiffusionShown));
        historyCircle.setElevation(a.dp(4));
        homeTopToneBgs.add(historyCircle);
        MobileDetailScreen.bindTouchFeedback(historyCircle);
        historyCircle.setOnClickListener(
                v -> a.open(new MainActivity.Screen(a::historyPage), true));
        NavGlyph historyGlyph = new NavGlyph(a, 5);
        historyGlyph.setIconTint(0xfff3f4f6);
        homeTopGlyphs.add(historyGlyph);
        historyGlyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        historyCircle.addView(
                historyGlyph, new FrameLayout.LayoutParams(a.dp(25), a.dp(25), Gravity.CENTER));
        LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(a.dp(48), a.dp(48));
        histLp.leftMargin = a.dp(6);
        bar.addView(historyCircle, histLp);

        // mkoai: Google account login button (Maoki-style sync). Not signed in: a
        // "登入" pill that opens the Google sign-in browser. Signed in: a circle with
        // the first letter of the email, opening the account sync page.
        boolean signedIn = SupabaseAuth.isSignedIn(a);
        if (signedIn) {
            FrameLayout accountCircle = new FrameLayout(a);
            accountCircle.setTag("nav-account");
            accountCircle.setBackground(topPillBg(heroDiffusionShown));
            accountCircle.setElevation(a.dp(4));
            homeTopToneBgs.add(accountCircle);
            MobileDetailScreen.bindTouchFeedback(accountCircle);
            accountCircle.setOnClickListener(
                    v ->
                            a.open(
                                    new MainActivity.Screen(
                                            () -> new SettingsScreen(a).accountSync()),
                                    true));
            String mail = SupabaseAuth.email(a);
            String initial =
                    (mail == null || mail.isEmpty())
                            ? "G"
                            : mail.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            TextView initialTv = a.text(initial, 15, MainActivity.WHITE);
            initialTv.setGravity(Gravity.CENTER);
            homeTopToneTexts.add(initialTv);
            accountCircle.addView(
                    initialTv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            // mkoai 2.0.51: same 48dp circle as the search / history
            // neighbours — the old 34dp circle looked smaller in the
            // header. Size only; icon, action and position unchanged.
            LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(a.dp(48), a.dp(48));
            acLp.leftMargin = a.dp(6);
            bar.addView(accountCircle, acLp);
        } else {
            TextView loginPill = a.text("登入", 13, MainActivity.WHITE);
            loginPill.setTag("nav-account-login");
            loginPill.setGravity(Gravity.CENTER);
            loginPill.setPadding(a.dp(12), 0, a.dp(12), 0);
            loginPill.setBackground(topPillBg(heroDiffusionShown));
            loginPill.setElevation(a.dp(4));
            homeTopToneBgs.add(loginPill);
            homeTopToneTexts.add(loginPill);
            MobileDetailScreen.bindTouchFeedback(loginPill);
            loginPill.setOnClickListener(v -> SupabaseAuth.signInWithGoogle(a));
            // mkoai 2.0.51: same 48dp height as the neighbouring circles
            // (was 34dp). Size only; text, action and position unchanged.
            LinearLayout.LayoutParams lpLp = new LinearLayout.LayoutParams(-2, a.dp(48));
            lpLp.leftMargin = a.dp(6);
            bar.addView(loginPill, lpLp);
        }

        homeTopBar = bar;
        // The first paint uses the shown tone (the slate until the first
        // hero tone arrives), so there is no flash; the animator keeps
        // the pill and circles in step from here on.
        retintTopOverlay(heroDiffusionShown);

        return bar;
    }

    /** The floating top pill / circle background for a page tone: the tone itself, pill radius, hairline stroke. */
    private GradientDrawable topPillBg(int tone) {
        return glassBg(tone, tone, a.dp(999), a.dp(1), HomeTone2066.topPillStroke(tone));
    }

    /**
     * mkoai 2.0.66: re-tints the floating top overlay for a page tone —
     * the pill and the circles take the tone as their background, the
     * texts take the tone's primary colour, the glyphs take the same
     * colour as their tint. On a light page the pill is near-white
     * with dark text; on a dark page it is dark glass with white text
     * (today's look). Called on every home build and from
     * applyDiffusionFrame when the light/dark state flips (or a fresh
     * build lands) — never per animation frame.
     */
    private void retintTopOverlay(int tone) {
        // One drawable per view — a shared GradientDrawable instance
        // would have its bounds clobbered by whichever view drew last.
        for (View v : homeTopToneBgs) {
            if (v != null) v.setBackground(topPillBg(tone));
        }
        int text = HomeTone2066.primaryText(tone);
        for (TextView tv : homeTopToneTexts) {
            if (tv != null) tv.setTextColor(text);
        }
        for (NavGlyph g : homeTopGlyphs) {
            if (g != null) g.setIconTint(text);
        }
    }

    /** Registers a home text view for tone-following re-tints (see the homeTextViews field). */
    private void registerHomeText(View v, int role) {
        if (v == null) return;
        homeTextViews.add(v);
        homeTextRoles.add(role);
        homeTextDirty = true;
        // Tint at once with the currently shown tone — a view registered
        // after the last diffusion frame must never wait for the next
        // one (a light hero would otherwise leave it white on light
        // until the next rotation). The flip block re-tints everything
        // when the tone itself changes.
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(
                    role == HOME_TEXT_MUTED
                            ? HomeTone2066.mutedText(heroDiffusionShown)
                            : HomeTone2066.primaryText(heroDiffusionShown));
        }
    }

    /**
     * mkoai 2.0.66: re-tints the registered home text for a page tone.
     * Detached views (rebuilt rows leave dead entries behind) are
     * pruned. Shadows are never touched here — registered views are
     * built without a shadow (the tone itself guarantees the contrast),
     * so dark text on a light page is never smudged by a black shadow.
     */
    private void retintHomeText(int tone) {
        for (int i = homeTextViews.size() - 1; i >= 0; i--) {
            View v = homeTextViews.get(i);
            if (v == null || v.getWindowToken() == null) {
                homeTextViews.remove(i);
                homeTextRoles.remove(i);
            }
        }
        for (int i = 0; i < homeTextViews.size(); i++) {
            View v = homeTextViews.get(i);
            if (!(v instanceof TextView)) continue;
            int role = homeTextRoles.get(i);
            ((TextView) v).setTextColor(
                    role == HOME_TEXT_MUTED
                            ? HomeTone2066.mutedText(tone)
                            : HomeTone2066.primaryText(tone));
        }
        homeIsLight = HomeTone2066.isLight(tone);
        homeTextDirty = false;
    }

    /** Re-tints the Continue Watching progress tracks for a page tone (prunes detached views). */
    private void retintProgressTracks(int tone) {
        int track = HomeTone2066.progressTrack(tone);
        for (int i = homeProgressTracks.size() - 1; i >= 0; i--) {
            View v = homeProgressTracks.get(i);
            if (v == null || v.getWindowToken() == null) {
                homeProgressTracks.remove(i);
                continue;
            }
            v.setBackground(new android.graphics.drawable.ColorDrawable(track));
        }
    }

    /** The quota-hide store: the app's SharedPreferences, via QuotaState2066's adapter. */
    private QuotaState2066.Store quotaStore() {
        return QuotaState2066.prefsStore(a.prefs);
    }

    /**
     * mkoai 2.0.66: a chart fetch that stays hidden while its quota is
     * out — returns null without touching the network, so a hidden row
     * is never re-fetched on every home open (a null job hides the row,
     * exactly like a failed fetch). Once the hide expires the next home
     * open fetches normally (isHidden clears the expired entry — the
     * re-test). A network failure never hides: only a recorded quota
     * 429 writes a hideUntil, and this check only reads it.
     */
    private List<TmdbClient.Item> fetchChartItems(
            String feature, java.util.concurrent.Callable<List<TmdbClient.Item>> fetch)
            throws Exception {
        if (QuotaState2066.isHidden(quotaStore(), feature, System.currentTimeMillis())) {
            return null;
        }
        return fetch.call();
    }

    /**
     * Builds the iOS 27 Floating Liquid Glass 4-Tab Pill Dock (`主页` · `直播` · `片库` · `设置`), with an
     * inner translucent glass lens on the active tab and continuous press-and-slide tab switching.
     */
    private LinearLayout buildNavigationBar(final boolean verticalRail) {
        final int touchSlop = a.dp(7);
        LinearLayout bar =
                new LinearLayout(a) {
                    private float downX;
                    private float downY;
                    private boolean sliding;
                    private int pendingTab = -1;

                    @Override
                    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            downX = ev.getX();
                            downY = ev.getY();
                            sliding = false;
                            pendingTab = -1;
                        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                            float delta =
                                    verticalRail
                                            ? Math.abs(ev.getY() - downY)
                                            : Math.abs(ev.getX() - downX);
                            if (delta > touchSlop) {
                                sliding = true;
                                return true;
                            }
                        }
                        return super.onInterceptTouchEvent(ev);
                    }

                    @Override
                    public boolean onTouchEvent(android.view.MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN
                                || action == android.view.MotionEvent.ACTION_MOVE) {
                            int[] dock = dockTabs();
                            int count = dock.length;
                            float pad = a.dp(6);
                            float span = (verticalRail ? getHeight() : getWidth()) - pad * 2f;
                            float coord = (verticalRail ? ev.getY() : ev.getX()) - pad;
                            if (span > 0 && count > 0) {
                                int slot =
                                        Math.max(
                                                0,
                                                Math.min(
                                                        count - 1,
                                                        (int) Math.floor((coord / span) * count)));
                                int targetTab = dock[slot];
                                int currentDockTab =
                                        pendingTab >= 0
                                                ? pendingTab
                                                : (activeTab == 1 ? 0 : activeTab);
                                if (targetTab != currentDockTab) {
                                    performHapticFeedback(
                                            android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                                    pendingTab = targetTab;
                                }
                            }
                            return true;
                        }
                        if (action == android.view.MotionEvent.ACTION_UP
                                || action == android.view.MotionEvent.ACTION_CANCEL) {
                            sliding = false;
                            if (action == android.view.MotionEvent.ACTION_UP && pendingTab >= 0)
                                switchTab(pendingTab);
                            pendingTab = -1;
                            return true;
                        }
                        return super.onTouchEvent(ev);
                    }
                };
        bar.setOrientation(verticalRail ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setTag("home-dock");
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6));
        a.glass(bar, 26);
        bar.setElevation(a.dp(18));

        String[] navKeys = {"nav-home", "nav-search", "nav-live", "nav-favorites", "nav-settings"};
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;

        int[] dockTabList = dockTabs();
        for (int pos = 0; pos < dockTabList.length; pos++) {
            final int tabIdx = dockTabList[pos];
            LinearLayout tab = a.vertical();
            tab.setTag(navKeys[tabIdx]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            MobileDetailScreen.bindTouchFeedback(tab);

            NavGlyph glyph = new NavGlyph(a, tabIdx);
            navIcons[tabIdx] = glyph;
            tab.addView(
                    glyph, new LinearLayout.LayoutParams(a.dp(22 * navScale), a.dp(22 * navScale)));

            TextView label = a.text(TAB_NAMES[tabIdx], 10.5f * navScale, 0xffe5e7eb);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, a.dp(2), 0, 0);
            navLabels[tabIdx] = label;
            tab.addView(label, new LinearLayout.LayoutParams(-2, -2));

            tab.setOnClickListener(v -> switchTab(tabIdx));
            navTabs[tabIdx] = tab;

            LinearLayout.LayoutParams lp =
                    verticalRail
                            ? new LinearLayout.LayoutParams(-1, 0, 1f)
                            : new LinearLayout.LayoutParams(0, -1, 1f);
            bar.addView(tab, lp);
        }
        return bar;
    }

    private int homeScrollY;
    private ScrollView homeScroll;
    private LinearLayout homeBody;

    void switchTab(int index) {
        ++homeRequest;
        homeReady = false;
        ++a.generation;
        if (activeTab == 0 && homeScroll != null) homeScrollY = homeScroll.getScrollY();
        int nextTab = Math.max(0, Math.min(4, index));
        // mkoai 2.0.38: no live source → the 直播 tab does not exist; fall back to home.
        if (nextTab == 2 && !hasLiveSources()) nextTab = 0;
        if (nextTab != 1 && activeSearchCoordinator != null) {
            activeSearchCoordinator.close();
            activeSearchCoordinator = null;
        }
        if (nextTab == 4 && activeTab == 4 && MobileIosSettings.hasSubPage()) {
            MobileIosSettings.resetToRoot();
        }
        activeTab = nextTab;
        // mkoai 2.0.64: the top-edge fade is a status-bar veil for every
        // tab — it only carries the hero tone on the home tab. Leaving
        // home restores the fixed near-black base at once (the tab swap
        // already changes all content, so no animation is needed here);
        // coming back home retints it through applyHeroDiffusion.
        // mkoai 2.0.66: leaving home also restores the dark dock, the
        // dark bottom veil and the white status-bar icons; coming back
        // home re-tints them through applyDiffusionFrame's flip block
        // (the fresh build marks the home text dirty, so the flip runs
        // even when the tone itself is unchanged).
        if (nextTab != 0 && homeTopFade != null) {
            homeTopFade.setBaseColor(HeroDiffusion2063.TOP_FADE_FIXED_RGB);
        }
        if (nextTab != 0) {
            if (bottomEdgeFade != null) bottomEdgeFade.setBaseColor(0x060a08);
            applyStatusBarIcons(false);
            homeTextDirty = true;
        }
        a.cancelPageRequests();
        retintDock();
        contentContainer.removeAllViews();
        switch (activeTab) {
            case 0:
                renderHomeTab();
                break;
            case 1:
                renderSearchTab();
                break;
            case 2:
                renderLiveTab();
                break;
            case 3:
                renderMyTab();
                break;
            case 4:
                renderSettingsTab();
                break;
        }
    }

    // =========================================================================
    // TAB 0: IPHONE PORTRAIT HOME (Scrollable Header + Category Pills + 3-Col Grid)
    // =========================================================================
    private void renderHomeTab() {
        if (a.config == null || currentSite == null) {
            renderEmptyWelcome();
            return;
        }

        final Runnable[] triggerPullRefresh = new Runnable[1];
        final TextView pullRefreshBadge = a.text("↓ 下拉重新整理当前内容", 12.5f, MainActivity.WHITE);
        pullRefreshBadge.setTypeface(null, Typeface.BOLD);
        pullRefreshBadge.setGravity(Gravity.CENTER);
        pullRefreshBadge.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        pullRefreshBadge.setBackground(
                glassBg(0xd81e2824, 0xd8141b18, a.dp(999), a.dp(0.8f), 0x40ffffff));
        pullRefreshBadge.setElevation(a.dp(12));
        pullRefreshBadge.setVisibility(View.GONE);

        final LinearLayout body = a.vertical();
        final int pullSlop = a.dp(10);
        final int pullThreshold = a.dp(44);
        final int pullMaxOffset = a.dp(76);
        final ScrollView scrollView =
                new ScrollView(a) {
                    private float startX;
                    private float startY;
                    private boolean trackingTopPull;
                    private boolean pullingDown;
                    private float currentPullOffset;

                    @Override
                    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            startX = ev.getRawX();
                            startY = ev.getRawY();
                            trackingTopPull = getScrollY() <= 0;
                            pullingDown = false;
                            currentPullOffset = 0f;
                        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                            if (!trackingTopPull && getScrollY() <= 0) {
                                startX = ev.getRawX();
                                startY = ev.getRawY();
                                trackingTopPull = true;
                            } else if (getScrollY() > 0 && !pullingDown) {
                                trackingTopPull = false;
                            }
                            if (trackingTopPull) {
                                float dx = ev.getRawX() - startX;
                                float dy = ev.getRawY() - startY;
                                if (!pullingDown
                                        && dy > pullSlop
                                        && dy > Math.abs(dx) * 1.2f
                                        && getScrollY() <= 0) {
                                    pullingDown = true;
                                }
                                if (pullingDown) {
                                    currentPullOffset =
                                            Math.max(
                                                    0f,
                                                    Math.min(
                                                            pullMaxOffset,
                                                            (dy - pullSlop) * 0.42f));
                                    body.setTranslationY(currentPullOffset);
                                    pullRefreshBadge.setVisibility(View.VISIBLE);
                                    pullRefreshBadge.setAlpha(
                                            Math.min(1f, currentPullOffset / pullThreshold));
                                    pullRefreshBadge.setText(
                                            currentPullOffset >= pullThreshold
                                                    ? "↑ 松开重新整理当前内容"
                                                    : "↓ 下拉重新整理当前内容");
                                    return true;
                                }
                            }
                        } else if (action == android.view.MotionEvent.ACTION_UP
                                || action == android.view.MotionEvent.ACTION_CANCEL) {
                            if (pullingDown) {
                                boolean shouldRefresh =
                                        action == android.view.MotionEvent.ACTION_UP
                                                && currentPullOffset >= pullThreshold;
                                pullingDown = false;
                                trackingTopPull = false;
                                currentPullOffset = 0f;
                                body.animate().translationY(0f).setDuration(180).start();
                                if (shouldRefresh && triggerPullRefresh[0] != null) {
                                    pullRefreshBadge.setVisibility(View.VISIBLE);
                                    pullRefreshBadge.setAlpha(1f);
                                    pullRefreshBadge.setText("正在重新整理…");
                                    triggerPullRefresh[0].run();
                                } else {
                                    pullRefreshBadge.setVisibility(View.GONE);
                                }
                                return true;
                            }
                            trackingTopPull = false;
                        }
                        return super.dispatchTouchEvent(ev);
                    }
                };
        homeScroll = scrollView;
        // mkoai 2.0.61: muted blue-grey home background (reference app) —
        // home-only (this ScrollView belongs to the home tab) and skipped
        // when the user has a custom wallpaper (the wallpaper stays visible).
        if (!a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) {
            scrollView.setBackgroundColor(HomeRows2061.HOME_SLATE_BG);
        }
        scrollView.post(() -> scrollView.scrollTo(0, homeScrollY));
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        a.scroll = scrollView;
        contentContainer.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams badgeLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        // mkoai 2.0.66: the badge sits BELOW the floating top overlay
        // (status bar + 8dp + 48dp overlay + 10dp), so it never covers
        // the pill while pulling.
        badgeLp.topMargin = statusBarHeight() + a.dp(66);
        contentContainer.addView(pullRefreshBadge, badgeLp);

        // mkoai 2.0.66: the floating top overlay (Emby style) — one tone
        // pill (logo + source) + separate floating round buttons over
        // the hero. It lives on contentContainer, not in the scroll
        // body, so it floats over the hero art and never scrolls away.
        View topOverlay = buildHomeTopHeaderRow();
        FrameLayout.LayoutParams overlayLp =
                new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        overlayLp.topMargin = statusBarHeight() + a.dp(8);
        overlayLp.leftMargin = a.dp(16);
        overlayLp.rightMargin = a.dp(16);
        contentContainer.addView(topOverlay, overlayLp);

        a.body = body;
        homeBody = body;
        // mkoai 2.0.66: no top padding — the hero art starts at the
        // screen top, under the status bar, with the floating overlay
        // on top of it (the old statusBar + 8dp belonged to the bar
        // that used to sit above the hero).
        body.setPadding(0, 0, 0, a.dp(106));
        scrollView.addView(body, new ScrollView.LayoutParams(-1, -2));

        // 2. Featured Hero billboard ("今日首推") — Netflix-style: first on
        // the home, full-bleed edge to edge, above the home list.
        final FrameLayout heroContainer = new FrameLayout(a);
        heroContainer.setPadding(0, 0, 0, 0);
        heroContainer.setVisibility(View.GONE);
        body.addView(heroContainer, new LinearLayout.LayoutParams(-1, -2));

        // mkoai 2.0.61: 继续观看 gets its own container directly under
        // the hero (before 我的媒体）, so it renders even while the
        // awwrated sections below are still loading. Filled in by
        // renderContinueWatchingRow at the renderHomeSections call sites.
        final LinearLayout continueWatchingContainer = a.vertical();
        continueWatchingContainer.setTag("home-continue-watching");
        continueWatchingContainer.setPadding(a.dp(16), a.dp(2), a.dp(16), 0);
        body.addView(continueWatchingContainer, new LinearLayout.LayoutParams(-1, -2));

        // mkoai 2.0.38 (Emby style): "我的媒體" entry cards （熱門電影 /
        // 熱門劇集） sit right under the hero. Filled in by renderTmdbRows
        // once the TMDB trending lists load.
        final LinearLayout mediaCardsContainer = a.vertical();
        mediaCardsContainer.setTag("home-media-cards");
        mediaCardsContainer.setPadding(a.dp(16), a.dp(2), a.dp(16), 0);
        body.addView(mediaCardsContainer, new LinearLayout.LayoutParams(-1, -2));

        // 3. Status & 3-Column Poster Grid Container
        final TextView statusText = a.text("正在加载片单…", 13, MainActivity.MUTED);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(a.dp(16), a.dp(24), a.dp(16), a.dp(24));
        // mkoai 2.0.66: a status line on the page tone — follows the tone.
        registerHomeText(statusText, HOME_TEXT_MUTED);
        body.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout gridContainer = a.vertical();
        gridContainer.setPadding(a.dp(16), a.dp(2), a.dp(16), 0);
        body.addView(gridContainer, new LinearLayout.LayoutParams(-1, -2));

        // mkoai 2.0.38 (Emby style): TMDB category rows （熱門電影、熱門劇集、
        // 最新上架、豆瓣高分、動畫電影 … 全部電影／劇集） render below the
        // awwrated sections. Each row only shows titles confirmed present
        // in the user's source; empty rows stay hidden.
        final LinearLayout tmdbRowsContainer = a.vertical();
        tmdbRowsContainer.setTag("home-tmdb-rows");
        tmdbRowsContainer.setPadding(a.dp(16), a.dp(2), a.dp(16), 0);
        body.addView(tmdbRowsContainer, new LinearLayout.LayoutParams(-1, -2));

        // mkoai 2.0.38: remember this load's TMDB containers and reset row state.
        tmdbRowsContainerRef = tmdbRowsContainer;
        mediaCardsContainerRef = mediaCardsContainer;
        homeStatusTextRef = statusText;
        tmdbRows = new ArrayList<>();
        tmdbRowsTicket = -1;
        tmdbRowsLoading = false;

        final TextView loadMoreBtn = a.text("加载更多影片", 13.5f, MainActivity.WHITE);
        loadMoreBtn.setTag("home-load-more");
        loadMoreBtn.setTypeface(null, Typeface.BOLD);
        loadMoreBtn.setGravity(Gravity.CENTER);
        loadMoreBtn.setPadding(a.dp(20), a.dp(11), a.dp(20), a.dp(11));
        loadMoreBtn.setBackground(
                glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.8f), 0x33ffffff));
        loadMoreBtn.setVisibility(View.GONE);
        MobileDetailScreen.bindTouchFeedback(loadMoreBtn);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
        moreLp.setMargins(a.dp(16), a.dp(14), a.dp(16), a.dp(8));
        body.addView(loadMoreBtn, moreLp);

        final boolean[] loadingPage = {false};
        final boolean[] pageFailed = {false};
        final int[] completedPage = {0};
        resumeHome =
                () -> {
                    // 2.0.61 (h): the hero is a 推薦 pick too — only a movie
                    // or drama/series may headline it.
                    java.util.List<Cms.Video> heroVideos =
                            VideoKind2061.filterRecommendations(currentVideos);
                    boolean showHero =
                            !SourceController.isConfigOrNetdiskSite(currentSite)
                                    && a.prefs.getBoolean(Prefs.SHOW_HERO_BANNER, true)
                                    && selectedCategoryId.isEmpty()
                                    && !heroVideos.isEmpty();
                    if (showHero) {
                        if (heroContainer.getChildCount() == 0) {
                            heroContainer.addView(
                                    buildHeroCarousel(currentSite, heroVideos));
                        }
                        heroContainer.setVisibility(View.VISIBLE);
                    } else {
                        heroContainer.removeAllViews();
                        heroContainer.setVisibility(View.GONE);
                    }
                    if (!loadingPage[0]) return;
                    loadingPage[0] = false;
                    pageFailed[0] = false;
                    currentPage = Math.max(1, completedPage[0]);
                    loadMoreBtn.setVisibility(currentPage < totalPages ? View.VISIBLE : View.GONE);
                    loadMoreBtn.setText("加载更多影片 (" + currentPage + "/" + totalPages + ")");
                };
        final Runnable[] fetchCategoryPage = new Runnable[1];
        reloadCategory =
                () -> {
                    if (currentSite != null && selectedCategoryId.isEmpty()) {
                        a.homeData.remove(currentSite.key);
                        // 2.0.34: a reload must re-fetch, not reuse the session cache.
                        AwwratedHome.clearSessionHome();
                    }
                    currentPage = 1;
                    if (fetchCategoryPage[0] != null) fetchCategoryPage[0].run();
                };
        triggerPullRefresh[0] =
                () -> {
                    if (loadingPage[0]) {
                        pullRefreshBadge.setVisibility(View.GONE);
                        return;
                    }
                    if (currentSite != null && selectedCategoryId.isEmpty()) {
                        a.homeData.remove(currentSite.key);
                        // 2.0.34: a pull-refresh must re-fetch, not reuse the session cache.
                        AwwratedHome.clearSessionHome();
                    }
                    currentPage = 1;
                    if (fetchCategoryPage[0] != null) fetchCategoryPage[0].run();
                };
        fetchCategoryPage[0] =
                () -> {
                    final int request = ++homeRequest;
                    // 2.0.41: pre-warm the TMDB rows check NOW, in parallel
                    // with the awwrated fetch, instead of waiting for the
                    // home render — the list pages read the same live rows,
                    // so titles are already confirmed by the time the user
                    // taps 「查看全部」. The render-time call below becomes
                    // a no-op via the ticket guard.
                    if (currentPage == 1
                            && selectedCategoryId.isEmpty()
                            && currentSite != null) {
                        startTmdbRows(currentSite, request);
                    }
                    loadingPage[0] = true;
                    if (currentPage == 1) {
                        if (!selectedCategoryId.isEmpty()) {
                            heroContainer.removeAllViews();
                            heroContainer.setVisibility(View.GONE);
                        }
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText("正在加载…");
                        loadMoreBtn.setVisibility(View.GONE);
                    } else {
                        loadMoreBtn.setVisibility(View.VISIBLE);
                        loadMoreBtn.setText("正在加载更多…");
                    }
                    final Cms.Site siteSnapshot = currentSite;
                    final String catSnapshot = selectedCategoryId;
                    final int pageSnapshot = currentPage;
                    final java.util.Map<String, String> filterSnapshot =
                            new java.util.LinkedHashMap<>(selectedFilters);
                    final List<VideoFilters.Definition> definitions = mobileFilterDefinitions();
                    final int ticket = a.generation;
                    final HomeScreen.Snapshot cached =
                            siteSnapshot == null ? null : a.homeData.get(siteSnapshot.key);
                    if (catSnapshot.isEmpty() && pageSnapshot == 1 && siteSnapshot != null) {
                        a.prefs.edit().putString(Prefs.LOADING_SITE, siteSnapshot.key).commit();
                    }
                    a.submitPage(
                            () -> {
                                try {
                                    SourceEngine eng = a.engine(siteSnapshot);
                                    List<Cms.Category> cats = currentCategories;
                                    Cms.Listing list;
                                    HomeScreen.Snapshot homeSnapshot = null;
                                    // mkoai 2.0.49: cross-source engines for
                                    // the awwrated home — built once in
                                    // phase 1 (poster pre-resolution, before
                                    // the first paint) and reused by the
                                    // phase-2 refinement below.
                                    final List<SourceEngine> crossEngs = new ArrayList<>();
                                    final List<String> crossKeys = new ArrayList<>();
                                    final List<Cms.Video> verificationCandidates = new ArrayList<>();
                                    if (catSnapshot.isEmpty() && pageSnapshot == 1) {
                                        // mkoai 2.0.26: home is awwrated only —
                                        // no source / Douban / Simkl modes, no
                                        // categories. One page of awwrated titles.
                                        cats = new ArrayList<>();
                                        list = new Cms.Listing();
                                        // mkoai 2.0.34: session cache first —
                                        // returning to the home tab reuses the
                                        // already-filtered list instead of
                                        // re-running fetchHome + prefilter
                                        // (~17s on a cold launch).
                                        List<Cms.Video> cachedHome =
                                                AwwratedHome.sessionHomeFor(siteSnapshot.api);
                                        boolean usedSessionCache =
                                                cachedHome != null && !cachedHome.isEmpty();
                                        List<Cms.Video> preloaded = null;
                                        boolean usedPreload = false;
                                        if (usedSessionCache) {
                                            list.videos.addAll(cachedHome);
                                        } else {
                                            // The splash-time preload may still
                                            // be running (cold launch): wait
                                            // briefly for it instead of
                                            // duplicating its fetch + prefilter.
                                            // This runs on a background thread.
                                            if (AwwratedHome.preloadedHome == null
                                                    && !AwwratedHome.homePreloadDone) {
                                                for (int i = 0; i < 20; i++) {
                                                    if (AwwratedHome.preloadedHome != null
                                                            || AwwratedHome.homePreloadDone) break;
                                                    try {
                                                        Thread.sleep(100);
                                                    } catch (InterruptedException ignored) {
                                                        break;
                                                    }
                                                }
                                            }
                                            preloaded = AwwratedHome.preloadedHome;
                                            usedPreload =
                                                    preloaded != null && !preloaded.isEmpty();
                                            if (usedPreload) {
                                                AwwratedHome.preloadedHome = null;
                                                list.videos.addAll(preloaded);
                                            } else {
                                                list.videos.addAll(
                                                        AwwratedHome.fetchHome(
                                                                a.getFilesDir()));
                                            }
                                        }
                                        // mkoai 2.0.40 (2b): disk-only
                                        // pre-paint check — NO live network
                                        // before the first render. Titles
                                        // with a fresh CONFIRMED-PRESENT disk
                                        // entry in ANY source keep their
                                        // confirming source (tap opens
                                        // there); titles confirmed ABSENT
                                        // everywhere are dropped; the rest
                                        // stay pending for the live
                                        // cross-source refinement in phase 2
                                        // below. The first screen therefore
                                        // paints immediately after fetchHome
                                        // instead of waiting up to 5s for
                                        // live checks. Skipped when the
                                        // preload or the session cache was
                                        // used: those lists were already
                                        // prefiltered.
                                        verificationCandidates.addAll(list.videos);
                                        {
                                            try {
                                                List<Cms.Video> visible =
                                                        AwwratedHome.prefilterVisibleDiskCross(
                                                                list.videos,
                                                                crossCheckApiKeys(siteSnapshot),
                                                                a.getFilesDir());
                                                if (visible != null) {
                                                    list.videos.clear();
                                                    list.videos.addAll(visible);
                                                }
                                            } catch (Throwable ignored) {
                                                // mkoai 2.0.51: fail open — a
                                                // prefilter error must not
                                                // blank the home; keep the
                                                // fetched list for phase 2.
                                            }
                                        }
                                        // mkoai 2.0.49: resolve posters
                                        // BEFORE the first paint. 2.0.48
                                        // filled a TMDB-poster-less card's
                                        // poster only after it was drawn
                                        // (Phase B top-up / maybeEnrichPoster
                                        // / deliverPoster) and the user still
                                        // saw 《艾倫的宇宙》 blank in all
                                        // three rows — the async chain has
                                        // too many silent failure points
                                        // (artwork-pool queue, ImageLoader
                                        // generation guards, resume rekindle
                                        // only restarting URL-tagged views),
                                        // and a title with a fresh v3 disk
                                        // entry (region present, no "po" —
                                        // exactly what 2.0.46/47 wrote)
                                        // short-circuits phase 2's live
                                        // check, so findMatch never runs and
                                        // the session poster cache stays
                                        // empty. The first 20 poster-less
                                        // cards (Top 10 + Netflix + start
                                        // of awwrated 推薦) are resolved
                                        // here, in parallel under one 2.5s
                                        // budget, so the card is born with
                                        // its poster. Best-effort: on any
                                        // failure the cards still render and
                                        // maybeEnrichPoster keeps trying.
                                        // The engines built here are reused
                                        // by phase 2 below.
                                        try {
                                            List<Cms.Site> preSites =
                                                    crossCheckSites(siteSnapshot);
                                            for (Cms.Site s : preSites) {
                                                try {
                                                    SourceEngine e = a.engine(s);
                                                    if (e != null) {
                                                        crossEngs.add(e);
                                                        crossKeys.add(s.api);
                                                    }
                                                } catch (Throwable ignored) {
                                                    // One dead engine must
                                                    // not kill the others.
                                                }
                                            }
                                            AwwratedHome.resolvePostersLive(
                                                    list.videos,
                                                    crossEngs,
                                                    crossKeys,
                                                    a.getFilesDir(),
                                                    2500L,
                                                    20);
                                        } catch (Throwable ignored) {
                                        }
                                        // 2.0.34: remember the phase-1 list so
                                        // a return to the home tab is instant.
                                        // Phase 2 below overwrites it with the
                                        // refined list when it finishes.
                                        if (!list.videos.isEmpty()) {
                                            AwwratedHome.rememberSessionHome(
                                                    siteSnapshot.api, list.videos);
                                        }
                                        // mkoai 2.0.30: filters 3+4 (source
                                        // availability + region, up to 15s of
                                        // live source searches) do NOT run
                                        // here. The home renders first with
                                        // filters 1+2 only (platform + 2-day
                                        // delay, applied at parse time), then
                                        // applySourceFilters runs in the
                                        // background after the first render
                                        // and refines the list in place —
                                        // see phase 2 below. This keeps the
                                        // first paint fast and shrinks the
                                        // window in which the success-post
                                        // guards below can drop the render.
                                        list.pageCount = 1;
                                    } else {
                                        if (cats.isEmpty()) {
                                            if (cached != null
                                                    && cached.categories != null
                                                    && !cached.categories.isEmpty()) {
                                                cats = new ArrayList<>(cached.categories);
                                            } else {
                                                try {
                                                    String rawHome = eng.home();
                                                    cats = Cms.categories(rawHome);
                                                    if (filterSchema == null
                                                            || filterSchema.isEmpty()
                                                            || "{}".equals(filterSchema)) {
                                                        filterSchema = rawHome;
                                                    }
                                                } catch (Throwable ignored) {
                                                }
                                            }
                                        }
                                        String targetCat =
                                                catSnapshot.isEmpty() && !cats.isEmpty()
                                                        ? cats.get(0).id
                                                        : catSnapshot;
                                        list =
                                                Cms.listing(
                                                        eng.category(
                                                                targetCat,
                                                                pageSnapshot,
                                                                VideoFilters.remoteValues(
                                                                        definitions,
                                                                        filterSnapshot,
                                                                        siteSnapshot.supported())));
                                    }
                                    final HomeScreen.Snapshot finalHomeSnapshot = homeSnapshot;
                                    final List<Cms.Category> finalCats = cats;
                                    final Cms.Listing finalList = list;
                                    final List<Cms.Video> filtered =
                                            VideoFilters.apply(
                                                    list.videos, definitions, filterSnapshot);
                                    a.learnTitles(finalList.videos);
                                    a.ui.post(
                                            () -> {
                                                a.prefs
                                                        .edit()
                                                        .putString(Prefs.LOADING_SITE, "")
                                                        .apply();
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                loadingPage[0] = false;
                                                pageFailed[0] = false;
                                                completedPage[0] = pageSnapshot;
                                                homeReady = true;
                                                pullRefreshBadge.setVisibility(View.GONE);
                                                if (finalHomeSnapshot != null) {
                                                    filterSchema = finalHomeSnapshot.filterSchema;
                                                    a.homeData.put(
                                                            siteSnapshot.key, finalHomeSnapshot);
                                                }
                                                currentCategories = finalCats;
                                                boolean configOrNetdisk =
                                                        SourceController.isConfigOrNetdiskSite(
                                                                siteSnapshot);
                                                if (pageSnapshot == 1) currentVideos.clear();
                                                for (Cms.Video v : filtered) {
                                                    if (configOrNetdisk
                                                            || v.action == null
                                                            || v.action.isEmpty()) {
                                                        currentVideos.add(v);
                                                    }
                                                }
                                                totalPages = Math.max(1, finalList.pageCount);
                                                // mkoai 2.0.51: on the home, the empty copy is
                                                // decided by refreshHomeEmptyState() — while
                                                // the TMDB checks are still running it shows
                                                // a loading line instead of "暫無影片".
                                                // Category pages keep the old inline logic.
                                                if (pageSnapshot == 1 && catSnapshot.isEmpty()) {
                                                    refreshHomeEmptyState();
                                                } else {
                                                    statusText.setVisibility(
                                                            currentVideos.isEmpty()
                                                                    ? View.VISIBLE
                                                                    : View.GONE);
                                                    if (currentVideos.isEmpty()) {
                                                        statusText.setText(
                                                                selectedFilters.isEmpty()
                                                                        ? "当前分类暂无影片"
                                                                        : "当前筛选暂无匹配，可继续加载更多影片");
                                                    }
                                                }
                                                // Render Featured Hero Showcase on Recommend tab
                                                // 2.0.61 (h): only a movie or drama/series
                                                // may headline the hero.
                                                java.util.List<Cms.Video> heroVideos =
                                                        VideoKind2061.filterRecommendations(
                                                                currentVideos);
                                                if (!configOrNetdisk
                                                        && a.prefs.getBoolean(
                                                                Prefs.SHOW_HERO_BANNER, true)
                                                        && catSnapshot.isEmpty()
                                                        && !heroVideos.isEmpty()) {
                                                    heroContainer.removeAllViews();
                                                    heroContainer.addView(
                                                            buildHeroCarousel(siteSnapshot, heroVideos));
                                                    heroContainer.setVisibility(View.VISIBLE);
                                                } else {
                                                    heroContainer.removeAllViews();
                                                    heroContainer.setVisibility(View.GONE);
                                                }
                                                final int savedScrollY = scrollView.getScrollY();
                                                // mkoai: Netflix-style home — the home
                                                // (page 1, no category) renders as
                                                // horizontal rows grouped by type;
                                                // category pages keep the grid.
                                                if (pageSnapshot == 1 && catSnapshot.isEmpty()) {
                                                    renderHomeSections(
                                                            gridContainer,
                                                            siteSnapshot,
                                                            currentVideos);
                                                    renderContinueWatchingRow(
                                                            continueWatchingContainer,
                                                            siteSnapshot);
                                                } else {
                                                    renderVideoGrid(
                                                            gridContainer, siteSnapshot, currentVideos);
                                                }
                                                // mkoai 2.0.38: TMDB rows （熱門＋最新＋
                                                // 分類） + "我的媒體" cards render below
                                                // the awwrated sections.
                                                if (pageSnapshot == 1 && catSnapshot.isEmpty()) {
                                                    startTmdbRows(siteSnapshot, request);
                                                }
                                                if (pageSnapshot > 1 && savedScrollY > 0) {
                                                    scrollView.scrollTo(0, savedScrollY);
                                                    scrollView.post(
                                                            () ->
                                                                    scrollView.scrollTo(
                                                                            0, savedScrollY));
                                                }
                                                if (currentPage < totalPages) {
                                                    loadMoreBtn.setVisibility(View.VISIBLE);
                                                    loadMoreBtn.setText(
                                                            "加载更多影片 ("
                                                                    + currentPage
                                                                    + "/"
                                                                    + totalPages
                                                                    + ")");
                                                } else {
                                                    loadMoreBtn.setVisibility(View.GONE);
                                                }
                                            });
                                        // mkoai 2.0.40 (2a, phase 2): refine
                                        // the awwrated home in the background
                                        // with filters 3+4 across ALL
                                        // configured sources (current first) —
                                        // a title counts when ANY source
                                        // confirms it, and the card remembers
                                        // the confirming source. The home
                                        // already rendered above, so this
                                        // never blocks the first paint.
                                        // Best-effort: any failure here leaves
                                        // the phase-1 render visible. The
                                        // refinement rewrites the matched
                                        // videos' ids + sourceApi in place
                                        // (real source id instead of
                                        // "msearch:") so even when no
                                        // re-render is needed, taps on
                                        // already-rendered cards open the
                                        // detail page directly on the
                                        // confirming source.
                                        if (catSnapshot.isEmpty() && pageSnapshot == 1) {
                                            try {
                                                // mkoai 2.0.49: reuse the
                                                // engines built in phase 1
                                                // (poster pre-resolution);
                                                // rebuild only if phase 1
                                                // produced none (e.g. it
                                                // threw before building).
                                                if (crossEngs.isEmpty()) {
                                                    List<Cms.Site> crossSites =
                                                            crossCheckSites(siteSnapshot);
                                                    for (Cms.Site s : crossSites) {
                                                        try {
                                                            SourceEngine e = a.engine(s);
                                                            if (e != null) {
                                                                crossEngs.add(e);
                                                                crossKeys.add(s.api);
                                                            }
                                                        } catch (Throwable ignored) {
                                                            // One dead engine must
                                                            // not kill the others.
                                                        }
                                                    }
                                                }
                                                // mkoai 2.0.47: snapshot
                                                // which rendered videos are
                                                // poster-less BEFORE the
                                                // refinement mutates them in
                                                // place (applySourceFiltersCross
                                                // fills v.poster / v.sourceApi
                                                // / v.id on the SAME objects
                                                // the first paint already
                                                // rendered, so a before/after
                                                // compare must use identity).
                                                java.util.Set<Cms.Video> emptyPosterBefore =
                                                        java.util.Collections.newSetFromMap(
                                                                new java.util.IdentityHashMap<>());
                                                for (Cms.Video v : filtered) {
                                                    if (v != null
                                                            && (v.poster == null
                                                                    || v.poster.isEmpty())) {
                                                        emptyPosterBefore.add(v);
                                                    }
                                                }
                                                List<Cms.Video> refined =
                                                        AwwratedHome.applySourceFiltersCross(
                                                                verificationCandidates,
                                                                crossEngs,
                                                                crossKeys,
                                                                a.getFilesDir());
                                                // 2.0.34: cache the refined list
                                                // too, so a return to the home
                                                // tab shows the final list.
                                                if (refined != null && !refined.isEmpty()) {
                                                    AwwratedHome.rememberSessionHome(
                                                            siteSnapshot.api, refined);
                                                }
                                                // mkoai 2.0.47: the refinement
                                                // also filled posters. Did any
                                                // poster-less card gain one?
                                                boolean posterFilled = false;
                                                if (refined != null) {
                                                    for (Cms.Video v : refined) {
                                                        if (v != null
                                                                && emptyPosterBefore.contains(v)
                                                                && v.poster != null
                                                                && !v.poster.isEmpty()) {
                                                            posterFilled = true;
                                                            break;
                                                        }
                                                    }
                                                }
                                                // Only re-render when titles
                                                // were actually removed OR a
                                                // poster was filled. When the
                                                // size is unchanged and no
                                                // poster was filled, the only
                                                // change is the in-place id
                                                // rewrite above, which takes
                                                // effect on tap without any
                                                // re-render (and avoids flicker).
                                                // mkoai 2.0.47: the old
                                                // size-only condition is what
                                                // left TMDB-poster-less cards
                                                // (e.g. 《艾倫的宇宙》) blank
                                                // on the first visit — Phase 2
                                                // had already filled the poster
                                                // on the Video, but the already
                                                // rendered card never heard
                                                // about it. The re-render
                                                // restores the saved scroll
                                                // position, so it costs one
                                                // repaint, not the user's place.
                                                if (refined != null
                                                        && (!refined.equals(filtered)
                                                                || posterFilled)) {
                                                    final List<Cms.Video> refinedFinal = refined;
                                                    a.ui.post(
                                                            () -> {
                                                                try {
                                                                    if (ticket != a.generation
                                                                            || a.isFinishing()
                                                                            || request
                                                                                    != homeRequest) {
                                                                        return;
                                                                    }
                                                                    final int savedScrollY =
                                                                            scrollView
                                                                                    .getScrollY();
                                                                    currentVideos.clear();
                                                                    for (Cms.Video v :
                                                                            refinedFinal) {
                                                                        currentVideos.add(v);
                                                                    }
                                                                    boolean configOrNetdisk =
                                                                            SourceController
                                                                                    .isConfigOrNetdiskSite(
                                                                                            siteSnapshot);
                                                                    // 2.0.61 (h): only a movie or
                                                                    // drama/series may headline
                                                                    // the hero.
                                                                    java.util.List<Cms.Video>
                                                                            heroVideos =
                                                                            VideoKind2061
                                                                                    .filterRecommendations(
                                                                                            currentVideos);
                                                                    if (!configOrNetdisk
                                                                            && a.prefs.getBoolean(
                                                                                    Prefs
                                                                                            .SHOW_HERO_BANNER,
                                                                                    true)
                                                                            && !heroVideos
                                                                                    .isEmpty()) {
                                                                        heroContainer
                                                                                .removeAllViews();
                                                                        heroContainer.addView(
                                                                                buildHeroCarousel(siteSnapshot, heroVideos));
                                                                        heroContainer
                                                                                .setVisibility(
                                                                                        View.VISIBLE);
                                                                    } else {
                                                                        heroContainer
                                                                                .removeAllViews();
                                                                        heroContainer
                                                                                .setVisibility(
                                                                                        View.GONE);
                                                                    }
                                                                    renderHomeSections(
                                                                            gridContainer,
                                                                            siteSnapshot,
                                                                            currentVideos);
                                                                    renderContinueWatchingRow(
                                                                            continueWatchingContainer,
                                                                            siteSnapshot);
                                                                    // mkoai 2.0.51: titles
                                                                    // landed — the loading
                                                                    // line (if any) goes away.
                                                                    refreshHomeEmptyState();
                                                                    if (savedScrollY > 0) {
                                                                        scrollView.scrollTo(
                                                                                0,
                                                                                savedScrollY);
                                                                        scrollView.post(
                                                                                () ->
                                                                                        scrollView
                                                                                                .scrollTo(
                                                                                                        0,
                                                                                                        savedScrollY));
                                                                    }
                                                                } catch (Throwable e) {
                                                                    AppLog.w(
                                                                            "MobileShell",
                                                                            "home refinement failed",
                                                                            e);
                                                                }
                                                            });
                                                }
                                            } catch (Throwable ignored) {
                                                // Refinement is best-effort; the
                                                // phase-1 render stays visible.
                                            }
                                        }
                                } catch (Throwable e) {
                                    AppLog.w("MobileShell", "fetchCategoryPage failed", e);
                                    a.ui.post(
                                            () -> {
                                                if (siteSnapshot != null
                                                        && siteSnapshot.key.equals(
                                                                a.prefs.getString(
                                                                        Prefs.LOADING_SITE, ""))) {
                                                    a.prefs
                                                            .edit()
                                                            .putString(Prefs.LOADING_SITE, "")
                                                            .apply();
                                                }
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != homeRequest) return;
                                                loadingPage[0] = false;
                                                pageFailed[0] = true;
                                                homeReady = !currentVideos.isEmpty();
                                                pullRefreshBadge.setVisibility(View.GONE);
                                                statusText.setVisibility(View.VISIBLE);
                                                statusText.setText("載入失敗：" + a.message(e));
                                                loadMoreBtn.setVisibility(View.VISIBLE);
                                                loadMoreBtn.setText("點選重試");
                                            });
                                } finally {
                                    if (siteSnapshot != null
                                            && siteSnapshot.key.equals(
                                                    a.prefs.getString(Prefs.LOADING_SITE, ""))) {
                                        a.prefs.edit().putString(Prefs.LOADING_SITE, "").commit();
                                    }
                                }
                            });
                };

        loadMoreBtn.setOnClickListener(
                v -> {
                    if (loadingPage[0]) return;
                    if (!pageFailed[0] && currentPage < totalPages) currentPage++;
                    fetchCategoryPage[0].run();
                });

        // Auto-load next page when scrolling near bottom (matching iPhone infinite scroll)
        scrollView
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            if (loadingPage[0] || pageFailed[0] || currentPage >= totalPages)
                                return;
                            View child = scrollView.getChildAt(0);
                            if (child == null) return;
                            int diff =
                                    child.getBottom()
                                            - (scrollView.getHeight() + scrollView.getScrollY());
                            if (diff <= a.dp(240)) {
                                currentPage++;
                                fetchCategoryPage[0].run();
                            }
                        });


        fetchCategoryPage[0].run();
    }

    /**
     * Builds the home hero carousel: one full-width big card per page
     * (full-bleed CENTER_CROP poster, centered 27sp title, full-width
     * white "▶ 播放" button), dot indicator, 12s slow auto-advance
     * (900ms glide). 2.0.53: the page is exactly windowWidth — no peek
     * of the next card. 2.0.63: no anime headlines the hero.
     */
    private View buildHeroCarousel(final Cms.Site site, final List<Cms.Video> candidates) {
        // mkoai 2.0.63: the hero never headlines anime — drop animation
        // categories (动画/動畫/动漫/動漫/日漫/国漫/國漫）. Fail-open: if
        // that would leave the hero empty, keep the original list —
        // showing something beats showing nothing.
        // mkoai 2.0.64: the hero-only check — it also resolves a
        // candidate's numeric type id against this source's cached
        // category list (a source may omit the per-video category name
        // while keeping the id) and, last resort, anime words in the
        // title. The recommendation page and the type badge never see
        // either signal.
        List<Cms.Video> heroList =
                VideoKind2061.filterNonAnimeHero(candidates, heroTypeNames(site));
        if (heroList.isEmpty()) heroList = candidates;
        // mkoai 2.0.66: the hero prefers recent titles (2 years) — recent
        // first, older after, nothing dropped (fail-open: an older hero
        // beats an empty hero). Year from Cms.Video.year (Cms.yearLabel
        // normalisation); unknown year counts as not-recent (filler).
        heroList = HomeYear2066.preferRecentForHero(heroList,
                v -> v == null ? -1 : HomeYear2066.parseYear(v.year),
                HomeYear2066.currentYear());
        // mkoai 2.0.63: record the carousel for hero diffusion — one
        // artwork + scrim per page, filled in by buildHeroShowcaseCard.
        heroCandidates.clear();
        heroCandidates.addAll(heroList);
        heroArtworks.clear();
        heroScrims.clear();
        heroRatings.clear();
        heroBackdropResolved.clear();
        // mkoai 2.0.65: anchor the bloom's tone hold to the hero's
        // bottom edge at rest (see HeroDiffusion2063.heroBottomFraction)
        // — the rest state then looks like the scrolled state, because
        // scrolling only moves the hero's bottom edge up, deeper into
        // the pure-tone zone. The carousel page is max(240dp, the
        // window width), so the window width is the page width here.
        // mkoai 2.0.66: the hero starts at the screen top now (no bar
        // above it), so its top offset is 0 — only the hero height and
        // the viewport matter.
        float density = a.getResources().getDisplayMetrics().density;
        heroDiffusionHoldFraction = HeroDiffusion2063.heroBottomFraction(
                0f,
                HeroDiffusion2063.heroHeightDp(a.windowWidth() / density),
                a.windowHeight() / density);
        if (heroList.size() <= 1) {
            View single = buildHeroShowcaseCard(site, heroList.get(0));
            applyHeroDiffusion(0);
            return single;
        }
        final int count = Math.min(5, heroList.size());
        // 2.0.53: hero carousel shows exactly one full-width card at a time —
        // no peek of the next card. (Was windowWidth - 32dp, which left a
        // 32dp sliver of the next card visible at the right edge.)
        final int pageWidth = Math.max(a.dp(240), a.windowWidth());
        final FrameLayout frame = new FrameLayout(a);
        final int[] page = {0};
        final boolean[] touching = {false};
        final TextView dots = a.text("", 13, Color.WHITE);
        dots.setGravity(Gravity.CENTER);
        final Runnable updateDots = () -> {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < count; i++) text.append(i == page[0] ? "●  " : "○  ");
            dots.setText(text.toString());
            dots.setContentDescription("推薦 " + (page[0]+1) + " / " + count);
        };
        final HorizontalScrollView pager = new HorizontalScrollView(a) {
            final float density = a.getResources().getDisplayMetrics().density;
            final float[] down = {0f, 0f};
            final boolean[] dragged = {false};
            final long[] lastManualMs = {0L};
            android.view.VelocityTracker vt;
            // mkoai 2.0.63: the slow auto-advance glide in flight (if any).
            android.animation.ValueAnimator autoAnimator;
            final Runnable advance = new Runnable() {
                @Override public void run() {
                    android.graphics.Rect visible = new android.graphics.Rect();
                    android.view.accessibility.AccessibilityManager accessibility =
                            (android.view.accessibility.AccessibilityManager) a.getSystemService(Context.ACCESSIBILITY_SERVICE);
                    // mkoai 2.0.63: after a manual swipe the auto-advance
                    // stays paused (MANUAL_PAUSE_MS) instead of snatching
                    // the card back one cycle later.
                    boolean paused = HeroSwipe2063.shouldPauseAutoAdvance(
                            System.currentTimeMillis(), lastManualMs[0],
                            HeroSwipe2063.MANUAL_PAUSE_MS);
                    if (!paused && !touching[0] && isShown() && getGlobalVisibleRect(visible)
                            && (accessibility == null || !accessibility.isTouchExplorationEnabled())) {
                        // mkoai 2.0.63: slow rotation — glide to the next
                        // card over AUTO_TRANSITION_MS (900ms) instead of
                        // the default ~250ms snap, so the change is never
                        // a sudden switch. The manual settle on release
                        // stays fast (a slow snap would feel laggy).
                        final int fromX = getScrollX();
                        final int toX = ((page[0]+1)%count)*pageWidth;
                        if (autoAnimator != null) autoAnimator.cancel();
                        autoAnimator = ValueAnimator.ofInt(fromX, toX);
                        autoAnimator.setDuration(HeroSwipe2063.AUTO_TRANSITION_MS);
                        autoAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
                        autoAnimator.addUpdateListener(anim ->
                                scrollTo((Integer) anim.getAnimatedValue(), 0));
                        autoAnimator.start();
                    }
                    postDelayed(this, HeroSwipe2063.AUTO_ADVANCE_MS);
                }
            };
            @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(advance, HeroSwipe2063.AUTO_ADVANCE_MS); }
            @Override protected void onDetachedFromWindow() { removeCallbacks(advance); if (autoAnimator != null) autoAnimator.cancel(); super.onDetachedFromWindow(); }
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    touching[0] = true;
                    removeCallbacks(advance);
                    // mkoai 2.0.63: stop a slow auto-glide in flight — the
                    // finger takes over from exactly where it is.
                    if (autoAnimator != null) autoAnimator.cancel();
                    // mkoai 2.0.63: a fresh gesture is a tap until it proves
                    // itself a drag — the card click is live again.
                    heroSwipeDragged = false;
                    dragged[0] = false;
                    down[0] = event.getX();
                    down[1] = event.getY();
                    if (vt != null) vt.recycle();
                    vt = android.view.VelocityTracker.obtain();
                    vt.addMovement(event);
                } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                    if (vt != null) vt.addMovement(event);
                    if (!dragged[0]) {
                        float dx = event.getX() - down[0];
                        float dy = event.getY() - down[1];
                        // mkoai 2.0.63: clearly horizontal — lock the
                        // vertical parent ScrollView out so the swipe
                        // follows the finger. Clearly vertical (or still
                        // undecided): leave the parent alone so the page
                        // still scrolls. Never disallow on DOWN, or a
                        // vertical drag starting on the hero could not
                        // scroll the home page at all.
                        if (Math.abs(dx) > Math.abs(dy)
                                && Math.abs(dx) > a.dp(HeroSwipe2063.TAP_SLOP_DP)) {
                            dragged[0] = true;
                            heroSwipeDragged = true;
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                } else if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    touching[0] = false;
                    float velocityX = 0f;
                    if (vt != null) {
                        if (action == android.view.MotionEvent.ACTION_UP) {
                            vt.addMovement(event);
                            vt.computeCurrentVelocity(1000);
                            velocityX = vt.getXVelocity();
                        }
                        vt.recycle();
                        vt = null;
                    }
                    // mkoai 2.0.63: a flick changes the page whatever the
                    // distance; otherwise a tenth of a page of drag is
                    // enough (was: half a page, then a quarter — a light
                    // swipe kept snapping back).
                    final int target = HeroSwipe2063.targetPage(page[0], count,
                            getScrollX(), pageWidth, velocityX,
                            HeroSwipe2063.flingThresholdPxPerSec(density));
                    final boolean manual = dragged[0];
                    if (manual) lastManualMs[0] = System.currentTimeMillis();
                    post(() -> smoothScrollTo(target * pageWidth, 0));
                    postDelayed(advance, HeroSwipe2063.resumeDelayMs(manual));
                }
                return super.dispatchTouchEvent(event);
            }
        };
        pager.setHorizontalScrollBarEnabled(false);
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout pages = a.row();
        pager.addView(pages);
        for (int i=0; i<count; i++) {
            View hero = buildHeroShowcaseCard(site,heroList.get(i));
            pages.addView(hero,new LinearLayout.LayoutParams(pageWidth,-1));
        }
        // mkoai 2.0.67: the pager height is HeroDiffusion2063.heroHeightDp —
        // the same formula the hero cards and the bloom hold use, so the
        // pager, the cards and the anchor can never drift apart.
        float height =
                HeroDiffusion2063.heroHeightDp(
                        pageWidth / a.getResources().getDisplayMetrics().density);
        frame.addView(pager, new FrameLayout.LayoutParams(-1, a.dp(height)));
        frame.addView(dots,new FrameLayout.LayoutParams(-1,a.dp(24),Gravity.BOTTOM));
        pager.getViewTreeObserver().addOnScrollChangedListener(() -> {
            int next = Math.max(0,Math.min(count-1,Math.round((float)pager.getScrollX()/pageWidth)));
            if (next != page[0]) {
                page[0] = next;
                updateDots.run();
                // mkoai 2.0.63: retint the page for the new hero.
                applyHeroDiffusion(next);
            }
        });
        updateDots.run();
        applyHeroDiffusion(0);
        return frame;
    }

    /**
     * mkoai 2.0.68: hero artwork binding — portrait poster first. The
     * hero box is 393x589.5 (ratio 0.6667, the poster's own 2:3), so a
     * CENTER_CROP poster fills it with ~0% crop (0.06% of the width in
     * pixels on a POCO F5) and the subject dead centre. A 16:9 backdrop
     * used to win here (2.0.66/2.0.67: FIT_START, a 393x221.2dp strip at
     * the top, the title block below it on the page tone) and left a
     * ~368dp flat-tone band below it — the 《心鄉迷踪》 shot, which
     * reads as half a broken image. Only a video with no usable poster
     * falls back to the backdrop, and the backdrop is CENTER_CROP too:
     * it fills the box (100%, no band), the middle 37.5% of its width
     * stays in frame (62.5% of the sides are cropped, 0% of the
     * height), so the subject — which sits near the centre of a 16:9
     * key visual — stays centred. A backdrop that fails to load falls
     * back to the poster (CENTER_CROP) through the same reliable path.
     */
    private void bindHeroArtwork(ImageView artwork, Cms.Video featured) {
        String poster = featured.poster;
        if (poster != null && !poster.isEmpty() && !ImageLoader.failed(poster)) {
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bindReliablePoster(artwork, featured);
            return;
        }
        String backdrop = featured.backdrop;
        if (backdrop != null && !backdrop.isEmpty() && !ImageLoader.failed(backdrop)) {
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setImageDrawable(new PosterPlaceholder(featured.title));
            artwork.setContentDescription(featured.title + " 封面");
            ImageLoader.onFailure(artwork, () -> {
                artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
                bindReliablePoster(artwork, featured);
            });
            a.poster(backdrop, artwork);
            return;
        }
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bindReliablePoster(artwork, featured);
    }

    private View buildHeroShowcaseCard(final Cms.Site site, final Cms.Video featured) {
        FrameLayout card = new FrameLayout(a);
        card.setTag("hero-watch-now-button");
        // mkoai 2.0.67: the height comes from HeroDiffusion2063.heroHeightDp
        // (the same formula the bloom hold uses) — one source of truth,
        // so the card bottom and the bloom anchor can never drift apart.
        float height =
                HeroDiffusion2063.heroHeightDp(
                        a.windowWidth() / a.getResources().getDisplayMetrics().density);
        card.setMinimumHeight(a.dp(height));
        // mkoai 2.0.63: the card sits on the diffusion colour, not a black
        // band — while the artwork is still loading the card already melts
        // into the page below.
        card.setBackgroundColor(heroDiffusionTarget);
        ImageView artwork = new ImageView(a);
        bindHeroArtwork(artwork, featured);
        card.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
        if (homeAmbientView != null) homeAmbientView.follow(artwork);
        // mkoai 2.0.63: hero diffusion scrim — transparent over the top
        // ~60%, fading into the page colour over the bottom ~40%, so the
        // hero melts into the rows below with no hard edge and no black
        // band (was: a scrim ending in solid black).
        View gradient = new View(a);
        gradient.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                HeroDiffusion2063.scrimColors(heroDiffusionTarget)));
        gradient.setTag("hero-diffusion-scrim");
        card.addView(gradient, new FrameLayout.LayoutParams(-1, -1));
        heroArtworks.add(artwork);
        heroScrims.add(gradient);
        // mkoai 2.0.66: the hero TOP fade is gone — the top bar is a
        // floating overlay now, not a band above the hero, so there is
        // no seam left to melt (the fade used to lay the page tone over
        // the artwork's top ~43%, which also dimmed the poster's top
        // edge). The bottom scrim is the only fade.
        LinearLayout content = a.vertical();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(a.dp(24), a.dp(100), a.dp(24), a.dp(36));
        TextView title = a.text(featured.title, 27, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));
        // mkoai 2.0.61: hero meta is the year only when present, else
        // the remarks (reference app: title + year + description, no
        // second line). Hidden when neither exists.
        String meta =
                (featured.year != null && !featured.year.trim().isEmpty())
                        ? featured.year.trim()
                        : (featured.remarks == null ? "" : featured.remarks);
        TextView year = a.text(meta, 14, 0xffeeeeee);
        year.setGravity(Gravity.CENTER);
        year.setPadding(0, a.dp(9), 0, a.dp(12));
        if (meta.isEmpty()) year.setVisibility(View.GONE);
        content.addView(year);
        if (featured.description != null && !featured.description.trim().isEmpty()) {
            TextView summary = a.text(featured.description.replaceAll("<[^>]*>", " "), 14, 0xffeeeeee);
            summary.setGravity(Gravity.CENTER);
            summary.setMaxLines(3);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setLineSpacing(a.dp(3), 1f);
            content.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        }
        // mkoai 2.0.66: hero ratings (Emby info hierarchy) — TMDB first,
        // Douban when the remarks carry it ("TMDB 8.4 · 豆瓣 9.1"). The
        // line comes from the remarks the row builders already wrote
        // ("★ 8.4", "8.4 分", "豆瓣 9.1 分"); the lazy backdrop/ratings
        // lookup fills it from the cached TMDB vote when the remarks
        // carry nothing. Hidden when there is no score at all — no blank
        // placeholder, no "—". The text sits on the photo's bottom
        // scrim, so it stays white like the title (never registered
        // for tone-following).
        TextView ratings = a.text("", 13, 0xffeeeeee);
        ratings.setTag("hero-ratings");
        ratings.setGravity(Gravity.CENTER);
        ratings.setPadding(0, a.dp(10), 0, 0);
        ratings.setVisibility(View.GONE);
        String ratingsLine = HeroRatings2066.ratingsLine(featured.remarks, 0);
        if (!ratingsLine.isEmpty()) {
            ratings.setText(ratingsLine);
            ratings.setVisibility(View.VISIBLE);
        }
        content.addView(ratings, new LinearLayout.LayoutParams(-1, -2));
        heroRatings.add(ratings);
        // mkoai 2.0.61: no ▶ 播放 button on the hero (the reference app
        // has none) — tapping the card still opens the detail page.
        card.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.setOnClickListener(v -> {
            // mkoai 2.0.63: a swipe is not a tap — when the pager's touch
            // handling flagged this gesture a drag, ignore the click so a
            // swipe never opens the detail page. A real tap (no drag) still
            // opens it; the flag resets on the next ACTION_DOWN.
            if (heroSwipeDragged) return;
            a.openHomeCard(site, featured);
        });
        return card;
    }

    /**
     * mkoai 2.0.63: hero diffusion — retint the phone home for hero page
     * {@code page}. When the page's tone is already cached (one tone per
     * poster URL), the page background animates to it and the page's scrim
     * follows. When it is not sampled yet, the current background is held
     * (no flash back to the slate between heroes) and sampling starts; the
     * slate fallback only ever shows at first paint, while the first hero
     * bitmap is still loading. Skipped when the user has a custom
     * wallpaper (the wallpaper stays visible, same rule as the slate).
     */
    /**
     * mkoai 2.0.64: this site's cached category map (numeric type id →
     * category name) for the hero-only anime check, or null when the
     * cache is not for this site (site switched, categories not loaded
     * yet). The hero filter then degrades to the category fields alone
     * — never a wrong guess, never a crash. The map is built from
     * {@link #currentCategories}, which the home page-load callback
     * sets from the same request that fills {@link #currentVideos}, so
     * it is in place by the time the hero builds.
     */
    private java.util.Map<Integer, String> heroTypeNames(Cms.Site site) {
        if (site == null
                || site.key == null
                || !site.key.equals(cachedCategorySiteKey)
                || currentCategories == null
                || currentCategories.isEmpty()) return null;
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        for (Cms.Category c : currentCategories) {
            if (c == null || c.id == null || c.name == null || c.name.isEmpty()) continue;
            try {
                int id = Integer.parseInt(c.id.trim());
                if (id != 0) out.put(id, c.name);
            } catch (NumberFormatException ignored) {
                // A non-numeric category id carries no type id to resolve.
            }
        }
        return out.isEmpty() ? null : out;
    }

    private void applyHeroDiffusion(int page) {
        if (a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) return;
        if (page < 0 || page >= heroCandidates.size() || page >= heroArtworks.size()
                || page >= heroScrims.size()) return;
        heroDiffusionPage = page;
        heroDiffusionGen++;
        final int gen = heroDiffusionGen;
        Cms.Video v = heroCandidates.get(page);
        // mkoai 2.0.66: one-shot lazy backdrop / ratings resolution
        // (disk cache only — see resolveHeroBackdrop).
        resolveHeroBackdrop(page);
        // mkoai 2.0.68: the tone follows the image actually shown — the
        // poster when the hero has one, else the backdrop.
        String key = heroArtworkKey(v);
        Integer cached = heroToneCache.get(key);
        if (cached != null) {
            heroDiffusionTarget = cached.intValue();
            animateHeroBackground(cached.intValue());
            updateHeroScrim(page, cached.intValue());
            return;
        }
        updateHeroScrim(page, heroDiffusionShown);
        // mkoai 2.0.64: paint the bloom for the tone shown so far, so the
        // page is already a bloom (slate → deeper slate) while the poster
        // is still loading, not a flat slate that jumps when the tone lands.
        applyDiffusionFrame();
        sampleHeroTone(page, key, gen, 0);
    }

    /** The tone-cache key for a hero video: the image actually shown (poster first, else backdrop). */
    private static String heroArtworkKey(Cms.Video v) {
        if (v == null) return "title:";
        if (v.poster != null && !v.poster.isEmpty()) return v.poster;
        if (v.backdrop != null && !v.backdrop.isEmpty()) return v.backdrop;
        return "title:" + v.title;
    }

    /**
     * mkoai 2.0.68: one-shot lazy backdrop / ratings resolution for hero
     * page {@code page} — disk cache only (TmdbClient.cachedSearch, 7-day
     * TTL, never the network), on the artwork pool, so the hero adds no
     * requests and no new threads. If the cache yields a backdrop it is
     * recorded on the Video (future builds use it directly as the
     * poster-less fallback) and the artwork is swapped to it — but
     * ONLY for a video with no poster at all, and only while the
     * artwork still shows the placeholder; a poster hero is never
     * yanked mid-view, and its tone is never re-sampled under a
     * backdrop the user cannot see. The swap re-samples the tone
     * under the backdrop key, and a cached TMDB vote reveals the
     * ratings row. Runs once per artwork key (heroBackdropResolved),
     * never on every rotation.
     */
    private void resolveHeroBackdrop(final int page) {
        if (page < 0 || page >= heroCandidates.size()) return;
        final Cms.Video v = heroCandidates.get(page);
        if (v == null || v.title == null || v.title.trim().isEmpty()) return;
        final String key = heroArtworkKey(v);
        if (heroBackdropResolved.contains(key)) return;
        heroBackdropResolved.add(key);
        if (v.backdrop != null && !v.backdrop.isEmpty()) return;
        final int gen = heroDiffusionGen;
        try {
            RequestScope.submit(a.artwork, () -> {
                try {
                    TmdbClient.Item hit = TmdbClient.cachedSearch(
                            a.getFilesDir(), v.title, v.year);
                    if (hit == null) return;
                    final String backdrop = hit.backdrop;
                    final double vote = hit.vote;
                    a.runOnUiThread(() -> {
                        if (gen != heroDiffusionGen || page != heroDiffusionPage) return;
                        if (backdrop != null && !backdrop.isEmpty()) {
                            v.backdrop = backdrop;
                            // mkoai 2.0.68: the late backdrop only swaps in
                            // when the hero has no poster at all — a poster
                            // hero never gets yanked, and the tone is never
                            // re-sampled under a backdrop the user cannot
                            // see. Poster-less heroes get the backdrop
                            // CENTER_CROP (fills the box, subject centred).
                            if ((v.poster == null || v.poster.isEmpty())
                                    && page < heroArtworks.size()) {
                                ImageView art = heroArtworks.get(page);
                                if (art != null
                                        && !(art.getDrawable() instanceof BitmapDrawable)) {
                                    bindHeroArtwork(art, v);
                                    sampleHeroTone(page, backdrop, gen, 0);
                                }
                            }
                        }
                        if (vote > 0 && page < heroRatings.size()) {
                            TextView ratings = heroRatings.get(page);
                            if (ratings != null && ratings.getVisibility() != View.VISIBLE) {
                                String line = HeroRatings2066.ratingsLine(v.remarks, vote);
                                if (!line.isEmpty()) {
                                    ratings.setText(line);
                                    ratings.setVisibility(View.VISIBLE);
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {
                    // Disk cache unreadable: keep the poster, hide the
                    // ratings row — never a crash, never a retry storm.
                }
            });
        } catch (Throwable ignored) {
            // Artwork pool shut down (app closing): same as above.
        }
    }

    /**
     * Smooth 600ms retint of the home background. The tone is applied
     * through {@link #applyDiffusionFrame()} on every frame (and once
     * when nothing changes — see below), so the ScrollView, the top bar
     * and the top-edge fade always move together.
     */
    private void animateHeroBackground(int target) {
        if (homeScroll == null) {
            heroDiffusionShown = target;
            applyDiffusionFrame();
            return;
        }
        if (heroDiffusionAnimator != null) heroDiffusionAnimator.cancel();
        final int from = heroDiffusionShown;
        if (from == target) {
            // mkoai 2.0.64: the colour did not change — but the views may
            // be fresh instances. renderHomeTab rebuilds the ScrollView
            // (and the top bar) on every return home, and the new
            // ScrollView is painted slate at construction; if the tone
            // is already cached, this early return used to leave the new
            // page slate (the animator never ran, and every rotation hit
            // the same early return when the shown tone already matched
            // the target). Paint the current views even when the colour
            // itself is unchanged.
            applyDiffusionFrame();
            return;
        }
        heroDiffusionAnimator = ValueAnimator.ofArgb(from, target);
        heroDiffusionAnimator.setDuration(600);
        heroDiffusionAnimator.addUpdateListener(
                anim -> {
                    heroDiffusionShown = (int) anim.getAnimatedValue();
                    applyDiffusionFrame();
                });
        heroDiffusionAnimator.start();
    }

    /**
     * mkoai 2.0.64: paint one diffusion frame — the home ScrollView
     * background (the bloom gradient, re-tinted in place) and the
     * top-edge fade's base colour, all from the currently shown tone, so
     * the whole top of the home reads as one field. The fade is only
     * tinted while the home tab is active; on any other tab it keeps its
     * fixed near-black base (restored by switchTab), so a late animation
     * frame can never tint the status veil over the search or settings
     * page. Cheap setColors / setBaseColor calls only — no new threads,
     * no new scheduled work, no layout changes. The bloom drawable is
     * created once and mutated per frame; the ScrollView keeps the same
     * drawable across rebuilds of its content.
     *
     * <p>mkoai 2.0.66: the tone-following chrome (top overlay, home
     * text, dock, bottom veil, status-bar icons) is re-tinted ONLY when
     * the light/dark state flips or a fresh build lands — never per
     * animation frame. The per-frame work stays what it was (the bloom
     * setColors above); the flip block is a handful of setTextColor /
     * setBackground / setBaseColor calls, no layout, no invalidation
     * storm.
     */
    private void applyDiffusionFrame() {
        if (homeScroll != null) {
            if (heroBloomGradient == null) {
                heroBloomGradient =
                        new android.graphics.drawable.GradientDrawable(
                                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                                // mkoai 2.0.65: anchored to the hero's
                                // bottom edge at rest
                                // (heroDiffusionHoldFraction) — the tone
                                // holds until the hero ends, then fades
                                // outward, so the rest state already looks
                                // like the scrolled state.
                                HeroDiffusion2063.bloomColorsAnchored(
                                        heroDiffusionShown, heroDiffusionHoldFraction));
            } else {
                heroBloomGradient.setColors(HeroDiffusion2063.bloomColorsAnchored(
                        heroDiffusionShown, heroDiffusionHoldFraction));
            }
            homeScroll.setBackground(heroBloomGradient);
        }
        boolean light = HomeTone2066.isLight(heroDiffusionShown);
        if (light != homeIsLight || homeTextDirty) {
            retintTopOverlay(heroDiffusionShown);
            retintHomeText(heroDiffusionShown);
            retintProgressTracks(heroDiffusionShown);
            retintDock();
            applyStatusBarIcons(light);
            if (bottomEdgeFade != null) bottomEdgeFade.setBaseColor(heroDiffusionShown);
            homeIsLight = light;
        }
        if (homeTopFade != null && activeTab == 0) {
            homeTopFade.setBaseColor(heroDiffusionShown);
        }
    }

    /**
     * mkoai 2.0.66: the status-bar icons follow the page tone — dark
     * icons on a light hero background (the MainActivity leaves the
     * status bar transparent with white icons, which are invisible on
     * a light page), white icons on a dark one. LIGHT_STATUS_BAR is
     * API 23+; below that the flag is ignored and the icons stay white.
     * Restored to white by switchTab when leaving the home tab.
     */
    private void applyStatusBarIcons(boolean light) {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            android.view.View decor = a.getWindow().getDecorView();
            int vis = decor.getSystemUiVisibility();
            int flag = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            int next = light ? (vis | flag) : (vis & ~flag);
            if (next != vis) decor.setSystemUiVisibility(next);
        } catch (Throwable ignored) {
        }
    }

    /** Retint one hero page's bottom-fade scrim (and its card) to {@code color}. */
    private void updateHeroScrim(int page, int color) {
        if (page < 0 || page >= heroScrims.size()) return;
        View scrim = heroScrims.get(page);
        if (scrim == null) return;
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                HeroDiffusion2063.scrimColors(color)));
        View parent = (View) scrim.getParent();
        if (parent != null) parent.setBackgroundColor(color);
        // mkoai 2.0.66: the hero TOP fade is gone (the top bar is a
        // floating overlay now, not a band above the hero — there is no
        // seam left to melt). The bottom scrim is the only fade.
    }

    /**
     * Retries while a hero poster is still decoding (500ms apart).
     * 24 retries ≈ 12s — one full auto-rotate cycle. The 2.0.63 window
     * (8 retries, ~4s) was too short for a cold first load (the Worker
     * /img proxy + CDN can take longer), and a missed window left the
     * page on the slate until a rotation re-triggered sampling; a
     * single-card hero has no rotation at all, so a miss there was
     * permanent. Same mechanism, just a longer window — still bounded,
     * and a swipe or a rebuild still stops the retries at once (the
     * generation guard).
     */
    private static final int HERO_TONE_MAX_RETRIES = 24;

    /**
     * Sample hero page {@code page}'s tone from its already-decoded
     * artwork. The drawable is read on the UI thread; the scale-down +
     * average runs on the artwork pool; the result is applied on the UI
     * thread only if this page is still current (generation-guarded), so
     * a late bitmap can never recolour a hero the user has already left.
     * A tap never waits for any of this.
     */
    private void sampleHeroTone(final int page, final String key, final int gen, final int attempt) {
        if (gen != heroDiffusionGen || page != heroDiffusionPage) return;
        if (page < 0 || page >= heroArtworks.size()) return;
        final ImageView art = heroArtworks.get(page);
        if (art == null) return;
        Drawable d = art.getDrawable();
        if (!(d instanceof BitmapDrawable)) {
            // Poster not decoded yet (still the placeholder) — retry
            // shortly; never blocks a tap.
            if (attempt < HERO_TONE_MAX_RETRIES) {
                art.postDelayed(() -> sampleHeroTone(page, key, gen, attempt + 1), 500);
            }
            return;
        }
        final Bitmap bm = ((BitmapDrawable) d).getBitmap();
        if (bm == null || bm.isRecycled()) {
            if (attempt < HERO_TONE_MAX_RETRIES) {
                art.postDelayed(() -> sampleHeroTone(page, key, gen, attempt + 1), 500);
            }
            return;
        }
        try {
            RequestScope.submit(a.artwork, () -> {
                try {
                    Bitmap small = Bitmap.createScaledBitmap(bm, 32, 24, true);
                    if (small == null) return;
                    int[] px = new int[32 * 24];
                    small.getPixels(px, 0, 32, 0, 0, 32, 24);
                    // mkoai 2.0.66: the light-preserving page tone — a
                    // light poster stays light (Emby), instead of being
                    // forced dark by backgroundTone.
                    final int tone = HeroDiffusion2063.pageTone(
                            HeroDiffusion2063.averageTone(px, px.length));
                    small.recycle();
                    art.post(() -> {
                        if (gen != heroDiffusionGen || page != heroDiffusionPage) return;
                        heroToneCache.put(key, tone);
                        heroDiffusionTarget = tone;
                        animateHeroBackground(tone);
                        updateHeroScrim(page, tone);
                    });
                } catch (Throwable ignored) {
                    // Bitmap recycled mid-sample or pool shut down: stay
                    // on the current background; the next rotation
                    // re-samples.
                }
            });
        } catch (Throwable ignored) {
            // Artwork pool shut down (app closing): stay on the current
            // background.
        }
    }

    private List<VideoFilters.Definition> mobileFilterDefinitions() {
        String category =
                selectedCategoryId.isEmpty() && !currentCategories.isEmpty()
                        ? currentCategories.get(0).id
                        : selectedCategoryId;
        List<VideoFilters.Definition> defs =
                VideoFilters.definitions(filterSchema, category, currentVideos);
        if (currentSite != null && currentSite.supported())
            for (VideoFilters.Definition def : defs)
                if (!VideoFilters.allowedKey(def.key)) def.remote = false;
        return defs;
    }

    /**
     * Builds a category pill 1:1 with iPhone `_buildCategoryPill`: white stadium pill with black
     * bold text when selected, translucent dark glass pill with white text when unselected.
     */
    private void addCategoryChip(
            LinearLayout row,
            String label,
            String catId,
            Runnable refreshChips,
            Runnable loadPage) {
        boolean active = selectedCategoryId.equals(catId);
        float navScale = Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100)) / 100f;
        TextView chip = a.text(label, 13 * navScale, active ? 0xff000000 : 0xfff3f4f6);
        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(
                a.dp(15 * navScale), a.dp(7 * navScale), a.dp(15 * navScale), a.dp(7 * navScale));
        if (active) {
            chip.setBackground(glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), a.dp(0.7f), 0xffffffff));
        } else {
            chip.setBackground(glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
        }
        MobileDetailScreen.bindTouchFeedback(chip);
        chip.setOnClickListener(
                v -> {
                    if (selectedCategoryId.equals(catId)) return;
                    selectedCategoryId = catId;
                    selectedFilters.clear();
                    currentPage = 1;
                    homeScrollY = 0;
                    refreshChips.run();
                    loadPage.run();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = a.dp(8);
        row.addView(chip, lp);
    }

    /**
     * Renders the 3-column portrait poster grid matching the iPhone screenshot (`_buildVideoCard`):
     * `12dp` rounded `2:3` poster with a smooth bottom gradient scrim and right-aligned `remarks`
     * inside the poster bottom edge, plus two-line left-aligned `title` + `year · area` below.
     */
    // mkoai: Netflix-style home sections — group the home videos by type
    // into horizontal scrolling rows, each with a section title.
    private void renderHomeSections(
            LinearLayout container, Cms.Site site, List<Cms.Video> videos) {
        container.removeAllViews();
        // mkoai 2.0.61 (h): the phone 推薦 sections show only movies and
        // dramas/series — variety, documentaries and kids titles are
        // dropped here, at the one place all recommendation sections are
        // built from. Category pages (renderVideoGrid) and search are
        // untouched: they take their own list, not this one.
        videos = VideoKind2061.filterRecommendations(videos);
        // mkoai 2.0.66: the recommendation rule (hard) — home
        // recommendation rows show ONLY titles from the last 4 years
        // (2022+). Unknown year fails open (kept); a known older year
        // is dropped. The Douban top-rated row is a recommendation row
        // too — no exception. Continue Watching, search, category grids
        // and favourites never pass through here.
        videos = HomeYear2066.filterRecommendations(videos,
                v -> v == null ? -1 : HomeYear2066.parseYear(v.year),
                HomeYear2066.currentYear());
        // mkoai 2.0.51: never show the "no recommendations" copy while
        // the TMDB checks are still running or their rows are visible —
        // the rows below are still filling in (or already showing
        // fail-open titles). The copy appears only when the home is
        // genuinely empty everywhere.
        if (videos.isEmpty() && !tmdbRowsLoading && !hasVisibleTmdbRows()) {
            TextView hint = a.text("目前没有已确认片源收录的推荐，可直接搜索作品或重新整理。", 14, 0xffb8c2bd);
            hint.setPadding(0, a.dp(24), 0, a.dp(20));
            registerHomeText(hint, HOME_TEXT_MUTED);
            container.addView(hint);
        }
        java.util.LinkedHashMap<String, List<Cms.Video>> groups =
                new java.util.LinkedHashMap<>();
        for (Cms.Video v : videos) {
            String key = v.tag != null ? v.tag.trim() : "";
            if (key.isEmpty() && v.genre != null) key = v.genre.trim();
            if (key.isEmpty()) key = "推薦";
            List<Cms.Video> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(v);
        }
        int cardWidthDp = 128;
        int posterHeightDp = Math.round(cardWidthDp * 1.48f);
        // mkoai 2.0.61: 继续观看 moved out of this method into its own
        // container directly under the hero (see the renderHomeSections
        // call sites) — it renders even while these sections load.
        // mkoai: Netflix-style Top 10 row — the first 10 home titles with
        // giant outlined numerals beside each poster. Hidden when empty.
        if (!videos.isEmpty()) {
            TextView top10Title = a.text("Top 10", 16, MainActivity.WHITE);
            top10Title.setTypeface(null, Typeface.BOLD);
            // mkoai 2.0.66: no shadow — the title sits on the page tone
            // now, and the tone itself guarantees the contrast (a black
            // shadow would smudge dark text on a light page).
            top10Title.setPadding(0, a.dp(14), 0, a.dp(8));
            registerHomeText(top10Title, HOME_TEXT_PRIMARY);
            container.addView(top10Title);
            HorizontalScrollView top10Scroll = new HorizontalScrollView(a);
            top10Scroll.setHorizontalScrollBarEnabled(false);
            top10Scroll.setClipToPadding(false);
            top10Scroll.setPadding(0, 0, 0, a.dp(6));
            LinearLayout top10Row = a.row();
            top10Row.setGravity(Gravity.CENTER_VERTICAL);
            top10Scroll.addView(top10Row);
            int topCount = Math.min(10, videos.size());
            for (int rank = 1; rank <= topCount; rank++) {
                Cms.Video v = videos.get(rank - 1);
                LinearLayout item = a.row();
                item.setGravity(Gravity.CENTER_VERTICAL);
                // Giant outlined numeral (the Top 10 visual signature)
                TextView num =
                        a.text(String.valueOf(rank), rank == 10 ? 52 : 64, 0xffd4d4d4);
                num.setTypeface(null, Typeface.BOLD);
                num.getPaint().setStyle(Paint.Style.STROKE);
                num.getPaint().setStrokeWidth(a.dp(1.5f));
                num.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams numLp =
                        new LinearLayout.LayoutParams(a.dp(rank == 10 ? 72 : 52), -2);
                item.addView(num, numLp);
                LinearLayout card =
                        buildHomePosterCard(site, v, cardWidthDp, posterHeightDp);
                LinearLayout.LayoutParams cardLp =
                        new LinearLayout.LayoutParams(a.dp(cardWidthDp), -2);
                // Overlap the poster's left edge over the numeral
                cardLp.leftMargin = a.dp(-14);
                cardLp.rightMargin = a.dp(10);
                item.addView(card, cardLp);
                top10Row.addView(item);
            }
            container.addView(top10Scroll, new LinearLayout.LayoutParams(-1, -2));
        }
        // mkoai 2.0.38: Netflix row — awwrated Netflix titles (platform
        // "netflix", databaseId 2). The list reaching here already passed
        // the strict source filter; an empty row stays hidden.
        List<Cms.Video> netflix = new ArrayList<>();
        for (Cms.Video v : videos) {
            if ("netflix".equals(v.platform)) netflix.add(v);
        }
        if (!netflix.isEmpty()) {
            TextView netflixTitle = a.text("Netflix", 16, MainActivity.WHITE);
            netflixTitle.setTypeface(null, Typeface.BOLD);
            // mkoai 2.0.66: no shadow (see top10Title above).
            netflixTitle.setPadding(0, a.dp(14), 0, a.dp(8));
            registerHomeText(netflixTitle, HOME_TEXT_PRIMARY);
            container.addView(netflixTitle);
            HorizontalScrollView netflixScroll = new HorizontalScrollView(a);
            netflixScroll.setHorizontalScrollBarEnabled(false);
            netflixScroll.setClipToPadding(false);
            netflixScroll.setPadding(0, 0, 0, a.dp(6));
            LinearLayout netflixRow = a.row();
            netflixRow.setGravity(Gravity.TOP);
            netflixScroll.addView(netflixRow);
            for (Cms.Video v : netflix) {
                LinearLayout card = buildHomePosterCard(site, v, cardWidthDp, posterHeightDp);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(a.dp(cardWidthDp), -2);
                lp.rightMargin = a.dp(10);
                netflixRow.addView(card, lp);
            }
            container.addView(netflixScroll, new LinearLayout.LayoutParams(-1, -2));
        }
        for (java.util.Map.Entry<String, List<Cms.Video>> e : groups.entrySet()) {
            String title = e.getKey();
            // mkoai 2.0.26: home is awwrated only, so the only section is
            // awwrated. (Douban/Simkl mappings removed with their home modes.)
            if ("awwrated".equals(title)) title = "awwrated 推荐";
            else title = ChineseConvert.s2t(title);
            // mkoai: section titles stay as the source serves them
            // (Simplified) — do not s2t (see 2.0.18 garbled names).
            TextView sectionTitle = a.text(title, 16, MainActivity.WHITE);
            sectionTitle.setTypeface(null, Typeface.BOLD);
            // mkoai 2.0.66: no shadow (see top10Title above).
            sectionTitle.setPadding(0, a.dp(14), 0, a.dp(8));
            registerHomeText(sectionTitle, HOME_TEXT_PRIMARY);
            container.addView(sectionTitle);
            HorizontalScrollView rowScroll = new HorizontalScrollView(a);
            rowScroll.setHorizontalScrollBarEnabled(false);
            rowScroll.setClipToPadding(false);
            rowScroll.setPadding(0, 0, 0, a.dp(6));
            LinearLayout row = a.row();
            row.setGravity(Gravity.TOP);
            rowScroll.addView(row);
            for (Cms.Video v : e.getValue()) {
                LinearLayout card = buildHomePosterCard(site, v, cardWidthDp, posterHeightDp);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(a.dp(cardWidthDp), -2);
                lp.rightMargin = a.dp(10);
                row.addView(card, lp);
            }
            container.addView(rowScroll, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /**
     * mkoai 2.0.38: 继续观看 row — the 10 most recent WATCH_HISTORY entries.
     * mkoai 2.0.61: renders into its own container directly under the hero
     * (called from the renderHomeSections call sites, not from
     * renderHomeSections itself), so it shows even while the awwrated
     * sections below are still loading. Clears the container first: it no
     * longer relies on the caller to clear it.
     */
    private void renderContinueWatchingRow(LinearLayout container, Cms.Site site) {
        if (container == null) return;
        container.removeAllViews();
        int cardWidthDp = 128;
        int posterHeightDp = Math.round(cardWidthDp * 1.48f);
        // 2.0.41: display-only dedupe — the same show+episode watched via
        // different sources/ids shows once (latest update wins).
        List<WatchHistory.Entry> items = WatchHistory.deduped(a.watchHistory());
        if (items == null || items.isEmpty()) return;
        int shown = Math.min(10, items.size());
        TextView title = a.text("继续观看", 16, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        // mkoai 2.0.66: no shadow (see top10Title in renderHomeSections).
        title.setPadding(0, a.dp(14), 0, a.dp(8));
        registerHomeText(title, HOME_TEXT_PRIMARY);
        container.addView(title);
        HorizontalScrollView rowScroll = new HorizontalScrollView(a);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(0, 0, 0, a.dp(6));
        LinearLayout row = a.row();
        row.setGravity(Gravity.TOP);
        rowScroll.addView(row);
        for (int i = 0; i < shown; i++) {
            WatchHistory.Entry e = items.get(i);
            // mkoai 2.0.66: the horizontal card is 260x104 (thumb 68x104
            // + text column), not the old 260x146 landscape crop.
            LinearLayout card = buildHistoryCard(e, 260, 104);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(260), -2);
            lp.rightMargin = a.dp(12);
            row.addView(card, lp);
        }
        container.addView(rowScroll, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * mkoai 2.0.66: one 繼續觀看 card, Emby-style horizontal — a 2:3
     * portrait thumb on the left, title + episode/time + progress bar
     * in a column on the right. The old card was a landscape crop of a
     * portrait poster (260x146 over a 2:3 image, ~17% of the height
     * cut — the HEARTLAND card's bottom text cut in half) with a 3dp
     * progress bar and an 11sp time line that were nearly invisible.
     * The thumb is 68x104 (2:3, ~2% crop), the progress bar is 4dp
     * across the full text column, and the time sits under the title
     * where it can always be read. Tap resumes.
     */
    private LinearLayout buildHistoryCard(
            final WatchHistory.Entry e, int cardWidthDp, int posterHeightDp) {
        LinearLayout card = a.row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        MobileDetailScreen.bindTouchFeedback(card);
        card.setOnClickListener(view -> a.resumeHistory(e));
        boolean done = e.complete();
        float frac = 0f;
        if (!done && e.duration > 0 && e.position > 0) {
            frac = Math.min(1f, (float) e.position / (float) e.duration);
        }
        // Left: the portrait thumb (2:3 — the poster's own ratio, so
        // CENTER_CROP cuts ~2%, not ~17%).
        int thumbWidthDp = 68;
        FrameLayout art = new FrameLayout(a);
        art.setBackground(glassBg(0xff1c2421, 0xff141a18, a.dp(12), 0, 0));
        art.setClipToOutline(true);
        art.setElevation(a.dp(2)); // mkoai 2.0.64: faint shadow edges the card - the 0.6dp glass stroke above is gone (it read as a pasted-on rectangle).
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(img, new FrameLayout.LayoutParams(-1, -1));
        Cms.Video resumeVideo = new Cms.Video();
        resumeVideo.title = e.title;
        resumeVideo.poster = e.poster;
        bindReliablePoster(img, resumeVideo);
        TextView playOverlay = a.text("▷", 24, Color.WHITE);
        playOverlay.setGravity(Gravity.CENTER);
        playOverlay.setContentDescription("繼續播放 " + e.title);
        art.addView(playOverlay, new FrameLayout.LayoutParams(-1, -1));
        // 2.0.40: year bottom-left on the thumb. History entries carry
        // no year, so it is filled from TMDB in the background when
        // available.
        TextView yearTv = addPosterOverlay(a, art, null, null);
        enrichYearLabel(a, yearTv, e.title);
        if (done) {
            TextView badge = a.text("已看完", 10, 0xfff5f7fa);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setPadding(a.dp(7), a.dp(3), a.dp(7), a.dp(3));
            badge.setBackground(a.bg(0xcc000000, 999));
            FrameLayout.LayoutParams badgeLp =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            badgeLp.setMargins(0, a.dp(6), a.dp(6), 0);
            art.addView(badge, badgeLp);
        }
        card.addView(art, new LinearLayout.LayoutParams(a.dp(thumbWidthDp), a.dp(posterHeightDp)));
        // Right: title + episode/time + progress bar, on the page tone
        // (not on the poster) — so the text follows the tone.
        LinearLayout col = a.vertical();
        col.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
        TextView title = a.text(e.title, 13, 0xfff5f7fa);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        registerHomeText(title, HOME_TEXT_PRIMARY);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));
        String sub = a.historyLabel(e);
        if (sub != null && !sub.isEmpty()) {
            TextView subTv = a.text(sub, 11, 0xffc8ccd2);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            subTv.setPadding(0, a.dp(3), 0, 0);
            registerHomeText(subTv, HOME_TEXT_MUTED);
            col.addView(subTv, new LinearLayout.LayoutParams(-1, -2));
        }
        // Progress bar (or full bar when finished) across the full text
        // column — always visible, never overlaid on the poster.
        if (done || frac > 0) {
            FrameLayout progressWrap = new FrameLayout(a);
            progressWrap.setBackground(
                    new android.graphics.drawable.ColorDrawable(
                            HomeTone2066.progressTrack(heroDiffusionShown)));
            homeProgressTracks.add(progressWrap);
            View fill = new View(a);
            fill.setBackground(
                    new android.graphics.drawable.ColorDrawable(HomeTone2066.PROGRESS_FILL));
            int colWidthDp = cardWidthDp - thumbWidthDp - 20;
            int fillW = done ? colWidthDp : Math.max(2, Math.round(colWidthDp * frac));
            progressWrap.addView(fill, new FrameLayout.LayoutParams(a.dp(fillW), a.dp(4)));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, a.dp(4));
            barLp.topMargin = a.dp(8);
            col.addView(progressWrap, barLp);
        }
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    // ---- mkoai 2.0.38: TMDB home rows + "我的媒體" cards (Emby style) ----

    private TmdbRow findTmdbRow(String kind) {
        for (TmdbRow r : tmdbRows) {
            if (r.kind.equals(kind)) return r;
        }
        return null;
    }

    /**
     * Kick off the TMDB home rows for one home load (once per request).
     *
     * <p>mkoai 2.0.39: availability is checked against ALL configured
     * sources, not just the current one — a title counts as playable
     * when ANY source has it, and the card remembers which source
     * confirmed it (a tap opens the detail page there, where the id
     * actually exists). Phase A: TMDB lists (disk cache first, 6h TTL)
     * + disk availability across all sources — confirmed titles go
     * straight in, titles known-absent everywhere are dropped, the rest
     * wait for phase B. Phase B: live-check the unchecked titles in
     * source rounds (current first, 8 threads, 60s shared budget, at
     * most 120 titles collected round-robin across rows so every row
     * gets a share), persist the results per source, re-render after
     * each round. Only titles confirmed present in some source ever
     * appear; a row with no confirmed titles stays hidden; when every
     * row is empty the home simply keeps its awwrated sections (never
     * blank).
     */
    /**
     * 2.0.40: cross-source check sites for the awwrated home — the
     * current site first, then the rest of the configured sources
     * (dedup by api). Youth mode skips adult-mixed sources entirely.
     * No cap: the disk-only first paint and the live engines both use
     * the full list (2.0.51: all configured sources are candidates).
     */
    private List<Cms.Site> crossCheckSites(Cms.Site current) {
        List<Cms.Site> out = new ArrayList<>();
        Set<String> seenApi = new HashSet<>();
        if (current != null && a.available(current) && current.api != null && !current.api.isEmpty()) {
            out.add(current);
            seenApi.add(current.api);
        }
        boolean youthOn = a.prefs.getBoolean(Prefs.YOUTH_MODE, false);
        if (a.config != null && a.config.sites != null) {
            // 2.0.42: priority (ad-free) sources next, in list order — the
            // cross-source availability check tries them before the rest,
            // so a card's remembered source is a priority one when it has
            // the title. The current source still goes first (the user is
            // already browsing it); youth mode still skips 大地 entirely.
            // 2.0.61 (k): the priority list is 巨量, 360, 魔都動漫, xgzy.
            for (String host : SourcePreferences.PRIORITY_SOURCE_HOSTS) {
                for (Cms.Site s : a.config.sites) {
                    if (s == null
                            || s.api == null
                            || !SourcePreferences.apiMatchesHost(s.api, host)
                            || seenApi.contains(s.api)) {
                        continue;
                    }
                    if (!a.available(s) || (youthOn && SearchVariants.isAdultSource(s))) continue;
                    seenApi.add(s.api);
                    out.add(s);
                }
            }
            for (Cms.Site s : a.config.sites) {
                if (s == null
                        || s.api == null
                        || s.api.isEmpty()
                        || seenApi.contains(s.api)) {
                    continue;
                }
                if (!a.available(s) || (youthOn && SearchVariants.isAdultSource(s))) continue;
                seenApi.add(s.api);
                out.add(s);
            }
        }
        return out;
    }

    /** API keys of crossCheckSites, in the same order (for disk lookups). */
    private List<String> crossCheckApiKeys(Cms.Site current) {
        List<String> keys = new ArrayList<>();
        for (Cms.Site s : crossCheckSites(current)) keys.add(s.api);
        return keys;
    }

    private void startTmdbRows(final Cms.Site site, final int request) {
        if (site == null || tmdbRowsLoading || tmdbRowsTicket == request) return;
        tmdbRowsTicket = request;
        tmdbRowsLoading = true;
        final int gen = a.generation;
        final int req = request;
        a.submitPage(
                () -> {
                    try {
                        final java.io.File filesDir = a.getFilesDir();
                        // Check sites: current first, then the rest of the
                        // configured sources (dedup by api). Youth mode
                        // skips adult-mixed sources entirely.
                        // mkoai 2.0.51: ALL configured sources are
                        // candidates (was: first 10 only). crossCheckSites
                        // already dedups by api, puts the current source
                        // first, priority sources next, and skips
                        // adult-mixed sources in youth mode.
                        final List<Cms.Site> checkSites = crossCheckSites(site);
                        final List<String> apiKeys = new ArrayList<>();
                        for (Cms.Site s : checkSites) apiKeys.add(s.api);
                        final long now = System.currentTimeMillis();
                        final org.json.JSONObject disk =
                                AwwratedHome.loadAvailDisk(filesDir);
                        List<TmdbRow> rows = new ArrayList<>();
                        for (String[] def : TMDB_ROW_DEFS) {
                            rows.add(new TmdbRow(def[0], def[1], def[2]));
                        }
                        List<java.util.concurrent.Callable<List<TmdbClient.Item>>> metadataJobs = new ArrayList<>();
                        for (TmdbRow row : rows) {
                            // mkoai 2.0.61: 豆瓣高分 is fetched from Douban
                            // (direct Douban; no Worker route for it yet) —
                            // not a TmdbClient kind. A failed fetch yields
                            // null (job cancelled/failed) and the row hides.
                            // mkoai 2.0.62: the external-ranking rows are
                            // fetched the same way (Trakt / Simkl / TVmaze
                            // fetchers, one job each, same BoundedBatch —
                            // 4 threads / 12s shared budget, no extra pool).
                            if (HomeRows2061.isDoubanRow(row.kind))
                                metadataJobs.add(() -> DoubanHome.fetchTopRatedItems());
                            else if (HomeRows2061.KIND_TRAKT_MOVIES.equals(row.kind))
                                metadataJobs.add(() -> fetchChartItems(
                                        QuotaState2066.TRAKT,
                                        () -> TraktHome.fetchMoviesItems(quotaStore())));
                            else if (HomeRows2061.KIND_TRAKT_SHOWS.equals(row.kind))
                                metadataJobs.add(() -> fetchChartItems(
                                        QuotaState2066.TRAKT,
                                        () -> TraktHome.fetchShowsItems(quotaStore())));
                            else if (HomeRows2061.KIND_SIMKL_MOVIES.equals(row.kind))
                                metadataJobs.add(() -> fetchChartItems(
                                        QuotaState2066.SIMKL,
                                        () -> SimklHome.fetchMoviesItems(quotaStore())));
                            else if (HomeRows2061.KIND_SIMKL_SHOWS.equals(row.kind))
                                metadataJobs.add(() -> fetchChartItems(
                                        QuotaState2066.SIMKL,
                                        () -> SimklHome.fetchTvItems(quotaStore())));
                            else if (HomeRows2061.KIND_TVMAZE_TONIGHT.equals(row.kind))
                                metadataJobs.add(() -> fetchChartItems(
                                        QuotaState2066.TVMAZE,
                                        () -> TvmazeHome.fetchTonightItems(quotaStore())));
                            else
                                metadataJobs.add(
                                        () -> TmdbClient.list(filesDir, row.kind, row.extra));
                        }
                        List<List<TmdbClient.Item>> metadata = BoundedBatch.run(metadataJobs, 4, 12000L);
                        // mkoai 2.0.63: translate every home-row item that
                        // still has a foreign title. TMDB rows return
                        // zh-TW titles, but an item with no zh-TW
                        // translation stays English — and an English title
                        // also misses the Chinese availability lookup
                        // below and the msearch key (tapping it searched
                        // English and found nothing; only Chinese finds
                        // it). The fast path inside TitleTranslator makes
                        // already-Chinese titles free (no network), and
                        // the batch is globally bounded (5s) so first
                        // paint never waits long. Chart rows already
                        // translate at fetch — this pass is a bounded
                        // retry for the ones that failed there (failures
                        // are never cached). Runs here on the background
                        // thread, never the UI thread.
                        List<TmdbClient.Item> allRowItems = new ArrayList<>();
                        for (List<TmdbClient.Item> one : metadata) {
                            if (one != null) allRowItems.addAll(one);
                        }
                        TitleTranslator.translateItems(allRowItems, 5000L);
                        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                            TmdbRow row = rows.get(rowIndex);
                            if (gen != a.generation || req != homeRequest || a.isFinishing()) return;
                            List<TmdbClient.Item> items = metadata.get(rowIndex);
                            if (items == null) continue;
                            row.listsLoaded = true;
                            for (TmdbClient.Item it : items) {
                                if (row.confirmed.size() + row.pending.size()
                                        >= TMDB_ROW_MAX_ITEMS) break;
                                // mkoai 2.0.45: the 2-day release delay
                                // applies to TMDB rows too — a title
                                // released less than 2 days ago never
                                // enters a recommendation row. The check
                                // runs here (at row build, not at fetch)
                                // because TmdbClient.list() has a 6h disk
                                // cache and time passes: a title that was
                                // too young at fetch time must not linger
                                // once the cache is re-read. Empty /
                                // unparseable dates fail open (kept).
                                if (!AwwratedHome.isOldEnoughDate(it.date)) continue;
                                // mkoai 2.0.66: the recommendation rule
                                // (hard) — TMDB / chart / Douban rows show
                                // ONLY titles from the last 4 years
                                // (2022+). Unknown year fails open (kept).
                                // The TVmaze tonight row is EXEMPT: its
                                // year is the show's premiere year, not
                                // the content's recency — a 2015 show
                                // airing a new episode tonight IS new
                                // content, and dropping it would break
                                // the schedule's purpose. The view-all
                                // pages read the same confirmed list, so
                                // they inherit the rule.
                                if (!HomeRows2061.isTvmazeRow(row.kind)
                                        && !HomeYear2066.keepInRecommendations(
                                                HomeYear2066.parseYear(it.year),
                                                HomeYear2066.currentYear())) continue;
                                Cms.Video v = TmdbClient.toVideo(it);
                                // mkoai 2.0.61: 豆瓣高分 cards come from
                                // Douban, not TMDB — tag them so the
                                // detail/search flow treats them as such.
                                // mkoai 2.0.62: same for the external
                                // rankings (tag = provider, so
                                // VideoKind2061 / the detail flow can
                                // tell them apart); the row kind itself
                                // is the movie/series fallback when the
                                // fetcher left Item.type empty, so the
                                // 電影/劇 badge still shows. TVmaze
                                // airtime replaces the ★ rating remarks —
                                // when it airs matters more than its score.
                                if (HomeRows2061.isDoubanRow(row.kind)) v.tag = "douban";
                                else if (HomeRows2061.isTraktRow(row.kind)) v.tag = "trakt";
                                else if (HomeRows2061.isSimklRow(row.kind)) v.tag = "simkl";
                                else if (HomeRows2061.isTvmazeRow(row.kind)) {
                                    v.tag = "tvmaze";
                                    if (it.airInfo != null && !it.airInfo.isEmpty())
                                        v.remarks = it.airInfo;
                                }
                                if ((v.mediaType == null || v.mediaType.isEmpty())
                                        && HomeRows2061.isExternalRow(row.kind)) {
                                    v.mediaType =
                                            HomeRows2061.KIND_TRAKT_MOVIES.equals(row.kind)
                                                            || HomeRows2061.KIND_SIMKL_MOVIES
                                                                    .equals(row.kind)
                                                    ? "movie"
                                                    : "tv";
                                }
                                if (v.title == null || v.title.isEmpty()) continue;
                                AwwratedHome.CrossHit hit =
                                        AwwratedHome.cachedHitAny(disk, apiKeys, v.title, now);
                                if (hit != null) {
                                    if (hit.detailId != null && !hit.detailId.isEmpty()) {
                                        v.id = hit.detailId;
                                    }
                                    v.sourceApi = hit.sourceKey;
                                    // mkoai 2.0.46: poster fallback chain
                                    // (TMDB poster -> cached source poster
                                    // -> TMDB backdrop). The live source
                                    // fetch (step c) runs in the Phase B
                                    // top-up pass below. A TMDB poster is
                                    // never overwritten.
                                    String resolved =
                                            AwwratedHome.resolvePoster(
                                                    filesDir,
                                                    v.poster,
                                                    hit.sourceKey,
                                                    v.title,
                                                    v.backdrop);
                                    if (resolved != null && !resolved.isEmpty()) {
                                        v.poster = resolved;
                                    }
                                    row.confirmed.add(v);
                                } else if (!AwwratedHome.cachedAbsentEverywhere(
                                        disk, apiKeys, v.title, now)) {
                                    row.pending.add(v);
                                }
                                // Known-absent everywhere: dropped, never shown.
                            }
                        }
                        tmdbRows = rows;
                        // mkoai 2.0.51: FIRST PAINT NOW, from the disk
                        // cache (confirmed titles, plus pending titles via
                        // the display fallback in renderTmdbRows) — the
                        // rows appear as soon as the TMDB metadata is in,
                        // instead of waiting up to 2.5s for the poster
                        // pre-resolution below. The pre-resolution still
                        // runs right after and re-renders with posters.
                        a.ui.post(() -> renderTmdbRows(site, req, gen));
                        // Engines, one per check site (current first).
                        // Built before the poster pre-resolution below so
                        // a card is born with its poster when possible.
                        final List<SourceEngine> engs = new ArrayList<>();
                        final List<String> engKeys = new ArrayList<>();
                        for (Cms.Site s : checkSites) {
                            try {
                                SourceEngine eng = a.engine(s);
                                if (eng != null) {
                                    engs.add(eng);
                                    engKeys.add(s.api);
                                }
                            } catch (Throwable ignored) {
                                // One dead engine must not kill the other sources.
                            }
                        }
                        // mkoai 2.0.49: resolve posters BEFORE the first
                        // paint (same reason as the awwrated home above):
                        // the first 24 confirmed poster-less titles, in
                        // row order, resolved in parallel under one 2.5s
                        // budget. Best-effort — the Phase B top-up and
                        // maybeEnrichPoster remain as backstops.
                        try {
                            List<Cms.Video> confirmedAll = new ArrayList<>();
                            for (TmdbRow row : rows) {
                                confirmedAll.addAll(row.display());
                            }
                            AwwratedHome.resolvePostersLive(
                                    confirmedAll, engs, engKeys, filesDir, 2500L, 24);
                        } catch (Throwable ignored) {
                        }
                        // Second paint: posters filled by pre-resolution.
                        a.ui.post(() -> renderTmdbRows(site, req, gen));
                        // Phase B: live-check the still-unchecked titles,
                        // round-robin across rows so every row gets a share.
                        List<Cms.Video> toCheck = new ArrayList<>();
                        int idx = 0;
                        boolean added = true;
                        while (added && toCheck.size() < TMDB_CHECK_MAX_TITLES) {
                            added = false;
                            for (TmdbRow row : rows) {
                                if (toCheck.size() >= TMDB_CHECK_MAX_TITLES) break;
                                if (idx < row.pending.size()) {
                                    toCheck.add(row.pending.get(idx));
                                    added = true;
                                }
                            }
                            idx++;
                        }
                        if (toCheck.isEmpty()) return;
                        // Engines were built before the first render (2.0.49,
                        // poster pre-resolution above); Phase B reuses them.
                        if (engs.isEmpty()) return;
                        final long deadline =
                                System.currentTimeMillis() + TMDB_CHECK_BUDGET_MS;
                        ExecutorService pool =
                                Executors.newFixedThreadPool(
                                        TMDB_CHECK_THREADS,
                                        r -> {
                                            Thread t = new Thread(r, "tmdb-availability");
                                            t.setDaemon(true);
                                            return t;
                                        });
                        try {
                            // Source rounds: each round checks the titles
                            // still unconfirmed against one more source.
                            // Confirmed titles are promoted immediately and
                            // the rows re-render after every round.
                            for (int si = 0; si < engs.size(); si++) {
                                if (gen != a.generation
                                        || req != homeRequest
                                        || a.isFinishing()) {
                                    return;
                                }
                                final SourceEngine eng = engs.get(si);
                                final String skey = engKeys.get(si);
                                List<Cms.Video> batch = new ArrayList<>(toCheck);
                                if (batch.isEmpty()) break;
                                long remain = deadline - System.currentTimeMillis();
                                if (remain <= 1000) break;
                                List<Callable<String>> tasks = new ArrayList<>();
                                for (Cms.Video v : batch) {
                                    final String title = v.title;
                                    tasks.add(
                                            () ->
                                                    AwwratedHome.checkOne(
                                                            eng, skey, title));
                                }
                                Map<String, String> roundRegions = new HashMap<>();
                                try {
                                    List<Future<String>> futures =
                                            pool.invokeAll(
                                                    tasks, remain, TimeUnit.MILLISECONDS);
                                    for (int i = 0; i < batch.size(); i++) {
                                        Cms.Video v = batch.get(i);
                                        String region = AwwratedHome.REGION_UNKNOWN;
                                        try {
                                            Future<String> f = futures.get(i);
                                            if (f.isDone() && !f.isCancelled()) {
                                                region = f.get();
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                        if (region == null) {
                                            region = AwwratedHome.REGION_UNKNOWN;
                                        }
                                        roundRegions.put(v.title, region);
                                    }
                                } catch (Throwable ignored) {
                                    for (Cms.Video v : batch) {
                                        roundRegions.put(v.title, AwwratedHome.REGION_UNKNOWN);
                                    }
                                }
                                AwwratedHome.recordChecked(filesDir, skey, roundRegions);
                                List<Cms.Video> stillPending = new ArrayList<>();
                                for (Cms.Video v : toCheck) {
                                    String region = roundRegions.get(v.title);
                                    if (AwwratedHome.isAnyRegion(region)) {
                                        String id =
                                                AwwratedHome.detailIdFor(
                                                        filesDir, skey, v.title);
                                        if (id != null && !id.isEmpty()) v.id = id;
                                        v.sourceApi = skey;
                                        // mkoai 2.0.46: Phase B live
                                        // confirmation runs the same poster
                                        // fallback chain as phase 1
                                        // (TMDB -> cached source -> backdrop).
                                        String resolved =
                                                AwwratedHome.resolvePoster(
                                                        filesDir,
                                                        v.poster,
                                                        skey,
                                                        v.title,
                                                        v.backdrop);
                                        if (resolved != null && !resolved.isEmpty()) {
                                            v.poster = resolved;
                                        }
                                        for (TmdbRow row : rows) {
                                            if (row.pending.remove(v)) {
                                                row.confirmed.add(v);
                                                break;
                                            }
                                        }
                                    } else {
                                        // Not in this source — try the next one.
                                        stillPending.add(v);
                                    }
                                }
                                toCheck = stillPending;
                                a.ui.post(() -> renderTmdbRows(site, req, gen));
                            }
                            // mkoai 2.0.46: poster top-up (fallback chain
                            // step (c)) — confirmed titles whose card
                            // still has no poster get one live fetch from
                            // their confirming source, then one re-render.
                            // Bounded by the remaining deadline;
                            // best-effort, never throws.
                            try {
                                List<Cms.Video> needPoster = new ArrayList<>();
                                for (TmdbRow row : rows) {
                                    for (Cms.Video v : row.confirmed) {
                                        if ((v.poster == null || v.poster.isEmpty())
                                                && v.sourceApi != null
                                                && !v.sourceApi.isEmpty()) {
                                            needPoster.add(v);
                                        }
                                    }
                                }
                                long remain = deadline - System.currentTimeMillis();
                                if (!needPoster.isEmpty() && remain > 1000) {
                                    List<Callable<String>> pTasks = new ArrayList<>();
                                    for (Cms.Video v : needPoster) {
                                        final String pKey = v.sourceApi;
                                        final String pTitle = v.title;
                                        SourceEngine peng = null;
                                        for (int ei = 0; ei < engKeys.size(); ei++) {
                                            if (pKey.equals(engKeys.get(ei))) {
                                                peng = engs.get(ei);
                                                break;
                                            }
                                        }
                                        final SourceEngine pengF = peng;
                                        pTasks.add(
                                                () ->
                                                        AwwratedHome.fetchPosterLive(
                                                                pengF, pKey, pTitle));
                                    }
                                    List<Future<String>> pFutures =
                                            pool.invokeAll(pTasks, remain, TimeUnit.MILLISECONDS);
                                    boolean changed = false;
                                    for (int i = 0; i < needPoster.size(); i++) {
                                        try {
                                            Future<String> f = pFutures.get(i);
                                            if (f.isDone() && !f.isCancelled()) {
                                                String p = f.get();
                                                if (p != null && !p.isEmpty()) {
                                                    needPoster.get(i).poster = p;
                                                    // mkoai 2.0.48: persist
                                                    // it — the in-memory
                                                    // Video alone does not
                                                    // survive the next cold
                                                    // start (2.0.46/2.0.47
                                                    // re-fetched live every
                                                    // launch).
                                                    AwwratedHome.recordPoster(
                                                            filesDir,
                                                            needPoster.get(i).sourceApi,
                                                            needPoster.get(i).title,
                                                            p);
                                                    changed = true;
                                                }
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    if (changed) {
                                        a.ui.post(() -> renderTmdbRows(site, req, gen));
                                    }
                                }
                            } catch (Throwable ignored) {
                                // Poster top-up is best-effort only.
                            }
                        } finally {
                            pool.shutdownNow();
                        }
                    } catch (Throwable e) {
                        AppLog.w("MobileShell", "tmdb rows failed", e);
                    } finally {
                        tmdbRowsLoading = false;
                        // mkoai 2.0.51: the checks have settled — if the
                        // home is still empty everywhere, the empty copy
                        // may now honestly appear (until then it stayed
                        // on the loading line).
                        if (req == homeRequest && gen == a.generation && !a.isFinishing()) {
                            a.ui.post(() -> refreshHomeEmptyState());
                        }
                    }
                });
    }

    /** Rebuild the "我的媒體" cards + TMDB rows from current row state (UI thread). */
    private void renderTmdbRows(Cms.Site site, int req, int gen) {
        if (req != homeRequest || gen != a.generation || a.isFinishing()) return;
        if (tmdbRowsContainerRef == null || mediaCardsContainerRef == null || site == null) {
            return;
        }
        // "我的媒體" (Terminus+ style): two big cards, shown only when
        // their list has at least one confirmed title.
        mediaCardsContainerRef.removeAllViews();
        TmdbRow hotMovie = findTmdbRow("trending_movie");
        TmdbRow hotTv = findTmdbRow("trending_tv");
        boolean anyCard =
                (hotMovie != null && !hotMovie.display().isEmpty())
                        || (hotTv != null && !hotTv.display().isEmpty());
        if (anyCard) {
            TextView mediaTitle = a.text("我的媒体", 16, MainActivity.WHITE);
            mediaTitle.setTypeface(null, Typeface.BOLD);
            // mkoai 2.0.66: no shadow (see top10Title in renderHomeSections).
            mediaTitle.setPadding(0, a.dp(14), 0, a.dp(8));
            registerHomeText(mediaTitle, HOME_TEXT_PRIMARY);
            mediaCardsContainerRef.addView(mediaTitle);
            LinearLayout cardsRow = a.row();
            cardsRow.setGravity(Gravity.TOP);
            if (hotMovie != null && !hotMovie.display().isEmpty()) {
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(0, -2, 1f);
                lp.rightMargin = a.dp(6);
                cardsRow.addView(buildMediaCard(site, "热门电影", "Hot Movies", hotMovie), lp);
            }
            if (hotTv != null && !hotTv.display().isEmpty()) {
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(0, -2, 1f);
                lp.leftMargin = a.dp(6);
                cardsRow.addView(buildMediaCard(site, "热门剧集", "Hot TV Series", hotTv), lp);
            }
            mediaCardsContainerRef.addView(cardsRow, new LinearLayout.LayoutParams(-1, -2));
        }
        // Category rows: a row shows its confirmed titles, or its
        // pending titles while checks are running / when nothing was
        // confirmed (2.0.51 fail-open display). Rendered in small
        // batches (2 rows per frame) so a warm launch with many rows
        // never blocks the main thread.
        tmdbRowsContainerRef.removeAllViews();
        final List<TmdbRow> visible = new ArrayList<>();
        for (TmdbRow row : tmdbRows) {
            if (!row.display().isEmpty()) visible.add(row);
        }
        final int epoch = ++tmdbRowsRenderEpoch;
        renderTmdbRowBatch(site, req, gen, epoch, visible, 0);
        // 2.0.41: if a TMDB list page is open for one of these rows, refresh
        // it in place with the newly confirmed titles.
        refreshOpenTmdbList();
    }

    /** One batch of TMDB row sections (UI thread, 4 rows per frame). */
    private void renderTmdbRowBatch(
            final Cms.Site site,
            final int req,
            final int gen,
            final int epoch,
            final List<TmdbRow> visible,
            final int start) {
        if (req != homeRequest
                || gen != a.generation
                || epoch != tmdbRowsRenderEpoch
                || a.isFinishing()
                || tmdbRowsContainerRef == null) return;
        int end = Math.min(start + 2, visible.size());
        for (int i = start; i < end; i++) {
            tmdbRowsContainerRef.addView(buildTmdbRowSection(site, visible.get(i)));
        }
        if (end < visible.size()) {
            tmdbRowsContainerRef.postOnAnimation(() -> renderTmdbRowBatch(site, req, gen, epoch, visible, end));
        }
    }

    /** One big "我的媒體" card (Terminus+ style): TMDB poster as background, title overlaid. */
    private LinearLayout buildMediaCard(
            Cms.Site site, String label, String subLabel, TmdbRow row) {
        String bg = null;
        for (Cms.Video v : row.display()) {
            if (v.poster != null && !v.poster.isEmpty()) {
                bg = v.poster;
                break;
            }
        }
        LinearLayout card = a.vertical();
        MobileDetailScreen.bindTouchFeedback(card);
        card.setOnClickListener(view -> openTmdbList(site, label, row));
        FrameLayout art = new FrameLayout(a);
        art.setBackground(glassBg(0xff1c2421, 0xff141a18, a.dp(12), 0, 0));
        art.setClipToOutline(true);
        art.setElevation(a.dp(2)); // mkoai 2.0.64: faint shadow edges the card - the 0.6dp glass stroke above is gone (it read as a pasted-on rectangle).
        if (bg != null) {
            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(img, new FrameLayout.LayoutParams(-1, -1));
            a.poster(bg, img);
        }
        // Bottom scrim + labels.
        FrameLayout scrim = new FrameLayout(a);
        scrim.setPadding(a.dp(10), a.dp(20), a.dp(10), a.dp(8));
        scrim.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0x00000000, 0xd8000000}));
        LinearLayout labels = a.vertical();
        TextView sub = a.text(subLabel, 11, 0xffc8ccd2);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(sub);
        TextView name = a.text(label, 16, 0xffffffff);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        scrim.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        art.addView(scrim, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(108)));
        return card;
    }

    /** One TMDB category row: title + 「查看全部」 + horizontal poster cards. */
    private LinearLayout buildTmdbRowSection(Cms.Site site, TmdbRow row) {
        LinearLayout section = a.vertical();
        LinearLayout header = a.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, a.dp(14), 0, a.dp(8));
        TextView title = a.text(row.title, 16, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        // mkoai 2.0.66: no shadow — the title sits on the page tone now
        // (see top10Title in renderHomeSections).
        registerHomeText(title, HOME_TEXT_PRIMARY);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView all = a.text("查看全部 >", 13, HomeRows2061.VIEW_ALL_COLOR);
        all.setPadding(a.dp(8), a.dp(4), a.dp(2), a.dp(4));
        registerHomeText(all, HOME_TEXT_MUTED);
        MobileDetailScreen.bindTouchFeedback(all);
        all.setOnClickListener(view -> openTmdbList(site, row.title, row));
        header.addView(all);
        section.addView(header);
        HorizontalScrollView rowScroll = new HorizontalScrollView(a);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(0, 0, 0, a.dp(6));
        LinearLayout cards = a.row();
        cards.setGravity(Gravity.TOP);
        rowScroll.addView(cards);
        int cardWidthDp = 128;
        int posterHeightDp = Math.round(cardWidthDp * 1.48f);
        List<Cms.Video> shownVideos = row.display();
        int shown = Math.min(TMDB_ROW_SHOW_ITEMS, shownVideos.size());
        for (int i = 0; i < shown; i++) {
            Cms.Video v = shownVideos.get(i);
            LinearLayout card = buildHomePosterCard(site, v, cardWidthDp, posterHeightDp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(cardWidthDp), -2);
            lp.rightMargin = a.dp(10);
            cards.addView(card, lp);
        }
        section.addView(rowScroll, new LinearLayout.LayoutParams(-1, -2));
        return section;
    }

    /** Open a full TMDB list page (strict-filtered titles only). */
    private void openTmdbList(Cms.Site site, String title, TmdbRow row) {
        // 2.0.41: the page reads the LIVE row, not a tap-time snapshot —
        // titles confirmed by the background cross-source check appear
        // as they arrive (refreshOpenTmdbList). Every (re)render
        // re-registers the row, so returning to the page refreshes it too.
        final TmdbRow liveRow = row;
        final MainActivity.Screen[] holder = new MainActivity.Screen[1];
        holder[0] =
                new MainActivity.Screen(
                        () -> {
                            openListRow = liveRow;
                            openListSite = site;
                            openListScreen = holder[0];
                            // mkoai 2.0.67: capture the home tone NOW —
                            // the list page opens with the colour the
                            // home shows at this moment.
                            openListTone =
                                    a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)
                                            ? MainActivity.DARK
                                            : heroDiffusionShown;
                            showTmdbListPage(a, site, title, liveRow);
                        });
        a.open(holder[0], true);
    }

    /**
     * Copy of a row's display titles (confirmed, else pending fail-open);
     * retries once if a background Phase B add races the copy.
     */
    private static List<Cms.Video> snapshotDisplay(TmdbRow row) {
        if (row == null) return new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new ArrayList<>(row.confirmed);
            } catch (Throwable ignored) {
                // A background add raced the copy; retry.
            }
        }
        return new ArrayList<>();
    }

    /**
     * 2.0.41: refresh the open TMDB list page in place when the background
     * cross-source check confirms more titles for its row. Only the grid
     * children + count text are rebuilt, so the outer ScrollView keeps its
     * scroll position. Runs on the UI thread from renderTmdbRows (after
     * every Phase B round).
     */
    private void refreshOpenTmdbList() {
        if (openListRow == null || openListScreen == null) return;
        if (a.current != openListScreen) {
            // User left the list page (detail page, back, another tab).
            openListRow = null;
            openListSite = null;
            openListScreen = null;
            return;
        }
        List<Cms.Video> now = snapshotDisplay(openListRow);
        if (now.size() == openListShown) return;
        View gridView = a.root.findViewWithTag("tmdb-list-grid");
        if (!(gridView instanceof LinearLayout)) {
            openListRow = null;
            openListSite = null;
            openListScreen = null;
            return;
        }
        LinearLayout grid = (LinearLayout) gridView;
        grid.removeAllViews();
        fillTmdbListGrid(a, openListSite, grid, now, openListTone);
        openListShown = now.size();
    }

    /** Full list page for a TMDB row / media card (renderPage scaffold + 4-col grid). */
    private void showTmdbListPage(
            MainActivity a, Cms.Site site, String title, TmdbRow row) {
        a.renderPage(title, "TMDB · " + (site == null ? "" : site.name));
        // mkoai 2.0.67: the view-all page takes the home tone it was
        // opened with (openListTone). renderPage paints the root DARK
        // and an opaque frosted Atmosphere over it, so the tone goes
        // on the scroll too — the scroll is added after the
        // Atmosphere and fills the viewport, so its opaque background
        // is the page background the user sees. On a light tone the
        // heading / eyebrow flip to the dark text set (HomeTone2066);
        // on a dark tone every colour below stays exactly today's
        // dark value. (mkoai 2.0.68: the count line is gone — see
        // below — so there is no count text to re-tint.)
        a.root.setBackgroundColor(openListTone);
        a.scroll.setBackgroundColor(openListTone);
        if (HomeTone2066.isLight(openListTone)) {
            View eye = a.root.findViewWithTag("page-eyebrow");
            if (eye instanceof TextView) {
                ((TextView) eye).setTextColor(HomeTone2066.mutedText(openListTone));
            }
            View head = a.root.findViewWithTag("page-heading");
            if (head instanceof TextView) {
                ((TextView) head).setTextColor(HomeTone2066.primaryText(openListTone));
            }
        }
        CatalogScreen.addBack(a, "tmdb-list-back");
        List<Cms.Video> videos = snapshotDisplay(row);
        if (videos == null || videos.isEmpty()) {
            TextView empty =
                    a.text(
                            "目前沒有可播放的片。",
                            16,
                            HomeTone2066.isLight(openListTone)
                                    ? HomeTone2066.mutedText(openListTone)
                                    : MainActivity.MUTED);
            empty.setLineSpacing(a.dp(5), 1);
            a.body.addView(empty);
            a.gap(a.body, 14);
            openListShown = 0;
            return;
        }
        // mkoai 2.0.68: the count line is deleted — the view itself
        // is gone, not hidden. It lived only on this page (created
        // here and updated in refreshOpenTmdbList); no other page
        // shared it, so nothing else changes.
        LinearLayout listGrid = a.vertical();
        listGrid.setTag("tmdb-list-grid");
        a.body.addView(listGrid, new LinearLayout.LayoutParams(-1, -2));
        fillTmdbListGrid(a, site, listGrid, videos, openListTone);
        openListShown = videos.size();
    }

    /** Fill a 4-column grid with TMDB list-page cards (initial render + live refresh share it).
     * mkoai 2.0.68: 3 -> 4 columns, card padding 5 -> 3dp, margins
     * (5,5,14,19) -> (3,3,6,12), art height 190 -> 140dp — the grid
     * reads visibly denser (POCO F5: ~13 cards per viewport instead
     * of ~8). The art box stays ~2:3 (92.25x140dp, ratio 0.659), so
     * the poster still fills with ~1% crop; the title stays 13sp
     * bold, max 2 lines. Phone only — the TV grid is a separate
     * builder in HomeScreen and is untouched. */
    private void fillTmdbListGrid(
            MainActivity a,
            Cms.Site site,
            LinearLayout listGrid,
            List<Cms.Video> videos,
            int tone) {
        LinearLayout cards = null;
        for (int i = 0; i < videos.size(); i++) {
            if (i % 4 == 0) {
                cards = a.row();
                cards.setGravity(Gravity.TOP);
                cards.setClipChildren(false);
                cards.setClipToPadding(false);
                listGrid.addView(cards);
            }
            Cms.Video v = videos.get(i);
            LinearLayout card = a.vertical();
            card.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(8));
            card.setClipChildren(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(a.dp(3), a.dp(3), a.dp(6), a.dp(12));
            MobileDetailScreen.bindTouchFeedback(card);
            card.setOnClickListener(view -> a.openHomeCard(site, v));
            FrameLayout art = new FrameLayout(a);
            art.setBackground(
                    glassBg(0xff1c2421, 0xff141a18, a.dp(12), 0, 0));
            art.setClipToOutline(true);
            art.setElevation(a.dp(2)); // mkoai 2.0.64: faint shadow edges the card - the 0.6dp glass stroke above is gone (it read as a pasted-on rectangle).
            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(img, new FrameLayout.LayoutParams(-1, -1));
            bindReliablePoster(img, v);
            // mkoai 2.0.63: the poster overlay keeps only the remarks
            // badge (same as the home card) — the year used to sit here
            // AND in the subtitle below, showing twice.
            addPosterOverlay(a, art, v.remarks, null);
            card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(140)));
            // mkoai 2.0.67: the card text follows the page tone — dark
            // text on a light page, exactly today's light values on a
            // dark page.
            TextView name =
                    a.text(
                            v.title,
                            13,
                            HomeTone2066.isLight(tone)
                                    ? HomeTone2066.primaryText(tone)
                                    : 0xfff5f7fa);
            name.setTypeface(null, Typeface.BOLD);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(a.dp(1), a.dp(7), a.dp(1), 0);
            card.addView(name);
            // mkoai 2.0.63: the subtitle keeps only the year line (same
            // as the home card) — the remarks now live only on the
            // poster overlay, so year + rating are no longer duplicated.
            String sub =
                    HomeRows2061.yearLine(
                            v,
                            java.util.Calendar.getInstance()
                                    .get(java.util.Calendar.YEAR));
            if (!sub.isEmpty()) {
                TextView subTv =
                        a.text(
                                sub,
                                11,
                                HomeTone2066.isLight(tone)
                                        ? HomeTone2066.mutedText(tone)
                                        : 0xff8e959e);
                subTv.setSingleLine(true);
                subTv.setEllipsize(TextUtils.TruncateAt.END);
                subTv.setPadding(a.dp(1), a.dp(2), a.dp(1), 0);
                card.addView(subTv);
            }
            cards.addView(card, lp);
        }
    }

    /**
     * 2.0.41: no-op — the home background is a static frosted matte now,
     * not poster diffusion, so nothing follows the top section's poster
     * anymore. Kept so the scroll listener's call site stays valid.
     */
    private void followTopSectionPoster() {
        return;
    }

    // 2.0.40: poster bottom overlay shared by every card builder — the
    // year at the bottom-left and the remarks/rating badge at the
    // bottom-right, on the poster's bottom gradient (a small scrim is
    // added when the card has no gradient of its own). The year is
    // 10sp bold, the same size as the rating badge, in light text on
    // the dark scrim. No year anywhere -> no year label (never a fake
    // value or "未知").
    //
    // Returns the year TextView (GONE when there is no year yet) so
    // callers whose entries carry no year (history, favorites) can fill
    // it later from TMDB via enrichYearLabel. The scrim itself is GONE
    // when there is neither a year nor remarks, and enrichYearLabel
    // reveals it when a year arrives.
    static TextView addPosterOverlay(
            MainActivity a, FrameLayout art, String remarks, String year) {
        return addPosterOverlay(a, art, remarks, year, "");
    }

    /**
     * mkoai 2.0.66: the 5-arg form renders the 「電影」/「劇」 type label
     * as the bottom overlay row's first element — bottom-left, white
     * bold text with a shadow on the same bottom scrim as the
     * bottom-right ★ rating, no pill, no background — so the two sides
     * balance (left: type [+ year on TV], right: rating). The 4-arg
     * form (other cards) passes "" and renders exactly today's row.
     */
    static TextView addPosterOverlay(
            MainActivity a, FrameLayout art, String remarks, String year, String typeLabel) {
        String y = Cms.yearLabel(year);
        boolean hasRemarks = remarks != null && !remarks.trim().isEmpty();
        boolean hasType = typeLabel != null && !typeLabel.trim().isEmpty();
        FrameLayout bottomScrim = new FrameLayout(a);
        bottomScrim.setPadding(a.dp(7), a.dp(16), a.dp(7), a.dp(5));
        bottomScrim.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0x00000000, 0xc8000000}));
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (hasType) {
            TextView type = a.text(typeLabel.trim(), 10, 0xfff5f7fa);
            type.setTypeface(null, Typeface.BOLD);
            type.setSingleLine(true);
            type.setShadowLayer(a.dp(2), 0, a.dp(1), 0xcc000000);
            row.addView(type, new LinearLayout.LayoutParams(-2, -2));
        }
        TextView yearTv = a.text(y, 10, 0xfff5f7fa);
        yearTv.setTypeface(null, Typeface.BOLD);
        yearTv.setSingleLine(true);
        if (y.isEmpty()) yearTv.setVisibility(View.GONE);
        LinearLayout.LayoutParams yearLp = new LinearLayout.LayoutParams(-2, -2);
        if (hasType && !y.isEmpty()) yearLp.leftMargin = a.dp(6);
        row.addView(yearTv, yearLp);
        if (hasRemarks) {
            TextView badge = a.text(remarks.trim(), 10, 0xfff5f7fa);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setSingleLine(true);
            badge.setEllipsize(TextUtils.TruncateAt.END);
            badge.setGravity(Gravity.END);
            LinearLayout.LayoutParams badgeLp =
                    new LinearLayout.LayoutParams(0, -2, 1f);
            if (!y.isEmpty() || hasType) badgeLp.leftMargin = a.dp(6);
            row.addView(badge, badgeLp);
        }
        bottomScrim.addView(row, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (y.isEmpty() && !hasRemarks && !hasType) bottomScrim.setVisibility(View.GONE);
        art.addView(bottomScrim, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return yearTv;
    }

    /**
     * 2.0.40: fill a year label from TMDB for entries that carry no year
     * (history / favorites). Same pattern as maybeEnrichPoster: a
     * background lookup (disk-cached, so repeats are free), guarded by
     * the view tag and the activity generation so a recycled view never
     * shows another title's year. Reveals the label and its scrim when
     * a year is found; leaves everything hidden when TMDB has no match.
     */
    static void enrichYearLabel(final MainActivity a, final TextView yearTv, final String title) {
        if (yearTv == null || title == null || title.isEmpty()) return;
        yearTv.setTag("tmdb-year:" + title);
        final int gen = a.generation;
        a.submitPage(
                () -> {
                    try {
                        TmdbClient.Item hit =
                                TmdbClient.search(a.getFilesDir(), title, null);
                        final String y = hit == null ? "" : Cms.yearLabel(hit.year);
                        if (y.isEmpty()) return;
                        a.ui.post(
                                () -> {
                                    if (gen != a.generation || a.isFinishing()) return;
                                    if (!("tmdb-year:" + title).equals(yearTv.getTag())) return;
                                    yearTv.setText(y);
                                    yearTv.setVisibility(View.VISIBLE);
                                    // Reveal the scrim row holding this label:
                                    // yearTv -> row (LinearLayout) -> scrim
                                    // (FrameLayout). Only touch it when it is
                                    // currently GONE so other layouts that
                                    // reuse this helper are left alone.
                                    android.view.ViewParent rowP = yearTv.getParent();
                                    if (rowP instanceof LinearLayout) {
                                        android.view.ViewParent scrimP =
                                                ((LinearLayout) rowP).getParent();
                                        if (scrimP instanceof FrameLayout
                                                && ((FrameLayout) scrimP).getVisibility()
                                                        == View.GONE) {
                                            ((FrameLayout) scrimP).setVisibility(View.VISIBLE);
                                        }
                                    }
                                });
                    } catch (Throwable ignored) {
                    }
                });
    }

    // mkoai: one poster card for a home section row (same look as the grid
    // card, fixed width for horizontal scrolling).
    private LinearLayout buildHomePosterCard(
            Cms.Site site, Cms.Video v, int cardWidthDp, int posterHeightDp) {
        LinearLayout card = a.vertical();
        MobileDetailScreen.bindTouchFeedback(card);
        card.setOnClickListener(view -> a.openHomeCard(site, v));
        FrameLayout art = new FrameLayout(a);
        art.setBackground(glassBg(0xff1c2421, 0xff141a18, a.dp(12), 0, 0));
        art.setClipToOutline(true);
        art.setElevation(a.dp(2)); // mkoai 2.0.64: faint shadow edges the card - the 0.6dp glass stroke above is gone (it read as a pasted-on rectangle).
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(img, new FrameLayout.LayoutParams(-1, -1));
        bindReliablePoster(img, v);
        // mkoai 2.0.61: the year moves off the poster (only the remarks
        // badge stays on it) and under the title instead (reference app).
        // mkoai 2.0.66: the 「電影」/「劇」 label is the bottom overlay
        // row's left element now (bottom-left, white bold + shadow, no
        // pill — the top-left dark chip is gone).
        addPosterOverlay(a, art, v.remarks, null, VideoKind2061.badgeLabel(v));
        card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(posterHeightDp)));
        TextView title = a.text(v.title, 13, 0xfff5f7fa);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(a.dp(3), 0, a.dp(1), 0xcc000000);
        title.setPadding(a.dp(1), a.dp(7), a.dp(1), 0);
        card.addView(title);
        // Year line under the title: "2026" or "2026 - 现在" for an
        // airing TV show. Hidden when there is no year.
        String yearLine =
                HomeRows2061.yearLine(
                        v, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
        if (!yearLine.isEmpty()) {
            TextView year = a.text(yearLine, 11, HomeRows2061.YEAR_TEXT_COLOR);
            year.setSingleLine(true);
            year.setEllipsize(TextUtils.TruncateAt.END);
            year.setPadding(a.dp(1), a.dp(2), a.dp(1), 0);
            // mkoai 2.0.66: the year line sits on the page tone under the
            // title (not on the poster), so it follows the tone. The
            // card title above it stays white + shadow on the poster.
            registerHomeText(year, HOME_TEXT_MUTED);
            card.addView(year);
        }
        return card;
    }

    /**
     * mkoai 2.0.38: when a card has no poster of its own, fill it from
     * TMDB (zh-TW, Chinese poster preferred). Runs on a background
     * thread; the disk cache makes repeats free. The ImageView tag is
     * the title, so a recycled view never shows another title's poster.
     * mkoai 2.0.46: full fallback chain — (b) cached confirming-source
     * poster first (no network), then (d) TMDB poster, else TMDB
     * backdrop. Step (c), the live source fetch, runs at the data level
     * (Phase B top-up / checkTitle) so this view-level path stays cheap
     * and never needs a source engine.
     * mkoai 2.0.47: (c) now ALSO runs here, as the guarantee for the
     * cold first paint. Phase 2 fills the poster at the data level but
     * the home only re-renders when titles are removed, so a card built
     * before Phase 2 finishes (no sourceApi yet, TMDB posterless, e.g.
     * 《艾倫的宇宙》) stayed blank forever on that visit even though the
     * poster was already known. Now a poster-less card gets one live
     * fetch from its confirming source — or, when it has none yet, from
     * the first priority source （巨量， the source most likely to carry
     * the title) — before falling back to TMDB. Bounded to one attempt
     * per (source, title) per session; failures are remembered so a
     * dead source is not retried on every re-render.
     */
    private void bindReliablePoster(final ImageView img, final Cms.Video v) {
        img.setImageDrawable(new PosterPlaceholder(v.title));
        img.setContentDescription(v.title + " 封面");
        if (v.poster == null || v.poster.isEmpty() || ImageLoader.failed(v.poster)) {
            maybeEnrichPoster(img, v);
            return;
        }
        ImageLoader.onFailure(img, () -> maybeEnrichPoster(img, v));
        a.poster(v.poster, img);
    }

    private final java.util.Map<String, java.util.concurrent.Future<?>> enrichingPosters = new java.util.HashMap<>();
    private void maybeEnrichPoster(final ImageView img, final Cms.Video v) {
        if (img == null || v == null || v.title == null || v.title.isEmpty()) return;
        final String title = v.title;
        final String year = v.year;
        final String sourceApi = v.sourceApi;
        img.setTag("tmdb-poster:" + title);
        registerPosterWaiter(title, img);
        final String enrichKey = title + "\n" + sourceApi;
        java.util.concurrent.Future<?> ongoing = enrichingPosters.get(enrichKey);
        if (ongoing != null && !ongoing.isDone() && !ongoing.isCancelled()) return;
        // mkoai 2.0.48: runs on the artwork pool, NOT submitPage. A
        // navigation (cancelPageRequests + generation++) must never
        // kill a poster fetch, and delivery below is generation-free
        // (tag-only), so a poster that lands after a navigation still
        // reaches the card that is on screen now. 2.0.47 submitted
        // these on the page pool, so any tab switch / detail visit
        // cancelled every pending fetch and the generation guard then
        // dropped the completions — while resume() re-attached the
        // retained home without ever re-running enrichment.
        java.util.concurrent.Future<?> job = RequestScope.submit(
                a.artwork,
                () -> {
                    try {
                        // (b) cached source poster — no network.
                        if (sourceApi != null && !sourceApi.isEmpty()) {
                            String cached =
                                    AwwratedHome.posterFor(
                                            a.getFilesDir(), sourceApi, title);
                            if (cached != null && !cached.isEmpty() && !ImageLoader.failed(cached)) {
                                deliverPoster(v, title, cached);
                                return;
                            }
                        }
                        // (c) live source poster — one live fetch from
                        // the confirming source, or the first priority
                        // source when the card has no confirming source
                        // yet (cold first paint). Only a completed
                        // search with no match is remembered as absent;
                        // a transient failure is retried on the next
                        // card build (2.0.47 poisoned it for the
                        // whole process).
                        String liveApi = sourceApi;
                        if ((liveApi == null || liveApi.isEmpty())) {
                            // 2.0.61 (k): the first priority source's api
                            // (巨量 when present in the config).
                            liveApi = SourcePreferences.firstPriorityApi(a);
                        }
                        if (liveApi != null && !liveApi.isEmpty()) {
                            String missKey = liveApi + "\n" + title;
                            if (!posterAbsent.contains(missKey)) {
                                AwwratedHome.PosterResult live =
                                        fetchSourcePosterLiveResult(liveApi, title);
                                if (live != null
                                        && live.poster != null
                                        && !live.poster.isEmpty() && !ImageLoader.failed(live.poster)) {
                                    deliverPoster(v, title, live.poster);
                                    return;
                                }
                                if (live != null && live.absent) {
                                    posterAbsent.add(missKey);
                                }
                            }
                        }
                        // (d1) TMDB poster, else (d2) Douban poster,
                        // else (d3) TMDB backdrop — all from one TMDB
                        // search plus one short Douban suggest call.
                        TmdbClient.Item hit =
                                TmdbClient.search(a.getFilesDir(), title, year);
                        if (hit != null
                                && hit.poster != null
                                && !hit.poster.isEmpty() && !ImageLoader.failed(hit.poster)) {
                            deliverPoster(v, title, hit.poster);
                            return;
                        }
                        String douban = AwwratedHome.fetchDoubanPoster(title, year);
                        if (douban != null && !douban.isEmpty() && !ImageLoader.failed(douban)) {
                            deliverPoster(v, title, douban);
                            return;
                        }
                        if (hit != null
                                && hit.backdrop != null
                                && !hit.backdrop.isEmpty()) {
                            deliverPoster(v, title, hit.backdrop);
                        }
                    } catch (Throwable ignored) {
                    }
                });
        enrichingPosters.put(enrichKey, job);
    }

    /**
     * mkoai 2.0.48: live poster fetch for one title from one source api
     * (never throws), with the absent-vs-transient outcome. Finds the
     * configured site by api, builds its engine, and runs
     * AwwratedHome.fetchPosterLiveResult (Simplified-first title match,
     * so a Traditional card title still finds the Simplified source
     * entry). A hit also fills the poster cache, so later cards for
     * the same title are served by step (b) for free.
     */
    private AwwratedHome.PosterResult fetchSourcePosterLiveResult(
            String sourceApi, String title) {
        try {
            if (a == null || a.config == null || a.config.sites == null) {
                return new AwwratedHome.PosterResult(null, false);
            }
            Cms.Site site = null;
            for (Cms.Site s : a.config.sites) {
                if (s != null && s.api != null && sameApi(s.api, sourceApi)) {
                    site = s;
                    break;
                }
            }
            if (site == null) return new AwwratedHome.PosterResult(null, false);
            SourceEngine eng = a.engine(site);
            if (eng == null) return new AwwratedHome.PosterResult(null, false);
            return AwwratedHome.fetchPosterLiveResult(eng, sourceApi, title);
        } catch (Throwable ignored) {
            return new AwwratedHome.PosterResult(null, false);
        }
    }

    /** Api equality ignoring a trailing slash. */
    private static boolean sameApi(String x, String y) {
        if (x == null || y == null) return false;
        String nx = x.endsWith("/") ? x.substring(0, x.length() - 1) : x;
        String ny = y.endsWith("/") ? y.substring(0, y.length() - 1) : y;
        return nx.equals(ny);
    }

    /** Back-compat wrapper for cards without a Video (history entries). */
    private void maybeEnrichPoster(final ImageView img, final String title, final String year) {
        if (img == null || title == null || title.isEmpty()) return;
        Cms.Video v = new Cms.Video();
        v.title = title;
        v.year = year;
        maybeEnrichPoster(img, v);
    }

    /** Register a poster-less card's ImageView as a delivery target for its title. */
    private static void registerPosterWaiter(String title, ImageView img) {
        try {
            java.util.Set<java.lang.ref.WeakReference<ImageView>> set = posterWaiters.get(title);
            if (set == null) {
                set = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
                java.util.Set<java.lang.ref.WeakReference<ImageView>> prev =
                        posterWaiters.putIfAbsent(title, set);
                if (prev != null) set = prev;
            }
            synchronized (set) {
                java.util.Iterator<java.lang.ref.WeakReference<ImageView>> it = set.iterator();
                while (it.hasNext()) {
                    ImageView cur = it.next().get();
                    if (cur == null || cur == img) it.remove();
                }
                set.add(new java.lang.ref.WeakReference<>(img));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * mkoai 2.0.48: a resolved poster reaches the card three ways at
     * once — (1) written onto the Video, so any later re-render
     * paints it synchronously from v.poster; (2) remembered in the
     * session poster cache and persisted to the disk availability
     * file ("po"), so the next cold start finds it without a live
     * fetch; (3) posted to every live ImageView currently showing
     * this title (tag match), on the UI thread. No generation check:
     * posting a poster is idempotent and the tag already binds the
     * view to the title — a generation bump from a navigation must
     * not drop it (2.0.47's gen guard did exactly that).
     */
    private void deliverPoster(final Cms.Video v, final String title, final String poster) {
        if (poster == null || poster.isEmpty() || ImageLoader.failed(poster)) return;
        if (v != null) v.poster = poster;
        if (v != null && v.sourceApi != null && !v.sourceApi.isEmpty()) {
            AwwratedHome.recordPoster(a.getFilesDir(), v.sourceApi, title, poster);
        }
        a.ui.post(
                () -> {
                    if (a.isFinishing()) return;
                    java.util.Set<java.lang.ref.WeakReference<ImageView>> waiters =
                            posterWaiters.get(title);
                    if (waiters == null) return;
                    java.util.List<java.lang.ref.WeakReference<ImageView>> copy;
                    synchronized (waiters) {
                        copy = new java.util.ArrayList<>(waiters);
                    }
                    for (java.lang.ref.WeakReference<ImageView> ref : copy) {
                        ImageView img = ref == null ? null : ref.get();
                        if (img == null) {
                            synchronized (waiters) {
                                waiters.remove(ref);
                            }
                            continue;
                        }
                        if (!("tmdb-poster:" + title).equals(img.getTag())) continue;
                        if (v != null) ImageLoader.onFailure(img, () -> maybeEnrichPoster(img, v));
                        a.poster(poster, img);
                    }
                });
    }

    private void renderVideoGrid(LinearLayout container, Cms.Site site, List<Cms.Video> videos) {
        container.removeAllViews();
        float widthDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int baseCols = widthDp >= 720 ? 5 : (widthDp >= 520 ? 4 : 3);
        int densityPref = a.prefs.getInt(Prefs.MOBILE_POSTER_DENSITY, 1);
        int cols =
                densityPref == 0
                        ? Math.max(2, baseCols - 1)
                        : (densityPref == 2 ? Math.min(6, baseCols + 1) : baseCols);
        int subtitleMode = a.prefs.getInt(Prefs.MOBILE_CARD_SUBTITLE, 0);
        int horizontalPaddingDp = widthDp >= 640 && !a.isPortrait() ? 116 : 32;
        int cardWidthDp =
                Math.max(76, (int) ((widthDp - horizontalPaddingDp - (cols - 1) * 12) / cols));
        int posterHeightDp = Math.round(cardWidthDp * 1.48f);

        LinearLayout currentRow = null;
        for (int i = 0; i < videos.size(); i++) {
            if (i % cols == 0) {
                currentRow = a.row();
                currentRow.setGravity(Gravity.TOP);
                container.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
            }
            final Cms.Video v = videos.get(i);
            LinearLayout card = a.vertical();
            card.setTag(i == 0 ? "primary" : "video:" + v.id);
            MobileDetailScreen.bindTouchFeedback(card);
            card.setOnClickListener(view -> a.openHomeCard(site, v));

            FrameLayout art = new FrameLayout(a);
            art.setBackground(glassBg(0xff1c2421, 0xff141a18, a.dp(12), 0, 0));
            art.setClipToOutline(true);
            art.setElevation(a.dp(2)); // mkoai 2.0.64: faint shadow edges the card - the 0.6dp glass stroke above is gone (it read as a pasted-on rectangle).

            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(img, new FrameLayout.LayoutParams(-1, -1));
            bindReliablePoster(img, v);

            // 2.0.40: year bottom-left + remarks badge bottom-right.
            addPosterOverlay(a, art, v.remarks, v.year);

            card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(posterHeightDp)));

            // Line 1: Title (bold 13sp white)
            TextView title = a.text(v.title, 13, 0xfff5f7fa);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setShadowLayer(a.dp(3), 0, a.dp(1), 0xcc000000);
            title.setPadding(a.dp(1), a.dp(7), a.dp(1), 0);
            card.addView(title);

            // Line 2: Subtitle controlled by Settings -> 浏览 -> 海报与卡片
            if (subtitleMode != 3) {
                String subtitleText = formatCardSubtitle(v, site, subtitleMode);
                TextView sub = a.text(subtitleText, 11, 0xff8e959e);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                sub.setPadding(a.dp(1), a.dp(2), a.dp(1), a.dp(2));
                card.addView(sub);
            }

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -2, 1);
            int colIdx = i % cols;
            cellLp.leftMargin = colIdx == 0 ? 0 : a.dp(5);
            cellLp.rightMargin = colIdx == cols - 1 ? 0 : a.dp(5);
            cellLp.topMargin = a.dp(4);
            cellLp.bottomMargin = a.dp(14);
            currentRow.addView(card, cellLp);
        }
        if (currentRow != null) {
            int rem = videos.size() % cols;
            if (rem > 0) {
                for (int i = rem; i < cols; i++) {
                    LinearLayout.LayoutParams blankLp = new LinearLayout.LayoutParams(0, 1, 1);
                    blankLp.leftMargin = a.dp(5);
                    blankLp.rightMargin = i == cols - 1 ? 0 : a.dp(5);
                    currentRow.addView(new View(a), blankLp);
                }
            }
        }
    }

    private static String formatCardSubtitle(Cms.Video v, Cms.Site site, int mode) {
        if (mode == 2) {
            return site != null && site.name != null ? site.name : "";
        }
        if (mode == 0 && v.remarks != null && !v.remarks.trim().isEmpty()) {
            return v.remarks.trim();
        }
        String year = v.year == null ? "" : v.year.trim();
        String area = v.area == null ? "" : v.area.trim();
        String tag = v.tag == null ? "" : v.tag.trim();
        StringBuilder sb = new StringBuilder();
        if (!year.isEmpty()) sb.append(year);
        if (!area.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(area);
        } else if (!tag.isEmpty() && !"folder".equalsIgnoreCase(tag)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(tag);
        }
        if (sb.length() > 0) return sb.toString();
        if (v.remarks != null && !v.remarks.trim().isEmpty()) return v.remarks.trim();
        return site != null && site.name != null ? site.name : "";
    }

    private void renderEmptyWelcome() {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout wrap = a.vertical();
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(a.dp(22), statusBarHeight() + a.dp(20), a.dp(22), a.dp(110));
        sv.addView(wrap, new ScrollView.LayoutParams(-1, -2));

        LinearLayout card = a.vertical();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(a.dp(24), a.dp(26), a.dp(24), a.dp(26));
        card.setBackground(glassBg(0xd818221e, 0xd8101715, a.dp(26), a.dp(0.8f), 0x33ffffff));
        wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));

        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        logo.setContentDescription(a.privateMode ? "退出隱私空間" : "mkoai");
        logo.setOnClickListener(
                v -> {
                    if (a.privateMode) PrivacySpace.switchSpace(a, false);
                    else PrivacySpace.logoTap(a);
                });
        card.addView(logo, new LinearLayout.LayoutParams(a.dp(64), a.dp(64)));
        a.gap(card, 12);

        if (a.privateMode) {
            addMobileActionBtn(
                    card,
                    "退出隱私空間",
                    0xff1e2724,
                    MainActivity.WHITE,
                    () -> PrivacySpace.switchSpace(a, false));
            a.gap(card, 12);
        }

        TextView heading = a.text("把今晚，留給好故事。", 23, MainActivity.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading);
        a.gap(card, 10);

        TextView desc =
                a.text("匯入你自己合法獲取的影視配置，支援單倉/多倉配置地址、JSON 文字及手機剪貼簿一鍵匯入。", 13.5f, MainActivity.MUTED);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(a.dp(4), 1f);
        card.addView(desc);
        a.gap(card, 22);

        LinearLayout urlRow = a.row();
        final EditText urlInput = new EditText(a);
        urlInput.setHint("輸入或長按貼上配置網址 https://…");
        urlInput.setHintTextColor(0xff6b7280);
        urlInput.setTextColor(MainActivity.WHITE);
        urlInput.setTextSize(13);
        urlInput.setSingleLine(true);
        urlInput.setShowSoftInputOnFocus(true);
        urlInput.setLongClickable(true);
        urlInput.setTextIsSelectable(true);
        urlInput.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        urlInput.setBackground(a.bg(0xff1e2724, 999));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, -2, 1);
        urlLp.rightMargin = a.dp(8);
        urlRow.addView(urlInput, urlLp);

        TextView goBtn = a.text("匯入", 13, Color.WHITE);
        goBtn.setTypeface(null, Typeface.BOLD);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10));
        goBtn.setBackground(a.bg(0xffe34b58, 999));
        MobileDetailScreen.bindTouchFeedback(goBtn);
        goBtn.setOnClickListener(v -> importTextOrUrl(a, urlInput.getText().toString()));
        urlRow.addView(goBtn);
        card.addView(urlRow, new LinearLayout.LayoutParams(-1, -2));
        a.gap(card, 12);

        addMobileActionBtn(
                card, "一鍵貼上剪貼簿並匯入", 0xffe34b58, Color.WHITE, () -> pasteConfigFromClipboard(a));
        a.gap(card, 10);
        addMobileActionBtn(
                card,
                "選擇本地配置或本地包 (.json/.js/.py/.zip)",
                0xff1e2724,
                MainActivity.WHITE,
                a::pickConfig);
        a.gap(card, 10);
        addMobileActionBtn(
                card,
                "匯入電視直播源 (M3U / TXT / JSON)",
                0xff1e2724,
                MainActivity.WHITE,
                () -> SourceController.showLiveImportDialog(a));

        a.gap(wrap, 18);
        TextView legal = a.text("本產品僅為播放工具，不內建、不提供、不託管任何影視資源或配置地址。", 11.5f, 0xff6b7280);
        legal.setGravity(Gravity.CENTER);
        legal.setLineSpacing(a.dp(3), 1f);
        wrap.addView(legal);
    }

    private void addMobileActionBtn(
            LinearLayout parent, String text, int bgColor, int textColor, Runnable onClick) {
        TextView btn = a.text(text, 14, textColor);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
        btn.setBackground(a.bg(bgColor, 999));
        MobileDetailScreen.bindTouchFeedback(btn);
        btn.setOnClickListener(v -> onClick.run());
        parent.addView(btn, new LinearLayout.LayoutParams(-1, -2));
    }

    // =========================================================================
    // TAB 1: MOBILE SEARCH (System Soft Keyboard + Multi-Site Live Results)
    // =========================================================================
    private void renderSearchTab() {
        if (cachedSearchPrivate != a.privateMode) clearSearchSession();
        cachedSearchPrivate = a.privateMode;
        LinearLayout page = a.vertical();
        page.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), 0);
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Top row: back circle + search capsule
        LinearLayout topBar = a.row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = a.text("‹", 22, Color.WHITE);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setBackground(glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff));
        MobileDetailScreen.bindTouchFeedback(backBtn);
        // mkoai 2.0.48 (item 4): leaving the search page clears the
        // search box and the current results, so the next visit opens
        // a blank search page. History chips are NOT cleared — they
        // live in Prefs.GLOBAL_SEARCH_HISTORY, which clearSearchSession
        // never touches. Returning from a detail page does NOT go
        // through this button (back-stack Screen re-render), so a
        // detail visit keeps the results.
        backBtn.setOnClickListener(
                v -> {
                    clearSearchSession();
                    switchTab(0);
                });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        backLp.rightMargin = a.dp(10);
        topBar.addView(backBtn, backLp);

        LinearLayout inputRow = a.row();
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(a.dp(14), a.dp(3), a.dp(5), a.dp(3));
        inputRow.setBackground(glassBg(0xd4222a27, 0xc4181e1c, a.dp(999), a.dp(0.8f), 0x33ffffff));

        final EditText input = new EditText(a);
        input.setHint("搜尋片名、演員或拼音首字母…");
        input.setHintTextColor(0xff7c858e);
        input.setTextColor(MainActivity.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(a.dp(2), a.dp(7), a.dp(8), a.dp(7));
        if (!pendingSearchKeyword.isEmpty()) {
            input.setText(pendingSearchKeyword);
            input.setSelection(pendingSearchKeyword.length());
        }
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        TextView clearBtn = a.text("✕", 13, MainActivity.MUTED);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        MobileDetailScreen.bindTouchFeedback(clearBtn);
        inputRow.addView(clearBtn);

        TextView searchBtn = a.text("搜尋", 13, Color.WHITE);
        searchBtn.setTypeface(null, Typeface.BOLD);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        searchBtn.setBackground(a.bg(0xffe34b58, 999));
        MobileDetailScreen.bindTouchFeedback(searchBtn);
        inputRow.addView(searchBtn);

        topBar.addView(inputRow, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // Search history chips
        final LinearLayout historyWrap = a.vertical();
        page.addView(historyWrap, new LinearLayout.LayoutParams(-1, -2));

        final TextView searchStatus = a.text("輸入關鍵詞搜尋全部已啟用的片源", 12, MainActivity.MUTED);
        searchStatus.setMaxLines(2);
        searchStatus.setEllipsize(TextUtils.TruncateAt.END);
        searchStatus.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(6));
        page.addView(searchStatus);

        final HorizontalScrollView siteFilterScroll = new HorizontalScrollView(a);
        siteFilterScroll.setHorizontalScrollBarEnabled(false);
        siteFilterScroll.setPadding(0, a.dp(2), 0, a.dp(8));
        siteFilterScroll.setVisibility(View.GONE);
        siteFilterScroll
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            cachedSiteFilterScrollX = siteFilterScroll.getScrollX();
                        });
        final LinearLayout siteFilterRow = a.row();
        siteFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        siteFilterScroll.addView(siteFilterRow);
        page.addView(siteFilterScroll, new LinearLayout.LayoutParams(-1, -2));

        final ScrollView resultScroll = new ScrollView(a);
        resultScroll.setFillViewport(true);
        resultScroll.setVerticalScrollBarEnabled(false);
        resultScroll.setClipToPadding(false);
        resultScroll
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            cachedSearchScrollY = resultScroll.getScrollY();
                        });
        page.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        final LinearLayout resultList = a.vertical();
        resultList.setPadding(0, 0, 0, a.dp(106));
        resultScroll.addView(resultList, new ScrollView.LayoutParams(-1, -2));

        final int[] searchSerial = {0};
        final Runnable renderSearchStatus =
                () -> {
                    int separator = cachedSearchStatus.indexOf('\n');
                    String mainLine =
                            separator < 0
                                    ? cachedSearchStatus
                                    : cachedSearchStatus.substring(0, separator);
                    if (!cachedSearchSiteFilter.isEmpty() && !cachedSearchHits.isEmpty()) {
                        String filterName = cachedSearchSiteFilter;
                        int filteredCount = 0;
                        for (CachedSearchHit hit : cachedSearchHits) {
                            if (hit.site != null && cachedSearchSiteFilter.equals(hit.site.key)) {
                                filterName = hit.site.name;
                                filteredCount++;
                            }
                        }
                        mainLine =
                                "已篩選「"
                                        + filterName
                                        + "」："
                                        + filteredCount
                                        + " 條（共 "
                                        + cachedSearchHits.size()
                                        + " 條）";
                    }
                    searchStatus.setText(mainLine);
                };

        final String preferredSiteKey =
                currentSite != null ? currentSite.key : a.prefs.getString(Prefs.SELECTED_SITE, "");

        final Runnable[] refreshSiteFilterBar = new Runnable[1];
        final Runnable rebuildFilteredResults =
                () -> {
                    resultList.removeAllViews();
                    List<CachedSearchHit> ordered = new ArrayList<>();
                    for (CachedSearchHit hit : cachedSearchHits) {
                        if (cachedSearchSiteFilter.isEmpty()
                                || (hit.site != null
                                        && cachedSearchSiteFilter.equals(hit.site.key))) {
                            ordered.add(hit);
                        }
                    }
                    final String activeKw = cachedSearchKeyword;
                    Collections.sort(
                            ordered,
                            (h1, h2) -> {
                                boolean p1 =
                                        h1.site != null
                                                && preferredSiteKey != null
                                                && preferredSiteKey.equals(h1.site.key);
                                boolean p2 =
                                        h2.site != null
                                                && preferredSiteKey != null
                                                && preferredSiteKey.equals(h2.site.key);
                                int s1 =
                                        SearchVariants.scoreVideo(
                                                activeKw,
                                                h1.video,
                                                h1.site == null ? "" : h1.site.name,
                                                p1);
                                int s2 =
                                        SearchVariants.scoreVideo(
                                                activeKw,
                                                h2.video,
                                                h2.site == null ? "" : h2.site.name,
                                                p2);
                                return Integer.compare(s2, s1);
                            });
                    // mkoai 2.0.34: merge the same title from different sources
                    // into one card (Settings toggle, default ON). Grouping
                    // runs only when no per-site filter is active — with a
                    // filter on, each source keeps its own row.
                    boolean mergeOn =
                            cachedSearchSiteFilter.isEmpty()
                                    && a.prefs.getBoolean(Prefs.MERGE_SAME_TITLE, true);
                    if (mergeOn) {
                        java.util.Map<String, MergedSearchGroup> groups =
                                new java.util.LinkedHashMap<>();
                        for (CachedSearchHit hit : ordered) {
                            String key =
                                    SearchVariants.canonicalSearchKey(
                                            Cms.searchTitle(hit.video));
                            MergedSearchGroup g = groups.get(key);
                            if (g == null) {
                                List<AutoSourceFallback.Candidate> members =
                                        new ArrayList<>();
                                members.add(
                                        new AutoSourceFallback.Candidate(
                                                hit.site, hit.video));
                                groups.put(
                                        key,
                                        new MergedSearchGroup(
                                                hit.site, hit.video, members));
                            } else {
                                g.members.add(
                                        new AutoSourceFallback.Candidate(
                                                hit.site, hit.video));
                            }
                        }
                        for (MergedSearchGroup g : groups.values()) {
                            resultList.addView(buildMergedSearchResultRow(g, activeKw));
                        }
                    } else {
                        for (CachedSearchHit hit : ordered) {
                            resultList.addView(buildSearchResultRow(hit.site, hit.video));
                        }
                    }
                    renderSearchStatus.run();
                    if (refreshSiteFilterBar[0] != null) refreshSiteFilterBar[0].run();
                };

        refreshSiteFilterBar[0] =
                () -> {
                    siteFilterRow.removeAllViews();
                    if (cachedSearchHits.isEmpty()) {
                        siteFilterScroll.setVisibility(View.GONE);
                        return;
                    }
                    final java.util.Map<String, Cms.Site> hitSites =
                            new java.util.LinkedHashMap<>();
                    final java.util.Map<String, Integer> siteCounts =
                            new java.util.LinkedHashMap<>();
                    // Pin currentSite immediately after "全部" when it has results
                    if (preferredSiteKey != null && !preferredSiteKey.isEmpty()) {
                        for (CachedSearchHit hit : cachedSearchHits) {
                            if (hit.site != null && preferredSiteKey.equals(hit.site.key)) {
                                hitSites.put(hit.site.key, hit.site);
                                break;
                            }
                        }
                    }
                    for (CachedSearchHit hit : cachedSearchHits) {
                        if (hit.site == null || hit.site.key == null) continue;
                        hitSites.put(hit.site.key, hit.site);
                        Integer prev = siteCounts.get(hit.site.key);
                        siteCounts.put(hit.site.key, prev == null ? 1 : prev + 1);
                    }
                    if (!cachedSearchSiteFilter.isEmpty()
                            && !siteCounts.containsKey(cachedSearchSiteFilter)) {
                        cachedSearchSiteFilter = "";
                    }
                    siteFilterScroll.setVisibility(View.VISIBLE);

                    if (hitSites.size() >= 2) {
                        TextView drawerBtn = a.text("篩選線路 ▾", 12, 0xff5ac8fa);
                        drawerBtn.setTypeface(null, Typeface.BOLD);
                        drawerBtn.setSingleLine(true);
                        drawerBtn.setPadding(a.dp(12), a.dp(5), a.dp(12), a.dp(5));
                        drawerBtn.setBackground(
                                glassBg(0xd81b2a34, 0xc8131f28, a.dp(999), a.dp(0.8f), 0x405ac8fa));
                        MobileDetailScreen.bindTouchFeedback(drawerBtn);
                        drawerBtn.setOnClickListener(
                                v -> {
                                    final List<String> keys = new ArrayList<>();
                                    final List<String> labels = new ArrayList<>();
                                    keys.add("");
                                    labels.add("全部線路（" + cachedSearchHits.size() + "）");
                                    int currentIdx = 0;
                                    for (java.util.Map.Entry<String, Cms.Site> entry :
                                            hitSites.entrySet()) {
                                        String k = entry.getKey();
                                        Cms.Site s = entry.getValue();
                                        int c = siteCounts.containsKey(k) ? siteCounts.get(k) : 0;
                                        if (k.equals(cachedSearchSiteFilter)) {
                                            currentIdx = keys.size();
                                        }
                                        keys.add(k);
                                        labels.add(s.name + "（" + c + "）");
                                    }
                                    showDrawerPicker(
                                            a,
                                            "篩選搜尋線路",
                                            "選擇僅展示指定片源的搜尋結果，或恢復檢視全部線路",
                                            labels.toArray(new String[0]),
                                            currentIdx,
                                            which -> {
                                                if (which >= 0 && which < keys.size()) {
                                                    cachedSearchSiteFilter = keys.get(which);
                                                    cachedSearchScrollY = 0;
                                                    resultScroll.scrollTo(0, 0);
                                                    rebuildFilteredResults.run();
                                                }
                                            });
                                });
                        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                        dlp.rightMargin = a.dp(7);
                        siteFilterRow.addView(drawerBtn, dlp);
                    }

                    boolean allActive = cachedSearchSiteFilter.isEmpty();
                    TextView allChip =
                            a.text(
                                    "全部 (" + cachedSearchHits.size() + ")",
                                    12,
                                    allActive ? 0xff000000 : 0xffe5e7eb);
                    allChip.setTypeface(null, allActive ? Typeface.BOLD : Typeface.NORMAL);
                    allChip.setSingleLine(true);
                    allChip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                    allChip.setBackground(
                            allActive
                                    ? glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0)
                                    : glassBg(
                                            0xcc262e2b,
                                            0xb81a211f,
                                            a.dp(999),
                                            a.dp(0.7f),
                                            0x2effffff));
                    MobileDetailScreen.bindTouchFeedback(allChip);
                    allChip.setOnClickListener(
                            v -> {
                                if (!cachedSearchSiteFilter.isEmpty()) {
                                    cachedSearchSiteFilter = "";
                                    cachedSearchScrollY = 0;
                                    resultScroll.scrollTo(0, 0);
                                    rebuildFilteredResults.run();
                                }
                            });
                    LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(-2, -2);
                    allLp.rightMargin = a.dp(7);
                    siteFilterRow.addView(allChip, allLp);

                    for (java.util.Map.Entry<String, Cms.Site> entry : hitSites.entrySet()) {
                        final String siteKey = entry.getKey();
                        final Cms.Site siteObj = entry.getValue();
                        int count = siteCounts.containsKey(siteKey) ? siteCounts.get(siteKey) : 0;
                        final boolean active = siteKey.equals(cachedSearchSiteFilter);
                        TextView chip =
                                a.text(
                                        siteObj.name + " (" + count + ")",
                                        12,
                                        active ? 0xff000000 : 0xffe5e7eb);
                        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                        chip.setSingleLine(true);
                        chip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                        chip.setBackground(
                                active
                                        ? glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0)
                                        : glassBg(
                                                0xcc262e2b,
                                                0xb81a211f,
                                                a.dp(999),
                                                a.dp(0.7f),
                                                0x2effffff));
                        MobileDetailScreen.bindTouchFeedback(chip);
                        chip.setOnClickListener(
                                v -> {
                                    cachedSearchSiteFilter = active ? "" : siteKey;
                                    cachedSearchScrollY = 0;
                                    resultScroll.scrollTo(0, 0);
                                    rebuildFilteredResults.run();
                                });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                        lp.rightMargin = a.dp(7);
                        siteFilterRow.addView(chip, lp);
                    }
                };

        clearBtn.setOnClickListener(
                v -> {
                    searchSerial[0]++;
                    if (activeSearchCoordinator != null) {
                        activeSearchCoordinator.close();
                        activeSearchCoordinator = null;
                    }
                    a.cancelPageRequests();
                    input.setText("");
                    cachedSearchKeyword = "";
                    cachedSearchStatus = "";
                    cachedSearchSiteFilter = "";
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;
                    cachedSiteFilterScrollX = 0;
                    siteFilterRow.removeAllViews();
                    siteFilterScroll.setVisibility(View.GONE);
                    resultList.removeAllViews();
                    searchStatus.setText("輸入關鍵詞搜尋全部已啟用的片源");
                });

        final Runnable[] triggerSearch = new Runnable[1];
        final Runnable renderHistoryChips =
                () -> {
                    historyWrap.removeAllViews();
                    try {
                        JSONArray arr =
                                new JSONArray(a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
                        if (arr.length() == 0) return;
                        LinearLayout hRow = a.row();
                        hRow.setPadding(a.dp(2), a.dp(10), a.dp(2), a.dp(4));
                        TextView hLabel = a.text("歷史搜尋", 12, MainActivity.MUTED);
                        hRow.addView(hLabel, new LinearLayout.LayoutParams(0, -2, 1));
                        TextView clearHist = a.text("清空", 12, 0xffe34b58);
                        MobileDetailScreen.bindTouchFeedback(clearHist);
                        clearHist.setOnClickListener(
                                v -> {
                                    a.prefs
                                            .edit()
                                            .putString(Prefs.GLOBAL_SEARCH_HISTORY, "[]")
                                            .apply();
                                    historyWrap.removeAllViews();
                                });
                        hRow.addView(clearHist);
                        historyWrap.addView(hRow);

                        HorizontalScrollView chipScroll = new HorizontalScrollView(a);
                        chipScroll.setHorizontalScrollBarEnabled(false);
                        LinearLayout chipRow = a.row();
                        chipScroll.addView(chipRow);
                        for (int i = 0; i < Math.min(12, arr.length()); i++) {
                            final String kw = arr.optString(i);
                            if (kw.isEmpty()) continue;
                            TextView chip = a.text(kw, 12, 0xffe5e7eb);
                            chip.setPadding(a.dp(13), a.dp(6), a.dp(13), a.dp(6));
                            chip.setBackground(
                                    glassBg(
                                            0xcc262e2b,
                                            0xb81a211f,
                                            a.dp(999),
                                            a.dp(0.7f),
                                            0x2effffff));
                            MobileDetailScreen.bindTouchFeedback(chip);
                            chip.setOnClickListener(
                                    v -> {
                                        input.setText(kw);
                                        input.setSelection(kw.length());
                                        triggerSearch[0].run();
                                    });
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                            lp.rightMargin = a.dp(8);
                            chipRow.addView(chip, lp);
                        }
                        historyWrap.addView(chipScroll);
                    } catch (Exception ignored) {
                    }
                };

        triggerSearch[0] =
                () -> {
                    String kw = input.getText().toString().trim();
                    if (kw.isEmpty()) {
                        a.toast("請輸入搜尋關鍵詞");
                        return;
                    }
                    if (a.config == null || a.config.sites.isEmpty()) {
                        showMobileImportDialog(a);
                        return;
                    }
                    // Hide keyboard
                    try {
                        InputMethodManager imm =
                                (InputMethodManager)
                                        a.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    } catch (Exception ignored) {
                    }

                    // Private queries never enter history or the shared phonetic dictionary.
                    if (!a.privateMode)
                        try {
                            JSONArray old =
                                    new JSONArray(
                                            a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
                            JSONArray updated = new JSONArray();
                            updated.put(kw);
                            for (int i = 0; i < old.length() && updated.length() < 16; i++) {
                                String item = old.optString(i);
                                if (!item.isEmpty() && !item.equals(kw)) updated.put(item);
                            }
                            a.prefs
                                    .edit()
                                    .putString(Prefs.GLOBAL_SEARCH_HISTORY, updated.toString())
                                    .apply();
                        } catch (Exception ignored) {
                        }
                    renderHistoryChips.run();

                    final int searchTicket = ++searchSerial[0];
                    if (activeSearchCoordinator != null) {
                        activeSearchCoordinator.close();
                        activeSearchCoordinator = null;
                    }
                    a.cancelPageRequests();
                    resultList.removeAllViews();
                    cachedSearchKeyword = kw;
                    cachedSearchSiteFilter = "";
                    cachedSearchHits.clear();
                    cachedSearchScrollY = 0;
                    cachedSiteFilterScrollX = 0;
                    siteFilterRow.removeAllViews();
                    siteFilterScroll.setVisibility(View.GONE);

                    List<Cms.Site> searchableSites = new ArrayList<>();
                    // mkoai 2.0.34: youth mode hides adult-mixed sources
                    // from search entirely, and adult-marked titles from
                    // every source (see the fetch lambda below).
                    final boolean youthOn =
                            a.prefs.getBoolean(Prefs.YOUTH_MODE, false);
                    for (Cms.Site s : SourcePreferences.prioritized(a, a.config.sites)) {
                        if (a.available(s) && s.searchable) {
                            if (!a.available(s) || (youthOn && SearchVariants.isAdultSource(s))) continue;
                            searchableSites.add(s);
                        }
                    }
                    final String selectedKey =
                            currentSite != null
                                    ? currentSite.key
                                    : a.prefs.getString(Prefs.SELECTED_SITE, "");
                    Collections.sort(
                            searchableSites,
                            (x, y) ->
                                    Boolean.compare(
                                            !x.key.equals(selectedKey),
                                            !y.key.equals(selectedKey)));
                    if (searchableSites.isEmpty()) {
                        cachedSearchStatus = "當前沒有可搜尋的片源";
                        searchStatus.setText(cachedSearchStatus);
                        return;
                    }
                    final List<String> expandedTerms =
                            SearchVariants.mergeTerms(kw, Collections.singletonList(kw));
                    cachedSearchStatus = "正在併發搜尋 " + searchableSites.size() + " 個片源…";
                    searchStatus.setText(cachedSearchStatus);
                    final int[] completed = {0};
                    final int[] hitCount = {0};

                    // mkoai 2.0.35: default 12 (was 6) — with 23 sources,
                    // 6 threads needed ~4 sequential rounds.
                    SearchCoordinator.configureThreads(a.prefs.getInt(Prefs.SEARCH_THREADS, 12));
                    activeSearchCoordinator =
                            new SearchCoordinator(
                                    (site, keyword) ->
                                            SearchVariants.run(
                                                    keyword,
                                                    expandedTerms,
                                                    t -> true,
                                                    term -> {
                                                        List<Cms.Video> matches = new ArrayList<>();
                                                        try {
                                                            Cms.Listing l =
                                                                    Cms.listing(
                                                                            a.engine(site)
                                                                                    .search(
                                                                                            term,
                                                                                            1));
                                                            for (Cms.Video v : l.videos) {
                                                                if (v.action == null
                                                                        || v.action.isEmpty()) {
                                                                    // mkoai 2.0.34: drop
                                                                    // trailer-only and
                                                                    // commentary-only entries —
                                                                    // they are not the show.
                                                                    // Youth mode (default OFF)
                                                                    // additionally drops
                                                                    // adult-marked titles.
                                                                    if (!SearchVariants
                                                                                    .isSearchJunk(v)
                                                                            && !(youthOn
                                                                                    && SearchVariants
                                                                                            .isAdult(
                                                                                                    v,
                                                                                                    SearchVariants
                                                                                                            .hideLunli(
                                                                                                                    a)))) {
                                                                        matches.add(v);
                                                                    }
                                                                }
                                                            }
                                                        } catch (
                                                                com.pilipala.catpaw.CatPawHost
                                                                                .DrpySourceException
                                                                        error) {
                                                            if ("drpy_verification_required"
                                                                    .equals(error.code)) {
                                                                throw new SearchCoordinator
                                                                        .VerificationRequiredException();
                                                            }
                                                            throw error;
                                                        }
                                                        return matches;
                                                    }),
                                    (site, matches, outcome) ->
                                            a.ui.post(
                                                    () -> {
                                                        if (searchTicket != searchSerial[0]
                                                                || a.switchingPrivacy
                                                                || a.privateMode
                                                                        != PrivacySpace.active
                                                                || a.isFinishing()
                                                                || a.isDestroyed()) return;
                                                        completed[0]++;
                                                        if (matches != null && !matches.isEmpty()) {
                                                            for (final Cms.Video v : matches) {
                                                                hitCount[0]++;
                                                                if (kw.equals(
                                                                        cachedSearchKeyword)) {
                                                                    cachedSearchHits.add(
                                                                            new CachedSearchHit(
                                                                                    site, v));
                                                                }
                                                            }
                                                            rebuildFilteredResults.run();
                                                        }
                                                        if (completed[0] < searchableSites.size()) {
                                                            cachedSearchStatus =
                                                                    "已找到 "
                                                                            + hitCount[0]
                                                                            + " 條結果（已完成 "
                                                                            + completed[0]
                                                                            + "/"
                                                                            + searchableSites.size()
                                                                            + " 個片源）";
                                                        } else {
                                                            cachedSearchStatus =
                                                                    hitCount[0] == 0
                                                                            ? "未找到與“" + kw
                                                                                    + "”相關的影片"
                                                                            : "搜尋完成，共找到 "
                                                                                    + hitCount[0]
                                                                                    + " 條結果（點選上方片源標籤可篩選線路）";
                                                        }
                                                        renderSearchStatus.run();
                                                    }),
                                    Prefs.clampSearchTimeoutSeconds(
                                                    a.prefs.getInt(Prefs.SEARCH_TIMEOUT, 20))
                                            * 1000L,
                                    90000L);
                    activeSearchCoordinator.start(searchableSites, kw);
                };

        searchBtn.setOnClickListener(v -> triggerSearch[0].run());
        input.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH
                            || (event != null
                                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                        triggerSearch[0].run();
                        return true;
                    }
                    return false;
                });

        renderHistoryChips.run();
        if (!pendingSearchKeyword.isEmpty()) {
            String wantedKw = pendingSearchKeyword;
            pendingSearchKeyword = "";
            input.setText(wantedKw);
            input.setSelection(wantedKw.length());
            if (wantedKw.equals(cachedSearchKeyword) && !cachedSearchHits.isEmpty()) {
                final int savedScroll = cachedSearchScrollY;
                final int savedFilterX = cachedSiteFilterScrollX;
                if (cachedSearchStatus.isEmpty())
                    cachedSearchStatus = "已保留搜尋結果，共 " + cachedSearchHits.size() + " 條";
                rebuildFilteredResults.run();
                if (savedFilterX > 0) {
                    siteFilterScroll.post(() -> siteFilterScroll.scrollTo(savedFilterX, 0));
                }
                if (savedScroll > 0) {
                    resultScroll.post(() -> resultScroll.scrollTo(0, savedScroll));
                }
            } else {
                triggerSearch[0].run();
            }
        } else if (!cachedSearchKeyword.isEmpty()) {
            input.setText(cachedSearchKeyword);
            input.setSelection(cachedSearchKeyword.length());
            final int savedScroll = cachedSearchScrollY;
            final int savedFilterX = cachedSiteFilterScrollX;
            if (cachedSearchStatus.isEmpty())
                cachedSearchStatus = "已保留搜尋結果，共 " + cachedSearchHits.size() + " 條";
            rebuildFilteredResults.run();
            if (savedFilterX > 0) {
                siteFilterScroll.post(() -> siteFilterScroll.scrollTo(savedFilterX, 0));
            }
            if (savedScroll > 0) {
                resultScroll.post(() -> resultScroll.scrollTo(0, savedScroll));
            }
        }
    }

    private View buildSearchResultRow(final Cms.Site site, final Cms.Video v) {
        return buildSearchResultRowView(
                site,
                v,
                "來源：" + site.name,
                view -> {
                    if (activeSearchCoordinator != null) {
                        activeSearchCoordinator.close();
                        activeSearchCoordinator = null;
                    }
                    activeTab = 1;
                    a.openListing(site, v);
                });
    }

    /**
     * mkoai 2.0.34: one merged card per title. Members are the same title
     * from different sources (grouped by canonicalSearchKey, so Simplified
     * and Traditional forms merge). The representative (highest title-match
     * score) supplies the poster/title/tag; the badge counts the sources.
     * Tap auto-selects the best playable source, Maoki-style.
     */
    static final class MergedSearchGroup {
        final Cms.Site repSite;
        final Cms.Video repVideo;
        final List<AutoSourceFallback.Candidate> members;

        MergedSearchGroup(
                Cms.Site repSite,
                Cms.Video repVideo,
                List<AutoSourceFallback.Candidate> members) {
            this.repSite = repSite;
            this.repVideo = repVideo;
            this.members = members;
        }
    }

    private View buildMergedSearchResultRow(final MergedSearchGroup group, final String query) {
        final int count = group.members.size();
        String label =
                count > 1
                        ? "來源：" + group.repSite.name + " 等 " + count + " 個片源"
                        : "來源：" + group.repSite.name;
        return buildSearchResultRowView(
                group.repSite,
                group.repVideo,
                label,
                view -> {
                    if (activeSearchCoordinator != null) {
                        activeSearchCoordinator.close();
                        activeSearchCoordinator = null;
                    }
                    // Single source: open its detail directly, no probe delay.
                    if (group.members.size() <= 1) {
                        activeTab = 1;
                        a.openListing(group.repSite, group.repVideo);
                        return;
                    }
                    a.toast("正在自動選源…");
                    AutoSourceFallback.findFromCandidates(
                            a,
                            group.members,
                            query,
                            p -> {
                                if (p != null) {
                                    activeTab = 1;
                                    // mkoai 2.0.35: MUST go through
                                    // open(..., history=true) like
                                    // openListing does. Calling a.detail()
                                    // directly renders the page but never
                                    // pushes the search screen onto the
                                    // back stack, so pressing Back found
                                    // an empty stack and exited the app
                                    // (2.0.34 regression, merged cards
                                    // only — single-source cards use
                                    // openListing and were fine).
                                    a.open(
                                            new MainActivity.Screen(
                                                    () ->
                                                            a.detail(
                                                                    p.site,
                                                                    p.listing,
                                                                    p.detail)),
                                            true);
                                } else {
                                    // No candidate could play: open the
                                    // top-ranked hit's detail page so its
                                    // own fallback can take over.
                                    activeTab = 1;
                                    a.openListing(group.repSite, group.repVideo);
                                }
                            });
                });
    }

    private View buildSearchResultRowView(
            final Cms.Site site,
            final Cms.Video v,
            final String sourceLabel,
            final View.OnClickListener onClick) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
        row.setBackground(glassBg(0xb81c2421, 0xb8141a18, a.dp(16), a.dp(0.7f), 0x26ffffff));
        MobileDetailScreen.bindTouchFeedback(row);
        row.setOnClickListener(onClick);

        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(a.bg(0xff232b28, 12));
        poster.setClipToOutline(true);
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(a.dp(60), a.dp(86));
        posterLp.rightMargin = a.dp(12);
        row.addView(poster, posterLp);
        if (v.sourceApi == null || v.sourceApi.isEmpty()) v.sourceApi = site.api;
        bindReliablePoster(poster, v);

        LinearLayout info = a.vertical();
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        TextView title = a.text(v.title, 15, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);

        LinearLayout badgeRow = a.row();
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        badgeRow.setPadding(0, a.dp(4), 0, 0);

        TextView badge = a.text(sourceLabel, 12, 0xffe34b58);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setSingleLine(true);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        badgeRow.addView(badge);

        String rawTag = v.tag == null ? "" : v.tag.trim();
        boolean isComic = SearchVariants.isComicOrShortDrama(v, site.name);
        String displayTag =
                !rawTag.isEmpty() && !"folder".equalsIgnoreCase(rawTag)
                        ? rawTag
                        : (isComic ? "漫劇/衍生" : "");
        if (!displayTag.isEmpty()) {
            TextView typePill = a.text(displayTag, 10.5f, isComic ? 0xffffb84d : 0xff6ee7b7);
            typePill.setTypeface(null, Typeface.BOLD);
            typePill.setSingleLine(true);
            typePill.setPadding(a.dp(7), a.dp(1.5f), a.dp(7), a.dp(1.5f));
            typePill.setBackground(a.bg(isComic ? 0x33ff9f0a : 0x2c30d158, 999));
            LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(-2, -2);
            tpLp.leftMargin = a.dp(7);
            badgeRow.addView(typePill, tpLp);
        }
        info.addView(badgeRow, new LinearLayout.LayoutParams(-1, -2));

        String sub =
                ((v.year == null ? "" : v.year)
                                + " "
                                + (v.area == null ? "" : v.area)
                                + " "
                                + (v.remarks == null ? "" : v.remarks))
                        .trim();
        if (!sub.isEmpty()) {
            TextView subView = a.text(sub, 12, MainActivity.MUTED);
            subView.setPadding(0, a.dp(4), 0, 0);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(subView);
        }

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = a.dp(10);
        row.setLayoutParams(rowLp);
        return row;
    }

    // =========================================================================
    // TAB 2: MOBILE LIVE TV (Two-Tier Group Pills + Multi-Column Card Grid)
    // =========================================================================
    private void renderLiveTab() {
        LinearLayout page = a.vertical();
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        final List<LiveCatalog.Source> sources = LiveController.sourcesFor(a);
        if (sources.isEmpty()) {
            page.setPadding(a.dp(24), statusBarHeight() + a.dp(24), a.dp(24), 0);
            renderCenteredHint(page, "當前未配置直播源（支援 M3U / TXT / JSON）");
            addMobileActionBtn(
                    page,
                    "匯入電視直播源 (M3U / TXT / JSON)",
                    0xffe34b58,
                    Color.WHITE,
                    () -> SourceController.showLiveImportDialog(a));
            return;
        }

        // Row 1: Top Header with Live Source Selector Pills + Quick Channel Filter
        LinearLayout topHeader = a.vertical();
        topHeader.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), a.dp(8));
        page.addView(topHeader, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleSearchRow = a.row();
        titleSearchRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = a.text("電視直播", 22, Color.WHITE);
        titleView.setTypeface(null, Typeface.BOLD);
        titleSearchRow.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));

        final EditText searchInput = new EditText(a);
        searchInput.setHint("搜尋頻道…");
        searchInput.setHintTextColor(0xff7c858e);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(12.5f);
        searchInput.setSingleLine(true);
        searchInput.setPadding(a.dp(13), a.dp(6), a.dp(13), a.dp(6));
        searchInput.setBackground(
                glassBg(0xd4222a27, 0xc4181e1c, a.dp(999), a.dp(0.8f), 0x33ffffff));
        titleSearchRow.addView(searchInput, new LinearLayout.LayoutParams(a.dp(168), -2));
        topHeader.addView(titleSearchRow, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView srcScroll = new HorizontalScrollView(a);
        srcScroll.setHorizontalScrollBarEnabled(false);
        srcScroll.setPadding(0, a.dp(10), 0, a.dp(2));
        final LinearLayout srcRow = a.row();
        srcScroll.addView(srcRow);
        topHeader.addView(srcScroll, new LinearLayout.LayoutParams(-1, -2));

        // Row 2: Horizontal Channel Group Selector Pills
        HorizontalScrollView groupScroll = new HorizontalScrollView(a);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setPadding(0, a.dp(6), 0, a.dp(2));
        final LinearLayout groupRow = a.row();
        groupScroll.addView(groupRow);
        topHeader.addView(groupScroll, new LinearLayout.LayoutParams(-1, -2));

        final TextView stateView = a.text("正在載入直播頻道…", 13, MainActivity.MUTED);
        stateView.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
        page.addView(stateView);

        // Main Multi-Column Channel Card Grid Area
        ScrollView channelScroll = new ScrollView(a);
        channelScroll.setVerticalScrollBarEnabled(false);
        channelScroll.setClipToPadding(false);
        final LinearLayout channelGridCol = a.vertical();
        channelGridCol.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(106));
        channelScroll.addView(channelGridCol);
        page.addView(channelScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        final int[] activeSrcIdx = {0};
        final int[] activeGroupIdx = {0};
        final LiveCatalog.Catalog[] activeCatalog = {null};
        final Runnable[] loadSource = new Runnable[1];
        final int[] liveRequest = {0};

        searchInput.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (activeCatalog[0] != null) {
                            renderLiveGroupsAndChannels(
                                    sources.get(activeSrcIdx[0]),
                                    activeCatalog[0],
                                    activeGroupIdx,
                                    s.toString().trim(),
                                    searchInput,
                                    120,
                                    groupRow,
                                    channelGridCol);
                        }
                    }
                });

        loadSource[0] =
                () -> {
                    final int request = ++liveRequest[0];
                    a.cancelPageRequests();
                    activeCatalog[0] = null;
                    srcRow.removeAllViews();
                    for (int i = 0; i < sources.size(); i++) {
                        final int idx = i;
                        boolean active = i == activeSrcIdx[0];
                        TextView chip =
                                a.text(sources.get(i).name, 12, active ? Color.WHITE : 0xffd1d5db);
                        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                        chip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                        chip.setBackground(
                                active
                                        ? a.bg(0xffe34b58, 999)
                                        : glassBg(
                                                0xcc262e2b,
                                                0xb81a211f,
                                                a.dp(999),
                                                a.dp(0.7f),
                                                0x2effffff));
                        MobileDetailScreen.bindTouchFeedback(chip);
                        chip.setOnClickListener(
                                v -> {
                                    activeSrcIdx[0] = idx;
                                    activeGroupIdx[0] = 0;
                                    loadSource[0].run();
                                });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                        lp.rightMargin = a.dp(8);
                        srcRow.addView(chip, lp);
                    }
                    TextView importLiveChip = a.text("＋ 匯入直播", 12, 0xff5ac8fa);
                    importLiveChip.setTypeface(null, Typeface.BOLD);
                    importLiveChip.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
                    importLiveChip.setBackground(
                            glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
                    MobileDetailScreen.bindTouchFeedback(importLiveChip);
                    importLiveChip.setOnClickListener(
                            v -> SourceController.showLiveImportDialog(a));
                    srcRow.addView(importLiveChip, new LinearLayout.LayoutParams(-2, -2));

                    groupRow.removeAllViews();
                    channelGridCol.removeAllViews();
                    stateView.setVisibility(View.VISIBLE);
                    stateView.setText("正在載入頻道列表…");
                    final LiveCatalog.Source selectedSource = sources.get(activeSrcIdx[0]);
                    final int ticket = a.generation;
                    a.submitPage(
                            () -> {
                                try {
                                    final LiveCatalog.Catalog catalog =
                                            LiveController.loadCatalog(a, selectedSource);
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != liveRequest[0]) return;
                                                activeCatalog[0] = catalog;
                                                a.liveSession =
                                                        new LiveSession(selectedSource, catalog);
                                                stateView.setVisibility(View.GONE);
                                                renderLiveGroupsAndChannels(
                                                        selectedSource,
                                                        catalog,
                                                        activeGroupIdx,
                                                        searchInput.getText().toString().trim(),
                                                        searchInput,
                                                        120,
                                                        groupRow,
                                                        channelGridCol);
                                            });
                                } catch (Exception e) {
                                    a.ui.post(
                                            () -> {
                                                if (ticket != a.generation
                                                        || a.isFinishing()
                                                        || request != liveRequest[0]) return;
                                                stateView.setVisibility(View.VISIBLE);
                                                stateView.setText("讀取直播源失敗：" + a.message(e));
                                            });
                                }
                            });
                };

        loadSource[0].run();
    }

    private void renderLiveGroupsAndChannels(
            final LiveCatalog.Source source,
            final LiveCatalog.Catalog catalog,
            final int[] selectedGroupHolder,
            final String searchQuery,
            final EditText searchInput,
            final int maxVisibleCount,
            final LinearLayout groupRow,
            final LinearLayout channelGridCol) {
        groupRow.removeAllViews();
        channelGridCol.removeAllViews();
        if (catalog.groups.isEmpty()) return;

        final int selectedGroupIdx = selectedGroupHolder[0];
        final String qLower = searchQuery == null ? "" : searchQuery.toLowerCase();

        // Pill 0: All Channels
        boolean allActive = qLower.isEmpty() && selectedGroupIdx == -1;
        TextView allBtn =
                a.text(
                        "全部 (" + catalog.channelCount() + ")",
                        12,
                        allActive ? 0xff000000 : 0xffe5e7eb);
        allBtn.setTypeface(null, allActive ? Typeface.BOLD : Typeface.NORMAL);
        allBtn.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
        allBtn.setBackground(
                allActive
                        ? glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0)
                        : glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
        MobileDetailScreen.bindTouchFeedback(allBtn);
        allBtn.setOnClickListener(
                v -> {
                    selectedGroupHolder[0] = -1;
                    if (searchInput != null && searchInput.getText().length() > 0) {
                        searchInput.setText("");
                        return;
                    }
                    renderLiveGroupsAndChannels(
                            source,
                            catalog,
                            selectedGroupHolder,
                            "",
                            searchInput,
                            120,
                            groupRow,
                            channelGridCol);
                });
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(-2, -2);
        allLp.rightMargin = a.dp(7);
        groupRow.addView(allBtn, allLp);

        for (int i = 0; i < catalog.groups.size(); i++) {
            final int gIdx = i;
            boolean active = qLower.isEmpty() && i == selectedGroupIdx;
            LiveCatalog.Group g = catalog.groups.get(i);
            TextView gBtn =
                    a.text(
                            g.name + " (" + g.channels.size() + ")",
                            12,
                            active ? 0xff000000 : 0xffe5e7eb);
            gBtn.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            gBtn.setSingleLine(true);
            gBtn.setPadding(a.dp(13), a.dp(5), a.dp(13), a.dp(5));
            gBtn.setBackground(
                    active
                            ? glassBg(0xffffffff, 0xffeaeaf0, a.dp(999), 0, 0)
                            : glassBg(0xcc262e2b, 0xb81a211f, a.dp(999), a.dp(0.7f), 0x2effffff));
            MobileDetailScreen.bindTouchFeedback(gBtn);
            gBtn.setOnClickListener(
                    v -> {
                        selectedGroupHolder[0] = gIdx;
                        if (searchInput != null && searchInput.getText().length() > 0) {
                            searchInput.setText("");
                            return;
                        }
                        renderLiveGroupsAndChannels(
                                source,
                                catalog,
                                selectedGroupHolder,
                                "",
                                searchInput,
                                120,
                                groupRow,
                                channelGridCol);
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = a.dp(7);
            groupRow.addView(gBtn, lp);
        }

        // Collect channels to display
        List<LiveCatalog.Channel> displayList = new ArrayList<>();
        List<String> displayGroups = new ArrayList<>();
        if (!qLower.isEmpty()) {
            for (LiveCatalog.Group g : catalog.groups) {
                for (LiveCatalog.Channel ch : g.channels) {
                    if (ch.name.toLowerCase().contains(qLower)
                            || g.name.toLowerCase().contains(qLower)) {
                        displayList.add(ch);
                        displayGroups.add(g.name);
                    }
                }
            }
        } else if (selectedGroupIdx == -1) {
            for (LiveCatalog.Group g : catalog.groups) {
                for (LiveCatalog.Channel ch : g.channels) {
                    displayList.add(ch);
                    displayGroups.add(g.name);
                }
            }
        } else {
            int safeIdx = Math.max(0, Math.min(selectedGroupIdx, catalog.groups.size() - 1));
            LiveCatalog.Group g = catalog.groups.get(safeIdx);
            for (LiveCatalog.Channel ch : g.channels) {
                displayList.add(ch);
                displayGroups.add(g.name);
            }
        }

        if (displayList.isEmpty()) {
            TextView empty = a.text("未找到匹配的直播頻道", 13, MainActivity.MUTED);
            empty.setPadding(a.dp(8), a.dp(24), a.dp(8), a.dp(24));
            channelGridCol.addView(empty);
            return;
        }

        int swDp = a.getResources().getConfiguration().screenWidthDp;
        final int cols = swDp >= 880 ? 4 : (swDp >= 580 ? 3 : 2);
        final int shownCount = Math.min(displayList.size(), Math.max(cols, maxVisibleCount));
        LinearLayout currentRow = null;

        for (int i = 0; i < shownCount; i++) {
            if (i % cols == 0) {
                currentRow = a.row();
                currentRow.setBaselineAligned(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.bottomMargin = a.dp(10);
                channelGridCol.addView(currentRow, rowLp);
            }
            final LiveCatalog.Channel ch = displayList.get(i);
            final String groupName = displayGroups.get(i);
            LinearLayout card = buildMobileLiveChannelCard(source, ch, groupName);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % cols != cols - 1) {
                cardLp.rightMargin = a.dp(10);
            }
            currentRow.addView(card, cardLp);
        }
        int remainder = shownCount % cols;
        if (remainder != 0 && currentRow != null) {
            for (int pad = remainder; pad < cols; pad++) {
                View spacer = new View(a);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                if (pad != cols - 1) {
                    spLp.rightMargin = a.dp(10);
                }
                currentRow.addView(spacer, spLp);
            }
        }
        if (shownCount < displayList.size()) {
            TextView moreBtn =
                    a.text(
                            "載入更多頻道（已顯示 " + shownCount + " / " + displayList.size() + "）",
                            13,
                            0xff5ac8fa);
            moreBtn.setTypeface(null, Typeface.BOLD);
            moreBtn.setGravity(Gravity.CENTER);
            moreBtn.setPadding(a.dp(16), a.dp(11), a.dp(16), a.dp(11));
            moreBtn.setBackground(
                    glassBg(0xcc202926, 0xb8161d1b, a.dp(999), a.dp(0.8f), 0x33ffffff));
            MobileDetailScreen.bindTouchFeedback(moreBtn);
            moreBtn.setOnClickListener(
                    v ->
                            renderLiveGroupsAndChannels(
                                    source,
                                    catalog,
                                    selectedGroupHolder,
                                    searchQuery,
                                    searchInput,
                                    shownCount + 120,
                                    groupRow,
                                    channelGridCol));
            LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(-1, -2);
            moreLp.topMargin = a.dp(4);
            channelGridCol.addView(moreBtn, moreLp);
        }
    }

    private LinearLayout buildMobileLiveChannelCard(
            final LiveCatalog.Source source, final LiveCatalog.Channel ch, final String groupName) {
        LinearLayout card = a.row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
        card.setBackground(glassBg(0xb81c2522, 0xb8141a18, a.dp(16), a.dp(0.8f), 0x28ffffff));
        MobileDetailScreen.bindTouchFeedback(card);

        // Left: Smart Callsign Badge
        TextView badge = buildSmartChannelBadgeView(a, ch.name);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(a.dp(40), a.dp(40));
        badgeLp.rightMargin = a.dp(9);
        card.addView(badge, badgeLp);

        // Middle: Two-Line Info
        LinearLayout infoCol = a.vertical();
        TextView name = a.text(ch.name, 13, MainActivity.WHITE);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        infoCol.addView(name);

        LinearLayout subRow = a.row();
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, a.dp(3), 0, 0);
        TextView sub =
                a.text(
                        groupName
                                + " · "
                                + (ch.streams.size() > 1 ? ch.streams.size() + "源" : "直連"),
                        11,
                        0xff9ca3af);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        subRow.addView(sub, new LinearLayout.LayoutParams(0, -2, 1f));
        infoCol.addView(subRow);

        card.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1f));

        card.setOnClickListener(
                v -> {
                    if (ch.streams.isEmpty()) return;
                    a.playLive(source, ch, ch.streams.get(0));
                });
        if (ch.streams.size() > 1) {
            card.setOnLongClickListener(
                    v -> {
                        String[] names = new String[ch.streams.size()];
                        for (int n = 0; n < names.length; n++) {
                            String lbl = ch.streams.get(n).label;
                            names[n] =
                                    "線路 "
                                            + (n + 1)
                                            + (lbl != null && !lbl.trim().isEmpty()
                                                    ? " · " + lbl.trim()
                                                    : "");
                        }
                        showDrawerPicker(
                                a,
                                ch.name + " · 選擇直播線路",
                                "長按頻道可手動切換不同直播源線路",
                                names,
                                0,
                                n -> a.playLive(source, ch, ch.streams.get(n)));
                        return true;
                    });
        }
        return card;
    }

    static TextView buildSmartChannelBadgeView(MainActivity a, String rawName) {
        String name = rawName == null ? "TV" : rawName.trim();
        String upper = name.toUpperCase();
        int bgColor = 0xff2563eb;
        String label;
        if (upper.startsWith("CCTV")) {
            bgColor = 0xffe11d48;
            String num = upper.replaceFirst("^CCTV[-\\s]*", "").replaceAll("[^0-9A-Z+]", "");
            if (num.length() > 3) num = num.substring(0, 3);
            label = num.isEmpty() ? "CCTV" : "CC\n" + num;
        } else if (name.contains("卫视")) {
            bgColor = 0xffd97706;
            String prefix = name.replaceAll("衛視.*$", "").trim();
            label = prefix.length() >= 2 ? prefix.substring(0, 2) : "衛視";
        } else if (name.contains("翡翠")
                || name.contains("明珠")
                || name.contains("凤凰")
                || upper.contains("TVB")) {
            bgColor = 0xff059669;
            label = name.contains("凤凰") ? "鳳凰" : "TVB";
        } else if (upper.contains("4K") || upper.contains("8K")) {
            bgColor = 0xff7c3aed;
            label = upper.contains("8K") ? "8K" : "4K";
        } else {
            String clean = name.replaceAll("[\\[\\(（【].*?[\\]\\)）】]", "").trim();
            label = clean.length() >= 2 ? clean.substring(0, 2) : (clean.isEmpty() ? "TV" : clean);
        }
        TextView tv = a.text(label, 11, Color.WHITE);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(a.bg(bgColor, 10));
        return tv;
    }

    // =========================================================================
    // TAB 3: LIBRARY ("片库": Watch History, Favorites & Downloads)
    // =========================================================================
    private void renderMyTab() {
        LinearLayout page = a.vertical();
        page.setPadding(a.dp(16), statusBarHeight() + a.dp(10), a.dp(16), 0);
        contentContainer.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // mkoai 2.0.38: 片庫 is now 收藏 — favorites only. History moved
        // to the home top-bar button; downloads stay on the detail page.
        TextView pageTitle = a.text("收藏", 22, Color.WHITE);
        pageTitle.setTypeface(null, Typeface.BOLD);
        pageTitle.setPadding(0, 0, 0, a.dp(10));
        page.addView(pageTitle);

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        final LinearLayout listBody = a.vertical();
        listBody.setPadding(0, 0, 0, a.dp(106));
        sv.addView(listBody, new ScrollView.LayoutParams(-1, -2));

        final Runnable renderFavorites =
                new Runnable() {
                    @Override
                    public void run() {
                        listBody.removeAllViews();
                        List<Favorites.Entry> favs =
                                Favorites.read(a.prefs.getString(Prefs.FAVORITES, "[]"));
                        if (favs.isEmpty()) {
                            renderCenteredHint(listBody, "還沒有收藏任何影片");
                            return;
                        }
                        for (final Favorites.Entry f : favs) {
                            LinearLayout row = a.row();
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(a.dp(10), a.dp(10), a.dp(12), a.dp(10));
                            row.setBackground(
                                    glassBg(
                                            0xb81b2320,
                                            0xb8131917,
                                            a.dp(16),
                                            a.dp(0.7f),
                                            0x26ffffff));
                            MobileDetailScreen.bindTouchFeedback(row);

                            ImageView thumb = new ImageView(a);
                            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            thumb.setBackground(a.bg(0xff232b28, 12));
                            thumb.setClipToOutline(true);
                            LinearLayout.LayoutParams thumbLp =
                                    new LinearLayout.LayoutParams(a.dp(52), a.dp(74));
                            thumbLp.rightMargin = a.dp(12);
                            row.addView(thumb, thumbLp);
                            if (f.poster != null && !f.poster.isEmpty())
                                a.poster(f.poster, thumb);

                            LinearLayout info = a.vertical();
                            TextView t = a.text(f.title, 15, MainActivity.WHITE);
                            t.setTypeface(null, Typeface.BOLD);
                            t.setSingleLine(true);
                            t.setEllipsize(TextUtils.TruncateAt.END);
                            info.addView(t);

                            TextView sub = a.text("已收藏 · 點選檢視詳情", 12, MainActivity.MUTED);
                            sub.setPadding(0, a.dp(5), 0, 0);
                            info.addView(sub);
                            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                            TextView unfav = a.text("移除", 12, 0xffef4444);
                            unfav.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
                            MobileDetailScreen.bindTouchFeedback(unfav);
                            unfav.setOnClickListener(
                                    v -> {
                                        a.prefs
                                                .edit()
                                                .putString(
                                                        Prefs.FAVORITES,
                                                        Favorites.toggle(
                                                                a.prefs.getString(
                                                                        Prefs.FAVORITES, "[]"),
                                                                f.site,
                                                                f.video()))
                                                .apply();
                                        run();
                                    });
                            row.addView(unfav);

                            row.setOnClickListener(
                                    v -> {
                                        Cms.Site targetSite = null;
                                        if (a.config != null) {
                                            for (Cms.Site s : a.config.sites) {
                                                if (s.key.equals(f.site)) {
                                                    targetSite = s;
                                                    break;
                                                }
                                            }
                                        }
                                        if (targetSite == null) {
                                            a.toast("原片源不在當前訂閱中，已自動全網搜尋該影片");
                                            openSearchTab(a, f.title);
                                            return;
                                        }
                                        final Cms.Site resolvedSite = targetSite;
                                        Cms.Video video = f.video();
                                        a.openListing(resolvedSite, video);
                                    });

                            LinearLayout.LayoutParams lp =
                                    new LinearLayout.LayoutParams(-1, -2);
                            lp.bottomMargin = a.dp(8);
                            listBody.addView(row, lp);
                        }
                    }
                };

        renderFavorites.run();
    }


    // =========================================================================
    // TAB 4: MOBILE SETTINGS (2-Level Clean Architecture, Zero Emojis, Zero Ghosting)
    // =========================================================================
    private void renderSettingsTab() {
        renderSettingsTabImpl();
    }

    private void renderSettingsTabImpl() {
        MobileIosSettings.render(a, contentContainer, currentSite, this);
    }

    void refreshSettingsPage(int slideDirection) {
        if (activeTab == 4 && contentContainer != null) {
            MobileIosSettings.render(a, contentContainer, currentSite, this, slideDirection);
        }
    }

    @SuppressWarnings("unused")
    private void renderLegacySettingsTabUnused() {
        contentContainer.removeAllViews();

        final ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout body = a.vertical();
        body.setPadding(a.dp(16), statusBarHeight() + a.dp(14), a.dp(16), a.dp(108));
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));

        populateSettingsBody(body, sv);
    }

    private void populateSettingsBody(final LinearLayout body, final ScrollView sv) {
        final int savedScrollY = sv.getScrollY();
        body.removeAllViews();

        final Runnable refreshInPlace =
                () -> {
                    populateSettingsBody(body, sv);
                    body.invalidate();
                    sv.invalidate();
                    if (contentContainer != null) contentContainer.invalidate();
                    if (a.root != null) a.root.invalidate();
                };

        if (settingsSubPage != 0) {
            LinearLayout topNav = a.row();
            topNav.setGravity(Gravity.CENTER_VERTICAL);
            topNav.setPadding(0, 0, 0, a.dp(14));

            TextView backBtn = a.text("‹", 22, Color.WHITE);
            backBtn.setGravity(Gravity.CENTER);
            backBtn.setBackground(
                    glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff));
            MobileDetailScreen.bindTouchFeedback(backBtn);
            backBtn.setOnClickListener(
                    v -> {
                        settingsSubPage = 0;
                        sv.scrollTo(0, 0);
                        refreshInPlace.run();
                    });
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
            backLp.rightMargin = a.dp(10);
            topNav.addView(backBtn, backLp);

            String subTitleText =
                    settingsSubPage == 1
                            ? "片源與直播配置"
                            : settingsSubPage == 2
                                    ? "播放、解碼與字幕"
                                    : settingsSubPage == 3 ? "介面外觀與偏好" : "備份、更新與診斷";
            TextView subTitle = a.text(subTitleText, 20, Color.WHITE);
            subTitle.setTypeface(null, Typeface.BOLD);
            topNav.addView(subTitle, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(topNav, new LinearLayout.LayoutParams(-1, -2));

            if (settingsSubPage == 1) {
                addSectionHeader(body, "配置與直播源匯入");
                addSettingCard(
                        body,
                        SettingIconView.ICON_CLIPBOARD,
                        "從剪貼簿一鍵貼上配置",
                        "直接讀取手機剪貼簿中的 JSON、JS、PY、TXT/M3U 或配置網址",
                        () -> pasteConfigFromClipboard(a));
                addSettingCard(
                        body,
                        SettingIconView.ICON_LINK_CODE,
                        "輸入或修改配置網址與原始碼",
                        a.configOrigin.isEmpty()
                                ? "支援 JSON / JS / PY / TXT / M3U / ZIP 地址"
                                : a.configOrigin,
                        () -> showMobileImportDialog(a));
                addSettingCard(
                        body,
                        SettingIconView.ICON_FOLDER_FILE,
                        "選擇本地配置或本地包",
                        "支援匯入 .json / .js / .py / .txt / .m3u / .zip 本地檔案",
                        a::pickConfig);
                String savedLiveUrl = a.prefs.getString(Prefs.LIVE_URL, "");
                addSettingCard(
                        body,
                        SettingIconView.ICON_LIVE_TV,
                        "匯入或管理電視直播源",
                        savedLiveUrl.isEmpty()
                                ? "支援 M3U、TXT (#genre#)、JSON 及本地直播檔案"
                                : "當前直播源：" + savedLiveUrl,
                        () -> SourceController.showLiveImportDialog(a));

                a.gap(body, 18);
                addSectionHeader(body, "片源選擇與擴充套件引擎");
                addSettingCard(
                        body,
                        SettingIconView.ICON_SWITCH_SOURCE,
                        "切換預設片源",
                        currentSite == null ? "未選擇" : "當前：" + currentSite.name,
                        a::chooseSources);
                final boolean cspEnabled = a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_PLUGIN_EXT,
                        "啟用第三方擴充套件片源 (JAR / JS / PY)",
                        "已開啟 · 允許執行配置源中的 JAR、JS 與 PY 擴充套件",
                        "已關閉 · 僅使用內建標準解析",
                        cspEnabled,
                        next -> {
                            a.prefs.edit().putBoolean(Prefs.EXPERIMENTAL_CSP, next).apply();
                            a.closeEngines();
                        });
            } else if (settingsSubPage == 2) {
                addSectionHeader(body, "播放器與解碼器");
                final int engine = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
                final String[] engineNames = {
                    "Exo 播放器（Media3 · 4K HDR 與字幕增強）",
                    "IJK 相容模式（Media3 + FFmpeg · 直播與特殊封裝最佳化）",
                    "Android 系統播放器"
                };
                final int[] engineVals = {0, 2, 1};
                final int curEngIdx = engine == 2 ? 1 : (engine == 1 ? 2 : 0);
                addSettingCard(
                        body,
                        SettingIconView.ICON_ENGINE_CORE,
                        "播放器核心",
                        engineNames[curEngIdx],
                        () ->
                                showDrawerPicker(
                                        a,
                                        "播放器核心",
                                        "作為後續播放的預設核心，播放頁內也可隨時切換",
                                        engineNames,
                                        curEngIdx,
                                        idx -> {
                                            if (idx >= 0 && idx < engineVals.length) {
                                                a.prefs
                                                        .edit()
                                                        .putInt(
                                                                Prefs.PLAYER_ENGINE,
                                                                engineVals[idx])
                                                        .apply();
                                                refreshInPlace.run();
                                            }
                                        }));
                final int decoder = a.prefs.getInt(Prefs.DECODER_MODE, 0);
                final String[] decNames = {"自動選擇（推薦）", "硬體解碼優先 (HW)", "軟體解碼優先 (SW)"};
                addSettingCard(
                        body,
                        SettingIconView.ICON_DECODER_CHIP,
                        "影片解碼方式",
                        decNames[Math.max(0, Math.min(2, decoder))],
                        () ->
                                showDrawerPicker(
                                        a,
                                        "影片解碼方式",
                                        "高位元速率 4K 影片建議使用自動或硬體解碼",
                                        decNames,
                                        Math.max(0, Math.min(2, decoder)),
                                        idx -> {
                                            a.prefs.edit().putInt(Prefs.DECODER_MODE, idx).apply();
                                            refreshInPlace.run();
                                        }));
                final boolean tunnel = a.prefs.getBoolean(Prefs.TUNNELED_PLAYBACK, false);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_HDR_TUNNEL,
                        "4K HDR 隧道模式",
                        "已開啟 · 硬體音影片隧道直通",
                        "已關閉 · 預設相容渲染模式",
                        tunnel,
                        next -> a.prefs.edit().putBoolean(Prefs.TUNNELED_PLAYBACK, next).apply());
                final boolean autoNext = a.prefs.getBoolean(Prefs.AUTO_NEXT, true);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_AUTO_NEXT,
                        "自動連播下一集",
                        "已開啟 · 當前劇集播放完畢自動切換下一集",
                        "已關閉 · 單集播放完畢後停止",
                        autoNext,
                        next -> a.prefs.edit().putBoolean(Prefs.AUTO_NEXT, next).apply());

                a.gap(body, 18);
                addSectionHeader(body, "片頭片尾與字幕樣式");
                int introSec = a.prefs.getInt(Prefs.SKIP_INTRO_SECONDS, 0);
                int outroSec = a.prefs.getInt(Prefs.SKIP_OUTRO_SECONDS, 0);
                String skipDesc =
                        (introSec > 0
                                        ? "片頭 " + PlaybackController.formatTime(introSec * 1000)
                                        : "片頭關閉")
                                + " · "
                                + (outroSec > 0
                                        ? "片尾 " + PlaybackController.formatTime(outroSec * 1000)
                                        : "片尾關閉");
                addSettingCard(
                        body,
                        SettingIconView.ICON_SKIP_TIMELINE,
                        "片頭片尾跳過時間節點",
                        skipDesc + "（支援手動拖動滑塊精準設定）",
                        () ->
                                PlaybackControls.showSkipAdjuster(
                                        a, true, introSec, 0, refreshInPlace));

                int subSize = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE_SP, 45));
                int subWeight =
                        Prefs.clampSubtitleWeight(a.prefs.getInt(Prefs.SUBTITLE_WEIGHT, 50));
                int subBg = Prefs.clampSubtitleBgStyle(a.prefs.getInt(Prefs.SUBTITLE_BG_STYLE, 0));
                addSettingCard(
                        body,
                        SettingIconView.ICON_SUBTITLE,
                        "全屏字幕觸控除錯（大小、粗細、位置與樣式）",
                        "字號 "
                                + subSize
                                + " · 粗細 "
                                + subWeight
                                + " · "
                                + SubtitleOverlayView.bgStyleLabel(subBg)
                                + "（雙指縮放 · 按住拖拽 · 中軸線指引）",
                        () -> PlaybackControls.showSubtitleAdjuster(a, null, refreshInPlace));
            } else if (settingsSubPage == 3) {
                addSectionHeader(body, "介面顯示與互動");
                final boolean showHeroBanner = a.prefs.getBoolean(Prefs.SHOW_HERO_BANNER, true);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_HERO_CARD,
                        "首頁推薦卡片",
                        "已開啟 · 在首頁頂部展示今日首推巨幕卡片",
                        "已關閉 · 隱藏首頁頂部推薦卡片，直接展示海報牆",
                        showHeroBanner,
                        next -> a.prefs.edit().putBoolean(Prefs.SHOW_HERO_BANNER, next).apply());
                final int scale = a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100);
                final int[] scaleVals = {90, 100, 110, 120};
                final String[] scaleLabels = {
                    "緊湊 · 90%", "標準 · 100%（預設）", "放大 · 110%", "特大 · 120%"
                };
                int curScaleIdx = 1;
                for (int i = 0; i < scaleVals.length; i++) {
                    if (scaleVals[i] == scale) curScaleIdx = i;
                }
                final int finalScaleIdx = curScaleIdx;
                addSettingCard(
                        body,
                        SettingIconView.ICON_UI_SCALE,
                        "介面整體縮放比例",
                        "當前：" + scaleLabels[finalScaleIdx],
                        () ->
                                showDrawerPicker(
                                        a,
                                        "介面整體縮放比例",
                                        "調整按鈕、文字與卡片整體顯示比例",
                                        scaleLabels,
                                        finalScaleIdx,
                                        idx -> {
                                            a.prefs
                                                    .edit()
                                                    .putInt(Prefs.UI_SCALE_PERCENT, scaleVals[idx])
                                                    .apply();
                                            a.recreate();
                                        }));
                final int navScalePct =
                        Prefs.clampNavScale(a.prefs.getInt(Prefs.NAV_SCALE_PERCENT, 100));
                final int[] navVals = {85, 100, 115, 130, 140};
                final String[] navLabels = {
                    "緊湊 · 85%", "標準 · 100%（預設）", "偏大 · 115%", "大號 · 130%", "特大 · 140%"
                };
                int curNavIdx = 1;
                for (int i = 0; i < navVals.length; i++) {
                    if (navVals[i] == navScalePct) curNavIdx = i;
                }
                final int finalNavIdx = curNavIdx;
                addSettingCard(
                        body,
                        SettingIconView.ICON_NAV_FONT,
                        "導航欄字型與按鈕大小",
                        "當前：" + navLabels[finalNavIdx] + "（自適應排版）",
                        () ->
                                showDrawerPicker(
                                        a,
                                        "導航欄字型與按鈕大小",
                                        "調整導航欄文字與按鈕尺寸，排版自動適應",
                                        navLabels,
                                        finalNavIdx,
                                        idx -> {
                                            a.prefs
                                                    .edit()
                                                    .putInt(Prefs.NAV_SCALE_PERCENT, navVals[idx])
                                                    .apply();
                                            a.recreate();
                                        }));
                final boolean reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
                final boolean reducedTransparency =
                        a.prefs.getBoolean(Prefs.REDUCED_TRANSPARENCY, false);
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_GLASS_BLUR,
                        "減少透明效果",
                        "已開啟 · 使用高對比實色面板",
                        "已關閉 · 使用背景液態毛玻璃材質",
                        reducedTransparency,
                        next -> {
                            a.prefs.edit().putBoolean(Prefs.REDUCED_TRANSPARENCY, next).apply();
                            com.pilipala.glass.GlassworkSurface.invalidateBackdrop(a.root);
                        });
                addSettingToggleCard(
                        body,
                        SettingIconView.ICON_MOTION_SPARK,
                        "精簡介面動畫",
                        "已開啟 · 減少過渡動畫，提升響應速度",
                        "已關閉 · 保留完整流暢動效",
                        reduced,
                        next -> {
                            a.reduced = next;
                            a.prefs.edit().putBoolean(Prefs.REDUCED, next).apply();
                        });
                addSettingCard(
                        body,
                        SettingIconView.ICON_TRASH_CLEAR,
                        "清空搜尋歷史記錄",
                        "清除搜尋頁儲存的全部歷史關鍵詞",
                        () ->
                                showDrawerPicker(
                                        a,
                                        "清空搜尋歷史記錄",
                                        "確認清除搜尋頁儲存的全部歷史關鍵詞？",
                                        new String[] {"確認清空全部搜尋歷史", "取消"},
                                        1,
                                        idx -> {
                                            if (idx == 0) {
                                                a.prefs
                                                        .edit()
                                                        .putString(
                                                                Prefs.GLOBAL_SEARCH_HISTORY, "[]")
                                                        .apply();
                                                cachedSearchKeyword = "";
                                                cachedSearchStatus = "";
                                                cachedSearchHits.clear();
                                                a.toast("已清空搜尋歷史記錄");
                                            }
                                        }));
            } else {
                addSectionHeader(body, "更新、備份與診斷");
                addSettingCard(
                        body,
                        SettingIconView.ICON_UPDATE_CLOUD,
                        "檢查軟體更新",
                        "當前版本：" + UpdateManager.installedVersion(a) + " · 正式版",
                        () ->
                                a.open(
                                        new MainActivity.Screen(() -> new UpdateScreen(a).show()),
                                        true));
                addSettingCard(
                        body,
                        SettingIconView.ICON_DIAGNOSTIC_LOG,
                        "檢視執行診斷日誌",
                        "排查片源網路請求、代理與播放解碼日誌",
                        () -> new SettingsScreen(a).diagnostics());
                addSettingCard(
                        body,
                        SettingIconView.ICON_BACKUP_SAFE,
                        "完整高階設定與備份恢復",
                        "配置備份匯出、恢復及完整系統引數頁面",
                        () ->
                                a.open(
                                        new MainActivity.Screen(() -> new SettingsScreen(a).show()),
                                        true));
            }
            if (savedScrollY > 0) {
                sv.scrollTo(0, savedScrollY);
            }
            return;
        }

        // =====================================================================
        // LEVEL 1: CLEAN 5-CARD PRIMARY SETTINGS MENU (Zero Emojis)
        // =====================================================================
        TextView pageTitle = a.text("設定", 24, Color.WHITE);
        pageTitle.setTypeface(null, Typeface.BOLD);
        body.addView(pageTitle);

        a.gap(body, 18);
        final String[] categories = {"片源與直播配置", "播放、解碼與字幕", "介面外觀與偏好", "備份、更新與診斷"};
        final int[] icons = {1, 3, 4, 5};
        for (int i = 0; i < categories.length; i++) {
            final int page = i + 1;
            addSettingCard(
                    body,
                    icons[i],
                    categories[i],
                    "",
                    () -> {
                        settingsSubPage = page;
                        sv.scrollTo(0, 0);
                        refreshInPlace.run();
                    });
        }
        // mkoai: hidden when the user turns on 「隱藏網盤相關功能」.
        if (!a.prefs.getBoolean(Prefs.HIDE_NETDISK, false))
            addSettingCard(body, 2, "網盤與賬號授權", "", () -> AccountPanel.show(a, null));
    }

    private void addSectionHeader(LinearLayout parent, String title) {
        TextView h = a.text(title, 12.5f, 0xff5ac8fa);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(10));
        parent.addView(h);
    }

    interface BooleanAction {
        void onToggle(boolean checked);
    }

    static void bindCardTouchFeedback(
            MainActivity a,
            View card,
            android.graphics.drawable.Drawable normalBg,
            android.graphics.drawable.Drawable pressedBg) {
        if (card == null) return;
        card.setOnTouchListener(
                (v, ev) -> {
                    int act = ev.getActionMasked();
                    if (act == android.view.MotionEvent.ACTION_DOWN) {
                        if (pressedBg != null) v.setBackground(pressedBg);
                        if (!a.reduced) {
                            v.animate().cancel();
                            v.animate().scaleX(0.986f).scaleY(0.986f).setDuration(80).start();
                        }
                    } else if (act == android.view.MotionEvent.ACTION_UP
                            || act == android.view.MotionEvent.ACTION_CANCEL) {
                        if (normalBg != null) v.setBackground(normalBg);
                        if (!a.reduced) {
                            v.animate().cancel();
                            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        }
                    }
                    return false;
                });
    }

    private void addSettingCard(
            LinearLayout parent, int iconKind, String title, String subtitle, Runnable action) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        GradientDrawable normalBg =
                glassBg(0xb81b2320, 0xb8131917, a.dp(20), a.dp(0.7f), 0x26ffffff);
        GradientDrawable pressedBg =
                glassBg(0xd825322d, 0xd81b2420, a.dp(20), a.dp(0.9f), 0x48ffffff);
        row.setBackground(normalBg);
        bindCardTouchFeedback(a, row, normalBg, pressedBg);
        row.setOnClickListener(v -> action.run());

        SettingIconView icon = new SettingIconView(a, iconKind);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);

        LinearLayout textCol = a.vertical();
        TextView t = a.text(title, 15f, MainActivity.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        textCol.addView(t);

        TextView s = a.text(subtitle, 12.5f, MainActivity.MUTED);
        s.setPadding(0, a.dp(4), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        if (!subtitle.isEmpty()) textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.text("›", 20, MainActivity.MUTED));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(15);
        parent.addView(row, lp);
    }

    private void addSettingToggleCard(
            LinearLayout parent,
            int iconKind,
            String title,
            final String onSubtitle,
            final String offSubtitle,
            boolean initialChecked,
            final BooleanAction onToggle) {
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        GradientDrawable normalBg =
                glassBg(0xb81b2320, 0xb8131917, a.dp(20), a.dp(0.7f), 0x26ffffff);
        GradientDrawable pressedBg =
                glassBg(0xd825322d, 0xd81b2420, a.dp(20), a.dp(0.9f), 0x48ffffff);
        row.setBackground(normalBg);
        bindCardTouchFeedback(a, row, normalBg, pressedBg);

        SettingIconView icon = new SettingIconView(a, iconKind);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);

        LinearLayout textCol = a.vertical();
        TextView t = a.text(title, 15f, MainActivity.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        textCol.addView(t);

        final TextView s =
                a.text(initialChecked ? onSubtitle : offSubtitle, 12.5f, MainActivity.MUTED);
        s.setPadding(0, a.dp(4), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

        final IosSwitchView toggleSwitch = new IosSwitchView(a, initialChecked);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(a.dp(50), a.dp(30));
        switchLp.leftMargin = a.dp(12);
        row.addView(toggleSwitch, switchLp);

        row.setOnClickListener(
                v -> {
                    boolean next = !toggleSwitch.isChecked();
                    toggleSwitch.setChecked(next, !a.reduced);
                    s.setText(next ? onSubtitle : offSubtitle);
                    if (onToggle != null) onToggle.onToggle(next);
                });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(15);
        parent.addView(row, lp);
    }

    /** iOS-style capsule toggle switch (`50dp × 30dp`, emerald/iOS-green track + sliding white thumb). */
    static final class IosSwitchView extends View {
        private final MainActivity a;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        private float progress;
        private android.animation.ValueAnimator animator;

        IosSwitchView(MainActivity activity, boolean initialChecked) {
            super(activity);
            this.a = activity;
            this.checked = initialChecked;
            this.progress = initialChecked ? 1f : 0f;
            setClickable(false);
            setFocusable(false);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean next, boolean animate) {
            this.checked = next;
            float target = next ? 1f : 0f;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animate || Math.abs(progress - target) < 0.01f) {
                progress = target;
                invalidate();
                return;
            }
            animator = android.animation.ValueAnimator.ofFloat(progress, target);
            animator.setDuration(160);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animator.addUpdateListener(
                    anim -> {
                        progress = (Float) anim.getAnimatedValue();
                        invalidate();
                    });
            animator.start();
        }

        private static int blendColor(int c1, int c2, float ratio) {
            float r = Math.max(0f, Math.min(1f, ratio));
            int a1 = (c1 >>> 24) & 0xff,
                    r1 = (c1 >> 16) & 0xff,
                    g1 = (c1 >> 8) & 0xff,
                    b1 = c1 & 0xff;
            int a2 = (c2 >>> 24) & 0xff,
                    r2 = (c2 >> 16) & 0xff,
                    g2 = (c2 >> 8) & 0xff,
                    b2 = c2 & 0xff;
            int oa = Math.round(a1 + (a2 - a1) * r);
            int or = Math.round(r1 + (r2 - r1) * r);
            int og = Math.round(g1 + (g2 - g1) * r);
            int ob = Math.round(b1 + (b2 - b1) * r);
            return (oa << 24) | (or << 16) | (og << 8) | ob;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float radius = h / 2f;
            int trackColor = blendColor(0x58787880, 0xffe34b58, progress); // mkoai 2.0.39: coral on-state (mkoai accent; was iOS green)
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(trackColor);
            RectF trackRect = new RectF(0, 0, w, h);
            c.drawRoundRect(trackRect, radius, radius, paint);

            if (progress < 0.95f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, a.dp(0.8f)));
                paint.setColor(blendColor(0x2effffff, 0x00ffffff, progress));
                float inset = paint.getStrokeWidth() / 2f;
                c.drawRoundRect(
                        new RectF(inset, inset, w - inset, h - inset), radius, radius, paint);
            }

            float pad = a.dp(2f);
            float thumbRadius = (h - pad * 2f) / 2f;
            float minCx = pad + thumbRadius;
            float maxCx = w - pad - thumbRadius;
            float cx = minCx + (maxCx - minCx) * progress;
            float cy = h / 2f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33000000);
            c.drawCircle(cx, cy + a.dp(0.8f), thumbRadius, paint);

            paint.setColor(Color.WHITE);
            c.drawCircle(cx, cy, thumbRadius, paint);
        }
    }

    /**
     * Semantic vector icon badge for Settings cards (`38dp × 38dp` rounded-square tinted badge +
     * bespoke vector glyph per setting item).
     */
    static final class SettingIconView extends View {
        // Level-1 Primary Categories
        static final int ICON_SOURCES = 1;
        static final int ICON_NETDISK = 2;
        static final int ICON_PLAYBACK = 3;
        static final int ICON_APPEARANCE = 4;
        static final int ICON_SYSTEM = 5;

        // Subpage 1: 片源与直播配置
        static final int ICON_CLIPBOARD = 11;
        static final int ICON_LINK_CODE = 12;
        static final int ICON_FOLDER_FILE = 13;
        static final int ICON_LIVE_TV = 14;
        static final int ICON_SWITCH_SOURCE = 15;
        static final int ICON_PLUGIN_EXT = 16;

        // Subpage 2: 播放、解码与字幕
        static final int ICON_ENGINE_CORE = 21;
        static final int ICON_DECODER_CHIP = 22;
        static final int ICON_HDR_TUNNEL = 23;
        static final int ICON_AUTO_NEXT = 24;
        static final int ICON_SKIP_TIMELINE = 25;
        static final int ICON_SUBTITLE = 26;

        // Subpage 3: 界面外观与偏好
        static final int ICON_UI_SCALE = 31;
        static final int ICON_NAV_FONT = 32;
        static final int ICON_GLASS_BLUR = 33;
        static final int ICON_MOTION_SPARK = 34;
        static final int ICON_TRASH_CLEAR = 35;
        static final int ICON_HERO_CARD = 36;

        // Subpage 4: 备份、更新与诊断
        static final int ICON_UPDATE_CLOUD = 41;
        static final int ICON_DIAGNOSTIC_LOG = 42;
        static final int ICON_BACKUP_SAFE = 43;

        // Additional TV / Advanced Settings Icons
        static final int ICON_WALLPAPER = 51;
        static final int ICON_GRID_COLS = 52;
        static final int ICON_AUDIO_WAVE = 53;
        static final int ICON_ASPECT_RATIO = 54;
        static final int ICON_SPEED_GAUGE = 55;
        static final int ICON_REPEAT_LOOP = 56;

        private final MainActivity a;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SettingIconView(MainActivity activity, int kind) {
            super(activity);
            this.a = activity;
            this.kind = kind;
        }

        static int kindForSetting(String key, String title) {
            String k = key == null ? "" : key;
            String t = title == null ? "" : title;
            if (k.contains("settings-appearance")) return ICON_APPEARANCE;
            if (k.contains("settings-playback")) return ICON_PLAYBACK;
            if (k.contains("settings-data")) return ICON_SOURCES;
            if (k.contains("settings-updates")) return ICON_UPDATE_CLOUD;
            if (k.contains("settings-accounts") || t.contains("网盘")) return ICON_NETDISK;
            if (k.contains("settings-live-import") || t.contains("直播")) return ICON_LIVE_TV;
            if (k.contains("settings-import") || t.contains("配置源")) return ICON_LINK_CODE;
            if (k.contains("settings-depots") || t.contains("线路") || t.contains("切换"))
                return ICON_SWITCH_SOURCE;
            if (k.contains(Prefs.EXPERIMENTAL_CSP) || t.contains("扩展")) return ICON_PLUGIN_EXT;
            if (k.contains("settings-backup")
                    || k.contains("settings-restore")
                    || t.contains("备份")
                    || t.contains("还原")) return ICON_BACKUP_SAFE;
            if (k.contains("settings-logs") || t.contains("日志")) return ICON_DIAGNOSTIC_LOG;
            if (k.contains("clear-history") || t.contains("清除") || t.contains("清空"))
                return ICON_TRASH_CLEAR;
            if (k.contains("settings-history")
                    || k.contains("clear-images")
                    || k.contains("settings-reset")
                    || k.contains(Prefs.REPEAT_MODE)
                    || t.contains("循环")
                    || t.contains("历史")
                    || t.contains("恢复")) return ICON_REPEAT_LOOP;
            if (k.contains(Prefs.SHOW_HERO_BANNER)
                    || t.contains("推荐卡片")
                    || t.contains("首推")
                    || t.contains("巨幕")) return ICON_HERO_CARD;
            if (k.contains(Prefs.UI_SCALE_PERCENT) || t.contains("界面大小") || t.contains("缩放"))
                return ICON_UI_SCALE;
            if (k.contains(Prefs.NAV_SCALE_PERCENT) || t.contains("导航栏")) return ICON_NAV_FONT;
            if (k.contains(Prefs.HOME_COLUMNS) || t.contains("每行海报")) return ICON_GRID_COLS;
            if (k.contains("wallpaper")
                    || k.contains(Prefs.HOME_AMBIENT)
                    || t.contains("壁纸")
                    || t.contains("图片")
                    || t.contains("柔光")
                    || t.contains("压暗")) return ICON_WALLPAPER;
            if (k.contains(Prefs.REDUCED_TRANSPARENCY) || t.contains("透明")) return ICON_GLASS_BLUR;
            if (k.contains(Prefs.REDUCED)
                    || k.contains(Prefs.STARTUP_ANIMATION)
                    || t.contains("动画")
                    || t.contains("动态")) return ICON_MOTION_SPARK;
            if (k.contains(Prefs.VIDEO_OUTPUT)
                    || k.contains(Prefs.TUNNELED_PLAYBACK)
                    || t.contains("HDR")
                    || t.contains("画面输出")) return ICON_HDR_TUNNEL;
            if (k.contains(Prefs.PLAYER_ENGINE) || t.contains("播放器")) return ICON_ENGINE_CORE;
            if (k.contains(Prefs.DECODER_MODE) || k.contains("decoders") || t.contains("解码"))
                return ICON_DECODER_CHIP;
            if (k.contains(Prefs.SOFTWARE_AUDIO)
                    || k.contains(Prefs.PREFER_AAC)
                    || k.contains(Prefs.AUDIO_EFFECT)
                    || t.contains("音轨")
                    || t.contains("音效")
                    || t.contains("音频")) return ICON_AUDIO_WAVE;
            if (k.contains(Prefs.ASPECT)
                    || k.contains(Prefs.PICTURE_EFFECT)
                    || t.contains("画面比例")
                    || t.contains("色彩")) return ICON_ASPECT_RATIO;
            if (k.contains(Prefs.PLAY_SPEED)
                    || k.contains(Prefs.BUFFER_SECONDS)
                    || k.contains(Prefs.SEARCH_THREADS)
                    || k.contains(Prefs.SEARCH_TIMEOUT)
                    || t.contains("速度")
                    || t.contains("缓冲")) return ICON_SPEED_GAUGE;
            if (k.contains(Prefs.AUTO_NEXT) || t.contains("下一集")) return ICON_AUTO_NEXT;
            if (k.contains(Prefs.SKIP_INTRO) || k.contains(Prefs.SKIP_OUTRO) || t.contains("跳过片"))
                return ICON_SKIP_TIMELINE;
            if (k.contains(Prefs.SUBTITLE_SIZE)
                    || k.contains(Prefs.SUBTITLE_STYLE)
                    || k.contains(Prefs.DANMAKU_DEFAULT)
                    || t.contains("字幕")
                    || t.contains("弹幕")) return ICON_SUBTITLE;
            return ICON_SOURCES;
        }

        int iconKind() {
            return kind;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f, cy = h / 2f;
            int badgeBg;
            int strokeColor;
            switch (kind) {
                case ICON_SOURCES:
                case ICON_CLIPBOARD:
                case ICON_DECODER_CHIP:
                case ICON_GLASS_BLUR:
                    badgeBg = 0x2606b6d4;
                    strokeColor = 0xff22d3ee;
                    break;
                case ICON_NETDISK:
                case ICON_PLUGIN_EXT:
                case ICON_AUTO_NEXT:
                case ICON_REPEAT_LOOP:
                case ICON_HERO_CARD:
                    badgeBg = 0x2610b981;
                    strokeColor = 0xffe34b58;
                    break;
                case ICON_PLAYBACK:
                case ICON_ENGINE_CORE:
                case ICON_SPEED_GAUGE:
                    badgeBg = 0x26f59e0b;
                    strokeColor = 0xfffbbf24;
                    break;
                case ICON_FOLDER_FILE:
                case ICON_SKIP_TIMELINE:
                    badgeBg = 0x26f97316;
                    strokeColor = 0xfffb923c;
                    break;
                case ICON_APPEARANCE:
                case ICON_HDR_TUNNEL:
                case ICON_UI_SCALE:
                case ICON_GRID_COLS:
                    badgeBg = 0x26a855f7;
                    strokeColor = 0xffc084fc;
                    break;
                case ICON_NAV_FONT:
                    badgeBg = 0x26ec4899;
                    strokeColor = 0xfff472b6;
                    break;
                case ICON_LIVE_TV:
                case ICON_AUDIO_WAVE:
                    badgeBg = 0x26f43f5e;
                    strokeColor = 0xfffb7185;
                    break;
                case ICON_TRASH_CLEAR:
                    badgeBg = 0x26ef4444;
                    strokeColor = 0xfff87171;
                    break;
                case ICON_MOTION_SPARK:
                    badgeBg = 0x26eab308;
                    strokeColor = 0xfffacc15;
                    break;
                case ICON_DIAGNOSTIC_LOG:
                case ICON_WALLPAPER:
                    badgeBg = 0x2614b8a6;
                    strokeColor = 0xff2dd4bf;
                    break;
                case ICON_SWITCH_SOURCE:
                case ICON_BACKUP_SAFE:
                    badgeBg = 0x266366f1;
                    strokeColor = 0xff818cf8;
                    break;
                case ICON_SUBTITLE:
                case ICON_ASPECT_RATIO:
                    badgeBg = 0x260ea5e9;
                    strokeColor = 0xff38bdf8;
                    break;
                case ICON_SYSTEM:
                case ICON_LINK_CODE:
                case ICON_UPDATE_CLOUD:
                default:
                    badgeBg = 0x263b82f6;
                    strokeColor = 0xff60a5fa;
                    break;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(badgeBg);
            c.drawRoundRect(new RectF(0, 0, w, h), a.dp(10), a.dp(10), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(strokeColor);
            paint.setStrokeWidth(a.dp(1.65f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            switch (kind) {
                case ICON_SOURCES:
                    {
                        // 3D stacked layers
                        Path top = new Path();
                        top.moveTo(cx, cy - a.dp(8f));
                        top.lineTo(cx + a.dp(8f), cy - a.dp(3.2f));
                        top.lineTo(cx, cy + a.dp(1.6f));
                        top.lineTo(cx - a.dp(8f), cy - a.dp(3.2f));
                        top.close();
                        c.drawPath(top, paint);
                        c.drawLine(cx - a.dp(8f), cy + a.dp(0.8f), cx, cy + a.dp(5.2f), paint);
                        c.drawLine(cx + a.dp(8f), cy + a.dp(0.8f), cx, cy + a.dp(5.2f), paint);
                        c.drawLine(cx - a.dp(8f), cy + a.dp(4.4f), cx, cy + a.dp(8.8f), paint);
                        c.drawLine(cx + a.dp(8f), cy + a.dp(4.4f), cx, cy + a.dp(8.8f), paint);
                        break;
                    }
                case ICON_NETDISK:
                    {
                        // Cloud drive with upward sync arrow
                        RectF base =
                                new RectF(
                                        cx - a.dp(8.2f),
                                        cy - a.dp(1.5f),
                                        cx + a.dp(8.2f),
                                        cy + a.dp(6.8f));
                        c.drawRoundRect(base, a.dp(4.2f), a.dp(4.2f), paint);
                        c.drawArc(
                                new RectF(
                                        cx - a.dp(5.2f),
                                        cy - a.dp(7.5f),
                                        cx + a.dp(5.2f),
                                        cy + a.dp(2.5f)),
                                185,
                                170,
                                false,
                                paint);
                        c.drawLine(cx, cy + a.dp(4.2f), cx, cy - a.dp(1.2f), paint);
                        c.drawLine(cx - a.dp(2.3f), cy + a.dp(1f), cx, cy - a.dp(1.4f), paint);
                        c.drawLine(cx + a.dp(2.3f), cy + a.dp(1f), cx, cy - a.dp(1.4f), paint);
                        break;
                    }
                case ICON_PLAYBACK:
                    {
                        // Cinema play screen
                        RectF scr =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(scr, a.dp(2.8f), a.dp(2.8f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        Path tri = new Path();
                        tri.moveTo(cx - a.dp(2.2f), cy - a.dp(3.2f));
                        tri.lineTo(cx + a.dp(3.4f), cy);
                        tri.lineTo(cx - a.dp(2.2f), cy + a.dp(3.2f));
                        tri.close();
                        c.drawPath(tri, paint);
                        break;
                    }
                case ICON_APPEARANCE:
                    {
                        // Dual tuning sliders
                        float x1 = cx - a.dp(8f), x2 = cx + a.dp(8f);
                        c.drawLine(x1, cy - a.dp(4.5f), x2, cy - a.dp(4.5f), paint);
                        c.drawLine(x1, cy + a.dp(4.5f), x2, cy + a.dp(4.5f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx - a.dp(2.8f), cy - a.dp(4.5f), a.dp(2.4f), paint);
                        c.drawCircle(cx + a.dp(2.8f), cy + a.dp(4.5f), a.dp(2.4f), paint);
                        break;
                    }
                case ICON_CLIPBOARD:
                    {
                        // Clipboard with top clip & lines
                        RectF board =
                                new RectF(
                                        cx - a.dp(6.8f),
                                        cy - a.dp(6.2f),
                                        cx + a.dp(6.8f),
                                        cy + a.dp(8.2f));
                        c.drawRoundRect(board, a.dp(2.2f), a.dp(2.2f), paint);
                        RectF clip =
                                new RectF(
                                        cx - a.dp(3.2f),
                                        cy - a.dp(8.4f),
                                        cx + a.dp(3.2f),
                                        cy - a.dp(4.8f));
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(clip, a.dp(1.4f), a.dp(1.4f), paint);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawLine(
                                cx - a.dp(3.6f),
                                cy - a.dp(0.8f),
                                cx + a.dp(3.6f),
                                cy - a.dp(0.8f),
                                paint);
                        c.drawLine(
                                cx - a.dp(3.6f),
                                cy + a.dp(3.4f),
                                cx + a.dp(2.2f),
                                cy + a.dp(3.4f),
                                paint);
                        break;
                    }
                case ICON_LINK_CODE:
                    {
                        // Code brackets </>
                        c.drawLine(cx - a.dp(3.8f), cy - a.dp(4.5f), cx - a.dp(8.2f), cy, paint);
                        c.drawLine(cx - a.dp(8.2f), cy, cx - a.dp(3.8f), cy + a.dp(4.5f), paint);
                        c.drawLine(cx + a.dp(3.8f), cy - a.dp(4.5f), cx + a.dp(8.2f), cy, paint);
                        c.drawLine(cx + a.dp(8.2f), cy, cx + a.dp(3.8f), cy + a.dp(4.5f), paint);
                        c.drawLine(
                                cx + a.dp(1.8f),
                                cy - a.dp(6.2f),
                                cx - a.dp(1.8f),
                                cy + a.dp(6.2f),
                                paint);
                        break;
                    }
                case ICON_FOLDER_FILE:
                    {
                        // Folder with top tab
                        Path folder = new Path();
                        folder.moveTo(cx - a.dp(8.2f), cy - a.dp(6.8f));
                        folder.lineTo(cx - a.dp(2.2f), cy - a.dp(6.8f));
                        folder.lineTo(cx + a.dp(0.2f), cy - a.dp(4.2f));
                        folder.lineTo(cx + a.dp(8.2f), cy - a.dp(4.2f));
                        folder.lineTo(cx + a.dp(8.2f), cy + a.dp(7.2f));
                        folder.lineTo(cx - a.dp(8.2f), cy + a.dp(7.2f));
                        folder.close();
                        c.drawPath(folder, paint);
                        c.drawLine(
                                cx - a.dp(8f),
                                cy - a.dp(0.8f),
                                cx + a.dp(8f),
                                cy - a.dp(0.8f),
                                paint);
                        break;
                    }
                case ICON_LIVE_TV:
                    {
                        // TV monitor with dual antenna rabbit ears & live dot
                        RectF tv =
                                new RectF(
                                        cx - a.dp(8f),
                                        cy - a.dp(3.8f),
                                        cx + a.dp(8f),
                                        cy + a.dp(7.5f));
                        c.drawRoundRect(tv, a.dp(2.4f), a.dp(2.4f), paint);
                        c.drawLine(cx - a.dp(4.5f), cy - a.dp(8.5f), cx, cy - a.dp(3.8f), paint);
                        c.drawLine(cx + a.dp(4.5f), cy - a.dp(8.5f), cx, cy - a.dp(3.8f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx, cy + a.dp(1.8f), a.dp(2f), paint);
                        break;
                    }
                case ICON_SWITCH_SOURCE:
                    {
                        // Two opposite horizontal swap arrows
                        float lx = cx - a.dp(7.5f), rx = cx + a.dp(7.5f);
                        float ty = cy - a.dp(3.5f), by = cy + a.dp(3.5f);
                        c.drawLine(lx, ty, rx, ty, paint);
                        c.drawLine(rx - a.dp(3f), ty - a.dp(2.6f), rx, ty, paint);
                        c.drawLine(rx - a.dp(3f), ty + a.dp(2.6f), rx, ty, paint);
                        c.drawLine(rx, by, lx, by, paint);
                        c.drawLine(lx + a.dp(3f), by - a.dp(2.6f), lx, by, paint);
                        c.drawLine(lx + a.dp(3f), by + a.dp(2.6f), lx, by, paint);
                        break;
                    }
                case ICON_PLUGIN_EXT:
                    {
                        // 2x2 modular plugin grid with popped top-right module
                        float s = a.dp(5.6f), g = a.dp(1.4f);
                        c.drawRoundRect(
                                new RectF(cx - g - s, cy - g - s, cx - g, cy - g),
                                a.dp(1.5f),
                                a.dp(1.5f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx - g - s, cy + g, cx - g, cy + g + s),
                                a.dp(1.5f),
                                a.dp(1.5f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx + g, cy + g, cx + g + s, cy + g + s),
                                a.dp(1.5f),
                                a.dp(1.5f),
                                paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(
                                new RectF(
                                        cx + g + a.dp(0.8f),
                                        cy - g - s - a.dp(0.8f),
                                        cx + g + s + a.dp(0.8f),
                                        cy - g - a.dp(0.8f)),
                                a.dp(1.5f),
                                a.dp(1.5f),
                                paint);
                        break;
                    }
                case ICON_ENGINE_CORE:
                    {
                        // Play circle core
                        c.drawCircle(cx, cy, a.dp(8.5f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        Path tri = new Path();
                        tri.moveTo(cx - a.dp(2.2f), cy - a.dp(3.4f));
                        tri.lineTo(cx + a.dp(3.6f), cy);
                        tri.lineTo(cx - a.dp(2.2f), cy + a.dp(3.4f));
                        tri.close();
                        c.drawPath(tri, paint);
                        break;
                    }
                case ICON_DECODER_CHIP:
                    {
                        // Hardware CPU/GPU decoder chip with pins
                        RectF chip =
                                new RectF(
                                        cx - a.dp(5.5f),
                                        cy - a.dp(5.5f),
                                        cx + a.dp(5.5f),
                                        cy + a.dp(5.5f));
                        c.drawRoundRect(chip, a.dp(1.8f), a.dp(1.8f), paint);
                        float p = a.dp(2.4f), in = a.dp(5.5f), out = a.dp(8.5f);
                        c.drawLine(cx - p, cy - out, cx - p, cy - in, paint);
                        c.drawLine(cx + p, cy - out, cx + p, cy - in, paint);
                        c.drawLine(cx - p, cy + in, cx - p, cy + out, paint);
                        c.drawLine(cx + p, cy + in, cx + p, cy + out, paint);
                        c.drawLine(cx - out, cy - p, cx - in, cy - p, paint);
                        c.drawLine(cx - out, cy + p, cx - in, cy + p, paint);
                        c.drawLine(cx + in, cy - p, cx + out, cy - p, paint);
                        c.drawLine(cx + in, cy + p, cx + out, cy + p, paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(
                                new RectF(
                                        cx - a.dp(2.2f),
                                        cy - a.dp(2.2f),
                                        cx + a.dp(2.2f),
                                        cy + a.dp(2.2f)),
                                a.dp(0.8f),
                                a.dp(0.8f),
                                paint);
                        break;
                    }
                case ICON_HDR_TUNNEL:
                    {
                        // HDR display with sun & rays
                        RectF scr =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(scr, a.dp(2.5f), a.dp(2.5f), paint);
                        c.drawCircle(cx, cy, a.dp(2.4f), paint);
                        c.drawLine(cx, cy - a.dp(4.6f), cx, cy - a.dp(3.8f), paint);
                        c.drawLine(cx, cy + a.dp(3.8f), cx, cy + a.dp(4.6f), paint);
                        c.drawLine(cx - a.dp(4.8f), cy, cx - a.dp(3.8f), cy, paint);
                        c.drawLine(cx + a.dp(3.8f), cy, cx + a.dp(4.8f), cy, paint);
                        break;
                    }
                case ICON_AUTO_NEXT:
                    {
                        // Fast-forward double-triangle + vertical bar (⏭)
                        paint.setStyle(Paint.Style.FILL);
                        Path t1 = new Path();
                        t1.moveTo(cx - a.dp(7.5f), cy - a.dp(4.5f));
                        t1.lineTo(cx - a.dp(1.2f), cy);
                        t1.lineTo(cx - a.dp(7.5f), cy + a.dp(4.5f));
                        t1.close();
                        c.drawPath(t1, paint);
                        Path t2 = new Path();
                        t2.moveTo(cx - a.dp(1.2f), cy - a.dp(4.5f));
                        t2.lineTo(cx + a.dp(5.2f), cy);
                        t2.lineTo(cx - a.dp(1.2f), cy + a.dp(4.5f));
                        t2.close();
                        c.drawPath(t2, paint);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawLine(
                                cx + a.dp(6.8f),
                                cy - a.dp(4.5f),
                                cx + a.dp(6.8f),
                                cy + a.dp(4.5f),
                                paint);
                        break;
                    }
                case ICON_SKIP_TIMELINE:
                    {
                        // Timeline bar with curved skip-over arc arrow
                        c.drawLine(
                                cx - a.dp(8f), cy + a.dp(5f), cx + a.dp(8f), cy + a.dp(5f), paint);
                        c.drawLine(
                                cx - a.dp(5f),
                                cy + a.dp(2.5f),
                                cx - a.dp(5f),
                                cy + a.dp(7.2f),
                                paint);
                        c.drawLine(
                                cx + a.dp(5f),
                                cy + a.dp(2.5f),
                                cx + a.dp(5f),
                                cy + a.dp(7.2f),
                                paint);
                        c.drawArc(
                                new RectF(
                                        cx - a.dp(6.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(6.5f),
                                        cy + a.dp(3.5f)),
                                195,
                                150,
                                false,
                                paint);
                        c.drawLine(
                                cx + a.dp(3.6f),
                                cy - a.dp(3.2f),
                                cx + a.dp(6.5f),
                                cy - a.dp(1f),
                                paint);
                        c.drawLine(
                                cx + a.dp(3.2f),
                                cy - a.dp(0.4f),
                                cx + a.dp(6.5f),
                                cy - a.dp(1f),
                                paint);
                        break;
                    }
                case ICON_SUBTITLE:
                    {
                        // Subtitle caption frame with bottom caption bars
                        RectF box =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(box, a.dp(2.5f), a.dp(2.5f), paint);
                        c.drawLine(
                                cx - a.dp(5.2f),
                                cy + a.dp(0.5f),
                                cx + a.dp(5.2f),
                                cy + a.dp(0.5f),
                                paint);
                        c.drawLine(
                                cx - a.dp(3.6f),
                                cy + a.dp(3.5f),
                                cx + a.dp(3.6f),
                                cy + a.dp(3.5f),
                                paint);
                        break;
                    }
                case ICON_HERO_CARD:
                    {
                        // Featured showcase card with left poster thumbnail & right title lines
                        RectF card =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(card, a.dp(2.4f), a.dp(2.4f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(
                                new RectF(
                                        cx - a.dp(6f),
                                        cy - a.dp(4f),
                                        cx - a.dp(1.2f),
                                        cy + a.dp(4f)),
                                a.dp(1.2f),
                                a.dp(1.2f),
                                paint);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawLine(
                                cx + a.dp(1.2f),
                                cy - a.dp(2.2f),
                                cx + a.dp(5.8f),
                                cy - a.dp(2.2f),
                                paint);
                        c.drawLine(
                                cx + a.dp(1.2f),
                                cy + a.dp(1.8f),
                                cx + a.dp(4.4f),
                                cy + a.dp(1.8f),
                                paint);
                        break;
                    }
                case ICON_UI_SCALE:
                    {
                        // Four outward expansion corners
                        float d = a.dp(7.5f), s = a.dp(3.4f);
                        c.drawLine(cx - d, cy - d, cx - d + s, cy - d, paint);
                        c.drawLine(cx - d, cy - d, cx - d, cy - d + s, paint);
                        c.drawLine(cx + d, cy - d, cx + d - s, cy - d, paint);
                        c.drawLine(cx + d, cy - d, cx + d, cy - d + s, paint);
                        c.drawLine(cx - d, cy + d, cx - d + s, cy + d, paint);
                        c.drawLine(cx - d, cy + d, cx - d, cy + d - s, paint);
                        c.drawLine(cx + d, cy + d, cx + d - s, cy + d, paint);
                        c.drawLine(cx + d, cy + d, cx + d, cy + d - s, paint);
                        c.drawLine(
                                cx - a.dp(3f),
                                cy - a.dp(3f),
                                cx - d + a.dp(0.6f),
                                cy - d + a.dp(0.6f),
                                paint);
                        c.drawLine(
                                cx + a.dp(3f),
                                cy + a.dp(3f),
                                cx + d - a.dp(0.6f),
                                cy + d - a.dp(0.6f),
                                paint);
                        break;
                    }
                case ICON_NAV_FONT:
                    {
                        // Typography 'A' + nav pill bar
                        c.drawLine(
                                cx - a.dp(3.5f),
                                cy - a.dp(6.5f),
                                cx - a.dp(7.2f),
                                cy + a.dp(3f),
                                paint);
                        c.drawLine(
                                cx - a.dp(3.5f),
                                cy - a.dp(6.5f),
                                cx + a.dp(0.2f),
                                cy + a.dp(3f),
                                paint);
                        c.drawLine(
                                cx - a.dp(6f),
                                cy - a.dp(0.2f),
                                cx - a.dp(1f),
                                cy - a.dp(0.2f),
                                paint);
                        c.drawCircle(cx + a.dp(4.8f), cy + a.dp(0.2f), a.dp(2.6f), paint);
                        c.drawLine(
                                cx + a.dp(7.4f),
                                cy - a.dp(2.4f),
                                cx + a.dp(7.4f),
                                cy + a.dp(3f),
                                paint);
                        c.drawLine(
                                cx - a.dp(7.5f),
                                cy + a.dp(7f),
                                cx + a.dp(7.5f),
                                cy + a.dp(7f),
                                paint);
                        break;
                    }
                case ICON_GLASS_BLUR:
                    {
                        // Overlapping translucent glass circles
                        c.drawCircle(cx - a.dp(2.6f), cy - a.dp(2.2f), a.dp(5.4f), paint);
                        paint.setAlpha(110);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx + a.dp(2.8f), cy + a.dp(2.4f), a.dp(5.4f), paint);
                        paint.setAlpha(255);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawCircle(cx + a.dp(2.8f), cy + a.dp(2.4f), a.dp(5.4f), paint);
                        break;
                    }
                case ICON_MOTION_SPARK:
                    {
                        // Lightning flash / dynamic animation
                        Path bolt = new Path();
                        bolt.moveTo(cx + a.dp(1.2f), cy - a.dp(8.2f));
                        bolt.lineTo(cx - a.dp(5.2f), cy + a.dp(0.6f));
                        bolt.lineTo(cx + a.dp(0.2f), cy + a.dp(0.6f));
                        bolt.lineTo(cx - a.dp(1.2f), cy + a.dp(8.2f));
                        bolt.lineTo(cx + a.dp(5.2f), cy - a.dp(0.6f));
                        bolt.lineTo(cx - a.dp(0.2f), cy - a.dp(0.6f));
                        bolt.close();
                        c.drawPath(bolt, paint);
                        break;
                    }
                case ICON_TRASH_CLEAR:
                    {
                        // Trash can
                        c.drawLine(
                                cx - a.dp(7.2f),
                                cy - a.dp(5.2f),
                                cx + a.dp(7.2f),
                                cy - a.dp(5.2f),
                                paint);
                        c.drawLine(
                                cx - a.dp(2.4f),
                                cy - a.dp(7.5f),
                                cx + a.dp(2.4f),
                                cy - a.dp(7.5f),
                                paint);
                        RectF bin =
                                new RectF(
                                        cx - a.dp(5.2f),
                                        cy - a.dp(5.2f),
                                        cx + a.dp(5.2f),
                                        cy + a.dp(7.8f));
                        c.drawRoundRect(bin, a.dp(1.8f), a.dp(1.8f), paint);
                        c.drawLine(
                                cx - a.dp(1.8f),
                                cy - a.dp(1.8f),
                                cx - a.dp(1.8f),
                                cy + a.dp(4.5f),
                                paint);
                        c.drawLine(
                                cx + a.dp(1.8f),
                                cy - a.dp(1.8f),
                                cx + a.dp(1.8f),
                                cy + a.dp(4.5f),
                                paint);
                        break;
                    }
                case ICON_DIAGNOSTIC_LOG:
                    {
                        // Diagnostic pulse monitor
                        RectF box =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.8f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.8f));
                        c.drawRoundRect(box, a.dp(2.4f), a.dp(2.4f), paint);
                        Path pulse = new Path();
                        pulse.moveTo(cx - a.dp(5.8f), cy + a.dp(0.5f));
                        pulse.lineTo(cx - a.dp(2.6f), cy + a.dp(0.5f));
                        pulse.lineTo(cx - a.dp(1f), cy - a.dp(3.8f));
                        pulse.lineTo(cx + a.dp(1.2f), cy + a.dp(3.8f));
                        pulse.lineTo(cx + a.dp(2.8f), cy + a.dp(0.5f));
                        pulse.lineTo(cx + a.dp(5.8f), cy + a.dp(0.5f));
                        c.drawPath(pulse, paint);
                        break;
                    }
                case ICON_BACKUP_SAFE:
                    {
                        // Archive / backup box
                        RectF lid =
                                new RectF(
                                        cx - a.dp(8.2f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.2f),
                                        cy - a.dp(2.2f));
                        c.drawRoundRect(lid, a.dp(1.5f), a.dp(1.5f), paint);
                        RectF body =
                                new RectF(
                                        cx - a.dp(7f),
                                        cy - a.dp(2.2f),
                                        cx + a.dp(7f),
                                        cy + a.dp(7.5f));
                        c.drawRoundRect(body, a.dp(2f), a.dp(2f), paint);
                        c.drawLine(
                                cx - a.dp(2.4f),
                                cy + a.dp(1.8f),
                                cx + a.dp(2.4f),
                                cy + a.dp(1.8f),
                                paint);
                        break;
                    }
                case ICON_WALLPAPER:
                    {
                        // Landscape image frame with sun & mountains
                        RectF frame =
                                new RectF(
                                        cx - a.dp(8.5f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8.5f),
                                        cy + a.dp(6.5f));
                        c.drawRoundRect(frame, a.dp(2.4f), a.dp(2.4f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx - a.dp(3.8f), cy - a.dp(2.2f), a.dp(1.6f), paint);
                        paint.setStyle(Paint.Style.STROKE);
                        Path mtn = new Path();
                        mtn.moveTo(cx - a.dp(6.5f), cy + a.dp(5.2f));
                        mtn.lineTo(cx - a.dp(1.5f), cy + a.dp(0.2f));
                        mtn.lineTo(cx + a.dp(1.8f), cy + a.dp(3.2f));
                        mtn.lineTo(cx + a.dp(4.2f), cy + a.dp(1f));
                        mtn.lineTo(cx + a.dp(6.8f), cy + a.dp(5.2f));
                        c.drawPath(mtn, paint);
                        break;
                    }
                case ICON_GRID_COLS:
                    {
                        // 2x2 poster grid
                        float s = a.dp(5.8f), g = a.dp(1.4f);
                        c.drawRoundRect(
                                new RectF(cx - g - s, cy - g - s, cx - g, cy - g),
                                a.dp(1.4f),
                                a.dp(1.4f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx + g, cy - g - s, cx + g + s, cy - g),
                                a.dp(1.4f),
                                a.dp(1.4f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx - g - s, cy + g, cx - g, cy + g + s),
                                a.dp(1.4f),
                                a.dp(1.4f),
                                paint);
                        c.drawRoundRect(
                                new RectF(cx + g, cy + g, cx + g + s, cy + g + s),
                                a.dp(1.4f),
                                a.dp(1.4f),
                                paint);
                        break;
                    }
                case ICON_AUDIO_WAVE:
                    {
                        // Equalizer sound wave bars
                        c.drawLine(
                                cx - a.dp(6.8f),
                                cy - a.dp(3f),
                                cx - a.dp(6.8f),
                                cy + a.dp(3f),
                                paint);
                        c.drawLine(
                                cx - a.dp(3.4f),
                                cy - a.dp(7f),
                                cx - a.dp(3.4f),
                                cy + a.dp(7f),
                                paint);
                        c.drawLine(cx, cy - a.dp(4.5f), cx, cy + a.dp(4.5f), paint);
                        c.drawLine(
                                cx + a.dp(3.4f),
                                cy - a.dp(8f),
                                cx + a.dp(3.4f),
                                cy + a.dp(8f),
                                paint);
                        c.drawLine(
                                cx + a.dp(6.8f),
                                cy - a.dp(3.5f),
                                cx + a.dp(6.8f),
                                cy + a.dp(3.5f),
                                paint);
                        break;
                    }
                case ICON_ASPECT_RATIO:
                    {
                        // 16:9 frame with inner corner brackets
                        RectF frame =
                                new RectF(
                                        cx - a.dp(8.8f),
                                        cy - a.dp(6f),
                                        cx + a.dp(8.8f),
                                        cy + a.dp(6f));
                        c.drawRoundRect(frame, a.dp(2.2f), a.dp(2.2f), paint);
                        c.drawLine(
                                cx - a.dp(5.8f),
                                cy - a.dp(3.2f),
                                cx - a.dp(2.8f),
                                cy - a.dp(3.2f),
                                paint);
                        c.drawLine(
                                cx - a.dp(5.8f),
                                cy - a.dp(3.2f),
                                cx - a.dp(5.8f),
                                cy - a.dp(0.4f),
                                paint);
                        c.drawLine(
                                cx + a.dp(5.8f),
                                cy + a.dp(3.2f),
                                cx + a.dp(2.8f),
                                cy + a.dp(3.2f),
                                paint);
                        c.drawLine(
                                cx + a.dp(5.8f),
                                cy + a.dp(3.2f),
                                cx + a.dp(5.8f),
                                cy + a.dp(0.4f),
                                paint);
                        break;
                    }
                case ICON_SPEED_GAUGE:
                    {
                        // Speedometer gauge
                        c.drawArc(
                                new RectF(
                                        cx - a.dp(8f),
                                        cy - a.dp(6.5f),
                                        cx + a.dp(8f),
                                        cy + a.dp(9.5f)),
                                155,
                                230,
                                false,
                                paint);
                        c.drawLine(cx, cy + a.dp(2f), cx + a.dp(4.2f), cy - a.dp(3.2f), paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx, cy + a.dp(2f), a.dp(1.6f), paint);
                        break;
                    }
                case ICON_REPEAT_LOOP:
                    {
                        // Circular history / repeat arrow
                        c.drawArc(
                                new RectF(
                                        cx - a.dp(7.5f),
                                        cy - a.dp(7.5f),
                                        cx + a.dp(7.5f),
                                        cy + a.dp(7.5f)),
                                -45,
                                290,
                                false,
                                paint);
                        c.drawLine(
                                cx + a.dp(2.5f),
                                cy - a.dp(7.5f),
                                cx + a.dp(6f),
                                cy - a.dp(4.5f),
                                paint);
                        c.drawLine(
                                cx + a.dp(6.5f),
                                cy - a.dp(8.2f),
                                cx + a.dp(6f),
                                cy - a.dp(4.5f),
                                paint);
                        c.drawLine(cx, cy - a.dp(3.5f), cx, cy + a.dp(0.5f), paint);
                        c.drawLine(cx, cy + a.dp(0.5f), cx + a.dp(2.8f), cy + a.dp(2.2f), paint);
                        break;
                    }
                case ICON_SYSTEM:
                case ICON_UPDATE_CLOUD:
                default:
                    {
                        // Circular update ring + arrow
                        c.drawArc(
                                new RectF(a.dp(9.5f), a.dp(9.5f), w - a.dp(9.5f), h - a.dp(9.5f)),
                                -30,
                                300,
                                false,
                                paint);
                        c.drawLine(cx, a.dp(13f), cx, cy + a.dp(3.5f), paint);
                        c.drawLine(cx - a.dp(2.8f), cy + a.dp(0.8f), cx, cy + a.dp(3.6f), paint);
                        c.drawLine(cx + a.dp(2.8f), cy + a.dp(0.8f), cx, cy + a.dp(3.6f), paint);
                        break;
                    }
            }
        }
    }

    private void renderCenteredHint(LinearLayout parent, String message) {
        TextView t = a.text(message, 14, MainActivity.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(a.dp(20), a.dp(48), a.dp(20), a.dp(48));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * Deep emerald-obsidian ambient canvas matching the iPhone `AppleAmbientBackground` preset.
     */
    private static final class AmbientCanvasView extends View {
        private final MainActivity a;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect srcRect = new Rect();
        private final Rect dstRect = new Rect();
        private Bitmap wallpaperBmp;
        private long wallpaperMtime = -1L;

        AmbientCanvasView(MainActivity activity) {
            super(activity);
            this.a = activity;
            reloadWallpaper();
        }

        void reloadWallpaper() {
            if (a == null || !a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) {
                wallpaperBmp = null;
                wallpaperMtime = -1L;
                return;
            }
            File wpFile = new File(a.getFilesDir(), "wallpaper.jpg");
            if (!wpFile.exists()) {
                wallpaperBmp = null;
                wallpaperMtime = -1L;
                return;
            }
            long mtime = wpFile.lastModified();
            if (wallpaperBmp != null && !wallpaperBmp.isRecycled() && mtime == wallpaperMtime) {
                return;
            }
            try {
                Bitmap decoded = BitmapFactory.decodeFile(wpFile.getAbsolutePath());
                if (decoded != null) {
                    wallpaperBmp = decoded;
                    wallpaperMtime = mtime;
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (wallpaperBmp != null
                    && !wallpaperBmp.isRecycled()
                    && a != null
                    && a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)) {
                int bw = wallpaperBmp.getWidth();
                int bh = wallpaperBmp.getHeight();
                if (bw > 0 && bh > 0) {
                    float scale = Math.max((float) w / bw, (float) h / bh);
                    int cropW = Math.round(w / scale);
                    int cropH = Math.round(h / scale);
                    int sx = Math.max(0, (bw - cropW) / 2);
                    int sy = Math.max(0, (bh - cropH) / 2);
                    srcRect.set(sx, sy, Math.min(bw, sx + cropW), Math.min(bh, sy + cropH));
                    dstRect.set(0, 0, w, h);
                    canvas.drawBitmap(wallpaperBmp, srcRect, dstRect, wpPaint);
                    int dimPct =
                            Math.max(20, Math.min(90, a.prefs.getInt(Prefs.WALLPAPER_DIM, 60)));
                    int dimAlpha = Math.round(255f * (dimPct / 100f));
                    canvas.drawColor((dimAlpha << 24) | 0x00060a08);
                    return;
                }
            }

            // 2.0.41: no custom wallpaper — the shared frosted-matte dark
            // base (same as the home ambient and the renderPage ambient),
            // instead of the old emerald gradient + green glow. Clean,
            // dark, no colour cast, no poster colours anywhere.
            HomeAmbient.paintFrost(canvas, w, h, paint);
        }
    }

    /** Soft top-edge gradient veil so scrolling content dissolves gently under the status bar. */
    private static final class TopEdgeFadeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // mkoai 2.0.64: the veil's base colour. Fixed near-black by
        // default (every tab, and the home before the first hero tone);
        // on the phone home it follows the hero diffusion tone so the
        // top bar is not a separate dark band. The alphas/stops never
        // change — only the base colour varies.
        private int baseRgb = HeroDiffusion2063.TOP_FADE_FIXED_RGB;

        TopEdgeFadeView(Context context) {
            super(context);
        }

        /** Retint the veil to a new base colour (same alphas/stops, new hue). */
        void setBaseColor(int rgb) {
            if (baseRgb == rgb) return;
            baseRgb = rgb;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setShader(
                    new LinearGradient(
                            0,
                            0,
                            0,
                            h,
                            HeroDiffusion2063.fadeColors(baseRgb),
                            new float[] {0f, 0.58f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    /** Soft bottom-edge vignette behind the floating Liquid Glass dock. */
    private static final class BottomEdgeFadeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // mkoai 2.0.66: the veil's base colour — fixed near-black by
        // default (the 2.0.38 look); on the phone home it follows the
        // page tone, so a light page does not get a dark band painted
        // across its bottom behind the dock. Same pattern as
        // TopEdgeFadeView: only the base colour varies.
        private int baseRgb = 0x060a08;

        BottomEdgeFadeView(Context context) {
            super(context);
        }

        /** Retint the veil to a new base colour (same alphas/stops, new hue). */
        void setBaseColor(int rgb) {
            int next = rgb & 0x00ffffff;
            if (baseRgb == next) return;
            baseRgb = next;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setShader(
                    new LinearGradient(
                            0,
                            0,
                            0,
                            h,
                            HomeTone2066.bottomFadeColors(baseRgb),
                            new float[] {0f, 0.55f, 1f},
                            Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    /**
     * Custom vector icons matching the iPhone Cupertino dock icons:
     *
     * <ul>
     *   <li>`kind == 0`: `play_rectangle_fill` / `play_rectangle` (`主页`)
     *   <li>`kind == 1`: `search` magnifying glass (`搜索`)
     *   <li>`kind == 2`: `dot_radiowaves_left_right` (`直播`)
     *   <li>`kind == 3`: `square_favorites_alt` (`片库`)
     *   <li>`kind == 4`: `gear_alt` (`设置`)
     * </ul>
     */
    static final class NavGlyph extends View {
        private final MainActivity a;
        private final int kind;
        private boolean selected;
        private int customTint = 0;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        NavGlyph(MainActivity activity, int kind) {
            super(activity);
            this.a = activity;
            this.kind = kind;
        }

        void setSelectedState(boolean value) {
            this.selected = value;
            invalidate();
        }

        void setIconTint(int color) {
            this.customTint = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            int color = customTint != 0 ? customTint : (selected ? 0xffe34b58 : 0xffe5e7eb);
            paint.setColor(color);
            paint.setStrokeWidth(a.dp(1.75f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            if (kind == 0) {
                // iPhone `play_rectangle` / `play_rectangle_fill`
                RectF rect = new RectF(a.dp(2.5f), a.dp(4f), w - a.dp(2.5f), h - a.dp(4f));
                if (selected) {
                    paint.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(rect, a.dp(4.5f), a.dp(4.5f), paint);
                    paint.setColor(0xff101820);
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    c.drawRoundRect(rect, a.dp(4.5f), a.dp(4.5f), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                Path tri = new Path();
                tri.moveTo(cx - a.dp(2.2f), cy - a.dp(3.2f));
                tri.lineTo(cx + a.dp(3.4f), cy);
                tri.lineTo(cx - a.dp(2.2f), cy + a.dp(3.2f));
                tri.close();
                c.drawPath(tri, paint);
            } else if (kind == 1) {
                // Search magnifying glass
                paint.setStyle(Paint.Style.STROKE);
                float r = a.dp(5.5f);
                c.drawCircle(cx - a.dp(1.2f), cy - a.dp(1.2f), r, paint);
                c.drawLine(cx + a.dp(2.8f), cy + a.dp(2.8f), w - a.dp(2.5f), h - a.dp(2.5f), paint);
            } else if (kind == 2) {
                // iPhone `dot_radiowaves_left_right` (`((•))`)
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, a.dp(2f), paint);
                paint.setStyle(Paint.Style.STROKE);
                float r1 = a.dp(5.2f);
                RectF inner = new RectF(cx - r1, cy - r1, cx + r1, cy + r1);
                c.drawArc(inner, -48, 96, false, paint);
                c.drawArc(inner, 132, 96, false, paint);
                float r2 = a.dp(8.6f);
                RectF outer = new RectF(cx - r2, cy - r2, cx + r2, cy + r2);
                c.drawArc(outer, -52, 104, false, paint);
                c.drawArc(outer, 128, 104, false, paint);
            } else if (kind == 5) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(a.dp(1.8f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                float radius = Math.min(w, h) * 0.34f;
                c.drawArc(new RectF(cx-radius, cy-radius, cx+radius, cy+radius), -60, 300, false, paint);
                c.drawLine(cx-radius, cy-radius*0.45f, cx-radius, cy-radius*0.95f, paint);
                c.drawLine(cx-radius, cy-radius*0.45f, cx-radius*0.48f, cy-radius*0.45f, paint);
                c.drawLine(cx, cy, cx, cy-radius*0.52f, paint);
                c.drawLine(cx, cy, cx+radius*0.43f, cy+radius*0.22f, paint);
            } else if (kind == 3) {
                // iPhone `square_favorites_alt` (rounded square card with heart/lines)
                paint.setStyle(Paint.Style.STROKE);
                RectF box = new RectF(a.dp(3.5f), a.dp(3f), w - a.dp(3.5f), h - a.dp(3f));
                c.drawRoundRect(box, a.dp(4f), a.dp(4f), paint);
                c.drawLine(a.dp(6.8f), h - a.dp(6.5f), w - a.dp(6.8f), h - a.dp(6.5f), paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - a.dp(1.6f), cy - a.dp(2.2f), a.dp(1.6f), paint);
                c.drawCircle(cx + a.dp(1.6f), cy - a.dp(2.2f), a.dp(1.6f), paint);
                Path heartTip = new Path();
                heartTip.moveTo(cx - a.dp(3.1f), cy - a.dp(1.7f));
                heartTip.lineTo(cx + a.dp(3.1f), cy - a.dp(1.7f));
                heartTip.lineTo(cx, cy + a.dp(1.8f));
                heartTip.close();
                c.drawPath(heartTip, paint);
            } else {
                // iPhone `gear_alt` (gear with 8 teeth + center circle)
                paint.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, a.dp(3.1f), paint);
                c.drawCircle(cx, cy, a.dp(6.2f), paint);
                for (int deg = 0; deg < 360; deg += 45) {
                    double rad = Math.toRadians(deg);
                    float x1 = cx + (float) Math.cos(rad) * a.dp(6.2f);
                    float y1 = cy + (float) Math.sin(rad) * a.dp(6.2f);
                    float x2 = cx + (float) Math.cos(rad) * a.dp(8.3f);
                    float y2 = cy + (float) Math.sin(rad) * a.dp(8.3f);
                    c.drawLine(x1, y1, x2, y2, paint);
                }
            }
        }
    }
}
