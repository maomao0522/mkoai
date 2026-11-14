package app.mkoai.player;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Dedicated global search page with a local remote-control keyboard. */
public final class GlobalSearchScreen implements AutoCloseable {
    final MainActivity a;
    final String initial;
    PinyinKeyboardModel keyboard;
    EditText input;
    TextView composition, status;
    boolean syncingInput, imeActive;
    LinearLayout candidates, keys, resultBody, sourceFilters;
    SearchCoordinator coordinator, retryCoordinator;
    List<String> searchVariants = Collections.emptyList();
    int sourceCount, columns = 4;
    Button retrySource;
    boolean closed;
    int pageTicket, searchSerial, candidatePage, finished, failed, timedOut, busy, matched;
    String activeFilter = "", submitted = "", edited = "";
    Runnable pendingSearch, pendingRender;
    java.util.concurrent.Future<?> expansion;
    final Map<String, Button> filterButtons = new LinkedHashMap<>();
    final Set<String> displayed = new LinkedHashSet<>();
    /** mkoai: normalized titles already shown as a card (one card per title). */
    final Set<String> displayedTitles = new LinkedHashSet<>();
    LinearLayout resultGrid, lastRow;
    TextView emptyResult, resultHint;
    Button moreResults;
    int shownLimit = 24, renderPasses;
    boolean focusNewResults, restoringResults;
    // mkoai 2.0.47: last flood-suppression state seen by renderResults.
    // When it flips (an exact match arrives after flood was already
    // shown), the grid is rebuilt so the flood cards disappear.
    boolean lastFloodActive;
    ScrollView resultScroll;
    int savedResultScroll;
    long resultTime;
    Cms.Config resultConfig;
    final LinkedHashMap<String, SourceResult> results = new LinkedHashMap<>();
    final Map<String, LinearLayout> posterCards = new HashMap<>();
    final Map<PinyinKeyboardModel.Mode, Button> modeButtons = new LinkedHashMap<>();

    static String staticSubmitted = "";
    static Cms.Config staticConfig;
    static long staticTime;
    static int staticMatched;
    static final LinkedHashMap<String, SourceResult> staticResults = new LinkedHashMap<>();

    // mkoai (item 5): hot-search titles for the empty search page
    // (Douban hot, fetched once on a background thread). null = not
    // loaded yet; empty list = fetch failed, don't retry in a loop.
    static List<String> hotSearchCache = null;
    static boolean hotSearchLoading = false;

    static final class SourceResult {
        final Cms.Site site;
        final List<Cms.Video> videos;
        final SearchCoordinator.Outcome outcome;

        SourceResult(Cms.Site site, List<Cms.Video> videos, SearchCoordinator.Outcome outcome) {
            this.site = site;
            this.videos = videos;
            this.outcome = outcome;
        }
    }

    public GlobalSearchScreen(MainActivity activity, String initialKeyword) {
        a = activity;
        initial = initialKeyword == null ? "" : initialKeyword;
    }

