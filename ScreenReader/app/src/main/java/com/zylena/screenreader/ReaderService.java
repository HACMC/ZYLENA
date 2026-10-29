package com.zylena.screenreader;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import android.util.SparseArray;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * The part that sees the screen. Android only allows reading other apps' text through an accessibility
 * service, so this is what the user switches on in Settings. It draws a small movable button over
 * every app: tap it to hear the screen, tap again to stop.
 */
public class ReaderService extends AccessibilityService {
    static volatile ReaderService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private ImageView button;
    private WindowManager.LayoutParams buttonLp;
    private final Runnable autoRead = this::readScreen;
    private long lastAutoRead;

    // What is currently on screen, as read: raw lines and the speakable pieces made from them.
    private List<String> lines = new ArrayList<>();
    private List<TextPrep.Seg> segs = new ArrayList<>();
    private volatile String pkg = ""; // also set on the look thread
    private List<Rect> rects = new ArrayList<>();
    private boolean fromPicture; // the current lines came from Picture text, not the screen's own text
    private Controls controls;
    private View onScreenMark;
    private static VerseStore verses;
    /** Per app: the last line read on each screen or tab, newest first. Lets a revisited tab continue where it was. */
    private final Map<String, LinkedList<String>> marks = new HashMap<>();

    private LinearLayout panel;
    private ScrollView panelScroll;
    private final SparseArray<TextView> lineViews = new SparseArray<>();
    private int currentLine = -1;

