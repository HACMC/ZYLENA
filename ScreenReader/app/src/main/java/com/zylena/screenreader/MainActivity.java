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
    private Ui.Row netRow, dataRow, aliveRow, locateRow, fanRow, tuckRow;
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
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomBar());
        setContentView(root);

        content.addView(page(homePage()));
        content.addView(page(readingPage()));
        content.addView(page(voicePage()));
        content.addView(page(controlsPage()));
        showTab(0);

        if (Prefs.notificationControls(this)) askNotificationPermission();
        Prefs.applyLists(this);
        // Warm up the phone's voices so the pickers have something to show.
        Speaker.get(this).whenSystemReady(this::refresh);
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        if (tab != 0) showTab(0);
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
        Ui.add(g, markRow.view);
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
        col.addView(bible);

        col.addView(Ui.section(this, "Leave out"));
        LinearLayout skip = Ui.group(this);
        timeRow = Ui.toggle(this, R.drawable.ic_clock, "Skip timestamps", "\"2h ago\", \"10:42 PM\", \"Yesterday\"",
                on -> Prefs.setSkipTimestamps(this, on));
        Ui.add(skip, timeRow.view);
        buttonsRow = Ui.toggle(this, R.drawable.ic_touch, "Skip button names", "Buttons, icons and short labels like \"Close\"",
                on -> Prefs.setSkipButtons(this, on));
        Ui.add(skip, buttonsRow.view);
        skipRow = Ui.nav(this, R.drawable.ic_frame, "Words to skip", "", () -> editList(false));
        Ui.add(skip, skipRow.view);
        col.addView(skip);

        col.addView(Ui.section(this, "Pronunciation"));
        LinearLayout say = Ui.group(this);
        sayRow = Ui.nav(this, R.drawable.ic_translate, "My pronunciations", "", () -> editList(true));
        Ui.add(say, sayRow.view);
        col.addView(say);
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
        col.addView(g);
        warn = Theme.body(this, "", Theme.DANGER, 12.5f);
        warn.setPadding(Theme.dp(this, 6), Theme.dp(this, 8), Theme.dp(this, 6), 0);
        col.addView(warn);

        col.addView(Ui.section(this, "Internet"));
        LinearLayout n = Ui.group(this);
        netRow = Ui.toggle(this, R.drawable.ic_download, "Use internet when connected",
                "Better online voice and Translate online. Off, or no internet: everything stays on the phone.",
                on -> {
                    Prefs.setUseInternet(this, on);
                    refresh();
                });
        Ui.add(n, netRow.view);
        dataRow = new Ui.Row(this, R.drawable.ic_info, "Data used", "");
        Ui.add(n, dataRow.view);
        col.addView(n);

        col.addView(Ui.section(this, "Sound"));
        LinearLayout s = Ui.group(this);
        Ui.add(s, Ui.slider(this, R.drawable.ic_speed, "Speed", 0.6f, 1.8f, 0.1f, Prefs.rate(this), "%.1f\u00D7",
                v -> Prefs.setRate(this, v)));
        Ui.add(s, Ui.slider(this, R.drawable.ic_translate, "Tagalog speed", 0.8f, 1.2f, 0.05f, Prefs.rateFil(this), "%.2f\u00D7",
                v -> Prefs.setRateFil(this, v)));
        Ui.add(s, Ui.slider(this, R.drawable.ic_eq, "Pitch", 0.8f, 1.3f, 0.05f, Prefs.pitch(this), "%.2f",
                v -> Prefs.setPitch(this, v)));
        col.addView(s);

        col.addView(Ui.section(this, "Voice app"));
        LinearLayout a = Ui.group(this);
        appRow = Ui.nav(this, R.drawable.ic_apps, "Phone voice app", "", this::pickPhoneApp);
        Ui.add(a, appRow.view);
        Ui.add(a, Ui.nav(this, R.drawable.ic_download, "Download better voices", "English (US) and Filipino, high quality",
                this::openVoiceDownloads).view);
        col.addView(a);

        Button test = Theme.button(this, "\u25B6  Test the voice", true);
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
        locateRow = Ui.toggle(this, R.drawable.ic_frame, "Auto-locate",
                "Scrolled away while it reads? The highlighted paragraph comes back after 3 seconds", on -> Prefs.setAutoLocate(this, on));
        Ui.add(g, locateRow.view);
        sleepRow = Ui.nav(this, R.drawable.ic_timer, "Sleep timer", "", this::pickSleepTimer);
        Ui.add(g, sleepRow.view);
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
        set(aliveRow, Prefs.keepAlive(this));
        set(locateRow, Prefs.autoLocate(this));
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
        refsRow.setValue(mode.equals("skip") ? "Skip" : mode.equals("verses") ? "Verse text" : "Read");
        set(timeRow, Prefs.skipTimestamps(this));
        set(buttonsRow, Prefs.skipButtons(this));
        set(notifRow, Prefs.notificationControls(this));
        set(shakeRow, Prefs.shakeStop(this));
        int say = TextPrep.parseSay(Prefs.sayList(this)).size(), skip = TextPrep.parseSkip(Prefs.skipList(this)).size();
        sayRow.setSub(say == 0 ? "Teach it how to say a word" : say + (say == 1 ? " word" : " words"));
        skipRow.setSub(skip == 0 ? "Words or phrases never read" : skip + (skip == 1 ? " item" : " items"));

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
                .setTitle(lang.equals(TextPrep.FIL) ? "Tagalog voice" : "English voice")
                .setView(sv)
                .setNegativeButton("Close", null)
                .create();
        dlg.setOnDismissListener(d -> Speaker.get(this).stop());

        String current = Prefs.voice(this, lang);
        list.addView(voiceRow("Auto (best female guess)", current.isEmpty(), voices.get(0), sample, dlg,
                () -> Prefs.setVoice(this, lang, "")));
        for (Voice v : voices) {
            String label = Speaker.friendly(v) + (v.isNetworkConnectionRequired() ? "  \u00B7 online, better quality" : "  \u00B7 offline");
            list.addView(voiceRow(label, v.getName().equals(current), v, sample, dlg,
                    () -> Prefs.setVoice(this, lang, v.getName())));
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
