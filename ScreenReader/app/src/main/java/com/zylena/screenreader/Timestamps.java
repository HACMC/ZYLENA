package com.zylena.screenreader;

import java.util.regex.Pattern;

/**
 * Drops the "when" labels that clutter feeds and chats ("2h", "5 minutes ago", "10:42 PM", "Yesterday",
 * "Oct 24"), so only the content is read. A line that is only a timestamp disappears; timestamps
 * tacked onto the start or end of a line are trimmed. Times in the middle of a sentence stay.
 */
final class Timestamps {
    private static final int I = Pattern.CASE_INSENSITIVE;
    private static final String CLOCK = "\\d{1,2}:\\d{2}(?::\\d{2})?\\s?(?:[ap]\\.?m\\.?)?";
    private static final String MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    private static final String DAY = "(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*\\.?";
    private static final String UNIT = "(?:s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks|mo|month|months|y|yr|yrs|year|years)";
    private static final String TL_UNIT = "(?:segundo|minuto|oras|araw|linggo|buwan|taon)";
    private static final String AGO = "\\d{1,3}\\s?" + UNIT + "\\s?ago|\\d{1,3}\\s?" + TL_UNIT + "(?:\\s+na)?(?:\\s+ang)?\\s+nakalipas";
    private static final String WORDS = "just now|now|today|yesterday|tomorrow|tonight|this morning|this afternoon|this evening"
            + "|kahapon|kanina|ngayon lang|ngayon|bukas|kagabi|kaninang umaga|mamaya";

    /** Lines that are nothing but a timestamp. */
    private static final Pattern WHOLE = Pattern.compile("^(?:[^\\p{L}\\d]*)(?:"
            + CLOCK
            + "|\\d{1,2}\\s?[smhdwy]"
            + "|" + AGO
            + "|(?:" + WORDS + ")(?:\\s+at\\s+" + CLOCK + ")?"
            + "|" + DAY + "(?:,?\\s+(?:at\\s+)?" + CLOCK + ")?"
            + "|" + MONTH + "\\s\\d{1,2}(?:,?\\s\\d{4})?(?:,?\\s+(?:at\\s+)?" + CLOCK + ")?"
            + "|\\d{1,2}\\s" + MONTH + "(?:,?\\s\\d{4})?(?:,?\\s+(?:at\\s+)?" + CLOCK + ")?"
            + "|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?(?:,?\\s+" + CLOCK + ")?"
            + "|\\d{4}-\\d{2}-\\d{2}(?:[ T]" + CLOCK + ")?"
            + ")(?:[^\\p{L}\\d]*)$", I);

    /** Stamps tacked on at the start or end of a line. */
    private static final Pattern EDGE = Pattern.compile("(?:^|(?<!\\bto)(?<!hanggang)\\s)(?:" + AGO + "|(?:yesterday|today) at " + CLOCK + "|" + CLOCK + ")\\s*$"
            + "|^\\s*(?:" + AGO + "|" + CLOCK + ")\\s+", I);
    private static final Pattern INLINE_AGO = Pattern.compile("\\b(?:" + AGO + ")\\b", I);

    private Timestamps() {
    }

    /** The text without timestamps; empty when the whole line was one. */
    static String strip(String s) {
        String t = s.trim();
        if (WHOLE.matcher(t).matches()) return "";
        t = INLINE_AGO.matcher(t).replaceAll(" ");
        t = EDGE.matcher(t).replaceAll(" ");
        return t.replaceAll("\\s{2,}", " ").trim();
    }
}
