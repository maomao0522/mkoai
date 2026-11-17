package app.mkoai.player;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;

/** The source chooser stays above the current screen and never creates a second navigation page. */
final class SourcePicker {
    private static String profile(String origin) {
        return "source-picker:" + (origin == null ? "" : origin);
    }

    static void applyEdits(MainActivity a, Cms.Config config, String origin) {
        String legacyProfile = profile(origin);
        if (!config.origin.isEmpty()) origin = config.origin;
        String currentProfile = profile(origin);
        if (!currentProfile.equals(legacyProfile)
                && !a.prefs.contains(currentProfile)
                && a.prefs.contains(legacyProfile)) {
            String old = a.prefs.getString(legacyProfile, "{}");
            if (!a.prefs.edit().putString(currentProfile, old).remove(legacyProfile).commit()) {
                // Reading the old edit record still honors deletion if migration cannot persist.
                currentProfile = legacyProfile;
            }
        }
        try {
            org.json.JSONObject saved =
                    new org.json.JSONObject(a.prefs.getString(currentProfile, "{}"));
            org.json.JSONArray order = saved.optJSONArray("order"),
                    removed = saved.optJSONArray("removed");
            java.util.List<Cms.Site> filtered =
                    applyProfileToSites(new java.util.ArrayList<>(config.sites), order, removed);
            config.sites.clear();
            config.sites.addAll(filtered);
        } catch (org.json.JSONException ignored) {
            /* Keep the imported order. */
        }
    }

    /** mkoai 2.0.74: apply an order/removed profile to a site list — the pure half of applyEdits, so the JVM tests can pin the delete → reload round-trip. Removed keys drop out; ordered keys come first in order; anything left keeps its imported order. */
    static java.util.List<Cms.Site> applyProfileToSites(
            java.util.List<Cms.Site> sites,
            org.json.JSONArray order,
            org.json.JSONArray removed) {
        java.util.Map<String, Cms.Site> byKey = new java.util.LinkedHashMap<>();
        for (Cms.Site s : sites) byKey.put(s.key, s);
        if (removed != null)
            for (int i = 0; i < removed.length(); i++) byKey.remove(removed.optString(i));
        java.util.List<Cms.Site> out = new java.util.ArrayList<>();
        if (order != null)
            for (int i = 0; i < order.length(); i++) {
                Cms.Site s = byKey.remove(order.optString(i));
                if (s != null) out.add(s);
            }
        out.addAll(byKey.values());
        return out;
    }

    /** mkoai 2.0.74: the persisted profile (order + removed) after deleting keys. order keeps the surviving keys in their given order; removed gains the deleted keys. Pure JSON — the JVM tests pin the round-trip. */
    static org.json.JSONObject profileAfterDelete(
            org.json.JSONObject saved,
            java.util.List<String> survivingKeys,
            java.util.Collection<String> deleteKeys)
            throws org.json.JSONException {
        org.json.JSONArray hidden = saved.optJSONArray("removed");
        if (hidden == null) hidden = new org.json.JSONArray();
        for (String k : deleteKeys) hidden.put(k);
        saved.put("removed", hidden);
        org.json.JSONArray order = new org.json.JSONArray();
        for (String k : survivingKeys) order.put(k);
        saved.put("order", order);
        return saved;
    }

    /** mkoai 2.0.74: the persisted profile after deleting ALL sources — a clean reset for this origin. order is empty AND removed is empty, so re-importing the SAME config brings every source back (user decision 2026-11-17: 全刪等於清空重來). Single deletes keep using profileAfterDelete, which keeps the removed list. Pure JSON — the JVM tests pin the round-trip. */
    static org.json.JSONObject profileAfterDeleteAll(org.json.JSONObject saved)
            throws org.json.JSONException {
        saved.put("order", new org.json.JSONArray());
        saved.put("removed", new org.json.JSONArray());
        return saved;
    }

    /** mkoai 2.0.74: pick the fallback current source after a delete — the preferred source when available, else the first available one, else null (no source left). Pure over key sets so the JVM tests can pin it. */
    static Cms.Site pickFallback(
            java.util.List<Cms.Site> sites,
            java.util.Set<String> preferredKeys,
            java.util.Set<String> availableKeys) {
        for (Cms.Site s : sites) {
            if (preferredKeys.contains(s.key) && availableKeys.contains(s.key)) return s;
        }
        for (Cms.Site s : sites) {
            if (availableKeys.contains(s.key)) return s;
        }
        return null;
    }

