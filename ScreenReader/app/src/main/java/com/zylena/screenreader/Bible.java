package com.zylena.screenreader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes scripture references and number ranges speakable. "Genesis 1:1-5" becomes
 * "Genesis chapter 1, verses 1 to 5" (or "kabanata ... talata ... hanggang ..." for Tagalog books),
 * so the dash is never read as "minus". Also handles "1 John 3:16,18", "Psalm 23:1-24:3", "Gen 1-3".
 */
final class Bible {
    private static final String[] ENGLISH = {
            "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth", "Samuel",
            "Kings", "Chronicles", "Ezra", "Nehemiah", "Esther", "Job", "Psalms", "Psalm", "Proverbs",
            "Ecclesiastes", "Song of Solomon", "Song of Songs", "Isaiah", "Jeremiah", "Lamentations", "Ezekiel",
            "Daniel", "Hosea", "Joel", "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah",
            "Haggai", "Zechariah", "Malachi", "Matthew", "Mark", "Luke", "John", "Acts", "Romans", "Corinthians",
            "Galatians", "Ephesians", "Philippians", "Colossians", "Thessalonians", "Timothy", "Titus",
            "Philemon", "Hebrews", "James", "Peter", "Jude", "Revelation"};

    /** Tagalog (Ang Dating Biblia / MBB style) names. The ones here are not also English names. */
    private static final String[] TAGALOG = {
            "Exodo", "Levitico", "Bilang", "Deuteronomio", "Josue", "Mga Hukom", "Mga Hari", "Mga Cronica",
            "Nehemias", "Mga Awit", "Awit ng mga Awit", "Awit", "Kawikaan", "Eclesiastes", "Isaias", "Jeremias",
            "Panaghoy", "Oseas", "Obadias", "Jonas", "Mikas", "Habacuc", "Zefanias", "Zacarias", "Malakias",
            "Mateo", "Marcos", "Lucas", "Juan", "Gawa", "Roma", "Corinto", "Galacia", "Efeso", "Filipos",
            "Colosas", "Tesalonica", "Timoteo", "Tito", "Filemon", "Hebreo", "Santiago", "Pedro", "Judas",
            "Pahayag", "Apocalipsis", "Ezequiel", "Isaias", "Hebreos", "Romanos", "Mga Hebreo", "Mga Gawa", "Mga Taga-Roma", "Mga Taga-Corinto", "Mga Taga-Galacia",
            "Mga Taga-Efeso", "Mga Taga-Filipos", "Mga Taga-Colosas", "Mga Taga-Tesalonica", "Kay Timoteo", "Kay Tito",
            "Kay Filemon"};

