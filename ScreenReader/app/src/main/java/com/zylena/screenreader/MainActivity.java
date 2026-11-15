package com.zylena.screenreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app screen: a top bar, four tabs (Home, Reading, Voice, Controls) and a bottom navigation bar,
 * laid out like a regular Android settings app.
 */
public class MainActivity extends Activity {
    private static final String SAMPLE = "Hi! I'm Zylena. Kumusta ka? Ready na akong basahin ang screen mo. "
            + "You can read in English, sa Tagalog, o kahit Taglish.";

    private final List<ScrollView> pages = new ArrayList<>();
    private final List<LinearLayout> tabs = new ArrayList<>();
    private int tab;

    // Home
    private TextView statusChip, heroTitle, heroText;
    private Button heroButton;
    private LinearLayout sleepTile, readTile;
    // Rows that show live state
    private Ui.Row nextHowRow, nextAppRow;
    private Ui.Row castRow, castFilRow, castEnRow, moodRow;
    private android.widget.EditText searchBox;
    private ScrollView results;
    private LinearLayout resultsCol;
    private Ui.Row offlineRow, booksRow, toneRow, bibleVoiceRow, povRow, fil2Row, en2Row;
    private boolean packOnPhone;
    private Ui.Row keyRow, aiRow, styleRow, glossRow, netRow, onlineVoiceRow, dataRow, aliveRow, fanRow, tuckRow;
    private Ui.Row floatRow, autoRow, resumeRow, pageRow, nextRow, markRow, timeRow, buttonsRow, refsRow, notifRow, shakeRow;
    private Ui.Row enRow, filRow, appRow, sleepRow, accessRow, sayRow, skipRow, readAppRow;
    private TextView warn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Logs.catchCrashes(this);
        Theme.applyWindow(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(topBar());
        root.addView(searchBar());
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomBar());
        setContentView(root);

        LinearLayout[] cols = {homePage(), readingPage(), voicePage(), controlsPage()};
        for (int i = 0; i < cols.length; i++) {
            content.addView(page(cols[i]));
            tidy(i, cols[i]);
        }
        // What the search finds, shown in place of the pages.
        resultsCol = column();
        results = new ScrollView(this);
        int rp = Theme.dp(this, 16);
        resultsCol.setPadding(rp, Theme.dp(this, 4), rp, Theme.dp(this, 28));
        results.addView(resultsCol);
        results.setVisibility(View.GONE);
        content.addView(results);
        showTab(0);