    static void manage(MainActivity a) {
        Cms.Config config = a.config;
        if (config == null || config.sites.isEmpty()) {
            a.notice("暫無可管理的線路");
            return;
        }
        String[] names = new String[config.sites.size()];
        for (int i = 0; i < names.length; i++) names[i] = config.sites.get(i).name;
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("管理線路")
                .setItems(
                        names,
                        (d, index) -> {
                            Cms.Site site = config.sites.get(index);
                            new AppDialog.Builder(a)
                                    .wrapChoices()
                                    .setTitle(site.name)
                                    .setItems(
                                            new String[] {"上移", "下移", "移到首位", "刪除這條線路"},
                                            (action, choice) -> {
                                                if (choice == 3) {
                                                    new AppDialog.Builder(a)
                                                            .wrapChoices()
                                                            .setTitle("刪除「" + site.name + "」？")
                                                            .setNegativeButton("取消", null)
                                                            .setPositiveButton(
                                                                    "刪除",
                                                                    (ok, which) ->
                                                                            edit(
                                                                                    a, config, site,
                                                                                    -1))
                                                            .show();
                                                } else {
                                                    int target =
                                                            choice == 2
                                                                    ? 0
                                                                    : Math.max(
                                                                            0,
                                                                            Math.min(
                                                                                    names.length
                                                                                            - 1,
                                                                                    index
                                                                                            + (choice
                                                                                                            == 0
                                                                                                    ? -1
                                                                                                    : 1)));
                                                    edit(a, config, site, target);
                                                }
                                            })
                                    .show();
                        })
                .show();
    }

    private static void edit(MainActivity a, Cms.Config config, Cms.Site site, int target) {
        if (a.config != config) return;
        try {
            String key = profile(config.origin.isEmpty() ? a.configOrigin : config.origin);
            org.json.JSONObject saved = new org.json.JSONObject(a.prefs.getString(key, "{}"));
            java.util.List<Cms.Site> sites = new java.util.ArrayList<>(config.sites);
            sites.remove(site);
            if (target >= 0) sites.add(target, site);
            java.util.List<String> survivingKeys = new java.util.ArrayList<>();
            for (Cms.Site s : sites) survivingKeys.add(s.key);
            java.util.Collection<String> deletedKeys =
                    target < 0
                            ? java.util.Collections.singletonList(site.key)
                            : java.util.Collections.emptyList();
            saved = profileAfterDelete(saved, survivingKeys, deletedKeys);
            android.content.SharedPreferences.Editor editor =
                    a.prefs.edit().putString(key, saved.toString());
            // mkoai 2.0.38: deleting the current source auto-switches to
            // the preferred source when available, else the first
            // available one — never stay on a deleted source.
            if (target < 0 && site.key.equals(a.prefs.getString(Prefs.SELECTED_SITE, ""))) {
                editor.remove(Prefs.SELECTED_SITE);
                editor.remove(Prefs.LOADING_SITE);
                java.util.Set<String> preferredKeys = new java.util.HashSet<>();
                java.util.Set<String> availableKeys = new java.util.HashSet<>();
                for (Cms.Site s : sites) {
                    if (SourcePreferences.isPreferred(a, s)) preferredKeys.add(s.key);
                    if (a.available(s)) availableKeys.add(s.key);
                }
                Cms.Site fallback = pickFallback(sites, preferredKeys, availableKeys);
                if (fallback != null) {
                    editor.putString(Prefs.SELECTED_SITE, fallback.key);
                    editor.putString(Prefs.LOADING_SITE, fallback.key);
                }
            }
            if (!editor.commit()) {
                a.notice("線路變更未能儲存，請重試");
                return;
            }
            config.sites.clear();
            config.sites.addAll(sites);
            // mkoai 2.0.74: do NOT navigate away. The picker is a dialog
            // over the current page; the old tail (back.clear + open(home))
            // replaced a.root and reset Settings to its root page, and
            // show(a) then ran while the new root was still unlaid-out, so
            // a.root.getWidth() was 0 and the dialog got a negative window
            // width — the picker came back with every control pushed
            // off-screen (2.0.73 field video, 2026-11-17). Stay on the
            // current page, refresh Settings in place so the "當前：" capsule
            // follows the new current source, and just re-show the picker.
            refreshSettingsInPlace(a);
            show(a);
        } catch (org.json.JSONException e) {
            a.notice("無法儲存線路順序");
        }
    }