    @Override
    protected void onServiceConnected() {
        instance = this;
        Logs.catchCrashes(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (look == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("zylena-look");
            t.start();
            look = new android.os.Handler(t.getLooper());
        }
        if (verses == null) verses = new VerseStore(this);
        Bible.source = verses;
        if (Prefs.readVerses(this)) verses.warmUp();
        Prefs.applyLists(this);
        controls = new Controls(this);
        Speaker.get(this).setListener(speaking -> {
            updateButtonIcon();
            if (controls != null) controls.update(null);
            if (!speaking) unmarkOnScreen();
        });
        Speaker.get(this).setProgress(new Speaker.Progress() {
            @Override
            public void onRun(int first, int last, float along) {
                markAlong = along;
                onProgress(first, last);
            }

            @Override
            public void onFinished() {
                onReadingFinished();
            }
        });
        Logs.toLogcat = Prefs.testMode(this);
        Logs.d(this, "service connected");
        Prefs.setServiceWasOn(this, true);
        applyKeepAlive();
        applyPrefs();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        int type = e.getEventType();
        // The page moved or another app or tab came up: move the outline with the text, or hide it.
        if (onScreenMark != null || markFirst >= 0) {
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                // At most every 120 ms, also while the page keeps moving (waiting for it to stop looked stuck).
                // A change in some other app (the status bar, say) says nothing about this page.
                boolean other = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && e.getPackageName() != null
                        && !e.getPackageName().toString().equals(pkg);
                if (!remarkPending && !other) {
                    remarkPending = true;
                    // On a big, busy screen finding the paragraph takes long, and doing it all the time kept the
                    // phone from answering taps ("isn't responding"): wait longer the longer the last look took.
                    long base = type == AccessibilityEvent.TYPE_VIEW_SCROLLED ? 40 : 120;
                    main.postDelayed(remark, Math.min(2000, base + 3 * remarkCost));
                }
            }
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!Prefs.autoRead(this)) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || pkg.toString().equals(getPackageName())) return;
        // Wait for the new screen to finish drawing, and don't chatter more than once every few seconds.
        main.removeCallbacks(autoRead);
        long wait = Math.max(900, 4000 - (System.currentTimeMillis() - lastAutoRead));
        main.postDelayed(autoRead, wait);
    }

    /** Headphone play/pause, next and previous control ZYLENA while it is reading or paused. */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        Speaker sp = Speaker.get(this);
        if (controls == null || (!sp.isSpeaking() && !sp.isPaused())) return false;
        String what;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                what = "toggle";
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                what = "next";
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                what = "back";
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                what = "stop";
                break;
            default:
                return false;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) controls.handle(what);
        return true;
    }

    @Override
    public void onInterrupt() {
        Speaker.get(this).stop();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Logs.d(this, "service unbound");
        hideBanner();
        hideFan();
        hidePanel();
        removeButton();
        unmarkOnScreen();
        Speaker.get(this).stop();
        if (controls != null) controls.release();
        controls = null;
        return super.onUnbind(intent);
    }

    private boolean keptAlive;

    /**
     * With "Stay-on notification": a quiet notification marks ZYLENA as in use, so phones that close
     * background apps (Xiaomi, Samsung, ...) are much less likely to stop it and take the button away.
     */
    void applyKeepAlive() {
        boolean want = Prefs.keepAlive(this);
        if (want == keptAlive) return;
        try {
            if (want) {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new android.app.NotificationChannel("ready", "ZYLENA is ready",
                        android.app.NotificationManager.IMPORTANCE_MIN));
                android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 7,
                        new android.content.Intent(this, MainActivity.class), android.app.PendingIntent.FLAG_IMMUTABLE);
                android.app.Notification n = new android.app.Notification.Builder(this, "ready")
                        .setSmallIcon(R.drawable.ic_speaker)
                        .setContentTitle("ZYLENA is ready")
                        .setContentText("Tap the gold button to read. This keeps the phone from closing ZYLENA.")
                        .setContentIntent(open)
                        .setOngoing(true)
                        .build();
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    startForeground(41, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(41, n);
                }
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE);
            }
            keptAlive = want;
            Logs.d(this, "stay-on notification " + (want ? "on" : "off"));
        } catch (Exception e) {
            Logs.d(this, "stay-on notification not possible: " + e);
        }
    }

    /** Call after a setting changes. */
    void applyPrefs() {
        applyKeepAlive();
        if (Prefs.floatingButton(this)) {
            showButton();
            if (tucked && !Prefs.tuck(this)) untuck();
            if (buttonLp != null && buttonLp.width != buttonPx()) resizeButton(); // e.g. after Restore defaults
        } else {
            removeButton();
        }
    }

    /** The screen's lines as language-tagged pieces; notes in the log why a long paragraph got the Tagalog voice. */
    private List<TextPrep.Seg> prep(List<String> ls) {
        TextPrep.lastWhy = "";
        List<TextPrep.Seg> out = TextPrep.prepare(ls, Prefs.skipTimestamps(this), Prefs.skipRefs(this), Prefs.readVerses(this));
        if (!TextPrep.lastWhy.isEmpty()) Logs.d(this, "read in Tagalog: " + TextPrep.lastWhy);
        return out;
    }

    void readScreenSoon(long delayMs) {
        main.postDelayed(this::readScreen, delayMs);
    }

    /*
     * Asking the app on screen about its text is a call into that app, and Android makes the caller wait for
     * the answer: seconds while the app is busy (just after a page was flicked, say). Asked on ZYLENA's main
     * thread, that wait kept it from answering taps, and Android said "ZYLENA isn't responding". So the
     * questions go to the look thread, one at a time, and the answer comes back here.
     */
    private interface Question<T> {
        T ask();
    }

    private <T> void ask(Question<T> q, java.util.function.Consumer<T> then) {
        if (look == null) {
            then.accept(q.ask());
            return;
        }
        look.post(() -> {
            T a = null;
            try {
                a = q.ask();
            } catch (RuntimeException e) {
                Logs.d(this, "screen question failed: " + e);
            }
            final T answer = a;
            main.post(() -> then.accept(answer));
        });
    }

    /** The screen's text as read on the look thread. */
    private static final class Snap {
        String pkg;
        List<String> lines;
        List<Rect> rects;
        List<String> skipped;
    }

    private Snap readSnap() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        Snap sn = new Snap();
        sn.pkg = String.valueOf(root.getPackageName());
        sn.lines = ScreenText.read(root, Prefs.wholePage(this), Prefs.skipButtons(this));
        sn.rects = ScreenText.lastRects;
        sn.skipped = ScreenText.lastSkipped;
        root.recycle();
        return sn;
    }

    /** Reads what is on screen now into lines/segs, asking on the look thread; then(false): no window to read. */
    private void snapshot(java.util.function.Consumer<Boolean> then) {
        ask(this::readSnap, sn -> then.accept(useSnap(sn)));
    }

    /** The same, waiting here for the answer (only where nothing else is going on). */
    private boolean snapshot() {
        return useSnap(readSnap());
    }

    private boolean useSnap(Snap sn) {
        if (sn == null) {
            Logs.d(this, "no active window to read");
            return false;
        }
        pkg = sn.pkg;
        lines = sn.lines;
        rects = sn.rects;
        fromPicture = false;
        segs = prep(lines);
        currentLine = -1;
        turnedFor = markedFirst = -1; // line numbers start over with every new read of the screen
        scanned = false;
        markedAlong = -1f;
        Logs.d(this, "screen " + pkg + ": " + lines.size() + " lines, " + segs.size() + " pieces, "
                + TextPrep.lastDropped + " timestamp lines skipped" + (TextPrep.lastVerseMode ? ", verse numbers skipped" : ""));
        if (Prefs.skipButtons(this) && !sn.skipped.isEmpty()) {
            Logs.d(this, "  left out as buttons: " + sn.skipped);
        }
        for (int i = 0; i < Math.min(6, lines.size()); i++) {
            String l = lines.get(i);
            String codes = "";
            if (!l.isEmpty() && !Character.isLetter(l.charAt(0))) {
                // What a verse number really is in this app (superscript? special space?), for fixing readers.
                StringBuilder c = new StringBuilder(" [");
                for (int k = 0; k < Math.min(4, l.length()); k++) c.append(k > 0 ? " " : "").append(Integer.toHexString(l.charAt(k)));
                codes = c.append("]").toString();
            }
            Logs.d(this, "  line " + i + ": " + Logs.snip(l) + codes);
        }
        return true;
    }

    /** Opens an app (e.g. Sabbath School) and reads its first screen once it has drawn. */
    void openAndRead(android.content.Intent launch) {
        startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        main.postDelayed(this::readScreen, 4500);
    }

    void readScreen() {
        readScreen(0);
    }

    private int readGen;

    private void readScreen(int tries) {
        int gen = tries == 0 ? ++readGen : readGen;
        pagesTurned = 0;
        lastAutoRead = System.currentTimeMillis();
        Speaker sp = Speaker.get(this);
        cancelScan();
        if (Prefs.wholePage(this)) {
            readWholePage(handled -> {
                if (!handled && gen == readGen) snapshot(ok -> {
                    if (gen == readGen) readScreenAsItIs(tries, ok);
                });
            });
        } else {
            snapshot(ok -> {
                if (gen == readGen) readScreenAsItIs(tries, ok);
            });
        }
    }

    private void readScreenAsItIs(int tries, boolean ok) {
        int gen = readGen;
        Speaker sp = Speaker.get(this);
        if (!ok || segs.isEmpty()) {
            // Some apps (EGW Writings) share a page's text only a moment after a reader first asks.
            if (tries < 3) {
                Logs.d(this, "no text yet, looking again");
                if (tries == 0) showBanner("Looking for the page's text\u2026");
                main.postDelayed(() -> {
                    if (gen == readGen && !sp.isSpeaking() && !scanning) readScreen(tries + 1);
                }, 700);
                return;
            }
            hideBanner();
            if (PictureText.supported()) readPictureText();
            else sp.speakText("I can't find any text on this screen. It may be a picture or a video.");
            return;
        }
        withPictureWords(() -> {
            int start = resumeLine();
            if (start > 0) Logs.d(this, "resuming at line " + start + ": " + Logs.snip(lines.get(start)));
            speakFrom(start);
        });
    }

    private int pictureGen;

    /**
     * When the screen shows pictures, the words inside them are read too, each in its place among the
     * screen's own text (a poster between two paragraphs is read between them). Then go runs.
     */
    private void withPictureWords(Runnable go) {
        DisplayMetrics real = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        ask(() -> PictureText.hasPictures(getRootInActiveWindow(), real.widthPixels, real.heightPixels), has -> {
            if (has == null || !has) go.run();
            else withPictureWords(go, real);
        });
    }

    private void withPictureWords(Runnable go, DisplayMetrics real) {
        final int gen = ++pictureGen;
        final List<String> screenLines = lines;
        final List<Rect> screenRects = rects;
        Logs.d(this, "pictures on screen: looking for words in them");
        PictureText.read(this, (found, where, error) -> {
            if (gen != pictureGen || lines != screenLines) return; // stopped or read again meanwhile
            if (error == null && !found.isEmpty()) {
                PictureText.keepContent(getRootInActiveWindow(), found, where, real.widthPixels, real.heightPixels,
                        systemDimen("status_bar_height", 24), systemDimen("navigation_bar_height", 48), true);
            }
            if (error != null || found.isEmpty()) {
                Logs.d(this, "pictures: " + (error != null ? error : "no words in them"));
                go.run();
                return;
            }
            List<String> merged = new ArrayList<>(screenLines);
            List<Rect> mergedRects = new ArrayList<>();
            for (int i = 0; i < merged.size(); i++) mergedRects.add(i < screenRects.size() ? screenRects.get(i) : new Rect());
            for (int k = 0; k < found.size(); k++) {
                Rect r = where.get(k);
                int at = merged.size();
                for (int i = 0; i < merged.size(); i++) {
                    Rect o = mergedRects.get(i);
                    if (!o.isEmpty() && o.top > r.top) {
                        at = i;
                        break;
                    }
                }
                merged.add(at, found.get(k));
                mergedRects.add(at, r);
            }
            lines = merged;
            rects = mergedRects;
            fromPicture = true; // outline from the saved positions, which cover the picture's words too
            segs = prep(lines);
            Logs.d(this, "pictures: added " + found.size() + " blocks of words in their place");
            go.run();
        });
    }

    /** Reads the words inside pictures on screen (photos, posters, screenshots) with on-device text recognition. */
    void readPictureText() {
        hidePanel();
        Speaker.get(this).stop();
        Logs.d(this, "reading text in the picture");
        // Let the panel disappear before the screenshot.
        main.postDelayed(() -> PictureText.read(this, (found, where, error) -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (error == null && !found.isEmpty()) {
                DisplayMetrics real = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(real);
                int before = found.size();
                PictureText.keepContent(root, found, where, real.widthPixels, real.heightPixels,
                        systemDimen("status_bar_height", 24), systemDimen("navigation_bar_height", 48), false);
                Logs.d(this, "picture text: kept " + found.size() + " of " + before + " blocks (names, times and labels left out)");
            }
            if (error != null || found.isEmpty()) {
                Logs.d(this, "picture text: " + (error != null ? error : "none found"));
                Speaker.get(this).speakText(error != null ? error : "I can't find any text on this screen, even in the pictures.");
                return;
            }
            if (root != null) pkg = String.valueOf(root.getPackageName());
            lines = found;
            rects = where;
            fromPicture = true;
            segs = prep(lines);
            Logs.d(this, "picture text: " + lines.size() + " blocks");
            speakFrom(0);
        }), 300);
    }

    /** Saves the screen's text as a spoken audio file in Downloads. */
    private void saveAudio() {
        hidePanel();
        if (lines.isEmpty() && !snapshot()) return;
        Speaker.get(this).saveAudio(segs);
    }

    // --------------------------------------------- highlight on the screen

    private int markFirst = -1, markLast = -1;
    private int markDx, markDy; // how far this phone shifts overlay windows; measured once, then corrected
    private boolean markMeasured;
    private long lastSwipe;

    /**
     * Turns the page like a reader would: when the paragraph about to be read is cut off at the bottom
     * (or too tall to fit), the page moves up so that paragraph's first line becomes the new top.
     * Only ever forward, so reading never jumps back. The swipe runs down the empty left margin
     * (touching words would select them) and holds still before lifting, so the page doesn't slide on.
     */
    /**
     * The part of the page where text can really be read: the scrolling area, minus the app's title bar
     * when the page runs underneath it (text under the bar counts as "on screen" but can't be seen).
     */
    private Rect readArea(AccessibilityNodeInfo root) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect view = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        AccessibilityNodeInfo list = root == null ? null : pageList(root);
        if (list == null) return view;
        pageBounds(list, view);
        return visibleArea(root, list, view);
    }

    private int turnedFor = -1;
    private int markedFirst = -1;
    private float markAlong, markedAlong = -1f;
    private Rect markArea = new Rect(); // the paragraph the page was last turned for: once per paragraph

    /**
     * The whole paragraph being read stays visible. Cut off at the bottom: the page moves up so it starts
     * at the top. Cut off at the top: the page moves down to show its start. Taller than the screen: the
     * page follows the voice, showing the part being read, but never past the paragraph's end.
     * Checked when a paragraph (or a part of a long one) starts, not continuously.
     */
    private int checksFor = -1, checks;

    private void keepInView(AccessibilityNodeInfo root, Rect para, int paragraph) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        if (paragraph != checksFor) {
            checksFor = paragraph;
            checks = 0;
        }
        if (++checks > 5) return; // give up on this paragraph rather than keep moving the page
        if (System.currentTimeMillis() - lastSwipe < 800) {
            // The page is still moving from the last scroll: look again once it has settled.
            main.postDelayed(() -> {
                markedFirst = -1;
                if (Speaker.get(this).isSpeaking() && markFirst >= 0) markOnScreen(markFirst, markLast);
            }, 850);
            return;
        }
        AccessibilityNodeInfo list = pageList(root);
        if (list == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect area = readArea(root);
        int top = area.top + Theme.dp(this, 10);
        int bottom = Math.min(area.bottom, dm.heightPixels) - Theme.dp(this, 10);
        int room = bottom - top;
        if (room <= 0) return;
        Rect listBox = new Rect();
        list.getBoundsInScreen(listBox);
        // Android reports only the visible part of a paragraph: one cut off at the top seems to start
        // exactly at the top edge of the list, so how much is hidden is unknown. Then scroll down in
        // steps and look again until its first line shows.
        boolean hiddenStart = markAlong == 0f && para.top <= Math.max(listBox.top, 0) + Theme.dp(this, 4)
                && para.top < top;
        int shift; // > 0 moves the text up, < 0 down
        if (hiddenStart) {
            shift = -room / 2;
        } else if (para.height() <= room) {
            if (para.top >= top && para.bottom <= bottom) return; // all of it is visible
            shift = para.top - top; // put its first line at the top: all of it fits
        } else if (markAlong == 0f) {
            if (para.top >= top && para.top <= top + Theme.dp(this, 24)) return; // starts at the top: fine
            shift = para.top - top; // a tall paragraph: begin with its first line at the top
        } else {
            // Further into a tall paragraph: show the part being read, but not beyond its end.
            int reading = para.top + (int) (markAlong * para.height()) - Theme.dp(this, 8);
            shift = Math.min(reading - top, para.bottom - bottom);
            if (shift <= 0) return;
        }
        int max = (int) (room * 0.9f);
        shift = Math.max(-max, Math.min(max, shift));
        if (Math.abs(shift) < Theme.dp(this, 24)) return;

        // Down the empty left margin (touching words selects them); hold still before lifting so it stops.
        float x = swipeX(area);
        float startY = shift > 0 ? bottom - Theme.dp(this, 6) : top + Theme.dp(this, 6);
        float endY = startY - shift;
        android.graphics.Path move = new android.graphics.Path();
        move.moveTo(x, startY);
        move.lineTo(x, endY);
        long ms = Math.max(300, Math.min(700, Math.abs(shift)));
        final android.accessibilityservice.GestureDescription.StrokeDescription drag =
                new android.accessibilityservice.GestureDescription.StrokeDescription(move, 0, ms, true);
        lastSwipe = System.currentTimeMillis();
        turnedFor = paragraph;
        boolean ok = dispatchGesture(new android.accessibilityservice.GestureDescription.Builder().addStroke(drag).build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(android.accessibilityservice.GestureDescription g) {
                        android.graphics.Path hold = new android.graphics.Path();
                        hold.moveTo(x, endY);
                        hold.lineTo(x, endY + 1);
                        dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                                .addStroke(drag.continueStroke(hold, 0, 180, false)).build(), null, null);
                    }
                }, null);
        Logs.d(this, "keeping the whole paragraph visible: scroll " + shift + " px" + (hiddenStart ? " (start hidden, stepping)" : "")
                + (ok ? "" : ", refused"));
        // Look again once the page has stopped: a hidden start may need another step.
        main.postDelayed(() -> {
            if (Speaker.get(this).isSpeaking() && markFirst == paragraph) {
                markedFirst = -1;
                markOnScreen(markFirst, markLast);
            }
        }, 1000);
    }

    private boolean remarkPending;
    private List<AccessibilityNodeInfo> markNodes = new ArrayList<>();
    private java.util.Set<String> markWant = new java.util.HashSet<>();
    private final Rect lastMark = new Rect();

    /** Where the paragraph being read is right now (empty if it has scrolled away). */
    private Rect liveBounds() {
        return liveBounds(markNodes, markWant);
    }

    /** Asks the app where these nodes are now. Can take long while the app is busy: see follow. */
    private Rect liveBounds(List<AccessibilityNodeInfo> nodes, java.util.Set<String> want) {
        Rect all = new Rect(), one = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            // Lists reuse rows for other text when scrolling; only count the node while it still shows ours.
            if (!n.refresh() || !n.isVisibleToUser() || !want.contains(ScreenText.ownText(n))) continue;
            n.getBoundsInScreen(one);
            // The same text on a neighbouring page (to the side) or far below the screen is not this one.
            DisplayMetrics dm = getResources().getDisplayMetrics();
            if (one.right <= 0 || one.left >= dm.widthPixels || one.top >= dm.heightPixels) continue;
            if (!one.isEmpty()) all.union(one);
        }
        return all;
    }

    // Follows the paragraph frame by frame while reading, so the outline stays on the text while scrolling.
    private boolean tracking;
    private int idleFrames;
    private long lastFollow;
    private boolean looking; // a look is under way on the look thread
    private android.os.Handler look; // a thread of its own for asking the app where the text is
    private final android.view.Choreographer.FrameCallback follow = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long t) {
            if (!tracking) return;
            // A moment's silence between two pieces of speech is no reason to stop following the text.
            idleFrames = Speaker.get(ReaderService.this).isSpeaking() ? 0 : idleFrames + 1;
            if (markNodes.isEmpty() || idleFrames > 90) { // ~1.5 s
                tracking = false;
                return;
            }
            // About every 50 ms (each look asks the app): close enough to stay on the moving text, and it leaves
            // time for everything else, which on a slow phone was held up by a look every frame.
            if (t - lastFollow >= 45_000_000L && !looking) {
                lastFollow = t;
                if (look == null) {
                    Rect r = liveBounds();
                    if (!r.equals(lastMark)) placeMark(r);
                } else {
                    // Asked on the side: while the app is busy moving the page an answer can take seconds,
                    // and waiting here froze the outline and everything else until it came.
                    final List<AccessibilityNodeInfo> nodes = markNodes;
                    final java.util.Set<String> want = markWant;
                    looking = true;
                    look.post(() -> {
                        Rect r;
                        try {
                            r = liveBounds(nodes, want);
                        } catch (RuntimeException e) {
                            r = null; // the nodes were let go meanwhile
                        }
                        final Rect found = r;
                        main.post(() -> {
                            looking = false;
                            if (found != null && tracking && nodes == markNodes && !found.equals(lastMark)) placeMark(found);
                        });
                    });
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startTracking() {
        if (tracking) return;
        tracking = true;
        android.view.Choreographer.getInstance().postFrameCallback(follow);
    }

    private void releaseMarkNodes() {
        final List<AccessibilityNodeInfo> old = markNodes;
        markNodes = new ArrayList<>();
        // Let go of them on the look thread, after any look that may still be using them.
        if (look != null) look.post(() -> {
            for (AccessibilityNodeInfo n : old) n.recycle();
        });
        else for (AccessibilityNodeInfo n : old) n.recycle();
    }

    /** Moves the outline to r, hiding it while the text is off screen. */
    private void placeMark(Rect r) {
        lastMark.set(r);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect v = new Rect(r);
        Rect clip = markArea.isEmpty() ? new Rect(0, 0, dm.widthPixels, dm.heightPixels) : markArea;
        if (v.isEmpty() || !v.intersect(clip)) {
            if (onScreenMark != null) onScreenMark.setVisibility(View.INVISIBLE);
            logMark(new Rect());
            return;
        }
        if (onScreenMark == null || markLp == null) return;
        logMark(v);
        int pad = Theme.dp(this, 4);
        markLp.width = v.width() + 2 * pad;
        markLp.height = v.height() + 2 * pad;
        markLp.x = v.left - pad - markDx;
        markLp.y = v.top - pad - markDy;
        onScreenMark.setVisibility(View.VISIBLE);
        try {
            wm.updateViewLayout(onScreenMark, markLp);
        } catch (Exception ignored) {
        }
    }
    private WindowManager.LayoutParams markLp;
    /** remark, reading the page afresh: Android's copy of it may still hold where the text was before a swipe. */
    private int freshWaits;
    private final Runnable remarkFresh = () -> {
        // (Not in a moment's silence between pieces: that would take the outline away. Try again shortly.)
        Speaker sp = Speaker.get(this);
        if (markFirst < 0 || !sp.isSpeaking()) {
            if (sp.isPaused() || freshWaits >= 8) return;
            freshWaits++;
            Logs.t(this, "outline refresh waits: " + (markFirst < 0 ? "no paragraph yet" : "between pieces"));
            main.postDelayed(this.remarkFresh, 120);
            return;
        }
        freshWaits = 0;
        if (android.os.Build.VERSION.SDK_INT >= 33) clearCache();
        this.remark.run();
    };

    private int settleGen, settleTicks, settleSame, settleY;

    /**
     * After a swipe: reads the page afresh and moves the outline every 150 ms until it has stayed in the
     * same place for 600 ms (the page has stopped), for up to 4.5 s.
     */
    private void settleOutline() {
        final int gen = ++settleGen;
        settleTicks = 0;
        settleSame = 0;
        settleY = Integer.MIN_VALUE;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != settleGen || markFirst < 0 || Speaker.get(ReaderService.this).isPaused()) return;
                if (Speaker.get(ReaderService.this).isSpeaking()) {
                    // The follow asks the app on its own thread; keep it going and watch where the outline is.
                    if (markNodes.isEmpty()) markOnScreen(markFirst, markLast);
                    else startTracking();
                    int y = lastMark.isEmpty() ? Integer.MIN_VALUE : lastMark.top;
                    settleSame = y == settleY ? settleSame + 1 : 0;
                    settleY = y;
                }
                if (++settleTicks >= 30 || (settleSame >= 4 && settleTicks >= 8)) {
                    Logs.t(ReaderService.this, "outline settled after " + settleTicks + " looks");
                    return;
                }
                main.postDelayed(this, 150);
            }
        }, 60);
    }

    private volatile long remarkCost; // how long the last look for the paragraph took (on the look thread)
    private boolean slowNoted;

    private final Runnable remark = () -> {
        remarkPending = false;
        if (remarkCost > 400 && !slowNoted) {
            slowNoted = true;
            Logs.d(this, "finding the paragraph is slow here (" + remarkCost + " ms): looking less often");
        }
        if (markFirst >= 0 && Speaker.get(this).isSpeaking()) markOnScreen(markFirst, markLast);
        else unmarkOnScreen();
    };

    /**
     * A soft gold outline around the paragraph being read, over the app itself. The paragraph is looked up
     * where it is now (the page may have scrolled since it was read in), and the outline goes away when
     * that text isn't on screen, e.g. in another app or tab.
     */
    /** What a look for the paragraph found, on the look thread. */
    private static final class MarkLook {
        boolean samePage;
        Rect r = new Rect();
        Rect area = new Rect();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        java.util.Set<String> want = new java.util.HashSet<>();
        Boolean shown; // asked the app to bring it into view: did it?
    }

    private int markGen;
    private boolean markBusy, markAgain;

    private void markOnScreen(int first, int last) {
        markFirst = first;
        markLast = last;
        boolean follow = Prefs.wholePage(this);
        if ((!Prefs.highlight(this) && !follow) || panel != null) {
            unmarkOnScreen();
            return;
        }
        // One look at a time: asked again meanwhile, look once more when this one is back.
        if (markBusy) {
            markAgain = true;
            return;
        }
        markBusy = true;
        final int g = ++markGen;
        final boolean picture = fromPicture, sc = scanned;
        final List<String> ls = lines;
        final List<Rect> rs = rects;
        final String p = pkg;
        // Only when a paragraph starts (not on every refresh while it is read): turning again
        // mid-paragraph pushed a long paragraph off the top.
        final boolean starts = first != markedFirst || markAlong != markedAlong;
        markedFirst = first;
        markedAlong = markAlong;
        ask(() -> {
            long t0 = android.os.SystemClock.uptimeMillis();
            MarkLook m = new MarkLook();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            m.samePage = root != null && String.valueOf(root.getPackageName()).equals(p);
            if (m.samePage && picture) {
                // Text found in a screenshot has no live position; use where it was in the picture.
                for (int i = first; i <= last && i < rs.size(); i++) if (!rs.get(i).isEmpty()) m.r.union(rs.get(i));
            } else if (m.samePage) {
                for (int i = first; i <= last && i < ls.size(); i++) m.want.add(ls.get(i));
                m.nodes = ScreenText.find(root, m.want);
                m.r = liveBounds(m.nodes, m.want);
                if (follow && !sc && !m.r.isEmpty() && starts) keepInView(root, m.r, first);
                m.area = readArea(root);
                // (Not for a scanned page: putInView swipes it there, and the app's own scroll on top of that overshot.)
                if (m.r.isEmpty() && !m.nodes.isEmpty() && !sc) {
                    // The paragraph is further down the page: scroll it into view, like turning the page.
                    m.shown = m.nodes.get(0).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                }
            }
            remarkCost = android.os.SystemClock.uptimeMillis() - t0;
            return m;
        }, m -> {
            markBusy = false;
            if (m != null && g == markGen) showMark(first, last, m, picture);
            if (markAgain) {
                markAgain = false;
                if (markFirst >= 0 && Speaker.get(this).isSpeaking()) markOnScreen(markFirst, markLast);
            }
        });
    }

    private void showMark(int first, int last, MarkLook m, boolean picture) {
        if (markFirst != first) { // reading moved on meanwhile
            if (look != null) look.post(() -> {
                for (AccessibilityNodeInfo n : m.nodes) n.recycle();
            });
            return;
        }
        final List<AccessibilityNodeInfo> old = markNodes;
        markNodes = new ArrayList<>();
        if (look != null) look.post(() -> {
            for (AccessibilityNodeInfo n : old) n.recycle();
        });
        Rect r = m.r;
        if (m.samePage && !picture) {
            markWant = m.want;
            markNodes = m.nodes;
            Logs.t(this, "paragraph " + first + " at " + r.toShortString());
            markArea = m.area;
            if (m.shown != null) {
                Logs.d(this, "bringing the paragraph into view: " + (m.shown ? "ok" : "not possible"));
                if (m.shown) {
                    main.postDelayed(remark, 350);
                    remarkPending = true;
                }
            }
            startTracking();
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect clip = picture || markArea.isEmpty() ? new Rect(0, 0, dm.widthPixels, dm.heightPixels) : markArea;
        if (r.isEmpty() || !r.intersect(clip)) {
            unmarkOnScreen();
            return;
        }
        if (!Prefs.highlight(this)) return; // only following the reading, no outline
        int pad = Theme.dp(this, 4);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width() + 2 * pad, r.height() + 2 * pad,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = r.left - pad - markDx;
        lp.y = r.top - pad - markDy;
        try {
            if (onScreenMark == null) {
                View v = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(Theme.dp(this, 8));
                d.setColor(0x1FF0D690);
                d.setStroke(Theme.dp(this, 2), 0xCCD6AD55);
                v.setBackground(d);
                wm.addView(v, lp);
                onScreenMark = v;
            } else {
                wm.updateViewLayout(onScreenMark, lp);
            }
            onScreenMark.setVisibility(View.VISIBLE);
            lastMark.set(r);
            markLp = lp;
            logMark(r);
            if (!markMeasured) measureMark();
        } catch (Exception e) {
            onScreenMark = null;
        }
    }

    private int calibTries, lastOffX = Integer.MIN_VALUE, lastOffY;

    /**
     * Some phones place overlays a little off (status bar, camera cutout). Measure where the outline
     * really landed, compared with where it was asked to go, and correct for it from now on. The page
     * may be moving meanwhile, so a value only counts when two looks agree and it is a small offset.
     */
    private void measureMark() {
        View v = onScreenMark;
        if (v == null || markMeasured || calibTries > 30) return;
        v.postDelayed(() -> {
            WindowManager.LayoutParams lp = markLp;
            if (v != onScreenMark || lp == null || !v.isAttachedToWindow() || markMeasured) return;
            int[] at = new int[2];
            v.getLocationOnScreen(at);
            int ox = at[0] - lp.x, oy = at[1] - lp.y;
            int limit = systemDimen("status_bar_height", 24) + Theme.dp(this, 16);
            calibTries++;
            if (Math.abs(ox) <= limit && Math.abs(oy) <= limit && ox == lastOffX && oy == lastOffY) {
                markDx = ox;
                markDy = oy;
                markMeasured = true;
                Logs.d(this, "outline offset " + markDx + "," + markDy);
                if (markDx != 0 || markDy != 0) markOnScreen(markFirst, markLast);
                return;
            }
            lastOffX = ox;
            lastOffY = oy;
            measureMark();
        }, 150);
    }

    private final Rect loggedMark = new Rect();

    /** For the emulator test: where the outline is (only when it moved). */
    private void logMark(Rect r) {
        if (!Logs.toLogcat || r.equals(loggedMark)) return;
        loggedMark.set(r);
        Logs.t(this, "mark " + (r.isEmpty() ? "hidden" : r.left + "," + r.top + "," + r.right + "," + r.bottom));
    }

    private void unmarkOnScreen() {
        if (onScreenMark != null) logMark(new Rect());
        if (onScreenMark == null) return;
        if (!Speaker.get(this).isSpeaking()) markFirst = markLast = -1;
        try {
            wm.removeView(onScreenMark);
        } catch (Exception ignored) {
        }
        onScreenMark = null;
        markLp = null;
        tracking = false;
        if (!Speaker.get(this).isSpeaking()) releaseMarkNodes();
    }


    // ------------------------------------------------- scan, then read the page

    /*
     * Android tells a screen reader only where the visible part of each paragraph is. So for a whole
     * page ZYLENA first scrolls through it once, top to bottom, and notes every paragraph's text and its
     * position on the whole page (each scroll's real distance is measured from paragraphs seen on
     * both screens). Back at the top it reads, and before each paragraph it knows exactly how far to
     * scroll so the whole paragraph is visible. At the end it turns to the next page and does the same;
     * with no next page it says so and stops.
     */

    private static final class Shot {
        final List<String> lines = new ArrayList<>(); // text inside the page's scrolling area
        final List<Rect> rects = new ArrayList<>();
        final Rect list = new Rect(); // the scrolling area on screen
        final Rect view = new Rect(); // the part of it where text can be seen: below a title bar, above the bottom
        int edge; // an edge this close to the view's border may be where the app clips the text, not its real end
        boolean atTop, atEnd; // can't scroll further up / down
        boolean noUp; // the app offers no "scroll up" right now (some apps never do)
    }

    private static final class Spot {
        int top = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE; // exact, once an edge has been seen
        int minTop = Integer.MAX_VALUE, maxBottom = Integer.MIN_VALUE; // what showed of it, when an edge never did
    }

    private boolean scanning, scanned;
    private int scanGen; // bumped to cancel a scan in progress
    private int showGen; // bumped when another paragraph is being put in view
    private final List<String> scanOrder = new ArrayList<>();
    private final Map<String, Spot> spots = new HashMap<>();
    private int scanY, scanSteps, scanStuck, knownY;
    private String scanPkg = "";
    private volatile AccessibilityNodeInfo lastList;
    private int pageEndY; // how far the scanned page scrolls
    private volatile boolean upSeen;
    private long scanTime;

    /** The page's main scrolling area, also when it is already at the bottom (then it can't scroll down). */
    private static AccessibilityNodeInfo pageList(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        java.util.ArrayDeque<AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        Rect r = new Rect();
        int visited = 0;
        while (!todo.isEmpty() && visited++ < 3000) {
            AccessibilityNodeInfo n = todo.poll();
            if (n.isVisibleToUser() && ScreenText.scrollsVertically(n) && (canScroll(n, true) || canScroll(n, false))) {
                n.getBoundsInScreen(r);
                long area = (long) r.width() * r.height();
                if (area > bestArea) {
                    bestArea = area;
                    best = n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        return best;
    }

    private static boolean canScroll(AccessibilityNodeInfo n, boolean down) {
        List<AccessibilityNodeInfo.AccessibilityAction> a = n.getActionList();
        return down ? a.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                || a.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
                : a.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                || a.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
    }

    /** The app's own one-screen scroll, as a reader's "page up" (quick, no swiping). */
    private static boolean scrollUp(AccessibilityNodeInfo list) {
        if (list.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)) {
            return list.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId());
        }
        return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    private int systemDimen(String name, int fallbackDp) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : Theme.dp(this, fallbackDp);
    }

    /**
     * Where text in the scrolling area can really be seen. Many apps run the page under a see-through
     * title bar; its buttons and title are not part of the page, so text above their lower edge is
     * hidden. Likewise a player or bar at the bottom, and the phone's own status and navigation bars.
     */
    private Rect visibleArea(AccessibilityNodeInfo root, AccessibilityNodeInfo list, Rect lr) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect v = new Rect(lr);
        Rect use = usableScreen();
        v.top = Math.max(v.top, use.top);
        v.bottom = Math.min(v.bottom, use.bottom);
        int barBottom = Integer.MIN_VALUE, bottomTop = Integer.MAX_VALUE;
        int topBand = lr.top + lr.height() * 3 / 10, bottomBand = lr.bottom - lr.height() * 3 / 10;
        java.util.ArrayDeque<AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        Rect b = new Rect();
        int visited = 0;
        while (!todo.isEmpty() && visited++ < 3000) {
            AccessibilityNodeInfo n = todo.poll();
            if (n.equals(list)) continue; // the page itself
            if (n.isVisibleToUser() && (!ScreenText.ownText(n).isEmpty() || n.isClickable())) {
                n.getBoundsInScreen(b);
                if (!b.isEmpty() && Rect.intersects(b, lr) && b.height() < lr.height() / 3) {
                    if (b.bottom <= topBand) barBottom = Math.max(barBottom, b.bottom);
                    else if (b.top >= bottomBand) bottomTop = Math.min(bottomTop, b.top);
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        // Bar buttons are a little shorter than the bar they sit in.
        if (barBottom != Integer.MIN_VALUE) v.top = Math.max(v.top, barBottom + Theme.dp(this, 8));
        if (bottomTop != Integer.MAX_VALUE) v.bottom = Math.min(v.bottom, bottomTop - Theme.dp(this, 4));
        if (v.height() < lr.height() / 3) { // something odd: better the whole area than almost nothing
            v.set(lr);
            v.bottom = Math.min(v.bottom, dm.heightPixels);
        }
        return v;
    }

    /**
     * The screen without the phone's status bar and navigation (or gesture) bar, in screen coordinates.
     * Pages often run underneath them, but text there can't be read.
     */
    private final Rect lastUsable = new Rect();

    private Rect usableScreen() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                // The whole display with its bars (the "current" size may already leave the bar out).
                android.view.WindowMetrics m = wm.getMaximumWindowMetrics();
                Rect b = m.getBounds();
                android.graphics.Insets in = m.getWindowInsets().getInsets(
                        android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                int most = Theme.dp(this, 64); // no bar is taller; anything more is a wrong answer
                Rect u = new Rect(b.left + Math.min(in.left, most), b.top + Math.min(in.top, most),
                        b.right - Math.min(in.right, most), b.bottom - Math.min(in.bottom, most));
                if (!u.equals(lastUsable)) {
                    lastUsable.set(u);
                    Logs.t(this, "screen " + b.toShortString() + ", bars " + in + ", usable " + u.toShortString());
                }
                if (u.height() > dm.heightPixels / 2) return u;
            } catch (Exception ignored) {
            }
        }
        // Older phones: the app area already ends above the navigation bar.
        return new Rect(0, systemDimen("status_bar_height", 24), dm.widthPixels, dm.heightPixels);
    }

    /**
     * Where the page's scrolling area is on screen. Some phones report it shorter than it is drawn
     * (the virtual test phone cut it 128 px above the gesture bar); its items show how far it reaches.
     */
    private void pageBounds(AccessibilityNodeInfo list, Rect out) {
        list.getBoundsInScreen(out);
        Rect c = new Rect();
        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo ch = list.getChild(i);
            if (ch == null) continue;
            if (ch.isVisibleToUser()) {
                ch.getBoundsInScreen(c);
                if (!c.isEmpty() && c.left < out.right && c.right > out.left) out.bottom = Math.max(out.bottom, c.bottom);
            }
            ch.recycle();
        }
        Rect use = usableScreen();
        out.intersect(0, 0, Math.max(use.right, out.left + 1), Math.max(use.bottom, out.top + 1));
    }

    /** A shot of the page, taken on the look thread; use gets it here. */
    private void shootThen(java.util.function.Consumer<Shot> use) {
        ask(this::shoot, use);
    }

    /** A shot of the page being read; null if another app or tab is in front (then nothing is swiped). */
    private Shot shoot() {
        // Read the page as it is now: Android may still hold the text from before the last scroll.
        if (android.os.Build.VERSION.SDK_INT >= 33) clearCache();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        if (!pkg.isEmpty() && !String.valueOf(root.getPackageName()).equals(pkg)) return null;
        // The scrolling area that holds the text on screen; else the biggest one.
        AccessibilityNodeInfo list = textScroller(root);
        if (list == null) list = pageList(root);
        // Some apps stop offering any scroll at all at the page's end; the page itself is still there.
        if (list == null && lastList != null && lastList.refresh() && lastList.isVisibleToUser()) list = lastList;
        if (list == null) return null;
        lastList = list;
        Shot sh = new Shot();
        pageBounds(list, sh.list);
        sh.view.set(visibleArea(root, list, sh.list));
        sh.edge = Theme.dp(this, 8);
        // Only the page's own text: a title bar repeating the heading, or its buttons, are not part of it.
        List<String> ls = ScreenText.read(list, false, Prefs.skipButtons(this));
        List<Rect> rs = ScreenText.lastRects;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < ls.size(); i++) {
            Rect r = rs.get(i);
            if (r.isEmpty() || !Rect.intersects(r, sh.list)) continue;
            if (!seen.add(key(ls.get(i)))) continue; // the same text twice on one screen can't be told apart
            sh.lines.add(ls.get(i));
            sh.rects.add(r);
        }
        sh.noUp = !canScroll(list, false);
        if (!sh.noUp) upSeen = true;
        // An app that never offers "scroll up" (EGW Writings) can't say it is at the top; the page not moving does.
        sh.atTop = sh.noUp && upSeen;
        sh.atEnd = !canScroll(list, true);
        return sh;
    }

    private static boolean topSeen(Shot sh, Rect r) {
        return r.top > sh.view.top + sh.edge && r.top < sh.view.bottom;
    }

    private static boolean bottomSeen(Shot sh, Rect r) {
        return r.bottom < sh.view.bottom - sh.edge && r.bottom > sh.view.top;
    }

    /** The first look at a page, asked on the look thread. */
    private static final class FirstLook {
        String pkg;
        Shot shot;
        int allChars;
    }

    /** Starts reading a scrolling page: scan it first, then read. handled(false): this screen doesn't scroll. */
    private void readWholePage(java.util.function.Consumer<Boolean> handled) {
        final int asked = readGen; // tapped again meanwhile: this answer is old
        ask(() -> {
            AccessibilityNodeInfo front = getRootInActiveWindow();
            if (front == null) return null;
            FirstLook f = new FirstLook();
            f.pkg = String.valueOf(front.getPackageName());
            if (!pkg.equals(f.pkg)) lastList = null;
            pkg = f.pkg;
            f.shot = shoot();
            if (f.shot != null && !f.shot.lines.isEmpty()) {
                for (String l : ScreenText.read(front, false, Prefs.skipButtons(this))) f.allChars += l.length();
            }
            return f;
        }, f -> handled.accept(f != null && asked == readGen && startWholePage(f)));
    }

    private boolean startWholePage(FirstLook f) {
        Shot first = f.shot;
        if (first == null || first.lines.isEmpty()) {
            Logs.d(this, "whole page: no scrolling page here, reading the screen as it is");
            return false;
        }
        Logs.t(this, "page area " + first.list.toShortString() + ", readable " + first.view.toShortString());
        if (first.noUp && first.atEnd) return false; // all of it is on screen already
        // The page must hold most of the screen's words; otherwise read the screen as it is.
        int inPage = 0, all = f.allChars;
        for (String l : first.lines) inPage += l.length();
        if (inPage * 10 < all * 6) {
            Logs.d(this, "whole page: most of the text is outside the scrolling part, reading the screen as it is");
            return false;
        }
        final int gen = ++scanGen;
        if (alreadyScanned(first)) {
            Logs.d(this, "whole page: this page was scanned already, reading");
            startScannedReading(gen, false);
            return true;
        }
        scanning = true;
        scanned = false;
        scanOrder.clear();
        spots.clear();
        scanY = 0;
        knownY = 0;
        pageEndY = 0;
        upSeen = false;
        scanSteps = 0;
        scanStuck = 0;
        Logs.d(this, "whole page: scanning before reading");
        updateButtonIcon();
        showBanner("Scanning the page\u2026 please wait");
        toTop(gen, 0, () -> scanStep(gen, null));
        return true;
    }

    /** Same app, and every paragraph on screen is one the last scan found: no need to scan again. */
    private boolean alreadyScanned(Shot sh) {
        if (spots.isEmpty() || !pkg.equals(scanPkg) || System.currentTimeMillis() - scanTime > 30 * 60_000L) return false;
        int known = 0;
        for (String l : sh.lines) {
            if (spots.containsKey(key(l))) known++;
            else if (l.length() >= 20) return false;
        }
        return known > 0;
    }

    private void cancelScan() {
        scanGen++;
        showGen++;
        if (scanning) Logs.d(this, "whole page: scan stopped");
        scanning = false;
        hideBanner();
        updateButtonIcon();
    }

    /**
     * Back to the top of the page with the app's own "scroll up", in quick succession: a new one before
     * the last has finished sliding makes most apps jump instead, so the top comes almost at once.
     * Then a look makes sure it really is the top before going on.
     */
    private void toTop(int gen, int n, Runnable then) {
        toTop(gen, n, 0, then);
    }

    private void toTop(int gen, int n, int refused, Runnable then) {
        if (gen != scanGen) return;
        // 0: can't scroll up (or no page), 1: scrolled up, 2: the app refused this one
        ask(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 33) clearCache();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            // The area that holds the text on screen; the biggest scrolling area may be another one (EGW).
            AccessibilityNodeInfo list = root == null ? null : textScroller(root);
            if (list == null && root != null) list = pageList(root);
            if (n == 0 && list != null) Logs.d(this, "whole page: going up in " + describe(list));
            if (list == null || !canScroll(list, false) || n >= 200) return 0;
            return scrollUp(list) ? 1 : 2;
        }, r -> toTopNext(gen, n, refused, r == null ? 0 : r, then));
    }

    /** Scroll-ups on the area that holds the text on screen until it can't go further. */
    private void upOnText(int gen, int n, int refused, Runnable then) {
        if (gen != scanGen) return;
        ask(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo t = root == null ? null : textScroller(root);
            if (t == null || !canScroll(t, false) || n >= 200) return 0;
            return scrollUp(t) ? 1 : 2;
        }, r -> {
            if (gen != scanGen) return;
            int res = r == null ? 0 : r;
            if (res == 1) main.postDelayed(() -> upOnText(gen, n + 1, 0, then), 40);
            else if (res == 2 && refused < 4) main.postDelayed(() -> upOnText(gen, n + 1, refused + 1, then), 150);
            else {
                Logs.d(this, "whole page: " + n + " scroll-ups on the text's own area");
                main.postDelayed(then, 300);
            }
        });
    }

    /**
     * The scrolling area that holds the text being read: the nearest scrolling parent of a paragraph on
     * screen. (The biggest scrolling area can be another one: in EGW Writings the book's pages sit side by
     * side, and scrolling the biggest one moved nothing that could be seen.)
     */
    private AccessibilityNodeInfo textScroller(AccessibilityNodeInfo root) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        java.util.ArrayDeque<AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        Rect b = new Rect();
        int visited = 0;
        while (!todo.isEmpty() && visited++ < 3000) {
            AccessibilityNodeInfo n = todo.poll();
            if (n.isVisibleToUser() && ScreenText.ownText(n).length() >= 20) {
                n.getBoundsInScreen(b);
                if (b.left >= 0 && b.right <= dm.widthPixels && b.bottom > 0 && b.top < dm.heightPixels) {
                    AccessibilityNodeInfo p = n.getParent();
                    for (int up = 0; p != null && up < 30; up++) {
                        if (ScreenText.scrollsVertically(p) && (canScroll(p, false) || canScroll(p, true))) return p;
                        p = p.getParent();
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        return null;
    }

    private String describe(AccessibilityNodeInfo n) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        String id = n.getViewIdResourceName();
        return n.getClassName() + (id == null ? "" : " " + id.replaceAll(".*/", "")) + " " + b.toShortString()
                + (canScroll(n, false) ? " up" : "") + (canScroll(n, true) ? " down" : "") + (n.isVisibleToUser() ? "" : " hidden");
    }

    /** Every scrolling area on screen, for the log. */
    private String describeScrollers(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        java.util.ArrayDeque<AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        int visited = 0, found = 0;
        while (!todo.isEmpty() && visited++ < 3000 && found < 10) {
            AccessibilityNodeInfo n = todo.poll();
            if (n.isScrollable()) {
                found++;
                sb.append(found == 1 ? "" : "; ").append(describe(n));
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        AccessibilityNodeInfo t = textScroller(root), p = pageList(root);
        sb.append(" | text's own: ").append(t == null ? "none" : describe(t)).append(" | biggest: ").append(p == null ? "none" : describe(p));
        return sb.toString();
    }

    private void toTopNext(int gen, int n, int refused, int result, Runnable then) {
        if (gen != scanGen) return;
        boolean more = result != 0;
        if (result == 2) {
            // Busy with the last one: that is not the top. Try again a moment later, a few times.
            if (refused < 4) {
                main.postDelayed(() -> toTop(gen, n + 1, refused + 1, then), 150);
                return;
            }
            more = false;
        }
        if (!more) {
            if (n == 0) {
                swipeToTop(gen, 0, 0, null, () -> checkTop(gen, 0, then)); // no quick "scroll up" here: flick back by finger
                return;
            }
            Logs.d(this, "whole page: back at the top after " + n + " scroll-ups");
            // The app's scroll-up stops at the text's top; a header above it that folds away (EGW Writings'
            // book title) only opens again when the finger pulls the page down, so flick once more.
            main.postDelayed(() -> swipeToTop(gen, 0, 0, null, () -> checkTop(gen, 0, then)), 250);
            return;
        }
        main.postDelayed(() -> toTop(gen, n + 1, 0, then), 40);
    }

    /**
     * Flicks the page down until it stops moving: the top. (Overshooting doesn't matter there.) It must stay
     * still twice in a row: an app can ignore a single flick, and the page then looks as if it were at the top.
     */
    private void swipeToTop(int gen, int n, int still, Shot prev, Runnable then) {
        if (gen != scanGen) return;
        shootThen(sh -> swipeToTop(gen, n, still, prev, sh, then));
    }

    private void swipeToTop(int gen, int n, int still, Shot prev, Shot sh, Runnable then) {
        if (gen != scanGen) return;
        // ("Can't scroll up" isn't trusted here: EGW Writings stops offering it far from the top.)
        boolean top = sh == null || n >= 40;
        int nowStill = still;
        if (!top && prev != null) {
            Integer moved = movedBy(prev, sh);
            nowStill = moved != null && Math.abs(moved) < Theme.dp(this, 4) ? still + 1 : 0;
            top = nowStill >= 2;
        }
        if (top) {
            if (n > 0) Logs.d(this, "whole page: back at the top after " + n + " swipes");
            main.postDelayed(() -> {
                if (gen == scanGen) then.run();
            }, n == 0 ? 0 : 150);
            return;
        }
        final int s2 = nowStill;
        flick(sh, () -> main.postDelayed(() -> swipeToTop(gen, n + 1, s2, sh, then), 350));
    }

    /**
     * Before reading from the top: where the scan says the page is now. Not at the top (the app stopped
     * short), then flick on up, twice at most.
     */
    private void checkTop(int gen, int tries, Runnable then) {
        if (gen != scanGen) return;
        if (spots.isEmpty()) {
            checkTop(gen, tries, null, then);
            return;
        }
        shootThen(sh -> checkTop(gen, tries, sh, then));
    }

    private void checkTop(int gen, int tries, Shot sh, Runnable then) {
        if (gen != scanGen) return;
        Integer y = sh == null ? null : pageY(sh);
        if (y != null && y > Theme.dp(this, 24) && tries < 3) {
            Logs.d(this, "whole page: not at the top yet (" + y + " px down), going on up");
            if (tries == 0) {
                // What scrolls on this screen, for the log, then scroll-ups on the area that holds the text.
                ask(() -> {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    return root == null ? "no window" : describeScrollers(root);
                }, d -> Logs.d(this, "whole page: scrolling areas here: " + d));
                upOnText(gen, 0, 0, () -> checkTop(gen, tries + 1, then));
            } else {
                swipeToTop(gen, 0, 0, null, () -> checkTop(gen, tries + 1, then));
            }
            return;
        }
        knownY = y != null ? y : 0;
        then.run();
    }

    private void scanStep(int gen, Shot prev) {
        scanStep(gen, prev, null, 0);
    }

    /**
     * One scan step. The page is measured once two looks in a row agree: at the page's end it stretches and
     * springs back after the swipe, and a look during that made the page seem hundreds of pixels longer.
     */
    private void scanStep(int gen, Shot prev, Shot before, int looks) {
        if (gen != scanGen) return;
        // With no shot: is the same app still in front (then it stopped answering) or another one?
        ask(() -> {
            Shot s1 = shoot();
            if (s1 != null) return new Object[]{s1, false};
            AccessibilityNodeInfo r = getRootInActiveWindow();
            return new Object[]{null, r != null && String.valueOf(r.getPackageName()).equals(pkg)};
        }, a -> scanStep(gen, prev, before, looks, a == null ? null : (Shot) a[0], a != null && (Boolean) a[1]));
    }

    private void scanStep(int gen, Shot prev, Shot before, int looks, Shot sh, boolean samePage) {
        if (gen != scanGen) return;
        if (prev != null && sh != null && looks < 6 && (before == null || !sh.lines.equals(before.lines) || !sh.rects.equals(before.rects))) {
            main.postDelayed(() -> scanStep(gen, prev, sh, looks + 1), 90);
            return;
        }
        if (sh == null) {
            if (samePage && !scanOrder.isEmpty()) {
                Logs.d(this, "whole page: the page stopped answering, reading what was found");
                endScan(gen);
            } else {
                Logs.d(this, "whole page: another app or screen came up");
                cancelScan();
            }
            return;
        }
        if (prev != null) {
            Integer moved = movedBy(prev, sh);
            if (moved == null) {
                // Nothing seen on both screens: the swipe's length, less the bit a finger moves before the page follows.
                moved = lastScrollAsked;
                Logs.d(this, "whole page: step " + scanSteps + " not measured, taking " + moved + " px");
            }
            scanY += moved;
            scanStuck = Math.abs(moved) < Theme.dp(this, 4) ? scanStuck + 1 : 0;
        }
        note(sh);
        scanSteps++;
        showBanner("Scanning the page\u2026 " + scanOrder.size() + " paragraphs so far");
        if (sh.atEnd || scanStuck >= 2 || scanSteps > 80) {
            endScan(gen);
            return;
        }
        // Overlapping steps, so each move can be measured from text seen before and after it.
        int step = (int) (sh.view.height() * 0.7f);
        quickSwipe(sh, step, () -> main.postDelayed(() -> scanStep(gen, sh), 120));
    }

    private void endScan(int gen) {
        Logs.d(this, "whole page: " + scanOrder.size() + " paragraphs, " + scanY + " px long, " + scanSteps + " steps");
        scanPkg = pkg;
        knownY = scanY; // where the page is now, if the next screen can't be measured
        pageEndY = scanY;
        scanTime = System.currentTimeMillis();
        startScannedReading(gen, true);
    }

    private int lastScrollAsked;

    /** How far the page really moved between two shots, from paragraphs whose edge shows on both. */
    private Integer movedBy(Shot a, Shot b) {
        List<Integer> d = new ArrayList<>();
        for (int i = 0; i < b.lines.size(); i++) {
            int j = a.lines.indexOf(b.lines.get(i));
            if (j < 0) continue;
            Rect ra = a.rects.get(j), rb = b.rects.get(i);
            if (topSeen(a, ra) && topSeen(b, rb)) d.add(ra.top - rb.top);
            else if (bottomSeen(a, ra) && bottomSeen(b, rb)) d.add(ra.bottom - rb.bottom);
        }
        if (d.isEmpty()) return null;
        java.util.Collections.sort(d);
        return d.get(d.size() / 2); // the middle one: a paragraph that moved on its own doesn't count
    }

    /** Records where each paragraph on screen sits on the whole page. */
    private void note(Shot sh) {
        for (int i = 0; i < sh.lines.size(); i++) {
            Rect r = sh.rects.get(i);
            String k = key(sh.lines.get(i));
            Spot sp = spots.get(k);
            if (sp == null) {
                sp = new Spot();
                spots.put(k, sp);
                scanOrder.add(sh.lines.get(i));
            }
            int top = scanY + r.top, bottom = scanY + r.bottom; // page position = scroll + place on screen
            if (topSeen(sh, r)) sp.top = top;
            if (bottomSeen(sh, r)) sp.bottom = bottom;
            sp.minTop = Math.min(sp.minTop, top);
            sp.maxBottom = Math.max(sp.maxBottom, bottom);
        }
    }

    private int topOf(String line) {
        Spot sp = spots.get(key(line));
        if (sp == null) return 0;
        return sp.top != Integer.MIN_VALUE ? sp.top : sp.minTop == Integer.MAX_VALUE ? 0 : sp.minTop;
    }

    private int bottomOf(String line) {
        Spot sp = spots.get(key(line));
        if (sp == null) return 0;
        if (sp.bottom != Integer.MIN_VALUE) return sp.bottom;
        return Math.max(sp.maxBottom, topOf(line) + Theme.dp(this, 24));
    }

    /**
     * After the scan (or straight away for a page scanned before): the page's paragraphs in order, then
     * reading from the top, or from where the reading stopped last time. back = go to the top first.
     */
    private void startScannedReading(int gen, boolean back) {
        if (gen != scanGen) return;
        scanning = false;
        if (scanOrder.isEmpty()) {
            hideBanner();
            updateButtonIcon();
            return;
        }
        List<String> ordered = new ArrayList<>(scanOrder);
        ordered.sort((x, y) -> Integer.compare(topOf(x), topOf(y)));
        lines = ordered;
        rects = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) rects.add(new Rect());
        fromPicture = false;
        segs = prep(lines);
        scanned = true;
        currentLine = -1;
        if (segs.isEmpty()) {
            hideBanner();
            updateButtonIcon();
            finishScannedPage();
            return;
        }
        int start = resumeLine();
        List<TextPrep.Seg> from = segsFrom(start);
        Logs.d(this, "whole page: reading " + lines.size() + " paragraphs" + (start > 0 ? ", from paragraph " + start : ""));
        startPage();
        turnedFor = markedFirst = -1;
        markedAlong = -1f;
        Runnable read = () -> {
            if (gen != scanGen) return;
            hideBanner();
            // The first paragraph is already in place, before the voice starts.
            Speaker.get(this).speak(from, true);
        };
        TextPrep.Seg first = from.get(0);
        Runnable place = () -> {
            if (gen == scanGen) putInView(first.line, first.lastLine, 0f, true, read);
        };
        // From the top: the app's quick "scroll up" first. From further down: go straight there.
        showBanner(back && first.line == 0 ? "Scanned. Going back to the top\u2026" : "Going to where you stopped\u2026");
        if (back && first.line == 0) toTop(gen, 0, place);
        else place.run();
    }

    private List<TextPrep.Seg> segsFrom(int line) {
        List<TextPrep.Seg> from = new ArrayList<>();
        for (TextPrep.Seg sg : segs) if (sg.lastLine >= line) from.add(sg);
        return from.isEmpty() ? segs : from;
    }

    /** Where the page is scrolled to now, from the paragraphs whose edge is on screen; null if none is. */
    private Integer pageY(Shot sh) {
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i < sh.lines.size(); i++) {
            Spot sp = spots.get(key(sh.lines.get(i)));
            if (sp == null) continue;
            Rect r = sh.rects.get(i);
            if (sp.top != Integer.MIN_VALUE && topSeen(sh, r)) ys.add(sp.top - r.top);
            else if (sp.bottom != Integer.MIN_VALUE && bottomSeen(sh, r)) ys.add(sp.bottom - r.bottom);
        }
        if (ys.isEmpty()) return null;
        java.util.Collections.sort(ys);
        return knownY = ys.get(ys.size() / 2);
    }

    /**
     * Before a paragraph (or the next part of a long one) is read: scroll so all of it is visible,
     * below the app's title bar. Far away (reading resumed further down): several swipes. Measures
     * again after each move and corrects, a few times at most. early = before the voice starts.
     */
    private void putInView(int first, int last, float along, boolean early, Runnable then) {
        int g = ++showGen;
        showStep(g, first, last, along, early, 0, 0, Integer.MIN_VALUE, then);
    }

    private void showStep(int g, int first, int last, float along, boolean early, int steps, int fixes, int lastY, Runnable then) {
        // The first look is at a page at rest; after one of our swipes, wait until the app has caught up.
        if (steps == 0) {
            if (g != showGen) return;
            shootThen(sh -> place(g, sh, first, last, along, early, steps, fixes, lastY, then));
        }
        else settled(g, null, 0, sh -> place(g, sh, first, last, along, early, steps, fixes, lastY, then));
    }

    /** A look at the page once two looks in a row agree: the page has stopped and the app has caught up. */
    private void settled(int g, Shot before, int tries, java.util.function.Consumer<Shot> use) {
        if (g != showGen) return;
        shootThen(sh -> settled(g, before, tries, sh, use));
    }

    private void settled(int g, Shot before, int tries, Shot sh, java.util.function.Consumer<Shot> use) {
        if (g != showGen) return;
        if (sh == null || tries >= 2 || (before != null && sh.lines.equals(before.lines) && sh.rects.equals(before.rects))) {
            use.accept(sh);
            return;
        }
        main.postDelayed(() -> settled(g, sh, tries + 1, use), 120);
    }

    private void place(int g, Shot sh, int first, int last, float along, boolean early, int steps, int fixes, int lastY, Runnable then) {
        if (g != showGen) return; // another paragraph took over
        if (!(early || Speaker.get(this).isSpeaking()) || steps > 40 || fixes > 3) sh = null;
        if (sh == null) {
            then.run();
            return;
        }
        Integer measured = pageY(sh);
        int y = measured != null ? measured : knownY;
        if (measured != null && lastY != Integer.MIN_VALUE && Math.abs(y - lastY) < Theme.dp(this, 4)) {
            Logs.t(this, "place " + first + ": page didn't move (y " + y + ")");
            then.run(); // the last swipe didn't move the page: it is as far as it goes
            return;
        }
        int top = topOf(lines.get(first)), bottom = bottomOf(lines.get(Math.min(last, lines.size() - 1)));
        // Placed a little further in than the edge margin, so the paragraph's own edge counts as seen next time.
        int viewTop = sh.view.top + Theme.dp(this, 12);
        int viewBottom = sh.view.bottom - Theme.dp(this, 12);
        int room = viewBottom - viewTop;
        int visTop = top - y, visBottom = bottom - y;
        // Already on screen: where it really is counts, not the scan's estimate (a little off after long paragraphs).
        int li = sh.lines.indexOf(lines.get(first));
        if (li >= 0) {
            Rect r = sh.rects.get(li);
            if (topSeen(sh, r)) {
                visTop = r.top;
                visBottom = r.top + (bottom - top);
            } else if (first == last && bottomSeen(sh, r)) {
                visBottom = r.bottom;
                visTop = r.bottom - (bottom - top);
            }
        }
        int move;
        if (bottom - top <= room) {
            if (visTop >= viewTop && visBottom <= viewBottom) {
                then.run();
                return;
            }
            move = visTop - viewTop; // its first line just under the title bar: all of it fits
        } else if (along <= 0f) {
            move = visTop - viewTop; // too tall: start with its first line
        } else {
            // Further into a tall paragraph: keep the part being read in view, never past the paragraph's end.
            int reading = visTop + (int) (along * (bottom - top));
            if (reading >= viewTop && reading <= viewTop + room * 2 / 3) {
                then.run();
                return;
            }
            move = Math.min(reading - viewTop - Theme.dp(this, 8), visBottom - viewBottom);
        }
        // A small move is skipped only when the paragraph is fully visible anyway; a hidden edge always moves.
        // (Only for a paragraph that fits: a taller one always has a hidden part.)
        boolean hidden = bottom - top <= room && (visTop < sh.view.top || visBottom > sh.view.bottom);
        // "Can't scroll up" only counts when the scan agrees the page is near its top (EGW says it far down).
        boolean atTop = sh.atTop && (measured == null || y < Theme.dp(this, 24));
        if ((!hidden && Math.abs(move) < Theme.dp(this, 10)) || move == 0 || (move > 0 && sh.atEnd) || (move < 0 && atTop)) {
            then.run(); // in place, or the page ends here and can't move that way
            return;
        }
        Logs.t(this, "place " + first + "-" + last + " along " + along + ": y " + y + (measured == null ? " (guessed)" : "")
                + " page " + top + ".." + bottom + " live " + (li >= 0 ? sh.rects.get(li).toShortString() : "none")
                + " shows " + visTop + ".." + visBottom + " in " + viewTop + ".." + viewBottom + " move " + move);
        int most = (int) (sh.view.height() * 0.8f);
        boolean far = Math.abs(move) > most;
        int m = far ? Integer.signum(move) * most : move;
        // Never past the page's ends (known from the scan): dragging on at the end stretches the page, and the
        // text bounces back after the outline has been placed.
        if (measured != null && pageEndY > 0) m = Math.max(-y, Math.min(m, pageEndY - y));
        if (Math.abs(m) < Theme.dp(this, 10)) {
            then.run(); // as far as the page goes
            return;
        }
        Logs.d(this, "showing the whole paragraph: move " + move + " px" + (far ? " (on the way)" : fixes > 0 ? " (correction " + fixes + ")" : ""));
        // If the next screen can't be measured, assume the swipe moved the page as asked (less the finger's slack).
        knownY = y + m;
        int nextFixes = far ? fixes : fixes + 1;
        int seenY = measured != null ? y : Integer.MIN_VALUE;
        // Also timed from the start: the swipe's "done" can come late, or not at all if the app takes over the touch.
        freshWaits = 0;
        for (int at : new int[]{1300, 1900, 2600}) main.postDelayed(remarkFresh, at);
        swipe(sh, m, () -> {
            // The outline catches up as soon as the page stops (the app may send no more scroll events).
            // "done" can come well before the page has come to rest, so keep looking until it has.
            settleOutline();
            main.postDelayed(() -> showStep(g, first, last, along, early, steps + 1, nextFixes, seenY, then), 200);
        });
    }

    /** End of a scanned page: turn to the next one and scan it, or say that this is the end. */
    private void finishScannedPage() {
        scanned = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !String.valueOf(root.getPackageName()).equals(pkg)) return;
        if (nextPage(root)) return;
        boolean fil = TextPrep.mostlyTagalog(lines);
        Logs.d(this, "whole page: the end");
        Speaker.get(this).speakText(fil ? "Tapos na." : "The end.");
    }

    /**
     * Moves the page by a finger swipe down the empty left margin (touching words would select them),
     * inside the part of the page that shows (a swipe that starts on a title bar moves nothing).
     * move > 0 moves the text up. Holds still before lifting, so the page stops where it is put.
     */
    private void swipe(Shot sh, int move, Runnable done) {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            done.run();
            return;
        }
        Rect area = sh.view;
        int room = (int) (area.height() * 0.8f);
        int m = Math.max(-room, Math.min(room, move));
        lastScrollAsked = m;
        float x = swipeX(sh.list);
        float startY = m > 0 ? area.top + area.height() * 0.9f : area.top + area.height() * 0.1f;
        // A finger moves a little before the page follows: add that, so the page moves by m itself.
        int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        float endY = startY - m - Integer.signum(m) * slop;
        // Fast for most of the way, then slowly into place and a still hold before lifting: a finger that
        // lifts while moving flings the page on by hundreds of pixels.
        float slowY = startY + (endY - startY) * 0.8f;
        long ms = Math.max(200, Math.min(400, Math.abs(m) / 3));
        android.graphics.Path fast = new android.graphics.Path();
        fast.moveTo(x, startY);
        fast.lineTo(x, slowY);
        final android.accessibilityservice.GestureDescription.StrokeDescription drag =
                new android.accessibilityservice.GestureDescription.StrokeDescription(fast, 0, ms, true);
        android.graphics.Path slow = new android.graphics.Path();
        slow.moveTo(x, slowY);
        slow.lineTo(x, endY);
        android.graphics.Path hold = new android.graphics.Path();
        hold.moveTo(x, endY);
        hold.lineTo(x, endY + 1);
        lastSwipe = System.currentTimeMillis();
        boolean ok = gestureChain(drag, new android.graphics.Path[]{slow, hold}, new long[]{350, 200}, 0, done);
        if (!ok) done.run();
    }

    /**
     * The scan's swipe: no need to land on an exact spot (each move is measured), only to stop dead.
     * One quick drag, then the finger rests a moment before lifting, so the page doesn't fling on.
     */
    private void quickSwipe(Shot sh, int move, Runnable done) {
        Rect area = sh.view;
        int m = Math.max(-(int) (area.height() * 0.8f), Math.min((int) (area.height() * 0.8f), move));
        lastScrollAsked = m;
        float x = swipeX(sh.list);
        float startY = m > 0 ? area.top + area.height() * 0.9f : area.top + area.height() * 0.1f;
        int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        float endY = startY - m - Integer.signum(m) * slop;
        android.graphics.Path drag = new android.graphics.Path();
        drag.moveTo(x, startY);
        drag.lineTo(x, endY);
        android.graphics.Path hold = new android.graphics.Path();
        hold.moveTo(x, endY);
        hold.lineTo(x, endY + 1);
        lastSwipe = System.currentTimeMillis();
        boolean ok = gestureChain(new android.accessibilityservice.GestureDescription.StrokeDescription(drag, 0, 220, true),
                new android.graphics.Path[]{hold}, new long[]{150}, 0, done);
        if (!ok) done.run();
    }

    /** A fast flick towards the top: the page flies up by several screens. */
    private void flick(Shot sh, Runnable done) {
        Rect area = sh.view;
        float x = swipeX(sh.list);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, area.top + area.height() * 0.15f);
        p.lineTo(x, area.top + area.height() * 0.85f);
        lastSwipe = System.currentTimeMillis();
        boolean ok = gestureChain(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 90, false),
                new android.graphics.Path[0], new long[0], 0, done);
        if (!ok) done.run();
    }

    /** Plays a stroke, then each next part as its continuation; done once the last part has lifted. */
    private boolean gestureChain(android.accessibilityservice.GestureDescription.StrokeDescription stroke,
                                 android.graphics.Path[] next, long[] ms, int i, Runnable done) {
        return dispatchGesture(new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(android.accessibilityservice.GestureDescription g) {
                        if (i >= next.length) {
                            done.run();
                            return;
                        }
                        android.accessibilityservice.GestureDescription.StrokeDescription more =
                                stroke.continueStroke(next[i], 0, ms[i], i < next.length - 1);
                        if (!gestureChain(more, next, ms, i + 1, done)) done.run();
                    }

                    @Override
                    public void onCancelled(android.accessibilityservice.GestureDescription g) {
                        done.run();
                    }
                }, null);
    }

    // ------------------------------------------------- whole-page reading

    private final java.util.Set<String> pageSeen = new java.util.HashSet<>();
    private String pagePkg;
    private int pageScrolls;

    /** A new reading starts: what's on screen now counts as this page's text. */
    /** The same text shown twice (the title in a collapsing header) can differ by invisible characters. */
    private static String key(String line) {
        return Bible.normalize(line).toLowerCase(java.util.Locale.ROOT);
    }

    private void startPage() {
        pageSeen.clear();
        for (String l : lines) pageSeen.add(key(l));
        pagePkg = pkg;
        pageScrolls = 0;
    }

    /** Reached the end of the screen: scroll the page down and read what's new, until it can't scroll. */
    private void continuePage(int tries) {
        if (!Prefs.wholePage(this)) return;
        if (pageScrolls >= 60) {
            Logs.d(this, "whole page: stopped after 60 scrolls");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Logs.d(this, "whole page: no window to scroll");
            return;
        }
        if (!String.valueOf(root.getPackageName()).equals(pagePkg)) {
            Logs.d(this, "whole page: another app is in front (" + root.getPackageName() + "), not scrolling");
            return;
        }
        AccessibilityNodeInfo list = biggestScrollable(root);
        boolean scrolled = false;
        if (list != null) {
            // Prefer "scroll down" where the app offers it; "forward" can mean sideways in some apps.
            int down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
            scrolled = list.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
                    ? list.performAction(down) : list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            Logs.d(this, "whole page: scrolling " + list.getClassName());
        }
        if (!scrolled) {
            Logs.d(this, "end of page after " + pageScrolls + " scrolls");
            nextPage(root);
            return;
        }
        pageScrolls++;
        // Give the app a moment to draw the new part of the page.
        main.postDelayed(() -> {
            if (Speaker.get(this).isSpeaking()) return;
            if (!snapshot()) {
                Logs.d(this, "whole page: couldn't read after scrolling");
                return;
            }
            hidePanel();
            List<TextPrep.Seg> fresh = new ArrayList<>();
            for (TextPrep.Seg sg : segs) {
                boolean seen = true;
                for (int i = sg.line; i <= sg.lastLine; i++) if (!pageSeen.contains(key(lines.get(i)))) seen = false;
                if (!seen) fresh.add(sg);
            }
            for (String l : lines) pageSeen.add(key(l));
            Logs.d(this, "scrolled down (" + pageScrolls + "), " + fresh.size() + " new pieces");
            if (!fresh.isEmpty()) Speaker.get(this).speak(fresh, true);
            else if (tries < 2) continuePage(tries + 1); // only old text came into view: scroll a bit more
            else {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) nextPage(r);
                else Logs.d(this, "whole page: no window");
            }
        }, 700);
    }

    private int pagesTurned;

    /**
     * The bottom of the page: turn to the next page of a sideways pager (tomorrow's lesson, the next
     * chapter) and read it from the top, the way you would swipe yourself.
     */
    private boolean nextPage(AccessibilityNodeInfo root) {
        if (!Prefs.nextPage(this)) return false;
        if (pagesTurned >= 30) {
            Logs.d(this, "next page: stopped after 30 pages");
            return false;
        }
        AccessibilityNodeInfo pager = biggestPager(root);
        if (pager == null) {
            Logs.d(this, "next page: this screen has no pages to turn");
            return false;
        }
        boolean turned = false;
        if (android.os.Build.VERSION.SDK_INT >= 29
                && pager.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT)) {
            turned = pager.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.getId());
        }
        if (!turned && pager.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)) {
            turned = pager.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId());
        }
        if (!turned) turned = pager.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        Logs.d(this, "next page (" + pager.getClassName() + "): " + (turned ? "turned" : "last page"));
        if (!turned) return false;
        pagesTurned++;
        // Let the new page slide in and draw, then scan it and read it from the top.
        main.postDelayed(() -> {
            if (Speaker.get(this).isSpeaking()) return;
            hidePanel();
            Runnable asItIs = () -> snapshot(ok -> {
                if (!ok || segs.isEmpty()) {
                    Logs.d(this, "next page: nothing to read");
                    return;
                }
                speakFrom(0);
            });
            if (Prefs.wholePage(this)) readWholePage(handled -> {
                if (!handled) asItIs.run();
            });
            else asItIs.run();
        }, 1200);
        return true;
    }

    /** The largest area on screen that turns pages sideways. */
    private static AccessibilityNodeInfo biggestPager(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        java.util.ArrayDeque<AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        android.graphics.Rect r = new android.graphics.Rect();
        int visited = 0;
        while (!todo.isEmpty() && visited++ < 3000) {
            AccessibilityNodeInfo n = todo.poll();
            if (n.isScrollable() && n.isVisibleToUser() && !ScreenText.scrollsVertically(n)) {
                n.getBoundsInScreen(r);
                long area = (long) r.width() * r.height();
                if (area > bestArea) {
                    bestArea = area;
                    best = n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        // A small strip (a row of photos or stories) is not the page itself: it must fill much of the screen.
        android.graphics.Rect whole = new android.graphics.Rect();
        root.getBoundsInScreen(whole);
        return best != null && bestArea * 10 >= (long) whole.width() * whole.height() * 4 ? best : null;
    }

    /** The page's main scrolling area: the largest one on screen that can scroll down. */
    private static AccessibilityNodeInfo biggestScrollable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        java.util.ArrayDeque<AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        android.graphics.Rect r = new android.graphics.Rect();
        int visited = 0;
        while (!todo.isEmpty() && visited++ < 3000) {
            AccessibilityNodeInfo n = todo.poll();
            // Only up-and-down areas: scrolling a sideways pager would jump to the next day's lesson.
            if (n.isScrollable() && n.isVisibleToUser() && ScreenText.scrollsVertically(n)
                    && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)) {
                n.getBoundsInScreen(r);
                long area = (long) r.width() * r.height();
                if (area > bestArea) {
                    bestArea = area;
                    best = n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        return best;
    }

    private void speakFrom(int line) {
        startPage();
        turnedFor = markedFirst = -1;
        markedAlong = -1f;
        List<TextPrep.Seg> from = new ArrayList<>();
        for (TextPrep.Seg s : segs) if (s.lastLine >= line) from.add(s);
        if (from.isEmpty()) from = segs;
        Speaker.get(this).speak(from, true);
    }

    /** Where to continue: the newest remembered line for this app that is on this screen, else the top. */
    private int resumeLine() {
        if (!Prefs.resume(this)) return 0;
        LinkedList<String> list = marks.get(pkg);
        if (list == null) return 0;
        for (String m : list) {
            int i = lines.indexOf(m);
            if (i >= 0) return i;
        }
        return 0;
    }

    private void onProgress(int first, int last) {
        if (first < 0 || first >= lines.size()) return;
        currentLine = first;
        LinkedList<String> list = marks.get(pkg);
        if (list == null) marks.put(pkg, list = new LinkedList<>());
        // One remembered spot per screen: drop older marks that belong to the text on this screen.
        list.removeIf(m -> lines.contains(m));
        list.addFirst(lines.get(first));
        while (list.size() > 8) list.removeLast();
        highlight(first);
        Logs.t(this, "reading line " + first + ": " + Logs.snip(lines.get(first)));
        if (scanned) {
            // The outline goes on at once and rides along with the text while the page moves into place.
            markOnScreen(first, last);
            putInView(first, last, markAlong, false, () -> {
                if (markFirst != first) return;
                markOnScreen(first, last);
                // Once more when the page has come to rest, in case the app reported a position mid-move.
                main.postDelayed(() -> {
                    if (markFirst == first && Speaker.get(this).isSpeaking()) markOnScreen(first, last);
                }, 400);
            });
        } else {
            markOnScreen(first, last);
        }
        if (controls != null) controls.update(Logs.snip(lines.get(first)));
    }

    private void onReadingFinished() {
        // Read to the end: next time this screen starts from the top again.
        LinkedList<String> list = marks.get(pkg);
        if (list != null) list.removeIf(m -> lines.contains(m));
        currentLine = -1;
        highlight(-1);
        if (scanned) finishScannedPage();
        else continuePage(0);
    }

    // ---------------------------------------------------- tap-a-line panel

    /** A sheet listing the screen's text. Tap a line to start reading from there; it follows along. */
    private boolean panelComing;

    private void showPanel() {
        if (panel != null || panelComing) return;
        Speaker spk = Speaker.get(this);
        if (lines.isEmpty() || (!spk.isSpeaking() && !spk.isPaused())) {
            // Not reading: list what is on screen now (asked on the look thread), then open.
            panelComing = true;
            snapshot(ok -> {
                panelComing = false;
                if (ok) openPanel();
            });
            return;
        }
        openPanel();
    }

    private void openPanel() {
        if (panel != null) return;
        Speaker spk = Speaker.get(this);
        // Reading pauses while the panel is open: changes then don't restart the voice again and again
        // (restarting it quickly froze the phone's voice and ZYLENA with it). Close carries on, once.
        panelPaused = spk.isSpeaking();
        panelChanged = false;
        if (panelPaused) spk.pause();
        Logs.d(this, "panel opened" + (panelPaused ? ", reading paused" : ""));
        unmarkOnScreen();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int pad = Theme.dp(this, 12);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2161113);
        bg.setCornerRadii(new float[]{Theme.dp(this, 18), Theme.dp(this, 18), Theme.dp(this, 18), Theme.dp(this, 18), 0, 0, 0, 0});
        bg.setStroke(Theme.dp(this, 1), Theme.LINE_STRONG);
        panel.setBackground(bg);
        panel.setPadding(pad, pad, pad, pad);

        // One row of buttons that scrolls sideways, so there is room for all of them.
        Speaker sp = Speaker.get(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(chip("From top", () -> speakFrom(0)));
        bar.addView(chip("\u23EE Back", () -> sp.skip(-1)));
        android.widget.Button[] pauseBtn = new android.widget.Button[1];
        pauseBtn[0] = chip(sp.isPaused() ? "\u25B6 Resume" : "\u23F8 Pause", () -> {
            if (sp.isPaused()) sp.resume();
            else sp.pause();
            pauseBtn[0].setText(sp.isPaused() ? "\u25B6 Resume" : "\u23F8 Pause");
        });
        bar.addView(pauseBtn[0]);
        bar.addView(chip("Skip \u23ED", () -> sp.skip(1)));
        bar.addView(chip("\u25A0 Stop", sp::stop));
        // Translate: English is read in Tagalog and Tagalog in English. On or off, it starts again at this paragraph.
        android.widget.Button[] trBtn = new android.widget.Button[1];
        trBtn[0] = chip(translateLabel(), () -> {
            Prefs.setTranslate(this, !Prefs.translate(this));
            Logs.d(this, "translate " + (Prefs.translate(this) ? "on" : "off"));
            trBtn[0].setText(translateLabel());
            if (panelPaused && sp.isPaused()) panelChanged = true; // takes effect on Close
            else if (sp.isSpeaking() || sp.isPaused()) speakFrom(Math.max(0, currentLine));
        });
        bar.addView(trBtn[0]);
        android.widget.Button[] sizeBtn = new android.widget.Button[1];
        sizeBtn[0] = chip(sizeLabel(), () -> {
            Prefs.setButtonSize(this, (Prefs.buttonSize(this) + 1) % SIZES.length);
            sizeBtn[0].setText(sizeLabel());
            resizeButton();
        });
        bar.addView(sizeBtn[0]);
        bar.addView(chip("\u21BB Scan again", this::scanAgain));
        bar.addView(chip("Picture text", this::readPictureText));
        bar.addView(chip("Save audio", this::saveAudio));
        bar.addView(chip("\u2699 Settings", () -> {
            hidePanel();
            startActivity(new android.content.Intent(this, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }));
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(bar);
        // Close stays put at the end of the row, outside the sideways scroll, so it's always in reach.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(strip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button close = chip("\u2715 Close", this::closePanel);
        ((LinearLayout.LayoutParams) close.getLayoutParams()).setMarginEnd(0);
        ((LinearLayout.LayoutParams) close.getLayoutParams()).setMarginStart(Theme.dp(this, 6));
        top.addView(close);
        panel.addView(top);

        // Speed, right here: the new speed starts at once from the beginning of the current paragraph.
        LinearLayout speed = new LinearLayout(this);
        speed.setOrientation(LinearLayout.HORIZONTAL);
        speed.setGravity(Gravity.CENTER_VERTICAL);
        speed.setPadding(pad / 2, Theme.dp(this, 8), 0, 0);
        TextView label = Theme.body(this, "", Theme.INK, 14);
        label.setTypeface(Theme.sansBold(this));
        label.setMinWidth(Theme.dp(this, 96));
        final float min = 0.6f, step = 0.1f;
        label.setText(String.format(java.util.Locale.US, "Speed %.1f\u00D7", Prefs.rate(this)));
        speed.addView(label);
        android.widget.SeekBar bar2 = new android.widget.SeekBar(this);
        bar2.setMax(12);
        bar2.setProgress(Math.round((Prefs.rate(this) - min) / step));
        bar2.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.GOLD));
        bar2.setThumbTintList(android.content.res.ColorStateList.valueOf(Theme.GOLD_LIGHT));
        bar2.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Theme.LINE_STRONG));
        bar2.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int p, boolean user) {
                label.setText(String.format(java.util.Locale.US, "Speed %.1f\u00D7", min + p * step));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
                Prefs.setRate(ReaderService.this, min + b.getProgress() * step);
                Logs.d(ReaderService.this, "speed " + Prefs.rate(ReaderService.this));
                if (panelPaused && sp.isPaused()) panelChanged = true; // takes effect on Close
                else if (sp.isSpeaking()) sp.skip(0); // queued speech keeps its old speed, so restart this paragraph
            }
        });
        speed.addView(bar2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(speed);
        TextView hint = Theme.body(this, "Tap a line to start reading there", Theme.MUTED, 13);
        hint.setPadding(pad / 2, pad / 2, 0, 0);
        panel.addView(hint);

        panelScroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        panelScroll.addView(list);
        panel.addView(panelScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        lineViews.clear();
        boolean[] spoken = new boolean[lines.size() + 1];
        for (TextPrep.Seg s : segs) for (int i = s.line; i <= s.lastLine && i < spoken.length; i++) spoken[i] = true;
        for (int i = 0; i < lines.size(); i++) {
            if (!spoken[i]) continue;
            final int line = i;
            TextView tv = Theme.body(this, lines.get(i), Theme.INK, 15);
            tv.setPadding(pad, pad, pad, pad);
            tv.setOnClickListener(v -> {
                Logs.d(this, "start from line " + line + ": " + Logs.snip(lines.get(line)));
                speakFrom(line);
            });
            list.addView(tv);
            lineViews.put(i, tv);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (dm.heightPixels * 0.55f),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        try {
            wm.addView(panel, lp);
        } catch (Exception e) {
            panel = null;
            return;
        }
        highlight(currentLine);
    }

    private static final int[] SIZES = {40, 52, 64, 76}; // dp
    private static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Extra large"};

    private String sizeLabel() {
        return "\u25CF Button: " + SIZE_NAMES[Math.max(0, Math.min(SIZES.length - 1, Prefs.buttonSize(this)))];
    }

    private int buttonPx() {
        int i = Math.max(0, Math.min(SIZES.length - 1, Prefs.buttonSize(this)));
        return Theme.dp(this, SIZES[i]);
    }

    /** New size from the panel: grows around its middle, still inside the screen. */
    private void resizeButton() {
        if (button == null || buttonLp == null) return;
        int size = buttonPx(), old = buttonLp.width;
        buttonLp.x -= (size - old) / 2;
        buttonLp.y -= (size - old) / 2;
        buttonLp.width = buttonLp.height = size;
        button.setPadding(size / 4, size / 4, size / 4, size / 4);
        snapToSide(false);
    }

    /** The button stays fully on screen, clear of the status and navigation bars. */
    private void keepButtonOnScreen() {
        if (button == null || buttonLp == null) return;
        Rect u = usableScreen();
        int size = buttonLp.width;
        buttonLp.x = Math.max(u.left, Math.min(buttonLp.x, u.right - size));
        buttonLp.y = Math.max(u.top, Math.min(buttonLp.y, u.bottom - size));
        try {
            wm.updateViewLayout(button, buttonLp);
        } catch (Exception ignored) {
        }
    }

    private android.animation.ValueAnimator snap;

    /** Slides the button to the nearer side of the screen, keeping its height, and remembers the spot. */
    private void snapToSide(boolean animate) {
        if (button == null || buttonLp == null) return;
        if (tuckAnim != null) tuckAnim.cancel();
        tucked = false;
        if (snap != null) snap.cancel();
        Rect u = usableScreen();
        int size = buttonLp.width;
        int to = buttonLp.x + size / 2 < u.centerX() ? u.left : u.right - size;
        buttonLp.y = Math.max(u.top, Math.min(buttonLp.y, u.bottom - size));
        if (!animate || to == buttonLp.x) {
            buttonLp.x = to;
            keepButtonOnScreen();
            Prefs.setButtonPos(this, buttonLp.x, buttonLp.y);
            return;
        }
        snap = android.animation.ValueAnimator.ofInt(buttonLp.x, to);
        snap.setDuration(220);
        snap.setInterpolator(new android.view.animation.DecelerateInterpolator());
        snap.addUpdateListener(a -> {
            if (button == null) return;
            buttonLp.x = (int) a.getAnimatedValue();
            try {
                wm.updateViewLayout(button, buttonLp);
            } catch (Exception ignored) {
            }
        });
        snap.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (buttonLp != null) Prefs.setButtonPos(ReaderService.this, buttonLp.x, buttonLp.y);
            }
        });
        snap.start();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // Turned sideways and back: a spot near the old edge may now be off the screen.
        main.postDelayed(() -> snapToSide(false), 300);
    }

    private String translateLabel() {
        return Prefs.translate(this) ? "🌐 Translate: on" : "🌐 Translate: off";
    }

    /** A button sized to its label, for the sideways-scrolling row. */
    private android.widget.Button chip(String label, Runnable r) {
        android.widget.Button b = Theme.button(this, label, false);
        b.setMinHeight(Theme.dp(this, 40));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setTextSize(13);
        b.setSingleLine(true);
        b.setPadding(Theme.dp(this, 14), 0, Theme.dp(this, 14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Theme.dp(this, 6));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private boolean panelPaused, panelChanged;

    /** Close from the panel: reading paused by opening it carries on, with any new speed or Translate. */
    private void closePanel() {
        boolean carryOn = panelPaused && Speaker.get(this).isPaused();
        boolean changed = panelChanged;
        hidePanel();
        if (!carryOn) return;
        Logs.d(this, "panel closed, reading on" + (changed ? " with the new settings" : ""));
        if (changed) speakFrom(Math.max(0, currentLine));
        else Speaker.get(this).resume();
    }

    private void hidePanel() {
        panelPaused = false;
        if (panel == null) return;
        try {
            wm.removeView(panel);
        } catch (Exception ignored) {
        }
        panel = null;
        panelScroll = null;
        lineViews.clear();
    }

    private void highlight(int line) {
        if (panel == null) return;
        for (int i = 0; i < lineViews.size(); i++) {
            TextView tv = lineViews.valueAt(i);
            boolean on = lineViews.keyAt(i) == line;
            tv.setTextColor(on ? Theme.BG : Theme.INK);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(Theme.dp(this, 10));
            d.setColor(on ? Theme.GOLD : 0x00000000);
            tv.setBackground(d);
        }
        TextView cur = lineViews.get(line);
        ScrollView sv = panelScroll;
        // The panel can be closed before this runs (Close while reading): use this panel, never a later null.
        if (cur != null && sv != null) sv.post(() -> sv.smoothScrollTo(0, Math.max(0, cur.getTop() - Theme.dp(this, 40))));
    }

    // ------------------------------------------------------ floating button

    @SuppressLint("ClickableViewAccessibility")
    private void showButton() {
        if (button != null) return;
        int size = buttonPx();
        button = new ImageView(this);
        int pad = size / 4;
        button.setPadding(pad, pad, pad, pad);
        button.setColorFilter(0xFF0D0B0C);
        button.setContentDescription("Read screen");
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFF0D690, 0xFFD6AD55});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke((int) getResources().getDisplayMetrics().density, 0x99FFFFFF);
        button.setBackground(bg);
        updateButtonIcon();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        buttonLp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        buttonLp.gravity = Gravity.TOP | Gravity.START;
        int x = Prefs.buttonX(this), y = Prefs.buttonY(this);
        buttonLp.x = x >= 0 ? x : dm.widthPixels - size - size / 4;
        buttonLp.y = y >= 0 ? y : dm.heightPixels / 2;
        Rect u = usableScreen();
        buttonLp.x = buttonLp.x + size / 2 < u.centerX() ? u.left : u.right - size; // on a side
        buttonLp.y = Math.max(u.top, Math.min(buttonLp.y, u.bottom - size));

        button.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging, longPressed;
            final Runnable longPress = () -> {
                longPressed = true;
                button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                if (panel != null) closePanel();
                else if (fan != null) hideFan();
                else showFan();
            };

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        main.removeCallbacks(fade);
                        main.removeCallbacks(tuck);
                        // Tucked into the edge: this touch brings it out; a plain tap does nothing more.
                        wokeUp = tucked;
                        if (tucked) untuck();
                        button.animate().alpha(1f).setDuration(120).start();
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        if (snap != null) snap.cancel();
                        startX = buttonLp.x;
                        startY = buttonLp.y;
                        dragging = false;
                        longPressed = false;
                        main.postDelayed(longPress, 550);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > 16) {
                            dragging = true;
                            main.removeCallbacks(longPress);
                            showDismissTarget();
                        }
                        if (dragging) {
                            Rect u = usableScreen();
                            buttonLp.x = Math.max(u.left, Math.min(startX + (int) dx, u.right - buttonLp.width));
                            buttonLp.y = Math.max(u.top, Math.min(startY + (int) dy, u.bottom - buttonLp.height));
                            try {
                                wm.updateViewLayout(button, buttonLp);
                            } catch (Exception ignored) {
                            }
                            if (dismiss != null) dismiss.setAlpha(overDismiss(ev) ? 1f : 0.6f);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        boolean drop = dragging && overDismiss(ev);
                        removeDismissTarget();
                        if (drop) hideButtonForGood();
                        else if (dragging) snapToSide(true);
                        else if (!longPressed && ev.getAction() == MotionEvent.ACTION_UP && !wokeUp) toggle();
                        fadeSoon();
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            wm.addView(button, buttonLp);
            fadeSoon();
            if (Logs.toLogcat) {
                ImageView b = button;
                b.postDelayed(() -> {
                    int[] at = new int[2];
                    b.getLocationOnScreen(at);
                    Logs.t(this, "button at " + (at[0] + b.getWidth() / 2) + "," + (at[1] + b.getHeight() / 2));
                }, 500);
            }
        } catch (Exception e) {
            button = null;
        }
    }

    // The button fades while idle so it doesn't get in the way, and lights up while reading or touched.
    private static final float IDLE_ALPHA = 0.4f;
    private final Runnable fade = () -> {
        if (button != null && !Speaker.get(this).isSpeaking()) button.animate().alpha(IDLE_ALPHA).setDuration(400).start();
    };

    private void fadeSoon() {
        main.removeCallbacks(fade);
        main.postDelayed(fade, 2500);
        main.removeCallbacks(tuck);
        main.postDelayed(tuck, 3000);
    }

    private boolean tucked, wokeUp;
    private android.animation.ValueAnimator tuckAnim;

    /** Like a phone's quick ball: untouched for 3 seconds, the button slides half into the screen's edge. */
    private final Runnable tuck = () -> {
        // (Not in the emulator test: it taps the button where it first appeared.)
        if (button == null || buttonLp == null || tucked || !Prefs.tuck(this) || Prefs.testMode(this)
                || panel != null || ReaderService.this.fan != null || ReaderService.this.dismiss != null) return;
        Rect u = usableScreen();
        int size = buttonLp.width;
        boolean right = buttonLp.x + size / 2 > u.centerX();
        tucked = true;
        slideButton(right ? u.right - size / 2 : u.left - size / 2);
    };

    /** Out of the edge again, fully on screen. */
    private void untuck() {
        if (button == null || buttonLp == null) return;
        if (tuckAnim != null) tuckAnim.cancel();
        Rect u = usableScreen();
        int size = buttonLp.width;
        boolean right = buttonLp.x + size / 2 > u.centerX();
        tucked = false;
        buttonLp.x = right ? u.right - size : u.left;
        try {
            wm.updateViewLayout(button, buttonLp);
        } catch (Exception ignored) {
        }
    }

    private void slideButton(int to) {
        if (tuckAnim != null) tuckAnim.cancel();
        tuckAnim = android.animation.ValueAnimator.ofInt(buttonLp.x, to);
        tuckAnim.setDuration(250);
        tuckAnim.addUpdateListener(a -> {
            if (button == null || buttonLp == null) return;
            buttonLp.x = (int) a.getAnimatedValue();
            try {
                wm.updateViewLayout(button, buttonLp);
            } catch (Exception ignored) {
            }
        });
        tuckAnim.start();
    }

    /** True when the floating button sits on the left side: ZYLENA's own swipes then use the right margin. */
    private boolean buttonOnLeft() {
        if (button == null || buttonLp == null) return false;
        return buttonLp.x + buttonLp.width / 2 < getResources().getDisplayMetrics().widthPixels / 2;
    }

    /** Where ZYLENA's own swipe runs: down a margin of the page, on the side away from the floating button. */
    private float swipeX(Rect list) {
        return buttonOnLeft() ? list.right - Theme.dp(this, 10) : list.left + Theme.dp(this, 10);
    }

    private View dismiss;

    /** A ✕ at the bottom while dragging: drop the button there to hide it. */
    private void showDismissTarget() {
        if (dismiss != null) return;
        int size = Theme.dp(this, 60);
        TextView x = new TextView(this);
        x.setText("\u2715");
        x.setTextSize(24);
        x.setTextColor(0xFFFFFFFF);
        x.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC8A2A33);
        bg.setStroke(Theme.dp(this, 2), 0xCCFFFFFF);
        x.setBackground(bg);
        x.setAlpha(0.6f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = Theme.dp(this, 48);
        try {
            wm.addView(x, lp);
            dismiss = x;
        } catch (Exception ignored) {
        }
    }

    private boolean overDismiss(MotionEvent ev) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float cx = dm.widthPixels / 2f, cy = dm.heightPixels - Theme.dp(this, 78);
        return Math.hypot(ev.getRawX() - cx, ev.getRawY() - cy) < Theme.dp(this, 70);
    }

    private void removeDismissTarget() {
        if (dismiss == null) return;
        try {
            wm.removeView(dismiss);
        } catch (Exception ignored) {
        }
        dismiss = null;
    }

    /** Brings the paragraph being read back into view (after scrolling away from it by hand). */
    private void locateHighlight() {
        Speaker sp = Speaker.get(this);
        if (markFirst < 0 || (!sp.isSpeaking() && !sp.isPaused())) {
            android.widget.Toast.makeText(this, "Nothing is being read right now", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Logs.d(this, "locate the paragraph being read: " + markFirst);
        int first = markFirst, last = markLast;
        if (scanned) {
            putInView(first, last, Math.max(0f, markAlong), true, () -> {
                if (markFirst == first) markOnScreen(first, last);
            });
        } else {
            markedFirst = -1; // counts as a paragraph start: the page is turned to it again
            checksFor = -1;
            markOnScreen(first, last);
        }
    }

    private void runShortcut(String id) {
        Speaker sp = Speaker.get(this);
        Logs.d(this, "shortcut: " + id);
        switch (id) {
            case "scan":
                scanAgain();
                break;
            case "translate":
                Prefs.setTranslate(this, !Prefs.translate(this));
                android.widget.Toast.makeText(this, Prefs.translate(this) ? "Translate on" : "Translate off",
                        android.widget.Toast.LENGTH_SHORT).show();
                if (sp.isSpeaking() || sp.isPaused()) speakFrom(Math.max(0, currentLine));
                break;
            case "locate":
                locateHighlight();
                break;
            case "settings":
                startActivity(new android.content.Intent(this, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
                break;
            case "pause":
                if (sp.isPaused()) sp.resume();
                else if (sp.isSpeaking()) sp.pause();
                break;
            case "back":
                sp.skip(-1);
                break;
            case "skip":
                sp.skip(1);
                break;
            case "top":
                if (lines.isEmpty()) readScreen();
                else speakFrom(0);
                break;
            case "picture":
                readPictureText();
                break;
            case "audio":
                saveAudio();
                break;
            case "panel":
                showPanel();
                break;
        }
    }

    private android.widget.FrameLayout fan;

    /**
     * Hold the button: a fan of round shortcut buttons opens beside it, like a phone's quick ball. The four
     * are chosen in Controls; "All controls" opens the full panel. Tap anywhere else to close it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void showFan() {
        if (fan != null || button == null) return;
        List<String[]> items = new ArrayList<>(Shortcuts.chosen(this));
        items.add(new String[]{"panel", "\u2630", "All controls"});
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        f.setBackgroundColor(0x33000000);
        f.setOnClickListener(v -> hideFan());
        int size = Theme.dp(this, 54), boxW = Theme.dp(this, 84), boxH = size + Theme.dp(this, 22);
        List<View> boxes = new ArrayList<>();
        for (String[] it : items) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView ball = new TextView(this);
            ball.setText(it[1]);
            ball.setTextSize(22);
            ball.setGravity(Gravity.CENTER);
            ball.setTextColor(0xFF0D0B0C);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF0D690, 0xFFD6AD55});
            bg.setShape(GradientDrawable.OVAL);
            ball.setBackground(bg);
            ball.setContentDescription(it[2]);
            box.addView(ball, new LinearLayout.LayoutParams(size, size));
            TextView name = new TextView(this);
            name.setText(it[2]);
            name.setTextSize(11);
            name.setTextColor(0xFFFFFFFF);
            name.setShadowLayer(4, 0, 1, 0xFF000000);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            box.addView(name, new LinearLayout.LayoutParams(boxW, ViewGroup.LayoutParams.WRAP_CONTENT));
            final String id = it[0];
            box.setOnClickListener(v -> {
                hideFan();
                runShortcut(id);
            });
            f.addView(box, new android.widget.FrameLayout.LayoutParams(boxW, boxH));
            boxes.add(box);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(f, lp);
            fan = f;
        } catch (Exception e) {
            return;
        }
        Logs.d(this, "shortcuts opened");
        // Placed once the window is up, around where the button really is: a half circle towards the screen's middle.
        f.post(() -> {
            if (fan != f || button == null) return;
            int[] o = new int[2], b = new int[2];
            f.getLocationOnScreen(o);
            button.getLocationOnScreen(b);
            int cx = b[0] + button.getWidth() / 2 - o[0], cy = b[1] + button.getHeight() / 2 - o[1];
            boolean right = cx > f.getWidth() / 2;
            int radius = Theme.dp(this, 118);
            int n = boxes.size();
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(90 + 180.0 * i / Math.max(1, n - 1)); // from above, round the side, to below
                int x = cx + (int) ((right ? 1 : -1) * radius * Math.cos(a)) - boxW / 2;
                int y = cy - (int) (radius * Math.sin(a)) - size / 2;
                x = Math.max(0, Math.min(f.getWidth() - boxW, x));
                y = Math.max(0, Math.min(f.getHeight() - boxH, y));
                View box = boxes.get(i);
                box.setX(x);
                box.setY(y);
            }
        });
    }

    private void hideFan() {
        if (fan == null) return;
        try {
            wm.removeView(fan);
        } catch (Exception ignored) {
        }
        fan = null;
    }

    /** Forgets this page's scan and reads it again from the top, scanning first. */
    private void scanAgain() {
        Logs.d(this, "scan again");
        hidePanel();
        Speaker.get(this).stop();
        spots.clear();
        scanOrder.clear();
        scanPkg = "";
        main.postDelayed(this::readScreen, 300); // the panel goes away first
    }

    private TextView banner;

    /** A message at the top of the screen that stays until the scan is done, so nothing looks broken meanwhile. */
    private void showBanner(String text) {
        if (wm == null) return;
        if (banner != null) {
            banner.setText(text);
            return;
        }
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF0D0B0C);
        t.setTextSize(15);
        int ph = Theme.dp(this, 16), pv = Theme.dp(this, 10);
        t.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Theme.dp(this, 99));
        bg.setColor(0xF2F0D690);
        t.setBackground(bg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = usableScreen().top + Theme.dp(this, 8);
        try {
            wm.addView(t, lp);
            banner = t;
        } catch (Exception ignored) {
        }
    }

    private void hideBanner() {
        if (banner == null) return;
        try {
            wm.removeView(banner);
        } catch (Exception ignored) {
        }
        banner = null;
    }

    /** Puts the floating button away; opening ZYLENA brings it back. */
    private void hideButtonForGood() {
        Logs.d(this, "floating button hidden");
        hidePanel();
        removeButton();
        android.widget.Toast.makeText(this, "Button hidden. Open ZYLENA to bring it back, or use the Read screen tile in Quick Settings.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void toggle() {
        Speaker sp = Speaker.get(this);
        Logs.d(this, sp.isSpeaking() || scanning ? "button: stop" : "button: read");
        if (scanning) cancelScan();
        else if (sp.isSpeaking()) sp.stop();
        else if (sp.isPaused()) sp.resume();
        else readScreen();
    }

    private void updateButtonIcon() {
        if (button == null) return;
        if (Speaker.get(this).isSpeaking()) {
            main.removeCallbacks(fade);
            button.animate().alpha(1f).setDuration(150).start();
            main.removeCallbacks(tuck);
            main.postDelayed(tuck, 3000);
        } else {
            fadeSoon();
        }
        button.setImageResource(Speaker.get(this).isSpeaking() || scanning ? R.drawable.ic_stop : R.drawable.ic_speaker);
        button.setContentDescription(Speaker.get(this).isSpeaking() ? "Stop reading" : "Read screen");
    }

    private void removeButton() {
        removeDismissTarget();
        if (button == null) return;
        StackTraceElement[] st = new Throwable().getStackTrace();
        Logs.d(this, "floating button removed (" + (st.length > 1 ? st[1].getMethodName() : "?") + ")");
        try {
            wm.removeView(button);
        } catch (Exception ignored) {
        }
        button = null;
    }
}
