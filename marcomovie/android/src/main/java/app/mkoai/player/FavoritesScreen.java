package app.mkoai.player;

import android.view.*;
import android.widget.*;
import java.util.*;

final class FavoritesScreen {
    final MainActivity a;

    FavoritesScreen(MainActivity a) {
        this.a = a;
    }

    void show() {
        a.renderPage("我的收藏", "YOUR COLLECTION");
        List<Favorites.Entry> entries = Favorites.read(a.prefs.getString(Prefs.FAVORITES, "[]"));
        if (entries.isEmpty()) {
            a.paragraph("把喜歡的內容留在這裏。開啟影片詳情，選擇“加入收藏”。");
            return;
        }
        a.paragraph(entries.size() + " 部收藏 · 確認開啟詳情，長按確認或按選單鍵管理");
        float screen = a.windowWidth() / a.getResources().getDisplayMetrics().density;
        int columns = Math.max(4, Math.min(6, a.prefs.getInt(Prefs.HOME_COLUMNS, 6)));
        int width = (int) ((screen - 92) / columns) - 20;
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            Favorites.Entry e = entries.get(i);
            if (i % columns == 0) {
                row = a.row();
                row.setGravity(Gravity.TOP);
                row.setClipChildren(false);
                a.body.addView(row);
            }
            LinearLayout card = a.vertical();
            card.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(8));
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setId(View.generateViewId());
            card.setTag("favorite:" + e.key());
            card.setFocusable(a.isTvDevice());
            card.setFocusableInTouchMode(a.isTvDevice());
            card.setClickable(true);
            ImageView poster = new ImageView(a);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            FrameLayout art = new FrameLayout(a);
            art.setBackground(a.bg(0xff29292e, 10));
            art.setClipToOutline(true);
            art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            // 2.0.40: year bottom-left on the poster. Favorites carry no
            // year, so it is filled from TMDB in the background.
            TextView yearTv = MobileShell.addPosterOverlay(a, art, null, null);
            MobileShell.enrichYearLabel(a, yearTv, e.title);
            card.addView(art, new LinearLayout.LayoutParams(a.dp(width), a.dp(width * 1.5f)));
            a.poster(e.poster, poster);
            TextView title = a.text(e.title, 13, MainActivity.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(0, a.dp(9), 0, 0);
            card.addView(title, new LinearLayout.LayoutParams(-1, a.dp(30)));
            card.setOnClickListener(v -> open(e));
            card.setOnLongClickListener(
                    v -> {
                        manage(e);
                        return true;
                    });
            a.pressFeedback(card);
            card.setOnKeyListener(
                    (v, key, event) -> {
                        if (key == KeyEvent.KEYCODE_MENU) {
                            if (event.getAction() == KeyEvent.ACTION_UP) manage(e);
                            return true;
                        }
                        return false;
                    });
            card.setOnFocusChangeListener(
                    (v, f) -> {
                        FocusMotion.style(
                                a,
                                card,
                                f,
                                0,
                                0xff29292f,
                                16,
                                MainActivity.WHITE,
                                MainActivity.WHITE,
                                true,
                                1.035f);
                    });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            size.setMargins(a.dp(4), a.dp(5), a.dp(4), a.dp(16));
            row.addView(card, size);
            if (i == 0 && a.current != null && a.current.focus == null)
                a.ui.post(card::requestFocus);
        }
        if (row != null)
            for (int i = entries.size() % columns; i > 0 && i < columns; i++)
                row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
    }

    void open(Favorites.Entry e) {
        Cms.Site found = null;
        if (a.config != null) for (Cms.Site s : a.config.sites) if (s.key.equals(e.site)) found = s;
        if (found == null || !a.available(found)) {
            a.notice("這個收藏的來源暫不可用，收藏仍保留。請檢查配置。");
            return;
        }
        Cms.Site selected = found;
        a.openListing(selected, e.video());
    }

    void manage(Favorites.Entry e) {
        new AppDialog.Builder(a)
                .setTitle(e.title)
                .setItems(
                        new String[] {"開啟詳情", "取消收藏"},
                        (d, n) -> {
                            if (n == 0) open(e);
                            else {
                                a.prefs
                                        .edit()
                                        .putString(
                                                Prefs.FAVORITES,
                                                Favorites.toggle(
                                                        a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                        e.site,
                                                        e.video()))
                                        .apply();
                                a.refresh();
                            }
                        })
                .setNegativeButton("返回", null)
                .show();
    }
}