    /** mkoai 2.0.74: re-render the Settings page in place after a source edit (MobileShell.refreshSettingsAfterSourceEdit). No-op unless the user is on the Settings tab; never navigates or replaces the root, so the open picker keeps a laid-out root and the Settings sub-page stays where the user left it. */
    private static void refreshSettingsInPlace(MainActivity a) {
        if (a.current != null && a.current.mobileHome != null) {
            a.current.mobileHome.refreshSettingsAfterSourceEdit();
        }
    }

    static void show(MainActivity a) {
        if (a.config == null) {
            a.importDialog();
            return;
        }
        View anchor = a.getCurrentFocus();
        Dialog dialog = new Dialog(a);
        a.sourceDialog = dialog;
        dialog.setOnDismissListener(
                d -> {
                    if (a.sourceDialog == dialog) a.sourceDialog = null;
                    if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
                });
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean tv = a.isTvDevice();
        LinearLayout panel = a.vertical();
        panel.setTag("source-picker");
        panel.setPadding(
                a.dp(tv ? 22 : 16), a.dp(tv ? 18 : 16), a.dp(tv ? 22 : 16), a.dp(tv ? 16 : 14));
        a.glass(panel, 22);

        LinearLayout headerRow = a.row();
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = a.vertical();
        LinearLayout titleBadgeRow = a.row();
        titleBadgeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = a.text("選擇片源", tv ? 22 : 19, MainActivity.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        titleBadgeRow.addView(title);
        TextView count = a.text(a.config.sites.size() + " 個片源", 11.5f, 0xff9ca3af);
        count.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
        count.setBackground(a.bg(0x22ffffff, 999));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
        countLp.leftMargin = a.dp(8);
        titleBadgeRow.addView(count, countLp);
        titleCol.addView(titleBadgeRow);
        TextView hint = a.text("選擇後返回首頁，顯示該片源的內容", 12, MainActivity.MUTED);
        hint.setPadding(0, a.dp(2), 0, 0);
        titleCol.addView(hint);
        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        boolean hasExtraTopActions = a.config.depots.length() > 0 || CatPawSettings.available(a);
        Button prefBtn =
                a.navButton(
                        "預設偏好源",
                        "source-preferred",
                        () -> {
                            dialog.dismiss();
                            SourcePreferences.choose(a);
                        });
        prefBtn.setTextSize(12.5f);
        prefBtn.setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
        prefBtn.setMinHeight(a.dp(34));
        prefBtn.setMinimumHeight(a.dp(34));
        if (!hasExtraTopActions) {
            headerRow.addView(prefBtn, new LinearLayout.LayoutParams(-2, a.dp(34)));
        }
        panel.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        if (hasExtraTopActions) {
            a.gap(panel, 8);
            LinearLayout topActions = a.row();
            if (a.config.depots.length() > 0) {
                Button depotBtn =
                        a.navButton(
                                "切換子倉庫",
                                "warehouse-switch",
                                () -> {
                                    dialog.dismiss();
                                    showWarehouses(a);
                                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(34), 1);
                lp.rightMargin = a.dp(6);
                topActions.addView(depotBtn, lp);
            }
            LinearLayout.LayoutParams prefLp = new LinearLayout.LayoutParams(0, a.dp(34), 1);
            if (CatPawSettings.available(a)) prefLp.rightMargin = a.dp(6);
            topActions.addView(prefBtn, prefLp);
            if (CatPawSettings.available(a)) {
                Button catBtn =
                        a.navButton(
                                "CatPaw 配置",
                                "catpaw-settings",
                                () -> {
                                    dialog.dismiss();
                                    CatPawSettings.choose(a);
                                });
                topActions.addView(catBtn, new LinearLayout.LayoutParams(0, a.dp(34), 1));
            }
            panel.addView(topActions, new LinearLayout.LayoutParams(-1, -2));
        }
        a.gap(panel, 8);
        ScrollView scrolling = new TvScrollView(a);
        scrolling.setVerticalScrollBarEnabled(false);
        LinearLayout grid = a.vertical();
        grid.setPadding(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
        scrolling.addView(grid);
        FrameLayout viewport =
                new FrameLayout(a) {
                    @Override
                    protected void dispatchDraw(android.graphics.Canvas c) {
                        int save = c.save();
                        c.clipRect(0, 0, getWidth(), getHeight());
                        super.dispatchDraw(c);
                        c.restoreToCount(save);
                    }
                };
        viewport.setTag("scroll-viewport-clip");
        viewport.setClipChildren(true);
        viewport.setClipToPadding(true);
        viewport.addView(scrolling, new FrameLayout.LayoutParams(-1, -1));
        int defaultViewportH = a.dp(tv ? 330 : 370);
        panel.addView(viewport, new LinearLayout.LayoutParams(-1, defaultViewportH));
        String selected = a.prefs.getString(Prefs.SELECTED_SITE, "");
        View selectedCard = null;
        // mkoai 2.0.60: "全部片源" — the TV home default (all sources merged).
        // Selecting it clears the pinned source so the home returns to the
        // merged view. It is not a real source, so it has no delete button.
        {
            boolean current = selected.isEmpty();
            LinearLayout card = a.row();
            card.setTag("source-choice:" + HomeScreen.ALL_SOURCES_KEY);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(a.dp(12), a.dp(10), a.dp(8), a.dp(10));
            card.setFocusable(true);
            card.setClickable(true);
            MobileDetailScreen.bindTouchFeedback(card);
            if (tv) card.setFocusable(true);
            TextView playIcon = a.text("▶", 12, Color.WHITE);
            playIcon.setGravity(Gravity.CENTER);
            playIcon.setBackground(a.bg(0xff34c759, 999));
            LinearLayout.LayoutParams iconLp =
                    new LinearLayout.LayoutParams(a.dp(32), a.dp(32));
            iconLp.rightMargin = a.dp(12);
            card.addView(playIcon, iconLp);
            LinearLayout texts = a.vertical();
            TextView name = a.text("全部片源", 15, 0xff111111);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            texts.addView(name);
            TextView subTv = a.text(a.config.sites.size() + " 個片源一起顯示 · 主頁預設", 12, 0xff6b7280);
            subTv.setSingleLine(true);
            subTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subTv.setPadding(0, a.dp(2), 0, 0);
            texts.addView(subTv);
            card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout right = a.vertical();
            right.setGravity(Gravity.CENTER_HORIZONTAL);
            View dot = new View(a);
            dot.setBackground(a.bg(0xff34c759, 999));
            LinearLayout.LayoutParams dotLp =
                    new LinearLayout.LayoutParams(a.dp(10), a.dp(10));
            dotLp.bottomMargin = a.dp(6);
            right.addView(dot, dotLp);
            TextView mark =
                    a.text(current ? "✓" : "›", current ? 15 : 16,
                            current ? 0xff34c759 : 0xff9ca3af);
            mark.setTypeface(null, Typeface.BOLD);
            mark.setGravity(Gravity.CENTER);
            right.addView(mark);
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(-2, -2);
            rightLp.leftMargin = a.dp(10);
            card.addView(right, rightLp);
            Runnable paint = () -> card.setBackground(sourceCardBg(a, current, card.hasFocus()));
            card.setOnFocusChangeListener((v, f) -> paint.run());
            paint.run();
            card.setOnClickListener(v -> selectAllSources(a, dialog));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            grid.addView(card, lp);
            if (current) selectedCard = card;
        }
        // mkoai 2.0.38 (VidHub style): one clean light card per source
        // (was a 2-3 column dark button grid). Each card shows the play
        // icon, name, host/type, a status dot and a selected check; the
        // ✕ button deletes the source (with confirmation).
        for (Cms.Site site : a.config.sites) {
            boolean current = site.key.equals(selected);
            boolean preferred = SourcePreferences.isPreferred(a, site);
            boolean enabled = a.available(site);
            LinearLayout card = a.row();
            card.setTag("source-choice:" + site.key);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(a.dp(12), a.dp(10), a.dp(8), a.dp(10));
            card.setFocusable(true);
            card.setClickable(true);
            MobileDetailScreen.bindTouchFeedback(card);
            // 2.0.58: bindTouchFeedback clears focusable (fine for touch
            // phones, fatal for TV remotes — a non-focusable card can
            // never take D-pad focus, so DPAD_UP/DOWN never reaches it
            // and DPAD_CENTER has nothing to click). Re-enable focus on
            // TV only; the phone path is unchanged (focusableInTouchMode
            // stays false, so taps behave exactly as before).
            if (tv) card.setFocusable(true);
            TextView playIcon = a.text("▶", 12, Color.WHITE);
            playIcon.setGravity(Gravity.CENTER);
            playIcon.setBackground(a.bg(0xff34c759, 999));
            LinearLayout.LayoutParams iconLp =
                    new LinearLayout.LayoutParams(a.dp(32), a.dp(32));
            iconLp.rightMargin = a.dp(12);
            card.addView(playIcon, iconLp);
            LinearLayout texts = a.vertical();
            TextView name = a.text((preferred ? "★ " : "") + site.name, 15, 0xff111111);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(name);
            String host = hostOf(site);
            String typeLabel =
                    site.type == 0 || site.type == 1 ? "CMS" : site.type == 3 ? "擴充" : "其他";
            String sub = host == null || host.isEmpty() ? typeLabel : host + " · " + typeLabel;
            TextView subTv = a.text(sub, 12, 0xff6b7280);
            subTv.setSingleLine(true);
            subTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subTv.setPadding(0, a.dp(2), 0, 0);
            texts.addView(subTv);
            card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout right = a.vertical();
            right.setGravity(Gravity.CENTER_HORIZONTAL);
            View dot = new View(a);
            dot.setBackground(a.bg(enabled ? 0xff34c759 : 0xff9ca3af, 999));
            LinearLayout.LayoutParams dotLp =
                    new LinearLayout.LayoutParams(a.dp(10), a.dp(10));
            dotLp.bottomMargin = a.dp(6);
            right.addView(dot, dotLp);
            TextView mark =
                    a.text(
                            current ? "✓" : "›",
                            current ? 15 : 16,
                            current ? 0xff34c759 : 0xff9ca3af);
            mark.setTypeface(null, Typeface.BOLD);
            mark.setGravity(Gravity.CENTER);
            right.addView(mark);
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(-2, -2);
            rightLp.leftMargin = a.dp(10);
            card.addView(right, rightLp);
            TextView del = a.text("✕", 14, 0xff9ca3af);
            del.setTag("source-delete:" + site.key);
            del.setGravity(Gravity.CENTER);
            del.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
            del.setFocusable(true);
            del.setClickable(true);
            MobileDetailScreen.bindTouchFeedback(del);
            // 2.0.58: same TV-focus fix as the card (see above) — without
            // it the ✕ delete button is unreachable with the remote.
            if (tv) del.setFocusable(true);
            del.setOnFocusChangeListener((v, f) -> del.setTextColor(f ? 0xffef4444 : 0xff9ca3af));
            del.setOnClickListener(v -> confirmDeleteSource(a, dialog, site));
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(-2, -2);
            delLp.leftMargin = a.dp(4);
            card.addView(del, delLp);
            Runnable paint = () -> card.setBackground(sourceCardBg(a, current, card.hasFocus()));
            card.setOnFocusChangeListener((v, f) -> paint.run());
            paint.run();
            card.setOnClickListener(v -> selectSource(a, dialog, site));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = a.dp(8);
            grid.addView(card, lp);
            if (current) selectedCard = card;
        }
        a.gap(panel, 8);
        LinearLayout footer = a.row();
        if (tv) {
            a.addButton(
                    footer,
                    a.navButton(
                            "已新增源",
                            "source-library",
                            () -> {
                                dialog.dismiss();
                                showSavedConfigs(a);
                            }));
        }
        a.addButton(
                footer,
                a.navButton(
                        "管理線路",
                        "source-manage",
                        () -> {
                            dialog.dismiss();
                            manage(a);
                        }));
        a.addButton(
                footer,
                a.navButton(
                        tv ? "新增新源" : "更新配置",
                        "source-update",
                        () -> {
                            dialog.dismiss();
                            a.importDialog();
                        }));
        a.addButton(footer, a.navButton("完成", "source-close", dialog::dismiss));
        // mkoai 2.0.74: one-click delete-all (phone only — TV has no such
        // picker, so the button is not added there). Destructive, so it
        // confirms first (confirmDeleteAllSources). The footer loop below
        // gives it the same weight-1 / 38dp treatment as the other three.
        if (!tv) {
            Button deleteAllBtn =
                    a.navButton(
                            "刪除全部",
                            "source-delete-all",
                            () -> {
                                dialog.dismiss();
                                confirmDeleteAllSources(a);
                            });
            deleteAllBtn.setTextColor(0xffef4444);
            a.addButton(footer, deleteAllBtn);
        }
        for (int i = 0; i < footer.getChildCount(); i++) {
            View control = footer.getChildAt(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, a.dp(38), 1);
            params.setMargins(a.dp(3), a.dp(2), a.dp(3), a.dp(2));
            control.setLayoutParams(params);
            if (control instanceof TextView) {
                ((TextView) control).setSingleLine(true);
                ((TextView) control).setEllipsize(android.text.TextUtils.TruncateAt.END);
                ((TextView) control).setTextSize(13);
                control.setPadding(a.dp(8), 0, a.dp(8), 0);
            }
        }
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        int marginH = tv ? a.dp(48) : a.dp(20);
        int panelWidth = Math.min(a.dp(740), a.windowWidth() - marginH);
        int maxHeight = Math.max(a.dp(180), a.windowHeight() - a.dp(tv ? 32 : 84));
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int controlsHeight = panel.getMeasuredHeight() - defaultViewportH;
        viewport.setLayoutParams(
                new LinearLayout.LayoutParams(
                        -1,
                        Math.max(
                                a.dp(96),
                                Math.min(a.dp(tv ? 360 : 410), maxHeight - controlsHeight))));
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (panel.getMeasuredHeight() > maxHeight) {
            ScrollView outer = new TvScrollView(a);
            outer.addView(panel);
            dialog.setContentView(outer, new ViewGroup.LayoutParams(panelWidth, maxHeight));
        } else dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(panelWidth, -2);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.CENTER;
            params.dimAmount = .65f;
            window.setAttributes(params);
        }
        View focus = selectedCard;
        dialog.setOnShowListener(
                d -> {
                    if (focus != null) focus.requestFocus();
                    else if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
                });
        dialog.show();
        // mkoai 2.0.74: windowWidth(), not a.root.getWidth(). The root may be
        // a fresh, never-laid-out FrameLayout (getWidth() == 0) when show()
        // follows a navigation, which used to pass a NEGATIVE width here
        // (0 - marginH) and corrupt the dialog window — the picker came back
        // wider than the screen with every right-side control (預設偏好源,
        // dot / ✓ / ✕, footer text) pushed off-screen. windowWidth() is
        // stable (last measured width, or the display width) and equals the
        // laid-out root width on the normal path, so the dialog size is
        // unchanged there.
        if (window != null)
            window.setLayout(Math.min(a.dp(720), a.windowWidth() - marginH), -2);
    }

    /** mkoai 2.0.38: light VidHub-style card background (green when selected/focused). */
    private static android.graphics.drawable.GradientDrawable sourceCardBg(
            MainActivity a, boolean current, boolean focused) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        current
                                ? new int[] {0xffe4f6e9, 0xfff2faf3}
                                : new int[] {0xfff2faf3, 0xffffffff});
        d.setCornerRadius(a.dp(14));
        if (current) {
            d.setStroke(Math.max(1, Math.round(a.dp(focused ? 2.5f : 2f))), 0xff34c759);
        } else if (focused) {
            d.setStroke(Math.max(1, Math.round(a.dp(2f))), 0xff34c759);
        } else {
            d.setStroke(Math.max(1, Math.round(a.dp(1f))), 0x1a000000);
        }
        return d;
    }