    private static final String[][] ABBR = {
            {"Gen", "Genesis"}, {"Ge", "Genesis"}, {"Gn", "Genesis"}, {"Ex", "Exodus"}, {"Exod", "Exodus"},
            {"Lev", "Leviticus"}, {"Lv", "Leviticus"}, {"Num", "Numbers"}, {"Nu", "Numbers"},
            {"Deut", "Deuteronomy"}, {"Dt", "Deuteronomy"}, {"Josh", "Joshua"}, {"Judg", "Judges"},
            {"Jdg", "Judges"}, {"Sam", "Samuel"}, {"Sm", "Samuel"}, {"Kgs", "Kings"}, {"Kg", "Kings"},
            {"Chr", "Chronicles"}, {"Chron", "Chronicles"}, {"Neh", "Nehemiah"}, {"Esth", "Esther"},
            {"Ps", "Psalm"}, {"Pss", "Psalms"}, {"Psa", "Psalm"}, {"Prov", "Proverbs"}, {"Pr", "Proverbs"},
            {"Eccl", "Ecclesiastes"}, {"Eccles", "Ecclesiastes"}, {"Isa", "Isaiah"}, {"Jer", "Jeremiah"},
            {"Lam", "Lamentations"}, {"Ezek", "Ezekiel"}, {"Eze", "Ezekiel"}, {"Dan", "Daniel"},
            {"Hos", "Hosea"}, {"Obad", "Obadiah"}, {"Mic", "Micah"}, {"Nah", "Nahum"}, {"Hab", "Habakkuk"},
            {"Zeph", "Zephaniah"}, {"Hag", "Haggai"}, {"Zech", "Zechariah"}, {"Mal", "Malachi"},
            {"Matt", "Matthew"}, {"Mt", "Matthew"}, {"Mk", "Mark"}, {"Mar", "Mark"}, {"Lk", "Luke"},
            {"Jn", "John"}, {"Joh", "John"}, {"Rom", "Romans"}, {"Cor", "Corinthians"}, {"Co", "Corinthians"},
            {"Gal", "Galatians"}, {"Eph", "Ephesians"}, {"Phil", "Philippians"}, {"Php", "Philippians"},
            {"Col", "Colossians"}, {"Thess", "Thessalonians"}, {"Thes", "Thessalonians"},
            {"Tim", "Timothy"}, {"Tit", "Titus"}, {"Philem", "Philemon"}, {"Heb", "Hebrews"},
            {"Jas", "James"}, {"Jm", "James"}, {"Pet", "Peter"}, {"Pt", "Peter"}, {"Rev", "Revelation"},
            {"Rv", "Revelation"},
            // More short forms, as listed by Pinoy Bible and other Bible apps.
            {"Exo", "Exodus"}, {"Le", "Leviticus"}, {"Nm", "Numbers"}, {"Bil", "Bilang"}, {"Deu", "Deuteronomy"},
            {"De", "Deuteronomy"}, {"Jo", "Joshua"}, {"Jos", "Joshua"}, {"Js", "Joshua"}, {"Jdgs", "Judges"},
            {"Jgs", "Judges"}, {"Jg", "Judges"}, {"Rth", "Ruth"}, {"Ru", "Ruth"}, {"Ki", "Kings"}, {"Ch", "Chronicles"},
            {"Chro", "Chronicles"}, {"Ezr", "Ezra"}, {"Ne", "Nehemiah"}, {"Est", "Esther"}, {"Es", "Esther"},
            {"Jb", "Job"}, {"Psm", "Psalm"}, {"Pslm", "Psalm"}, {"Prv", "Proverbs"}, {"Ec", "Ecclesiastes"},
            {"Ecc", "Ecclesiastes"}, {"Qoh", "Ecclesiastes"}, {"Song", "Song of Solomon"}, {"SS", "Song of Solomon"},
            {"Jr", "Jeremiah"}, {"La", "Lamentations"}, {"Ezk", "Ezekiel"}, {"Dn", "Daniel"}, {"Da", "Daniel"},
            {"Ho", "Hosea"}, {"Jl", "Joel"}, {"Joe", "Joel"}, {"Ob", "Obadiah"}, {"Jnh", "Jonah"}, {"Jon", "Jonah"},
            {"Mi", "Micah"}, {"Na", "Nahum"}, {"Hb", "Habakkuk"}, {"Zep", "Zephaniah"}, {"Zp", "Zephaniah"},
            {"Hg", "Haggai"}, {"Zec", "Zechariah"}, {"Zc", "Zechariah"}, {"Ml", "Malachi"}, {"Mat", "Matthew"},
            {"Mrk", "Mark"}, {"Mr", "Mark"}, {"Luk", "Luke"}, {"Lu", "Luke"}, {"Jhn", "John"}, {"Act", "Acts"},
            {"Rm", "Romans"}, {"Ga", "Galatians"}, {"Ep", "Ephesians"}, {"Pp", "Philippians"}, {"Tm", "Timothy"},
            {"Phm", "Philemon"}, {"Pm", "Philemon"}, {"Ja", "James"}, {"Jud", "Jude"}, {"Jd", "Jude"},
            {"Apoc", "Apocalipsis"}, {"Gaw", "Gawa"}, {"Kaw", "Kawikaan"}};

    /** The 66 books in order, so a reference can be looked up in the built-in Bible text. */
    private static final String[] ORDER = {
            "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth", "1Samuel",
            "2Samuel", "1Kings", "2Kings", "1Chronicles", "2Chronicles", "Ezra", "Nehemiah", "Esther", "Job", "Psalms",
            "Proverbs", "Ecclesiastes", "Song of Solomon", "Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel",
            "Hosea", "Joel", "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai",
            "Zechariah", "Malachi", "Matthew", "Mark", "Luke", "John", "Acts", "Romans", "1Corinthians",
            "2Corinthians", "Galatians", "Ephesians", "Philippians", "Colossians", "1Thessalonians", "2Thessalonians",
            "1Timothy", "2Timothy", "Titus", "Philemon", "Hebrews", "James", "1Peter", "2Peter", "1John", "2John",
            "3John", "Jude", "Revelation"};

