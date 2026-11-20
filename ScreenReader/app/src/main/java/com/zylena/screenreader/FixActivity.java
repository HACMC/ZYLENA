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
    static final String EXTRA_PAGE = "page", EXTRA_AT = "at", EXTRA_LANGS = "langs";
    private final List<Integer> paraStart = new ArrayList<>(); // where each paragraph begins in text
    private java.util.ArrayList<String> langs;
    private TextView ideas; // other ways to write the word so the voice says it differently
    private int startAt; // where the paragraph being read begins in text
    private android.widget.ScrollView box;
    private EditText find;
    private final List<int[]> hits = new ArrayList<>(); // what the search found: {start, end}
    private int hit = -1;

    private final List<int[]> words = new ArrayList<>(); // {start, end} of each word in text
    private String text = "";
    private int from = -1, to = -1;
    private TextView said;
    private Button mine; // opens the list of fixes made so far, to take one back
    private EditText wrong, right;
    private TextView hints; // real words spelled close to the chosen one: tap one to use it
    private int asked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        java.util.ArrayList<String> page = getIntent().getStringArrayListExtra(EXTRA_PAGE);
        int at = getIntent().getIntExtra(EXTRA_AT, 0);
        StringBuilder all = new StringBuilder();
        langs = getIntent().getStringArrayListExtra(EXTRA_LANGS);
        for (int i = 0; page != null && i < page.size(); i++) {
            paraStart.add(all.length());
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
        // The app's own look: a dark card with a gold hairline, over the page being read.
        android.graphics.drawable.GradientDrawable win = new android.graphics.drawable.GradientDrawable();
        win.setCornerRadius(Theme.dp(this, 20));
        win.setColor(Theme.SURFACE);
        win.setStroke(Theme.dp(this, 1), Theme.LINE_STRONG);
        getWindow().setBackgroundDrawable(win);

        col.addView(Theme.eyebrow(this, "Pronunciation"));
        col.addView(Theme.title(this, "Fix a word", 28));
        col.addView(label("This is the whole page as it is read, opened where the voice was. Tap the word that sounded wrong. "
                + "For a phrase, tap its first word, then its last."));

        LinearLayout search = new LinearLayout(this);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        find = field("Search the page");
        search.addView(find, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button next = Theme.button(this, "Next", false);
        next.setMinHeight(Theme.dp(this, 44));
        next.setOnClickListener(v -> nextHit());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMarginStart(Theme.dp(this, 8));
        search.addView(next, nlp);
        col.addView(search, Theme.spaced(this, 8));
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
        said.setTextColor(Theme.INK);
        said.setTextSize(17);
        said.setTypeface(Theme.sans(this));
        said.setLineSpacing(0, 1.3f);
        said.setMovementMethod(LinkMovementMethod.getInstance());
        said.setHighlightColor(Color.TRANSPARENT);
        box = new ScrollView(this);
        box.addView(said);
        android.graphics.drawable.GradientDrawable sheet = new android.graphics.drawable.GradientDrawable();
        sheet.setCornerRadius(Theme.dp(this, 14));
        sheet.setColor(Theme.SUNK);
        sheet.setStroke(Theme.dp(this, 1), Theme.LINE);
        box.setBackground(sheet);
        int bp = Theme.dp(this, 12);
        box.setPadding(bp, bp, bp, bp);
        box.setClipToPadding(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 230));
        bl.topMargin = Theme.dp(this, 10);
        col.addView(box, bl);

        col.addView(label("Wrong word or phrase"));
        wrong = field("Tap it above, or type it here");
        col.addView(wrong);
        col.addView(label("Say it as"));
        right = field("How it should be said");
        col.addView(right);
        hints = new TextView(this);
        hints.setTextColor(Theme.GOLD_LIGHT);
        hints.setTextSize(15);
        hints.setTypeface(Theme.sans(this));
        hints.setPadding(0, Theme.dp(this, 8), 0, 0);
        hints.setLineSpacing(0, 1.3f);
        hints.setMovementMethod(LinkMovementMethod.getInstance());
        hints.setHighlightColor(Color.TRANSPARENT);
        hints.setVisibility(View.GONE);
        col.addView(hints);
        ideas = new TextView(this);
        ideas.setTextColor(Theme.GOLD_LIGHT);
        ideas.setTextSize(15);
        ideas.setTypeface(Theme.sans(this));
        ideas.setPadding(0, Theme.dp(this, 8), 0, 0);
        ideas.setLineSpacing(0, 1.3f);
        ideas.setMovementMethod(LinkMovementMethod.getInstance());
        ideas.setHighlightColor(Color.TRANSPARENT);
        ideas.setVisibility(View.GONE);
        col.addView(ideas);
        Button hearWord = button("▶  Hear the word", false);
        // As it is read now: with the fixes and respellings already in use.
        hearWord.setOnClickListener(v -> Speaker.get(this).sayNow(TextPrep.withoutHalts(TextPrep.say(wrong.getText().toString(), lang())), lang()));
        Button hearFix = button("▶  Hear my fix", false);
        hearFix.setOnClickListener(v -> {
            if (right.getText().toString().trim().isEmpty()) Toast.makeText(this, "Type how it should be said first, or tap an idea.", Toast.LENGTH_SHORT).show();
            else Speaker.get(this).sayNow(right.getText().toString(), lang());
        });
        col.addView(pair(hearWord, hearFix), Theme.spaced(this, 12));
        col.addView(label("Tip: write it the way it sounds, like \"Zeleena\" or \"pro-pe-ta\". It changes how the word is said everywhere."));

        Button save = button("Say it this way", true);
        save.setOnClickListener(v -> save(false));
        Button never = button("Never read this", false);
        never.setOnClickListener(v -> save(true));
        Button asIs = button("Say it as written", false);
        asIs.setOnClickListener(v -> {
            right.setText(wrong.getText().toString());
            save(false);
        });
        mine = button("My fixes", false);
        mine.setOnClickListener(v -> showMine());
        Button cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> finish());
        col.addView(save, Theme.spaced(this, 14));
        col.addView(pair(asIs, never), Theme.spaced(this, 8));
        col.addView(pair(mine, cancel), Theme.spaced(this, 8));
        countMine();

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
        TextView t = Theme.body(this, s, Theme.MUTED, 13);
        t.setPadding(0, Theme.dp(this, 12), 0, Theme.dp(this, 4));
        return t;
    }

    /** A text box in the app's look: a rounded dark field with a gold hairline. */
    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Theme.INK);
        e.setHintTextColor(Theme.FAINT);
        e.setTextSize(16);
        e.setTypeface(Theme.sans(this));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Theme.dp(this, 12));
        bg.setColor(Theme.SURFACE_2);
        bg.setStroke(Theme.dp(this, 1), Theme.LINE_STRONG);
        e.setBackground(bg);
        int h = Theme.dp(this, 14), v = Theme.dp(this, 11);
        e.setPadding(h, v, h, v);
        return e;
    }

    /** primary: the main action, in gold; the others dark with a gold hairline. */
    private Button button(String s, boolean primary) {
        Button b = Theme.button(this, s, primary);
        b.setMinHeight(Theme.dp(this, primary ? 52 : 46));
        b.setTextSize(primary ? 16 : 14);
        return b;
    }

    /** Two buttons side by side, each half the width. */
    private LinearLayout pair(Button a, Button b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        la.setMarginEnd(Theme.dp(this, 8));
        row.addView(a, la);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
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
        // A word fixed before: say so, so it isn't fixed twice.
        for (String[] p : TextPrep.parseSay(Prefs.sayList(this))) {
            if (p[0].equalsIgnoreCase(phrase)) {
                right.setText(p[1]);
                Toast.makeText(this, "\"" + p[0] + "\" is now said \"" + p[1] + "\". Change it here, or take it back in My fixes.", Toast.LENGTH_LONG).show();
            }
        }
        show();
        right.requestFocus();
        suggest(from == to ? phrase : "");
        sayIdeas(from == to ? phrase : "");
    }

    /** The language of the paragraph the chosen word is in (the voice that reads it). */
    private String lang() {
        int off = from >= 0 ? words.get(from)[0] : startAt, p = 0;
        for (int i = 0; i < paraStart.size(); i++) if (paraStart.get(i) <= off) p = i;
        return langs != null && p < langs.size() ? langs.get(p) : TextPrep.FIL;
    }

    /** Other ways to write the word so the voice says it another way: tap one to try it, then Hear my fix. */
    private void sayIdeas(String word) {
        List<String> got = word.isEmpty() ? new ArrayList<>() : TextPrep.sayIdeas(word, TextPrep.FIL.equals(lang()));
        if (got.isEmpty()) {
            ideas.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder("Ways to say it:  ");
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
                    Speaker.get(FixActivity.this).sayNow(pick, lang()); // heard at once
                }

                @Override
                public void updateDrawState(TextPaint p) {
                    p.setUnderlineText(true);
                }
            }, at.get(i)[0], at.get(i)[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ideas.setText(sp);
        ideas.setVisibility(View.VISIBLE);
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

    /** The word just fixed: reading goes on from it. */
    private String fixed;

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
            // A newer fix for the same word takes the place of the older one. Changing a fixed word back
            // ("AIR-un" to "Aaron" after "Aaron" was made "AIR-un") takes that fix away instead of adding another.
            StringBuilder keep = new StringBuilder();
            boolean undone = false;
            for (String[] p : TextPrep.parseSay(say)) {
                if (p[0].equalsIgnoreCase(r) && p[1].equalsIgnoreCase(w)) undone = true;
                else if (!p[0].equalsIgnoreCase(w)) keep.append(p[0]).append(" = ").append(p[1]).append("\n");
            }
            say = undone ? keep.toString().trim() : keep + w + " = " + r;
        }
        Prefs.setLists(this, say, skip);
        fixed = never ? null : w;
        Logs.d(this, never ? "fix: never read \"" + w + "\"" : "fix: \"" + w + "\" is said \"" + r + "\"");
        Toast.makeText(this, never ? "\"" + w + "\" won't be read any more." : "From now on \"" + w + "\" is said \"" + r + "\".", Toast.LENGTH_LONG).show();
        finish();
    }

    private void countMine() {
        int n = TextPrep.parseSay(Prefs.sayList(this)).size() + TextPrep.parseSkip(Prefs.skipList(this)).size();
        mine.setText(n == 0 ? "My fixes (none yet)" : "My fixes (" + n + ")");
    }

    /** The fixes made so far: tap one to take it back. */
    private void showMine() {
        final List<String[]> say = TextPrep.parseSay(Prefs.sayList(this));
        final List<String> skip = TextPrep.parseSkip(Prefs.skipList(this));
        if (say.isEmpty() && skip.isEmpty()) {
            Toast.makeText(this, "No fixes yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[say.size() + skip.size()];
        for (int i = 0; i < say.size(); i++) items[i] = "\"" + say.get(i)[0] + "\" is said \"" + say.get(i)[1] + "\"";
        for (int i = 0; i < skip.size(); i++) items[say.size() + i] = "\"" + skip.get(i) + "\" is never read";
        new android.app.AlertDialog.Builder(this)
                .setTitle("My fixes: tap one to remove it")
                .setItems(items, (d, which) -> {
                    StringBuilder a = new StringBuilder(), b = new StringBuilder();
                    for (int i = 0; i < say.size(); i++) if (i != which) a.append(say.get(i)[0]).append(" = ").append(say.get(i)[1]).append("\n");
                    for (int i = 0; i < skip.size(); i++) if (say.size() + i != which) b.append(skip.get(i)).append("\n");
                    Prefs.setLists(this, a.toString().trim(), b.toString().trim());
                    Logs.d(this, "fix removed: " + items[which]);
                    Toast.makeText(this, "Removed: " + items[which], Toast.LENGTH_LONG).show();
                    countMine();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Reading goes on from the word just fixed, so the fix is heard right away and nothing before it is read again
        // (from the paragraph it stopped at when nothing was fixed).
        if (isFinishing() && Speaker.get(this).isPaused()) Speaker.get(this).resumeFrom(fixed);
    }
}