    private static String hostOf(Cms.Site site) {
        try {
            android.net.Uri u = android.net.Uri.parse(site.api);
            String host = u.getHost();
            if (host != null && !host.isEmpty()) return host;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Select a source (same behaviour as the old grid button). */
    private static void selectSource(MainActivity a, Dialog dialog, Cms.Site site) {
        if (site.api.startsWith("catpaw://")
                && android.net.Uri.parse(site.api).getPath().equals("/api/baseset")) {
            dialog.dismiss();
            CatPawSettings.open(a, site);
            return;
        }
        if (!a.available(site)) {
            a.notice("這個片源需要啟用來源擴充套件，或仍有介面待適配。可以在設定中檢視。");
            return;
        }
        a.prefs
                .edit()
                .putString(Prefs.SELECTED_SITE, site.key)
                .putString(Prefs.LOADING_SITE, site.key)
                .commit();
        dialog.dismiss();
        a.back.clear();
        a.open(new MainActivity.Screen(a::home), false);
    }

    /** mkoai 2.0.60: select "全部片源" (the TV home default) — clear the pinned source so the home merges all sources. */
    private static void selectAllSources(MainActivity a, Dialog dialog) {
        a.prefs
                .edit()
                .remove(Prefs.SELECTED_SITE)
                .putString(Prefs.LOADING_SITE, HomeScreen.ALL_SOURCES_KEY)
                .commit();
        dialog.dismiss();
        a.back.clear();
        a.open(new MainActivity.Screen(a::home), false);
    }

    /** mkoai 2.0.38: delete a source from the card (confirm first). */
    private static void confirmDeleteSource(MainActivity a, Dialog dialog, Cms.Site site) {
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("刪除「" + site.name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "刪除",
                        (d, w) -> {
                            dialog.dismiss();
                            edit(a, a.config, site, -1);
                        })
                .show();
    }

    /** mkoai 2.0.74: one-click delete-all — confirm first; the title states it deletes every source and leaves 0. */
    private static void confirmDeleteAllSources(MainActivity a) {
        Cms.Config config = a.config;
        if (config == null || config.sites.isEmpty()) {
            a.notice("目前沒有片源可以刪除");
            return;
        }
        int count = config.sites.size();
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("刪除全部片源？")
                .setMessage(
                        "將一次刪除全部 "
                                + count
                                + " 個片源，之後只剩 0 個片源；首頁將沒有任何片源，之後重新匯入同一份設定檔，片源會全部回來。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部刪除", (d, w) -> deleteAllSources(a, config))
                .show();
    }

    /** mkoai 2.0.74: delete every source at once — one commit, one re-show. Unlike a single delete, the profile for this origin is RESET (order AND removed both empty), so re-importing the SAME config brings every source back — delete-all is a clean slate (user decision 2026-11-17: 全刪等於清空重來). SELECTED_SITE / LOADING_SITE are cleared so nothing points at a deleted source. After this the picker shows 0 個片源, the home shows its empty welcome, and 更新配置 can import again. */
    private static void deleteAllSources(MainActivity a, Cms.Config config) {
        if (a.config != config) return;
        try {
            String key = profile(config.origin.isEmpty() ? a.configOrigin : config.origin);
            org.json.JSONObject saved = new org.json.JSONObject(a.prefs.getString(key, "{}"));
            saved = profileAfterDeleteAll(saved);
            android.content.SharedPreferences.Editor editor =
                    a.prefs.edit().putString(key, saved.toString());
            editor.remove(Prefs.SELECTED_SITE);
            editor.remove(Prefs.LOADING_SITE);
            if (!editor.commit()) {
                a.notice("刪除全部片源未能儲存，請重試");
                return;
            }
            config.sites.clear();
            refreshSettingsInPlace(a);
            show(a);
        } catch (org.json.JSONException e) {
            a.notice("無法儲存線路變更");
        }
    }

    static void showSavedConfigs(MainActivity a) {
        Cms.Config previous = a.config;
        ConfigLibrary library = new ConfigLibrary(a.configStore.directory);
        String previousOrigin = a.configOrigin;
        String previousSelection = a.prefs.getString(Prefs.SELECTED_SITE, "");
        a.worker.execute(
                () -> {
                    try {
                        library.rememberIfAbsent(
                                previous.raw,
                                previousOrigin,
                                SourceController.configName(previous),
                                previousSelection);
                        java.util.List<ConfigLibrary.Entry> entries = library.entries();
                        String active = ConfigLibrary.identity(previousOrigin, previous.raw);
                        String[] names = new String[entries.size()];
                        int selected = -1;
                        for (int i = 0; i < names.length; i++) {
                            ConfigLibrary.Entry entry = entries.get(i);
                            names[i] = entry.name + "（訂閱 " + (i + 1) + "）";
                            if (entry.id.equals(active)) selected = i;
                        }
                        final int checked = selected;
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing() || a.config != previous) return;
                                    new AppDialog.Builder(a)
                                            .wrapChoices()
                                            .setTitle("已新增源 · " + entries.size() + " 個訂閱")
                                            .setSingleChoiceItems(
                                                    names,
                                                    checked,
                                                    (dialog, index) -> {
                                                        dialog.dismiss();
                                                        ConfigLibrary.Entry entry =
                                                                entries.get(index);
                                                        if (entry.id.equals(active)) {
                                                            show(a);
                                                            return;
                                                        }
                                                        a.worker.execute(
                                                                () -> {
                                                                    try {
                                                                        Cms.Config saved =
                                                                                Cms.parseConfig(
                                                                                        library.raw(
                                                                                                entry),
                                                                                        entry.origin);
                                                                        a.ui.post(
                                                                                () -> {
                                                                                    if (a
                                                                                                    .isFinishing()
                                                                                            || a.config
                                                                                                    != previous)
                                                                                        return;
                                                                                    try {
                                                                                        saveSelectedConfig(
                                                                                                a,
                                                                                                saved,
                                                                                                entry.origin);
                                                                                    } catch (
                                                                                            Exception
                                                                                                    error) {
                                                                                        a.notice(
                                                                                                "訂閱切換失敗，已保留當前片源");
                                                                                    }
                                                                                });
                                                                    } catch (Exception error) {
                                                                        a.ui.post(
                                                                                () ->
                                                                                        a.notice(
                                                                                                "無法讀取這份訂閱，已保留當前片源"));
                                                                    }
                                                                });
                                                    })
                                            .setNegativeButton("關閉", null)
                                            .show();
                                });
                    } catch (Exception error) {
                        a.ui.post(() -> a.notice("無法讀取已新增源，原訂閱檔案已保留"));
                    }
                });
    }

    private static void saveSelectedConfig(MainActivity a, Cms.Config saved, String origin)
            throws Exception {
        SourceController.saveConfig(a, saved, origin);
        show(a);
    }

    static void showWarehouses(MainActivity a) {
        Cms.Config previous = a.config;
        org.json.JSONArray rows = previous.depots;
        String[] names = new String[rows.length()];
        for (int i = 0; i < names.length; i++) names[i] = rows.optJSONObject(i).optString("name");
        new AppDialog.Builder(a)
                .wrapChoices()
                .setTitle("切換子倉庫")
                .setItems(
                        names,
                        (dialog, index) -> {
                            String url = rows.optJSONObject(index).optString("url");
                            a.toast("正在讀取子倉庫…");
                            a.worker.execute(
                                    () -> {
                                        try {
                                            Cms.Config selected =
                                                    Cms.parseConfig(
                                                            SourceController.fetchImport(a, url),
                                                            url);
                                            String combined =
                                                    com.pilipala.source.Warehouse.retainDirectory(
                                                            selected.raw,
                                                            selected.origin,
                                                            previous.raw,
                                                            raw ->
                                                                    new org.json.JSONObject(
                                                                            Cms.withoutComments(
                                                                                    raw)));
                                            Cms.Config ready =
                                                    Cms.parseConfig(combined, selected.origin);
                                            a.ui.post(
                                                    () -> {
                                                        if (a.isFinishing() || a.config != previous)
                                                            return;
                                                        try {
                                                            SourceController.saveConfig(
                                                                    a, ready, ready.origin);
                                                        } catch (Exception e) {
                                                            a.notice("子倉庫儲存失敗，請重試");
                                                        }
                                                    });
                                        } catch (Exception e) {
                                            a.ui.post(
                                                    () -> {
                                                        if (!a.isFinishing())
                                                            a.notice("子倉庫讀取失敗，已保留原線路");
                                                    });
                                        }
                                    });
                        })
                .show();
    }
}