        if (Prefs.notificationControls(this)) askNotificationPermission();
        Prefs.applyLists(this);
        // Warm up the phone's voices so the pickers have something to show.
        Speaker.get(this).whenSystemReady(this::refresh);
    }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }

    /** Opened from the floating button's Search: the search box is focused and the keyboard comes up. */
    private void focusSearchIfAsked() {
        android.content.Intent i = getIntent();
        if (i == null || !i.getBooleanExtra("focus_search", false) || searchBox == null) return;
        i.removeExtra("focus_search");
        searchBox.post(() -> {
            searchBox.requestFocus();
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        focusSearchIfAsked();
        // Switched on in Accessibility but not running: Android stopped it after a problem and won't start it again
        // by itself (Settings shows "Not working"). Give Android a moment first: after a restart it binds it again.
        getWindow().getDecorView().postDelayed(() -> {
            if (isFinishing() || ReaderService.instance != null || !enabledInSettings()) return;
            boolean crashed = getSharedPreferences("zylena_reader", MODE_PRIVATE).getBoolean("crashed", false);
            getSharedPreferences("zylena_reader", MODE_PRIVATE).edit().putBoolean("crashed", false).apply();
            Logs.d(this, "service on in settings but not running" + (crashed ? " (after a crash)" : ""));
            new AlertDialog.Builder(this)
                    .setTitle("ZYLENA stopped")
                    .setMessage((crashed ? "ZYLENA ran into a problem and Android stopped it" : "Android stopped ZYLENA")
                            + ", so the floating button is gone. To start it again:\n\n"
                            + "1. Tap Open Accessibility.\n2. Tap ZYLENA Screen Reader (it may say \"Not working\").\n"
                            + "3. Turn it off, then on again, and tap Allow.\n4. Come back here."
                            + (crashed ? "\n\nThe problem is in the log (Logs): please share it so it can be fixed." : ""))
                    .setPositiveButton("Open Accessibility", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("Close", null)
                    .show();
        }, 1500);
        // ZYLENA was on and now isn't: most likely the phone stopped it to save battery (Xiaomi, Samsung, ...).
        if (!serviceOn() && Prefs.serviceWasOn(this)) {
            Prefs.setServiceWasOn(this, false);
            Logs.d(this, "service found off: showing how to keep it on");
            showKeepOnGuide(true);
        }
        // Opening ZYLENA always brings the floating button back, however it was put away.
        Prefs.setFloatingButton(this, true);
        applyService();
        refresh();
    }

    @Override
    public void onBackPressed() {
        if (searchBox.getText().length() > 0) searchBox.setText("");
        else if (tab != 0) showTab(0);
        else super.onBackPressed();
    }

    // ------------------------------------------------------------ frame

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Theme.dp(this, 18), Theme.dp(this, 14), Theme.dp(this, 10), Theme.dp(this, 10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        int s = Theme.dp(this, 40);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(s, s);
        llp.setMarginEnd(Theme.dp(this, 12));
        bar.addView(logo, llp);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Theme.title(this, "ZYLENA", 26));
        TextView sub = Theme.eyebrow(this, "Screen Reader");
        sub.setTextSize(9);
        names.addView(sub);
        bar.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView logs = new ImageView(this);
        logs.setImageResource(R.drawable.ic_list);
        logs.setColorFilter(Theme.MUTED);
        int p = Theme.dp(this, 10);
        logs.setPadding(p, p, p, p);
        logs.setContentDescription("View logs");
        logs.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        bar.addView(logs, new LinearLayout.LayoutParams(Theme.dp(this, 44), Theme.dp(this, 44)));
        return bar;
    }

    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Theme.SURFACE);
        bar.setPadding(0, Theme.dp(this, 8), 0, Theme.dp(this, 10));
        String[] names = {"Home", "Reading", "Voice", "Controls"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_book, R.drawable.ic_eq, R.drawable.ic_tune};
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            int ph = Theme.dp(this, 20), pv = Theme.dp(this, 4);
            ic.setPadding(ph, pv, ph, pv);
            t.addView(ic, new LinearLayout.LayoutParams(Theme.dp(this, 64), Theme.dp(this, 32)));
            TextView label = Theme.body(this, names[i], Theme.MUTED, 12);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Theme.dp(this, 4), 0, 0);
            t.addView(label);
            t.setOnClickListener(v -> showTab(which));
            tabs.add(t);
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    private void showTab(int which) {
        tab = which;
        if (results != null) results.setVisibility(View.GONE);
        for (int i = 0; i < pages.size(); i++) pages.get(i).setVisibility(i == which ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabs.size(); i++) {
            LinearLayout t = tabs.get(i);
            ImageView ic = (ImageView) t.getChildAt(0);
            TextView label = (TextView) t.getChildAt(1);
            boolean on = i == which;
            ic.setColorFilter(on ? Theme.BG : Theme.MUTED);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(Theme.dp(this, 16));
            pill.setColor(on ? Theme.GOLD : Color.TRANSPARENT);
            ic.setBackground(pill);
            label.setTextColor(on ? Theme.GOLD_LIGHT : Theme.MUTED);
            label.setTypeface(on ? Theme.sansBold(this) : Theme.sans(this));
        }
    }

    private ScrollView page(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        int pad = Theme.dp(this, 16);
        col.setPadding(pad, Theme.dp(this, 4), pad, Theme.dp(this, 28));
        sv.addView(col);
        pages.add(sv);
        return sv;
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    private TextView pageTitle(String t) {
        TextView v = Theme.title(this, t, 30);
        v.setPadding(Theme.dp(this, 4), Theme.dp(this, 8), 0, 0);
        return v;
    }

    // ------------------------------------------------------------ Home

    private LinearLayout homePage() {
        LinearLayout col = column();

        // Status and main action.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hb = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(255, 74, 28, 40), Color.argb(255, 32, 18, 22), Color.argb(255, 22, 17, 19)});
        hb.setCornerRadius(Theme.dp(this, 20));
        hb.setStroke(Theme.dp(this, 1), Theme.LINE_STRONG);
        hero.setBackground(hb);
        int p = Theme.dp(this, 20);
        hero.setPadding(p, p, p, p);
        statusChip = Theme.body(this, "", Theme.BG, 11.5f);
        statusChip.setTypeface(Theme.sansBold(this));
        statusChip.setPadding(Theme.dp(this, 10), Theme.dp(this, 4), Theme.dp(this, 10), Theme.dp(this, 4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hero.addView(statusChip, clp);
        heroTitle = Theme.title(this, "", 28);
        heroTitle.setPadding(0, Theme.dp(this, 12), 0, 0);
        hero.addView(heroTitle);
        heroText = Theme.body(this, "", Theme.MUTED, 13.5f);
        heroText.setPadding(0, Theme.dp(this, 4), 0, 0);
        hero.addView(heroText);
        heroButton = Theme.button(this, "", true);
        heroButton.setOnClickListener(v -> {
            if (!serviceOn()) showAccessGuide();
            else if (Speaker.get(this).isSpeaking()) Speaker.get(this).stop();
            else Speaker.get(this).speakText(SAMPLE);
            heroButton.postDelayed(this::refresh, 300);
        });
        hero.addView(heroButton, Theme.spaced(this, 16));
        col.addView(hero, Theme.spaced(this, 8));

        // Shortcuts.
        col.addView(Ui.section(this, "Shortcuts"));
        LinearLayout grid = column();
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.addView(Ui.tile(this, R.drawable.ic_copy, "Read copied text", "Copy text anywhere, then tap",
                () -> ReadNowActivity.readClipboard(this)), tileLp(false));
        readTile = Ui.tile(this, R.drawable.ic_book, "Open & read", "", this::readLesson);
        readTile.setOnLongClickListener(v -> {
            pickReadApp();
            return true;
        });
        r1.addView(readTile, tileLp(true));
        sleepTile = Ui.tile(this, R.drawable.ic_timer, "Sleep timer", "", this::pickSleepTimer);
        r2.addView(sleepTile, tileLp(false));
        r2.addView(Ui.tile(this, R.drawable.ic_list, "Logs", "See and share what happened",
                () -> startActivity(new Intent(this, LogActivity.class))), tileLp(true));
        grid.addView(r1);
        grid.addView(r2, Theme.spaced(this, 10));
        col.addView(grid);

        // How it works.
        col.addView(Ui.section(this, "How to use"));
        LinearLayout how = Ui.group(this);
        String[][] steps = {
                {"1", "Turn on ZYLENA in Accessibility", "Tap \"Turn on in Accessibility\" above for the steps, one by one."},
                {"2", "Open any app and tap the gold button", "Tap again to stop. Drag it anywhere; drop it on the \u2715 to hide it. Opening ZYLENA always brings it back. Words inside pictures are read too, in their place."},
                {"3", "Hold the button for shortcuts", "A fan of shortcuts opens (choose them in Controls). All controls in it: pick a line to start from, Back, Pause, Skip, Translate (English read in Tagalog, Tagalog in English; online with internet, which is better, offline without), Button size (tap to change), Scan again, Picture text, Save audio and Settings. To put the button away, drag it onto the \u2715 at the bottom; opening ZYLENA brings it back."}};
        for (String[] st : steps) Ui.add(how, stepRow(st[0], st[1], st[2]));
        col.addView(how);
        return col;
    }

    /** The steps for turning ZYLENA on in Android's Accessibility settings, with buttons that open each screen. */
    private void showAccessGuide() {
        String[][] steps = {
                {"1", "Tap \"Open Accessibility\" below", "Android's Accessibility settings open."},
                {"2", "Find ZYLENA Screen Reader", "It's under \"Downloaded apps\" or \"Installed apps\". On some phones tap \"Installed services\" or \"More downloaded apps\" first. Scroll down if you don't see it."},
                {"3", "Tap ZYLENA Screen Reader", "Then tap the switch at the top (\"Use ZYLENA Screen Reader\") so it turns on."},
                {"4", "Tap Allow", "Android asks if ZYLENA may see and control the screen. That's how it reads the text and turns the pages. Tap Allow."},
                {"5", "Come back to ZYLENA", "Press back until you're here again. The top card says ZYLENA is on and the gold button shows up."},
                {"!", "Switch greyed out or \"Restricted setting\"?", "Android blocks this for apps installed from a file. Tap \"Open App info\" below, tap the \u22EE menu at the top right, tap \"Allow restricted settings\" and confirm. Then do steps 1 to 5 again."}};
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 14);
        list.setPadding(p, p, p, p);
        for (String[] st : steps) list.addView(stepRow(st[0], st[1], st[2]));
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("Turn on ZYLENA")
                .setView(sv)
                .setPositiveButton("Open Accessibility", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNeutralButton("Open App info", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Phones like Xiaomi close apps in the background to save battery, and that turns ZYLENA off with its
     * floating button. These settings stop that.
     */
    private void showKeepOnGuide(boolean stopped) {
        String[][] steps = {
                {"1", "Battery: No restrictions", "Tap \"Battery\" below and allow it. Or in App info tap Battery saver (Xiaomi) or Battery (Samsung) and choose No restrictions / Unrestricted."},
                {"2", "Autostart on (Xiaomi, Oppo, Vivo)", "Tap \"Open App info\" below and turn on Autostart."},
                {"3", "Lock it in recent apps (Xiaomi)", "Open recent apps, press and hold ZYLENA (or pull it down) and tap the lock. Then clearing recent apps leaves it alone."},
                {"4", "Then turn it on again if needed", "If the top card says OFF, tap it and turn ZYLENA on in Accessibility."}};
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 14);
        list.setPadding(p, p, p, p);
        if (stopped) list.addView(Theme.body(this, "ZYLENA was turned off, so the floating button went away. If you didn't turn it off, your phone did, "
                + "to save battery. These settings keep it on:", Theme.MUTED, 14));
        for (String[] st : steps) list.addView(stepRow(st[0], st[1], st[2]));
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("Keep ZYLENA on")
                .setView(sv)
                .setPositiveButton("Battery", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNeutralButton("Open App info", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Close", null)
                .show();
    }

    private LinearLayout.LayoutParams tileLp(boolean right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (right) lp.setMarginStart(Theme.dp(this, 10));
        return lp;
    }

    private View stepRow(String n, String title, String sub) {
        Ui.Row r = new Ui.Row(this, R.drawable.ic_info, title, sub);
        // Swap the icon for the step number.
        TextView num = new TextView(this);
        num.setText(n);
        num.setGravity(Gravity.CENTER);
        num.setTextColor(Theme.BG);
        num.setTypeface(Theme.sansBold(this));
        num.setTextSize(15);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Theme.dp(this, 12));
        d.setColors(new int[]{Theme.GOLD_LIGHT, Theme.GOLD});
        num.setBackground(d);
        View old = r.view.getChildAt(0);
        r.view.removeViewAt(0);
        r.view.addView(num, 0, old.getLayoutParams());
        return r.view;
    }

    // ------------------------------------------------------------ Reading

    private LinearLayout readingPage() {
        LinearLayout col = column();
        col.addView(pageTitle("Reading"));

        col.addView(Ui.section(this, "How it reads"));
        LinearLayout g = Ui.group(this);
        floatRow = Ui.toggle(this, R.drawable.ic_touch, "Floating button", "Tap to read, again to stop. Hold for more. Always back when you open ZYLENA.", on -> {
            Prefs.setFloatingButton(this, on);
            applyService();
        });
        Ui.add(g, floatRow.view);
        tuckRow = Ui.toggle(this, R.drawable.ic_touch, "Tuck into the edge",
                "After 3 seconds untouched the button hides halfway at the edge. Tap it to bring it out.", on -> {
                    Prefs.setTuck(this, on);
                    applyService();
                });
        Ui.add(g, tuckRow.view);
        Ui.add(g, flagRow(R.drawable.ic_touch, "Hide in full screen",
                "The button goes away while a video or a game fills the screen, and comes back after. The app you last read in keeps it, "
                        + "even if it is full screen too (some story apps are). With it hidden, use the Quick Settings tile or the notification controls.",
                "hide_fullscreen", false));
        autoRow = Ui.toggle(this, R.drawable.ic_play, "Read each new screen", "Starts by itself when you open an app",
                on -> Prefs.setAutoRead(this, on));
        Ui.add(g, autoRow.view);
        resumeRow = Ui.toggle(this, R.drawable.ic_bookmark, "Remember where I stopped", "Back on a tab? It continues there",
                on -> Prefs.setResume(this, on));
        Ui.add(g, resumeRow.view);
        pageRow = Ui.toggle(this, R.drawable.ic_scroll, "Read the whole page", "Also the text further down; the page scrolls along",
                on -> Prefs.setWholePage(this, on));
        Ui.add(g, pageRow.view);
        nextRow = Ui.toggle(this, R.drawable.ic_next, "Go on to the next page", "At the very bottom, swipes to the next page (e.g. tomorrow's lesson)",
                on -> Prefs.setNextPage(this, on));
        Ui.add(g, nextRow.view);
        markRow = Ui.toggle(this, R.drawable.ic_frame, "Highlight while reading", "Gold outline around the paragraph",
                on -> Prefs.setHighlight(this, on));
        nextHowRow = Ui.nav(this, R.drawable.ic_next, "How the next page is reached", "", () -> pickNextMode(null));
        Ui.add(g, nextHowRow.view);
        nextAppRow = Ui.nav(this, R.drawable.ic_next, "Next page in the app last read", "", () -> pickNextMode(Prefs.lastApp(this)));
        Ui.add(g, nextAppRow.view);
        Ui.add(g, Ui.nav(this, R.drawable.ic_next, "Words on a Next button", "What the button that goes on may say: Next, Next part, Susunod\u2026",
                this::editNextWords).view);
        Ui.add(g, markRow.view);
        Ui.add(g, flagRow(R.drawable.ic_play, "Start reading right after the scan",
                "On a long page the voice starts at once, while the page is still going back to the top. Off: it waits until the page is at the top.",
                "read_at_once", false));
        Ui.add(g, flagRow(R.drawable.ic_next, "Turn the page early",
                "Shortly before the last paragraph ends, the next page is opened and scanned, so reading goes on without a wait.",
                "early_turn", true));
        Ui.add(g, numSlider(R.drawable.ic_clock, "How early the page is turned", 3f, 20f, 1f, "early_seconds", 9f, "%.0f s"));
        col.addView(g);

        col.addView(Ui.section(this, "Open & read"));
        LinearLayout open = Ui.group(this);
        readAppRow = Ui.nav(this, R.drawable.ic_apps, "App to open", "", this::pickReadApp);
        Ui.add(open, readAppRow.view);
        col.addView(open);

        col.addView(Ui.section(this, "Bible"));
        LinearLayout bible = Ui.group(this);
        refsRow = Ui.nav(this, R.drawable.ic_book, "Bible references", "", this::pickRefMode);
        Ui.add(bible, refsRow.view);
        booksRow = Ui.toggle(this, R.drawable.ic_book, "English book names",
                "A reference is said \"Numbers fourteen verse four\". Off: the name as printed, \"Bilang fourteen verse four\".",
                on -> Prefs.setEnglishBooks(this, on));
        Ui.add(bible, booksRow.view);
        col.addView(bible);

        col.addView(Ui.section(this, "Leave out"));
        LinearLayout skip = Ui.group(this);
        timeRow = Ui.toggle(this, R.drawable.ic_clock, "Skip timestamps", "\"2h ago\", \"10:42 PM\", \"Yesterday\"",
                on -> Prefs.setSkipTimestamps(this, on));
        Ui.add(skip, timeRow.view);
        buttonsRow = Ui.toggle(this, R.drawable.ic_touch, "Skip button names", "Buttons, icons and short labels like \"Close\"",
                on -> Prefs.setSkipButtons(this, on));
        Ui.add(skip, buttonsRow.view);
        Ui.add(skip, flagRow(R.drawable.ic_frame, "Skip author's notes",
                "\"A/N\", \"Author's note\", \"please vote and comment\", \"thanks for reading\" in stories", "skip_notes", true));
        Ui.add(skip, flagRow(R.drawable.ic_frame, "Stop before the cards after a story",
                "\"You'll also like\", \"Share this story\" and what follows are not read", "stop_cards", true));
        skipRow = Ui.nav(this, R.drawable.ic_frame, "Words to skip", "", () -> editList(false));
        Ui.add(skip, skipRow.view);
        col.addView(skip);

        col.addView(Ui.section(this, "Pronunciation"));
        LinearLayout say = Ui.group(this);
        sayRow = Ui.nav(this, R.drawable.ic_translate, "My pronunciations", "", () -> editList(true));
        Ui.add(say, sayRow.view);
        col.addView(say);

        col.addView(Ui.section(this, "How words are said"));
        LinearLayout how = Ui.group(this);
        Ui.add(how, flagRow(R.drawable.ic_clock, "Years the way people say them", "\"In 1844\" is read \"eighteen forty-four\". Off: left to the voice.",
                "say_years", true));
        Ui.add(how, flagRow(R.drawable.ic_book, "Page numbers in English", "In a Tagalog text, \"pahina 254\" is read \"pahina two fifty-four\".",
                "pages_english", true));
        Ui.add(how, flagRow(R.drawable.ic_translate, "Bible names the Tagalog way", "The Tagalog voice says \"Jesus\" as \"Hesus\" and \"Jerusalem\" as \"Herusalem\".",
                "respell_names", true));
        Ui.add(how, flagRow(R.drawable.ic_translate, "Say -syon as -shon", "The Tagalog voice says \"leksyon\" as \"lek-shon\". Off: as the voice reads it, \"lek-si-yon\".",
                "respell_syon", true));
        col.addView(how);
        return col;
    }

    // ------------------------------------------------------------ Voice

    private LinearLayout voicePage() {
        LinearLayout col = column();
        col.addView(pageTitle("Voice"));

        col.addView(Ui.section(this, "Voices"));
        LinearLayout g = Ui.group(this);
        enRow = Ui.nav(this, R.drawable.ic_person, "English voice", "", () -> pickVoice(TextPrep.EN));
        Ui.add(g, enRow.view);
        filRow = Ui.nav(this, R.drawable.ic_person, "Tagalog voice", "", () -> pickVoice(TextPrep.FIL));
        Ui.add(g, filRow.view);
        fil2Row = Ui.nav(this, R.drawable.ic_person, "Second Tagalog voice", "", () -> pickVoice(TextPrep.FIL, true));
        Ui.add(g, fil2Row.view);
        en2Row = Ui.nav(this, R.drawable.ic_person, "Second English voice", "", () -> pickVoice(TextPrep.EN, true));
        Ui.add(g, en2Row.view);
        col.addView(g);
        warn = Theme.body(this, "", Theme.DANGER, 12.5f);
        warn.setPadding(Theme.dp(this, 6), Theme.dp(this, 8), Theme.dp(this, 6), 0);
        col.addView(warn);

        col.addView(Ui.section(this, "Story and Bible voices"));
        LinearLayout st = Ui.group(this);
        Ui.add(st, flagRow(R.drawable.ic_clock, "Mark scene changes",
                "\"* * *\", \"Later that day\", \"The next morning\": a longer pause and, in a story, a soft chime", "scene_breaks", true));
        Ui.add(st, flagRow(R.drawable.ic_person, "A voice for each character",
                "In a story, what each character says is read in a voice of their own: a man's voice for a man, a woman's for a woman, "
                        + "the best voices first. The storyteller keeps the usual voice.", "cast_voices", true));
        Ui.add(st, flagRow(R.drawable.ic_eq, "Sound effects in stories",
                "A knock, a door, a ringing phone, thunder and the like are played as the story tells of them. A sound written out "
                        + "(\"Ring, ring!\") is played instead of said. Made on the phone, nothing to download.", "sfx", true));
        Ui.add(st, numSlider(R.drawable.ic_eq, "Sound effects volume", 0f, 1f, 0.05f, "sfx_volume", 0.7f, "%.2f"));
        Ui.add(st, flagRow(R.drawable.ic_person, "Voices that suit the character",
                "A child's voice, an old person's, a husky one, a mob boss's, a maid's: found from how the story describes them (\"a seven-year-old girl\", "
                        + "\"in a husky voice\", \"the maid\"). God, Jesus and the Holy Spirit are deep and slow. Made by changing the sound, so it works with any "
                        + "voice. Off: only the voice's own pitch is asked for, which some online voices ignore.", "voice_fx", true));
        castFilRow = Ui.nav(this, R.drawable.ic_person, "Tagalog voices for characters", "", () -> pickCastVoices(TextPrep.FIL));
        Ui.add(st, castFilRow.view);
        castEnRow = Ui.nav(this, R.drawable.ic_person, "English voices for characters", "", () -> pickCastVoices(TextPrep.EN));
        Ui.add(st, castEnRow.view);
        Ui.add(st, Ui.nav(this, R.drawable.ic_play, "Hear a drama sample",
                "A short story with a mob boss, a child, a maid, an old woman, Jesus, sound effects and a scene change, to check how voices and sounds come out on this phone", this::hearSample).view);
        Ui.add(st, Ui.nav(this, R.drawable.ic_person, "Fix a character's voice",
                "Every character the reader has met: pick man or woman, and a kind of voice (child, elder, husky, boss, maid\u2026)", this::pickCharacter).view);
        castRow = Ui.nav(this, R.drawable.ic_person, "Story characters", "", this::editPovNames);
        Ui.add(st, castRow.view);
        povRow = Ui.toggle(this, R.drawable.ic_person, "Point of view (POV) voice",
                "A line like \"Liam's POV\" switches the storyteller to the second voice for a man and \"POV: Mia\" to the usual voice for a woman.",
                on -> Prefs.setVoices(this, Prefs.bibleVoice(this), on));
        Ui.add(st, povRow.view);
        bibleVoiceRow = Ui.toggle(this, R.drawable.ic_book, "Second voice for Bible verses",
                "A quoted verse is read in the second voice, then the usual voice goes on.",
                on -> Prefs.setVoices(this, on, Prefs.povVoices(this)));
        Ui.add(st, bibleVoiceRow.view);
        Ui.add(st, numSlider(R.drawable.ic_clock, "Pause when the voice changes", 0f, 1f, 0.05f, "pause_voice", 0f, "%.2f s"));
        col.addView(st);

        col.addView(Ui.section(this, "Speed and pitch"));
        LinearLayout s = Ui.group(this);
        Ui.add(s, Ui.slider(this, R.drawable.ic_speed, "Speed", 0.6f, 1.8f, 0.1f, Prefs.rate(this), "%.1f×",
                v -> Prefs.setRate(this, v)));
        Ui.add(s, Ui.slider(this, R.drawable.ic_translate, "Tagalog speed", 0.8f, 1.2f, 0.05f, Prefs.rateFil(this), "%.2f×",
                v -> Prefs.setRateFil(this, v)));
        Ui.add(s, Ui.slider(this, R.drawable.ic_eq, "Pitch", 0.8f, 1.3f, 0.05f, Prefs.pitch(this), "%.2f",
                v -> Prefs.setPitch(this, v)));
        col.addView(s);

        col.addView(Ui.section(this, "Tone and pauses"));
        LinearLayout t = Ui.group(this);
        moodRow = Ui.nav(this, R.drawable.ic_eq, "Match the kind of text", "", this::pickMood);
        Ui.add(t, moodRow.view);
        Ui.add(t, flagRow(R.drawable.ic_eq, "Feelings in what characters say",
                "\"she whispered\" is read softly, \"he shouted\" louder and higher, \"umiiyak na sabi niya\" sadly. In stories only.",
                "feel_voices", true));
        toneRow = Ui.toggle(this, R.drawable.ic_eq, "Expressive reading",
                "Questions sound asking, with a moment after each. Exclamations have more life. Headings and quoted verses are read calmer and slower.",
                on -> Prefs.setQuestionTone(this, on));
        Ui.add(t, toneRow.view);
        Ui.add(t, Ui.slider(this, R.drawable.ic_eq, "Tone strength", 0f, 2f, 0.1f, Prefs.toneStrength(this), "%.1f×",
                v -> Prefs.setFloat(this, "tone_strength", v)));
        Ui.add(t, Ui.slider(this, R.drawable.ic_clock, "Pause between paragraphs", 0f, 1.5f, 0.05f, Prefs.pauseParagraph(this), "%.2f s",
                v -> Prefs.setFloat(this, "pause_paragraph", v)));
        Ui.add(t, Ui.slider(this, R.drawable.ic_clock, "Pause around headings", 0f, 2f, 0.05f, Prefs.pauseHeading(this), "%.2f s",
                v -> Prefs.setFloat(this, "pause_heading", v)));
        Ui.add(t, Ui.slider(this, R.drawable.ic_clock, "Pause after a question", 0f, 2f, 0.05f, Prefs.pauseQuestion(this), "%.2f s",
                v -> Prefs.setFloat(this, "pause_question", v)));
        col.addView(t);

        col.addView(Ui.section(this, "Internet and translation"));
        LinearLayout n = Ui.group(this);
        offlineRow = Ui.nav(this, R.drawable.ic_download, "Download for offline use", "", this::offlineDownloads);
        Ui.add(n, offlineRow.view);
        netRow = Ui.toggle(this, R.drawable.ic_download, "Use internet when connected",
                "Better online voice and Translate online. Off, or no internet: everything stays on the phone.",
                on -> {
                    Prefs.setUseInternet(this, on);
                    refresh();
                });
        Ui.add(n, netRow.view);
        onlineVoiceRow = Ui.toggle(this, R.drawable.ic_download, "Online voice",
                "Sounds better, but can pause between sentences and change loudness when the internet is slow. "
                        + "Off: the voice on the phone reads, steady and without waiting.",
                on -> {
                    Prefs.setOnlineVoice(this, on);
                    refresh();
                });
        Ui.add(n, onlineVoiceRow.view);
        aiRow = Ui.nav(this, R.drawable.ic_translate, "AI storyteller translation (free key)", "", this::editAiKey);
        Ui.add(n, aiRow.view);
        styleRow = Ui.nav(this, R.drawable.ic_translate, "Tagalog translation style", "", this::pickTagStyle);
        Ui.add(n, styleRow.view);
        glossRow = Ui.nav(this, R.drawable.ic_translate, "My translation words", "", this::editGlossary);
        Ui.add(n, glossRow.view);
        keyRow = Ui.nav(this, R.drawable.ic_translate, "Google Translate key (optional)", "", this::editTranslateKey);
        Ui.add(n, keyRow.view);
        dataRow = new Ui.Row(this, R.drawable.ic_info, "Data used", "");
        Ui.add(n, dataRow.view);
        col.addView(n);

        col.addView(Ui.section(this, "Voice app"));
        LinearLayout a = Ui.group(this);
        appRow = Ui.nav(this, R.drawable.ic_apps, "Phone voice app", "", this::pickPhoneApp);
        Ui.add(a, appRow.view);
        Ui.add(a, Ui.nav(this, R.drawable.ic_download, "Download better voices", "English (US) and Filipino, high quality",
                this::openVoiceDownloads).view);
        col.addView(a);

        Button test = Theme.button(this, "▶  Test the voice", true);
        test.setOnClickListener(v -> Speaker.get(this).speakText(SAMPLE));
        col.addView(test, Theme.spaced(this, 20));
        Button stop = Theme.button(this, "Stop", false);
        stop.setOnClickListener(v -> Speaker.get(this).stop());
        col.addView(stop, Theme.spaced(this, 8));
        return col;
    }

    // ------------------------------------------------------------ Controls

    private LinearLayout controlsPage() {
        LinearLayout col = column();
        col.addView(pageTitle("Controls"));

        col.addView(Ui.section(this, "While reading"));
        LinearLayout g = Ui.group(this);
        notifRow = Ui.toggle(this, R.drawable.ic_bell, "Notification controls", "Back, Pause, Skip and Stop", on -> {
            Prefs.setNotificationControls(this, on);
            askNotificationPermission();
        });
        Ui.add(g, notifRow.view);
        shakeRow = Ui.toggle(this, R.drawable.ic_shake, "Shake to stop", "Shake firmly twice", on -> Prefs.setShakeStop(this, on));
        Ui.add(g, shakeRow.view);
        fanRow = Ui.nav(this, R.drawable.ic_touch, "Floating button shortcuts", "", this::pickShortcuts);
        Ui.add(g, fanRow.view);
        sleepRow = Ui.nav(this, R.drawable.ic_timer, "Sleep timer", "", this::pickSleepTimer);
        Ui.add(g, sleepRow.view);
        Ui.add(g, flagRow(R.drawable.ic_timer, "Sleep timer finishes the paragraph",
                "When the time is up, the paragraph being read is finished first (at most 3 minutes more), then reading stops", "sleep_finish", true));
        Ui.add(g, new Ui.Row(this, R.drawable.ic_headset, "Headphone buttons",
                "Play/pause pauses and continues; next and previous skip a paragraph").view);
        col.addView(g);

        col.addView(Ui.section(this, "App"));
        LinearLayout app = Ui.group(this);
        accessRow = Ui.nav(this, R.drawable.ic_access, "Accessibility setting", "",
                this::showAccessGuide);
        Ui.add(app, accessRow.view);
        aliveRow = Ui.toggle(this, R.drawable.ic_bell, "Stay-on notification",
                "A quiet \"ZYLENA is ready\" notification, so the phone doesn't close ZYLENA and its button", on -> {
                    Prefs.setKeepAlive(this, on);
                    applyService();
                });
        Ui.add(app, aliveRow.view);
        Ui.add(app, Ui.nav(this, R.drawable.ic_bell, "Keep ZYLENA on", "Stop the phone from closing it (Xiaomi, Samsung, Oppo\u2026)",
                () -> showKeepOnGuide(false)).view);
        Ui.add(app, Ui.nav(this, R.drawable.ic_tune, "Restore default settings", "Every setting back to how it came",
                this::restoreDefaults).view);
        Ui.add(app, Ui.nav(this, R.drawable.ic_list, "Logs", "See and share what happened",
                () -> startActivity(new Intent(this, LogActivity.class))).view);
        Ui.add(app, flagRow(R.drawable.ic_tune, "Debug mode",
                "The log records much more: every line on screen, the scrolling areas, and what was tried. Turn it on, make the problem happen, then share the log.",
                "debug", false));
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        Ui.add(app, new Ui.Row(this, R.drawable.ic_info, "ZYLENA Screen Reader " + ver,
                "Free. Uses your phone's own voices; the Bible texts are public domain.").view);
        col.addView(app);
        return col;
    }

    // ------------------------------------------------------------ settings kept by key, folding and search

    private final List<Object[]> flagRows = new ArrayList<>(); // {row, key, default}

    /** An on/off row for a setting kept by its key. */
    private View flagRow(int icon, String title, String sub, String key, boolean def) {
        Ui.Row r = Ui.toggle(this, icon, title, sub, on -> Prefs.setFlag(this, key, on));
        flagRows.add(new Object[]{r, key, def});
        return r.view;
    }

    /** A slider for a number kept by its key. */
    private View numSlider(int icon, String title, float min, float max, float step, String key, float def, String format) {
        return Ui.slider(this, icon, title, min, max, step, Prefs.num(this, key, def), format, v -> Prefs.setFloat(this, key, v));
    }

    /** One setting, for the search: where it is and what it says. */
    private static final class Found {
        int tab;
        String section;
        TextView header;
        View group, row;
    }

    private final List<Found> allSettings = new ArrayList<>();
    private static final String[] TAB_NAMES = {"Home", "Reading", "Voice", "Controls"};
    /** Sections that start closed, so each page opens short: the less often needed ones. */
    private static final java.util.Set<String> CLOSED = new java.util.HashSet<>(java.util.Arrays.asList(
            "Open & read", "How words are said", "Leave out", "Tone and pauses", "Internet and translation", "Voice app"));

    /** Makes each section fold away when its title is tapped, and lists every setting for the search. */
    private void tidy(int tab, LinearLayout col) {
        for (int i = 0; i + 1 < col.getChildCount(); i++) {
            View head = col.getChildAt(i), body = col.getChildAt(i + 1);
            if (!(head instanceof TextView) || !(body instanceof LinearLayout) || !"group".equals(body.getTag())) continue;
            final TextView title = (TextView) head;
            final String name = title.getText().toString();
            final View group = body;
            boolean closed = getSharedPreferences("zylena_reader", MODE_PRIVATE).getBoolean("fold_" + name, CLOSED.contains(name));
            fold(title, name, group, closed);
            title.setOnClickListener(v -> {
                boolean now = group.getVisibility() == View.VISIBLE;
                getSharedPreferences("zylena_reader", MODE_PRIVATE).edit().putBoolean("fold_" + name, now).apply();
                fold(title, name, group, now);
            });
            LinearLayout g = (LinearLayout) body;
            for (int k = 0; k < g.getChildCount(); k++) {
                View row = g.getChildAt(k);
                if (!(row.getTag() instanceof String) || !((String) row.getTag()).startsWith("set:")) continue;
                Found f = new Found();
                f.tab = tab;
                f.section = name;
                f.header = title;
                f.group = group;
                f.row = row;
                allSettings.add(f);
            }
        }
    }

    private void fold(TextView title, String name, View group, boolean closed) {
        title.setText(name + (closed ? "   ▸" : "   ▾"));
        title.setTag(name);
        group.setVisibility(closed ? View.GONE : View.VISIBLE);
    }

    private static void texts(View v, StringBuilder into) {
        if (v instanceof TextView) into.append(((TextView) v).getText()).append('\n');
        else if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) texts(((ViewGroup) v).getChildAt(i), into);
    }

    private View searchBar() {
        searchBox = new android.widget.EditText(this);
        searchBox.setHint("Search settings");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Theme.INK);
        searchBox.setHintTextColor(Theme.FAINT);
        searchBox.setTextSize(15);
        searchBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Theme.dp(this, 14));
        bg.setColor(Theme.SURFACE_2);
        bg.setStroke(Theme.dp(this, 1), Theme.LINE);
        searchBox.setBackground(bg);
        int h = Theme.dp(this, 14), v = Theme.dp(this, 10);
        searchBox.setPadding(h, v, h, v);
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
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
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(Theme.dp(this, 16), 0, Theme.dp(this, 16), Theme.dp(this, 6));
        wrap.addView(searchBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** Shows the settings whose name or description holds every word typed. */
    private void search(String q) {
        String[] words = q.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+");
        if (q.trim().length() < 2) {
            results.setVisibility(View.GONE);
            pages.get(tab).setVisibility(View.VISIBLE);
            return;
        }
        for (ScrollView p : pages) p.setVisibility(View.GONE);
        results.setVisibility(View.VISIBLE);
        resultsCol.removeAllViews();
        resultsCol.addView(pageTitle("Search"));
        LinearLayout g = Ui.group(this);
        int n = 0;
        for (Found f : allSettings) {
            StringBuilder sb = new StringBuilder(f.section).append('\n');
            texts(f.row, sb);
            String hay = sb.toString().toLowerCase(java.util.Locale.ROOT);
            boolean all = true;
            for (String w : words) all &= hay.contains(w);
            if (!all) continue;
            String name = ((String) f.row.getTag()).substring(4);
            Ui.add(g, Ui.nav(this, R.drawable.ic_tune, name, TAB_NAMES[f.tab] + "  ›  " + f.section, () -> open(f)).view);
            n++;
        }
        if (n > 0) resultsCol.addView(g);
        else resultsCol.addView(Theme.body(this, "No setting found for \"" + q.trim() + "\". Try another word, like voice, pause, Bible, page or story.", Theme.MUTED, 14));
    }

    /** Goes to a setting found by the search: its tab, its section opened, the row in view and marked for a moment. */
    private void open(Found f) {
        searchBox.setText("");
        searchBox.clearFocus();
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        showTab(f.tab);
        fold(f.header, (String) f.header.getTag(), f.group, false);
        final ScrollView page = pages.get(f.tab);
        page.post(() -> {
            int y = 0;
            View v = f.row;
            while (v != null && v != page.getChildAt(0)) {
                y += v.getTop();
                v = (View) v.getParent();
            }
            page.smoothScrollTo(0, Math.max(0, y - Theme.dp(this, 80)));
            final android.graphics.drawable.Drawable was = f.row.getBackground();
            f.row.setBackgroundColor(Color.argb(70, 214, 173, 85));
            f.row.postDelayed(() -> f.row.setBackground(was), 1400);
        });
    }

    private static final String[] NEXT_IDS = {"auto", "pager", "button", "swipe", "tap", "off"};
    private static final String[] NEXT_NAMES = {
            "Auto: swipe to the next page, else tap the app's Next button",
            "Pages side by side (Sabbath School, EGW Writings)",
            "Tap the app's Next button (story apps)",
            "Swipe a finger from right to left",
            "Tap the right edge of the screen",
            "Don't go on to a next page"};

    private static String nextName(String id) {
        int i = java.util.Arrays.asList(NEXT_IDS).indexOf(id);
        return NEXT_NAMES[i < 0 ? 0 : i];
    }

    /** pkg null: for every app; else for that app alone. */
    private void pickNextMode(String pkg) {
        if (pkg != null && pkg.isEmpty()) {
            android.widget.Toast.makeText(this, "Read something in the app first, then come back here.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final boolean own = pkg != null;
        final String[] ids = new String[NEXT_IDS.length + (own ? 1 : 0)];
        final String[] names = new String[ids.length];
        if (own) {
            ids[0] = "";
            names[0] = "Same as every app";
        }
        for (int i = 0; i < NEXT_IDS.length; i++) {
            ids[i + (own ? 1 : 0)] = NEXT_IDS[i];
            names[i + (own ? 1 : 0)] = NEXT_NAMES[i];
        }
        String cur = own ? Prefs.nextModeOwn(this, pkg) : Prefs.nextMode(this, null);
        int now = Math.max(0, java.util.Arrays.asList(ids).indexOf(cur));
        new AlertDialog.Builder(this)
                .setTitle(own ? "Next page in " + appName(pkg) : "How the next page is reached")
                .setSingleChoiceItems(names, now, (d, w) -> {
                    Prefs.setNextMode(this, pkg, ids[w]);
                    Logs.d(this, "next page " + (own ? "in " + pkg : "in every app") + ": " + (ids[w].isEmpty() ? "same as every app" : ids[w]));
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void editNextWords() {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(Prefs.nextWords(this));
        e.setMinLines(4);
        e.setGravity(Gravity.TOP);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int p = Theme.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, Theme.dp(this, 8), p, 0);
        box.addView(Theme.body(this, "Separated by commas. At the end of a page ZYLENA taps a button whose label is one of these, or begins with one. "
                + "Add the words your app uses. Empty: back to the list it came with.", Theme.MUTED, 13));
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("Words on a Next button")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> Prefs.setNextWords(this, e.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickMood() {
        final String[] ids = new String[Mood.KINDS.length + 2];
        final String[] names = new String[ids.length];
        ids[0] = "auto";
        names[0] = "Auto: tell from the page";
        ids[1] = "off";
        names[1] = "Off: always the same";
        for (int i = 0; i < Mood.KINDS.length; i++) {
            ids[i + 2] = Mood.KINDS[i];
            names[i + 2] = "Always: " + Mood.KIND_NAMES[i];
        }
        int now = Math.max(0, java.util.Arrays.asList(ids).indexOf(Prefs.mood(this)));
        new AlertDialog.Builder(this)
                .setTitle("Match the kind of text")
                .setSingleChoiceItems(names, now, (d, w) -> {
                    Prefs.setMood(this, ids[w]);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Which of the phone's voices a story's characters may use, and whether each is a man's or a woman's. */
    private static final String[] SAMPLE_STORY = {
            "Chapter One",
            "Thunder rolled across the sky as the rain began to fall.",
            "Don Vito leaned back in his chair. He was the mafia boss of the whole city.",
            "\"You are late,\" Don Vito said coldly.",
            "\"I'm sorry, boss,\" Luca whispered.",
            "Someone knocked at the door.",
            "Mia, a seven-year-old girl, ran in and tugged at her mother's sleeve.",
            "\"Mommy, can we go home now?\" Mia asked.",
            "\"Soon, sweetheart,\" Anna said, smiling.",
            "Lola Rosa chuckled from her rocking chair. \"Ay, these children,\" she said.",
            "The maid, Carmen, curtsied at the door.",
            "\"Dinner is ready, sir,\" Carmen said softly.",
            "\"Ha ha ha!\"",
            "* * *",
            "Later that night, a gentle voice filled the room.",
            "\"Peace,\" Jesus said. \"Do not be afraid.\"",
            "\"I-I'm not afraid,\" Luca said in a husky voice.",
            "The end."};

    /** A short story that uses everything: story voices, kinds of voice, God's voice, sound effects, a scene change. */
    private void hearSample() {
        Prefs.applyLists(this);
        TextPrep.scope("zylena.sample");
        java.util.List<TextPrep.Seg> segs = TextPrep.prepare(java.util.Arrays.asList(SAMPLE_STORY), false, true, false);
        android.widget.Toast.makeText(this, "Playing a short drama sample\u2026", android.widget.Toast.LENGTH_SHORT).show();
        Speaker.get(this).speak(segs, false);
    }

    private static final String[] CHAR_STYLE_IDS = {"", "none", "child", "teen", "elder", "husky", "gruff", "boss", "villain", "maid", "butler", "gentle", "cute", "squeaky", "bass"};
    private static final String[] CHAR_STYLE_NAMES = {"Automatic (from the story)", "Plain voice", "Child", "Teen", "Elder (old person)", "Husky", "Gruff", "Boss (mob boss)", "Villain",
            "Maid", "Butler", "Gentle", "Cute", "Squeaky", "Deep bass"};

    /** Every character met so far; a tap lets the user say man or woman and what kind of voice. */
    private void pickCharacter() {
        java.util.List<String[]> known = Cast.knownList();
        java.util.LinkedHashMap<String, String[]> all = new java.util.LinkedHashMap<>();
        for (String[] k : known) all.put(k[0].toLowerCase(java.util.Locale.ROOT), k);
        for (String line : Prefs.castOverrides(this).split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length >= 3 && !f[0].trim().isEmpty() && !all.containsKey(f[0].trim().toLowerCase(java.util.Locale.ROOT))) {
                all.put(f[0].trim().toLowerCase(java.util.Locale.ROOT), new String[]{f[0].trim(), f[1].isEmpty() ? "u" : f[1], f[2].equals("none") ? "" : f[2]});
            }
        }
        java.util.List<String[]> list = new java.util.ArrayList<>(all.values());
        String[] labels = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            String[] k = list.get(i);
            labels[i] = k[0] + " \u2014 " + (k[1].equals("m") ? "man" : k[1].equals("f") ? "woman" : "not sure") + (k[2].isEmpty() ? "" : ", " + k[2]);
        }
        labels[list.size()] = "Add a character by name\u2026";
        new AlertDialog.Builder(this)
                .setTitle("Characters")
                .setItems(labels, (d, which) -> {
                    if (which < list.size()) {
                        editCharacter(list.get(which)[0]);
                        return;
                    }
                    android.widget.EditText in = new android.widget.EditText(this);
                    in.setHint("Name, e.g. Lily");
                    in.setSingleLine(true);
                    new AlertDialog.Builder(this).setTitle("Add a character").setView(in)
                            .setPositiveButton("Next", (d2, w) -> {
                                String n = in.getText().toString().trim();
                                if (!n.isEmpty()) editCharacter(n);
                            }).setNegativeButton("Cancel", null).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void editCharacter(String name) {
        String[] genders = {"Automatic", "Man", "Woman", "Not sure"};
        String[] gid = {"", "m", "f", "u"};
        new AlertDialog.Builder(this)
                .setTitle(name + ": a man or a woman?")
                .setItems(genders, (d, g) -> new AlertDialog.Builder(this)
                        .setTitle(name + ": what kind of voice?")
                        .setItems(CHAR_STYLE_NAMES, (d2, st) -> {
                            String key = name.trim().toLowerCase(java.util.Locale.ROOT);
                            StringBuilder out = new StringBuilder();
                            for (String line : Prefs.castOverrides(this).split("\n")) {
                                if (line.isEmpty() || line.toLowerCase(java.util.Locale.ROOT).startsWith(key + "|")) continue;
                                out.append(line).append('\n');
                            }
                            if (!gid[g].isEmpty() || !CHAR_STYLE_IDS[st].isEmpty()) out.append(key).append('|').append(gid[g]).append('|').append(CHAR_STYLE_IDS[st]).append('\n');
                            Prefs.setCastOverrides(this, out.toString().trim());
                            android.widget.Toast.makeText(this, name + ": saved. It is used the next time the reader reaches them.", android.widget.Toast.LENGTH_LONG).show();
                        }).show())
                .show();
    }

    private void pickCastVoices(String lang) {
        Speaker sp = Speaker.get(this);
        List<Voice> voices = sp.voicesFor(lang);
        if (voices.isEmpty()) {
            refresh();
            return;
        }
        final String sample = lang.equals(TextPrep.FIL) ? "\"Saan ka pupunta?\" tanong niya." : "\"Where are you going?\" she asked.";
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 14);
        list.setPadding(p, p, p, p);
        list.addView(Theme.body(this, "Characters take these voices in turn, the best first; your usual voice stays the storyteller's. "
                + "Android doesn't say whether a voice is a man's or a woman's, so ZYLENA guesses: tap Play to hear one, and tap the "
                + "button beside it to set it right or to leave the voice out.", Theme.MUTED, 13));
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(lang.equals(TextPrep.FIL) ? "Tagalog voices for characters" : "English voices for characters")
                .setView(sv)
                .setPositiveButton("Done", null)
                .create();
        dlg.setOnDismissListener(d -> {
            sp.stop();
            sp.voicesChanged();
            refresh();
        });
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Voice> order = new ArrayList<>(sp.castPool(lang, 'm'));
        order.addAll(sp.castPool(lang, 'f'));
        for (Voice v : voices) order.add(v); // then the rest: not known, or left out
        for (Voice v : order) {
            final String base = Speaker.base(v.getName());
            if (!seen.add(base)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Theme.dp(this, 4), 0, Theme.dp(this, 4));
            String country = v.getLocale().getDisplayCountry();
            TextView name = Theme.body(this, Speaker.friendly(v).replaceAll(" · likely.*", "") + (country.isEmpty() ? "" : "  · " + country), Theme.INK, 14);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final Button kind = Theme.button(this, "", false);
            kind.setMinHeight(Theme.dp(this, 40));
            kind.setTextSize(13);
            final Runnable label = () -> {
                String mine = Prefs.voiceGenders(this).get(base);
                char g = sp.genderOf(v);
                kind.setText(mine == null ? (g == 'm' ? "Man (guess)" : g == 'f' ? "Woman (guess)" : "Not sure")
                        : g == 'm' ? "Man" : g == 'f' ? "Woman" : "Not used");
            };
            label.run();
            kind.setOnClickListener(x -> {
                String mine = Prefs.voiceGenders(this).get(base);
                Prefs.setVoiceGender(this, base, mine == null ? "m" : mine.equals("m") ? "f" : mine.equals("f") ? "x" : null);
                label.run();
            });
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            klp.setMarginEnd(Theme.dp(this, 6));
            row.addView(kind, klp);
            Button play = Theme.button(this, "▶", false);
            play.setMinHeight(Theme.dp(this, 40));
            play.setTextSize(13);
            play.setOnClickListener(x -> sp.preview(v, sample));
            row.addView(play, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            list.addView(row);
        }
        dlg.show();
    }

    private String castLabel(String lang) {
        Speaker sp = Speaker.get(this);
        int m = sp.castPool(lang, 'm').size(), f = sp.castPool(lang, 'f').size();
        if (m + f == 0) return "No other voices on the phone: the usual voice is said higher or lower for each character";
        return m + (m == 1 ? " man's voice, " : " men's voices, ") + f + (f == 1 ? " woman's voice" : " women's voices")
                + (m == 0 ? ". No man's voice: the usual voice, lower" : "");
    }

    // ------------------------------------------------------------ state

    private void refresh() {
        boolean on = serviceOn();
        boolean speaking = Speaker.get(this).isSpeaking();
        statusChip.setText(on ? "\u25CF  READY" : "\u25CF  OFF");
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(Theme.dp(this, 99));
        chip.setColor(on ? Theme.DONE : Theme.DANGER);
        statusChip.setBackground(chip);
        heroTitle.setText(on ? "Ready to read" : "Turn on ZYLENA");
        heroText.setText(on ? "Open any app and tap the gold button. English, Tagalog or Taglish."
                : "Android needs your OK before ZYLENA can see the screen. It reads nothing until you tap the button.");
        heroButton.setText(!on ? "Turn on in Accessibility" : speaking ? "\u25A0  Stop" : "\u25B6  Test the voice");

        String readApp = appName(Prefs.readApp(this));
        ((TextView) readTile.findViewWithTag("sub")).setText(readApp + " \u00B7 hold to change");
        readAppRow.setSub("The Open & read shortcut opens this app and reads it");
        readAppRow.setValue(readApp);
        int left = Speaker.get(this).sleepMinutesLeft();
        String sleep = left > 0 ? "Stops in " + left + " min" : "Off";
        ((TextView) sleepTile.findViewWithTag("sub")).setText(sleep);
        sleepRow.setValue(sleep);
        accessRow.setValue(on ? "On" : "Off");

        set(floatRow, Prefs.floatingButton(this));
        set(netRow, Prefs.useInternet(this));
        int st = Math.max(0, java.util.Arrays.asList(STYLE_IDS).indexOf(Prefs.tagStyle(this)));
        styleRow.setValue(STYLE_NAMES[st]);
        styleRow.setSub(STYLE_SUBS[st]);
        aiRow.setSub(Prefs.aiKey(this).isEmpty() ? "Not set. With a free Gemini key, Translate tells it naturally instead of word for word"
                : "Set: an AI translates by meaning, like a storyteller");
        int gl = TextPrep.parseSay(Prefs.glossary(this)).size();
        glossRow.setSub(gl == 0 ? "Teach Translate which word to use" : gl + (gl == 1 ? " word" : " words"));
        offlineRow.setSub(offlineSummary());
        Translate.packReady(ok -> {
            if (ok == packOnPhone) return;
            packOnPhone = ok;
            offlineRow.setSub(offlineSummary());
        });
        set(onlineVoiceRow, Prefs.onlineVoice(this));
        keyRow.setSub(Prefs.translateKey(this).isEmpty() ? "Not set: the free translators are used (Google, then MyMemory)" : "Set: Translate uses your Google key");
        set(aliveRow, Prefs.keepAlive(this));
        set(tuckRow, Prefs.tuck(this));
        StringBuilder fanNames = new StringBuilder();
        for (String[] sc : Shortcuts.chosen(this)) fanNames.append(fanNames.length() == 0 ? "" : ", ").append(sc[2]);
        fanRow.setSub("Hold the button: " + fanNames + " and All controls");
        dataRow.setSub(Net.usedThisMonth(this) + ". The online voice uses about 11 MB an hour of listening; "
                + "translation adds less than 1 MB an hour.");
        set(autoRow, Prefs.autoRead(this));
        set(resumeRow, Prefs.resume(this));
        set(pageRow, Prefs.wholePage(this));
        set(nextRow, Prefs.nextPage(this));
        set(markRow, Prefs.highlight(this));
        String mode = Prefs.refMode(this);
        refsRow.setSub(mode.equals("skip") ? "Skip them: \u2026Apostol (Lucas 8:51). Matapos\u2026 is read \u2026Apostol. Matapos\u2026"
                : mode.equals("verses") ? "Read the verse text (Ang Biblia 1905 / KJV)" : "Read the reference: Lucas kabanata 8, talata 51");
        set(booksRow, Prefs.englishBooks(this));
        set(toneRow, Prefs.questionTone(this));
        set(bibleVoiceRow, Prefs.bibleVoice(this));
        set(povRow, Prefs.povVoices(this));
        for (Object[] fr : flagRows) set((Ui.Row) fr[0], Prefs.flag(this, (String) fr[1], (Boolean) fr[2]));
        nextHowRow.setSub("In every app: " + nextName(Prefs.nextMode(this, null)));
        String la = Prefs.lastApp(this);
        nextAppRow.title.setText(la.isEmpty() ? "Next page in the app last read" : "Next page in " + appName(la));
        nextAppRow.setSub(la.isEmpty() ? "Read something first: then its own way can be chosen here"
                : Prefs.nextModeOwn(this, la).isEmpty() ? "Same as every app" : nextName(Prefs.nextModeOwn(this, la)));
        String md = Prefs.mood(this);
        int mi = Mood.kinds().indexOf(md);
        moodRow.setValue(md.equals("auto") ? "Auto" : md.equals("off") ? "Off" : Mood.KIND_NAMES[mi < 0 ? 0 : mi].replaceAll("[ ,(].*", ""));
        moodRow.setSub(md.equals("auto") ? "ZYLENA tells a devotional, a love story, suspense, comedy, drama or action from the page and reads to suit it"
                : md.equals("off") ? "The voice reads every kind of text the same way" : "Always read as: " + Mood.KIND_NAMES[mi < 0 ? 0 : mi]);
        castFilRow.setSub(castLabel(TextPrep.FIL));
        castEnRow.setSub(castLabel(TextPrep.EN));
        String known = Cast.known();
        castRow.setSub(known.isEmpty() ? "Tell ZYLENA who is a man or a woman when it guesses wrong" : "Found so far: " + known);
        refsRow.setValue(mode.equals("skip") ? "Skip" : mode.equals("verses") ? "Verse text" : "Read");
        set(timeRow, Prefs.skipTimestamps(this));
        set(buttonsRow, Prefs.skipButtons(this));
        set(notifRow, Prefs.notificationControls(this));
        set(shakeRow, Prefs.shakeStop(this));
        int say = TextPrep.parseSay(Prefs.sayList(this)).size(), skip = TextPrep.parseSkip(Prefs.skipList(this)).size();
        sayRow.setSub(say == 0 ? "Teach it how to say a word" : say + (say == 1 ? " word" : " words"));
        skipRow.setSub(skip == 0 ? "Words or phrases never read" : skip + (skip == 1 ? " item" : " items"));

        fil2Row.setSub(voice2Label(TextPrep.FIL));
        en2Row.setSub(voice2Label(TextPrep.EN));
        enRow.setSub(voiceLabel(TextPrep.EN));
        filRow.setSub(voiceLabel(TextPrep.FIL));
        String pkg = Prefs.ttsPackage(this);
        appRow.setSub(pkg.isEmpty() ? "Phone default" : pkg);
        boolean noFil = Speaker.get(this).voicesFor(TextPrep.FIL).isEmpty();
        warn.setText(noFil ? "No Tagalog voice is installed yet. Tap Download better voices and add Filipino." : "");
        warn.setVisibility(noFil ? View.VISIBLE : View.GONE);
    }

    /** The four shortcuts in the fan that opens when the floating button is held. */
    private void pickShortcuts() {
        java.util.List<String[]> now = Shortcuts.chosen(this);
        String[] slots = new String[Shortcuts.SLOTS];
        for (int i = 0; i < slots.length; i++) slots[i] = i < now.size() ? now.get(i)[0] : Shortcuts.ALL[i][0];
        String[] labels = new String[slots.length];
        for (int i = 0; i < slots.length; i++) {
            String[] sc = Shortcuts.find(slots[i]);
            labels[i] = "Shortcut " + (i + 1) + ":  " + (sc == null ? "?" : sc[1] + "  " + sc[2]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Floating button shortcuts")
                .setItems(labels, (d, which) -> {
                    String[] names = new String[Shortcuts.ALL.length];
                    for (int k = 0; k < names.length; k++) names[k] = Shortcuts.ALL[k][1] + "  " + Shortcuts.ALL[k][2];
                    new AlertDialog.Builder(this)
                            .setTitle("Shortcut " + (which + 1))
                            .setItems(names, (d2, k) -> {
                                slots[which] = Shortcuts.ALL[k][0];
                                Prefs.setShortcuts(this, String.join(",", slots));
                                refresh();
                                pickShortcuts();
                            })
                            .show();
                })
                .setNegativeButton("Done", null)
                .show();
    }

    /**
     * The free web translator often turns this phone away ("too many requests"), and the translator on the
     * phone is rougher. With the person's own Google Cloud Translation key (500,000 characters a month free)
     * Translate is accurate. The key stays on this phone.
     */
    private void editTranslateKey() {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setHint("Paste the key here");
        e.setText(Prefs.translateKey(this));
        new AlertDialog.Builder(this)
                .setTitle("Google Translate key")
                .setMessage("For the most accurate Translate. In Google Cloud console: create a project, turn on \"Cloud Translation API\", "
                        + "then Credentials > Create API key. The first 500,000 characters a month are free. The key stays on this phone "
                        + "and is only sent to Google. Leave empty to use the free translator.")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    Prefs.setTranslateKey(this, e.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void editAiKey() {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setHint("Paste the Gemini key here");
        e.setText(Prefs.aiKey(this));
        new AlertDialog.Builder(this)
                .setTitle("AI storyteller translation")
                .setMessage("Translate then goes by meaning and sounds natural, not word for word. Free, no card: open "
                        + "aistudio.google.com/apikey in a browser, sign in with a Google account, tap \"Create API key\", copy it "
                        + "and paste it here. The key stays on this phone and is only sent to Google. The text being translated is "
                        + "sent to Google's Gemini; on the free plan Google may use it to improve its products. When the free "
                        + "allowance runs out for the moment, the usual translator takes over. Leave empty to turn this off.")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    Prefs.setAiKey(this, e.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreDefaults() {
        new AlertDialog.Builder(this)
                .setTitle("Restore default settings?")
                .setMessage("Voices, speed, what to leave out, your word lists, button size and place: all back to how they came.")
                .setPositiveButton("Restore", (d, w) -> {
                    Speaker.get(this).stop();
                    Prefs.restoreDefaults(this);
                    Prefs.applyLists(this);
                    Logs.d(this, "settings restored to defaults");
                    applyService();
                    android.widget.Toast.makeText(this, "Settings restored", android.widget.Toast.LENGTH_SHORT).show();
                    recreate(); // sliders and switches show the defaults
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static final String[] STYLE_IDS = {"everyday", "casual", "modern", "formal"};
    private static final String[] STYLE_NAMES = {"Everyday", "Casual", "Modern", "Formal"};
    private static final String[] STYLE_SUBS = {
            "Everyday words anyone can follow: pero, dahil, para, libro",
            "How friends talk: kasi, meron, yung, ganun",
            "Everyday words, and common English words stay English",
            "Book Tagalog as the translator gives it: ngunit, sapagkat, upang"};

    private void pickTagStyle() {
        String[] labels = new String[STYLE_IDS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = STYLE_NAMES[i] + ": " + STYLE_SUBS[i];
        int now = java.util.Arrays.asList(STYLE_IDS).indexOf(Prefs.tagStyle(this));
        new AlertDialog.Builder(this)
                .setTitle("Tagalog translation style")
                .setSingleChoiceItems(labels, now, (d, which) -> {
                    Prefs.setTagStyle(this, STYLE_IDS[which]);
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private void pickRefMode() {
        String[] ids = {"skip", "say", "verses"};
        String[] labels = {"Skip references (go straight on with the text)", "Read the reference (Lucas kabanata 8, talata 51)",
                "Read the verse text from the built-in Bible"};
        int now = java.util.Arrays.asList(ids).indexOf(Prefs.refMode(this));
        new AlertDialog.Builder(this)
                .setTitle("Bible references")
                .setSingleChoiceItems(labels, now, (d, which) -> {
                    Prefs.setRefMode(this, ids[which]);
                    if (ids[which].equals("verses") && Bible.source instanceof VerseStore) ((VerseStore) Bible.source).warmUp();
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    private static void set(Ui.Row r, boolean on) {
        if (r.sw.isChecked() != on) r.sw.setChecked(on);
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && Prefs.notificationControls(this)
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    private void pickSleepTimer() {
        final int[] mins = {0, 15, 30, 45, 60, 90};
        String[] labels = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "1 hour 30 minutes"};
        new AlertDialog.Builder(this)
                .setTitle("Stop reading after")
                .setItems(labels, (d, which) -> {
                    Speaker.get(this).setSleepTimer(mins[which]);
                    refresh();
                })
                .show();
    }

    /** Edits the words the user taught Translate. */
    private void editGlossary() {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(Prefs.glossary(this));
        e.setHint("counterfeit = huwad\npekeng = huwad na\nsimbahan = iglesia");
        e.setMinLines(6);
        e.setGravity(android.view.Gravity.TOP);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int p = Theme.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, Theme.dp(this, 8), p, 0);
        box.addView(Theme.body(this, "One per line: word = the word you want. The left side can be the word on the page "
                + "(English or Tagalog) or a word the translation keeps saying wrong.", Theme.MUTED, 13));
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("My translation words")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Prefs.setGlossary(this, e.getText().toString());
                    Logs.d(this, "translation words: " + TextPrep.parseSay(Prefs.glossary(this)).size() + " saved");
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Edits who is a man or a woman among a story's characters. */
    private void editPovNames() {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(Prefs.povNames(this));
        e.setHint("Riley = male\nAlex = female\nSkye = babae");
        e.setMinLines(5);
        e.setGravity(android.view.Gravity.TOP);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int p = Theme.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, Theme.dp(this, 8), p, 0);
        String known = Cast.known();
        box.addView(Theme.body(this, "One per line: name = male or female (lalaki or babae). Used for a character's voice when the name could be either."
                + (known.isEmpty() ? "" : "\n\nFound so far: " + known + "."), Theme.MUTED, 13));
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("Story characters")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Prefs.setPovNames(this, e.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Forget the characters", (d, w) -> {
                    Cast.reset();
                    refresh();
                })
                .show();
    }

    /** Edits "My pronunciations" (say = true) or "Words to skip". */
    private void editList(boolean say) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(say ? Prefs.sayList(this) : Prefs.skipList(this));
        e.setHint(say ? "Domitian = Domishan\nGCash = Gee Cash" : "Sponsored\nShare\nSee translation");
        e.setMinLines(6);
        e.setGravity(android.view.Gravity.TOP);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int p = Theme.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, Theme.dp(this, 8), p, 0);
        box.addView(Theme.body(this, say ? "One per line: word = how to say it" : "One word or phrase per line. They are never read.",
                Theme.MUTED, 13));
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle(say ? "My pronunciations" : "Words to skip")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String text = e.getText().toString();
                    if (say) Prefs.setLists(this, text, Prefs.skipList(this));
                    else Prefs.setLists(this, Prefs.sayList(this), text);
                    Logs.d(this, (say ? "pronunciations: " + TextPrep.userSay.size() : "skip words: " + TextPrep.userSkip.size()) + " saved");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Opens the chosen app (Sabbath School by default) and reads what it shows. */
    private void readLesson() {
        String pkg = Prefs.readApp(this);
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            android.widget.Toast.makeText(this, appName(pkg) + " isn't installed. Hold the tile to pick another app.",
                    android.widget.Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
            } catch (Exception ignored) {
            }
            return;
        }
        ReaderService s = ReaderService.instance;
        if (s == null) {
            showAccessGuide();
            return;
        }
        android.widget.Toast.makeText(this, "Opening " + appName(pkg) + ". Reading starts in a few seconds.",
                android.widget.Toast.LENGTH_LONG).show();
        s.openAndRead(launch);
    }

    private String appName(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg.equals("com.cryart.sabbathschool") ? "Sabbath School" : pkg;
        }
    }

    /** Choose the app that "Open & read" opens: any app on the phone (Wattpad, a manga reader, news...). */
    private void pickReadApp() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> found = pm.queryIntentActivities(main, 0);
        List<String[]> apps = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new String[]{String.valueOf(r.loadLabel(pm)), pkg});
        }
        apps.sort((x, y) -> x[0].compareToIgnoreCase(y[0]));
        String current = Prefs.readApp(this);
        String[] labels = new String[apps.size()];
        int checked = -1;
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("App for Open & read")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Prefs.setReadApp(this, apps.get(which)[1]);
                    Logs.d(this, "open & read app: " + apps.get(which)[1]);
                    d.dismiss();
                    refresh();
                })
                .setNeutralButton("Sabbath School", (d, w) -> {
                    Prefs.setReadApp(this, "com.cryart.sabbathschool");
                    refresh();
                })
                .show();
    }

    private String offlineSummary() {
        Speaker sp = Speaker.get(this);
        boolean fil = sp.hasOfflineVoice(TextPrep.FIL), en = sp.hasOfflineVoice(TextPrep.EN);
        if (packOnPhone && fil && en) return "Ready: reading and Translate work without internet";
        return "Translation pack and voices, so reading and Translate work without internet or on little data";
    }

    /** What ZYLENA needs on the phone to work without internet, what is there already, and buttons to get the rest. */
    private void offlineDownloads() {
        Speaker sp = Speaker.get(this);
        boolean fil = sp.hasOfflineVoice(TextPrep.FIL), en = sp.hasOfflineVoice(TextPrep.EN);
        String yes = "\u2713 on the phone", no = "\u2717 not yet";
        String msg = "Translation pack (about 30 MB): " + (packOnPhone ? yes : no)
                + "\nTagalog voice: " + (fil ? yes : no)
                + "\nEnglish voice: " + (en ? yes : no)
                + "\nBibles (English and Tagalog): " + yes + ", they come with ZYLENA"
                + "\n\nWith these on the phone, reading and Translate work with no internet, and use almost no mobile data. "
                + "The AI translation and the online voice still need internet; without it ZYLENA uses the ones on the phone."
                + (fil && en ? "" : "\n\nVoices are downloaded in the phone's voice app: tap Get voices, then download Filipino and English (United States).");
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Download for offline use").setMessage(msg)
                .setNegativeButton("Close", null)
                .setNeutralButton(fil && en ? "More voices" : "Get voices", (d, w) -> openVoiceDownloads());
        if (!packOnPhone) b.setPositiveButton("Download pack", (d, w) -> {
            android.widget.Toast.makeText(this, "Downloading the translation pack (about 30 MB)…", android.widget.Toast.LENGTH_LONG).show();
            Translate.downloadPack(getApplicationContext(), err -> {
                packOnPhone = err == null;
                android.widget.Toast.makeText(getApplicationContext(), err == null ? "The translation pack is on the phone. Translate now works offline."
                        : "Couldn't download the translation pack. Check the internet and try again.", android.widget.Toast.LENGTH_LONG).show();
                if (!isFinishing()) offlineRow.setSub(offlineSummary());
            });
        });
        b.show();
    }

    /** Opens the voice-data download page of the phone voice app (Google's high-quality voices live there). */
    private void openVoiceDownloads() {
        String pkg = Prefs.ttsPackage(this);
        Intent i = new Intent("android.speech.tts.engine.INSTALL_TTS_DATA");
        i.setPackage(pkg.isEmpty() ? "com.google.android.tts" : pkg);
        try {
            startActivity(i);
            android.widget.Toast.makeText(this, "Download English (United States) and Filipino. Pick the voices marked high quality.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            // No settings page on this phone: offer Google's voice app in the Play Store.
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")));
        }
    }

    /** Choose which text-to-speech app speaks for the phone voice, e.g. Google or Samsung. */
    private void pickPhoneApp() {
        Speaker sp = Speaker.get(this);
        List<android.speech.tts.TextToSpeech.EngineInfo> apps = sp.phoneEngines();
        if (apps.isEmpty()) {
            sp.whenSystemReady(this::refresh);
            return;
        }
        String[] labels = new String[apps.size() + 2];
        String current = Prefs.ttsPackage(this);
        labels[0] = (current.isEmpty() ? "\u2713 " : "") + "Phone default";
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = (apps.get(i).name.equals(current) ? "\u2713 " : "") + apps.get(i).label;
        }
        labels[labels.length - 1] = "Get Google Speech Services (best free voices)";
        new AlertDialog.Builder(this)
                .setTitle("Phone voice app")
                .setItems(labels, (d, which) -> {
                    if (which == labels.length - 1) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")));
                        return;
                    }
                    String pkg = which == 0 ? "" : apps.get(which - 1).name;
                    sp.usePhoneEngine(pkg, () -> {
                        refresh();
                        Speaker.get(this).speakText(SAMPLE);
                    });
                })
                .show();
    }

    private boolean enabledInSettings() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(new ComponentName(this, ReaderService.class).flattenToString());
    }

    private boolean serviceOn() {
        if (ReaderService.instance != null) return true;
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(new ComponentName(this, ReaderService.class).flattenToString());
    }

    private void applyService() {
        ReaderService s = ReaderService.instance;
        if (s != null) s.applyPrefs();
    }

    private String voiceLabel(String lang) {
        String chosen = Prefs.voice(this, lang);
        List<Voice> v = Speaker.get(this).voicesFor(lang);
        if (!chosen.isEmpty()) {
            for (Voice x : v) if (x.getName().equals(chosen)) return Speaker.friendly(x);
            return chosen;
        }
        return v.isEmpty() ? "none installed" : "Auto (" + Speaker.friendly(v.get(0)) + ")";
    }

    private void pickVoice(String lang) {
        pickVoice(lang, false);
    }

    private String voice2Label(String lang) {
        String chosen = Prefs.voice2(this, lang);
        Voice v = Speaker.get(this).secondVoice(lang);
        if (v == null) return "none on the phone: the usual voice, lower";
        return (chosen.isEmpty() ? "Default (" : "") + Speaker.friendly(v) + (chosen.isEmpty() ? ")" : "");
    }

    /** second: the voice for Bible verses and a story's other point of view. */
    private void pickVoice(String lang, boolean second) {
        List<Voice> voices = Speaker.get(this).voicesFor(lang);
        if (voices.isEmpty()) {
            refresh();
            return;
        }
        final String sample = lang.equals(TextPrep.FIL)
                ? "Kumusta! Ito ang boses ko sa Tagalog. Genesis 1:1-5." : "Hello! This is my English voice. Genesis 1:1-5.";
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 14);
        list.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle((second ? "Second " : "") + (lang.equals(TextPrep.FIL) ? "Tagalog voice" : "English voice"))
                .setView(sv)
                .setNegativeButton("Close", null)
                .create();
        dlg.setOnDismissListener(d -> Speaker.get(this).stop());

        String current = second ? Prefs.voice2(this, lang) : Prefs.voice(this, lang);
        if (second) {
            Voice def = Speaker.get(this).secondVoice(lang);
            if (!current.isEmpty()) { // what the default would be, with nothing chosen
                Prefs.setVoice2(this, lang, "");
                def = Speaker.get(this).secondVoice(lang);
                Prefs.setVoice2(this, lang, current);
            }
            list.addView(voiceRow(lang.equals(TextPrep.FIL) ? "Default (Voice FIE)" : "Default (best man's voice)", current.isEmpty(),
                    def != null ? def : voices.get(0), sample, dlg, () -> Prefs.setVoice2(this, lang, "")));
        } else {
            list.addView(voiceRow("Auto (best female guess)", current.isEmpty(), voices.get(0), sample, dlg,
                    () -> Prefs.setVoice(this, lang, "")));
        }
        for (Voice v : voices) {
            String label = Speaker.friendly(v) + (v.isNetworkConnectionRequired() ? "  \u00B7 online, better quality" : "  \u00B7 offline");
            list.addView(voiceRow(label, v.getName().equals(current), v, sample, dlg,
                    () -> {
                        if (second) Prefs.setVoice2(this, lang, v.getName());
                        else Prefs.setVoice(this, lang, v.getName());
                    }));
        }
        dlg.show();
    }

    /** One line in the picker: tap the name to choose it, tap Play to hear it without choosing. */
    private View voiceRow(String label, boolean selected, Voice v, String sample, AlertDialog dlg, Runnable choose) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Theme.dp(this, 4), 0, Theme.dp(this, 4));

        TextView name = Theme.body(this, (selected ? "\u2713  " : "") + label, selected ? Theme.GOLD_LIGHT : Theme.INK, 14);
        name.setPadding(0, Theme.dp(this, 10), Theme.dp(this, 8), Theme.dp(this, 10));
        name.setOnClickListener(x -> {
            choose.run();
            dlg.dismiss();
            refresh();
        });
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button play = Theme.button(this, "\u25B6 Play", false);
        play.setMinHeight(Theme.dp(this, 40));
        play.setTextSize(13);
        play.setOnClickListener(x -> Speaker.get(this).preview(v, sample));
        row.addView(play, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }
}
