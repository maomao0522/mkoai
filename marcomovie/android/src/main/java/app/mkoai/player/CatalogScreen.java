package app.mkoai.player;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Category listing, paginated video grid browsing, and watch history management extracted from
 * {@link MainActivity}.
 */
final class CatalogScreen {
    private CatalogScreen() {}

    /** Visible back entry so remote and touch users can always leave a library page. */
    static void addBack(MainActivity a, String tag) {
        a.body.addView(
                a.navButton("‹ 返回", tag, a::onBackPressed),
                new LinearLayout.LayoutParams(a.dp(82), a.dp(32)));
        a.gap(a.body, 8);
    }

    static void categories(MainActivity a, Cms.Site s) {
        a.prefs.edit().putString(Prefs.SELECTED_SITE, s.key).apply();
        a.renderPage(s.name, "CHOOSE A CATEGORY");
        addBack(a, "catalog-back");
        LinearLayout actions = a.row();
        a.addButton(actions, a.button("全域性搜尋", "search", a::globalSearchDialog));
        a.addButton(
                actions,
                a.button(
                        "最新內容",
                        "latest",
                        () ->
                                a.open(
                                        new MainActivity.Screen(
                                                () -> videos(a, s, "", "最新內容", 1, null)),
                                        true)));
        a.body.addView(actions);
        a.load(
                "正在讀取分類…",
                () -> Cms.categories(a.engine(s).home()),
                cats -> {
                    if (cats.isEmpty()) {
                        a.paragraph("來源沒有返回分類，可嘗試“最新內容”或搜尋。");
                        return;
                    }
                    for (Cms.Category c : cats)
                        a.addButton(
                                a.body,
                                a.button(
                                        c.name,
                                        "category:" + c.id,
                                        () ->
                                                a.open(
                                                        new MainActivity.Screen(
                                                                () ->
                                                                        videos(
                                                                                a, s, c.id, c.name,
                                                                                1, null)),
                                                        true)));
                });
    }

