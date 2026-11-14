package app.mkoai.player;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;

/** Remote-friendly settings. Each row changes a setting consumed by the app. */
final class SettingsScreen {
    final MainActivity a;

    SettingsScreen(MainActivity activity) {
        a = activity;
    }

    void page(String title, String note) {
        a.renderPage("", "");
        if (a.isTvDevice() && a.scroll instanceof TvScrollView)
            ((TvScrollView) a.scroll).readingMode = true;
        if (!a.isTvDevice()) {
            LinearLayout topNav = a.row();
            topNav.setGravity(Gravity.CENTER_VERTICAL);
            topNav.setPadding(0, 0, 0, a.dp(14));
            TextView backBtn = a.text("‹", 22, MainActivity.WHITE);
            backBtn.setGravity(Gravity.CENTER);
            backBtn.setBackground(
                    MobileShell.glassBg(0xb034343a, 0xc01e1e22, a.dp(999), a.dp(0.8f), 0x38ffffff));
            MobileDetailScreen.bindTouchFeedback(backBtn);
            backBtn.setOnClickListener(v -> a.onBackPressed());
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
            backLp.rightMargin = a.dp(10);
            topNav.addView(backBtn, backLp);
            TextView heading = a.text(title, 22, MainActivity.WHITE);
            heading.setTypeface(null, Typeface.BOLD);
            topNav.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            a.body.addView(topNav, new LinearLayout.LayoutParams(-1, -2));
        } else {
            a.body.addView(a.text(title, 28, MainActivity.WHITE));
            a.gap(a.body, 10);
        }
        if (!note.isEmpty()) {
            a.paragraph(note);
            a.gap(a.body, 6);
        }
    }