    /** Tagalog and alternative names to the English name used in ORDER. */
    private static final String[][] SAME = {
            {"Exodo", "Exodus"}, {"Levitico", "Leviticus"}, {"Bilang", "Numbers"}, {"Deuteronomio", "Deuteronomy"},
            {"Josue", "Joshua"}, {"Mga Hukom", "Judges"}, {"Mga Hari", "Kings"}, {"Mga Cronica", "Chronicles"},
            {"Nehemias", "Nehemiah"}, {"Mga Awit", "Psalms"}, {"Awit", "Psalms"}, {"Psalm", "Psalms"},
            {"Awit ng mga Awit", "Song of Solomon"}, {"Song of Songs", "Song of Solomon"}, {"Kawikaan", "Proverbs"},
            {"Eclesiastes", "Ecclesiastes"}, {"Isaias", "Isaiah"}, {"Jeremias", "Jeremiah"}, {"Panaghoy", "Lamentations"},
            {"Ezequiel", "Ezekiel"}, {"Oseas", "Hosea"}, {"Obadias", "Obadiah"}, {"Jonas", "Jonah"}, {"Mikas", "Micah"},
            {"Habacuc", "Habakkuk"}, {"Zefanias", "Zephaniah"}, {"Zacarias", "Zechariah"}, {"Malakias", "Malachi"},
            {"Mateo", "Matthew"}, {"Marcos", "Mark"}, {"Lucas", "Luke"}, {"Juan", "John"}, {"Gawa", "Acts"},
            {"Mga Gawa", "Acts"}, {"Roma", "Romans"}, {"Romanos", "Romans"}, {"Mga Taga-Roma", "Romans"},
            {"Corinto", "Corinthians"}, {"Mga Taga-Corinto", "Corinthians"}, {"Galacia", "Galatians"},
            {"Mga Taga-Galacia", "Galatians"}, {"Efeso", "Ephesians"}, {"Mga Taga-Efeso", "Ephesians"},
            {"Filipos", "Philippians"}, {"Mga Taga-Filipos", "Philippians"}, {"Colosas", "Colossians"},
            {"Mga Taga-Colosas", "Colossians"}, {"Tesalonica", "Thessalonians"}, {"Mga Taga-Tesalonica", "Thessalonians"},
            {"Timoteo", "Timothy"}, {"Kay Timoteo", "Timothy"}, {"Tito", "Titus"}, {"Kay Tito", "Titus"},
            {"Filemon", "Philemon"}, {"Kay Filemon", "Philemon"}, {"Hebreo", "Hebrews"}, {"Hebreos", "Hebrews"},
            {"Mga Hebreo", "Hebrews"}, {"Santiago", "James"}, {"Pedro", "Peter"}, {"Judas", "Jude"},
            {"Pahayag", "Revelation"}, {"Apocalipsis", "Revelation"}};
    private static final Map<String, String> SAME_MAP = new HashMap<>();

    private static final Map<String, String> EXPAND = new HashMap<>();
    private static final Set<String> TAGALOG_NAMES = new HashSet<>(Arrays.asList(TAGALOG));
    private static final Pattern REF;
    private static final Pattern RANGE_PART = Pattern.compile("(\\d+)\\s?[-–—]\\s?(\\d+)(?::(\\d+))?");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(\\d{1,2}:\\d{2}\\s?(?:[AaPp]\\.?[Mm]\\.?)?)\\s?[-–—]\\s?(\\d{1,2}:\\d{2})");
    private static final Pattern NUM_RANGE = Pattern.compile(
            "(?<![\\d:.,\\-–—])(?<!\\d)([1-9]\\d{0,3})\\s?[-–—]\\s?([1-9]\\d{0,3})(?![\\d:\\-–—])");
    private static final Pattern LEAD = Pattern.compile("^(\\d{1,3})\\s*[.)]?\\s*(?=[\\p{L}“\"‘'(\\[])");
    private static final Pattern STANDALONE = Pattern.compile("^\\d{1,3}(?:\\s?[-–—,]\\s?\\d{1,3})*[.)]?$");
    private static final Pattern INLINE = Pattern.compile("(?<=[.!?;:,”’\"')])\\s+(\\d{1,3})\\s+(?=[\\p{Lu}“\"‘'(])");
    private static final Pattern SUPER = Pattern.compile("[\\u00B9\\u00B2\\u00B3\\u2070-\\u2079]+");
    private static final Pattern CHAPTER_HEAD;
    private static final Pattern SPACED_DASH = Pattern.compile("\\s[-–—]+\\s");

