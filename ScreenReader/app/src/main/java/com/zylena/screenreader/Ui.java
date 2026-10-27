package com.zylena.screenreader;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Settings-screen building blocks: grouped rows with an icon, a title, a line of help and a switch or value. */
final class Ui {
    private Ui() {
    }

    /** Section label above a group, like the ones in Android's own Settings. */
    static TextView section(Context c, String text) {
        TextView t = Theme.eyebrow(c, text);
        t.setPadding(Theme.dp(c, 6), Theme.dp(c, 22), 0, Theme.dp(c, 8));
        return t;
    }

    /** A rounded card that holds rows, with hairlines between them. */
    static LinearLayout group(Context c) {
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Theme.card(c));
        g.setPadding(0, Theme.dp(c, 4), 0, Theme.dp(c, 4));
        return g;
    }

    static void add(LinearLayout group, View row) {
        Context c = group.getContext();
        if (group.getChildCount() > 0) {
            View line = new View(c);
            line.setBackgroundColor(Theme.LINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMarginStart(Theme.dp(c, 68));
            group.addView(line, lp);
        }
        group.addView(row);
    }

    /** A gold icon on a soft gold tile. */
    static ImageView icon(Context c, int res) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setColorFilter(Theme.GOLD_LIGHT);
        int p = Theme.dp(c, 8);
        i.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Theme.dp(c, 12));
        bg.setColor(Color.argb(38, 214, 173, 85));
        i.setBackground(bg);
        return i;
    }

    static final class Row {
        final LinearLayout view;
        final TextView title, sub, value;
        Switch sw;

        Row(Context c, int iconRes, String titleText, String subText) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(Theme.dp(c, 64));
            int h = Theme.dp(c, 14);
            view.setPadding(h, Theme.dp(c, 10), h, Theme.dp(c, 10));
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 240, 214, 144)), null, mask));

            int s = Theme.dp(c, 40);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.setMarginEnd(Theme.dp(c, 14));
            view.addView(icon(c, iconRes), ilp);

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(c);
            title.setText(titleText);
            title.setTextColor(Theme.INK);
            title.setTextSize(15.5f);
            title.setTypeface(Theme.sansBold(c));
            texts.addView(title);
            sub = Theme.body(c, subText == null ? "" : subText, Theme.MUTED, 12.5f);
            sub.setVisibility(subText == null || subText.isEmpty() ? View.GONE : View.VISIBLE);
            texts.addView(sub);
            view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            value = Theme.body(c, "", Theme.GOLD_LIGHT, 13);
            value.setVisibility(View.GONE);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.setMarginStart(Theme.dp(c, 10));
            view.addView(value, vlp);
        }

        void setSub(String t) {
            sub.setText(t);
            sub.setVisibility(t == null || t.isEmpty() ? View.GONE : View.VISIBLE);
        }

        void setValue(String t) {
            value.setText(t);
            value.setVisibility(t == null || t.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** A row that opens something: title, help, optional value and a chevron. */
    static Row nav(Context c, int iconRes, String title, String sub, Runnable onTap) {
        Row r = new Row(c, iconRes, title, sub);
        ImageView chev = new ImageView(c);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setColorFilter(Theme.FAINT);
        int s = Theme.dp(c, 22);
        r.view.addView(chev, new LinearLayout.LayoutParams(s, s));
        r.view.setOnClickListener(v -> onTap.run());
        return r;
    }

    interface OnSwitch {
        void changed(boolean on);
    }

    /** A row with an on/off switch; tapping anywhere on the row flips it. */
    static Row toggle(Context c, int iconRes, String title, String sub, OnSwitch onChange) {
        Row r = new Row(c, iconRes, title, sub);
        Switch sw = new Switch(c);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{Theme.GOLD_LIGHT, Color.parseColor("#9A8C84")}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{Color.argb(150, 214, 173, 85), Color.argb(70, 185, 170, 159)}));
        sw.setClickable(false); // the row handles taps, so the whole line is a big target
        sw.setFocusable(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Theme.dp(c, 8));
        r.view.addView(sw, lp);
        r.sw = sw;
        r.view.setOnClickListener(v -> {
            sw.setChecked(!sw.isChecked());
            onChange.changed(sw.isChecked());
        });
        return r;
    }

    interface OnSlide {
        void changed(float value);
    }

    /** A row with a slider underneath, for speed and pitch. */
    static LinearLayout slider(Context c, int iconRes, String title, float min, float max, float step, float now,
                               String format, OnSlide onChange) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        Row r = new Row(c, iconRes, title, null);
        r.setValue(String.format(Locale.US, format, now));
        r.view.setMinimumHeight(Theme.dp(c, 52));
        r.view.setPadding(r.view.getPaddingLeft(), r.view.getPaddingTop(), r.view.getPaddingRight(), 0);
        box.addView(r.view);
        SeekBar sb = new SeekBar(c);
        int steps = Math.round((max - min) / step);
        sb.setMax(steps);
        sb.setProgress(Math.round((now - min) / step));
        sb.setProgressTintList(ColorStateList.valueOf(Theme.GOLD));
        sb.setThumbTintList(ColorStateList.valueOf(Theme.GOLD_LIGHT));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.LINE_STRONG));
        sb.setPadding(Theme.dp(c, 70), Theme.dp(c, 4), Theme.dp(c, 24), Theme.dp(c, 14));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                r.setValue(String.format(Locale.US, format, min + p * step));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                onChange.changed(min + s.getProgress() * step);
            }
        });
        box.addView(sb);
        return box;
    }

    /** A square shortcut tile for the home screen grid. */
    static LinearLayout tile(Context c, int iconRes, String title, String sub, Runnable onTap) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(c, 14);
        t.setPadding(p, p, p, p);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(Theme.dp(c, 14));
        mask.setColor(Color.WHITE);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(50, 240, 214, 144)), Theme.card(c), mask));
        int s = Theme.dp(c, 40);
        t.addView(icon(c, iconRes), new LinearLayout.LayoutParams(s, s));
        TextView name = new TextView(c);
        name.setText(title);
        name.setTextColor(Theme.INK);
        name.setTextSize(14.5f);
        name.setTypeface(Theme.sansBold(c));
        name.setPadding(0, Theme.dp(c, 12), 0, 0);
        t.addView(name);
        TextView hint = Theme.body(c, sub, Theme.MUTED, 12);
        hint.setTag("sub");
        t.addView(hint);
        t.setOnClickListener(v -> onTap.run());
        return t;
    }
}
