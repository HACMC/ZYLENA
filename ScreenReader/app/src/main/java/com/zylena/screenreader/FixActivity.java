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
    static final String EXTRA_PAGE = "page", EXTRA_AT = "at";
    private int startAt; // where the paragraph being read begins in text
    private android.widget.ScrollView box;
    private EditText find;
    private final List<int[]> hits = new ArrayList<>(); // what the search found: {start, end}
    private int hit = -1;

    private final List<int[]> words = new ArrayList<>(); // {start, end} of each word in text
    private String text = "";
    private int from = -1, to = -1;
    private TextView said;
    private EditText wrong, right;
    private TextView hints; // real words spelled close to the chosen one: tap one to use it
    private int asked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        java.util.ArrayList<String> page = getIntent().getStringArrayListExtra(EXTRA_PAGE);
        int at = getIntent().getIntExtra(EXTRA_AT, 0);
        StringBuilder all = new StringBuilder();
        for (int i = 0; page != null && i < page.size(); i++) {
            if (i == at) startAt = all.length();
            all.append(page.get(i).trim()).append("\n\n");
        }
        text = all.toString().trim();
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
        col.addView(label("This is the whole page as it is read, opened where the voice was. Tap the word that sounded wrong. "
                + "For a phrase, tap its first word, then its last."));

        LinearLayout search = new LinearLayout(this);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        find = field("Search the page");
        search.addView(find, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button next = new Button(this);
        next.setText("Next");
        next.setAllCaps(false);
        next.setOnClickListener(v -> nextHit());
        search.addView(next, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(search);
        find.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                search(e.toString());
            }
        });

        said = new TextView(this);
        said.setTextColor(Color.WHITE);
        said.setTextSize(17);
        said.setLineSpacing(0, 1.25f);
        said.setMovementMethod(LinkMovementMethod.getInstance());
        said.setHighlightColor(Color.TRANSPARENT);
        box = new ScrollView(this);
        box.addView(said);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 220));
        bl.topMargin = Theme.dp(this, 8);
        col.addView(box, bl);

        col.addView(label("Wrong word or phrase"));
        wrong = field("Tap it above, or type it here");
        col.addView(wrong);
        col.addView(label("Say it as"));
        right = field("How it should be said");
        col.addView(right);
        hints = new TextView(this);
        hints.setTextColor(0xFFF0D690);
        hints.setTextSize(15);
        hints.setLineSpacing(0, 1.3f);
        hints.setMovementMethod(LinkMovementMethod.getInstance());
        hints.setHighlightColor(Color.TRANSPARENT);
        hints.setVisibility(View.GONE);
        col.addView(hints);
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

        ScrollView scroller = new ScrollView(this);
        scroller.addView(col);
        setContentView(scroller);
        getWindow().setLayout(getResources().getDisplayMetrics().widthPixels * 94 / 100, ViewGroup.LayoutParams.WRAP_CONTENT);
        show();
        scrollTo(startAt);
    }

    /** Brings the text at this place into view. */
    private void scrollTo(int offset) {
        said.post(() -> {
            android.text.Layout l = said.getLayout();
            if (l == null) return;
            int line = l.getLineForOffset(Math.max(0, Math.min(offset, text.length())));
            box.scrollTo(0, Math.max(0, l.getLineTop(line) - Theme.dp(this, 8)));
        });
    }

    /** Marks every place the words are found on the page and shows the first. */
    private void search(String q) {
        hits.clear();
        hit = -1;
        String needle = q.trim().toLowerCase(java.util.Locale.ROOT), hay = text.toLowerCase(java.util.Locale.ROOT);
        if (needle.length() >= 2 && hay.length() == text.length()) {
            for (int i = hay.indexOf(needle); i >= 0 && hits.size() < 200; i = hay.indexOf(needle, i + needle.length())) {
                hits.add(new int[]{i, i + needle.length()});
            }
        }
        show();
        if (!hits.isEmpty()) nextHit();
    }

    private void nextHit() {
        if (hits.isEmpty()) {
            if (find.getText().toString().trim().length() >= 2) Toast.makeText(this, "Not on this page.", Toast.LENGTH_SHORT).show();
            return;
        }
        hit = (hit + 1) % hits.size();
        show();
        scrollTo(hits.get(hit)[0]);
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
        for (int i = 0; i < hits.size(); i++) { // what the search found; the one being shown is brighter
            sp.setSpan(new BackgroundColorSpan(i == hit ? 0xFF2F6B3A : 0xFF24402A), hits.get(i)[0], hits.get(i)[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                .replaceAll("\\s+", " ").replaceAll("^[^\\p{L}\\d]+|[^\\p{L}\\d]+$", ""); // without the quotation marks and full stops around it
        wrong.setText(phrase);
        show();
        right.requestFocus();
        suggest(from == to ? phrase : "");
    }

    /** Looks up real words spelled close to the chosen word (in the built-in Bibles' words) and offers them. */
    private void suggest(String word) {
        final int mine = ++asked;
        hints.setVisibility(View.GONE);
        final Bible.Verses src = Bible.source != null ? Bible.source : new VerseStore(getApplicationContext());
        if (Bible.source == null) Bible.source = src;
        if (word.length() < 3) return;
        new Thread(() -> {
            List<String> near;
            try {
                near = src.near(word);
            } catch (Exception e) {
                return;
            }
            final List<String> got = near;
            runOnUiThread(() -> {
                if (mine != asked || got.isEmpty() || isFinishing()) return;
                StringBuilder sb = new StringBuilder("Did you mean:  ");
                List<int[]> at = new ArrayList<>();
                for (String g : got) {
                    at.add(new int[]{sb.length(), sb.length() + g.length()});
                    sb.append(g).append("    ");
                }
                SpannableString sp = new SpannableString(sb.toString());
                for (int i = 0; i < got.size(); i++) {
                    final String pick = got.get(i);
                    sp.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View v) {
                            right.setText(pick);
                            right.setSelection(pick.length());
                        }

                        @Override
                        public void updateDrawState(TextPaint p) {
                            p.setUnderlineText(true);
                        }
                    }, at.get(i)[0], at.get(i)[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                hints.setText(sp);
                hints.setVisibility(View.VISIBLE);
            });
        }, "zylena-suggest").start();
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
        if (isFinishing() && Speaker.get(this).isPaused()) Speaker.get(this).resume();
    }
}
