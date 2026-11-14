package com.zylena.screenreader;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * "Fix a word": while reading, shows the words the voice just said. Tap the word (or the first and last word
 * of a phrase) that sounded wrong, type how it should be said, and it is said that way from then on,
 * everywhere; or choose never to read it. The fixes are the same lists as "Say it my way" and "Never read"
 * in Settings, so they can be seen and changed there.
 */
public class FixActivity extends Activity {
    static final String EXTRA_TEXT = "text";

    private final List<int[]> words = new ArrayList<>(); // {start, end} of each word in text
    private String text = "";
    private int from = -1, to = -1;
    private TextView said;
    private EditText wrong, right;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        text = String.valueOf(getIntent().getStringExtra(EXTRA_TEXT)).trim();
        for (int i = 0; i < text.length(); ) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            int s = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            if (i > s) words.add(new int[]{s, i});
        }
        int pad = Theme.dp(this, 18);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(0xFF1B1718);

        TextView title = new TextView(this);
        title.setText("Fix a word");
        title.setTextColor(0xFFF0D690);
        title.setTextSize(20);
        col.addView(title);
        col.addView(label("This is what was just read. Tap the word that sounded wrong. For a phrase, tap its first word, then its last."));

        said = new TextView(this);
        said.setTextColor(Color.WHITE);
        said.setTextSize(17);
        said.setLineSpacing(0, 1.25f);
        said.setMovementMethod(LinkMovementMethod.getInstance());
        said.setHighlightColor(Color.TRANSPARENT);
        ScrollView box = new ScrollView(this);
        box.addView(said);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 170));
        bl.topMargin = Theme.dp(this, 8);
        col.addView(box, bl);

        col.addView(label("Wrong word or phrase"));
        wrong = field("Tap it above, or type it here");
        col.addView(wrong);
        col.addView(label("Say it as"));
        right = field("How it should be said");
        col.addView(right);
        col.addView(label("Tip: write it the way it sounds, like \"Zeleena\" or \"pro-pe-ta\". It changes how the word is said everywhere."));

        Button save = button("Say it this way");
        save.setOnClickListener(v -> save(false));
        Button never = button("Never read this");
        never.setOnClickListener(v -> save(true));
        Button cancel = button("Cancel");
        cancel.setOnClickListener(v -> finish());
        col.addView(save);
        col.addView(never);
        col.addView(cancel);

        ScrollView page = new ScrollView(this);
        page.addView(col);
        setContentView(page);
        getWindow().setLayout(getResources().getDisplayMetrics().widthPixels * 94 / 100, ViewGroup.LayoutParams.WRAP_CONTENT);
        show();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFB9AFA6);
        t.setTextSize(13);
        t.setPadding(0, Theme.dp(this, 12), 0, Theme.dp(this, 2));
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF7D746C);
        return e;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(this, 6);
        b.setLayoutParams(lp);
        return b;
    }

    /** The words, each one tappable; the chosen ones marked. */
    private void show() {
        SpannableString sp = new SpannableString(text);
        for (int i = 0; i < words.size(); i++) {
            final int index = i;
            int[] w = words.get(i);
            sp.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View v) {
                    tap(index);
                }

                @Override
                public void updateDrawState(TextPaint p) {
                    p.setUnderlineText(false); // plain words, not links
                }
            }, w[0], w[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (from >= 0) {
            sp.setSpan(new BackgroundColorSpan(0xFF7A5C12), words.get(from)[0], words.get(to)[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        said.setText(sp);
    }

    /** First tap: that word. Second tap: the phrase from the first word to this one. A third starts again. */
    private void tap(int i) {
        if (from < 0 || from != to) {
            from = to = i;
        } else {
            int a = Math.min(from, i), z = Math.max(from, i);
            from = a;
            to = z;
        }
        String phrase = text.substring(words.get(from)[0], words.get(to)[1])
                .replaceAll("^[^\\p{L}\\d]+|[^\\p{L}\\d]+$", ""); // without the quotation marks and full stops around it
        wrong.setText(phrase);
        show();
        right.requestFocus();
    }

    private void save(boolean never) {
        String w = wrong.getText().toString().trim().replace("\n", " "), r = right.getText().toString().trim().replace("\n", " ");
        if (w.isEmpty()) {
            Toast.makeText(this, "Tap the wrong word first, or type it.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!never && r.isEmpty()) {
            Toast.makeText(this, "Type how it should be said, or tap Never read this.", Toast.LENGTH_LONG).show();
            return;
        }
        if (w.contains("=") || r.contains("=")) {
            Toast.makeText(this, "Leave out the = sign.", Toast.LENGTH_LONG).show();
            return;
        }
        String say = Prefs.sayList(this), skip = Prefs.skipList(this);
        if (never) {
            skip = (skip.trim().isEmpty() ? "" : skip.trim() + "\n") + w;
        } else {
            // A newer fix for the same word takes the place of the older one.
            StringBuilder keep = new StringBuilder();
            for (String[] p : TextPrep.parseSay(say)) {
                if (!p[0].equalsIgnoreCase(w)) keep.append(p[0]).append(" = ").append(p[1]).append("\n");
            }
            say = keep + w + " = " + r;
        }
        Prefs.setLists(this, say, skip);
        Logs.d(this, never ? "fix: never read \"" + w + "\"" : "fix: \"" + w + "\" is said \"" + r + "\"");
        Toast.makeText(this, never ? "\"" + w + "\" won't be read any more." : "From now on \"" + w + "\" is said \"" + r + "\".", Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Reading goes on from the paragraph it stopped at, so the fix is heard right away.
        if (isFinishing()) Speaker.get(this).resume();
    }
}