    static void videos(
            MainActivity a, Cms.Site s, String cat, String title, int page, String search) {
        a.renderPage(title, "EXPLORE THE LIBRARY");
        addBack(a, "catalog-back");
        a.paragraph(s.name + " · 第 " + page + " 頁");
        a.load(
                "正在讀取影片…",
                () -> {
                    SourceEngine selected = a.engine(s);
                    return Cms.listing(
                            search != null
                                    ? selected.search(search, page)
                                    : selected.category(cat, page));
                },
                list -> {
                    if (list.videos.isEmpty()) a.paragraph("這一頁沒有影片。");
                    int cols =
                            TvLayoutPolicy.catalogColumns(
                                    a.windowWidth() / a.getResources().getDisplayMetrics().density,
                                    a.formFactor,
                                    a.isPortrait());
                    LinearLayout cards = null;
                    for (int i = 0; i < list.videos.size(); i++) {
                        if (i % cols == 0) {
                            cards = a.row();
                            cards.setGravity(Gravity.TOP);
                            cards.setClipChildren(false);
                            cards.setClipToPadding(false);
                            a.body.addView(cards);
                        }
                        Cms.Video v = list.videos.get(i);
                        LinearLayout card = a.vertical();
                        card.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(10));
                        card.setClipChildren(false);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                        lp.setMargins(a.dp(5), a.dp(5), a.dp(14), a.dp(19));
                        cards.addView(card, lp);
                        FrameLayout art = new FrameLayout(a);
                        art.setBackground(
                                new GradientDrawable(
                                        GradientDrawable.Orientation.TL_BR,
                                        new int[] {0xff304c70, 0xff141c2c}));
                        ImageView img = new ImageView(a);
                        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        art.addView(img, new FrameLayout.LayoutParams(-1, -1));
                        card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(185)));
                        a.poster(v.poster, img);
                        // 2.0.40: year bottom-left on the poster.
                        MobileShell.addPosterOverlay(a, art, v.remarks, v.year);
                        TextView label = a.text(v.title, 16, MainActivity.WHITE);
                        label.setPadding(a.dp(5), a.dp(10), a.dp(5), 0);
                        label.setLines(2);
                        label.setEllipsize(TextUtils.TruncateAt.END);
                        card.addView(label);
                        TextView remark = a.text(v.remarks, 12, MainActivity.MUTED);
                        remark.setMaxLines(1);
                        remark.setEllipsize(TextUtils.TruncateAt.END);
                        card.addView(remark);
                        card.setFocusable(a.isTvDevice());
                        card.setFocusableInTouchMode(a.isTvDevice());
                        card.setClickable(true);
                        card.setTag(i == 0 ? "primary" : "video:" + v.id);
                        card.setOnClickListener(view -> a.openListing(s, v));
                        card.setOnFocusChangeListener(
                                (view, focus) ->
                                        FocusMotion.style(
                                                a,
                                                card,
                                                focus,
                                                0x00101c30,
                                                0xff435c7c,
                                                10,
                                                MainActivity.WHITE,
                                                MainActivity.WHITE,
                                                true,
                                                1.035f));
                    }
                    if (cards != null) {
                        int remainder = list.videos.size() % cols;
                        if (remainder > 0)
                            for (int i = remainder; i < cols; i++) {
                                LinearLayout.LayoutParams blank =
                                        new LinearLayout.LayoutParams(0, 1, 1);
                                blank.setMargins(a.dp(5), a.dp(5), a.dp(14), a.dp(19));
                                cards.addView(new View(a), blank);
                            }
                    }
                    LinearLayout paging = a.row();
                    if (page > 1)
                        a.addButton(
                                paging,
                                a.button(
                                        "上一頁",
                                        "previous",
                                        () ->
                                                a.open(
                                                        new MainActivity.Screen(
                                                                () ->
                                                                        videos(
                                                                                a, s, cat, title,
                                                                                page - 1, search)),
                                                        false)));
                    if (page < list.pageCount)
                        a.addButton(
                                paging,
                                a.button(
                                        "下一頁",
                                        "next",
                                        () ->
                                                a.open(
                                                        new MainActivity.Screen(
                                                                () ->
                                                                        videos(
                                                                                a, s, cat, title,
                                                                                page + 1, search)),
                                                        false)));
                    a.body.addView(paging);
                    a.restore(a.current);
                });
    }

    static String historyLabel(WatchHistory.Entry e) {
        return e.episode
                + " · "
                + (e.complete()
                        ? "已看完"
                        : PlaybackController.formatTime(e.position)
                                + (e.duration > 0
                                        ? " / " + PlaybackController.formatTime(e.duration)
                                        : ""));
    }

    /**
     * 2.0.43: re-select a playable source for a history entry, by title.
     * The stored e.site is never trusted — it may be dead. Searches all
     * searchable sources in priority order (巨量, xgzy first) and verifies
     * the detail has playable lines; the episode-URL probe only ranks
     * candidates (2.0.44), it never rejects one. Calls done once on the UI
     * thread with the winner, or null when no source can play this title.
     * The bookmarked episode is passed as a ranking preference, so a source
     * carrying that episode wins over one that only has the title.
     */
    private static void reselectSource(
            MainActivity a,
            WatchHistory.Entry e,
            MainActivity.Done<AutoSourceFallback.Playable> done) {
        Cms.Video listing = new Cms.Video();
        listing.title = e.title;
        listing.poster = e.poster;
        AutoSourceFallback.find(a, listing, null, e.episode, done);
    }

    /** 2.0.43: no source can play this title — say so, never open a blank player or silently jump elsewhere. */
    private static void showCannotPlay(MainActivity a, WatchHistory.Entry e) {
        String title = e.title == null || e.title.isEmpty() ? "未命名影片" : e.title;
        new AppDialog.Builder(a)
                .setTitle("無法播放")
                .setMessage("《" + title + "》目前沒有可用的片源。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /**
     * 2.0.43: open the detail page for a history entry on a freshly
     * re-selected source (never the stored e.site — it may be dead).
     */
    static void resumeHistory(MainActivity a, WatchHistory.Entry e) {
        reselectSource(
                a,
                e,
                playable -> {
                    if (playable == null
                            || playable.detail == null
                            || playable.detail.lines == null
                            || playable.detail.lines.isEmpty()) {
                        showCannotPlay(a, e);
                        return;
                    }
                    a.open(
                            new MainActivity.Screen(
                                    () ->
                                            a.detail(
                                                    playable.site,
                                                    playable.listing,
                                                    playable.detail)),
                            true);
                });
    }

    /** 2.0.41: one direct-resume fetch at a time (double taps ignored). */
    private static boolean resumeInFlight;

    /**
     * 2.0.41: resume a history entry DIRECTLY (no detail page) — re-select
     * a playable source for the title first (2.0.43: never the stored
     * e.site — it may be dead), then play the bookmarked episode from its
     * saved position. No playable source -> "無法播放" dialog, never a
     * blank player. A playable source whose detail no longer has the
     * episode -> its detail page, so the user can pick another episode.
     */
    static void playHistoryDirect(MainActivity a, WatchHistory.Entry e) {
        if (resumeInFlight) return;
        resumeInFlight = true;
        a.ui.postDelayed(() -> resumeInFlight = false, 15000); // safety: never sticks
        // mkoai 2.0.48: 「自動選源直接播放」 OFF -> show the source
        // picker first; picking ✦ 自動選擇最佳片源 runs the auto path.
        // ON (default) -> auto-select directly, never a picker.
        if (!AutoSourceFallback.autoDirectPlay(a)) {
            Cms.Video listing = new Cms.Video();
            listing.title = e.title;
            listing.poster = e.poster;
            AutoSourceFallback.pickSource(
                    a,
                    listing,
                    null,
                    playable -> {
                        resumeInFlight = false;
                        playHistoryPlayable(a, e, playable);
                    },
                    () ->
                            reselectSource(
                                    a,
                                    e,
                                    playable -> {
                                        resumeInFlight = false;
                                        playHistoryPlayable(a, e, playable);
                                    }));
            return;
        }
        reselectSource(
                a,
                e,
                playable -> {
                    resumeInFlight = false;
                    playHistoryPlayable(a, e, playable);
                });
    }

    /** Continuation shared by the auto path and the OFF-mode picker. */
    private static void playHistoryPlayable(
            MainActivity a, WatchHistory.Entry e, AutoSourceFallback.Playable playable) {
                    if (playable == null
                            || playable.detail == null
                            || playable.detail.lines == null
                            || playable.detail.lines.isEmpty()) {
                        showCannotPlay(a, e);
                        return;
                    }
                    Cms.Video video = playable.detail;
                    // Resolve the bookmarked line/episode first (same
                    // matching as playBookmark, except episode names are
                    // compared normalized — 第01集 matches 第1集 across
                    // sources): unresolvable -> detail page.
                    Cms.Line line = null;
                    for (Cms.Line candidate : video.lines) {
                        if (candidate.name.equals(e.line)) {
                            line = candidate;
                            break;
                        }
                    }
                    if (line == null) line = video.lines.get(0);
                    Cms.Episode episode = null;
                    int epIndex = -1;
                    if (line != null) {
                        String wantEp = WatchHistory.normEpisode(e.episode);
                        for (int i = 0; i < line.episodes.size(); i++) {
                            if (WatchHistory.normEpisode(line.episodes.get(i).name)
                                    .equals(wantEp)) {
                                episode = line.episodes.get(i);
                                epIndex = i;
                                break;
                            }
                        }
                        if (episode == null
                                && e.index >= 0
                                && e.index < line.episodes.size()) {
                            episode = line.episodes.get(e.index);
                            epIndex = e.index;
                        }
                    }
                    if (episode == null) {
                        a.open(
                                new MainActivity.Screen(
                                        () ->
                                                a.detail(
                                                        playable.site,
                                                        playable.listing,
                                                        playable.detail)),
                                true);
                        return;
                    }
                    // Hand playBookmark an entry whose line/episode names
                    // match the found detail exactly, so its (unchanged)
                    // exact-name matching resolves to this episode instead
                    // of falling back to a possibly wrong index on a
                    // differently-ordered source. Position/duration are
                    // the bookmark's own.
                    WatchHistory.Entry matched = new WatchHistory.Entry();
                    matched.site = playable.site.key;
                    matched.id = video.id;
                    matched.title = e.title;
                    matched.poster = e.poster;
                    matched.line = line.name;
                    matched.episode = episode.name;
                    matched.index = epIndex;
                    matched.position = e.position;
                    matched.duration = e.duration;
                    matched.updated = e.updated;
                    a.playBookmark(playable.site, video, matched);
    }

    static void playBookmark(
            MainActivity a, Cms.Site site, Cms.Video video, WatchHistory.Entry bookmark) {
        Cms.Line line = null;
        for (Cms.Line candidate : video.lines)
            if (candidate.name.equals(bookmark.line)) {
                line = candidate;
                break;
            }
        if (line == null && !video.lines.isEmpty()) {
            line = video.lines.get(0);
        }
        Cms.Episode episode = null;
        if (line != null) {
            for (Cms.Episode candidate : line.episodes)
                if (candidate.name.equals(bookmark.episode)) {
                    episode = candidate;
                    break;
                }
            if (episode == null && bookmark.index >= 0 && bookmark.index < line.episodes.size()) {
                episode = line.episodes.get(bookmark.index);
            }
        }
        if (episode == null) {
            a.notice("上次的線路或集數已變更，請在下方重新選集。");
            return;
        }
        a.resumeAt = bookmark.resume();
        a.resolvePlayer(site, video, line, episode);
    }

    private static String historyProgressBadge(WatchHistory.Entry e) {
        if (e.complete()) return "已看完";
        if (e.duration > 0) {
            int pct = Math.max(1, Math.min(99, Math.round(e.position * 100f / e.duration)));
            return "已看 " + pct + "%";
        }
        if (e.position > 0) return "已看 " + PlaybackController.formatTime(e.position);
        return "剛剛觀看";
    }

    private static String historyTimeSummary(WatchHistory.Entry e) {
        String ep = e.episode == null ? "" : e.episode.trim();
        if (e.complete()) return ep.isEmpty() ? "已看完" : ep + " · 已看完";
        if (e.duration > 0)
            return PlaybackController.formatTime(e.position)
                    + " / "
                    + PlaybackController.formatTime(e.duration);
        if (e.position > 0) return "觀看至 " + PlaybackController.formatTime(e.position);
        return ep.isEmpty() ? "繼續觀看" : ep;
    }

    private static void confirmClearHistory(MainActivity a) {
        new AppDialog.Builder(a)
                .setTitle("清空觀看歷史？")
                .setMessage("清除本機及目前帳號的雲端觀看紀錄；離線時將於恢復同步後完成。保留收藏、配置和帳號。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "清空",
                        (d, n) -> {
                            SupabaseSync.clearHistory(a);
                            a.refresh();
                        })
                .show();
    }

    private static void manageHistoryEntry(MainActivity a, WatchHistory.Entry e) {
        new AppDialog.Builder(a)
                .setTitle(e.title == null || e.title.isEmpty() ? "觀看記錄" : e.title)
                .setItems(
                        new String[] {"繼續觀看", "移除此記錄", "清空觀看歷史"},
                        (d, n) -> {
                            if (n == 0) resumeHistory(a, e);
                            else if (n == 2) confirmClearHistory(a);
                            else {
                                a.prefs
                                        .edit()
                                        .putString(
                                                Prefs.WATCH_HISTORY,
                                                WatchHistory.remove(
                                                        a.prefs.getString(
                                                                Prefs.WATCH_HISTORY, "[]"),
                                                        e.key()))
                                        .apply();
                                a.refresh();
                            }
                        })
                .setNegativeButton("返回", null)
                .show();
    }

    static void historyPage(MainActivity a) {
        a.renderPage("觀看歷史", "CONTINUE WATCHING");
        // 2.0.41: display-only dedupe — the same show+episode shows once
        // (latest update wins); raw records are untouched.
        List<WatchHistory.Entry> entries = WatchHistory.deduped(a.watchHistory());
        if (entries.isEmpty()) {
            a.paragraph("播放後會在這裏記錄集數和進度。");
            return;
        }
        if (a.isTvDevice()) {
            LinearLayout topBar = a.row();
            topBar.setClipChildren(false);
            topBar.setClipToPadding(false);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            TextView summary =
                    a.text(entries.size() + " 部觀看記錄 · 確認繼續觀看，長按確認或按選單鍵管理", 13, MainActivity.MUTED);
            topBar.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
            Button clearBtn = a.navButton("清空觀看歷史", "history-clear", () -> confirmClearHistory(a));
            topBar.addView(clearBtn, new LinearLayout.LayoutParams(-2, a.dp(36)));
            a.body.addView(topBar, new LinearLayout.LayoutParams(-1, -2));
            a.gap(a.body, 10);

            float screen = a.windowWidth() / a.getResources().getDisplayMetrics().density;
            int columns = Math.max(4, Math.min(6, a.prefs.getInt(Prefs.HOME_COLUMNS, 6)));
            int width = Math.max(108, (int) ((screen - 92) / columns) - 20);
            int height = Math.round(width * 1.5f);
            LinearLayout row = null;
            for (int i = 0; i < entries.size(); i++) {
                final WatchHistory.Entry e = entries.get(i);
                if (i % columns == 0) {
                    row = a.row();
                    row.setGravity(Gravity.TOP);
                    row.setClipChildren(false);
                    row.setClipToPadding(false);
                    a.body.addView(row);
                }
                LinearLayout card = a.vertical();
                card.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(8));
                card.setGravity(Gravity.CENTER_HORIZONTAL);
                card.setClipChildren(false);
                card.setClipToPadding(false);
                card.setId(View.generateViewId());
                card.setTag("history:" + e.key());
                card.setFocusable(true);
                card.setFocusableInTouchMode(true);
                card.setClickable(true);

                FrameLayout art = new FrameLayout(a);
                GradientDrawable artBg =
                        new GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                new int[] {0xff282d38, 0xff171a22});
                artBg.setCornerRadius(a.dp(10));
                art.setBackground(artBg);
                art.setClipToOutline(true);

                TextView fallback =
                        a.text(
                                e.title == null || e.title.isEmpty() ? "影片" : e.title,
                                13,
                                0x55ffffff);
                fallback.setGravity(Gravity.CENTER);
                fallback.setMaxLines(2);
                fallback.setEllipsize(TextUtils.TruncateAt.END);
                fallback.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(28));
                art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

                ImageView poster = new ImageView(a);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
                a.poster(e.poster, poster);

                String ep = e.episode == null ? "" : e.episode.trim();
                if (!ep.isEmpty()) {
                    TextView epBadge = a.text(ep, 10.5f, 0xffedf1f7);
                    epBadge.setSingleLine(true);
                    epBadge.setEllipsize(TextUtils.TruncateAt.END);
                    epBadge.setMaxWidth(a.dp(Math.max(48, width - 16)));
                    epBadge.setPadding(a.dp(7), a.dp(2.5f), a.dp(7), a.dp(2.5f));
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setColor(0xd214171f);
                    badgeBg.setCornerRadius(a.dp(6));
                    badgeBg.setStroke(Math.max(1, a.dp(.8f)), 0x24ffffff);
                    epBadge.setBackground(badgeBg);
                    FrameLayout.LayoutParams bp =
                            new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
                    bp.setMargins(a.dp(6), a.dp(6), a.dp(6), 0);
                    art.addView(epBadge, bp);
                }

                LinearLayout overlay = a.vertical();
                overlay.setGravity(Gravity.BOTTOM);
                overlay.setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(7));
                GradientDrawable scrim =
                        new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[] {0x00000000, 0xaa0e1016, 0xeb0b0d12});
                overlay.setBackground(scrim);

                // 2.0.40: year bottom-left, on its own line above the
                // progress label. History entries carry no year, so it is
                // filled from TMDB in the background when available.
                TextView yearTv = a.text("", 10.5f, 0xfff3f5f9);
                yearTv.setTypeface(null, Typeface.BOLD);
                yearTv.setSingleLine(true);
                yearTv.setVisibility(View.GONE);
                overlay.addView(yearTv, new LinearLayout.LayoutParams(-1, -2));
                MobileShell.enrichYearLabel(a, yearTv, e.title);

                TextView progLabel = a.text(historyProgressBadge(e), 10.5f, 0xfff3f5f9);
                progLabel.setTypeface(null, Typeface.BOLD);
                progLabel.setSingleLine(true);
                progLabel.setEllipsize(TextUtils.TruncateAt.END);
                overlay.addView(progLabel, new LinearLayout.LayoutParams(-1, -2));

                FrameLayout track = new FrameLayout(a);
                track.setBackground(a.bg(0x3affffff, 2));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, a.dp(3.5f));
                tp.topMargin = a.dp(4);
                float ratio =
                        e.complete()
                                ? 1f
                                : e.duration > 0
                                        ? Math.max(
                                                .04f, Math.min(1f, (float) e.position / e.duration))
                                        : (e.position > 0 ? .18f : 0f);
                if (ratio > 0f) {
                    View fill = new View(a);
                    fill.setBackground(a.bg(0xfff5f7fb, 2));
                    int barInnerPx = Math.max(a.dp(6), a.dp(width - 16));
                    int fillPx = Math.max(a.dp(4), Math.round(barInnerPx * ratio));
                    track.addView(fill, new FrameLayout.LayoutParams(fillPx, -1, Gravity.LEFT));
                }
                overlay.addView(track, tp);
                art.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

                card.addView(art, new LinearLayout.LayoutParams(a.dp(width), a.dp(height)));

                TextView title =
                        a.text(
                                e.title == null || e.title.isEmpty() ? "未命名影片" : e.title,
                                13,
                                MainActivity.WHITE);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setPadding(a.dp(2), a.dp(8), a.dp(2), 0);
                card.addView(title, new LinearLayout.LayoutParams(-1, -2));

                TextView sub = a.text(historyTimeSummary(e), 11, MainActivity.MUTED);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                sub.setPadding(a.dp(2), a.dp(2), a.dp(2), 0);
                card.addView(sub, new LinearLayout.LayoutParams(-1, -2));

                card.setOnClickListener(v -> resumeHistory(a, e));
                card.setOnLongClickListener(
                        v -> {
                            manageHistoryEntry(a, e);
                            return true;
                        });
                a.pressFeedback(card);
                card.setOnKeyListener(
                        (v, key, event) -> {
                            if (key == KeyEvent.KEYCODE_MENU) {
                                if (event.getAction() == KeyEvent.ACTION_UP)
                                    manageHistoryEntry(a, e);
                                return true;
                            }
                            return false;
                        });
                card.setOnFocusChangeListener(
                        (v, f) ->
                                FocusMotion.style(
                                        a,
                                        card,
                                        f,
                                        0,
                                        0xff29292f,
                                        14,
                                        MainActivity.WHITE,
                                        MainActivity.WHITE,
                                        true,
                                        1.035f));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
                size.setMargins(a.dp(4), a.dp(5), a.dp(4), a.dp(16));
                row.addView(card, size);
                if (i == 0 && a.current != null && a.current.focus == null)
                    a.ui.post(card::requestFocus);
            }
            if (row != null)
                for (int i = entries.size() % columns; i > 0 && i < columns; i++) {
                    LinearLayout.LayoutParams blank = new LinearLayout.LayoutParams(0, 1, 1);
                    blank.setMargins(a.dp(4), a.dp(5), a.dp(4), a.dp(16));
                    row.addView(new View(a), blank);
                }
            return;
        }
        a.addButton(a.body, a.navButton("清空觀看歷史", "history-clear", () -> confirmClearHistory(a)));
        // 2.0.41: Netflix 「繼續觀看」 poster cards (phone). 2 columns in
        // portrait, 3 in landscape. Each card: poster + centred circular
        // play button (direct resume) + red progress bar at the poster
        // bottom; title + time below; ⓘ (detail page) left, ⋮ (menu)
        // right. No poster → title text on the gradient, never blank.
        // 2.0.43: FIXED card width, computed from the body's real content
        // width (renderPage pads the body 18dp/side portrait, 28dp/side
        // landscape) minus the cards' 4dp/side margins, so every row fills
        // the body exactly. The 2.0.42 weight-1 cards (width 0 + weight)
        // let the first row measure its MATCH_PARENT inner rows — the
        // ⓘ/⋮ row — against a transient width while the page was still
        // settling (heading animation + first layout), so the buttons
        // landed half outside the card edge on the top row only; later
        // rows measured after the body width settled and looked fine. A
        // fixed width makes every row measure identically, first or not,
        // in portrait (2 cols) and landscape (3 cols).
        float screenDp = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int columns = (!a.isPortrait() && screenDp >= 600) ? 3 : 2;
        int sidePad = a.isPortrait() ? 18 : 28;
        int contentDp = Math.max(0, (int) screenDp - sidePad * 2);
        int cardW = Math.max(120, (contentDp - columns * 8) / columns);
        int cardH = Math.round(cardW * 1.5f);
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            final WatchHistory.Entry e = entries.get(i);
            if (i % columns == 0) {
                row = a.row();
                row.setGravity(Gravity.TOP);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                a.body.addView(row);
            }
            LinearLayout card = a.vertical();
            card.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(8));
            card.setClipChildren(false);
            card.setClipToPadding(false);
            card.setTag("history:" + e.key());
            card.setClickable(true);

            FrameLayout art = new FrameLayout(a);
            GradientDrawable artBg =
                    new GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            new int[] {0xff282d38, 0xff171a22});
            artBg.setCornerRadius(a.dp(10));
            art.setBackground(artBg);
            art.setClipToOutline(true);

            TextView fallback =
                    a.text(
                            e.title == null || e.title.isEmpty() ? "影片" : e.title,
                            13,
                            0x55ffffff);
            fallback.setGravity(Gravity.CENTER);
            fallback.setMaxLines(2);
            fallback.setEllipsize(TextUtils.TruncateAt.END);
            fallback.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(28));
            art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

            ImageView poster = new ImageView(a);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            a.poster(e.poster, poster);

            String ep = e.episode == null ? "" : e.episode.trim();
            if (!ep.isEmpty()) {
                TextView epBadge = a.text(ep, 10.5f, 0xffedf1f7);
                epBadge.setSingleLine(true);
                epBadge.setEllipsize(TextUtils.TruncateAt.END);
                epBadge.setMaxWidth(a.dp(Math.max(48, cardW - 16)));
                epBadge.setPadding(a.dp(7), a.dp(2.5f), a.dp(7), a.dp(2.5f));
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setColor(0xd214171f);
                badgeBg.setCornerRadius(a.dp(6));
                badgeBg.setStroke(Math.max(1, a.dp(.8f)), 0x24ffffff);
                epBadge.setBackground(badgeBg);
                FrameLayout.LayoutParams bp =
                        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
                bp.setMargins(a.dp(6), a.dp(6), a.dp(6), 0);
                art.addView(epBadge, bp);
            }

            // Centred circular play button — direct resume from the saved position.
            FrameLayout playWrap = new FrameLayout(a);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xcc000000);
            circle.setStroke(a.dp(1.5f), 0xffffffff);
            playWrap.setBackground(circle);
            TextView tri = a.text("▶", 20, 0xffffffff);
            tri.setGravity(Gravity.CENTER);
            playWrap.addView(tri, new FrameLayout.LayoutParams(-1, -1));
            int playSize = a.dp(56);
            art.addView(
                    playWrap,
                    new FrameLayout.LayoutParams(playSize, playSize, Gravity.CENTER));
            MobileDetailScreen.bindTouchFeedback(playWrap);
            playWrap.setOnClickListener(v -> playHistoryDirect(a, e));

            // Netflix-red progress bar at the poster bottom.
            float ratio =
                    e.complete()
                            ? 1f
                            : e.duration > 0
                                    ? Math.max(
                                            .04f, Math.min(1f, (float) e.position / e.duration))
                                    : (e.position > 0 ? .18f : 0f);
            FrameLayout track = new FrameLayout(a);
            track.setBackground(a.bg(0x3affffff, 2));
            if (ratio > 0f) {
                View fill = new View(a);
                fill.setBackground(a.bg(0xffe50914, 2));
                int barPx = a.dp(Math.max(48, cardW - 10));
                int fillPx = Math.max(a.dp(4), Math.round(barPx * ratio));
                track.addView(fill, new FrameLayout.LayoutParams(fillPx, -1, Gravity.LEFT));
            }
            art.addView(
                    track,
                    new FrameLayout.LayoutParams(-1, a.dp(4), Gravity.BOTTOM));

            card.addView(art, new LinearLayout.LayoutParams(-1, a.dp(cardH)));

            TextView title =
                    a.text(
                            e.title == null || e.title.isEmpty() ? "未命名影片" : e.title,
                            13,
                            MainActivity.WHITE);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(a.dp(2), a.dp(8), a.dp(2), 0);
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));

            TextView sub = a.text(historyTimeSummary(e), 11, MainActivity.MUTED);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(a.dp(2), a.dp(2), a.dp(2), 0);
            card.addView(sub, new LinearLayout.LayoutParams(-1, -2));

            // Bottom row: ⓘ (detail page) left, ⋮ (menu) right.
            // 2.0.43: the row is WRAP_CONTENT tall with the 8dp gap as a
            // top MARGIN, not padding inside a fixed 34dp height — padding
            // inside a fixed-height row left only 26dp for the 34dp
            // buttons, clipping their bottom edge. Horizontal padding
            // (2dp) keeps both buttons inside the card padding. The
            // middle spacer stays 0dp tall (it only needs horizontal
            // weight), so no measurement path can push the row away from
            // the time line above it.
            LinearLayout actions = a.row();
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setPadding(a.dp(2), 0, a.dp(2), 0);
            TextView infoBtn = a.text("i", 14, 0xffffffff);
            infoBtn.setTypeface(null, Typeface.BOLD);
            infoBtn.setGravity(Gravity.CENTER);
            GradientDrawable infoBg = new GradientDrawable();
            infoBg.setShape(GradientDrawable.OVAL);
            infoBg.setColor(0x00000000);
            infoBg.setStroke(a.dp(1.2f), 0xaaffffff);
            infoBtn.setBackground(infoBg);
            int btnSize = a.dp(34);
            infoBtn.setLayoutParams(new LinearLayout.LayoutParams(btnSize, btnSize));
            MobileDetailScreen.bindTouchFeedback(infoBtn);
            infoBtn.setOnClickListener(v -> resumeHistory(a, e));
            actions.addView(infoBtn);
            // 2.0.42: spacer height 0 (it only needs horizontal weight);
            // WRAP_CONTENT height here let the row measure taller than its
            // 34dp buttons on some devices, opening a gap above the row.
            actions.addView(new View(a), new LinearLayout.LayoutParams(0, 0, 1f));
            TextView moreBtn = a.text("⋮", 18, 0xffffffff);
            moreBtn.setGravity(Gravity.CENTER);
            moreBtn.setLayoutParams(new LinearLayout.LayoutParams(btnSize, btnSize));
            MobileDetailScreen.bindTouchFeedback(moreBtn);
            moreBtn.setOnClickListener(v -> manageHistoryEntry(a, e));
            actions.addView(moreBtn);
            LinearLayout.LayoutParams actionsLp =
                    new LinearLayout.LayoutParams(-1, -2);
            actionsLp.topMargin = a.dp(8);
            card.addView(actions, actionsLp);

            card.setOnClickListener(v -> playHistoryDirect(a, e));
            a.pressFeedback(card);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(a.dp(cardW), -2);
            size.setMargins(a.dp(4), a.dp(5), a.dp(4), a.dp(16));
            row.addView(card, size);
        }
        if (row != null)
            for (int i = entries.size() % columns; i > 0 && i < columns; i++) {
                LinearLayout.LayoutParams blank =
                        new LinearLayout.LayoutParams(a.dp(cardW), 1);
                blank.setMargins(a.dp(4), a.dp(5), a.dp(4), a.dp(16));
                row.addView(new View(a), blank);
            }
    }
}