    public void show() {
        String restored = keyboard == null ? initial : keyboard.query();
        if (results.isEmpty()
                && !staticResults.isEmpty()
                && staticConfig == a.config
                && System.currentTimeMillis() - staticTime < 7200000L
                && (restored.trim().isEmpty() || restored.trim().equals(staticSubmitted))) {
            restored = staticSubmitted;
            submitted = staticSubmitted;
            resultConfig = staticConfig;
            resultTime = staticTime;
            matched = staticMatched;
            results.putAll(staticResults);
        }
        boolean searchAgain = !submitted.isEmpty() || !initial.trim().isEmpty();
        boolean reuse =
                !results.isEmpty()
                        && submitted.equals(restored.trim())
                        && resultConfig == a.config
                        && System.currentTimeMillis() - resultTime < 7200000L;
        a.renderPage("", "");
        closed = false;
        edited = "";
        syncingInput = false;
        imeActive = false;
        filterButtons.clear();
        displayed.clear();
        posterCards.clear();
        modeButtons.clear();
        resultGrid = null;
        lastRow = null;
        emptyResult = null;
        moreResults = null;
        pageTicket = a.generation;
        a.root.setBackgroundColor(0xff101013);
        if (a.root.getChildAt(0) instanceof ImageLoader.Atmosphere) a.root.removeViewAt(0);
        a.body.setPadding(a.dp(32), a.dp(14), a.dp(32), a.dp(16));
        try {
            keyboard = new PinyinKeyboardModel(a.getAssets().open("search/pinyin-characters.tsv"));
        } catch (Exception missing) {
            a.paragraph("內建拼音字型檔無法讀取，請重新安裝完整版本。");
            return;
        }
        keyboard.set(restored);
        LinearLayout split = a.row();
        split.setGravity(Gravity.TOP);
        int height =
                Math.max(
                        260,
                        Math.round(a.windowHeight() / a.getResources().getDisplayMetrics().density)
                                - 30);
        a.body.addView(split, new LinearLayout.LayoutParams(-1, a.dp(height)));
        LinearLayout left = a.vertical();
        left.setPadding(a.dp(4), a.dp(4), a.dp(24), a.dp(8));
        ScrollView keyboardScroll = new TvScrollView(a);
        keyboardScroll.setTag("search-keyboard-scroll");
        keyboardScroll.setVerticalScrollBarEnabled(false);
        keyboardScroll.setClipChildren(false);
        keyboardScroll.setClipToPadding(false);
        keyboardScroll.addView(left, new ScrollView.LayoutParams(-1, -2));
        split.addView(keyboardScroll, new LinearLayout.LayoutParams(a.dp(282), -1));
        columns =
                TvLayoutPolicy.searchColumns(
                        a.windowWidth() / a.getResources().getDisplayMetrics().density - 346);
        TextView heading = a.text("搜尋所有片源", 24, MainActivity.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        left.addView(heading);
        a.gap(left, 12);
        input =
                new EditText(a) {
                    @Override
                    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                        if (keyCode == KeyEvent.KEYCODE_BACK
                                && event.getAction() == KeyEvent.ACTION_UP
                                && imeActive) {
                            imeActive = false;
                            setCursorVisible(false);
                            setShowSoftInputOnFocus(false);
                        }
                        return super.onKeyPreIme(keyCode, event);
                    }

                    @Override
                    public boolean onTouchEvent(MotionEvent event) {
                        boolean hadFocus = hasFocus();
                        boolean handled = super.onTouchEvent(event);
                        if (event.getAction() == MotionEvent.ACTION_UP && !hadFocus && hasFocus()) {
                            handleInputClick();
                        }
                        return handled;
                    }

                    @Override
                    public boolean onKeyDown(int keyCode, KeyEvent event) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                                || keyCode == KeyEvent.KEYCODE_ENTER
                                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                            if (event.getRepeatCount() == 0) {
                                FocusMotion.key(a, this, keyCode, event);
                                performClick();
                            }
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                                || (!imeActive
                                        && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT))) {
                            return false;
                        }
                        return super.onKeyDown(keyCode, event);
                    }

                    @Override
                    public boolean onKeyUp(int keyCode, KeyEvent event) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                                || keyCode == KeyEvent.KEYCODE_ENTER
                                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                            FocusMotion.key(a, this, keyCode, event);
                            return true;
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                                || (!imeActive
                                        && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT))) {
                            return false;
                        }
                        return super.onKeyUp(keyCode, event);
                    }
                };
        input.setTextSize(18);
        input.setTextColor(MainActivity.WHITE);
        input.setHintTextColor(MainActivity.MUTED);
        input.setHint("輸入片名…（點一下調出輸入法）");
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setIncludeFontPadding(false);
        input.setTag("primary");
        input.setContentDescription("搜尋內容");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        // mkoai 2.0.53: tap the box to use the TV's own system keyboard.
        input.setShowSoftInputOnFocus(true);
        input.setCursorVisible(true);
        input.setEllipsize(android.text.TextUtils.TruncateAt.START);
        input.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10));
        input.setBackground(a.bg(0xff29292e, 10));
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setClickable(true);
        input.setOnFocusChangeListener(
                (v, f) -> {
                    if (!f && imeActive) {
                        imeActive = false;
                        input.setCursorVisible(false);
                        input.setShowSoftInputOnFocus(false);
                        hideSystemIme();
                    }
                    FocusMotion.style(
                            a,
                            input,
                            f,
                            0xff29292e,
                            0xff35353e,
                            10,
                            MainActivity.WHITE,
                            MainActivity.WHITE,
                            true,
                            1.01f);
                });
        input.setOnClickListener(v -> handleInputClick());
        input.setOnEditorActionListener(
                (v, actionId, event) -> {
                    hideSystemIme();
                    submit();
                    return true;
                });
        input.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (syncingInput || keyboard == null) return;
                        keyboard.set(s == null ? "" : s.toString());
                        updateAfterImeInput();
                    }
                });
        input.setOnKeyListener((v, key, event) -> physicalKey(key, event));
        left.addView(input, new LinearLayout.LayoutParams(-1, a.dp(48)));
        composition = a.text("", 12, MainActivity.MUTED);
        composition.setSingleLine(true);
        composition.setPadding(a.dp(2), a.dp(5), 0, 0);
        left.addView(composition, new LinearLayout.LayoutParams(-1, a.dp(25)));
        candidates = a.row();
        left.addView(candidates, new LinearLayout.LayoutParams(-1, a.dp(34)));
        LinearLayout modes = a.row();
        left.addView(modes, new LinearLayout.LayoutParams(-1, a.dp(38)));
        modeButton(modes, "拼音", PinyinKeyboardModel.Mode.PINYIN);
        modeButton(modes, "字母", PinyinKeyboardModel.Mode.LETTERS);
        modeButton(modes, "數字", PinyinKeyboardModel.Mode.NUMBERS);
        keys = a.vertical();
        left.addView(keys);
        a.gap(left, 5);
        LinearLayout editing = a.row();
        left.addView(editing);
        keyButton(
                editing,
                "空格",
                "search-space",
                () -> {
                    keyboard.space();
                    changed();
                },
                1);
        keyButton(
                editing,
                "刪除",
                "search-delete",
                () -> {
                    keyboard.backspace();
                    changed();
                },
                1);
        keyButton(
                editing,
                "清空",
                "search-clear",
                () -> {
                    keyboard.clear();
                    changed();
                },
                1);
        Button submit = a.button("立即搜尋", "search-submit", () -> submit());
        submit.setTextSize(15);
        submit.setMinHeight(a.dp(40));
        submit.setMinimumHeight(a.dp(40));
        submit.setPadding(a.dp(8), a.dp(5), a.dp(8), a.dp(5));
        LinearLayout.LayoutParams submitSize = new LinearLayout.LayoutParams(-1, a.dp(42));
        submitSize.topMargin = a.dp(8);
        left.addView(submit, submitSize);
        LinearLayout right = a.vertical();
        split.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        status = a.text("輸入即搜尋 · 停頓片刻自動更新結果。", 14, MainActivity.MUTED);
        status.setMinHeight(a.dp(30));
        right.addView(status);
        HorizontalScrollView filterScroll = new HorizontalScrollView(a);
        filterScroll.setTag("search-source-scroll");
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setClipChildren(false);
        filterScroll.setClipToPadding(false);
        sourceFilters = a.row();
        sourceFilters.setClipChildren(false);
        sourceFilters.setClipToPadding(false);
        sourceFilters.setPadding(a.dp(4), a.dp(5), a.dp(4), a.dp(5));
        filterScroll.addView(sourceFilters);
        right.addView(filterScroll, new LinearLayout.LayoutParams(-1, a.dp(46)));
        resultScroll = new TvScrollView(a);
        resultScroll.setTag("global-search-results");
        resultScroll.setClipToPadding(false);
        resultBody = a.vertical();
        resultBody.setPadding(a.dp(4), a.dp(8), a.dp(4), a.dp(20));
        resultScroll.addView(resultBody);
        right.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        rebuildKeys();
        // mkoai 2.0.53: the built-in on-screen keyboard is no longer shown;
        // search uses the TV's system keyboard instead. The editing buttons
        // (space/delete/clear) and the submit button stay.
        modes.setVisibility(View.GONE);
        keys.setVisibility(View.GONE);
        candidates.setVisibility(View.GONE);
        composition.setVisibility(View.GONE);
        if (reuse) {
            edited = restored.trim();
            changed();
            resetGrid();
            restoringResults = true;
            renderFilters();
            renderResults();
            status.setText("已恢復上次結果 · " + matched + " 個來源有結果，可點立即搜尋重新整理");
        } else {
            changed();
            showHistory();
            if (searchAgain && !restored.trim().isEmpty()) submit();
        }
    }

    boolean current() {
        return !closed && !a.isFinishing() && pageTicket == a.generation;
    }

    void modeButton(LinearLayout row, String title, PinyinKeyboardModel.Mode mode) {
        Button b =
                keyButton(
                        row,
                        title,
                        "search-mode:" + mode.name(),
                        () -> {
                            keyboard.mode(mode);
                            candidatePage = 0;
                            rebuildKeys();
                            changed();
                        },
                        1);
        modeButtons.put(mode, b);
    }

    Button keyButton(LinearLayout row, String label, String tag, Runnable action, float weight) {
        Button b = a.button(label, tag, action);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setTextSize(14);
        b.setPadding(a.dp(2), 0, a.dp(2), 0);
        b.setBackground(a.bg(0xff29292e, 7));
        b.setOnFocusChangeListener(
                (v, focus) -> {
                    FocusMotion.style(
                            a,
                            b,
                            focus,
                            0xff29292e,
                            MainActivity.WHITE,
                            7,
                            MainActivity.WHITE,
                            MainActivity.DARK,
                            false,
                            1.02f);
                });
        b.setOnKeyListener((v, key, event) -> physicalKey(key, event));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, a.dp(28), weight);
        size.setMargins(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
        row.addView(b, size);
        return b;
    }

    void rebuildKeys() {
        keys.removeAllViews();
        String characters =
                keyboard.mode() == PinyinKeyboardModel.Mode.NUMBERS
                        ? "1234567890-_:.,!?@#&/"
                        : keyboard.mode() == PinyinKeyboardModel.Mode.LETTERS
                                ? "abcdefghijklmnopqrstuvwxyz"
                                : "abcdefghijklmnopqrstuvwxyz'";
        for (int start = 0; start < characters.length(); start += 6) {
            LinearLayout row = a.row();
            keys.addView(row);
            for (int column = 0; column < 6; column++) {
                int index = start + column;
                if (index >= characters.length()) {
                    row.addView(new View(a), new LinearLayout.LayoutParams(0, a.dp(32), 1));
                    continue;
                }
                String letter = characters.substring(index, index + 1);
                keyButton(
                        row,
                        letter.toUpperCase(Locale.ROOT),
                        "search-key:" + letter,
                        () -> {
                            keyboard.input(letter);
                            changed();
                        },
                        1);
            }
        }
        for (Map.Entry<PinyinKeyboardModel.Mode, Button> entry : modeButtons.entrySet())
            entry.getValue()
                    .setText(
                            (entry.getKey() == keyboard.mode() ? "● " : "")
                                    + modeLabel(entry.getKey()));
    }

    String modeLabel(PinyinKeyboardModel.Mode mode) {
        return mode == PinyinKeyboardModel.Mode.PINYIN
                ? "拼音"
                : mode == PinyinKeyboardModel.Mode.LETTERS ? "字母" : "數字";
    }

    void handleInputClick() {
        // mkoai 2.0.53: the built-in on-screen keyboard is gone; tapping the
        // box opens the TV's own system keyboard (the user's own zhuyin IME).
        openSystemIme();
    }

    void openSystemIme() {
        if (input == null || keyboard == null) return;
        imeActive = true;
        if (!keyboard.composition().isEmpty()) {
            keyboard.set(keyboard.query());
        }
        syncingInput = true;
        String currentText = keyboard.query();
        if (!currentText.equals(input.getText().toString())) {
            input.setText(currentText);
        }
        input.setSelection(input.getText().length());
        syncingInput = false;
        input.setShowSoftInputOnFocus(true);
        input.setCursorVisible(true);
        input.requestFocus();
        try {
            a.getWindow()
                    .setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        } catch (Exception ignored) {
        }
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            if (!imm.showSoftInput(
                    input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
            }
        }
        if (composition != null) composition.setText("已調出電視輸入法 · 可直接輸入中文或拼音");
    }

    void hideSystemIme() {
        if (input == null) return;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    boolean physicalKey(int key, KeyEvent event) {
        if (imeActive && a.getCurrentFocus() == input) {
            if (key == KeyEvent.KEYCODE_SEARCH && event.getAction() == KeyEvent.ACTION_DOWN) {
                hideSystemIme();
                submit();
                return true;
            }
            return false;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            View focus = a.getCurrentFocus();
            if (focus != null) FocusMotion.key(a, focus, key, event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DEL) {
            keyboard.backspace();
            changed();
            return true;
        }
        if (key == KeyEvent.KEYCODE_SPACE) {
            keyboard.space();
            changed();
            return true;
        }
        if (key == KeyEvent.KEYCODE_SEARCH) {
            submit();
            return true;
        }
        int unicode = event.getUnicodeChar();
        if (unicode >= 32
                && unicode < 127
                && key != KeyEvent.KEYCODE_DPAD_CENTER
                && key != KeyEvent.KEYCODE_ENTER) {
            keyboard.input(String.valueOf((char) unicode).toLowerCase(Locale.ROOT));
            changed();
            return true;
        }
        return false;
    }

    void changed() {
        if (input == null) return;
        String display =
                keyboard.query().isEmpty()
                        ? ""
                        : keyboard.committed()
                                + (keyboard.composition().isEmpty()
                                        ? ""
                                        : "  " + keyboard.composition());
        syncingInput = true;
        if (!display.equals(input.getText().toString())) {
            input.setText(display);
            input.setSelection(display.length());
        }
        syncingInput = false;
        updateAfterImeInput();
    }

    void updateAfterImeInput() {
        if (composition == null) return;
        composition.setText(
                keyboard.composition().isEmpty()
                        ? (keyboard.mode() == PinyinKeyboardModel.Mode.PINYIN
                                ? "預設識別首拼 / 全拼 · 雙擊輸入框調出電視輸入法"
                                : "直接輸入字母、首字母或數字 · 雙擊輸入框調出輸入法")
                        : "拼音  " + keyboard.composition());
        candidatePage = 0;
        renderCandidates();
        scheduleSearch();
    }

    void renderCandidates() {
        candidates.removeAllViews();
        List<PinyinKeyboardModel.Candidate> values = keyboard.candidates();
        if (values.isEmpty()) {
            TextView hint =
                    a.text(
                            keyboard.composition().isEmpty() ? "" : "繼續輸入拼音，或切到字母搜尋",
                            12,
                            MainActivity.MUTED);
            candidates.addView(hint);
            return;
        }
        int pages = (values.size() + 5) / 6;
        candidatePage = Math.max(0, Math.min(candidatePage, pages - 1));
        final int page = candidatePage;
        keyButton(
                        candidates,
                        "‹",
                        "candidate-previous",
                        () -> {
                            candidatePage = Math.max(0, candidatePage - 1);
                            renderCandidates();
                        },
                        1)
                .setEnabled(page > 0);
        for (int i = page * 6; i < Math.min(values.size(), page * 6 + 6); i++) {
            PinyinKeyboardModel.Candidate candidate = values.get(i);
            keyButton(
                    candidates,
                    candidate.text,
                    "candidate:" + candidate.text,
                    () -> {
                        keyboard.select(candidate);
                        changed();
                        View first = a.root.findViewWithTag("search-key:a");
                        if (first != null) first.requestFocus();
                    },
                    1);
        }
        keyButton(
                        candidates,
                        "›",
                        "candidate-next",
                        () -> {
                            candidatePage = Math.min(pages - 1, candidatePage + 1);
                            renderCandidates();
                        },
                        1)
                .setEnabled(page < pages - 1);
    }

    void scheduleSearch() {
        String query = keyboard.query().trim();
        if (query.equals(edited)) return;
        edited = query;
        if (pendingSearch != null) a.ui.removeCallbacks(pendingSearch);
        pendingSearch = null;
        ++searchSerial;
        restoringResults = false;
        cancelWork();
        if (query.isEmpty()) {
            submitted = "";
            results.clear();
            clearCards();
            filterButtons.clear();
            sourceFilters.removeAllViews();
            resultBody.removeAllViews();
            resultGrid = null;
        }

        if (query.isEmpty()) {
            status.setText("輸入即搜尋 · 支援中文、首拼和全拼");
            showHistory();
            return;
        }
        status.setText("正在輸入… 停頓後自動搜尋");
        pendingSearch =
                () -> {
                    pendingSearch = null;
                    if (current() && query.equals(keyboard.query().trim())) submit(false);
                };
        a.ui.postDelayed(pendingSearch, 650);
    }

    void submit() {
        submit(true);
    }

    void submit(boolean remember) {
        if (!current()) return;
        if (pendingSearch != null) {
            a.ui.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        String query = keyboard.query().trim();
        if (query.isEmpty()) {
            status.setText("先輸入一個片名。也可以搜尋英文、首字母或數字。");
            return;
        }
        cancelWork();
        restoringResults = false;
        submitted = query;
        resultConfig = a.config;
        resultTime = System.currentTimeMillis();
        if (remember) saveHistory(query);
        if (coordinator != null) coordinator.close();
        results.clear();
        clearCards();
        finished = failed = timedOut = busy = matched = 0;
        activeFilter = "";
        lastFloodActive = false; // mkoai 2.0.47: new search, flood state restarts
        filterButtons.clear();
        sourceFilters.removeAllViews();
        shownLimit = 24;
        resetGrid();
        final int serial = ++searchSerial;
        List<Cms.Site> sources = new ArrayList<>();
        if (a.config != null)
            for (Cms.Site site : SourcePreferences.prioritized(a, a.config.sites)) {
                if (!site.searchable || !a.available(site)) continue;
                // mkoai: when the user hides netdisk features, netdisk sources are not
                // searched at all, so their titles never appear in the results.
                if (a.prefs.getBoolean(Prefs.HIDE_NETDISK, false)
                        && SourcePreferences.isNetdiskSite(site)) continue;
                sources.add(site);
            }
        // Only changes scheduling order; every available searchable source participates.
        final String selected = a.prefs.getString(Prefs.SELECTED_SITE, "");
        Collections.sort(
                sources,
                (x, y) -> Boolean.compare(!x.key.equals(selected), !y.key.equals(selected)));
        final int total = sources.size();
        sourceCount = total;
        status.setText(total == 0 ? "沒有可搜尋來源，請先匯入配置並啟用來源擴充套件。" : "正在搜尋全部 " + total + " 個來源…");
        if (total == 0) return;
        expansion =
                a.phoneticWork.submit(
                        () -> {
                            if (a.phonetic == null)
                                try {
                                    a.phoneticReady.await(8, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (InterruptedException cancelled) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            if (Thread.currentThread().isInterrupted()) return;
                            final PhoneticSearchIndex index = a.phonetic;
                            if (index != null && !query.matches("[A-Za-z0-9' -]+"))
                                if (!a.privateMode) index.prefer(query);
                            final List<String> baseVariants =
                                    index == null
                                            ? Collections.singletonList(query)
                                            : index.searchTerms(query);
                            final List<String> variants =
                                    SearchVariants.mergeTerms(query, baseVariants);
                            a.ui.post(
                                    () -> {
                                        if (current() && serial == searchSerial)
                                            startSources(sources, query, variants, serial);
                                    });
                        });
    }

    void startSources(List<Cms.Site> sources, String query, List<String> variants, int serial) {
        final int total = sources.size();
        searchVariants = variants;
        if (variants.size() > 1) {
            String joined =
                    variants.size() >= 2
                            ? variants.get(0) + " / " + variants.get(1)
                            : variants.get(0);
            status.setText("搜尋全部 " + total + " 個來源 · 自動匹配 " + joined);
        }
        // mkoai 2.0.35: default 12 (was 6) — with 23 sources, 6 threads
        // needed ~4 sequential rounds.
        SearchCoordinator.configureThreads(a.prefs.getInt(Prefs.SEARCH_THREADS, 12));
        coordinator =
                new SearchCoordinator(
                        (site, keyword) -> searchSource(site, query, variants),
                        (site, videos, outcome) ->
                                a.ui.post(
                                        () -> {
                                            if (!current() || serial != searchSerial) return;
                                            a.learnTitles(videos);
                                            SourceResult source =
                                                    new SourceResult(site, videos, outcome);
                                            results.put(site.key, source);
                                            finished++;
                                            if (outcome == SearchCoordinator.Outcome.RESULT)
                                                matched++;
                                            if (outcome == SearchCoordinator.Outcome.FAILED)
                                                failed++;
                                            if (outcome == SearchCoordinator.Outcome.TIMEOUT)
                                                timedOut++;
                                            if (outcome == SearchCoordinator.Outcome.BUSY) busy++;
                                            staticSubmitted = submitted;
                                            staticConfig = resultConfig;
                                            staticTime = resultTime;
                                            staticMatched = matched;
                                            staticResults.clear();
                                            staticResults.putAll(results);
                                            updateStatus(total);
                                            scheduleRender();
                                        }),
                        Prefs.clampSearchTimeoutSeconds(a.prefs.getInt(Prefs.SEARCH_TIMEOUT, 20))
                                * 1000L,
                        90000L);
        coordinator.start(sources, query);
    }

    List<Cms.Video> searchSource(Cms.Site site, String query, List<String> variants)
            throws Exception {
        // mkoai 2.0.34: youth mode (default OFF) skips adult-mixed sources
        // entirely and drops adult-marked titles from every source.
        final boolean youthOn = a.prefs.getBoolean(Prefs.YOUTH_MODE, false);
        if (youthOn && SearchVariants.isAdultSource(site)) return new ArrayList<>();
        List<Cms.Video> out =
                SearchVariants.run(
                        query,
                        variants,
                        title ->
                                !query.matches("[A-Za-z0-9' -]+")
                                        || a.phonetic == null
                                        || a.phonetic.matches(title, query),
                        term -> {
                            try {
                                return Cms.listing(a.engine(site).search(term, 1)).videos;
                            } catch (com.pilipala.catpaw.CatPawHost.DrpySourceException error) {
                                if ("drpy_verification_required".equals(error.code))
                                    throw new SearchCoordinator.VerificationRequiredException();
                                throw error;
                            }
                        });
        // mkoai 2.0.34: drop trailer-only and commentary-only entries — they
        // are not the show (same filter as the main search tab).
        if (out != null && !out.isEmpty()) {
            out.removeIf(SearchVariants::isSearchJunk);
            // 2.0.42: youth mode drops adult-marked titles; the 倫理 part
            // follows the separate 「隱藏倫理片」 switch (youth ON + switch ON).
            if (youthOn) {
                boolean hideLunli = SearchVariants.hideLunli(a);
                out.removeIf(v -> SearchVariants.isAdult(v, hideLunli));
            }
        }
        return out;
    }

    void retrySelected() {
        SourceResult selected = results.get(activeFilter);
        if (selected == null || retryCoordinator != null) return;
        final int serial = searchSerial;
        retrySource.setEnabled(false);
        emptyResult.setText("正在重新搜尋此源…");
        retryCoordinator =
                new SearchCoordinator(
                        (site, query) -> searchSource(site, submitted, searchVariants),
                        (site, videos, outcome) ->
                                a.ui.post(
                                        () -> {
                                            if (!current() || serial != searchSerial) return;
                                            if (retryCoordinator != null) {
                                                retryCoordinator.close();
                                                retryCoordinator = null;
                                            }
                                            SourceResult old = results.get(site.key);
                                            if (old != null) {
                                                if (old.outcome == SearchCoordinator.Outcome.RESULT)
                                                    matched--;
                                                if (old.outcome == SearchCoordinator.Outcome.FAILED)
                                                    failed--;
                                                if (old.outcome
                                                        == SearchCoordinator.Outcome.TIMEOUT)
                                                    timedOut--;
                                                if (old.outcome == SearchCoordinator.Outcome.BUSY)
                                                    busy--;
                                            }
                                            results.put(
                                                    site.key,
                                                    new SourceResult(site, videos, outcome));
                                            if (outcome == SearchCoordinator.Outcome.RESULT)
                                                matched++;
                                            if (outcome == SearchCoordinator.Outcome.FAILED)
                                                failed++;
                                            if (outcome == SearchCoordinator.Outcome.TIMEOUT)
                                                timedOut++;
                                            if (outcome == SearchCoordinator.Outcome.BUSY) busy++;
                                            updateStatus(sourceCount);
                                            scheduleRender();
                                        }),
                        Prefs.clampSearchTimeoutSeconds(a.prefs.getInt(Prefs.SEARCH_TIMEOUT, 20))
                                * 1000L,
                        40000L);
        retryCoordinator.start(Collections.singletonList(selected.site), submitted);
    }

    void updateStatus(int total) {
        String text =
                (finished == total ? "搜尋結束" : "搜尋中")
                        + " · "
                        + finished
                        + " / "
                        + total
                        + " 個來源已處理 · "
                        + matched
                        + " 個有結果";
        if (timedOut > 0) text += " · " + timedOut + " 個超時";
        if (failed > 0) text += " · " + failed + " 個暫不可用";
        if (busy > 0) text += " · " + busy + " 個未完成";
        int verification = 0;
        for (SourceResult result : results.values())
            if (result.outcome == SearchCoordinator.Outcome.VERIFICATION_REQUIRED) verification++;
        if (verification > 0) text += " · " + verification + " 個需網站驗證碼";
        status.setText(text);
    }

    void cancelWork() {
        if (retryCoordinator != null) {
            retryCoordinator.close();
            retryCoordinator = null;
        }
        if (expansion != null) {
            expansion.cancel(true);
            expansion = null;
        }
        if (coordinator != null) {
            coordinator.close();
            coordinator = null;
        }
        if (pendingRender != null) {
            a.ui.removeCallbacks(pendingRender);
            pendingRender = null;
        }
    }

    void clearCards() {
        for (LinearLayout card : posterCards.values())
            if (card.getChildCount() > 0) card.getChildAt(0).setTag(null);
        posterCards.clear();
    }

    void scheduleRender() {
        if (pendingRender != null) return;
        pendingRender =
                () -> {
                    pendingRender = null;
                    if (!current()) return;
                    renderFilters();
                    renderResults();
                };
        a.ui.postDelayed(pendingRender, 100);
    }

    void renderFilters() {
        if (!filterButtons.containsKey("")) addFilter("全部", "");
        for (SourceResult source : results.values())
            if (!filterButtons.containsKey(source.site.key))
                addFilter(clean(source.site.name), source.site.key);
    }

    void addFilter(String label, String key) {
        Button b =
                a.button(
                        label,
                        "search-filter:" + key,
                        () -> {
                            activeFilter = key;
                            shownLimit = 24;
                            updateFilterLabels();
                            resetGrid();
                            renderResults();
                        });
        b.setTextSize(12);
        b.setMinWidth(a.dp(64));
        b.setMinimumWidth(a.dp(64));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(a.dp(15), 0, a.dp(15), 0);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(label);
        b.setOnFocusChangeListener((v, focus) -> styleFilter(b, key, focus));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, a.dp(34));
        size.setMargins(a.dp(2), 0, a.dp(8), 0);
        sourceFilters.addView(b, size);
        filterButtons.put(key, b);
        styleFilter(b, key, false);
    }

    void styleFilter(Button b, String key, boolean focused) {
        boolean selected = key.equals(activeFilter);
        b.setSelected(selected);
        b.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        FocusMotion.style(
                a,
                b,
                focused,
                selected ? 0xff465c76 : 0xff272f3a,
                MainActivity.WHITE,
                17,
                selected ? MainActivity.WHITE : 0xffc2cbd7,
                MainActivity.DARK,
                true,
                1.035f);
    }

    void updateFilterLabels() {
        for (Map.Entry<String, Button> entry : filterButtons.entrySet()) {
            Button b = entry.getValue();
            styleFilter(b, entry.getKey(), b.hasFocus());
        }
    }

    void resetGrid() {
        resultBody.removeAllViews();
        displayed.clear();
        displayedTitles.clear();
        lastRow = null;
        resultGrid = a.vertical();
        resultGrid.setClipChildren(false);
        resultBody.addView(resultGrid);
        emptyResult = a.text("等待匹配結果…", 15, MainActivity.MUTED);
        emptyResult.setPadding(a.dp(8), a.dp(24), 0, 0);
        resultBody.addView(emptyResult);
        retrySource = a.navButton("重新搜尋此源", "search-retry-source", this::retrySelected);
        retrySource.setVisibility(View.GONE);
        a.addButton(resultBody, retrySource);
        moreResults =
                a.navButton(
                        "顯示更多結果",
                        "search-more-results",
                        () -> {
                            shownLimit = Math.min(120, shownLimit + 24);
                            focusNewResults = true;
                            renderResults();
                        });
        moreResults.setVisibility(View.GONE);
        a.addButton(resultBody, moreResults);
        resultHint = a.text("全部檢視顯示前 120 條，可切換上方來源檢視更多。", 12, MainActivity.MUTED);
        resultHint.setVisibility(View.GONE);
        resultBody.addView(resultHint);
    }

    void renderResults() {
        if (resultGrid == null) return;
        renderFilters();
        renderPasses++;
        // mkoai 2.0.47: global flood suppression + best-match-first.
        // The exact match and the flood usually live in DIFFERENT
        // sources (e.g. 「艾倫的宇宙」 only in 巨量， while Mkoai1 floods
        // 39 loose 「宇宙」 rows and yzzy dumps 20 unrelated titles), so
        // a per-source filter cannot see it. When ANY arrived result
        // substantively matches the query, the merged ("全部") view shows
        // only substantively-matching titles, and sources are ordered
        // best-match-first so the exact match is on top instead of
        // buried under the flood. A single-source filter view is never
        // filtered (the user explicitly asked to see that source). When
        // the flood state flips — an exact match arrives after flood
        // cards were already shown — the grid is rebuilt so the flood
        // cards disappear instead of staying on screen.
        boolean floodActive = false;
        if (activeFilter.isEmpty() && submitted != null && !submitted.isEmpty()) {
            for (SourceResult source : results.values()) {
                if (source.videos == null) continue;
                for (Cms.Video v : source.videos) {
                    if (v != null && SearchVariants.titleTier(submitted, v.title) > 0) {
                        floodActive = true;
                        break;
                    }
                }
                if (floodActive) break;
            }
        }
        if (floodActive != lastFloodActive) {
            lastFloodActive = floodActive;
            resetGrid();
            if (resultGrid == null) return;
        }
        // Best-match-first source order (stable: ties keep completion order).
        java.util.List<SourceResult> ordered = new java.util.ArrayList<>(results.values());
        if (floodActive) {
            ordered.sort(
                    (x, y) -> Integer.compare(bestTitleTier(y), bestTitleTier(x)));
        }
        int available = 0, added = 0;
        boolean remaining = false;
        for (SourceResult source : ordered)
            if (activeFilter.isEmpty() || source.site.key.equals(activeFilter))
                for (int i = 0;
                        i < Math.min(activeFilter.isEmpty() ? 12 : 24, source.videos.size())
                                && available < 120;
                        i++) {
                    Cms.Video video = source.videos.get(i);
                    // mkoai 2.0.47: in the merged view, drop flood rows
                    // with no substantive title overlap once a real match
                    // exists anywhere (see floodActive above).
                    if (floodActive
                            && (video == null
                                    || SearchVariants.titleTier(submitted, video.title) <= 0)) {
                        continue;
                    }
                    available++;
                    if (available > shownLimit) continue;
                    String key = source.site.key + ":" + video.id;
                    if (displayed.contains(key)) continue;
                    // mkoai: one card per title — skip this video when its normalized
                    // title was already shown as a card from another source.
                    // mkoai 2.0.34: canonicalSearchKey (script-normalized) so a
                    // Simplified title and its Traditional form dedup to one card
                    // (normTitle kept them apart: 急诊室的奇迹 vs 急診室的奇蹟).
                    String titleKey = SearchVariants.canonicalSearchKey(Cms.searchTitle(video));
                    if (!titleKey.isEmpty() && displayedTitles.contains(titleKey)) continue;
                    if (added >= 4) {
                        remaining = true;
                        continue;
                    }
                    if (displayed.size() % columns == 0) {
                        lastRow = a.row();
                        lastRow.setGravity(Gravity.TOP);
                        lastRow.setClipChildren(false);
                        resultGrid.addView(lastRow);
                        for (int column = 0; column < columns; column++) {
                            LinearLayout.LayoutParams blank =
                                    new LinearLayout.LayoutParams(0, 1, 1);
                            blank.setMargins(a.dp(3), a.dp(3), a.dp(8), a.dp(13));
                            lastRow.addView(new View(a), blank);
                        }
                    }
                    LinearLayout card = posterCards.get(key);
                    if (card == null) {
                        card = makeCard(source, video);
                        posterCards.put(key, card);
                    }
                    if (card.getParent() instanceof ViewGroup)
                        ((ViewGroup) card.getParent()).removeView(card);
                    int column = displayed.size() % columns;
                    lastRow.removeViewAt(column);
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    cell.setMargins(a.dp(3), a.dp(3), a.dp(8), a.dp(13));
                    lastRow.addView(card, column, cell);
                    displayed.add(key);
                    if (!titleKey.isEmpty()) displayedTitles.add(titleKey);
                    added++;
                    if (focusNewResults) {
                        card.requestFocus();
                        focusNewResults = false;
                    }
                }
        emptyResult.setVisibility(displayed.isEmpty() ? View.VISIBLE : View.GONE);
        if (displayed.isEmpty()) {
            SourceResult selected = results.get(activeFilter);
            emptyResult.setText(
                    selected == null
                            ? (finished == sourceCount ? "本次未獲得可顯示的結果。可選擇上方來源檢視具體狀態。" : "正在等待其他來源…")
                            : selected.outcome == SearchCoordinator.Outcome.VERIFICATION_REQUIRED
                                    ? "此片源搜尋需要完成網站驗證碼，暫時無法返回結果。可先選擇其他片源。"
                                    : selected.outcome == SearchCoordinator.Outcome.FAILED
                                            ? "此源請求失敗或返回無效響應，並非確認沒有內容。可重試或檢查配置。"
                                            : selected.outcome == SearchCoordinator.Outcome.TIMEOUT
                                                    ? "此源搜尋超時，可單獨重試。"
                                                    : selected.outcome
                                                                    == SearchCoordinator.Outcome
                                                                            .BUSY
                                                            ? "此源尚未完成，可單獨重試。"
                                                            : "此源已響應，但沒有匹配結果。可更換關鍵詞再試。");
        }
        retrySource.setVisibility(
                !activeFilter.isEmpty() && displayed.isEmpty() ? View.VISIBLE : View.GONE);
        retrySource.setEnabled(retryCoordinator == null);
        moreResults.setVisibility(available > shownLimit ? View.VISIBLE : View.GONE);
        resultHint.setVisibility(displayed.size() >= 120 ? View.VISIBLE : View.GONE);
        if (!remaining && restoringResults) {
            restoringResults = false;
            View saved =
                    a.current == null || a.current.focus == null
                            ? null
                            : a.root.findViewWithTag(a.current.focus);
            if (saved != null) saved.requestFocus();
            resultScroll.scrollTo(0, savedResultScroll);
        }
        if (remaining && pendingRender == null) {
            pendingRender =
                    () -> {
                        pendingRender = null;
                        if (current()) renderResults();
                    };
            a.ui.postDelayed(pendingRender, 16);
        }
    }

    /**
     * mkoai 2.0.47: best title-match tier among one source's videos for
     * the submitted query (0 when none matches). Used to order sources
     * best-match-first in the merged view.
     */
    private int bestTitleTier(SourceResult source) {
        int best = 0;
        if (source == null || source.videos == null || submitted == null) return 0;
        for (Cms.Video v : source.videos) {
            if (v == null) continue;
            int tier = SearchVariants.titleTier(submitted, v.title);
            if (tier > best) best = tier;
        }
        return best;
    }

    LinearLayout makeCard(SourceResult source, Cms.Video video) {
        LinearLayout card = a.vertical();
        card.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(8));
        FrameLayout art = new FrameLayout(a);
        art.setBackground(a.bg(0xff25252a, 9));
        art.setClipToOutline(true);
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams posterSize = new LinearLayout.LayoutParams(a.dp(110), a.dp(165));
        posterSize.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(art, posterSize);
        a.poster(video.poster, poster);
        // 2.0.40: year bottom-left on the poster (CMS vod_year / XML year).
        MobileShell.addPosterOverlay(a, art, null, video.year);
        TextView title = a.text(video.title, 14, MainActivity.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(a.dp(2), a.dp(6), 0, 0);
        card.addView(title);
        TextView origin = a.text(clean(source.site.name), 11, MainActivity.MUTED);
        origin.setSingleLine(true);
        origin.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(origin);
        card.setTag("global-result:" + source.site.key + ":" + video.id);
        card.setFocusable(a.isTvDevice());
        card.setFocusableInTouchMode(a.isTvDevice());
        card.setClickable(true);
        a.pressFeedback(card);
        final String query = submitted;
        card.setOnClickListener(
                view -> {
                    // 2.0.40: stop the still-running cross-source search
                    // FIRST — its 12 threads keep fetching/parsing and
                    // starve the detail load of network/CPU, which is
                    // what froze the app when tapping a result mid-search.
                    // cancelWork closes the coordinator; in-flight HTTP
                    // aborts via RequestScope.cancel -> disconnect. The
                    // detail load runs on its own pool and never waits
                    // for the search. (Back restores the results from
                    // staticResults, so cancelling loses nothing.)
                    cancelWork();
                    saveHistory(query);
                    a.openListing(source.site, video);
                });
        card.setOnFocusChangeListener(
                (view, selected) -> {
                    FocusMotion.style(
                            a,
                            card,
                            selected,
                            Color.TRANSPARENT,
                            0xff393940,
                            11,
                            MainActivity.WHITE,
                            MainActivity.WHITE,
                            true,
                            1.035f);
                });
        return card;
    }

    String focusedTag(ViewGroup group) {
        View focus = a.getCurrentFocus();
        if (focus == null || focus.getTag() == null) return null;
        View parent = focus;
        while (parent != null) {
            if (parent == group) return String.valueOf(focus.getTag());
            if (!(parent.getParent() instanceof View)) break;
            parent = (View) parent.getParent();
        }
        return null;
    }

    String clean(String title) {
        return title == null
                ? "來源"
                : title.replaceAll("[\\p{So}\\p{Cs}]", "").replace('┃', ' ').trim();
    }

    void showHistory() {
        resultBody.removeAllViews();
        resultBody.addView(a.text("最近搜尋", 17, MainActivity.WHITE));
        List<String> history = history();
        if (history.isEmpty()) {
            TextView hint =
                    a.text(
                            "支援中文、首拼和全拼，例如 st / santi。\n也可以輸入字母、數字或選擇拼音候選字。",
                            15,
                            MainActivity.MUTED);
            hint.setLineSpacing(a.dp(6), 1);
            hint.setPadding(0, a.dp(18), 0, 0);
            resultBody.addView(hint);
            ensureHotSearches();
            return;
        }
        for (String value : history) {
            Button recent =
                    a.navButton(
                            value,
                            "search-history:" + value,
                            () -> {
                                keyboard.set(value);
                                changed();
                                submit();
                            });
            a.addButton(resultBody, recent);
        }
        Button clear =
                a.navButton(
                        "清除搜尋記錄",
                        "search-clear-history",
                        () -> {
                            a.prefs.edit().remove(Prefs.GLOBAL_SEARCH_HISTORY).apply();
                            showHistory();
                        });
        a.addButton(resultBody, clear);
        ensureHotSearches();
    }

    // mkoai (item 5): "熱門搜尋" section on the empty search page.
    // Douban hot titles, fetched once on a background thread (never on
    // the UI thread) and cached. Tapping one fills the query and runs
    // the search, same as a recent search. Fails silently — if Douban
    // is unreachable the section simply doesn't appear.
    void ensureHotSearches() {
        if (hotSearchCache != null) {
            renderHotSearches();
            return;
        }
        if (hotSearchLoading) return;
        hotSearchLoading = true;
        new Thread(
                        () -> {
                            List<String> titles = new ArrayList<>();
                            try {
                                for (Cms.Video v : DoubanHome.fetchHot()) {
                                    if (v.title != null && !v.title.trim().isEmpty()) {
                                        titles.add(v.title.trim());
                                    }
                                    if (titles.size() >= 10) break;
                                }
                            } catch (Throwable ignored) {
                            }
                            hotSearchCache = titles;
                            hotSearchLoading = false;
                            a.ui.post(
                                    () -> {
                                        if (current()
                                                && keyboard != null
                                                && keyboard.query().trim().isEmpty()) {
                                            renderHotSearches();
                                        }
                                    });
                        })
                .start();
    }

    void renderHotSearches() {
        if (hotSearchCache == null || hotSearchCache.isEmpty()) return;
        TextView hotTitle = a.text("熱門搜尋", 17, MainActivity.WHITE);
        hotTitle.setPadding(0, a.dp(18), 0, a.dp(4));
        resultBody.addView(hotTitle);
        for (String value : hotSearchCache) {
            Button hot =
                    a.navButton(
                            value,
                            "search-hot:" + value,
                            () -> {
                                keyboard.set(value);
                                changed();
                                submit();
                            });
            a.addButton(resultBody, hot);
        }
    }

    List<String> history() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
            for (int i = 0; i < Math.min(8, list.length()); i++) {
                String text = list.optString(i);
                if (!text.isEmpty()) out.add(text);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    void saveHistory(String query) {
        if (a.privateMode) return;
        List<String> previous = history();
        previous.remove(query);
        previous.add(0, query);
        JSONArray list = new JSONArray();
        for (int i = 0; i < Math.min(8, previous.size()); i++) list.put(previous.get(i));
        a.prefs.edit().putString(Prefs.GLOBAL_SEARCH_HISTORY, list.toString()).apply();
    }

    @Override
    public void close() {
        closed = true;
        if (resultScroll != null) savedResultScroll = resultScroll.getScrollY();
        hideSystemIme();
        if (pendingSearch != null) {
            a.ui.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        clearCards();
        searchSerial++;
        cancelWork();
    }
}
