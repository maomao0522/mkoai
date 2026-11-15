package app.mkoai.player;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Shared app-owned dialog chrome for lists, confirmations, text and forms. */
final class AppDialog extends Dialog {
    final MainActivity a;
    final Map<Integer, Button> actions = new HashMap<>();
    final View anchor;
    final Builder spec;

    AppDialog(Builder b) {
        super(b.a);
        a = b.a;
        spec = b;
        anchor = a.getCurrentFocus();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    Button getButton(int which) {
        return actions.get(which);
    }

    void style(Button button) {
        button.setBackground(a.bg(0xff33333b, 18));
        button.setOnFocusChangeListener(
                (v, focused) -> {
                    FocusMotion.style(
                            a,
                            button,
                            focused,
                            0xff33333b,
                            MainActivity.WHITE,
                            18,
                            MainActivity.WHITE,
                            MainActivity.DARK,
                            false,
                            1.015f);
                });
    }

    @Override
    public void show() {
        LinearLayout panel = a.vertical();
        panel.setTag("app-dialog");
        panel.setPadding(a.dp(24), a.dp(22), a.dp(24), a.dp(16));
        a.glass(panel, 28);
        if (spec.title != null) {
            TextView title = a.text(spec.title, 23, MainActivity.WHITE);
            title.setTypeface(null, Typeface.BOLD);
            panel.addView(title);
            a.gap(panel, 14);
        }
        LinearLayout content = a.vertical();
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        if (spec.message != null) {
            TextView message = a.text(spec.message, 15, 0xffd0d0d5);
            message.setLineSpacing(a.dp(4), 1);
            message.setFocusable(true);
            content.addView(message);
            a.gap(content, 10);
        }
        if (spec.view != null) content.addView(spec.view);
        View selected = null;
        if (spec.labels != null)
            for (int i = 0; i < spec.labels.length; i++) {
                final int index = i;
                boolean checked = i == spec.selected;
                Button item =
                        a.button(
                                (checked ? "✓  " : "") + spec.labels[i],
                                "dialog-option:" + i,
                                () -> {
                                    dismiss();
                                    if (spec.pick != null) spec.pick.onClick(this, index);
                                });
                item.setTextSize(15);
                item.setSingleLine(!spec.wrapChoices);
                item.setEllipsize(spec.wrapChoices ? null : android.text.TextUtils.TruncateAt.END);
                if (spec.wrapChoices) item.setMaxLines(Integer.MAX_VALUE);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(-1, spec.wrapChoices ? -2 : a.dp(46));
                lp.bottomMargin = a.dp(6);
                content.addView(item, lp);
                if (checked || selected == null) selected = item;
            }
        int marginH = a.isTvDevice() ? a.dp(72) : a.dp(32);
        int width = Math.min(a.dp(spec.view == null ? 500 : 680), a.windowWidth() - marginH);
        int maxHeight = Math.max(a.dp(140), a.windowHeight() - a.dp(200));
        content.measure(
                View.MeasureSpec.makeMeasureSpec(width - a.dp(48), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ScrollView scroller = new TvScrollView(a);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(content);
        FrameLayout clip =
                new FrameLayout(a) {
                    protected void dispatchDraw(Canvas c) {
                        int save = c.save();
                        c.clipRect(0, 0, getWidth(), getHeight());
                        super.dispatchDraw(c);
                        c.restoreToCount(save);
                    }
                };
        clip.setTag("scroll-viewport-clip");
        clip.setClipChildren(true);
        clip.setClipToPadding(true);
        clip.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(
                clip,
                new LinearLayout.LayoutParams(
                        -1, Math.min(maxHeight, content.getMeasuredHeight())));
        LinearLayout footer = a.row();
        footer.setGravity(Gravity.RIGHT);
        a.gap(panel, 14);
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        boolean compactFooter = spec.buttons.size() >= 3 || !a.isTvDevice();
        for (int code : new int[] {-3, -2, -1})
            if (spec.buttons.containsKey(code)) {
                Button button =
                        a.button(
                                spec.buttons.get(code),
                                "dialog-action:" + code,
                                () -> {
                                    dismiss();
                                    DialogInterface.OnClickListener action =
                                            spec.callbacks.get(code);
                                    if (action != null) action.onClick(this, code);
                                });
                button.setSingleLine(true);
                button.setEllipsize(android.text.TextUtils.TruncateAt.END);
                button.setMinWidth(0);
                button.setMinimumWidth(0);
                if (compactFooter) {
                    button.setTextSize(spec.buttons.size() >= 3 ? 13f : 14f);
                    button.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(42), 1);
                    lp.setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
                    footer.addView(button, lp);
                } else {
                    button.setTextSize(14);
                    a.addButton(footer, button);
                }
                actions.put(code, button);
            }
        for (int i = 0; i < content.getChildCount(); i++)
            if (content.getChildAt(i) instanceof Button) style((Button) content.getChildAt(i));
        for (Button button : actions.values()) style(button);
        setContentView(panel);
        setOnCancelListener(spec.cancel);
        super.show();
        a.appDialog = this;
        Window w = getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.setLayout(width, -2);
        w.setGravity(Gravity.CENTER);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = w.getAttributes();
        attrs.dimAmount = a.isTvDevice() ? .5f : .62f;
        w.setAttributes(attrs);
        if (a.isTvDevice()) {
            w.getDecorView().setSystemUiVisibility(5894);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            // mkoai 2.0.61: initial TV focus. The old code focused the
            // first content child — for a plain message dialog that is
            // the message TextView itself (it is focusable), so OK did
            // nothing until the user pressed DOWN. Now the target is
            // chosen by tvFocusTarget(): a list selection first, then the
            // first field of a form, then the safe (negative) button —
            // unless this dialog opted into the positive button.
            View first = null;
            String target =
                    tvFocusTarget(
                            selected != null,
                            spec.view != null,
                            spec.focusPositive,
                            actions.containsKey(-1),
                            actions.containsKey(-2),
                            actions.containsKey(-3));
            if ("selected".equals(target)) first = selected;
            else if ("view".equals(target)) first = firstFocusable(spec.view);
            else if ("positive".equals(target)) first = actions.get(-1);
            else if ("negative".equals(target)) first = actions.get(-2);
            else if ("neutral".equals(target)) first = actions.get(-3);
            else if (content.getChildCount() > 0) first = content.getChildAt(0);
            if (first == null) {
                // Form with no focusable field: fall back to the safe
                // button, then the first content child.
                if (actions.containsKey(-2)) first = actions.get(-2);
                else if (actions.containsKey(-1)) first = actions.get(-1);
                else if (content.getChildCount() > 0) first = content.getChildAt(0);
            }
            if (first != null) first.requestFocus();
        } else {
            w.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        if (!a.reduced) {
            panel.setAlpha(0);
            panel.setScaleX(.96f);
            panel.setScaleY(.96f);
            panel.animate().alpha(1).scaleX(1).scaleY(1).setDuration(160).start();
        }
    }

    @Override
    public void dismiss() {
        super.dismiss();
        if (a.appDialog == this) a.appDialog = null;
        if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
    }

    /**
     * mkoai 2.0.61: which view gets the initial TV focus in show(), as a
     * pure choice (JVM-testable). Returns "selected" (checked list item),
     * "view" (first field of a form), "positive" / "negative" / "neutral"
     * (footer button), or "content" (first content child — only when the
     * dialog has no list, no form and no buttons). The safe default for a
     * plain message + buttons dialog is the NEGATIVE button: focusing a
     * positive button by default would be wrong for destructive dialogs
     * (e.g. 「清空」 / 「刪除」) — an accidental OK would delete things.
     * A dialog whose positive action IS the reason the user opened it
     * (the install dialogs) opts into "positive" via focusPositive.
     */
    static String tvFocusTarget(
            boolean hasSelected,
            boolean hasView,
            boolean focusPositive,
            boolean hasPositive,
            boolean hasNegative,
            boolean hasNeutral) {
        if (hasSelected) return "selected";
        if (hasView) return "view";
        if (focusPositive && hasPositive) return "positive";
        if (hasNegative) return "negative";
        if (hasPositive) return "positive";
        if (hasNeutral) return "neutral";
        return "content";
    }

    /** First focusable view in this subtree (depth-first), or null. */
    static View firstFocusable(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v.isFocusable()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    static final class Builder {
        final MainActivity a;
        boolean wrapChoices;
        String title, message;
        String[] labels;
        int selected = -1;
        View view;
        // mkoai 2.0.61: initial TV focus goes to the positive button
        // instead of the safe negative default (see focusPositiveButton).
        boolean focusPositive;
        DialogInterface.OnClickListener pick;
        DialogInterface.OnCancelListener cancel;
        final Map<Integer, String> buttons = new HashMap<>();
        final Map<Integer, DialogInterface.OnClickListener> callbacks = new HashMap<>();

        Builder(MainActivity activity) {
            a = activity;
        }

        Builder setTitle(String value) {
            title = value;
            return this;
        }

        Builder setMessage(String value) {
            message = value;
            return this;
        }

        Builder setView(View value) {
            view = value;
            return this;
        }

        Builder wrapChoices() {
            wrapChoices = true;
            return this;
        }

        Builder setItems(String[] values, DialogInterface.OnClickListener action) {
            labels = values;
            pick = action;
            return this;
        }

        Builder setSingleChoiceItems(
                String[] values, int checked, DialogInterface.OnClickListener action) {
            selected = checked;
            return setItems(values, action);
        }

        Builder button(int code, String value, DialogInterface.OnClickListener action) {
            buttons.put(code, value);
            callbacks.put(code, action);
            return this;
        }

        Builder setPositiveButton(String value, DialogInterface.OnClickListener action) {
            return button(-1, value, action);
        }

        /**
         * mkoai 2.0.61: opt this dialog's initial TV focus into the
         * positive button (instead of the safe negative default). Use
         * only when the positive action is the reason the user opened
         * the dialog (e.g. the install permission / install confirm
         * dialogs) — never for destructive positives.
         */
        Builder focusPositiveButton() {
            focusPositive = true;
            return this;
        }

        Builder setNegativeButton(String value, DialogInterface.OnClickListener action) {
            return button(-2, value, action);
        }

        Builder setNeutralButton(String value, DialogInterface.OnClickListener action) {
            return button(-3, value, action);
        }

        Builder setOnCancelListener(DialogInterface.OnCancelListener action) {
            cancel = action;
            return this;
        }

        AppDialog create() {
            return new AppDialog(this);
        }

        AppDialog show() {
            AppDialog dialog = create();
            dialog.show();
            return dialog;
        }
    }
}
