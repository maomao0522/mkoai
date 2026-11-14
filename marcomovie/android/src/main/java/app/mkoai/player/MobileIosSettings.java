package app.mkoai.player;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.pilipala.glass.GlassworkSurface;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android Mobile 2-Level Liquid Glass Settings architecture aligned with iOS {@code IosSettingsPage}:
 * - Level 1: Full-screen "设置" page with version subtitle (5-tap PrivacySpace) and 6 {@code 36dp}
 *   Liquid Glass category capsules
 * - Level 2: Full-screen secondary settings page (never a popup dialog) with top {@code ‹} back
 *   navigation, grouped section cards, wrapping {@code SettingsChoices} pills, and live glass slider.
 */
final class MobileIosSettings {
    private static final int COLOR_PURPLE = 0xffbf5af2;
    private static final int COLOR_BLUE = 0xffe34b58;
    private static final int COLOR_ORANGE = 0xffff9f0a;
    private static final int COLOR_GREEN = 0xff30d158;
    private static final int COLOR_TEAL = 0xff5ac8fa;
    private static final int COLOR_INDIGO = 0xff5e5ce6;
    private static final int COLOR_CORAL = 0xffff6b4a;

    private static final int ICON_SPARKLES = 1;
    private static final int ICON_GRID = 2;
    private static final int ICON_PLAY_CIRCLE = 3;
    private static final int ICON_LAYERS = 4;
    private static final int ICON_TRAY = 5;
    private static final int ICON_INFO = 6;
    private static final int ICON_WALLPAPER = 7;
    private static final int ICON_DROPLET = 8;
    private static final int ICON_POSTER = 9;
    private static final int ICON_HOME_MODULE = 10;
    private static final int ICON_DISPLAY = 11;
    private static final int ICON_SPEED = 12;
    private static final int ICON_SKIP = 13;
    private static final int ICON_CLOUD = 14;
    private static final int ICON_BACKUP = 15;
    private static final int ICON_BROOM = 16;

    /** Empty string = Level 1 root page; non-empty = active Level 2 category title. */
    private static String activeCategoryTitle = "";

    private MobileIosSettings() {}

    static boolean hasSubPage() {
        return activeCategoryTitle != null && !activeCategoryTitle.isEmpty();
    }

    static void resetToRoot() {
        activeCategoryTitle = "";
        MobileShell.settingsSubPage = 0;
    }

    static boolean navigateBack(MainActivity a) {
        if (!hasSubPage()) return false;
        resetToRoot();
        if (a != null) {
            if (a.current != null && a.current.mobileHome != null) {
                a.current.mobileHome.refreshSettingsPage(-1);
            } else {
                MobileShell.show(a);
            }
        }
        return true;
    }

    static final class PaneSpec {
        final String title;
        final String subtitle;
        final int iconKind;
        final int color;
        final List<PaneSpec> children = new ArrayList<>();
        final PaneBuilder builder;

        PaneSpec(String title, String subtitle, int iconKind, int color, PaneBuilder builder) {
            this.title = title;
            this.subtitle = subtitle;
            this.iconKind = iconKind;
            this.color = color;
            this.builder = builder;
        }

        PaneSpec addChild(PaneSpec child) {
            children.add(child);
            return this;
        }
    }

    interface PaneBuilder {
        void build(LinearLayout body, PageController ctl);
    }

    interface ChoiceAction {
        void onSelect(int index);
    }

    static final class PageController {
        final MainActivity a;
        final FrameLayout contentContainer;
        final Cms.Site currentSite;
        final MobileShell shell;
        ScrollView activeScroll;

        PageController(
                MainActivity a,
                FrameLayout contentContainer,
                Cms.Site currentSite,
                MobileShell shell) {
            this.a = a;
            this.contentContainer = contentContainer;
            this.currentSite = currentSite;
            this.shell = shell;
        }

        void openSubPage(PaneSpec pane) {
            activeCategoryTitle = pane.title;
            MobileShell.settingsSubPage = 1;
            renderInternal(a, contentContainer, currentSite, shell, 1, 0);
        }

        void backToRoot() {
            resetToRoot();
            renderInternal(a, contentContainer, currentSite, shell, -1, 0);
        }

        void rerenderInPlace() {
            final int savedScrollY = activeScroll != null ? activeScroll.getScrollY() : 0;
            if (shell != null) shell.applyAppearancePreferences();
            renderInternal(a, contentContainer, currentSite, shell, 0, savedScrollY);
        }
    }

    static void render(
            final MainActivity a,
            final FrameLayout contentContainer,
            final Cms.Site currentSite,
            final MobileShell shell) {
        render(a, contentContainer, currentSite, shell, 0);
    }

    static void render(
            final MainActivity a,
            final FrameLayout contentContainer,
            final Cms.Site currentSite,
            final MobileShell shell,
            final int slideDirection) {
        renderInternal(a, contentContainer, currentSite, shell, slideDirection, 0);
    }

