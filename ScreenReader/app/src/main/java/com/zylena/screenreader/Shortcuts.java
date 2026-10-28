package com.zylena.screenreader;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** What the floating button's fan of shortcuts can hold (hold the button to open it). */
final class Shortcuts {
    private Shortcuts() {
    }

    /** id, symbol on the round button, name. */
    static final String[][] ALL = {
            {"scan", "↻", "Scan again"},
            {"translate", "🌐", "Translate"},
            {"locate", "◎", "Locate"},
            {"settings", "⚙", "Settings"},
            {"pause", "⏯", "Pause / Resume"},
            {"back", "⏮", "Back"},
            {"skip", "⏭", "Skip"},
            {"top", "⤒", "From top"},
            {"picture", "🖼", "Picture text"},
            {"audio", "💾", "Save audio"}};

    static final int SLOTS = 4;

    static String[] find(String id) {
        for (String[] s : ALL) if (s[0].equals(id)) return s;
        return null;
    }

    /** The four chosen, in order; unknown ones are left out. */
    static List<String[]> chosen(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String id : Prefs.shortcuts(c).split(",")) {
            String[] s = find(id.trim());
            if (s != null) out.add(s);
        }
        return out;
    }
}
