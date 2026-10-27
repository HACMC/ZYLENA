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
    private String pkg = "";
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
                if (!remarkPending) {
                    remarkPending = true;
                    main.postDelayed(remark, type == AccessibilityEvent.TYPE_VIEW_SCROLLED ? 40 : 120);
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
        hidePanel();
        removeButton();
        unmarkOnScreen();
        Speaker.get(this).stop();
        if (controls != null) controls.release();
        controls = null;
        return super.onUnbind(intent);
    }

    /** Call after a setting changes. */
    void applyPrefs() {
        if (Prefs.floatingButton(this)) showButton();
        else removeButton();
    }

    void readScreenSoon(long delayMs) {
        main.postDelayed(this::readScreen, delayMs);
    }

    /** Reads what is on screen now into lines/segs. False when Android gives no window to read. */
    private boolean snapshot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Logs.d(this, "no active window to read");
            return false;
        }
        pkg = String.valueOf(root.getPackageName());
        lines = ScreenText.read(root, Prefs.wholePage(this), Prefs.skipButtons(this));
        root.recycle();
        rects = ScreenText.lastRects;
        fromPicture = false;
        segs = TextPrep.prepare(lines, Prefs.skipTimestamps(this), Prefs.skipRefs(this), Prefs.readVerses(this));
        currentLine = -1;
        turnedFor = markedFirst = -1; // line numbers start over with every new read of the screen
        scanned = false;
        markedAlong = -1f;
        Logs.d(this, "screen " + pkg + ": " + lines.size() + " lines, " + segs.size() + " pieces, "
                + TextPrep.lastDropped + " timestamp lines skipped" + (TextPrep.lastVerseMode ? ", verse numbers skipped" : ""));
        if (Prefs.skipButtons(this) && !ScreenText.lastSkipped.isEmpty()) {
            Logs.d(this, "  left out as buttons: " + ScreenText.lastSkipped);
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
        pagesTurned = 0;
        lastAutoRead = System.currentTimeMillis();
        Speaker sp = Speaker.get(this);
        cancelScan();
        if (Prefs.wholePage(this) && readWholePage()) return;
        if (!snapshot() || segs.isEmpty()) {
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
        if (!PictureText.hasPictures(getRootInActiveWindow(), real.widthPixels, real.heightPixels)) {
            go.run();
            return;
        }
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
            segs = TextPrep.prepare(lines, Prefs.skipTimestamps(this), Prefs.skipRefs(this), Prefs.readVerses(this));
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
            segs = TextPrep.prepare(lines, Prefs.skipTimestamps(this), Prefs.skipRefs(this), Prefs.readVerses(this));
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
        float x = area.left + Theme.dp(this, 10);
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
        for (AccessibilityNodeInfo n : markNodes) n.recycle();
        markNodes = new ArrayList<>();
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

    private final Runnable remark = () -> {
        remarkPending = false;
        if (markFirst >= 0 && Speaker.get(this).isSpeaking()) markOnScreen(markFirst, markLast);
        else unmarkOnScreen();
    };

    /**
     * A soft gold outline around the paragraph being read, over the app itself. The paragraph is looked up
     * where it is now (the page may have scrolled since it was read in), and the outline goes away when
     * that text isn't on screen, e.g. in another app or tab.
     */
    private void markOnScreen(int first, int last) {
        markFirst = first;
        markLast = last;
        boolean follow = Prefs.wholePage(this);
        if ((!Prefs.highlight(this) && !follow) || panel != null) {
            unmarkOnScreen();
            return;
        }
        Rect r = new Rect();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean samePage = root != null && String.valueOf(root.getPackageName()).equals(pkg);
        releaseMarkNodes();
        if (samePage && fromPicture) {
            // Text found in a screenshot has no live position; use where it was in the picture.
            for (int i = first; i <= last && i < rects.size(); i++) if (!rects.get(i).isEmpty()) r.union(rects.get(i));
        } else if (samePage) {
            java.util.Set<String> want = new java.util.HashSet<>();
            for (int i = first; i <= last && i < lines.size(); i++) want.add(lines.get(i));
            markWant = want;
            markNodes = ScreenText.find(root, want);
            r = liveBounds();
            Logs.t(this, "paragraph " + first + " at " + r.toShortString());
            // Only when a paragraph starts (not on every refresh while it is read): turning again
            // mid-paragraph pushed a long paragraph off the top.
            if (follow && !scanned && !r.isEmpty() && (first != markedFirst || markAlong != markedAlong)) keepInView(root, r, first);
            markedFirst = first;
            markedAlong = markAlong;
            markArea = readArea(root);
            // (Not for a scanned page: putInView swipes it there, and the app's own scroll on top of that overshot.)
            if (r.isEmpty() && !markNodes.isEmpty() && !scanned) {
                // The paragraph is further down the page: scroll it into view, like turning the page.
                boolean shown = markNodes.get(0).performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                Logs.d(this, "bringing the paragraph into view: " + (shown ? "ok" : "not possible"));
                if (shown) {
                    main.postDelayed(remark, 350);
                    remarkPending = true;
                }
            }
            startTracking();
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect clip = fromPicture || markArea.isEmpty() ? new Rect(0, 0, dm.widthPixels, dm.heightPixels) : markArea;
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
    private AccessibilityNodeInfo lastList;
    private int pageEndY; // how far the scanned page scrolls
    private boolean upSeen;
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

    /** A shot of the page being read; null if another app or tab is in front (then nothing is swiped). */
    private Shot shoot() {
        // Read the page as it is now: Android may still hold the text from before the last scroll.
        if (android.os.Build.VERSION.SDK_INT >= 33) clearCache();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        if (!pkg.isEmpty() && !String.valueOf(root.getPackageName()).equals(pkg)) return null;
        AccessibilityNodeInfo list = pageList(root);
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

    /** Starts reading a scrolling page: scan it first, then read. False if this screen doesn't scroll. */
    private boolean readWholePage() {
        AccessibilityNodeInfo front = getRootInActiveWindow();
        if (front == null) return false;
        if (!pkg.equals(String.valueOf(front.getPackageName()))) lastList = null;
        pkg = String.valueOf(front.getPackageName());
        Shot first = shoot();
        if (first == null || first.lines.isEmpty()) {
            Logs.d(this, "whole page: no scrolling page here, reading the screen as it is");
            return false;
        }
        Logs.t(this, "page area " + first.list.toShortString() + ", readable " + first.view.toShortString());
        if (first.noUp && first.atEnd) return false; // all of it is on screen already
        // The page must hold most of the screen's words; otherwise read the screen as it is.
        int inPage = 0, all = 0;
        for (String l : first.lines) inPage += l.length();
        for (String l : ScreenText.read(front, false, Prefs.skipButtons(this))) all += l.length();
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
        android.widget.Toast.makeText(this, "ZYLENA: scanning the page…", android.widget.Toast.LENGTH_SHORT).show();
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
        updateButtonIcon();
    }

    /** Back to the top of the page with the app's own "scroll up" (quick, no swiping). */
    private void toTop(int gen, int n, Runnable then) {
        if (gen != scanGen) return;
        if (android.os.Build.VERSION.SDK_INT >= 33) clearCache();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo list = root == null ? null : pageList(root);
        if (list == null || !canScroll(list, false) || n >= 80 || !scrollUp(list)) {
            if (n == 0) {
                swipeToTop(gen, 0, null, then); // no quick "scroll up" here: swipe back by finger
                return;
            }
            if (n < 80) knownY = 0;
            main.postDelayed(() -> {
                if (gen == scanGen) then.run();
            }, 400);
            return;
        }
        main.postDelayed(() -> toTop(gen, n + 1, then), 220);
    }

    /** Swipes the page down a screen at a time until it stops moving: the top. */
    private void swipeToTop(int gen, int n, Shot prev, Runnable then) {
        if (gen != scanGen) return;
        Shot sh = shoot();
        boolean top = sh == null || n >= 60 || (sh.atTop && n > 0);
        if (!top && prev != null) {
            Integer moved = movedBy(prev, sh);
            top = moved != null && Math.abs(moved) < Theme.dp(this, 4);
        }
        if (top) {
            if (n > 0) Logs.d(this, "whole page: back at the top after " + n + " swipes");
            if (n < 60) knownY = 0;
            main.postDelayed(() -> {
                if (gen == scanGen) then.run();
            }, n == 0 ? 0 : 300);
            return;
        }
        swipe(sh, -(int) (sh.view.height() * 0.8f), () -> main.postDelayed(() -> swipeToTop(gen, n + 1, sh, then), 250));
    }

    private void scanStep(int gen, Shot prev) {
        if (gen != scanGen) return;
        Shot sh = shoot();
        if (sh == null) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && String.valueOf(r.getPackageName()).equals(pkg) && !scanOrder.isEmpty()) {
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
        if (sh.atEnd || scanStuck >= 2 || scanSteps > 80) {
            endScan(gen);
            return;
        }
        // Overlapping steps, so each move can be measured from text seen before and after it.
        int step = (int) (sh.view.height() * 0.62f);
        swipe(sh, step, () -> main.postDelayed(() -> scanStep(gen, sh), 200));
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
            updateButtonIcon();
            return;
        }
        List<String> ordered = new ArrayList<>(scanOrder);
        ordered.sort((x, y) -> Integer.compare(topOf(x), topOf(y)));
        lines = ordered;
        rects = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) rects.add(new Rect());
        fromPicture = false;
        segs = TextPrep.prepare(lines, Prefs.skipTimestamps(this), Prefs.skipRefs(this), Prefs.readVerses(this));
        scanned = true;
        currentLine = -1;
        if (segs.isEmpty()) {
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
            // The first paragraph is already in place, before the voice starts.
            Speaker.get(this).speak(from, true);
        };
        TextPrep.Seg first = from.get(0);
        Runnable place = () -> {
            if (gen == scanGen) putInView(first.line, first.lastLine, 0f, true, read);
        };
        // From the top: the app's quick "scroll up" first. From further down: go straight there.
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
        if (steps == 0) place(g, g == showGen ? shoot() : null, first, last, along, early, steps, fixes, lastY, then);
        else settled(g, null, 0, sh -> place(g, sh, first, last, along, early, steps, fixes, lastY, then));
    }

    /** A look at the page once two looks in a row agree: the page has stopped and the app has caught up. */
    private void settled(int g, Shot before, int tries, java.util.function.Consumer<Shot> use) {
        if (g != showGen) return;
        Shot sh = shoot();
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
        if ((!hidden && Math.abs(move) < Theme.dp(this, 10)) || move == 0 || (move > 0 && sh.atEnd) || (move < 0 && sh.atTop)) {
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
        float x = sh.list.left + Theme.dp(this, 10);
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
            if (Prefs.wholePage(this) && readWholePage()) return;
            if (!snapshot() || segs.isEmpty()) {
                Logs.d(this, "next page: nothing to read");
                return;
            }
            speakFrom(0);
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
    private void showPanel() {
        if (panel != null) return;
        Speaker spk = Speaker.get(this);
        if (lines.isEmpty() || (!spk.isSpeaking() && !spk.isPaused())) {
            if (!snapshot()) return;
        }
        Logs.d(this, "panel opened");
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
        bar.addView(chip("Picture text", this::readPictureText));
        bar.addView(chip("Save audio", this::saveAudio));
        bar.addView(chip("\u2699 Settings", () -> {
            hidePanel();
            startActivity(new android.content.Intent(this, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }));
        bar.addView(chip("\u23FB Turn off", this::turnOff));
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(bar);
        // Close stays put at the end of the row, outside the sideways scroll, so it's always in reach.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(strip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button close = chip("\u2715 Close", this::hidePanel);
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
                if (sp.isSpeaking()) sp.skip(0); // queued speech keeps its old speed, so restart this paragraph
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

    private void hidePanel() {
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
        if (cur != null && panelScroll != null) {
            panelScroll.post(() -> panelScroll.smoothScrollTo(0, Math.max(0, cur.getTop() - Theme.dp(this, 40))));
        }
    }

    // ------------------------------------------------------ floating button

    @SuppressLint("ClickableViewAccessibility")
    private void showButton() {
        if (button != null) return;
        int size = (int) (52 * getResources().getDisplayMetrics().density);
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
        buttonLp.x = x >= 0 ? Math.min(x, dm.widthPixels - size) : dm.widthPixels - size - size / 4;
        buttonLp.y = y >= 0 ? Math.min(y, dm.heightPixels - size) : dm.heightPixels / 2;

        button.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging, longPressed;
            final Runnable longPress = () -> {
                longPressed = true;
                button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                if (panel != null) hidePanel();
                else showPanel();
            };

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        main.removeCallbacks(fade);
                        button.animate().alpha(1f).setDuration(120).start();
                        downX = ev.getRawX();
                        downY = ev.getRawY();
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
                            buttonLp.x = startX + (int) dx;
                            buttonLp.y = startY + (int) dy;
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
                        else if (dragging) Prefs.setButtonPos(ReaderService.this, buttonLp.x, buttonLp.y);
                        else if (!longPressed && ev.getAction() == MotionEvent.ACTION_UP) toggle();
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

    /** Puts the floating button away; opening ZYLENA brings it back. */
    private void hideButtonForGood() {
        Logs.d(this, "floating button hidden");
        hidePanel();
        removeButton();
        android.widget.Toast.makeText(this, "Button hidden. Open ZYLENA to bring it back, or use the Read screen tile in Quick Settings.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** Closes the reader completely: stops reading and turns the accessibility service off. */
    private void turnOff() {
        Logs.d(this, "turned off from the panel");
        if (scanning) cancelScan();
        Speaker.get(this).stop();
        hidePanel();
        removeButton();
        android.widget.Toast.makeText(this, "ZYLENA is off. To use it again, open ZYLENA and turn it on.",
                android.widget.Toast.LENGTH_LONG).show();
        disableSelf();
    }

    /**
     * The ZYLENA app was swiped away from recent apps: stop reading and put the floating button away. The
     * service itself stays on (an app can't turn it back on), so opening ZYLENA again brings the button back.
     */
    void closeWithApp() {
        Logs.d(this, "app closed from recent apps: button put away");
        if (scanning) cancelScan();
        Speaker.get(this).stop();
        hidePanel();
        removeButton();
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
        } else {
            fadeSoon();
        }
        button.setImageResource(Speaker.get(this).isSpeaking() || scanning ? R.drawable.ic_stop : R.drawable.ic_speaker);
        button.setContentDescription(Speaker.get(this).isSpeaking() ? "Stop reading" : "Read screen");
    }

    private void removeButton() {
        removeDismissTarget();
        if (button == null) return;
        try {
            wm.removeView(button);
        } catch (Exception ignored) {
        }
        button = null;
    }
}
