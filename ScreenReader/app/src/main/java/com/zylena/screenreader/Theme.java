package com.zylena.screenreader;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ZYLENA look, shared with the Task Organizer: near-black with wine glows, champagne gold,
 * cream ink, Cormorant Garamond headings and Manrope body text, thin gold hairlines.
 */
final class Theme {
    static final int BG = Color.parseColor("#0D0B0C");
    static final int SURFACE = Color.parseColor("#161113");
    static final int SURFACE_2 = Color.parseColor("#1C1517");
    static final int SUNK = Color.parseColor("#110D0E");
    static final int INK = Color.parseColor("#F2EADB");
    static final int MUTED = Color.parseColor("#B9AA9F");
    static final int FAINT = Color.parseColor("#7D6F68");
    static final int GOLD = Color.parseColor("#D6AD55");
    static final int GOLD_LIGHT = Color.parseColor("#F0D690");
    static final int WINE = Color.parseColor("#3B161F");
    static final int WINE_LIGHT = Color.parseColor("#6A2638");
    static final int DANGER = Color.parseColor("#E5737B");
    static final int DONE = Color.parseColor("#A9C4A0");
    static final int LINE = Color.argb(46, 214, 173, 85);         // gold at 18%
    static final int LINE_STRONG = Color.argb(107, 214, 173, 85); // gold at 42%

    private static Typeface serif, serifItalic, sans, sansBold;

    private Theme() {
    }

