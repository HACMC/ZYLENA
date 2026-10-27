package com.zylena.screenreader;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Collects the visible text on screen, in the order the app lays it out (what TalkBack follows). */
final class ScreenText {
    private final List<String> lines = new ArrayList<>();
    private final List<Rect> rects = new ArrayList<>();
    /** Where each line of the last read() sits on screen, for the highlight. */
    static volatile List<Rect> lastRects = new ArrayList<>();
    private final Rect bounds = new Rect();
    private final List<Boolean> buttons = new ArrayList<>();
    private final List<String> skippedExamples = new ArrayList<>();
    /** What the last read() left out as buttons, for the log. */
    static volatile List<String> lastSkipped = new ArrayList<>();
    private final List<Boolean> listLabel = new ArrayList<>(); // a tappable item's own text, not an icon or button
    private int visited;
    private boolean wholePage;
    private int screenLeft, screenRight;

    /**
     * True for areas that scroll up and down (an article, a list). Sideways pagers, like the days of a
     * lesson or photo carousels, are false: their other pages are not "further down this page".
     */
    static boolean scrollsVertically(AccessibilityNodeInfo n) {
        if (!n.isScrollable()) return false;
        CharSequence cls = n.getClassName();
        String c = cls == null ? "" : cls.toString();
        if (c.contains("ViewPager") || c.contains("HorizontalScrollView") || c.contains("Carousel")) return false;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            List<AccessibilityNodeInfo.AccessibilityAction> acts = n.getActionList();
            if (acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
                    || acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)) return true;
            if (acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
                    || acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)) return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            List<AccessibilityNodeInfo.AccessibilityAction> acts = n.getActionList();
            if (acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT)
                    || acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT)) return false;
        }
        AccessibilityNodeInfo.CollectionInfo ci = n.getCollectionInfo();
        return ci == null || !(ci.getRowCount() <= 1 && ci.getColumnCount() > 1);
    }

    private ScreenText() {
    }

    static List<String> read(AccessibilityNodeInfo root) {
        return read(root, false);
    }

    /**
     * wholePage: also take text that is scrolled out of view inside a scrolling area (articles, lessons,
     * web pages keep it in memory), so the whole page can be read without scrolling first. Hidden text
     * outside scrolling areas (closed menus, other tabs) is still left out.
     */
    static List<String> read(AccessibilityNodeInfo root, boolean wholePage) {
        return read(root, wholePage, false);
    }

    /** skipButtons: leave out button names, icons and short tappable labels, unless that is all the screen has. */
    static List<String> read(AccessibilityNodeInfo root, boolean wholePage, boolean skipButtons) {
        ScreenText st = new ScreenText();
        st.wholePage = wholePage;
        if (root != null) {
            Rect r = new Rect();
            root.getBoundsInScreen(r);
            st.screenLeft = r.left;
            st.screenRight = r.right;
            st.walk(root, 0, "", false, 0);
        }
        if (skipButtons) {
            int realText = 0;
            for (int i = 0; i < st.lines.size(); i++) if (!st.buttons.get(i)) realText += st.lines.get(i).length();
            // A menu or a list of lessons is only buttons; then those labels are what there is to read,
            // but icons and toolbar buttons ("Videos", "Arrow Back") still stay out.
            boolean onlyButtons = realText < 60;
            lastSkipped = st.skippedExamples;
            {
                List<String> keep = new ArrayList<>();
                List<Rect> where = new ArrayList<>();
                for (int i = 0; i < st.lines.size(); i++) {
                    if (st.buttons.get(i) && !(onlyButtons && st.listLabel.get(i))) continue;
                    keep.add(st.lines.get(i));
                    where.add(st.rects.get(i));
                }
                lastRects = where;
                return keep;
            }
        }
        lastRects = st.rects;
        return st.lines;
    }

    private static boolean buttonClass(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        if (c == null) return false;
        String s = c.toString();
        return s.endsWith("Button") || s.endsWith("Switch") || s.endsWith("CheckBox") || s.endsWith("Chip")
                || s.contains("Tab") || s.endsWith("Spinner") || s.endsWith("ImageView")
                || (s.endsWith("EditText") && n.isEditable()); // apps also show plain headings in text fields
    }

    /** Inside a list or grid (a list of lessons), as opposed to a toolbar. */
    private static boolean inList(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 6; i++) {
            boolean list = p.getCollectionInfo() != null || scrollsVertically(p);
            AccessibilityNodeInfo up = p.getParent();
            p.recycle();
            if (list) {
                if (up != null) up.recycle();
                return true;
            }
            p = up;
        }
        if (p != null) p.recycle();
        return false;
    }

    private static int words(String s) {
        int n = 0;
        for (String w : s.trim().split("\\s+")) if (!w.isEmpty()) n++;
        return n;
    }

    /** tapDepth: how many levels below a tappable item this node is still its label (0 = not a label). */
    private void walk(AccessibilityNodeInfo node, int depth, String parentText, boolean inScroll, int tapDepth) {
        if (node == null || depth > 60 || ++visited > 4000) return;
        boolean visible = node.isVisibleToUser();
        if (!visible) {
            if (!(wholePage && inScroll)) return;
            // Off screen to the side means another page of a pager (tomorrow's lesson), not further down.
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && (bounds.left >= screenRight || bounds.right <= screenLeft)) return;
        }
        inScroll |= scrollsVertically(node);
        if (node.isClickable() || node.isLongClickable() || buttonClass(node)) tapDepth = 3;
        boolean tappable = tapDepth > 0;

        String own = ownText(node);
        node.getBoundsInScreen(bounds);
        // A parent that repeats its child's text (common with buttons) would be read twice.
        if (!own.isEmpty() && !own.equals(parentText) && (!visible || !isOffScreen(node))) {
            if (lines.isEmpty() || !lines.get(lines.size() - 1).equals(own)) {
                CharSequence t = node.getText();
                boolean iconLabel = (t == null || t.length() == 0) && words(own) <= 4; // "Arrow Back", "Search"
                boolean shortTap = tappable && words(own) <= 4 && own.length() <= 40; // "Komento ng Guro", "READ"
                lines.add(own);
                rects.add(new Rect(bounds));
                // Only short labels can be buttons: a paragraph is read whatever the app calls it.
                boolean shortText = words(own) <= 4 && own.length() <= 40;
                buttons.add(shortText && (iconLabel || shortTap || buttonClass(node)));
                listLabel.add(!iconLabel && !buttonClass(node) && inList(node));
                if (shortText && (iconLabel || shortTap || buttonClass(node)) && skippedExamples.size() < 6) {
                    skippedExamples.add(own + " (" + node.getClassName() + ")");
                }
            }
        }

        String passDown = own.isEmpty() ? parentText : own;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            walk(child, depth + 1, passDown, inScroll, Math.max(0, tapDepth - 1));
            child.recycle();
        }
    }

    /** The nodes on screen showing these texts (not recycled: the caller keeps and refreshes them). */
    static List<AccessibilityNodeInfo> find(AccessibilityNodeInfo root, java.util.Set<String> texts) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        java.util.ArrayDeque<AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>();
        if (root != null) todo.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        while (!todo.isEmpty()) {
            AccessibilityNodeInfo n = todo.poll();
            boolean keep = false;
            if (++visited <= 3000) {
                String own = ownText(n);
                if (!own.isEmpty() && texts.contains(own)) {
                    out.add(n);
                    keep = true;
                } else {
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo c = n.getChild(i);
                        if (c != null) todo.add(c);
                    }
                }
            }
            if (!keep) n.recycle();
        }
        return out;
    }

    static String ownText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if ((t == null || t.length() == 0) && n.isEditable()) t = n.getHintText();
        if (t == null) return "";
        if (n.isPassword()) return "";
        return t.toString().trim();
    }

    private boolean isOffScreen(AccessibilityNodeInfo n) {
        n.getBoundsInScreen(bounds);
        return bounds.width() <= 0 || bounds.height() <= 0;
    }
}