    static {
        for (String[] a : ABBR) EXPAND.put(a[0], a[1]);
        for (String[] a : SAME) SAME_MAP.put(a[0], a[1]);
        List<String> names = new ArrayList<>(Arrays.asList(ENGLISH));
        names.addAll(Arrays.asList(TAGALOG));
        for (String[] a : ABBR) names.add(a[0]);
        names.sort((x, y) -> y.length() - x.length());
        StringBuilder alt = new StringBuilder();
        for (String n : names) {
            if (alt.length() > 0) alt.append('|');
            alt.append(Pattern.quote(n));
        }
        String dash = "[-–—]";
        String spec = "\\d{1,3}(?:\\s?" + dash + "\\s?\\d{1,3}(?::\\d{1,3})?)?"
                + "(?:\\s?,\\s?\\d{1,3}(?:\\s?" + dash + "\\s?\\d{1,3})?(?![\\d:]))*";
        CHAPTER_HEAD = Pattern.compile("^(?:[1-3]\\s)?(?:" + alt + ")\\.?\\s\\d{1,3}$");
        REF = Pattern.compile("(?<![\\p{L}\\d])(?:(?<n>[1-3]|III|II|I)\\.?\\s?)?(?<b>" + alt + ")\\.?\\s?(?<c>\\d{1,3})"
                + "(?:\\s?" + dash + "\\s?(?<c2>\\d{1,3})(?![\\d:])|\\s?:\\s?(?<v>" + spec + "))(?![\\d:])");
    }

    private Bible() {
    }

    /** True if the text cites a Tagalog-named book (Juan 3:16), which means the reference is read in Tagalog. */
    static boolean hasTagalogBook(String text) {
        Matcher m = REF.matcher(text);
        while (m.find()) if (TAGALOG_NAMES.contains(m.group("b"))) return true;
        return false;
    }

    private static final Pattern MARK = Pattern.compile("\u0001(\\d+)\u0002");
    private static final Pattern REF_PAREN = Pattern.compile(
            "\\s*\\(\\s*(?:\u0001\\d+\u0002|[\\s;,.\\d\\-–—]|\\b(?:at|and|cf|tingnan|ihambing)\\b)*\u0001\\d+\u0002"
                    + "(?:\u0001\\d+\u0002|[\\s;,.\\d\\-–—]|\\b(?:at|and)\\b)*\\)");
    private static final Pattern ONLY_REFS = Pattern.compile(
            "[\\s(]*(?:\u0001\\d+\u0002|[\\s;,.\\d\\-–—()]|\\b(?:at|and)\\b)*\u0001\\d+\u0002(?:\u0001\\d+\u0002|[\\s;,.\\d\\-–—()]|\\b(?:at|and)\\b)*");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([.,;:!?])");

