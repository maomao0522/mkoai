// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package app.mkoai.player;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.Locale;

/**
 * iOS Software Update (`通用 -> 软件更新`) inspired update center for Android Mobile and Android TV.
 * mkoai 2.0.42: checks the user's own Drive release manifest for both metadata and APK packages,
 * with SHA-256 + signature verification before installation. No MarcoMovie mirror / GitHub.
 */
final class UpdateScreen {
    static final int INSTALL = 1401;
    final MainActivity a;
    final UpdateManager updates;
    TextView status;
    private TextView heroVersionTitle;
    private TextView heroMetaSubtitle;
    private TextView notesHeading;
    private TextView notesBody;
    private View progressWrap;
    private View progressFill;
    private TextView progressPercentView;
    private Button primaryBtn;
    private Button secondaryActionBtn;
    private TextView modeValueView;

    UpdateScreen(MainActivity host) {
        a = host;
        updates = UpdateManager.get(a);
    }

    private GradientDrawable cardSurface(
            int topColor, int bottomColor, float radiusDp, int stroke) {
        GradientDrawable d =
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM, new int[] {topColor, bottomColor});
        d.setCornerRadius(a.dp(radiusDp));
        if (stroke != 0) {
            d.setStroke(Math.max(1, a.dp(0.8f)), stroke);
        }
        return d;
    }

    void show() {
        a.renderPage("", "");
        if (a.isTvDevice() && a.scroll instanceof TvScrollView)
            ((TvScrollView) a.scroll).readingMode = true;
        a.updateScreen = this;
        boolean tv = a.isTvDevice();

        // Top Navigation Bar (mkoai 2.0.63: Level-2 settings header —
        // flat "‹", "設定" crumb, 24sp bold title; TV branch below keeps
        // its own layout).
        if (!tv) {
            LinearLayout topNav = a.row();
            topNav.setGravity(Gravity.CENTER_VERTICAL);
            topNav.setPadding(0, 0, 0, a.dp(12));
            TextView backBtn = a.text("‹", 22, MainActivity.WHITE);
            backBtn.setGravity(Gravity.CENTER);
            backBtn.setPadding(0, 0, a.dp(1), a.dp(2));
            MobileDetailScreen.bindTouchFeedback(backBtn);
            backBtn.setOnClickListener(v -> a.onBackPressed());
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
            backLp.rightMargin = a.dp(12);
            topNav.addView(backBtn, backLp);

            LinearLayout titleStack = a.vertical();
            TextView crumb = a.text("設定", 11.5f, 0xff9ca3af);
            crumb.setOnClickListener(v -> a.onBackPressed());
            titleStack.addView(crumb);
            TextView heading = a.text("軟體更新", 24, MainActivity.WHITE);
            heading.setTypeface(null, Typeface.BOLD);
            titleStack.addView(heading);
            topNav.addView(titleStack, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView mirrorBadge = a.text("mkoai 發布渠道", 11.5f, 0xffe34b58);
            mirrorBadge.setTypeface(null, Typeface.BOLD);
            mirrorBadge.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
            mirrorBadge.setBackground(cardSurface(0x26e34b58, 0x1ae34b58, 999, 0x55e34b58));
            topNav.addView(mirrorBadge, new LinearLayout.LayoutParams(-2, -2));
            a.body.addView(topNav, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout titleRow = a.row();
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView heading = a.text("軟體更新", 28, MainActivity.WHITE);
            heading.setTypeface(null, Typeface.BOLD);
            titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView mirrorBadge = a.text("mkoai 發布渠道 · 自動校驗簽名", 12.5f, 0xffe34b58);
            mirrorBadge.setTypeface(null, Typeface.BOLD);
            mirrorBadge.setPadding(a.dp(12), a.dp(5), a.dp(12), a.dp(5));
            mirrorBadge.setBackground(cardSurface(0x26e34b58, 0x1ae34b58, 999, 0x55e34b58));
            titleRow.addView(mirrorBadge, new LinearLayout.LayoutParams(-2, -2));
            a.body.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
            a.gap(a.body, 14);
        }

        // =====================================================================
        // 1. iOS SOFTWARE UPDATE HERO CARD
        // =====================================================================
        LinearLayout heroCard = a.vertical();
        heroCard.setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(20));
        // mkoai 2.0.63: neutral dark surface (was a green-tinted dark).
        heroCard.setBackground(cardSurface(0xd81e1e22, 0xe6191920, 24, 0x2effffff));

        LinearLayout headerRow = a.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        // iOS App Squircle Icon Badge
        FrameLayout iconBox = new FrameLayout(a);
        GradientDrawable squircle =
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[] {0xff26262b, 0xff1a1a1e, 0xff121215});
        squircle.setCornerRadius(a.dp(16));
        squircle.setStroke(Math.max(1, a.dp(1)), 0x55e34b58);
        iconBox.setBackground(squircle);
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo_mark);
        FrameLayout.LayoutParams logoLp =
                new FrameLayout.LayoutParams(a.dp(36), a.dp(36), Gravity.CENTER);
        iconBox.addView(logo, logoLp);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(60), a.dp(60));
        iconLp.rightMargin = a.dp(16);
        headerRow.addView(iconBox, iconLp);

        LinearLayout titleCol = a.vertical();
        heroVersionTitle =
                a.text("mkoai " + UpdateManager.installedVersion(a), 19, MainActivity.WHITE);
        heroVersionTitle.setTypeface(null, Typeface.BOLD);
        titleCol.addView(heroVersionTitle);

        heroMetaSubtitle =
                a.text((tv ? "Android TV 端" : "Android 手機端") + " · 正式版渠道", 12.5f, 0xff9ca3af);
        heroMetaSubtitle.setPadding(0, a.dp(3), 0, 0);
        titleCol.addView(heroMetaSubtitle);

        status = a.text("", 13f, 0xffe34b58);
        status.setTag("update-status");
        status.setPadding(0, a.dp(5), 0, 0);
        status.setLineSpacing(a.dp(2), 1f);
        titleCol.addView(status);

        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
        heroCard.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        // Sleek iOS Download Progress Bar (shown when downloading / verifying)
        LinearLayout progContainer = a.vertical();
        progContainer.setPadding(0, a.dp(16), 0, 0);
        LinearLayout progLabelRow = a.row();
        progLabelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView progTitle = a.text("正在從雲端硬碟下載安裝包…", 12, MainActivity.MUTED);
        progLabelRow.addView(progTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        progressPercentView = a.text("0%", 12, 0xff5ac8fa);
        progressPercentView.setTypeface(null, Typeface.BOLD);
        progLabelRow.addView(progressPercentView, new LinearLayout.LayoutParams(-2, -2));
        progContainer.addView(progLabelRow, new LinearLayout.LayoutParams(-1, -2));
        a.gap(progContainer, 6);

        FrameLayout track = new FrameLayout(a);
        track.setBackground(a.bg(0x33ffffff, 999));
        progressFill = new View(a);
        progressFill.setBackground(a.bg(0xffe34b58, 999));
        track.addView(progressFill, new FrameLayout.LayoutParams(0, a.dp(6), Gravity.LEFT));
        progContainer.addView(track, new LinearLayout.LayoutParams(-1, a.dp(6)));
        progressWrap = progContainer;
        progressWrap.setVisibility(View.GONE);
        heroCard.addView(progressWrap, new LinearLayout.LayoutParams(-1, -2));

        // Hairline Divider
        a.gap(heroCard, 16);
        View divider = new View(a);
        divider.setBackgroundColor(0x1fffffff);
        heroCard.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.7f))));
        a.gap(heroCard, 14);

        // Release Notes / Feature Highlights Box
        notesHeading = a.text("關於此更新", 13.5f, MainActivity.WHITE);
        notesHeading.setTypeface(null, Typeface.BOLD);
        heroCard.addView(notesHeading);
        a.gap(heroCard, 6);

        notesBody = a.text("", 13f, 0xffd1d5db);
        notesBody.setTag("update-notes");
        notesBody.setId(View.generateViewId());
        notesBody.setFocusable(tv);
        notesBody.setFocusableInTouchMode(tv);
        if (tv) notesBody.setContentDescription("更新內容，按上下鍵逐段閱讀");
        notesBody.setLineSpacing(a.dp(4), 1f);
        heroCard.addView(notesBody, new LinearLayout.LayoutParams(-1, -2));

        a.gap(heroCard, 18);

        // Primary & Secondary Action Buttons Row
        LinearLayout actionRow = a.row();
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        primaryBtn =
                a.button(
                        "檢查軟體更新",
                        "update-check",
                        () -> {
                            if (updates.busy) {
                                updates.cancel();
                            } else if (updates.ready()) {
                                install();
                            } else if (updates.available != null) {
                                updates.download();
                            } else {
                                updates.check(true);
                            }
                        });
        primaryBtn.setFocusable(true);
        primaryBtn.setTextSize(14.5f);
        primaryBtn.setTypeface(null, Typeface.BOLD);
        primaryBtn.setTextColor(MainActivity.WHITE);
        primaryBtn.setMinHeight(a.dp(46));
        primaryBtn.setMinimumHeight(a.dp(46));
        primaryBtn.setBackground(a.bg(0xffe34b58, 14));
        primaryBtn.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                a,
                                primaryBtn,
                                f,
                                0xffe34b58,
                                MainActivity.WHITE,
                                14,
                                MainActivity.WHITE,
                                MainActivity.DARK,
                                false,
                                1.02f));
        actionRow.addView(primaryBtn, new LinearLayout.LayoutParams(0, a.dp(46), 1f));

        secondaryActionBtn =
                a.button(
                        "重新檢查",
                        "update-secondary",
                        () -> {
                            if (updates.busy) {
                                updates.cancel();
                            } else {
                                updates.check(true);
                            }
                        });
        secondaryActionBtn.setFocusable(true);
        secondaryActionBtn.setTextSize(13.5f);
        secondaryActionBtn.setTextColor(0xffe5e7eb);
        secondaryActionBtn.setMinHeight(a.dp(46));
        secondaryActionBtn.setMinimumHeight(a.dp(46));
        secondaryActionBtn.setPadding(a.dp(16), 0, a.dp(16), 0);
        secondaryActionBtn.setBackground(cardSurface(0xff1e1e22, 0xff191920, 14, 0x2effffff));
        secondaryActionBtn.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                a,
                                secondaryActionBtn,
                                f,
                                0xff1e1e22,
                                MainActivity.WHITE,
                                14,
                                0xffe5e7eb,
                                MainActivity.DARK,
                                false,
                                1.02f));
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-2, a.dp(46));
        secLp.leftMargin = a.dp(10);
        actionRow.addView(secondaryActionBtn, secLp);

        heroCard.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));
        a.body.addView(heroCard, new LinearLayout.LayoutParams(-1, -2));

        a.gap(a.body, 16);

        // =====================================================================
        // 2. iOS INSET-GROUPED SETTINGS CARD (Automatic Updates & Mirror Policy)
        // =====================================================================
        LinearLayout groupCard = a.vertical();
        // mkoai 2.0.63: neutral dark surface (was a green-tinted dark).
        groupCard.setBackground(cardSurface(0xb81e1e22, 0xb8191920, 20, 0x26ffffff));

        // Row 1: Automatic Update Mode
        LinearLayout modeRow = a.row();
        modeRow.setTag("update-mode");
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        modeRow.setClickable(true);
        modeRow.setFocusable(tv);
        modeRow.setFocusableInTouchMode(tv);
        if (tv) {
            modeRow.setOnFocusChangeListener(
                    (v, f) ->
                            FocusMotion.style(
                                    a,
                                    modeRow,
                                    f,
                                    0x00000000,
                                    0x28ffffff,
                                    16,
                                    MainActivity.WHITE,
                                    MainActivity.WHITE,
                                    true,
                                    1.005f));
        } else {
            MobileShell.bindCardTouchFeedback(
                    a, modeRow, null, cardSurface(0x28ffffff, 0x18ffffff, 20, 0));
        }
        TextView modeTitle = a.text("自動更新策略", 15f, MainActivity.WHITE);
        modeTitle.setTypeface(null, Typeface.BOLD);
        modeRow.addView(modeTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        modeValueView = a.text(modeName(updates.mode()) + "  ›", 13.5f, MainActivity.MUTED);
        modeRow.addView(modeValueView, new LinearLayout.LayoutParams(-2, -2));
        modeRow.setOnClickListener(
                v -> {
                    if (!tv) {
                        String[] modes = {"notify", "download", "off"};
                        String[] labels = {"自動檢查並提醒（推薦）", "自動下載後提醒", "僅手動檢查"};
                        int cur =
                                Math.max(0, java.util.Arrays.asList(modes).indexOf(updates.mode()));
                        MobileShell.showDrawerPicker(
                                a,
                                "自動更新策略",
                                "自動檢查至少間隔六小時，播放過程中絕不彈窗打擾",
                                labels,
                                cur,
                                idx -> {
                                    updates.setMode(modes[idx]);
                                    refresh();
                                });
                    } else {
                        mode();
                    }
                });
        groupCard.addView(modeRow, new LinearLayout.LayoutParams(-1, -2));

        View rowDiv1 = new View(a);
        rowDiv1.setBackgroundColor(0x16ffffff);
        LinearLayout.LayoutParams divLp1 =
                new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.6f)));
        divLp1.leftMargin = a.dp(18);
        divLp1.rightMargin = a.dp(18);
        groupCard.addView(rowDiv1, divLp1);

        // Row 2: Manual Install Ready Package (if downloaded) or Release Notes Dialog
        LinearLayout installRow = a.row();
        installRow.setTag("update-install");
        installRow.setGravity(Gravity.CENTER_VERTICAL);
        installRow.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        installRow.setClickable(true);
        installRow.setFocusable(tv);
        installRow.setFocusableInTouchMode(tv);
        if (tv) {
            installRow.setOnFocusChangeListener(
                    (v, f) ->
                            FocusMotion.style(
                                    a,
                                    installRow,
                                    f,
                                    0x00000000,
                                    0x28ffffff,
                                    16,
                                    MainActivity.WHITE,
                                    MainActivity.WHITE,
                                    true,
                                    1.005f));
        } else {
            MobileShell.bindCardTouchFeedback(
                    a, installRow, null, cardSurface(0x28ffffff, 0x18ffffff, 20, 0));
        }
        TextView installTitle = a.text("安裝已下載更新包", 15f, MainActivity.WHITE);
        installTitle.setTypeface(null, Typeface.BOLD);
        installRow.addView(installTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView installHint = a.text("覆蓋升級保留全部資料  ›", 13f, MainActivity.MUTED);
        installRow.addView(installHint, new LinearLayout.LayoutParams(-2, -2));
        installRow.setOnClickListener(v -> install());
        groupCard.addView(installRow, new LinearLayout.LayoutParams(-1, -2));

        View rowDiv2 = new View(a);
        rowDiv2.setBackgroundColor(0x16ffffff);
        LinearLayout.LayoutParams divLp2 =
                new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(0.6f)));
        divLp2.leftMargin = a.dp(18);
        divLp2.rightMargin = a.dp(18);
        groupCard.addView(rowDiv2, divLp2);

        // Row 3: Mirror Source Priority Info
        LinearLayout sourceRow = a.row();
        sourceRow.setGravity(Gravity.CENTER_VERTICAL);
        sourceRow.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        TextView sourceTitle = a.text("下載線路優先順序", 15f, MainActivity.WHITE);
        sourceTitle.setTypeface(null, Typeface.BOLD);
        sourceRow.addView(sourceTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sourceVal = a.text("mkoai 發布渠道 · Drive", 13f, 0xffe34b58);
        sourceRow.addView(sourceVal, new LinearLayout.LayoutParams(-2, -2));
        groupCard.addView(sourceRow, new LinearLayout.LayoutParams(-1, -2));

        a.body.addView(groupCard, new LinearLayout.LayoutParams(-1, -2));

        a.gap(a.body, 14);
        TextView footer =
                a.text(
                        "各平臺版本獨立推進。檢查更新與下載安裝包均連線 mkoai 發布渠道（Drive manifest），手機／電視版本各自比對。安裝前自動覈驗 SHA-256 摘要與開發者簽名。",
                        12f,
                        MainActivity.MUTED);
        footer.setPadding(a.dp(6), 0, a.dp(6), a.dp(16));
        footer.setLineSpacing(a.dp(3), 1f);
        a.body.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        refresh();
    }

    void refresh() {
        String installedVer = UpdateManager.installedVersion(a);
        boolean tv = a.isTvDevice();
        String platformLabel = tv ? "Android TV 端" : "Android 手機端";
        UpdateManager.Release rel = updates.available;

        if (status != null) {
            status.setText(
                    "當前版本 "
                            + installedVer
                            + " · 正式版\n"
                            + updates.message
                            + (updates.percent >= 0 ? " · " + updates.percent + "%" : "")
                            + "\n"
                            + modeName(updates.mode()));
            if (updates.ready() || (rel == null && !updates.busy)) {
                status.setTextColor(0xffe34b58);
            } else if (updates.busy) {
                status.setTextColor(0xff5ac8fa);
            } else {
                status.setTextColor(0xfffbbf24);
            }
        }

        if (heroVersionTitle != null) {
            if (rel != null) {
                heroVersionTitle.setText("mkoai " + rel.version);
            } else {
                heroVersionTitle.setText("mkoai " + installedVer);
            }
        }

        if (heroMetaSubtitle != null) {
            if (rel != null && rel.size > 0) {
                String sizeMb = String.format(Locale.US, "%.1f MB", rel.size / (1024f * 1024f));
                heroMetaSubtitle.setText(platformLabel + " · 新版本可用 · " + sizeMb);
            } else {
                heroMetaSubtitle.setText(platformLabel + " · 已安裝當前版本 " + installedVer);
            }
        }

        if (progressWrap != null && progressFill != null && progressPercentView != null) {
            if (updates.busy && updates.percent >= 0) {
                progressWrap.setVisibility(View.VISIBLE);
                int pct = Math.max(0, Math.min(100, updates.percent));
                progressPercentView.setText(pct + "%");
                View parent = (View) progressFill.getParent();
                int totalW =
                        parent != null && parent.getWidth() > 0
                                ? parent.getWidth()
                                : (a.windowWidth() - a.dp(80));
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) progressFill.getLayoutParams();
                lp.width = Math.max(a.dp(8), Math.round(totalW * (pct / 100f)));
                progressFill.setLayoutParams(lp);
            } else {
                progressWrap.setVisibility(View.GONE);
            }
        }

        if (notesHeading != null && notesBody != null) {
            if (rel != null && rel.notes != null && !rel.notes.trim().isEmpty()) {
                notesHeading.setText("版本 " + rel.version + " 更新內容");
                notesBody.setText(rel.notes.trim());
            } else {
                notesHeading.setText("版本特性與安全保障");
                notesBody.setText(
                        "• mkoai 發布渠道：檢查更新與下載 APK 均從 Drive manifest 取得\n"
                                + "• 平臺獨立演進：手機端與電視端按各自平臺節奏獨立釋出版本號\n"
                                + "• 完整性與簽名保護：安裝前嚴格校驗 SHA-256 與應用簽名，覆蓋升級保留全部片源配置與歷史記錄");
            }
        }

        if (primaryBtn != null && secondaryActionBtn != null) {
            if (updates.busy) {
                primaryBtn.setText("正在處理… 取消任務");
                secondaryActionBtn.setVisibility(View.GONE);
            } else if (updates.ready() && rel != null) {
                primaryBtn.setText("現在安裝 " + rel.version);
                secondaryActionBtn.setText("重新檢查");
                secondaryActionBtn.setVisibility(View.VISIBLE);
            } else if (rel != null) {
                primaryBtn.setText("下載並更新 (" + rel.version + ")");
                secondaryActionBtn.setText("重新檢查");
                secondaryActionBtn.setVisibility(View.VISIBLE);
            } else {
                primaryBtn.setText("檢查軟體更新");
                secondaryActionBtn.setVisibility(View.GONE);
            }
        }

        if (modeValueView != null) {
            modeValueView.setText(modeName(updates.mode()) + "  ›");
        }
    }

    static String modeName(String mode) {
        return "off".equals(mode) ? "僅手動檢查" : "download".equals(mode) ? "自動下載後提醒" : "自動檢查並提醒";
    }

    void mode() {
        String[] modes = {"notify", "download", "off"};
        new AppDialog.Builder(a)
                .setTitle("更新方式")
                .setMessage("自動檢查至少間隔六小時，僅在應用首頁執行時發起。播放過程中不彈出更新視窗；安裝始終由你確認。")
                .setSingleChoiceItems(
                        new String[] {"自動檢查並提醒", "自動下載後提醒", "僅手動檢查"},
                        java.util.Arrays.asList(modes).indexOf(updates.mode()),
                        (d, n) -> {
                            updates.setMode(modes[n]);
                            refresh();
                        })
                .setNegativeButton("返回", null)
                .show();
    }

    /**
     * mkoai 2.0.61 (d): set before leaving for the system permission
     * settings, so the return path (resumeAfterPermission) can restore
     * this update screen even if the activity was destroyed while the
     * user was in settings. Cleared when the return is handled.
     */
    static final String PERMISSION_RETURN_KEY = "update_permission_return";

    void install() {
        if (updates.busy) {
            a.toast("請等待當前任務完成");
            return;
        }
        if (!updates.ready()) {
            a.toast("請先下載並校驗新版");
            return;
        }
        String deviceName = a.isTvDevice() ? "電視" : "手機";
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AppDialog.Builder(a)
                    .setTitle("允許安裝更新")
                    .setMessage(deviceName + "需要你允許 mkoai 安裝應用。開啟系統授權後，返回本頁再次選擇安裝。")
                    .setPositiveButton(
                            "開啟授權頁面",
                            (d, n) -> {
                                // mkoai 2.0.61 (d): remember we left for
                                // the permission settings before leaving,
                                // so coming back restores this page.
                                a.prefs
                                        .edit()
                                        .putBoolean(PERMISSION_RETURN_KEY, true)
                                        .apply();
                                try {
                                    a.startActivity(
                                            new Intent(
                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:" + a.getPackageName())));
                                } catch (RuntimeException e) {
                                    a.prefs.edit().remove(PERMISSION_RETURN_KEY).apply();
                                    a.notice("當前裝置未提供授權頁面，請從設定中開啟未知來源安裝許可權。");
                                }
                            })
                    .setNegativeButton("稍後", null)
                    .focusPositiveButton() // mkoai 2.0.61 (c): TV focus on 開啟授權頁面
                    .show();
            return;
        }
        showInstallConfirm();
    }

    /** The 「安裝 <version>？」 confirm — shared by install() and the return path. */
    void showInstallConfirm() {
        new AppDialog.Builder(a)
                .setTitle("安裝 " + updates.available.version + "？")
                .setMessage("將開啟系統的安裝確認頁面。覆蓋升級保留配置、收藏和觀看記錄。")
                .setPositiveButton("繼續安裝", (d, n) -> prepareInstall())
                .setNegativeButton("稍後", null)
                .focusPositiveButton() // mkoai 2.0.61 (c): TV focus on 繼續安裝
                .show();
    }

    /**
     * mkoai 2.0.61 (d): called from MainActivity.onResume() when the user
     * comes back from the system permission settings. Restores this update
     * screen and, if the permission is now granted and the update is still
     * downloaded and verified, offers the install confirm right away — one
     * OK press installs. If the permission is still missing, the user just
     * lands back on this page and can tap install again (no dialog forced).
     * Posted to the UI queue: on a recreated activity the initial home is
     * opened by an onCreate-posted runnable, and this post (enqueued in
     * onResume, after it) runs after the home — a direct call here would
     * be overwritten by the home open. Why the screen can be lost at all:
     * the MainActivity screen stack is in-memory only and onCreate always
     * starts at home, so a TV box that kills the backgrounded activity
     * while the user is in system settings restarts the app at home.
     */
    static void resumeAfterPermission(MainActivity a) {
        if (!a.prefs.getBoolean(PERMISSION_RETURN_KEY, false)) return;
        a.prefs.edit().remove(PERMISSION_RETURN_KEY).apply();
        a.ui.post(
                () -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    UpdateScreen screen = a.updateScreen;
                    boolean showing =
                            screen != null
                                    && screen.status != null
                                    && screen.status.isAttachedToWindow();
                    if (!showing) {
                        screen = new UpdateScreen(a);
                        a.open(new MainActivity.Screen(screen::show), true);
                    }
                    if (Build.VERSION.SDK_INT >= 26
                            && a.getPackageManager().canRequestPackageInstalls()
                            && screen.updates.ready()) {
                        screen.showInstallConfirm();
                    }
                });
    }

    static Intent installIntent() {
        Intent intent =
                new Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setDataAndType(
                                UpdateApkProvider.URI, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        intent.setClipData(ClipData.newRawUri("mkoai update", UpdateApkProvider.URI));
        return intent;
    }

    void prepareInstall() {
        final UpdateManager.Release release = updates.available;
        if (release == null || updates.busy) return;
        final int epoch = a.generation;
        updates.busy = true;
        updates.resetCancellation();
        updates.message = "正在覈對安裝包…";
        refresh();
        updates.worker.execute(
                () -> {
                    try {
                        UpdateManager.verifyApk(a, updates.apk(), release);
                        a.ui.post(
                                () -> {
                                    updates.busy = false;
                                    if (!a.foreground
                                            || a.isFinishing()
                                            || a.isDestroyed()
                                            || epoch != a.generation
                                            || updates.cancelled()
                                            || status == null
                                            || !status.isAttachedToWindow()) {
                                        updates.message = "校驗完成，回到軟體更新頁後可繼續安裝";
                                        return;
                                    }
                                    try {
                                        updates.prefs
                                                .edit()
                                                .putInt("pending_code", release.code)
                                                .commit();
                                        a.startActivityForResult(installIntent(), INSTALL);
                                        updates.message = "請在系統安裝頁面確認或返回";
                                    } catch (RuntimeException error) {
                                        updates.prefs.edit().remove("pending_code").apply();
                                        updates.message = "無法開啟安裝器，請從 mkoai 發布渠道下載安裝";
                                    }
                                    refresh();
                                });
                    } catch (Exception error) {
                        a.ui.post(
                                () -> {
                                    updates.busy = false;
                                    updates.message = "安裝包校驗失敗，請重新下載";
                                    refresh();
                                });
                    }
                });
    }

    /** mkoai 2.0.61 (e): the installer reports a successful install only as RESULT_OK. */
    static boolean installSucceeded(int result) {
        return result == android.app.Activity.RESULT_OK;
    }

    static void result(MainActivity a, int result) {
        UpdateManager updates = UpdateManager.get(a);
        updates.prefs.edit().remove("pending_code").apply();
        if (installSucceeded(result)) {
            // mkoai 2.0.61 (e): the installer confirmed the install — the
            // downloaded APK is no longer needed, delete it now (covers
            // the case where this old process survives long enough to get
            // the result; if the process was killed instead, the
            // UpdateManager constructor deletes it on the next launch).
            updates.deleteDownloadedApk();
            updates.message = "已安裝新版，安裝包已自動刪除";
        } else {
            // Cancelled / failed: keep the APK so the user can retry the
            // install without re-downloading.
            updates.message = "已返回應用；若取消安裝，原版本繼續保留";
        }
        if (a.updateScreen != null) a.updateScreen.refresh();
    }
}
