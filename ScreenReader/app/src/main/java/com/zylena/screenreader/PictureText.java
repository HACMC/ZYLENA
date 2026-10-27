package com.zylena.screenreader;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads text inside pictures: takes a screenshot (Android 11 and newer let accessibility services do this)
 * and finds the words with Google's on-device text recognition. Works offline; nothing leaves the phone.
 */
final class PictureText {
    interface Result {
        /** lines and where each is on screen; empty when no text was found. */
        void done(List<String> lines, List<Rect> rects, String error);
    }

    private PictureText() {
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    static void read(AccessibilityService svc, Result out) {
        if (!supported()) {
            out.done(new ArrayList<>(), new ArrayList<>(), "Reading text in pictures needs Android 11 or newer.");
            return;
        }
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(AccessibilityService.ScreenshotResult shot) {
                Bitmap soft;
                try (HardwareBuffer hb = shot.getHardwareBuffer()) {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, shot.getColorSpace());
                    if (hw == null) {
                        out.done(new ArrayList<>(), new ArrayList<>(), "Couldn't take a screenshot.");
                        return;
                    }
                    soft = hw.copy(Bitmap.Config.ARGB_8888, false); // text recognition can't read GPU memory
                    hw.recycle();
                }
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        .process(InputImage.fromBitmap(soft, 0))
                        .addOnSuccessListener(text -> {
                            List<Text.TextBlock> blocks = new ArrayList<>(text.getTextBlocks());
                            // Top to bottom, then left to right, the way a person reads a page.
                            blocks.sort((a, b) -> {
                                Rect ra = a.getBoundingBox(), rb = b.getBoundingBox();
                                if (ra == null || rb == null) return 0;
                                if (Math.abs(ra.top - rb.top) > 24) return ra.top - rb.top;
                                return ra.left - rb.left;
                            });
                            List<String> lines = new ArrayList<>();
                            List<Rect> rects = new ArrayList<>();
                            for (Text.TextBlock b : blocks) {
                                String t = b.getText().replace('\n', ' ').trim();
                                if (t.isEmpty()) continue;
                                lines.add(t);
                                rects.add(b.getBoundingBox() != null ? b.getBoundingBox() : new Rect());
                            }
                            soft.recycle();
                            out.done(lines, rects, null);
                        })
                        .addOnFailureListener(e -> {
                            soft.recycle();
                            out.done(new ArrayList<>(), new ArrayList<>(), "Text recognition failed: " + e.getMessage());
                        });
            }

            @Override
            public void onFailure(int code) {
                out.done(new ArrayList<>(), new ArrayList<>(), "Couldn't take a screenshot (code " + code + ").");
            }
        });
    }

    // Picture details, not picture content: file names, times, dates, sizes, camera settings.
    private static final Pattern FILE = Pattern.compile(
            "(?i)\\S*\\.(jpe?g|png|gif|webp|heic|heif|bmp|tiff?|mp4|mov|3gp|pdf)\\b");
    private static final Pattern DETAIL = Pattern.compile("(?i)"
            + "\\b\\d{1,2}[:.]\\d{2}([:.]\\d{2})?\\s*([ap]\\.?m\\.?)?"
            + "|\\b\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}\\b"
            + "|\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(st|nd|rd|th)?(,?\\s*\\d{4})?"
            + "|\\b\\d{1,2}\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?(\\s+\\d{4})?"
            + "|\\b(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*\\b|\\b(today|yesterday|ngayon|kahapon)\\b"
            + "|\\b\\d+([.,]\\d+)?\\s*([kmg]i?b|bytes?|b)(/s)?\\b|\\bkb/s\\b"
            + "|\\b\\d{2,5}\\s*[x\\u00D7]\\s*\\d{2,5}\\b|\\b\\d+([.,]\\d+)?\\s*(mp|mm|px|dpi|fps)\\b"
            + "|\\bf/\\d+([.,]\\d+)?|\\biso\\s*\\d+|\\b1/\\d+\\s*s?\\b|\\b\\d{4}\\b|\\b\\d{1,3}%");

    /** True when a line is only a picture's details (name, time, size...), with no real words left. */
    static boolean onlyDetails(String line) {
        String rest = DETAIL.matcher(FILE.matcher(line).replaceAll(" ")).replaceAll(" ");
        int letters = 0;
        for (int i = 0; i < rest.length(); i++) if (Character.isLetter(rest.charAt(i))) letters++;
        return letters < 2;
    }

    /**
     * Keeps only the words that are part of a picture's content. Dropped: the phone's status and navigation
     * bars, the app's own labels (text the app already reports, like titles, names and times), file names
     * and details. When the screen has pictures, only words inside them are kept.
     */
    static void keepContent(AccessibilityNodeInfo root, List<String> lines, List<Rect> rects,
                            int screenW, int screenH, int topBar, int bottomBar, boolean picturesOnly) {
        List<Rect> pictures = new ArrayList<>(), labels = new ArrayList<>();
        if (root != null) collect(root, pictures, labels, (long) screenW * screenH, 0);
        // A box around a picture (a chat bubble, a gallery card) is not a label on top of it.
        labels.removeIf(l -> {
            for (Rect p : pictures) if (Rect.intersects(l, p) && l.width() * (long) l.height() >= p.width() * (long) p.height() / 2) return true;
            return false;
        });
        List<String> outL = new ArrayList<>(), inPic = new ArrayList<>();
        List<Rect> outR = new ArrayList<>(), inPicR = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Rect r = rects.get(i);
            int cx = r.centerX(), cy = r.centerY();
            if (!r.isEmpty() && (cy < topBar || cy > screenH - bottomBar)) continue;
            if (onlyDetails(lines.get(i))) continue;
            if (inside(labels, cx, cy)) continue;
            String t = FILE.matcher(lines.get(i)).replaceAll(" ").replaceAll("\\s+", " ").trim();
            if (t.isEmpty()) continue;
            outL.add(t);
            outR.add(r);
            if (inside(pictures, cx, cy)) {
                inPic.add(t);
                inPicR.add(r);
            }
        }
        lines.clear();
        rects.clear();
        // Pictures on screen with words in them: read just those. Otherwise everything that's left.
        boolean all = inPic.isEmpty() && !picturesOnly;
        lines.addAll(all ? outL : inPic);
        rects.addAll(all ? outR : inPicR);
    }

    /** True when the screen shows a picture big enough to hold words (a photo or poster, not an icon). */
    static boolean hasPictures(AccessibilityNodeInfo root, int screenW, int screenH) {
        if (root == null || !supported()) return false;
        List<Rect> pictures = new ArrayList<>(), labels = new ArrayList<>();
        collect(root, pictures, labels, (long) screenW * screenH, 0);
        return !pictures.isEmpty();
    }

    private static boolean inside(List<Rect> list, int x, int y) {
        for (Rect r : list) if (r.contains(x, y)) return true;
        return false;
    }

    private static void collect(AccessibilityNodeInfo n, List<Rect> pictures, List<Rect> labels, long screen, int depth) {
        if (n == null || depth > 40 || !n.isVisibleToUser()) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        long area = (long) b.width() * b.height();
        String cls = String.valueOf(n.getClassName());
        boolean picture = cls.contains("Image") && !cls.contains("Button");
        CharSequence text = n.getText();
        if (picture) {
            if (area * 20 >= screen) pictures.add(b); // 5% of the screen or more: a real picture, not an icon
        } else if (!b.isEmpty() && area * 3 < screen
                && ((text != null && text.toString().trim().length() > 0)
                || (n.getContentDescription() != null && n.getContentDescription().toString().trim().length() > 0))) {
            labels.add(b);
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), pictures, labels, screen, depth + 1);
    }
}