    /**
     * Leaves out Bible references for "Skip Bible references": lines that are only references
     * ("Hebreo 1:1–2; 2 Timoteo 3:15–17.") and references in brackets ("... ng panahon (Apocalipsis 1:1–3).").
     * A reference that is part of a sentence ("Read John 3:16 today") stays, so the sentence still makes sense.
     */
    static String stripRefs(String text) {
        Matcher m = REF.matcher(text);
        List<String> found = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "\u0001" + found.size() + "\u0002");
            found.add(m.group());
        }
        if (found.isEmpty()) return text;
        m.appendTail(sb);
        String t = sb.toString();
        if (ONLY_REFS.matcher(t).matches()) return "";
        t = REF_PAREN.matcher(t).replaceAll("");
        Matcher back = MARK.matcher(t);
        StringBuffer out = new StringBuffer();
        while (back.find()) back.appendReplacement(out, Matcher.quoteReplacement(found.get(Integer.parseInt(back.group(1)))));
        back.appendTail(out);
        return SPACE_BEFORE_PUNCT.matcher(out.toString()).replaceAll("$1").trim();
    }

    // ------------------------------------------------------- verse text

    /** Gives the words of one verse (book 1-66), from the Tagalog or the English Bible; null if missing. */
    interface Verses {
        String get(boolean tagalog, int book, int chapter, int verse);
    }

    static volatile Verses source;
    private static final int MAX_VERSES = 12;

    /** 1-66 for a matched reference's book, or 0 if unknown. */
    static int bookNumber(String n, String b) {
        String name = EXPAND.containsKey(b) ? EXPAND.get(b) : b;
        if (SAME_MAP.containsKey(name)) name = SAME_MAP.get(name);
        int k = n == null ? 0 : n.equals("1") || n.equals("I") ? 1 : n.equals("2") || n.equals("II") ? 2 : 3;
        if (k > 0) name = k + name;
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(name)) return i + 1;
        return 0;
    }

    /**
     * For "Read verse text": after each reference, the verse itself is read, from the built-in
     * Ang Biblia (1905) for Tagalog text or the King James Version for English. Long passages stop
     * after a dozen verses so one reference doesn't take over the lesson.
     */
    static String withVerses(String text, boolean fil) {
        Verses src = source;
        if (src == null) return text;
        Matcher m = REF.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            String v = m.group("v");
            int book = bookNumber(m.group("n"), m.group("b"));
            boolean tl = fil || TAGALOG_NAMES.contains(m.group("b"));
            String words = v == null || book == 0 ? null : passage(src, tl, book, Integer.parseInt(m.group("c")), v);
            String out = m.group();
            if (words != null) {
                words = words.replaceAll("[\\s,;:]+$", "");
                if (!words.matches(".*[.!?]$")) words += ".";
                out += " \u2014 " + words + "\u0003";
                any = true;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        // The separator after a quoted verse (";" or ")") would only be read as an odd pause.
        return any ? sb.toString().replaceAll("\u0003[\\s;,.)]*", " ").trim() : text;
    }

    private static String passage(Verses src, boolean tl, int book, int chapter, String spec) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String part : spec.split("\\s?,\\s?")) {
            Matcher r = RANGE_PART.matcher(part.trim());
            int c = chapter, from, to, endChapter = chapter;
            if (r.matches()) {
                from = Integer.parseInt(r.group(1));
                if (r.group(3) != null) {
                    endChapter = Integer.parseInt(r.group(2));
                    to = Integer.parseInt(r.group(3));
                } else {
                    to = Integer.parseInt(r.group(2));
                }
            } else {
                try {
                    from = to = Integer.parseInt(part.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            int v = from;
            while (count < MAX_VERSES && (c < endChapter || (c == endChapter && v <= to))) {
                String t = src.get(tl, book, c, v);
                if (t == null) {
                    if (c < endChapter) {
                        c++;
                        v = 1;
                        continue;
                    }
                    break;
                }
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
                count++;
                v++;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static String expand(String text, boolean fil) {
        Matcher m = REF.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            boolean tagBook = TAGALOG_NAMES.contains(m.group("b"));
            boolean f = fil || tagBook;
            String out = book(m.group("n"), m.group("b"), f);
            if (m.group("v") != null) {
                out += (f ? " kabanata " : " chapter ") + m.group("c") + ", " + verses(m.group("v"), f);
            } else {
                out += (f ? " kabanata " : " chapters ") + m.group("c") + (f ? " hanggang " : " to ") + m.group("c2");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return ranges(sb.toString(), fil);
    }

    /**
     * True when the screen looks like Bible text: verse numbers 1, 2, 3... at the starts of lines (or on
     * lines of their own, or inside a paragraph), or a chapter heading like "Genesis 1" above numbered lines.
     */
    static boolean verseMode(List<String> lines) {
        List<Integer> nums = new ArrayList<>();
        int heading = 0, leading = 0;
        for (String line : lines) {
            String t = normalize(line);
            if (CHAPTER_HEAD.matcher(t).matches() || REF.matcher(t).matches()) heading++;
            if (STANDALONE.matcher(t).matches()) {
                Matcher n = Pattern.compile("\\d+").matcher(t);
                if (n.find()) nums.add(Integer.parseInt(n.group()));
                leading++;
                continue;
            }
            // Numbers inside references ("; 2 Timoteo 3:15") are not verse numbers.
            String bare = REF.matcher(t).replaceAll(" \u00A4 ");
            Matcher m = LEAD.matcher(bare);
            if (m.find()) {
                nums.add(Integer.parseInt(m.group(1)));
                leading++;
            }
            Matcher in = INLINE.matcher(bare);
            while (in.find()) nums.add(Integer.parseInt(in.group(1)));
        }
        int run = 0;
        for (int i = 0; i + 1 < nums.size(); i++) if (nums.get(i + 1) == nums.get(i) + 1) run++;
        // A study page that only cites verses ("Apocalipsis 1:1, 2" as a link of its own) is not a Bible page:
        // that needs verses numbered at the start of lines under the heading, or a run of 1, 2, 3...
        return run >= 2 || (heading > 0 && leading > 0);
    }

    private static final Pattern ODD_SPACE = Pattern.compile("[\\p{Z}\\t]+");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}]+");
    private static final String SUPER_DIGITS = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079";

    /**
     * Bible apps write verse numbers as small superscripts ("¹ Ang") followed by thin or no-break spaces.
     * Turn those into plain digits and plain spaces so verse numbers are recognised.
     */
    static String normalize(String s) {
        s = INVISIBLE.matcher(s).replaceAll("");
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int d = SUPER_DIGITS.indexOf(ch);
            if (d >= 0) {
                // "patotoo ni Jesus.¹⁰ Ako'y" -> "patotoo ni Jesus. 10 Ako'y"
                if (b.length() > 0 && SUPER_DIGITS.indexOf(s.charAt(i - 1)) < 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
                b.append((char) ('0' + d));
                if (i + 1 < s.length() && SUPER_DIGITS.indexOf(s.charAt(i + 1)) < 0) b.append(' ');
            } else {
                b.append(ch);
            }
        }
        return ODD_SPACE.matcher(b).replaceAll(" ").trim();
    }

    /** Drops verse numbers so reading starts at the words: "1 Ang Dios..." is read "Ang Dios...". */
    static String stripVerseNumbers(String s) {
        s = normalize(s);
        String t = s.trim();
        if (STANDALONE.matcher(t).matches()) return "";
        Matcher m = LEAD.matcher(t);
        if (m.find()) t = t.substring(m.end());
        return INLINE.matcher(t).replaceAll(" ").trim();
    }

    /** Dashes outside scripture: "10-15" is "10 to 15", "9:00-11:00" likewise, a spaced " - " is a pause. */
    private static String ranges(String s, boolean fil) {
        String to = fil ? " hanggang " : " to ";
        s = TIME_RANGE.matcher(s).replaceAll("$1" + to + "$2");
        s = NUM_RANGE.matcher(s).replaceAll("$1" + to + "$2");
        return SPACED_DASH.matcher(s).replaceAll(", ");
    }

    private static String book(String n, String b, boolean fil) {
        String name = EXPAND.containsKey(b) ? EXPAND.get(b) : b;
        if (n == null) return name;
        if (fil) name = name.replaceFirst("^Kay ", ""); // "II Kay Timoteo" is said "Ikalawang Timoteo"
        int k = n.equals("1") || n.equals("I") ? 1 : n.equals("2") || n.equals("II") ? 2 : 3;
        String[] en = {"First", "Second", "Third"};
        String[] tl = {"Unang", "Ikalawang", "Ikatlong"};
        return (fil ? tl[k - 1] : en[k - 1]) + " " + name;
    }

    private static String verses(String spec, boolean fil) {
        String[] parts = spec.split("\\s?,\\s?");
        boolean plural = parts.length > 1;
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            Matcher r = RANGE_PART.matcher(p.trim());
            if (r.matches()) {
                plural = true;
                if (r.group(3) != null) {
                    out.add(r.group(1) + (fil ? " hanggang kabanata " : " to chapter ") + r.group(2)
                            + (fil ? " talata " : " verse ") + r.group(3));
                } else {
                    out.add(r.group(1) + (fil ? " hanggang " : " to ") + r.group(2));
                }
            } else {
                out.add(p.trim());
            }
        }
        StringBuilder sb = new StringBuilder(fil ? (plural ? "mga talata " : "talata ") : (plural ? "verses " : "verse "));
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append(i == out.size() - 1 ? (fil ? " at " : " and ") : ", ");
            sb.append(out.get(i));
        }
        return sb.toString();
    }
}