    void link(String title, String detail, String key, Runnable action) {
        boolean tv = a.isTvDevice();
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(20), a.dp(15), a.dp(20), a.dp(15));
        MobileShell.SettingIconView icon =
                new MobileShell.SettingIconView(
                        a, MobileShell.SettingIconView.kindForSetting(key, title));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);
        LinearLayout copy = a.vertical();
        copy.addView(a.text(title, 17, MainActivity.WHITE));
        TextView caption = a.text(detail, 12, MainActivity.MUTED);
        caption.setPadding(0, a.dp(4), 0, 0);
        if (!detail.isEmpty()) copy.addView(caption);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(a.text("›", 23, MainActivity.MUTED));
        row.setTag(key);
        row.setId(View.generateViewId());
        row.setFocusable(tv);
        row.setFocusableInTouchMode(tv);
        row.setClickable(true);
        row.setBackground(a.bg(0xff222226, 14));
        row.setOnFocusChangeListener(
                (v, f) -> {
                    FocusMotion.style(
                            a,
                            row,
                            f,
                            0xff222226,
                            0xff37373e,
                            14,
                            MainActivity.WHITE,
                            MainActivity.WHITE,
                            true,
                            1.008f);
                });
        if (tv) {
            a.pressFeedback(row);
        } else {
            MobileShell.bindCardTouchFeedback(a, row, a.bg(0xff222226, 14), a.bg(0xff303038, 14));
        }
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = a.dp(14);
        a.body.addView(row, size);
    }

    void open(Runnable page) {
        a.open(new MainActivity.Screen(page), true);
    }

    void show() {
        page("設定", "");
        // 2.0.53: TV had no way to sign in (mobile has the header "M" button
        // + 訂閱與賬號）. TV-only entry to the same account page (mobile is
        // untouched: it already has its own login entry).
        if (a.isTvDevice()) {
            String em = SupabaseAuth.email(a);
            link(
                    "帳號同步",
                    SupabaseAuth.isSignedIn(a)
                            ? (em == null ? "已登入" : "已登入：" + em)
                            : "登入帳號以同步觀看紀錄與收藏",
                    "sync-account",
                    () -> open(this::accountSync));
        }
        link("首頁與桌布", "", "settings-appearance", () -> open(this::appearance));
        link("播放與字幕", "", "settings-playback", () -> open(this::playback));
        link("搜尋與資料", "", "settings-data", () -> open(this::data));
        link("軟體更新", "", "settings-updates", () -> open(() -> new UpdateScreen(a).show()));
    }

    /** mkoai account sync: Google sign-in, sync now, sign out. */
    void accountSync() {
        boolean signedIn = SupabaseAuth.isSignedIn(a);
        page(
                "帳號同步",
                signedIn
                        ? "觀看紀錄與收藏會在登入的裝置之間同步（進度取看得最遠的那個）。"
                        : (a.isTvDevice()
                                ? "用 Google 或帳號密碼登入後，觀看紀錄與收藏會同步到其他裝置。"
                                : "用 Google 帳號登入後，觀看紀錄與收藏會同步到其他裝置。"));
        if (signedIn) {
            a.paragraph("已登入：" + SupabaseAuth.email(a));
            a.paragraph("上次同步：" + SupabaseSync.lastSyncText(a));
            link(
                    "立即同步",
                    "下載雲端紀錄並上傳本機紀錄",
                    "sync-now",
                    () -> SupabaseSync.syncNow(a));
            link(
                    "登出帳號",
                    "登出後不再同步，本機紀錄保留",
                    "sync-signout",
                    () -> {
                        SupabaseAuth.signOut(a);
                        a.toast("已登出");
                        show();
                    });
        } else {
            link(
                    "使用 Google 登入",
                    "會開啟瀏覽器完成 Google 登入",
                    "sync-signin",
                    () -> SupabaseAuth.signInWithGoogle(a));
            // 2.0.53: TV-only email + password sign-in (Google needs a
            // browser on the TV, which many boxes don't have).
            if (a.isTvDevice()) {
                link(
                        "用帳號密碼登入",
                        "用 Email 與密碼登入帳號",
                        "sync-password",
                        () -> open(this::passwordSignIn));
                // 2.0.54: TV QR login (TV shows a QR; the phone scans it and
                // the TV signs in as the phone's account). No browser, no
                // password typing on the TV.
                link(
                        "掃碼登入",
                        "用手機 mkoai 掃電視的 QR 碼，電視直接登入手機同一個帳號",
                        "sync-qr-login",
                        () -> a.tvLoginDialog());
            }
        }
        // 2.0.54: phone scans the TV's login QR so the TV signs in as this
        // phone's account (phone only; the phone must be signed in first).
        if (!a.isTvDevice()) {
            link(
                    "掃碼登入電視",
                    "掃電視的 QR 碼，讓電視登入手機同一個帳號",
                    "sync-qr-login-phone",
                    () -> new PhoneLoginDialog(a).show());
        }
    }

    /** 2.0.53: TV-only email + password sign-in form (system keyboard for input). */
    void passwordSignIn() {
        page(
                "帳號密碼登入",
                "用 Email 與密碼登入。若帳號是用 Google 註冊的、從未設過密碼，請先按「忘記密碼」到信箱設定密碼再登入。");
        final EditText emailInput = inputField("Email，例如 you@gmail.com", false);
        a.body.addView(emailInput, new LinearLayout.LayoutParams(-1, -2));
        a.gap(a.body, 12);
        final EditText passInput = inputField("密碼", true);
        a.body.addView(passInput, new LinearLayout.LayoutParams(-1, -2));
        a.gap(a.body, 16);
        final TextView status = a.text("", 13, MainActivity.MUTED);
        a.body.addView(status);
        a.gap(a.body, 12);
        TextView loginBtn = a.text("登入", 16, Color.WHITE);
        loginBtn.setTypeface(null, Typeface.BOLD);
        loginBtn.setGravity(Gravity.CENTER);
        loginBtn.setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11));
        loginBtn.setBackground(a.bg(0xffe34b58, 999));
        loginBtn.setFocusable(true);
        loginBtn.setFocusableInTouchMode(true);
        MobileDetailScreen.bindTouchFeedback(loginBtn);
        Runnable doLogin =
                () -> {
                    String email = emailInput.getText().toString().trim();
                    String pass = passInput.getText().toString();
                    if (email.isEmpty() || pass.isEmpty()) {
                        status.setText("請輸入 Email 與密碼");
                        a.toast("請輸入 Email 與密碼");
                        return;
                    }
                    status.setText("正在登入…");
                    a.worker.execute(
                            () -> {
                                SupabaseAuth.PasswordResult r =
                                        SupabaseAuth.signInWithPassword(a, email, pass);
                                a.ui.post(
                                        () -> {
                                            if (r.ok) {
                                                a.toast(
                                                        "已登入 "
                                                                + (SupabaseAuth.email(a) == null
                                                                        ? ""
                                                                        : SupabaseAuth.email(a)));
                                                accountSync();
                                                SupabaseSync.syncNow(a);
                                            } else {
                                                status.setText(r.error);
                                                a.toast(r.error);
                                            }
                                        });
                            });
                };
        loginBtn.setOnClickListener(v -> doLogin.run());
        a.body.addView(loginBtn, new LinearLayout.LayoutParams(-1, -2));
        a.gap(a.body, 12);
        TextView forgot = a.text("忘記密碼（寄重設信到信箱）", 14, MainActivity.MUTED);
        forgot.setGravity(Gravity.CENTER);
        forgot.setFocusable(true);
        forgot.setFocusableInTouchMode(true);
        forgot.setPadding(a.dp(8), a.dp(10), a.dp(8), a.dp(10));
        MobileDetailScreen.bindTouchFeedback(forgot);
        forgot.setOnClickListener(
                v -> {
                    String email = emailInput.getText().toString().trim();
                    if (email.isEmpty()) {
                        status.setText("請先在上面輸入 Email，再按忘記密碼");
                        a.toast("請先輸入 Email");
                        return;
                    }
                    status.setText("正在寄送重設信…");
                    a.worker.execute(
                            () -> {
                                String err = SupabaseAuth.sendPasswordRecovery(a, email);
                                a.ui.post(
                                        () -> {
                                            if (err == null) {
                                                status.setText("重設信已寄出，請到信箱點連結設定密碼");
                                                a.toast("重設信已寄出");
                                            } else {
                                                status.setText(err);
                                                a.toast(err);
                                            }
                                        });
                            });
                });
        a.body.addView(forgot, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Input field that brings up the TV system keyboard when focused or tapped. */
    private EditText inputField(String hint, boolean password) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setHintTextColor(0xff6b7280);
        et.setTextColor(MainActivity.WHITE);
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setShowSoftInputOnFocus(true);
        et.setFocusable(true);
        et.setFocusableInTouchMode(true);
        et.setLongClickable(true);
        et.setTextIsSelectable(true);
        et.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        et.setBackground(a.bg(0xff1e2623, 12));
        et.setOnClickListener(v -> showSystemIme(v));
        et.setOnFocusChangeListener(
                (v, hasFocus) -> {
                    if (hasFocus) showSystemIme(v);
                });
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            et.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        }
        return et;
    }

    private static void showSystemIme(View v) {
        try {
            InputMethodManager imm =
                    (InputMethodManager)
                            v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception ignored) {
        }
    }

    void decoders() {
        page("解碼元件", "元件隨應用安裝，無需另外下載解碼包。");
        a.paragraph(DecoderSupport.description());
    }

    void openConfigSiteHome(Cms.Site targetSite) {
        a.prefs.edit().putString(Prefs.SELECTED_SITE, targetSite.key).apply();
        MobileShell.activeTab = 0;
        a.back.clear();
        a.open(new MainActivity.Screen(a::home), false);
    }

    interface AccountChoice {
        void accept(int index);
    }

    void showAccountActionPicker(
            String title, String subtitle, String[] labels, AccountChoice handleChoice) {
        if (!a.isTvDevice()) {
            MobileShell.showDrawerPicker(a, title, subtitle, labels, -1, handleChoice::accept);
        } else {
            new AppDialog.Builder(a)
                    .setTitle(title)
                    .setItems(labels, (d, which) -> handleChoice.accept(which))
                    .setNegativeButton("關閉", null)
                    .show();
        }
    }

    static Cms.Video findSortCard(java.util.List<Cms.Video> videos) {
        if (videos == null) return null;
        for (Cms.Video v : videos) {
            if (v == null) continue;
            String id = v.id == null ? "" : v.id.toLowerCase(java.util.Locale.ROOT);
            String act = v.action == null ? "" : v.action.toLowerCase(java.util.Locale.ROOT);
            String title = v.title == null ? "" : v.title;
            if (id.contains("pan_order")
                    || id.contains("panorder")
                    || id.contains("panpaixu")
                    || act.contains("pan_order")
                    || act.contains("panorder")
                    || act.contains("panpaixu")
                    || title.contains("优先顺序")
                    || title.contains("搜索顺序")
                    || title.contains("网盘排序")
                    || title.contains("网盘顺序")
                    || title.contains("盘序")) {
                return v;
            }
        }
        return null;
    }

    void showDynamicVideoPicker(
            Cms.Site targetSite, String title, java.util.List<Cms.Video> videos) {
        if (videos == null || videos.isEmpty()) {
            openConfigSiteHome(targetSite);
            return;
        }
        String[] labels = new String[videos.size() + 1];
        for (int i = 0; i < videos.size(); i++) {
            Cms.Video v = videos.get(i);
            String name = v.title == null ? "" : v.title.trim();
            String rem = v.remarks == null ? "" : v.remarks.trim();
            if (!rem.isEmpty() && !rem.equals(name)) {
                labels[i] = name + " · " + rem;
            } else {
                labels[i] = name;
            }
        }
        labels[videos.size()] = "開啟完整配置中心";
        showAccountActionPicker(
                title,
                "選擇需要執行的網盤配置操作",
                labels,
                idx -> {
                    if (idx < 0 || idx > videos.size()) return;
                    if (idx == videos.size()) {
                        openConfigSiteHome(targetSite);
                    } else {
                        a.openListing(targetSite, videos.get(idx));
                    }
                });
    }

    void openDynamicConfigCategory(Cms.Site targetSite, Cms.Category cat) {
        final int ticket = a.generation;
        a.worker.execute(
                () -> {
                    try {
                        CspEngine.syncAllPluginActivities(a);
                        SourceEngine eng = a.engine(targetSite);
                        Cms.Listing list = Cms.listing(eng.category(cat.id, 1));
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || ticket != a.generation) return;
                                    showDynamicVideoPicker(targetSite, cat.name, list.videos);
                                });
                    } catch (Throwable error) {
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || ticket != a.generation) return;
                                    openConfigSiteHome(targetSite);
                                });
                    }
                });
    }

    void openDynamicConfigSiteAction(Cms.Site targetSite, String action) {
        final int ticket = a.generation;
        final boolean wantSort = "panSortShow".equals(action) || "panSourceSortShow".equals(action);
        a.worker.execute(
                () -> {
                    try {
                        CspEngine.syncAllPluginActivities(a);
                        SourceEngine eng = a.engine(targetSite);
                        String homeRaw = eng.home();
                        java.util.List<Cms.Category> cats = Cms.categories(homeRaw);
                        Cms.Listing homeList = null;
                        try {
                            homeList = Cms.listing(homeRaw);
                        } catch (Throwable ignored) {
                        }
                        if (wantSort) {
                            Cms.Video sortCard =
                                    findSortCard(homeList != null ? homeList.videos : null);
                            if (sortCard == null && cats != null) {
                                for (Cms.Category c : cats) {
                                    String cid =
                                            c.id == null
                                                    ? ""
                                                    : c.id.toLowerCase(java.util.Locale.ROOT);
                                    String cname = c.name == null ? "" : c.name;
                                    if (cid.contains("pan")
                                            || cid.contains("config")
                                            || cname.contains("网盘")
                                            || cname.contains("配置")) {
                                        try {
                                            Cms.Listing cl = Cms.listing(eng.category(c.id, 1));
                                            sortCard = findSortCard(cl.videos);
                                            if (sortCard != null) break;
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            }
                            if (sortCard != null) {
                                final Cms.Video found = sortCard;
                                a.ui.post(
                                        () -> {
                                            if (a.isFinishing() || ticket != a.generation) return;
                                            a.openListing(targetSite, found);
                                        });
                                return;
                            }
                        }
                        if (cats != null && !cats.isEmpty()) {
                            final java.util.List<Cms.Category> categoryList = cats;
                            a.ui.post(
                                    () -> {
                                        if (a.isFinishing() || ticket != a.generation) return;
                                        String[] labels = new String[categoryList.size() + 1];
                                        for (int i = 0; i < categoryList.size(); i++) {
                                            labels[i] = categoryList.get(i).name;
                                        }
                                        labels[categoryList.size()] = "開啟完整配置中心";
                                        showAccountActionPicker(
                                                targetSite.name,
                                                "選擇需要配置的網盤或功能分類",
                                                labels,
                                                idx -> {
                                                    if (idx < 0 || idx > categoryList.size())
                                                        return;
                                                    if (idx == categoryList.size()) {
                                                        openConfigSiteHome(targetSite);
                                                    } else {
                                                        openDynamicConfigCategory(
                                                                targetSite, categoryList.get(idx));
                                                    }
                                                });
                                    });
                            return;
                        }
                        if (homeList != null && !homeList.videos.isEmpty()) {
                            final java.util.List<Cms.Video> videos = homeList.videos;
                            a.ui.post(
                                    () -> {
                                        if (a.isFinishing() || ticket != a.generation) return;
                                        showDynamicVideoPicker(targetSite, targetSite.name, videos);
                                    });
                            return;
                        }
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || ticket != a.generation) return;
                                    openConfigSiteHome(targetSite);
                                });
                    } catch (Throwable error) {
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || ticket != a.generation) return;
                                    openConfigSiteHome(targetSite);
                                });
                    }
                });
    }

    void accountAction(String action) {
        if (a.config != null) {
            Cms.Site fallback = null;
            String currentKey = a.prefs.getString(Prefs.SELECTED_SITE, "");
            if (currentKey != null && !currentKey.isEmpty()) {
                for (Cms.Site site : a.config.sites) {
                    if (currentKey.equals(site.key)
                            && SourceController.isDedicatedConfigSite(site)) {
                        fallback = site;
                        break;
                    }
                }
            }
            if (fallback == null) {
                for (Cms.Site site : a.config.sites) {
                    if (site.api != null
                            && site.api.toLowerCase(java.util.Locale.ROOT).contains("mydrive")) {
                        fallback = site;
                        break;
                    }
                    if (fallback == null && SourceController.isDedicatedConfigSite(site)) {
                        fallback = site;
                    }
                }
            }
            if (fallback == null) {
                for (Cms.Site site : a.config.sites) {
                    if (SourceController.isConfigOrNetdiskSite(site)) {
                        fallback = site;
                        break;
                    }
                }
            }
            if (fallback != null) {
                if (!a.available(fallback)) {
                    a.notice("請先在“搜尋與資料”中啟用來源擴充套件。");
                    return;
                }
                final Cms.Site targetSite = fallback;
                String api =
                        targetSite.api == null
                                ? ""
                                : targetSite.api.toLowerCase(java.util.Locale.ROOT);
                String key =
                        targetSite.key == null
                                ? ""
                                : targetSite.key.toLowerCase(java.util.Locale.ROOT);
                boolean isMyDrive = api.contains("mydrive") || key.contains("mydrive");
                if (isMyDrive) {
                    a.sourceAction(targetSite, action);
                    return;
                }
                boolean isPanConfig = api.contains("panconfig") || key.contains("panconfig");
                if (isPanConfig) {
                    if ("LoginShow".equals(action)) {
                        final String[] labels =
                                new String[] {
                                    "夸克網盤掃碼登入",
                                    "UC網盤 Cookie 掃碼登入",
                                    "UC網盤 TV Token 掃碼登入",
                                    "百度網盤掃碼登入",
                                    "115網盤掃碼登入",
                                    "天翼網盤掃碼登入",
                                    "天翼網盤賬號密碼登入",
                                    "移動雲盤掃碼登入",
                                    "光鴨網盤掃碼登入",
                                    "迅雷雲盤掃碼登入",
                                    "嗶哩嗶哩掃碼登入",
                                    "123網盤賬號密碼設定",
                                    "開啟完整配置中心"
                                };
                        final String[] codes =
                                new String[] {
                                    "quark",
                                    "uccookie",
                                    "uctoken",
                                    "baidu",
                                    "pan115",
                                    "pan189",
                                    "pan189account",
                                    "pan139",
                                    "guangya",
                                    "thunder",
                                    "bili",
                                    "pan123manual",
                                    "__open_config__"
                                };
                        showAccountActionPicker(
                                "網盤掃碼與賬號登入",
                                "選擇需要掃碼或配置憑據的網盤服務",
                                labels,
                                idx -> {
                                    if (idx < 0 || idx >= codes.length) return;
                                    String code = codes[idx];
                                    if ("__open_config__".equals(code)) {
                                        openConfigSiteHome(targetSite);
                                    } else {
                                        a.sourceAction(targetSite, code);
                                    }
                                });
                        return;
                    }
                    if ("pushCkShow".equals(action)) {
                        a.sourceAction(targetSite, "web");
                        return;
                    }
                    if ("panSortShow".equals(action) || "panSourceSortShow".equals(action)) {
                        a.sourceAction(targetSite, "panorder");
                        return;
                    }
                }
                boolean isWexConfig =
                        api.contains("wexconfig")
                                || api.contains("wexokconfig")
                                || key.contains("wexconfig")
                                || key.contains("wexokconfig");
                if (isWexConfig) {
                    if ("panSortShow".equals(action) || "panSourceSortShow".equals(action)) {
                        a.sourceAction(targetSite, "panpaixu");
                        return;
                    }
                    final String[] labels =
                            new String[] {
                                "夸克網盤掃碼 / Cookie 設定",
                                "UC網盤掃碼 / Cookie 設定①",
                                "UC網盤 TV 掃碼設定②",
                                "阿里雲盤掃碼 / Token 設定",
                                "百度網盤掃碼登入",
                                "115網盤掃碼 / Cookie 設定",
                                "天翼雲盤賬號登入",
                                "移動雲盤 Cookie 設定",
                                "123雲盤賬號登入",
                                "迅雷雲盤賬號登入",
                                "嗶哩嗶哩掃碼 / Cookie 設定",
                                "網盤啟用開關設定",
                                "開啟完整配置中心"
                            };
                    final String[] codes =
                            new String[] {
                                "quarkcookie",
                                "ucpancookie",
                                "uctvpancookie",
                                "aliyuntoken",
                                "baidupanlogin",
                                "115pancookie",
                                "tianyilogin",
                                "ydyuncookie",
                                "pan123login",
                                "panxunleiuser",
                                "bilicookie",
                                "pankaiguan",
                                "__open_config__"
                            };
                    showAccountActionPicker(
                            "網盤掃碼與賬號登入",
                            "選擇需要掃碼或配置憑據的網盤服務",
                            labels,
                            idx -> {
                                if (idx < 0 || idx >= codes.length) return;
                                String code = codes[idx];
                                if ("__open_config__".equals(code)) {
                                    openConfigSiteHome(targetSite);
                                } else {
                                    a.sourceAction(targetSite, code);
                                }
                            });
                    return;
                }
                openDynamicConfigSiteAction(targetSite, action);
                return;
            }
        }
        a.notice("當前配置沒有提供網盤賬號入口，請先匯入支援網盤的配置源。");
    }

    void appearance() {
        page("首頁與桌布", "介面按螢幕解析度自動適配，海報保持 2:3 比例。");
        link(
                "介面大小",
                TvLayoutPolicy.percent(a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100))
                        + "% · 按鈕、文字與圖示一起調整",
                "setting-ui_scale_percent",
                () -> {
                    String[] scaleLabels = new String[] {"標準 · 自動適配", "放大 · 110%", "更大 · 120%"};
                    int[] scaleValues = new int[] {100, 110, 120};
                    int curScale =
                            a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100) == 120
                                    ? 2
                                    : a.prefs.getInt(Prefs.UI_SCALE_PERCENT, 100) == 110 ? 1 : 0;
                    if (!a.isTvDevice()) {
                        MobileShell.showDrawerPicker(
                                a,
                                "介面大小",
                                "標準會自動適配螢幕解析度；選擇後介面重新載入",
                                scaleLabels,
                                curScale,
                                n -> {
                                    a.prefs
                                            .edit()
                                            .putInt(Prefs.UI_SCALE_PERCENT, scaleValues[n])
                                            .commit();
                                    a.recreate();
                                });
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle("介面大小")
                            .setMessage("標準會自動適配螢幕解析度。觀看距離較遠時可放大；選擇後介面重新載入。")
                            .setSingleChoiceItems(
                                    scaleLabels,
                                    curScale,
                                    (d, n) -> {
                                        a.prefs
                                                .edit()
                                                .putInt(Prefs.UI_SCALE_PERCENT, scaleValues[n])
                                                .commit();
                                        a.recreate();
                                    })
                            .setNegativeButton("返回", null)
                            .show();
                });
        if (a.isTvDevice())
            ints("Dock 位置", Prefs.TV_DOCK_POSITION, new String[] {"右側", "頂部"}, new int[] {0, 1}, 0);
        ints(
                "導航欄字型與按鈕大小",
                Prefs.NAV_SCALE_PERCENT,
                new String[] {"緊湊 · 85%", "標準 · 100%", "偏大 · 115%", "大號 · 130%", "特大 · 140%"},
                new int[] {85, 100, 115, 130, 140},
                100);
        ints(
                "每行海報",
                Prefs.HOME_COLUMNS,
                new String[] {"最多 5 張 · 舒展", "最多 6 張", "最多 7 張", "最多 8 張"},
                new int[] {5, 6, 7, 8},
                5);
        link(
                "首頁桌布",
                a.prefs.getBoolean(Prefs.WALLPAPER_ENABLED, false)
                        ? "自定義桌布"
                        : a.prefs.getBoolean(Prefs.HOME_AMBIENT, true) ? "海報配色柔光" : "純深色",
                "settings-wallpaper",
                () -> open(this::wallpaper));
        if (!a.isTvDevice()) {
            toggle("首頁推薦卡片", Prefs.SHOW_HERO_BANNER, true, null);
        }
        toggle(
                "減少透明效果",
                Prefs.REDUCED_TRANSPARENCY,
                false,
                () -> com.pilipala.glass.GlassworkSurface.invalidateBackdrop(a.root));
        toggle("開屏動畫", Prefs.STARTUP_ANIMATION, true, null);
        toggle("海報配色柔光", Prefs.HOME_AMBIENT, true, null);
        toggle(
                "減少動態效果",
                Prefs.REDUCED,
                false,
                // 2.0.54 TV lite: the old TV never animates, even if the toggle is off.
                () -> a.reduced = a.isTvDevice() || a.prefs.getBoolean(Prefs.REDUCED, false));
    }

    void wallpaper() {
        page("首頁桌布", "桌布隻影響應用背景，不會更改電視桌面。圖片以居中裁切顯示，自動縮小以減少電視記憶體佔用。");
        link("選擇本地圖片", "JPG、PNG、WebP", "wallpaper-file", () -> pick(44, "image/*", false));
        link("從圖片地址匯入", "輸入你自己的 HTTP / HTTPS 圖片地址", "wallpaper-url", this::wallpaperUrl);
        ints(
                "背景壓暗",
                Prefs.WALLPAPER_DIM,
                new String[] {"輕 · 40%", "適中 · 60%", "深 · 80%"},
                new int[] {40, 60, 80},
                60);
        link(
                "恢復預設背景",
                "保留圖片檔案，隨時可以重新啟用",
                "wallpaper-reset",
                () -> {
                    a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, false).apply();
                    a.refresh();
                });
        if (new File(a.getFilesDir(), "wallpaper.jpg").exists())
            link(
                    "啟用已儲存的桌布",
                    "使用上次匯入的圖片",
                    "wallpaper-enable",
                    () -> {
                        a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, true).apply();
                        a.refresh();
                    });
    }

    void playback() {
        page("播放與字幕", "作為之後播放的預設設定；播放介面中仍可隨時調整。");
        if (a.isTvDevice()) {
            ints(
                    "播放介面樣式",
                    Prefs.TV_PLAYER_STYLE,
                    new String[] {"現代懸浮卡片（預設）", "經典雙層工具欄"},
                    new int[] {0, 1},
                    0);
        }
        ints(
                "畫面輸出",
                Prefs.VIDEO_OUTPUT,
                new String[] {"保真輸出 · HDR 與流暢度優先", "相容輸出 · 支援應用內調色"},
                new int[] {0, 1},
                0);
        ints(
                "播放器",
                Prefs.PLAYER_ENGINE,
                new String[] {"Exo (Media3)", "IJK 相容模式 (Media3 + FFmpeg)", "系統播放器"},
                new int[] {0, 2, 1},
                0);
        ints(
                "解碼方式",
                Prefs.DECODER_MODE,
                new String[] {"自動", "硬體解碼", "軟體解碼優先"},
                new int[] {0, 1, 2},
                0);
        toggle("4K HDR 隧道模式", Prefs.TUNNELED_PLAYBACK, false, null);
        toggle("優先使用相容音訊解碼", Prefs.SOFTWARE_AUDIO, false, null);
        toggle("優先選擇 AAC 音軌", Prefs.PREFER_AAC, false, null);
        link("解碼元件", "檢視本機已載入的音訊與影片擴充套件", "settings-decoders", () -> open(this::decoders));
        ints(
                "畫面比例",
                Prefs.ASPECT,
                new String[] {"適應", "鋪滿", "16:9", "4:3", "裁切填充"},
                new int[] {0, 1, 2, 3, 4},
                0);
        ints(
                "網路緩衝",
                Prefs.BUFFER_SECONDS,
                new String[] {"15 秒 · 節省記憶體", "30 秒 · 均衡", "50 秒 · 網路波動"},
                new int[] {15, 30, 50},
                30);
        link(
                "預設速度",
                a.prefs.getFloat(Prefs.PLAY_SPEED, 1) + "×",
                "setting-play_speed",
                () -> {
                    String[] speedLabels =
                            new String[] {"0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"};
                    float[] speedVals = new float[] {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
                    float curSpeed = a.prefs.getFloat(Prefs.PLAY_SPEED, 1f);
                    int curIdx = 2;
                    for (int i = 0; i < speedVals.length; i++) {
                        if (Math.abs(curSpeed - speedVals[i]) < 0.05f) {
                            curIdx = i;
                            break;
                        }
                    }
                    if (!a.isTvDevice()) {
                        MobileShell.showDrawerPicker(
                                a,
                                "預設播放速度",
                                "作為之後進入播放時的初始倍速",
                                speedLabels,
                                curIdx,
                                i -> {
                                    a.prefs.edit().putFloat(Prefs.PLAY_SPEED, speedVals[i]).apply();
                                    a.refresh();
                                });
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle("播放速度")
                            .setItems(
                                    speedLabels,
                                    (d, i) -> {
                                        a.prefs
                                                .edit()
                                                .putFloat(Prefs.PLAY_SPEED, speedVals[i])
                                                .apply();
                                        a.refresh();
                                    })
                            .show();
                });
        toggle("自動播放下一集", Prefs.AUTO_NEXT, true, null);
        // mkoai 2.0.48: 自動選源直接播放（預設開啟）。開：按播放自動選源、
        // 直接播放，絕不跳出選源清單；關：按播放先顯示「選擇片源」。
        toggle("自動選源直接播放", Prefs.AUTO_DIRECT_PLAY, true, null);
        ints("迴圈", Prefs.REPEAT_MODE, new String[] {"關閉", "單集迴圈", "列表迴圈"}, new int[] {0, 1, 2}, 0);
        int skipIntroSec = Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_INTRO, 0));
        link(
                "跳過片頭（精準拉取節點）",
                skipIntroSec <= 0
                        ? "關閉（點選可手動拉取秒數）"
                        : PlaybackController.formatTime(skipIntroSec * 1000)
                                + " · "
                                + skipIntroSec
                                + " 秒",
                "setting-" + Prefs.SKIP_INTRO,
                () -> PlaybackControls.showSkipAdjuster(a, true, 0, 0, a::refresh));
        int skipOutroSec = Prefs.clampSkipSeconds(a.prefs.getInt(Prefs.SKIP_OUTRO, 0));
        link(
                "跳過片尾（精準拉取節點）",
                skipOutroSec <= 0
                        ? "關閉（點選可手動拉取秒數）"
                        : PlaybackController.formatTime(skipOutroSec * 1000)
                                + " · "
                                + skipOutroSec
                                + " 秒",
                "setting-" + Prefs.SKIP_OUTRO,
                () -> PlaybackControls.showSkipAdjuster(a, false, 0, 0, a::refresh));
        int subSize = Prefs.clampSubtitleSize(a.prefs.getInt(Prefs.SUBTITLE_SIZE, 45));
        int subBottom = Prefs.clampSubtitleBottom(a.prefs.getInt(Prefs.SUBTITLE_BOTTOM, 24));
        int subStyle = Prefs.clampSubtitleStyle(a.prefs.getInt(Prefs.SUBTITLE_STYLE, 0));
        link(
                a.isTvDevice() ? "字幕大小與位置（全屏預覽調校）" : "字幕全屏除錯（雙指縮放 · 按住拖拽）",
                "字號 "
                        + subSize
                        + " · 底部高度 "
                        + subBottom
                        + "dp · "
                        + SubtitleOverlayView.STYLE_LABELS[subStyle],
                "setting-" + Prefs.SUBTITLE_SIZE,
                () -> PlaybackControls.showSubtitleAdjuster(a, null, a::refresh));
        ints(
                "字幕樣式",
                Prefs.SUBTITLE_STYLE,
                SubtitleOverlayView.STYLE_LABELS,
                new int[] {0, 1, 2, 3, 4, 5, 6, 7},
                0);
        toggle("自動載入來源彈幕", Prefs.DANMAKU_DEFAULT, true, null);
        ints(
                "畫面色彩",
                Prefs.PICTURE_EFFECT,
                new String[] {"原始", "明亮", "影院", "柔和"},
                new int[] {0, 1, 2, 3},
                0);
        ints(
                "音效",
                Prefs.AUDIO_EFFECT,
                new String[] {"原聲", "影院", "人聲清晰", "柔和低音"},
                new int[] {0, 1, 2, 3},
                0);
        a.paragraph(
                "相容音訊解碼僅切換音訊，不強制影片軟解碼；AAC 優先僅在媒體包含可用 AAC 音軌時生效，不會轉換檔案。以上兩項用於 Media3。音效取決於電視提供的音訊介面。系統播放器支援的字幕和網路格式少於 Media3；播放器中的字幕按鈕可匯入外部字幕。");
    }

    void data() {
        page("搜尋與資料", "搜尋預設識別首拼和全拼，並檢索配置中全部可搜尋來源。");
        ints(
                "同時搜尋的來源數",
                Prefs.SEARCH_THREADS,
                new String[] {"2 個 · 輕量", "4 個 · 均衡", "6 個 · 快速", "8 個 · 更快", "12 個 · 最快"},
                new int[] {2, 4, 6, 8, 12},
                12);
        ints(
                "單個來源等待時間",
                Prefs.SEARCH_TIMEOUT,
                new String[] {"10 秒", "20 秒", "30 秒"},
                new int[] {10, 20, 30},
                20);
        link(
                "匯入或更新配置源",
                a.isTvDevice()
                        ? "手機掃碼、遙控輸入或本地包 (.json/.js/.py/.zip)"
                        : "剪貼簿貼上、網址輸入或本地包 (.json/.js/.py/.zip)",
                "settings-import",
                a::importDialog);
        if (a.isTvDevice()) {
            link(
                    "手機推送來源到電視",
                    "手機掃碼後直接推送源網址或 JSON（同一 Wi-Fi）",
                    "settings-tv-push",
                    a::tvPushDialog);
        }
        link(
                "匯入或管理電視直播源",
                "支援 M3U、TXT (#genre#)、JSON 及本地直播檔案",
                "settings-live-import",
                () -> SourceController.showLiveImportDialog(a));
        if (a.config != null && a.config.depots.length() > 1) {
            link(
                    "切換影視倉線路",
                    "當前訂閱包含 " + a.config.depots.length() + " 個子倉庫，點選切換",
                    "settings-depots",
                    () -> SourcePicker.showWarehouses(a));
        }
        toggle("啟用來源擴充套件", Prefs.EXPERIMENTAL_CSP, true, a::closeEngines);
        toggle(
                "隱藏網盤相關功能",
                Prefs.HIDE_NETDISK,
                false,
                () -> {
                    a.refresh();
                    a.toast(
                            a.prefs.getBoolean(Prefs.HIDE_NETDISK, false)
                                    ? "已隱藏網盤按鈕、網盤線路與掃碼登入"
                                    : "已恢復顯示網盤相關功能");
                });
        toggle(
                "搜尋結果合併相同影片",
                Prefs.MERGE_SAME_TITLE,
                true,
                () -> {
                    a.toast(
                            a.prefs.getBoolean(Prefs.MERGE_SAME_TITLE, true)
                                    ? "已合併相同影片為一張卡片（點卡片自動選源）"
                                    : "已關閉合併，每個片源各顯示一張卡片");
                });
        toggle(
                "青少年模式",
                Prefs.YOUTH_MODE,
                false,
                () -> {
                    a.toast(
                            a.prefs.getBoolean(Prefs.YOUTH_MODE, false)
                                    ? "已開啟青少年模式（隱藏成人內容）"
                                    : "已關閉青少年模式");
                });
        if (!a.prefs.getBoolean(Prefs.HIDE_NETDISK, false)) {
            link(
                    "網盤與賬號",
                    "網盤掃碼登入、憑據與網盤順序（由配置提供）",
                    "settings-accounts",
                    () -> AccountPanel.show(a, null));
        }
        link(
                "帳號同步",
                SupabaseAuth.isSignedIn(a)
                        ? "已登入 " + SupabaseAuth.email(a) + " · " + SupabaseSync.lastSyncText(a)
                        : "用 Google 帳號同步觀看紀錄與收藏",
                "settings-account-sync",
                this::accountSync);
        link(
                "備份配置與偏好",
                "匯出配置與偏好，請妥善保管；不含外掛單獨儲存的登入狀態",
                "settings-backup",
                () -> pick(45, "application/json", true));
        link(
                "還原配置與偏好",
                "從 mkoai 備份檔案還原",
                "settings-restore",
                () -> pick(46, "application/json", false));
        link("觀看歷史", "檢視、繼續播放或刪除本機觀看記錄", "settings-history", () -> open(a::historyPage));
        link(
                "清除搜尋記錄",
                "清除最近搜尋，保留網盤登入",
                "settings-clear-history",
                () ->
                        confirm(
                                "清除搜尋記錄？",
                                () -> {
                                    a.prefs.edit().remove(Prefs.GLOBAL_SEARCH_HISTORY).apply();
                                    a.refresh();
                                }));
        link(
                "重新載入首頁圖片與片單",
                "清除記憶體與磁碟中的首頁快取",
                "settings-clear-images",
                () -> {
                    a.images.evictAll();
                    a.homeData.clear();
                    try {
                        File snapDir = new File(a.getCacheDir(), "home_snap_v1");
                        File[] files = snapDir.listFiles();
                        if (files != null) for (File f : files) f.delete();
                    } catch (Throwable ignored) {
                    }
                    a.toast("已清除首頁快取");
                });
        link("執行日誌", "檢視本機最近異常與診斷記錄", "settings-logs", this::diagnostics);
        link(
                "恢復播放與外觀預設設定",
                "保留來源配置和網盤登入",
                "settings-reset",
                () ->
                        confirm(
                                "恢復預設設定？",
                                () -> {
                                    android.content.SharedPreferences.Editor edit = a.prefs.edit();
                                    for (String key : KEYS) edit.remove(key);
                                    edit.commit();
                                    a.reduced = false;
                                    a.recreate();
                                }));
        a.paragraph(
                "mkoai "
                        + UpdateManager.installedVersion(a)
                        + " · 作者 Pilipala · github.com/Pilipala9919/MarcoMovie。本產品僅為工具，不含任何影視資源；內容與配置請自行合法獲取。播放能力取決於服務授權和裝置支援。");
    }

    void confirm(String title, Runnable action) {
        new AppDialog.Builder(a)
                .setTitle(title)
                .setNegativeButton("取消", null)
                .setPositiveButton("確定", (d, i) -> action.run())
                .show();
    }

    void diagnostics() {
        String text = AppLog.dump();
        new AppDialog.Builder(a)
                .setTitle("執行日誌")
                .setMessage(text.isEmpty() ? "當前執行正常，暫無異常或告警記錄。" : text)
                .setNeutralButton(
                        "清空記錄",
                        (d, i) -> {
                            AppLog.clear();
                            a.toast("已清空執行日誌");
                        })
                .setPositiveButton("關閉", null)
                .show();
    }

    void toggle(String title, String key, boolean defaultValue, Runnable after) {
        boolean tv = a.isTvDevice();
        boolean initial = a.prefs.getBoolean(key, defaultValue);
        LinearLayout row = a.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.dp(20), a.dp(15), a.dp(20), a.dp(15));
        MobileShell.SettingIconView icon =
                new MobileShell.SettingIconView(
                        a, MobileShell.SettingIconView.kindForSetting(key, title));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(a.dp(38), a.dp(38));
        iconLp.rightMargin = a.dp(14);
        row.addView(icon, iconLp);
        LinearLayout copy = a.vertical();
        copy.addView(a.text(title, 17, MainActivity.WHITE));
        final TextView caption = a.text(initial ? "開啟" : "關閉", 12, MainActivity.MUTED);
        caption.setPadding(0, a.dp(4), 0, 0);
        copy.addView(caption);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        final MobileShell.IosSwitchView toggleSwitch = new MobileShell.IosSwitchView(a, initial);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(a.dp(50), a.dp(30));
        switchLp.leftMargin = a.dp(12);
        row.addView(toggleSwitch, switchLp);

        row.setTag("setting-" + key);
        row.setId(View.generateViewId());
        row.setFocusable(tv);
        row.setFocusableInTouchMode(tv);
        row.setClickable(true);
        row.setBackground(a.bg(0xff222226, 14));
        row.setOnFocusChangeListener(
                (v, f) ->
                        FocusMotion.style(
                                a,
                                row,
                                f,
                                0xff222226,
                                0xff37373e,
                                14,
                                MainActivity.WHITE,
                                MainActivity.WHITE,
                                true,
                                1.008f));
        if (tv) {
            a.pressFeedback(row);
        } else {
            MobileShell.bindCardTouchFeedback(a, row, a.bg(0xff222226, 14), a.bg(0xff303038, 14));
        }
        row.setOnClickListener(
                v -> {
                    final boolean current = a.prefs.getBoolean(key, defaultValue);
                    final boolean nextVal = !current;
                    Runnable applyChange =
                            () -> {
                                a.prefs.edit().putBoolean(key, nextVal).apply();
                                toggleSwitch.setChecked(nextVal, !a.reduced);
                                caption.setText(nextVal ? "開啟" : "關閉");
                                if (after != null) after.run();
                                if (key.equals(Prefs.WALLPAPER_ENABLED)) {
                                    a.refresh();
                                }
                            };
                    if (key.equals(Prefs.EXPERIMENTAL_CSP) && nextVal && !current) {
                        confirm("執行配置源提供的擴充套件？請僅使用你信任的配置。", applyChange);
                    } else {
                        applyChange.run();
                    }
                });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = a.dp(14);
        a.body.addView(row, size);
    }

    void ints(String title, String key, String[] labels, int[] values, int fallback) {
        int current = a.prefs.getInt(key, fallback), selected = 0;
        for (int i = 0; i < values.length; i++) if (current == values[i]) selected = i;
        final int choice = selected;
        link(
                title,
                labels[selected],
                "setting-" + key,
                () -> {
                    if (!a.isTvDevice()) {
                        MobileShell.showDrawerPicker(
                                a,
                                title,
                                "在下方抽屜中選擇預設選項",
                                labels,
                                choice,
                                index -> {
                                    a.prefs.edit().putInt(key, values[index]).apply();
                                    a.refresh();
                                });
                        return;
                    }
                    new AppDialog.Builder(a)
                            .setTitle(title)
                            .setSingleChoiceItems(
                                    labels,
                                    choice,
                                    (dialog, index) -> {
                                        a.prefs.edit().putInt(key, values[index]).apply();
                                        dialog.dismiss();
                                        a.refresh();
                                    })
                            .setNegativeButton("取消", null)
                            .show();
                });
    }

    void pick(int request, String mime, boolean create) {
        if (a.privateMode && (request == 45 || request == 46)) {
            a.toast("請先退出隱私空間，再匯出或恢復公開備份");
            return;
        }
        try {
            Intent intent =
                    new Intent(
                            create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            if (create) intent.putExtra(Intent.EXTRA_TITLE, "mkoai-backup.json");
            a.startActivityForResult(intent, request);
        } catch (Exception error) {
            a.notice("電視沒有檔案選擇器。桌布也可以透過圖片地址匯入；檔案匯入匯出需要安裝檔案管理器。");
        }
    }

    void wallpaperUrl() {
        AddressInput input = new AddressInput(a, "https://…/wallpaper.jpg", "");
        new AppDialog.Builder(a)
                .setTitle("桌布圖片地址")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "匯入",
                        (d, i) -> {
                            String address = input.value();
                            if (!Cms.isHttp(address)) {
                                a.notice("請輸入 HTTP 或 HTTPS 圖片地址。");
                                return;
                            }
                            a.worker.execute(
                                    () -> {
                                        try {
                                            java.net.HttpURLConnection connection =
                                                    (java.net.HttpURLConnection)
                                                            new java.net.URL(
                                                                            Cms.normalizeHttp(
                                                                                    address))
                                                                    .openConnection();
                                            connection.setConnectTimeout(15000);
                                            connection.setReadTimeout(15000);
                                            try (InputStream stream = connection.getInputStream()) {
                                                saveWallpaper(a, stream);
                                            } finally {
                                                connection.disconnect();
                                            }
                                            a.ui.post(
                                                    () -> {
                                                        a.refresh();
                                                        a.toast("桌布已更新");
                                                    });
                                        } catch (Exception error) {
                                            a.ui.post(() -> a.notice("圖片無法讀取，請檢查圖片地址和格式。"));
                                        }
                                    });
                        })
                .show();
    }

    static void saveWallpaper(MainActivity a, InputStream stream) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int length;
        while ((length = stream.read(chunk)) != -1) {
            if (bytes.size() + length > 16 * 1024 * 1024) throw new IOException("Image too large");
            bytes.write(chunk, 0, length);
        }
        byte[] data = bytes.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1920
                || options.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (image == null) throw new IOException("Invalid image");
        File temporary = new File(a.getFilesDir(), "wallpaper.tmp");
        try {
            try (OutputStream output = new FileOutputStream(temporary)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 90, output))
                    throw new IOException("Encoding failed");
            }
            if (!temporary.renameTo(new File(a.getFilesDir(), "wallpaper.jpg")))
                throw new IOException("Unable to save wallpaper");
        } finally {
            image.recycle();
            temporary.delete();
        }
        a.prefs.edit().putBoolean(Prefs.WALLPAPER_ENABLED, true).apply();
    }

    static final String[] KEYS = Prefs.KEYS;

    static void result(MainActivity a, int request, Uri uri) {
        if (a.privateMode && (request == 45 || request == 46)) return;
        a.worker.execute(
                () -> {
                    try {
                        if (request == 44) {
                            try (InputStream input = a.getContentResolver().openInputStream(uri)) {
                                saveWallpaper(a, input);
                            }
                        } else if (request == 45) {
                            Object exportedConfig = org.json.JSONObject.NULL;
                            if (a.config != null && a.config.raw != null) {
                                try {
                                    String cleanRaw = Cms.withoutComments(a.config.raw).trim();
                                    exportedConfig =
                                            cleanRaw.startsWith("{")
                                                    ? new org.json.JSONObject(cleanRaw)
                                                    : a.config.raw;
                                } catch (Exception ignored) {
                                    exportedConfig = a.config.raw;
                                }
                            }
                            org.json.JSONObject backup =
                                    new org.json.JSONObject()
                                            .put("format", "MarcoMovie-1")
                                            .put("schema", "marco-movie-backup")
                                            .put("version", 1)
                                            .put("origin", a.configOrigin)
                                            .put("configOrigin", a.configOrigin)
                                            .put("config", exportedConfig);
                            org.json.JSONObject settings = new org.json.JSONObject();
                            for (String key : KEYS)
                                if (a.prefs.contains(key))
                                    settings.put(key, a.prefs.getAll().get(key));
                            for (String dataKey :
                                    new String[] {
                                        Prefs.SELECTED_SITE,
                                        Prefs.WATCH_HISTORY,
                                        Prefs.FAVORITES,
                                        Prefs.GLOBAL_SEARCH_HISTORY
                                    }) {
                                if (a.prefs.contains(dataKey)) {
                                    settings.put(dataKey, a.prefs.getString(dataKey, ""));
                                }
                            }
                            try {
                                ConfigLibrary lib = new ConfigLibrary(a.configStore.directory);
                                org.json.JSONArray subs = new org.json.JSONArray();
                                for (ConfigLibrary.Entry entry : lib.entries()) {
                                    String rawSub = lib.raw(entry);
                                    if (rawSub != null && !rawSub.trim().isEmpty()) {
                                        subs.put(
                                                new org.json.JSONObject()
                                                        .put("id", entry.id)
                                                        .put("name", entry.name)
                                                        .put("url", entry.origin)
                                                        .put("rawContent", rawSub)
                                                        .put("enabled", true)
                                                        .put("isPrivate", false));
                                    }
                                }
                                if (subs.length() > 0) {
                                    settings.put("saved_subscriptions", subs);
                                }
                            } catch (Exception ignored) {
                            }
                            backup.put("settings", settings);
                            try (OutputStream output =
                                    a.getContentResolver().openOutputStream(uri, "wt")) {
                                output.write(backup.toString(2).getBytes("UTF-8"));
                            }
                        } else if (request == 46) {
                            org.json.JSONObject backup;
                            try (InputStream input = a.getContentResolver().openInputStream(uri)) {
                                backup =
                                        new org.json.JSONObject(
                                                Cms.withoutComments(Cms.read(input, Cms.MAX_JSON)));
                            }
                            if (!Prefs.isBackupObject(
                                    backup.optString("format", null),
                                    backup.optString("schema", null),
                                    backup.optInt("version", 0)))
                                throw new IOException("Invalid backup");
                            final String origin =
                                    backup.has("origin") && !backup.optString("origin").isEmpty()
                                            ? backup.optString("origin", "")
                                            : backup.optString("configOrigin", "");
                            final String configJson;
                            if (!backup.has("config") || backup.isNull("config")) {
                                configJson = null;
                            } else {
                                configJson = Prefs.extractBackupConfigJson(backup.opt("config"));
                                if (configJson == null)
                                    throw new IOException("Invalid backup config");
                            }
                            final Cms.Config config =
                                    configJson == null ? null : Cms.parseConfig(configJson, origin);
                            final org.json.JSONObject settings = backup.optJSONObject("settings");
                            final org.json.JSONArray savedSubscriptions =
                                    settings == null
                                            ? null
                                            : settings.optJSONArray("saved_subscriptions");
                            if (savedSubscriptions != null) {
                                for (int i = 0; i < savedSubscriptions.length(); i++) {
                                    org.json.JSONObject sub = savedSubscriptions.getJSONObject(i);
                                    String subRaw = sub.optString("rawContent", "").trim();
                                    String subOrigin = sub.optString("url", "");
                                    if (!subRaw.isEmpty()) {
                                        Cms.parseConfig(subRaw, subOrigin);
                                    }
                                }
                            }
                            if (settings != null) {
                                for (String key :
                                        new String[] {
                                            Prefs.WATCH_HISTORY,
                                            Prefs.FAVORITES,
                                            Prefs.GLOBAL_SEARCH_HISTORY
                                        }) {
                                    if (!settings.has(key)) continue;
                                    org.json.JSONArray rows =
                                            new org.json.JSONArray(
                                                    String.valueOf(settings.get(key)));
                                    for (int i = 0; i < rows.length(); i++) {
                                        if (Prefs.GLOBAL_SEARCH_HISTORY.equals(key)) {
                                            if (!(rows.get(i) instanceof String))
                                                throw new IOException("Invalid search history");
                                        } else {
                                            org.json.JSONObject item = rows.getJSONObject(i);
                                            if (item.optString("id").isEmpty()
                                                    || item.optString("site").isEmpty())
                                                throw new IOException("Invalid bookmark");
                                        }
                                    }
                                }
                            }
                            a.ui.post(
                                    () ->
                                            new AppDialog.Builder(a)
                                                    .setTitle("還原備份？")
                                                    .setMessage("將替換本機來源配置與外觀、播放偏好。網盤賬號保持不變。")
                                                    .setNegativeButton("取消", null)
                                                    .setPositiveButton(
                                                            "還原",
                                                            (d, i) -> {
                                                                try {
                                                                    int oldScale =
                                                                            a.prefs.getInt(
                                                                                    Prefs
                                                                                            .UI_SCALE_PERCENT,
                                                                                    100);
                                                                    restoreSettings(a, settings);
                                                                    if (savedSubscriptions
                                                                            != null) {
                                                                        try {
                                                                            ConfigLibrary lib =
                                                                                    new ConfigLibrary(
                                                                                            a.configStore
                                                                                                    .directory);
                                                                            for (int idx = 0;
                                                                                    idx
                                                                                            < savedSubscriptions
                                                                                                    .length();
                                                                                    idx++) {
                                                                                org.json.JSONObject
                                                                                        sub =
                                                                                                savedSubscriptions
                                                                                                        .optJSONObject(
                                                                                                                idx);
                                                                                if (sub == null)
                                                                                    continue;
                                                                                String subRaw =
                                                                                        sub.optString(
                                                                                                        "rawContent",
                                                                                                        "")
                                                                                                .trim();
                                                                                if (subRaw
                                                                                        .isEmpty())
                                                                                    continue;
                                                                                lib
                                                                                        .rememberIfAbsent(
                                                                                                subRaw,
                                                                                                sub
                                                                                                        .optString(
                                                                                                                "url",
                                                                                                                ""),
                                                                                                sub
                                                                                                        .optString(
                                                                                                                "name",
                                                                                                                "已恢復的訂閱"),
                                                                                                "");
                                                                            }
                                                                        } catch (
                                                                                Exception ignored) {
                                                                        }
                                                                    }
                                                                    if (config != null)
                                                                        a.saveConfig(
                                                                                config, origin);
                                                                    else a.refresh();
                                                                    if (oldScale
                                                                            != a.prefs.getInt(
                                                                                    Prefs
                                                                                            .UI_SCALE_PERCENT,
                                                                                    100))
                                                                        a.recreate();
                                                                } catch (Exception error) {
                                                                    AppLog.w(
                                                                            "Settings",
                                                                            "restoreSettings failed",
                                                                            error);
                                                                    a.notice("備份無法還原。");
                                                                }
                                                            })
                                                    .show());
                            return;
                        }
                        a.ui.post(
                                () -> {
                                    a.refresh();
                                    a.toast(request == 45 ? "備份已儲存" : "桌布已更新");
                                });
                    } catch (Exception error) {
                        AppLog.w("Settings", "file result " + request + " failed", error);
                        a.ui.post(() -> a.notice("檔案無法處理。請檢查檔案格式與可用儲存空間。"));
                    }
                });
    }

    static void restoreSettings(MainActivity a, org.json.JSONObject settings) {
        if (settings == null) return;
        android.content.SharedPreferences.Editor edit = a.prefs.edit();
        for (String key : KEYS) {
            Object clean = Prefs.sanitizeSetting(key, settings.opt(key));
            if (clean instanceof Boolean) edit.putBoolean(key, (Boolean) clean);
            else if (clean instanceof Float) edit.putFloat(key, (Float) clean);
            else if (clean instanceof Integer) edit.putInt(key, (Integer) clean);
        }
        for (String dataKey :
                new String[] {
                    Prefs.SELECTED_SITE,
                    Prefs.WATCH_HISTORY,
                    Prefs.FAVORITES,
                    Prefs.GLOBAL_SEARCH_HISTORY
                }) {
            if (settings.has(dataKey)) {
                String cleanData = Prefs.sanitizeUserData(dataKey, settings.opt(dataKey));
                if (cleanData != null) edit.putString(dataKey, cleanData);
            }
        }
        edit.apply();
        a.reduced = a.prefs.getBoolean(Prefs.REDUCED, false);
    }
}