    private static void renderInternal(
            final MainActivity a,
            final FrameLayout contentContainer,
            final Cms.Site currentSite,
            final MobileShell shell,
            final int slideDirection,
            final int restoreScrollY) {
        contentContainer.removeAllViews();

        final ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        // mkoai 2.0.39: VidHub-style grouped cards, mkoai dark theme (was light in 2.0.38).
        sv.setBackgroundColor(0xff141414);
        int topInset = getStatusBarHeight(a);
        int bottomDockClearance = a.dp(116) + a.navigationBarHeight();
        sv.setPadding(a.dp(20), topInset + a.dp(14), a.dp(20), bottomDockClearance);
        contentContainer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout rootCol = a.vertical();
        sv.addView(rootCol, new ScrollView.LayoutParams(-1, -2));

        final PageController ctl = new PageController(a, contentContainer, currentSite, shell);
        ctl.activeScroll = sv;

        List<PaneSpec> pages = buildPages(a, currentSite, shell);
        PaneSpec activePane = null;
        if (hasSubPage()) {
            for (PaneSpec p : pages) {
                if (p.title.equals(activeCategoryTitle)) {
                    activePane = p;
                    break;
                }
            }
            if (activePane == null) {
                resetToRoot();
            }
        }
        MobileShell.settingsSubPage = activePane != null ? 1 : 0;

        if (activePane == null) {
            // ==================== LEVEL 1: PRIMARY SETTINGS PAGE ====================
            TextView largeTitle = a.text("設定", 32f, 0xfff5f5f7);
            largeTitle.setTypeface(null, Typeface.BOLD);
            largeTitle.setLetterSpacing(-0.02f);
            rootCol.addView(largeTitle, new LinearLayout.LayoutParams(-1, -2));

            a.gap(rootCol, 6);

            String verText = "mkoai · v" + UpdateManager.installedVersion(a);
            if (a.privateMode) verText += " · 隱私空間";
            TextView verLabel = a.text(verText, 13f, a.privateMode ? COLOR_GREEN : 0xff9ca3af);
            verLabel.setOnClickListener(v -> PrivacySpace.logoTap(a));
            rootCol.addView(verLabel, new LinearLayout.LayoutParams(-1, -2));

            a.gap(rootCol, 22);

            int widthPx = a.windowWidth();
            float density = a.getResources().getDisplayMetrics().density;
            boolean twoCols = (widthPx / Math.max(1f, density)) >= 620f;

            if (!twoCols) {
                // mkoai 2.0.51: flat YouTube-style list — plain rows with
                // an inset hairline divider between them (was spaced cards).
                for (int i = 0; i < pages.size(); i++) {
                    final PaneSpec pane = pages.get(i);
                    View cap = buildSettingsCapsule(a, pane, () -> ctl.openSubPage(pane));
                    rootCol.addView(cap, new LinearLayout.LayoutParams(-1, -2));
                    if (i + 1 < pages.size()) {
                        View divider = new View(a);
                        divider.setBackgroundColor(0x14ffffff);
                        LinearLayout.LayoutParams divLp =
                                new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(1)));
                        divLp.leftMargin = a.dp(40);
                        divLp.topMargin = a.dp(2);
                        divLp.bottomMargin = a.dp(2);
                        rootCol.addView(divider, divLp);
                    }
                }
            } else {
                for (int i = 0; i < pages.size(); i += 2) {
                    LinearLayout row = a.row();
                    row.setGravity(Gravity.TOP);
                    final PaneSpec leftPane = pages.get(i);
                    View leftCap =
                            buildSettingsCapsule(a, leftPane, () -> ctl.openSubPage(leftPane));
                    LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
                    leftLp.rightMargin = a.dp(7);
                    row.addView(leftCap, leftLp);

                    if (i + 1 < pages.size()) {
                        final PaneSpec rightPane = pages.get(i + 1);
                        View rightCap =
                                buildSettingsCapsule(
                                        a, rightPane, () -> ctl.openSubPage(rightPane));
                        LinearLayout.LayoutParams rightLp =
                                new LinearLayout.LayoutParams(0, -2, 1f);
                        rightLp.leftMargin = a.dp(7);
                        row.addView(rightCap, rightLp);
                    } else {
                        LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(0, 1, 1f);
                        emptyLp.leftMargin = a.dp(7);
                        row.addView(new View(a), emptyLp);
                    }
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                    rowLp.bottomMargin = a.dp(12);
                    rootCol.addView(row, rowLp);
                }
            }
        } else {
            // ==================== LEVEL 2: SECONDARY SETTINGS PAGE ====================
            LinearLayout topNav = a.row();
            topNav.setGravity(Gravity.CENTER_VERTICAL);
            topNav.setPadding(0, 0, 0, a.dp(12));

            // mkoai 2.0.51: YouTube-style flat back arrow (was a filled circle).
            TextView backBtn = a.text("‹", 22f, 0xfff5f5f7);
            backBtn.setTag("settings-back");
            backBtn.setGravity(Gravity.CENTER);
            backBtn.setPadding(0, 0, a.dp(1), a.dp(2));
            bindPressFeedback(backBtn);
            backBtn.setOnClickListener(v -> ctl.backToRoot());
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
            backLp.rightMargin = a.dp(12);
            topNav.addView(backBtn, backLp);

            LinearLayout titleStack = a.vertical();
            TextView crumb = a.text("設定", 11.5f, 0xff9ca3af);
            crumb.setOnClickListener(v -> ctl.backToRoot());
            titleStack.addView(crumb);

            TextView subTitle = a.text(activePane.title, 24f, 0xfff5f5f7);
            subTitle.setTypeface(null, Typeface.BOLD);
            titleStack.addView(subTitle);
            topNav.addView(titleStack, new LinearLayout.LayoutParams(0, -2, 1f));
            rootCol.addView(topNav, new LinearLayout.LayoutParams(-1, -2));

            if (activePane.subtitle != null && !activePane.subtitle.isEmpty()) {
                TextView desc = a.text(activePane.subtitle, 13f, 0xff9ca3af);
                desc.setLineSpacing(a.dp(3), 1f);
                desc.setPadding(a.dp(2), 0, a.dp(2), a.dp(16));
                rootCol.addView(desc, new LinearLayout.LayoutParams(-1, -2));
            }

            if (!activePane.children.isEmpty()) {
                for (int i = 0; i < activePane.children.size(); i++) {
                    PaneSpec section = activePane.children.get(i);
                    addSubSectionCard(a, rootCol, section, ctl);
                }
            }
            if (activePane.builder != null) {
                activePane.builder.build(rootCol, ctl);
            }
        }

        if (restoreScrollY > 0) {
            sv.post(() -> sv.scrollTo(0, restoreScrollY));
        }
        if (slideDirection != 0 && !a.reduced) {
            rootCol.setTranslationX(slideDirection > 0 ? a.dp(26) : -a.dp(22));
            rootCol.setAlpha(0.35f);
            rootCol.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(170)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private static void addSubSectionCard(
            MainActivity a, LinearLayout parent, PaneSpec section, PageController ctl) {
        // mkoai 2.0.51: YouTube-style flat section (was a VidHub dark card
        // with a coloured icon badge). Dark colours unchanged; the section
        // is now a small grey label with its rows directly on the page
        // background. Builders and rows are unchanged.
        LinearLayout group = a.vertical();
        group.setPadding(0, a.dp(12), 0, 0);
        TextView secTitle = a.text(section.title, 13f, 0xff9ca3af);
        secTitle.setTypeface(null, Typeface.BOLD);
        secTitle.setPadding(a.dp(2), 0, a.dp(2), 0);
        group.addView(secTitle, new LinearLayout.LayoutParams(-1, -2));
        if (section.subtitle != null && !section.subtitle.isEmpty()) {
            TextView secSub = a.text(section.subtitle, 12f, 0xff9ca3af);
            secSub.setPadding(a.dp(2), a.dp(3), a.dp(2), 0);
            group.addView(secSub, new LinearLayout.LayoutParams(-1, -2));
        }
        a.gap(group, 10);
        if (section.builder != null) {
            section.builder.build(group, ctl);
        }
        LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(-1, -2);
        groupLp.bottomMargin = a.dp(10);
        parent.addView(group, groupLp);
    }

    private static List<PaneSpec> buildPages(
            final MainActivity a, final Cms.Site currentSite, final MobileShell shell) {
        List<PaneSpec> top = new ArrayList<>();

        // 1. 外观 (Sparkles, #BF5AF2)
        PaneSpec wallpaperSub =
                new PaneSpec(
                        "桌布與氛圍",
                        "切換應用背景桌布、呼吸流光與介面顯示比例",
                        ICON_WALLPAPER,
                        COLOR_PURPLE,
                        (body, ctl) -> {
                            final boolean wpEnabled =
                                    a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false);
                            final boolean hasSavedWp =
                                    new File(a.getFilesDir(), "wallpaper.jpg").exists();
                            addChoices(
                                    a,
                                    body,
                                    "應用背景模式",
                                    hasSavedWp
                                            ? new String[] {"深色流光背景", "啟用已存桌布", "相簿選圖…", "圖片網址…"}
                                            : new String[] {"深色流光背景", "相簿選圖…", "圖片網址…"},
                                    wpEnabled && hasSavedWp ? 1 : 0,
                                    idx -> {
                                        if (idx == 0) {
                                            a.prefs
                                                    .edit()
                                                    .putBoolean(Prefs.WALLPAPER_ENABLED, false)
                                                    .apply();
                                            ctl.rerenderInPlace();
                                        } else if (hasSavedWp && idx == 1) {
                                            a.prefs
                                                    .edit()
                                                    .putBoolean(Prefs.WALLPAPER_ENABLED, true)
                                                    .apply();
                                            ctl.rerenderInPlace();
                                        } else if ((hasSavedWp && idx == 2)
                                                || (!hasSavedWp && idx == 1)) {
                                            new SettingsScreen(a).pick(44, "image/*", false);
                                        } else {
                                            new SettingsScreen(a).wallpaperUrl();
                                        }
                                    });

                            int curDim = a.prefs.getInt(Prefs.WALLPAPER_DIM, 60);
                            int dimIdx = curDim <= 45 ? 0 : (curDim >= 75 ? 2 : 1);
                            final int[] dimVals = {40, 60, 80};
                            addChoices(
                                    a,
                                    body,
                                    "桌布遮罩壓暗",
                                    new String[] {"輕柔 40%", "標準 60%", "深邃 80%"},
                                    dimIdx,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.WALLPAPER_DIM, dimVals[idx])
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean ambient = a.prefs.getBoolean(Prefs.HOME_AMBIENT, true);
                            addChoices(
                                    a,
                                    body,
                                    "呼吸式氛圍流光",
                                    new String[] {"開啟", "關閉"},
                                    ambient ? 0 : 1,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.HOME_AMBIENT, idx == 0)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean startupAnim = a.prefs.getBoolean(Prefs.STARTUP_ANIMATION, true);
                            addChoices(
                                    a,
                                    body,
                                    "啟動開屏動畫",
                                    new String[] {"開啟", "關閉"},
                                    startupAnim ? 0 : 1,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.STARTUP_ANIMATION, idx == 0)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean reducedMotion = a.prefs.getBoolean(Prefs.REDUCED, false);
                            addChoices(
                                    a,
                                    body,
                                    "動態效果與過渡",
                                    new String[] {"標準靈動", "減少動態效果"},
                                    reducedMotion ? 1 : 0,
                                    idx -> {
                                        boolean next = (idx == 1);
                                        a.prefs.edit().putBoolean(Prefs.REDUCED, next).apply();
                                        a.reduced = next;
                                        ctl.rerenderInPlace();
                                    });

                            int uiScale = a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100);
                            final int[] scaleVals = {90, 100, 110};
                            int scaleIdx = uiScale <= 92 ? 0 : (uiScale >= 108 ? 2 : 1);
                            addChoices(
                                    a,
                                    body,
                                    "全域性介面縮放",
                                    new String[] {"緊湊 90%", "標準 100%", "放大 110%"},
                                    scaleIdx,
                                    idx -> {
                                        int target = scaleVals[idx];
                                        if (target != uiScale) {
                                            a.prefs
                                                    .edit()
                                                    .putInt(Prefs.UI_SCALE_PERCENT, target)
                                                    .commit();
                                            a.recreate();
                                        }
                                    });
                        });

        PaneSpec glassSub =
                new PaneSpec(
                        "液態玻璃",
                        "實時調節導航欄、膠囊與卡片的磨砂半透明質感",
                        ICON_DROPLET,
                        COLOR_TEAL,
                        (body, ctl) -> {
                            int savedIntensity =
                                    a.prefs.getInt(
                                            Prefs.MOBILE_GLASS_INTENSITY,
                                            a.prefs.getBoolean(Prefs.REDUCED_TRANSPARENCY, false)
                                                    ? 100
                                                    : 65);
                            addGlassSliderCard(a, body, savedIntensity, ctl);

                            int presetIdx;
                            if (savedIntensity >= 96) presetIdx = 3;
                            else if (savedIntensity >= 78) presetIdx = 2;
                            else if (savedIntensity <= 45) presetIdx = 0;
                            else presetIdx = 1;
                            final int[] presets = {35, 65, 85, 100};
                            addChoices(
                                    a,
                                    body,
                                    "快速濃度預設",
                                    new String[] {"輕盈通透", "標準玻璃", "深邃磨砂", "純色實底"},
                                    presetIdx,
                                    idx -> {
                                        int val = presets[idx];
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.MOBILE_GLASS_INTENSITY, val)
                                                .putBoolean(Prefs.REDUCED_TRANSPARENCY, val >= 96)
                                                .apply();
                                        GlassworkSurface.invalidateBackdrop(a.root);
                                        ctl.rerenderInPlace();
                                    });
                        });

        PaneSpec appearancePane =
                new PaneSpec("外觀", "桌布、流光與液態玻璃", ICON_SPARKLES, COLOR_PURPLE, null)
                        .addChild(wallpaperSub)
                        .addChild(glassSub);
        top.add(appearancePane);

        // 2. 浏览 (Grid, #0A84FF)
        PaneSpec posterCardSub =
                new PaneSpec(
                        "海報與卡片",
                        "自定義首頁網格列數密度與海報下方副標題資訊",
                        ICON_POSTER,
                        COLOR_BLUE,
                        (body, ctl) -> {
                            int density = a.prefs.getInt(Prefs.MOBILE_POSTER_DENSITY, 1);
                            addChoices(
                                    a,
                                    body,
                                    "海報排布密度",
                                    new String[] {"舒展", "標準", "緊湊"},
                                    Math.max(0, Math.min(2, density)),
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.MOBILE_POSTER_DENSITY, idx)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            int subMode = a.prefs.getInt(Prefs.MOBILE_CARD_SUBTITLE, 0);
                            addChoices(
                                    a,
                                    body,
                                    "卡片副標題內容",
                                    new String[] {"更新與備註", "年份與分類", "所屬片源", "隱藏副標題"},
                                    Math.max(0, Math.min(3, subMode)),
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.MOBILE_CARD_SUBTITLE, idx)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });
                        });

        PaneSpec homeModuleSub =
                new PaneSpec(
                        "首頁模組",
                        "控制主頁頂部推薦焦點卡片與歷史續播橫欄",
                        ICON_HOME_MODULE,
                        COLOR_INDIGO,
                        (body, ctl) -> {
                            boolean showHero = a.prefs.getBoolean(Prefs.SHOW_HERO_BANNER, true);
                            addChoices(
                                    a,
                                    body,
                                    "今日首推焦點卡片",
                                    new String[] {"顯示", "隱藏"},
                                    showHero ? 0 : 1,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.SHOW_HERO_BANNER, idx == 0)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean showHist = a.prefs.getBoolean(Prefs.MOBILE_SHOW_HISTORY, true);
                            addChoices(
                                    a,
                                    body,
                                    "繼續觀看歷史橫欄",
                                    new String[] {"顯示", "隱藏"},
                                    showHist ? 0 : 1,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.MOBILE_SHOW_HISTORY, idx == 0)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });
                        });

        // mkoai 2.0.38 (item 4): the three browsing toggles used to live only in the
        // unreachable legacy SettingsScreen — they are restored here as VidHub switch
        // rows. Defaults: youth OFF, merge ON, hide-netdisk OFF.
        PaneSpec browsePrefsSub =
                new PaneSpec(
                        "瀏覽偏好",
                        "青少年模式、搜尋合併與網盤顯示",
                        ICON_GRID,
                        COLOR_BLUE,
                        (body, ctl) -> {
                            addSwitchRow(
                                    a,
                                    body,
                                    ICON_INFO,
                                    COLOR_ORANGE,
                                    "青少年模式",
                                    "隱藏成人內容與成人混合片源",
                                    Prefs.YOUTH_MODE,
                                    false,
                                    false,
                                    () ->
                                            a.toast(
                                                    a.prefs.getBoolean(Prefs.YOUTH_MODE, false)
                                                            ? "已開啟青少年模式（隱藏成人內容）"
                                                            : "已關閉青少年模式"));
                            // 2.0.42: independent 倫理 switch — follows youth
                            // mode by default (ON when youth is ON) but can
                            // be turned off separately; no effect when youth
                            // mode itself is OFF.
                            addSwitchRow(
                                    a,
                                    body,
                                    ICON_INFO,
                                    COLOR_ORANGE,
                                    "隱藏倫理片",
                                    "青少年模式開啟時，隱藏分類帶有「倫理」的影片",
                                    Prefs.HIDE_LUNLI,
                                    true,
                                    true,
                                    () ->
                                            a.toast(
                                                    a.prefs.getBoolean(Prefs.HIDE_LUNLI, true)
                                                            ? "已隱藏倫理片（需青少年模式開啟才生效）"
                                                            : "已顯示倫理片（其他青少年模式規則仍生效）"));
                            addSwitchRow(
                                    a,
                                    body,
                                    ICON_LAYERS,
                                    COLOR_BLUE,
                                    "搜尋結果合併相同影片",
                                    "同一部片只顯示一張卡片，點卡片自動選源",
                                    Prefs.MERGE_SAME_TITLE,
                                    true,
                                    true,
                                    () ->
                                            a.toast(
                                                    a.prefs.getBoolean(
                                                                    Prefs.MERGE_SAME_TITLE, true)
                                                            ? "已合併相同影片為一張卡片（點卡片自動選源）"
                                                            : "已關閉合併，每個片源各顯示一張卡片"));
                            addSwitchRow(
                                    a,
                                    body,
                                    ICON_CLOUD,
                                    COLOR_TEAL,
                                    "隱藏網盤相關功能",
                                    "隱藏網盤按鈕、網盤線路與掃碼登入",
                                    Prefs.HIDE_NETDISK,
                                    false,
                                    true,
                                    () -> {
                                        a.refresh();
                                        a.toast(
                                                a.prefs.getBoolean(Prefs.HIDE_NETDISK, false)
                                                        ? "已隱藏網盤按鈕、網盤線路與掃碼登入"
                                                        : "已恢復顯示網盤相關功能");
                                    });
                        });

        PaneSpec browsePane =
                new PaneSpec("瀏覽", "首頁內容、海報與卡片資訊", ICON_GRID, COLOR_BLUE, null)
                        .addChild(browsePrefsSub)
                        .addChild(posterCardSub)
                        .addChild(homeModuleSub);
        top.add(browsePane);

        // 3. 播放 (Play Circle, #FF9F0A)
        PaneSpec videoQualitySub =
                new PaneSpec(
                        "畫質與畫面",
                        "配置播放器核心、硬解策略、HDR 畫面與音軌輸出",
                        ICON_DISPLAY,
                        COLOR_ORANGE,
                        (body, ctl) -> {
                            // mkoai 2.0.38 (item 22): quality preference. 高畫質優先 pins
                            // the best probed rendition; 流暢優先 picks the best rendition
                            // the current network can sustain (never the lowest while a
                            // better one fits). Default: 高畫質優先.
                            int qp = a.prefs.getInt(Prefs.QUALITY_PREF, Prefs.QUALITY_HIGH);
                            addChoices(
                                    a,
                                    body,
                                    "畫質偏好",
                                    new String[] {"高畫質優先", "流暢優先"},
                                    qp == Prefs.QUALITY_SMOOTH ? 1 : 0,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putInt(
                                                        Prefs.QUALITY_PREF,
                                                        idx == 1
                                                                ? Prefs.QUALITY_SMOOTH
                                                                : Prefs.QUALITY_HIGH)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            int eng = a.prefs.getInt(Prefs.PLAYER_ENGINE, 0);
                            final int[] engVals = {0, 2, 1};
                            int engIdx = eng == 2 ? 1 : (eng == 1 ? 2 : 0);
                            addChoices(
                                    a,
                                    body,
                                    "預設播放器核心",
                                    new String[] {"Exo (Media3)", "IJK (FFmpeg)", "系統播放器"},
                                    engIdx,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.PLAYER_ENGINE, engVals[idx])
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            int dec = a.prefs.getInt(Prefs.DECODER_MODE, 0);
                            addChoices(
                                    a,
                                    body,
                                    "解碼方式",
                                    new String[] {"自動選擇", "硬體解碼", "軟體解碼優先"},
                                    Math.max(0, Math.min(2, dec)),
                                    idx -> {
                                        a.prefs.edit().putInt(Prefs.DECODER_MODE, idx).apply();
                                        ctl.rerenderInPlace();
                                    });

                            int aspect = a.prefs.getInt(Prefs.ASPECT, 0);
                            // 2.0.41: 「自動」 = fit, never crops (was 「自動填滿」,
                            // which zoomed to fill and cropped the picture).
                            addChoices(
                                    a,
                                    body,
                                    "預設畫面比例",
                                    new String[] {"自動", "鋪滿螢幕", "16:9", "4:3", "裁切填充"},
                                    Math.max(0, Math.min(4, aspect)),
                                    idx -> {
                                        a.prefs.edit().putInt(Prefs.ASPECT, idx).apply();
                                        ctl.rerenderInPlace();
                                    });

                            int outMode = a.prefs.getInt(Prefs.VIDEO_OUTPUT, 0);
                            addChoices(
                                    a,
                                    body,
                                    "畫面渲染輸出",
                                    new String[] {"保真 HDR (Surface)", "相容調色 (Texture)"},
                                    outMode == 1 ? 1 : 0,
                                    idx -> {
                                        a.prefs.edit().putInt(Prefs.VIDEO_OUTPUT, idx).apply();
                                        ctl.rerenderInPlace();
                                    });

                            int buf = a.prefs.getInt(Prefs.BUFFER_SECONDS, 30);
                            final int[] bufVals = {15, 30, 50};
                            int bufIdx = buf <= 20 ? 0 : (buf >= 45 ? 2 : 1);
                            addChoices(
                                    a,
                                    body,
                                    "網路預緩衝時長",
                                    new String[] {"15 秒", "30 秒", "50 秒"},
                                    bufIdx,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.BUFFER_SECONDS, bufVals[idx])
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean swAudio = a.prefs.getBoolean(Prefs.SOFTWARE_AUDIO, false);
                            addChoices(
                                    a,
                                    body,
                                    "相容音訊軟解",
                                    new String[] {"關閉 (預設直通)", "開啟相容軟解"},
                                    swAudio ? 1 : 0,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.SOFTWARE_AUDIO, idx == 1)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean preferAac = a.prefs.getBoolean(Prefs.PREFER_AAC, false);
                            addChoices(
                                    a,
                                    body,
                                    "多音軌優先 AAC",
                                    new String[] {"預設音軌", "優先 AAC"},
                                    preferAac ? 1 : 0,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.PREFER_AAC, idx == 1)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean tunnel = a.prefs.getBoolean(Prefs.TUNNELED_PLAYBACK, false);
                            addChoices(
                                    a,
                                    body,
                                    "4K HDR 硬體隧道",
                                    new String[] {"關閉", "開啟"},
                                    tunnel ? 1 : 0,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.TUNNELED_PLAYBACK, idx == 1)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            int pic = a.prefs.getInt(Prefs.PICTURE_EFFECT, 0);
                            addChoices(
                                    a,
                                    body,
                                    "畫面色彩預設",
                                    new String[] {"原始", "明亮", "影院", "柔和"},
                                    Math.max(0, Math.min(3, pic)),
                                    idx -> {
                                        a.prefs.edit().putInt(Prefs.PICTURE_EFFECT, idx).apply();
                                        ctl.rerenderInPlace();
                                    });

                            int sfx = a.prefs.getInt(Prefs.AUDIO_EFFECT, 0);
                            addChoices(
                                    a,
                                    body,
                                    "空間與人聲音效",
                                    new String[] {"原聲", "影院", "人聲清晰", "柔和低音"},
                                    Math.max(0, Math.min(3, sfx)),
                                    idx -> {
                                        a.prefs.edit().putInt(Prefs.AUDIO_EFFECT, idx).apply();
                                        ctl.rerenderInPlace();
                                    });

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_DISPLAY,
                                    COLOR_ORANGE,
                                    "本機解碼元件報告",
                                    "檢視當前裝置支援的硬解與內建音訊擴充套件",
                                    () ->
                                            new AppDialog.Builder(a)
                                                    .setTitle("解碼元件")
                                                    .setMessage(DecoderSupport.description(false))
                                                    .setPositiveButton("確定", null)
                                                    .show());
                        });

        PaneSpec speedEpisodeSub =
                new PaneSpec(
                        "倍速與選集",
                        "預設起播倍速、劇集排列順序、彈幕與字幕外觀",
                        ICON_SPEED,
                        COLOR_GREEN,
                        (body, ctl) -> {
                            final String[] speedLabels = {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×"};
                            final float[] speedVals = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
                            float curSpeed = a.prefs.getFloat(Prefs.PLAY_SPEED, 1.0f);
                            int speedIdx = 1;
                            for (int i = 0; i < speedVals.length; i++) {
                                if (Math.abs(curSpeed - speedVals[i]) < 0.05f) {
                                    speedIdx = i;
                                    break;
                                }
                            }
                            addChoices(
                                    a,
                                    body,
                                    "預設起播倍速",
                                    speedLabels,
                                    speedIdx,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putFloat(Prefs.PLAY_SPEED, speedVals[idx])
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean desc = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
                            addChoices(
                                    a,
                                    body,
                                    "詳情頁劇集排序",
                                    new String[] {"正序 (第 1 集起)", "倒序 (最新集優先)"},
                                    desc ? 1 : 0,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.EPISODES_DESCENDING, idx == 1)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean autoNext = a.prefs.getBoolean(Prefs.AUTO_NEXT, true);
                            addChoices(
                                    a,
                                    body,
                                    "自動連播下一集",
                                    new String[] {"開啟", "關閉"},
                                    autoNext ? 0 : 1,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.AUTO_NEXT, idx == 0)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            // mkoai 2.0.48: 自動選源直接播放（預設開啟）。
                            // 開：按播放自動選源、直接播放，絕不跳出選源
                            // 清單；關：按播放先顯示「選擇片源」。詳情頁
                            // 手動點「片源」挑源不受此開關影響。
                            boolean autoDirectPlay =
                                    a.prefs.getBoolean(Prefs.AUTO_DIRECT_PLAY, true);
                            addChoices(
                                    a,
                                    body,
                                    "自動選源直接播放",
                                    new String[] {"開啟", "關閉"},
                                    autoDirectPlay ? 0 : 1,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.AUTO_DIRECT_PLAY, idx == 0)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            int rep = a.prefs.getInt(Prefs.REPEAT_MODE, 0);
                            addChoices(
                                    a,
                                    body,
                                    "迴圈播放模式",
                                    new String[] {"播完停止", "單集迴圈", "列表迴圈"},
                                    Math.max(0, Math.min(2, rep)),
                                    idx -> {
                                        a.prefs.edit().putInt(Prefs.REPEAT_MODE, idx).apply();
                                        ctl.rerenderInPlace();
                                    });

                            boolean danmaku = a.prefs.getBoolean(Prefs.DANMAKU_DEFAULT, true);
                            addChoices(
                                    a,
                                    body,
                                    "自動載入彈幕",
                                    new String[] {"開啟", "關閉"},
                                    danmaku ? 0 : 1,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putBoolean(Prefs.DANMAKU_DEFAULT, idx == 0)
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            int subStyle =
                                    Prefs.clampSubtitleStyle(
                                            a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
                            addChoices(
                                    a,
                                    body,
                                    "外掛與內封字幕樣式",
                                    SubtitleOverlayView.STYLE_LABELS,
                                    subStyle,
                                    idx -> {
                                        a.prefs.edit().putInt(Prefs.SUBTITLE_STYLE, idx).apply();
                                        ctl.rerenderInPlace();
                                    });

                            int subSize =
                                    Prefs.clampSubtitleSize(
                                            a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
                            int subBottom =
                                    Prefs.clampSubtitleBottom(
                                            a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_DISPLAY,
                                    COLOR_BLUE,
                                    "字幕大小與位置全屏調校",
                                    "當前字號 " + subSize + " · 距底 " + subBottom + "dp (支援雙指縮放與拖拽)",
                                    () ->
                                            PlaybackControls.showSubtitleAdjuster(
                                                    a, null, ctl::rerenderInPlace));
                        });

        PaneSpec introOutroSub =
                new PaneSpec(
                        "片頭與片尾",
                        "設定起播自動跳過片頭與臨近片尾自動切集秒數",
                        ICON_SKIP,
                        COLOR_CORAL,
                        (body, ctl) -> {
                            final int[] skipPresets = {0, 30, 60, 90, 120};
                            int curIntro =
                                    Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_INTRO, 0));
                            int introIdx = matchPresetOrCustom(skipPresets, curIntro);
                            String customIntro =
                                    introIdx == 5 ? "精準調校 (" + curIntro + "s)…" : "精準調校…";
                            addChoices(
                                    a,
                                    body,
                                    "自動跳過片頭",
                                    new String[] {"關閉", "30s", "60s", "90s", "120s", customIntro},
                                    introIdx,
                                    idx -> {
                                        if (idx < skipPresets.length) {
                                            a.prefs
                                                    .edit()
                                                    .putInt(Prefs.SKIP_INTRO, skipPresets[idx])
                                                    .apply();
                                            ctl.rerenderInPlace();
                                        } else {
                                            PlaybackControls.showSkipAdjuster(
                                                    a, true, 0, 0, ctl::rerenderInPlace);
                                        }
                                    });

                            int curOutro =
                                    Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_OUTRO, 0));
                            int outroIdx = matchPresetOrCustom(skipPresets, curOutro);
                            String customOutro =
                                    outroIdx == 5 ? "精準調校 (" + curOutro + "s)…" : "精準調校…";
                            addChoices(
                                    a,
                                    body,
                                    "自動跳過片尾",
                                    new String[] {"關閉", "30s", "60s", "90s", "120s", customOutro},
                                    outroIdx,
                                    idx -> {
                                        if (idx < skipPresets.length) {
                                            a.prefs
                                                    .edit()
                                                    .putInt(Prefs.SKIP_OUTRO, skipPresets[idx])
                                                    .apply();
                                            ctl.rerenderInPlace();
                                        } else {
                                            PlaybackControls.showSkipAdjuster(
                                                    a, false, 0, 0, ctl::rerenderInPlace);
                                        }
                                    });
                        });

        PaneSpec playbackPane =
                new PaneSpec("播放", "畫質、倍速、選集與自動跳過", ICON_PLAY_CIRCLE, COLOR_ORANGE, null)
                        .addChild(videoQualitySub)
                        .addChild(speedEpisodeSub)
                        .addChild(introOutroSub);
        top.add(playbackPane);

        // 4. 订阅与账号 (Layers, #30D158)
        PaneSpec subscriptionPane =
                new PaneSpec(
                        "訂閱與賬號",
                        "片源管理、網盤掃碼與配置",
                        ICON_LAYERS,
                        COLOR_GREEN,
                        (body, ctl) -> {
                            String curSiteName =
                                    currentSite != null
                                            ? currentSite.name
                                            : (a.config != null && !a.config.sites.isEmpty()
                                                    ? "已載入 " + a.config.sites.size() + " 個片源"
                                                    : "暫未匯入訂閱源");
                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_LAYERS,
                                    COLOR_GREEN,
                                    "切換當前預設片源",
                                    "當前：" + curSiteName,
                                    a::chooseSources);

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_GRID,
                                    COLOR_BLUE,
                                    "片源排序與顯隱管理",
                                    "置頂常用片源或隱藏不需要參與檢索的站點",
                                    () -> SourcePicker.manage(a));

                            if (a.config != null && a.config.depots.length() > 1) {
                                addActionCapsule(
                                        a,
                                        body,
                                        ICON_LAYERS,
                                        COLOR_INDIGO,
                                        "切換多倉子倉庫線路",
                                        "當前配置包含 " + a.config.depots.length() + " 條子倉庫，點選切換",
                                        () -> SourcePicker.showWarehouses(a));
                            }

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_TRAY,
                                    COLOR_TEAL,
                                    "匯入或更新影視訂閱",
                                    "支援一鍵讀取剪貼簿、輸入訂閱連結或選擇本地包",
                                    () -> MobileShell.showMobileImportDialog(a));

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_DISPLAY,
                                    COLOR_TEAL,
                                    "推送來源到電視",
                                    "掃描電視 QR 碼，把源網址或 JSON 推過去（同一 Wi-Fi）",
                                    () -> new PhonePushDialog(a).show());

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_PLAY_CIRCLE,
                                    COLOR_ORANGE,
                                    "匯入或管理電視直播源",
                                    "支援 M3U、TXT (#genre#) 與 JSON 直播訂閱地址",
                                    () -> SourceController.showLiveImportDialog(a));

                            // mkoai: hidden when the user turns on 「隱藏網盤相關功能」.
                            if (!a.prefs.getBoolean(Prefs.HIDE_NETDISK, false)) {
                                addActionCapsule(
                                        a,
                                        body,
                                        ICON_CLOUD,
                                        COLOR_GREEN,
                                        "我的雲盤 · 掃碼登入與線路",
                                        "管理夸克、UC、百度、阿里、115 等授權與解析排序",
                                        () -> AccountPanel.show(a));
                            }

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_DISPLAY,
                                    COLOR_PURPLE,
                                    "連線 Emby 媒體伺服器",
                                    "直連家庭 NAS 或遠端 Emby 媒體庫流暢播放",
                                    () -> EmbyEngine.connect(a));

                            if (CatPawSettings.available(a)) {
                                addActionCapsule(
                                        a,
                                        body,
                                        ICON_CLOUD,
                                        COLOR_TEAL,
                                        "CatPaw 本機引擎配置",
                                        "管理內嵌 CatPaw 服務埠與網盤憑據",
                                        () -> CatPawSettings.choose(a));
                            }

                            a.gap(body, 6);
                            boolean cspEnabled = a.prefs.getBoolean(Prefs.EXPERIMENTAL_CSP, true);
                            addChoices(
                                    a,
                                    body,
                                    "第三方擴充套件片源引擎",
                                    new String[] {"啟用", "停用"},
                                    cspEnabled ? 0 : 1,
                                    idx -> {
                                        final boolean next = (idx == 0);
                                        if (next && !cspEnabled) {
                                            new AppDialog.Builder(a)
                                                    .setTitle("執行配置源提供的擴充套件？")
                                                    .setMessage("請僅啟用來自你信任訂閱源的擴充套件元件。")
                                                    .setNegativeButton("取消", null)
                                                    .setPositiveButton(
                                                            "啟用",
                                                            (d, w) -> {
                                                                a.prefs
                                                                        .edit()
                                                                        .putBoolean(
                                                                                Prefs
                                                                                        .EXPERIMENTAL_CSP,
                                                                                true)
                                                                        .apply();
                                                                a.closeEngines();
                                                                ctl.rerenderInPlace();
                                                            })
                                                    .show();
                                        } else {
                                            a.prefs
                                                    .edit()
                                                    .putBoolean(Prefs.EXPERIMENTAL_CSP, next)
                                                    .apply();
                                            a.closeEngines();
                                            ctl.rerenderInPlace();
                                        }
                                    });

                            int threads = a.prefs.getInt(Prefs.SEARCH_THREADS, 6);
                            final int[] threadVals = {2, 4, 6};
                            int threadIdx = threads <= 2 ? 0 : (threads <= 4 ? 1 : 2);
                            addChoices(
                                    a,
                                    body,
                                    "多源聚合併發檢索數",
                                    new String[] {"2 源 · 輕量", "4 源 · 均衡", "6 源 · 快速"},
                                    threadIdx,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.SEARCH_THREADS, threadVals[idx])
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });

                            int timeout = a.prefs.getInt(Prefs.SEARCH_TIMEOUT, 20);
                            final int[] timeoutVals = {10, 20, 30};
                            int timeoutIdx = timeout <= 12 ? 0 : (timeout >= 25 ? 2 : 1);
                            addChoices(
                                    a,
                                    body,
                                    "單源檢索等待上限",
                                    new String[] {"10 秒", "20 秒", "30 秒"},
                                    timeoutIdx,
                                    idx -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.SEARCH_TIMEOUT, timeoutVals[idx])
                                                .apply();
                                        ctl.rerenderInPlace();
                                    });
                        });
        top.add(subscriptionPane);

        // 5. 数据 (Tray, #5AC8FA)
        PaneSpec backupRestoreSub =
                new PaneSpec(
                        "備份與恢復",
                        "匯出跨裝置通用的 JSON 備份包，包含訂閱源、收藏片庫、觀看歷史與偏好設定",
                        ICON_BACKUP,
                        COLOR_TEAL,
                        (body, ctl) -> {
                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_BACKUP,
                                    COLOR_TEAL,
                                    "匯出備份檔案",
                                    "儲存 mkoai-backup.json 到手機儲存",
                                    () -> new SettingsScreen(a).pick(45, "application/json", true));

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_TRAY,
                                    COLOR_BLUE,
                                    "從備份檔案恢復",
                                    "選擇已匯出的備份包還原配置與觀影記錄 (不影響網盤登入)",
                                    () -> new SettingsScreen(a).pick(46, "*/*", false));
                        });

        PaneSpec cacheDiagSub =
                new PaneSpec(
                        "快取與診斷",
                        "釋放磁碟圖片快取、清理搜尋詞記錄或檢視執行診斷資訊",
                        ICON_BROOM,
                        COLOR_ORANGE,
                        (body, ctl) -> {
                            long cacheBytes = computeDirBytes(a.getCacheDir());
                            String sizeText =
                                    String.format(
                                            Locale.US,
                                            "當前快取約 %.1f MB · 清理後保留收藏、歷史與雲盤登入",
                                            cacheBytes / (1024.0 * 1024.0));
                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_BROOM,
                                    COLOR_ORANGE,
                                    "清理圖片快取與搜尋記錄",
                                    sizeText,
                                    () -> {
                                        a.images.evictAll();
                                        a.homeData.clear();
                                        a.prefs.edit().remove(Prefs.GLOBAL_SEARCH_HISTORY).apply();
                                        clearImageCacheFiles(a.getCacheDir());
                                        a.toast("已清理圖片快取與搜尋記錄");
                                        ctl.rerenderInPlace();
                                    });

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_INFO,
                                    COLOR_BLUE,
                                    "檢視執行診斷日誌",
                                    "排查片源網路請求、擴充套件載入或解碼器異常記錄",
                                    () -> new SettingsScreen(a).diagnostics());

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_LAYERS,
                                    COLOR_PURPLE,
                                    a.privateMode ? "退出隱私空間" : "隱私空間",
                                    a.privateMode
                                            ? "當前處於獨立加密空間，點選返回常規空間"
                                            : "連續點選設定頁版本號 5 次亦可快速進入獨立私密空間",
                                    () -> {
                                        for (int i = 0; i < 5; i++) PrivacySpace.logoTap(a);
                                    });

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_SKIP,
                                    COLOR_CORAL,
                                    "恢復外觀與播放預設設定",
                                    "重置介面與播放器引數，保留已匯入的訂閱源與雲盤授權",
                                    () ->
                                            new AppDialog.Builder(a)
                                                    .setTitle("恢復預設設定？")
                                                    .setMessage(
                                                            "外觀與播放參數將恢復初始狀態，已匯入的片源、收藏與雲盤登入均會保留。")
                                                    .setNegativeButton("取消", null)
                                                    .setPositiveButton(
                                                            "恢復預設",
                                                            (d, w) -> {
                                                                SharedPreferences.Editor ed =
                                                                        a.prefs.edit();
                                                                for (String k : Prefs.KEYS) {
                                                                    ed.remove(k);
                                                                }
                                                                ed.remove(
                                                                                Prefs
                                                                                        .MOBILE_GLASS_INTENSITY)
                                                                        .remove(
                                                                                Prefs
                                                                                        .MOBILE_POSTER_DENSITY)
                                                                        .remove(
                                                                                Prefs
                                                                                        .MOBILE_CARD_SUBTITLE)
                                                                        .remove(
                                                                                Prefs
                                                                                        .MOBILE_SHOW_HISTORY)
                                                                        .commit();
                                                                a.reduced = false;
                                                                resetToRoot();
                                                                a.recreate();
                                                            })
                                                    .show());
                        });

        PaneSpec dataPane =
                new PaneSpec("資料", "跨平臺備份、恢復與快取清理", ICON_TRAY, COLOR_TEAL, null)
                        .addChild(backupRestoreSub)
                        .addChild(cacheDiagSub);
        top.add(dataPane);

        // 6. 关于与更新 (Info Circle, #5E5CE6)
        PaneSpec aboutPane =
                new PaneSpec(
                        "關於與更新",
                        "版本資訊與安裝包下載",
                        ICON_INFO,
                        COLOR_INDIGO,
                        (body, ctl) -> {
                            // mkoai 2.0.51: flat (was a rounded card).
                            LinearLayout brandCard = a.vertical();
                            brandCard.setGravity(Gravity.CENTER_HORIZONTAL);
                            brandCard.setPadding(a.dp(20), a.dp(22), a.dp(20), a.dp(20));

                            ImageView logo = new ImageView(a);
                            try {
                                logo.setImageResource(R.drawable.logo_mark);
                            } catch (Throwable ignored) {
                                logo.setImageResource(R.mipmap.ic_launcher);
                            }
                            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            brandCard.addView(
                                    logo, new LinearLayout.LayoutParams(a.dp(52), a.dp(52)));

                            a.gap(brandCard, 12);
                            TextView appName = a.text("mkoai", 22f, 0xfff5f5f7);
                            appName.setTypeface(null, Typeface.BOLD);
                            brandCard.addView(appName);

                            a.gap(brandCard, 4);
                            TextView ver =
                                    a.text(
                                            "當前版本 v" + UpdateManager.installedVersion(a),
                                            13f,
                                            0xffd1d1da);
                            brandCard.addView(ver);

                            LinearLayout.LayoutParams brandLp =
                                    new LinearLayout.LayoutParams(-1, -2);
                            brandLp.bottomMargin = a.dp(14);
                            body.addView(brandCard, brandLp);

                            addActionCapsule(
                                    a,
                                    body,
                                    ICON_INFO,
                                    COLOR_INDIGO,
                                    "檢查軟體更新",
                                    "線上檢測新版本說明、校驗簽名並下載安裝包",
                                    () ->
                                            a.open(
                                                    new MainActivity.Screen(
                                                            () -> new UpdateScreen(a).show()),
                                                    true));

                            TextView legal =
                                    a.text(
                                            "mkoai 僅為本地媒體播放與訂閱解析工具，內建不包含任何影視內容資源。請僅匯入您擁有合法授權的訂閱配置與個人網盤內容。",
                                            12f,
                                            0x8cebebf5);
                            legal.setLineSpacing(a.dp(4), 1f);
                            legal.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(4));
                            body.addView(legal, new LinearLayout.LayoutParams(-1, -2));
                        });
        top.add(aboutPane);

        // mkoai: standalone disclaimer (user request 2026-11-12) — a full
        // disclaimer page in Settings, not just the one-liner in About.
        PaneSpec disclaimerPane =
                new PaneSpec(
                        "免責聲明",
                        "內容來源與使用責任",
                        ICON_INFO,
                        COLOR_INDIGO,
                        (body, ctl) -> {
                            TextView disclaimer =
                                    a.text(
                                            "免責聲明\n\n"
                                                    + "1. mkoai 僅為本地媒體播放與訂閱解析工具，內建不包含任何影視、音樂或其他媒體內容資源。\n\n"
                                                    + "2. App 內所有影視內容均來自使用者自行匯入的第三方訂閱配置、來源規則或個人網盤，其內容之合法性、準確性與可用性由該等第三方及使用者自行負責，與 mkoai 及開發者無關。\n\n"
                                                    + "3. 使用者應僅匯入擁有合法授權的內容，並遵守所在地的法律法規，請勿將本 App 用於任何侵權用途。\n\n"
                                                    + "4. 本 App 不對第三方內容的品質、完整性或持續可用性作任何保證；播放中斷、來源失效等情形與本 App 無關。\n\n"
                                                    + "5. 使用本 App 即表示您已閱讀並同意上述聲明。",
                                            13.5f,
                                            0xffe5e7eb);
                            disclaimer.setLineSpacing(a.dp(6), 1f);
                            disclaimer.setPadding(a.dp(6), a.dp(10), a.dp(6), a.dp(4));
                            body.addView(disclaimer, new LinearLayout.LayoutParams(-1, -2));
                        });
        top.add(disclaimerPane);

        return top;
    }

    /**
     * 1:1 {@code SettingsCapsule} from {@code ios_settings.dart}:
     * 36dp stadium radius, 18dp H / 14dp V padding, 38x38dp 18%-alpha circular badge + 21dp glyph,
     * 17sp w700 title + 12sp subtitle, right '›'.
     */
    private static View buildSettingsCapsule(MainActivity a, PaneSpec pane, Runnable onTap) {
        return buildCapsuleRow(a, pane.iconKind, pane.color, pane.title, pane.subtitle, onTap);
    }

    private static void addActionCapsule(
            MainActivity a,
            LinearLayout parent,
            int iconKind,
            int color,
            String title,
            String subtitle,
            Runnable onTap) {
        View v = buildCapsuleRow(a, iconKind, color, title, subtitle, onTap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(10);
        parent.addView(v, lp);
    }

    private static View buildCapsuleRow(
            MainActivity a,
            int iconKind,
            int color,
            String title,
            String subtitle,
            Runnable onTap) {
        // mkoai 2.0.51: YouTube-style flat list row (was a VidHub dark
        // card with a coloured badge circle). Dark colours unchanged:
        // plain 24dp near-white icon, regular title, grey subtitle,
        // grey chevron, no card container.
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(2), a.dp(12), a.dp(2), a.dp(12));

        CapsuleIconView icon = new CapsuleIconView(a, iconKind, 0xfff5f5f7);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(24), a.dp(24));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);

        LinearLayout col = a.vertical();
        TextView t = a.text(title, 16f, 0xfff5f5f7);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = a.text(subtitle, 12f, 0xff9ca3af);
            s.setMaxLines(2);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(0, a.dp(2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView chev = a.text("›", 17f, 0xff9ca3af);
        chev.setPadding(a.dp(8), 0, 0, a.dp(1));
        row.addView(chev);

        bindPressFeedback(row);
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    /**
     * 1:1 {@code SettingsChoices} from {@code ios_settings.dart}:
     * 13sp w600 #FFD1D1DA label + 8dp gap + wrapping {@code AppleCapsuleButton} pills (selected in
     * #FF0A84FF with '✓ ' prefix).
     */
    private static void addChoices(
            MainActivity a,
            LinearLayout parent,
            String label,
            String[] options,
            int selectedIndex,
            final ChoiceAction onSelect) {
        LinearLayout group = a.vertical();
        TextView heading = a.text(label, 13f, 0xff9ca3af);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(a.dp(4), 0, a.dp(4), a.dp(8));
        group.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        FlowWrapLayout wrap = new FlowWrapLayout(a, a.dp(8), a.dp(8));
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            boolean active = (i == selectedIndex);
            String pillLabel = active ? "✓ " + options[i] : options[i];
            TextView pill = a.text(pillLabel, 13f, active ? Color.WHITE : 0xfff5f5f7);
            pill.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            pill.setSingleLine(true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(a.dp(999));
            if (active) {
                // mkoai 2.0.39: coral selected pill (mkoai accent #E34B58).
                bg.setColor(0xffe34b58);
            } else {
                bg.setColor(0xff1e1e22);
                bg.setStroke(Math.max(1, a.dp(1)), 0x33ffffff);
            }
            pill.setBackground(bg);
            bindPressFeedback(pill);
            pill.setOnClickListener(v -> onSelect.onSelect(idx));
            wrap.addView(pill);
        }
        group.addView(wrap, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(16);
        parent.addView(group, lp);
    }

    /**
     * mkoai 2.0.38 (item 4): VidHub-style switch row for the light settings cards —
     * coloured icon badge + title + subtitle on the left, iOS green switch on the
     * right. Behaviour mirrors the legacy SettingsScreen.toggle() rows (persist +
     * after-action); the row itself is the tap target.
     */
    private static void addSwitchRow(
            final MainActivity a,
            final LinearLayout parent,
            final int iconKind,
            final int color,
            final String title,
            final String subtitle,
            final String key,
            final boolean defaultValue,
            final boolean showDivider,
            final Runnable after) {
        if (showDivider) {
            View divider = new View(a);
            divider.setBackgroundColor(0x14ffffff);
            LinearLayout.LayoutParams divLp =
                    new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(1)));
            // mkoai 2.0.51: aligns with the text column (2 pad + 24 icon + 12 gap).
            divLp.leftMargin = a.dp(38);
            parent.addView(divider, divLp);
        }
        boolean initial = a.prefs.getBoolean(key, defaultValue);
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(2), a.dp(12), a.dp(2), a.dp(12));

        // mkoai 2.0.51: YouTube-style plain icon (was a coloured badge
        // circle). Switch, colours and behaviour unchanged.
        CapsuleIconView icon = new CapsuleIconView(a, iconKind, 0xfff5f5f7);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(24), a.dp(24));
        iconLp.rightMargin = a.dp(12);
        row.addView(icon, iconLp);

        LinearLayout copy = a.vertical();
        TextView t = a.text(title, 15f, 0xfff5f5f7);
        copy.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = a.text(subtitle, 12f, 0xff9ca3af);
            s.setPadding(0, a.dp(2), 0, 0);
            copy.addView(s);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        final MobileShell.IosSwitchView toggleSwitch = new MobileShell.IosSwitchView(a, initial);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(a.dp(50), a.dp(30));
        switchLp.leftMargin = a.dp(12);
        row.addView(toggleSwitch, switchLp);

        row.setTag("setting-" + key);
        row.setClickable(true);
        bindPressFeedback(row);
        row.setOnClickListener(
                v -> {
                    boolean nextVal = !a.prefs.getBoolean(key, defaultValue);
                    a.prefs.edit().putBoolean(key, nextVal).apply();
                    toggleSwitch.setChecked(nextVal, !a.reduced);
                    if (after != null) after.run();
                });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void addGlassSliderCard(
            final MainActivity a, LinearLayout parent, int currentVal, final PageController ctl) {
        // mkoai 2.0.51: flat (was a dark card). Slider colours unchanged.
        LinearLayout box = a.vertical();
        box.setPadding(a.dp(2), a.dp(6), a.dp(2), a.dp(6));

        LinearLayout topRow = a.row();
        TextView label = a.text("玻璃磨砂強度", 14f, 0xfff5f5f7);
        label.setTypeface(null, Typeface.BOLD);
        topRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView valTv = a.text(currentVal + "%", 13.5f, COLOR_TEAL);
        valTv.setTypeface(null, Typeface.BOLD);
        topRow.addView(valTv);
        box.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        a.gap(box, 8);
        SeekBar bar = new SeekBar(a);
        bar.setMax(100);
        bar.setProgress(Math.max(0, Math.min(100, currentVal)));
        if (Build.VERSION.SDK_INT >= 21) {
            // mkoai 2.0.39: coral progress on the dark card.
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe34b58));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            bar.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0x33ffffff));
        }
        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        valTv.setText(progress + "%");
                        if (fromUser) {
                            a.prefs
                                    .edit()
                                    .putInt(Prefs.MOBILE_GLASS_INTENSITY, progress)
                                    .putBoolean(Prefs.REDUCED_TRANSPARENCY, progress >= 96)
                                    .apply();
                            if (ctl.shell != null) ctl.shell.applyAppearancePreferences();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        GlassworkSurface.invalidateBackdrop(a.root);
                        ctl.rerenderInPlace();
                    }
                });
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = a.dp(16);
        parent.addView(box, lp);
    }

    private static int matchPresetOrCustom(int[] presets, int val) {
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == val) return i;
        }
        return presets.length;
    }

    private static int getStatusBarHeight(MainActivity a) {
        int resId = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? a.getResources().getDimensionPixelSize(resId) : a.dp(24);
    }

    private static long computeDirBytes(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long sum = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                sum += f.isDirectory() ? computeDirBytes(f) : f.length();
            }
        }
        return sum;
    }

    private static void clearImageCacheFiles(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String n = f.getName();
            if (f.isDirectory()) {
                if ("home_snap_v1".equals(n) || n.contains("img") || n.contains("poster")) {
                    File[] sub = f.listFiles();
                    if (sub != null) for (File sf : sub) sf.delete();
                }
            } else if (n.endsWith(".img")
                    || n.startsWith("mob_hd_poster_")
                    || n.startsWith("tv_wide_")) {
                f.delete();
            }
        }
    }

    private static void bindPressFeedback(View view) {
        view.setOnTouchListener(
                (v, ev) -> {
                    int act = ev.getActionMasked();
                    if (act == MotionEvent.ACTION_DOWN) {
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                    } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                    }
                    return false;
                });
    }

    /** Multi-line wrapping layout matching Flutter's {@code Wrap(spacing, runSpacing)}. */
    static final class FlowWrapLayout extends ViewGroup {
        private final int hSpacing;
        private final int vSpacing;

        FlowWrapLayout(MainActivity a, int hSpacing, int vSpacing) {
            super(a);
            this.hSpacing = hSpacing;
            this.vSpacing = vSpacing;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            if (mode == MeasureSpec.UNSPECIFIED || maxWidth <= 0) {
                maxWidth = getResources().getDisplayMetrics().widthPixels;
            }
            int availW = maxWidth - getPaddingLeft() - getPaddingRight();

            int lineW = 0;
            int lineH = 0;
            int totalH = getPaddingTop() + getPaddingBottom();
            int maxUsedW = 0;

            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(
                        MeasureSpec.makeMeasureSpec(availW, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (lineW > 0 && lineW + hSpacing + cw > availW) {
                    maxUsedW = Math.max(maxUsedW, lineW);
                    totalH += lineH + vSpacing;
                    lineW = cw;
                    lineH = ch;
                } else {
                    lineW = lineW == 0 ? cw : lineW + hSpacing + cw;
                    lineH = Math.max(lineH, ch);
                }
            }
            maxUsedW = Math.max(maxUsedW, lineW);
            totalH += lineH;

            int finalW =
                    mode == MeasureSpec.EXACTLY
                            ? maxWidth
                            : Math.min(maxWidth, maxUsedW + getPaddingLeft() + getPaddingRight());
            setMeasuredDimension(finalW, resolveSize(totalH, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int availW = (r - l) - getPaddingRight();
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int lineH = 0;

            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > getPaddingLeft() && x + cw > availW) {
                    x = getPaddingLeft();
                    y += lineH + vSpacing;
                    lineH = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + hSpacing;
                lineH = Math.max(lineH, ch);
            }
        }
    }

    /** Crisp anti-aliased Cupertino-inspired vector icons inside each 38x38dp capsule badge. */
    private static final class CapsuleIconView extends View {
        private final int kind;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        CapsuleIconView(MainActivity a, int kind, int color) {
            super(a);
            this.kind = kind;
            stroke.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2f, a.dp(1.8f)));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);

            fill.setColor(color);
            fill.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            path.reset();
            switch (kind) {
                case ICON_SPARKLES:
                    {
                        // 4-point Cupertino sparkle star + secondary star
                        drawStar(c, w * 0.44f, h * 0.52f, w * 0.34f);
                        drawStar(c, w * 0.78f, h * 0.24f, w * 0.16f);
                        break;
                    }
                case ICON_GRID:
                case ICON_POSTER:
                    {
                        float r = w * 0.08f;
                        rect.set(w * 0.14f, h * 0.14f, w * 0.45f, h * 0.45f);
                        c.drawRoundRect(rect, r, r, stroke);
                        rect.set(w * 0.55f, h * 0.14f, w * 0.86f, h * 0.45f);
                        c.drawRoundRect(rect, r, r, stroke);
                        rect.set(w * 0.14f, h * 0.55f, w * 0.45f, h * 0.86f);
                        c.drawRoundRect(rect, r, r, stroke);
                        rect.set(w * 0.55f, h * 0.55f, w * 0.86f, h * 0.86f);
                        c.drawRoundRect(rect, r, r, stroke);
                        break;
                    }
                case ICON_PLAY_CIRCLE:
                    {
                        c.drawCircle(w * 0.5f, h * 0.5f, w * 0.38f, stroke);
                        path.moveTo(w * 0.42f, h * 0.35f);
                        path.lineTo(w * 0.66f, h * 0.50f);
                        path.lineTo(w * 0.42f, h * 0.65f);
                        path.close();
                        c.drawPath(path, fill);
                        break;
                    }
                case ICON_LAYERS:
                    {
                        path.moveTo(w * 0.50f, h * 0.16f);
                        path.lineTo(w * 0.86f, h * 0.34f);
                        path.lineTo(w * 0.50f, h * 0.52f);
                        path.lineTo(w * 0.14f, h * 0.34f);
                        path.close();
                        c.drawPath(path, stroke);
                        c.drawLine(w * 0.16f, h * 0.54f, w * 0.50f, h * 0.70f, stroke);
                        c.drawLine(w * 0.84f, h * 0.54f, w * 0.50f, h * 0.70f, stroke);
                        c.drawLine(w * 0.16f, h * 0.70f, w * 0.50f, h * 0.86f, stroke);
                        c.drawLine(w * 0.84f, h * 0.70f, w * 0.50f, h * 0.86f, stroke);
                        break;
                    }
                case ICON_TRAY:
                case ICON_BACKUP:
                    {
                        rect.set(w * 0.14f, h * 0.42f, w * 0.86f, h * 0.84f);
                        c.drawRoundRect(rect, w * 0.12f, w * 0.12f, stroke);
                        c.drawLine(w * 0.50f, h * 0.14f, w * 0.50f, h * 0.58f, stroke);
                        c.drawLine(w * 0.35f, h * 0.44f, w * 0.50f, h * 0.59f, stroke);
                        c.drawLine(w * 0.65f, h * 0.44f, w * 0.50f, h * 0.59f, stroke);
                        break;
                    }
                case ICON_INFO:
                    {
                        c.drawCircle(w * 0.5f, h * 0.5f, w * 0.38f, stroke);
                        c.drawCircle(w * 0.5f, h * 0.33f, w * 0.05f, fill);
                        c.drawLine(w * 0.5f, h * 0.46f, w * 0.5f, h * 0.70f, stroke);
                        break;
                    }
                case ICON_WALLPAPER:
                case ICON_HOME_MODULE:
                    {
                        rect.set(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.82f);
                        c.drawRoundRect(rect, w * 0.14f, w * 0.14f, stroke);
                        c.drawCircle(w * 0.35f, h * 0.38f, w * 0.07f, fill);
                        path.moveTo(w * 0.20f, h * 0.76f);
                        path.lineTo(w * 0.46f, h * 0.52f);
                        path.lineTo(w * 0.62f, h * 0.66f);
                        path.lineTo(w * 0.78f, h * 0.50f);
                        c.drawPath(path, stroke);
                        break;
                    }
                case ICON_DROPLET:
                    {
                        path.moveTo(w * 0.50f, h * 0.14f);
                        path.cubicTo(
                                w * 0.82f, h * 0.48f, w * 0.80f, h * 0.84f, w * 0.50f, h * 0.86f);
                        path.cubicTo(
                                w * 0.20f, h * 0.84f, w * 0.18f, h * 0.48f, w * 0.50f, h * 0.14f);
                        c.drawPath(path, stroke);
                        break;
                    }
                case ICON_DISPLAY:
                    {
                        rect.set(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.68f);
                        c.drawRoundRect(rect, w * 0.10f, w * 0.10f, stroke);
                        c.drawLine(w * 0.34f, h * 0.84f, w * 0.66f, h * 0.84f, stroke);
                        c.drawLine(w * 0.50f, h * 0.68f, w * 0.50f, h * 0.84f, stroke);
                        break;
                    }
                case ICON_SPEED:
                    {
                        rect.set(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.88f);
                        c.drawArc(rect, 195f, 150f, false, stroke);
                        c.drawLine(w * 0.50f, h * 0.58f, w * 0.70f, h * 0.36f, stroke);
                        c.drawCircle(w * 0.50f, h * 0.58f, w * 0.06f, fill);
                        break;
                    }
                case ICON_SKIP:
                    {
                        path.moveTo(w * 0.20f, h * 0.28f);
                        path.lineTo(w * 0.58f, h * 0.50f);
                        path.lineTo(w * 0.20f, h * 0.72f);
                        path.close();
                        c.drawPath(path, stroke);
                        c.drawLine(w * 0.74f, h * 0.26f, w * 0.74f, h * 0.74f, stroke);
                        break;
                    }
                case ICON_CLOUD:
                    {
                        rect.set(w * 0.14f, h * 0.38f, w * 0.86f, h * 0.78f);
                        c.drawRoundRect(rect, w * 0.20f, w * 0.20f, stroke);
                        rect.set(w * 0.28f, h * 0.20f, w * 0.68f, h * 0.56f);
                        c.drawArc(rect, 195f, 160f, false, stroke);
                        break;
                    }
                default:
                    {
                        c.drawCircle(w * 0.5f, h * 0.5f, w * 0.34f, stroke);
                        break;
                    }
            }
        }

        private void drawStar(Canvas c, float cx, float cy, float r) {
            path.reset();
            float inner = r * 0.30f;
            path.moveTo(cx, cy - r);
            path.quadTo(cx + inner, cy - inner, cx + r, cy);
            path.quadTo(cx + inner, cy + inner, cx, cy + r);
            path.quadTo(cx - inner, cy + inner, cx - r, cy);
            path.quadTo(cx - inner, cy - inner, cx, cy - r);
            path.close();
            c.drawPath(path, fill);
        }
    }
}