    private static Typeface font(Context ctx, String file, int weight) {
        try {
            return new Typeface.Builder(ctx.getAssets(), "fonts/" + file)
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    static Typeface serif(Context ctx) {
        if (serif == null) serif = font(ctx, "cormorant.ttf", 600);
        return serif;
    }

    /** Cormorant has no italic file; a synthetic slant still reads as the Organizer's italic heads. */
    static Typeface serifItalic(Context ctx) {
        if (serifItalic == null) serifItalic = Typeface.create(serif(ctx), Typeface.ITALIC);
        return serifItalic;
    }

    static Typeface sans(Context ctx) {
        if (sans == null) sans = font(ctx, "manrope.ttf", 450);
        return sans;
    }

    static Typeface sansBold(Context ctx) {
        if (sansBold == null) sansBold = font(ctx, "manrope.ttf", 700);
        return sansBold;
    }

    static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    /** Dark window with a wine glow top-right and a faint gold glow bottom-left, like the Organizer. */
    static void applyWindow(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        a.getWindow().setBackgroundDrawable(background());
    }

    static Drawable background() {
        GradientDrawable base = new GradientDrawable();
        base.setColor(BG);

        GradientDrawable wine = new GradientDrawable();
        wine.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        wine.setColors(new int[]{Color.argb(110, 106, 38, 56), Color.TRANSPARENT});
        wine.setGradientCenter(0.9f, 0.04f);
        wine.setGradientRadius(1400);

        GradientDrawable gold = new GradientDrawable();
        gold.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gold.setColors(new int[]{Color.argb(26, 214, 173, 85), Color.TRANSPARENT});
        gold.setGradientCenter(0.02f, 0.98f);
        gold.setGradientRadius(1300);

        GradientDrawable vignette = new GradientDrawable();
        vignette.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        vignette.setColors(new int[]{Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 6, 5, 5)});
        vignette.setGradientCenter(0.5f, 0.4f);
        vignette.setGradientRadius(2200);

        return new LayerDrawable(new Drawable[]{base, wine, gold, vignette});
    }

    private static GradientDrawable rounded(Context ctx, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    /** Raised panel with a gold hairline. */
    static Drawable card(Context ctx) {
        GradientDrawable d = rounded(ctx, 14);
        d.setOrientation(GradientDrawable.Orientation.TL_BR);
        d.setColors(new int[]{Color.argb(242, 28, 21, 23), Color.argb(242, 17, 13, 14)});
        d.setStroke(dp(ctx, 1), LINE_STRONG);
        return d;
    }

    static LinearLayout cardLayout(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(card(ctx));
        int p = dp(ctx, 18);
        l.setPadding(p, p, p, p);
        return l;
    }

    private static Drawable ripple(Drawable content, Context ctx, float radiusDp) {
        GradientDrawable mask = rounded(ctx, radiusDp);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 240, 214, 144)), content, mask);
    }

    /** Solid gold gradient button with dark ink: the main action. */
    static void primary(Button b) {
        Context ctx = b.getContext();
        GradientDrawable d = rounded(ctx, 12);
        d.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        d.setColors(new int[]{GOLD_LIGHT, GOLD});
        b.setBackground(ripple(d, ctx, 12));
        b.setTextColor(BG);
        baseButton(b, true);
    }

    /** Dark button with a gold hairline. */
    static void secondary(Button b) {
        Context ctx = b.getContext();
        GradientDrawable d = rounded(ctx, 12);
        d.setColor(SURFACE_2);
        d.setStroke(dp(ctx, 1), LINE_STRONG);
        b.setBackground(ripple(d, ctx, 12));
        b.setTextColor(INK);
        baseButton(b, false);
    }

    /** Wine button for "turn off" style actions. */
    static void wine(Button b) {
        Context ctx = b.getContext();
        GradientDrawable d = rounded(ctx, 12);
        d.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        d.setColors(new int[]{WINE_LIGHT, WINE});
        d.setStroke(dp(ctx, 1), LINE_STRONG);
        b.setBackground(ripple(d, ctx, 12));
        b.setTextColor(GOLD_LIGHT);
        baseButton(b, true);
    }

    private static void baseButton(Button b, boolean bold) {
        Context ctx = b.getContext();
        b.setAllCaps(false);
        b.setTypeface(bold ? sansBold(ctx) : sans(ctx));
        b.setTextSize(15);
        b.setLetterSpacing(0.02f);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(ctx, 52));
        b.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
    }

    static Button button(Context ctx, String text, boolean primary) {
        Button b = new Button(ctx);
        b.setText(text);
        if (primary) primary(b);
        else secondary(b);
        return b;
    }

    /** Small spaced-out gold capitals, like the Organizer's eyebrow labels. */
    static TextView eyebrow(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text.toUpperCase());
        t.setTextColor(GOLD);
        t.setTextSize(10);
        t.setTypeface(sansBold(ctx));
        t.setLetterSpacing(0.42f);
        return t;
    }

    /** Large serif title with a cream-to-gold sheen. */
    static TextView title(Context ctx, String text, float sizeSp) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTypeface(serif(ctx));
        t.setTextColor(INK);
        t.addOnLayoutChangeListener((v, l, top, r, bottom, ol, ot, or, ob) -> {
            int w = r - l;
            if (w <= 0) return;
            t.getPaint().setShader(new LinearGradient(0, 0, w, 0,
                    new int[]{INK, INK, GOLD_LIGHT, GOLD}, new float[]{0f, 0.3f, 0.75f, 1f}, Shader.TileMode.CLAMP));
            t.invalidate();
        });
        return t;
    }

    static TextView body(Context ctx, String text, int color, float sizeSp) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setTypeface(sans(ctx));
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    /** Thin gold rule that fades out at both ends. */
    static View divider(Context ctx) {
        View v = new View(ctx);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, LINE_STRONG, Color.TRANSPARENT});
        v.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.setMargins(0, dp(ctx, 14), 0, dp(ctx, 14));
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * A setting row: name and explanation on the left, a gold "ON" / outlined "OFF" pill on the right.
     * The whole row is tappable.
     */
    static final class Toggle {
        final LinearLayout row;
        final TextView name, hint, pill;

        Toggle(Context ctx, String nameText) {
            row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = dp(ctx, 4);
            row.setPadding(0, p * 2, 0, p * 2);
            GradientDrawable mask = rounded(ctx, 10);
            mask.setColor(Color.WHITE);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 240, 214, 144)), null, mask));

            LinearLayout text = new LinearLayout(ctx);
            text.setOrientation(LinearLayout.VERTICAL);
            name = new TextView(ctx);
            name.setText(nameText);
            name.setTextColor(INK);
            name.setTextSize(19);
            name.setTypeface(serif(ctx));
            hint = body(ctx, "", MUTED, 12.5f);
            text.addView(name);
            text.addView(hint);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            pill = new TextView(ctx);
            pill.setTypeface(sansBold(ctx));
            pill.setTextSize(11);
            pill.setLetterSpacing(0.2f);
            pill.setGravity(Gravity.CENTER);
            pill.setMinWidth(dp(ctx, 64));
            pill.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(ctx, 12));
            row.addView(pill, lp);
        }

        /** state: "ON" (gold), "OFF" (outlined), or any other label (wine, e.g. "SET UP"). */
        void set(String state, String hintText) {
            Context ctx = row.getContext();
            hint.setText(hintText);
            hint.setVisibility(hintText == null || hintText.isEmpty() ? View.GONE : View.VISIBLE);
            pill.setText(state);
            GradientDrawable d = rounded(ctx, 99);
            if ("ON".equals(state)) {
                d.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
                d.setColors(new int[]{GOLD_LIGHT, GOLD});
                pill.setTextColor(BG);
            } else if ("OFF".equals(state)) {
                d.setColor(Color.TRANSPARENT);
                d.setStroke(dp(ctx, 1), LINE_STRONG);
                pill.setTextColor(FAINT);
            } else {
                d.setColor(WINE);
                d.setStroke(dp(ctx, 1), WINE_LIGHT);
                pill.setTextColor(GOLD_LIGHT);
            }
            pill.setBackground(d);
        }
    }

    static LinearLayout.LayoutParams spaced(Context ctx, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, topDp);
        return lp;
    }
}
