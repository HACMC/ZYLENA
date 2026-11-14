package com.zylena.screenreader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns raw screen text into speakable pieces: strips what a voice would read badly (links, emoji,
 * symbols), splits into sentences, and tags each one English or Tagalog so Taglish text is spoken
 * by the right voice.
 */
final class TextPrep {
    static final String EN = "en";
    static final String FIL = "fil";

    static final class Seg {
        final String text;
        final String lang;
        /** Index of the screen line this came from (first and last line when several are joined). */
        final int line, lastLine;
        /** A short title line (no closing punctuation): the reader pauses a little longer around it. */
        final boolean heading;
        /** Already translated (Translate on): read as it is, never translated back. */
        final boolean translated;
        /** The Bible reference printed right after this text ("Acts 2:17"), when the text is a quoted verse. */
        String ref;

        Seg(String text, String lang, int line, int lastLine) {
            this(text, lang, line, lastLine, false);
        }

        Seg(String text, String lang, int line, int lastLine, boolean heading) {
            this(text, lang, line, lastLine, heading, false);
        }

        Seg(String text, String lang, int line, int lastLine, boolean heading, boolean translated) {
            this.text = text;
            this.lang = lang;
            this.line = line;
            this.lastLine = lastLine;
            this.heading = heading;
            this.translated = translated;
        }
    }

    private static final Pattern URL = Pattern.compile("(https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+\\.\\S+");
    private static final Pattern PESO = Pattern.compile("(?:[₱]|\\b(?:PHP|Php|php|P)(?=\\s?\\d))\\s?(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern IKA = Pattern.compile("\\b([Ii])ka-?\\s?(\\d{1,2})\\b");
    private static final String[] BILANG = {"", "isa", "dalawa", "tatlo", "apat", "lima", "anim", "pito", "walo", "siyam", "sampu"};

    /** "ika-5" is "ikalima", "ika-2" is "ikalawa", "ika-12" is "ika-labindalawa" (a voice would say "ika five"). */
    private static String tagalogOrdinals(String s) {
        java.util.regex.Matcher m = IKA.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int n = Integer.parseInt(m.group(2));
            String word;
            if (n == 1) word = "una";
            else if (n == 2) word = "ikalawa";
            else if (n == 3) word = "ikatlo";
            else if (n == 4) word = "ikaapat";
            else if (n <= 10) word = "ika" + BILANG[n];
            else if (n < 20) word = "ikalabing-" + BILANG[n - 10];
            else word = m.group(0);
            if (Character.isUpperCase(m.group(1).charAt(0))) word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
            // "ika-2 linggo" is said "ikalawang linggo"; before "ng"/"sa" or punctuation it stays "ikalawa".
            String after = s.substring(m.end());
            if (word.matches(".*[aeiou]$") && after.matches("^ (?!ng\\b|sa\\b|na\\b)\\p{L}[\\s\\S]*")) word += "ng";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(word));
        }
        m.appendTail(sb);
        return sb.toString();
    }
    private static final Pattern DOLLAR = Pattern.compile("\\$\\s?(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern PERCENT = Pattern.compile("(\\d)\\s?%");
    private static final Pattern JUNK = Pattern.compile("[\\p{So}\\p{Cs}\\p{Cn}\\p{Cf}•·●○◦▪■□▶►◀◄→←↑↓|~^*_#<>{}\\[\\]\\\\]+");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+|\\n+");
    private static final Pattern REPEAT_PUNCT = Pattern.compile("([.!?,])\\1{2,}");
    private static final Pattern SPACES = Pattern.compile("[\\p{Z}\\t]+"); // also thin and no-break spaces
    /** A name's initial, "Ellen G. White": the full stop isn't a sentence end, so the voice shouldn't pause. */
    private static final Pattern INITIAL = Pattern.compile("(?<![\\p{L}\\p{N}])(\\p{Lu})\\.(?=\\s*\\p{Lu}(?:\\p{Ll}|\\.))");
    private static final Pattern SHOUT = Pattern.compile("\\b\\p{Lu}{5,}\\b");
    private static final Pattern WORD = Pattern.compile("(?<![\\p{L}/])[\\p{L}/.]+(?![\\p{L}])");

    /** Chat shorthand and abbreviations a person would say in full. Keys are lower case. */
    private static final java.util.Map<String, String> SAY = new java.util.HashMap<>();

    static {
        String[] pairs = {
                "pls", "please", "plz", "please", "thx", "thanks", "tnx", "thanks", "ty", "thank you",
                "btw", "by the way", "idk", "I don't know", "omg", "oh my gosh", "asap", "as soon as possible",
                "w/", "with", "w/o", "without", "e.g.", "for example", "i.e.", "that is", "etc.", "et cetera",
                "vs.", "versus", "vs", "versus", "approx.", "about",
                // Taglish texting
                "lng", "lang", "nmn", "naman", "kc", "kasi", "ksi", "kasi", "sna", "sana", "pra", "para",
                "hnd", "hindi", "hndi", "hindi", "nde", "hindi", "ung", "yung", "yng", "yung", "tlga", "talaga",
                "sge", "sige", "slmt", "salamat", "sbi", "sabi", "d2", "dito", "dun", "doon",
                "nlng", "na lang", "khit", "kahit", "pde", "pwede", "pwd", "pwede", "cge", "sige", "aq", "ako",
                "mgnda", "maganda", "mhal", "mahal", "kmusta", "kumusta", "musta", "kumusta",
                "nyo", "ninyo", "nya", "niya", "sya", "siya", "pano", "paano", "bkt", "bakit", "nman", "naman",
                "nmn", "naman", "ngaun", "ngayon", "ngyn", "ngayon", "dba", "di ba", "diba", "di ba", "tlg", "talaga",
                "kht", "kahit", "lht", "lahat", "wla", "wala", "mron", "meron", "slamat", "salamat", "tnx po", "thanks po",
                "gud", "good", "mr.", "Mister", "mrs.", "Missus", "jr.", "Junior", "sr.", "Senior", "st.", "Saint", "mt.", "Mount",
                // Filipino titles and places
                "gng.", "Ginang", "bb.", "Binibini", "sto.", "Santo", "sta.", "Santa", "brgy.", "Barangay",
                "dr.", "Doctor", "atty.", "Attorney", "engr.", "Engineer", "pres.", "Presidente", "kgg.", "Kagalang-galang",
                "pr.", "Pastor", "ptr.", "Pastor", "bro.", "Brother", "sis.", "Sister", "blvd.", "Boulevard",
                "ave.", "Avenue", "hrs", "hours", "mins", "minutes"};
        for (int i = 0; i < pairs.length; i += 2) SAY.put(pairs[i], pairs[i + 1]);
    }

    /**
     * Bible names in Tagalog text follow the Spanish way of saying J and C: "Jesu-Cristo" is "Hesu Kristo",
     * "Juan" is "Huwan". The phone's Tagalog voice says them the English way, so they are respelled.
     */
    private static final String[][] TAGALOG_SAY = {
            {"Jesu-?Cristo|Hesu-?Kristo|Jesucristo|Hesukristo", "Hesu Kristo"},
            {"Jesus|Jesús", "Hesus"}, {"Cristo", "Kristo"}, {"Juan", "Huwan"}, {"Jose|José", "Hose"},
            {"Jerusalem|Jerusalen", "Herusalem"}, {"Jeremias|Jeremías", "Heremias"}, {"Josue|Josué", "Hosue"},
            {"Juda", "Huda"}, {"Judas", "Hudas"}, {"Judea", "Hudea"}, {"Jordan|Jordán", "Hordan"},
            {"Jehova|Jehová", "Hehoba"}, {"Santiago", "Santiyago"},
            // The voice slurs "siya" into "sha": the accent keeps it pro-pe-SI-ya.
            {"propesiya", "propesya"}, {"propesiyang", "propesyang"}, {"mga propesiya", "mga propesya"}}; // said the way people say it: pro-PES-ya
    private static final Pattern[] TAGALOG_SAY_P = new Pattern[TAGALOG_SAY.length];

    static {
        for (int i = 0; i < TAGALOG_SAY.length; i++) {
            TAGALOG_SAY_P[i] = Pattern.compile("(?<![\\p{L}-])(?:" + TAGALOG_SAY[i][0] + ")(?![\\p{L}-])", Pattern.CASE_INSENSITIVE);
        }
    }

    /** "-syon" is said "-shon" (liksyon = lik-SHON); the Tagalog voice says "lik-si-yon". */
    private static final Pattern SYON = Pattern.compile("(?<=\\p{L})([sS])([yY])([oO][nN])(?=g?\\b|es\\b)");

    private static final Pattern NAME = Pattern.compile("\\b[Zz][Yy][Ll][Ee][Nn][Aa]\\b");

    /**
     * ZYLENA is said like "Selena" with a Z (zuh-LEE-nuh), not "Zy-lena" or spelled out. Spelled for each
     * voice so it comes out that way: English "Zeleena", Tagalog "Zelina".
     */
    static String sayName(String s, String lang) {
        // A translation can bring back "Ellen G. White": the voice would stop at "G." as at a sentence end.
        s = eraDots(titles(s, FIL.equals(lang))); // "A.D." first, or "A." would be taken for an initial
        s = INITIAL.matcher(s).replaceAll("$1 ").replace("  ", " ");
        return sayDates(NAME.matcher(s).replaceAll(FIL.equals(lang) ? "Zelina" : "Zeleena"), lang);
    }

    /**
     * A title or a shortened first name before a name is said in full: "Mrs. White" would stop at "Mrs." as at
     * a sentence end. A translation brings these back even when the page said "Missus".
     */
    private static final String[][] TITLES = {
            {"Mrs", "Missus", "Misis"}, {"Mr", "Mister", "Mister"}, {"Ms", "Miz", "Miz"}, {"Dr", "Doctor", "Doktor"}, // first four
            {"Rev", "Reverend", "Reberendo"}, {"Eld", "Elder", "Elder"}, {"Pr", "Pastor", "Pastor"}, {"Ptr", "Pastor", "Pastor"},
            {"Gng", "Ginang", "Ginang"}, {"Bb", "Binibini", "Binibini"}, {"Sto", "Santo", "Santo"}, {"Sta", "Santa", "Santa"},
            {"St", "Saint", "Saint"}, {"Mt", "Mount", "Bundok"}, {"Prof", "Professor", "Propesor"}, {"Gen", "General", "Heneral"},
            {"Capt", "Captain", "Kapitan"}, {"Col", "Colonel", "Koronel"}, {"Gov", "Governor", "Gobernador"}, {"Pres", "President", "Pangulong"},
            {"Wm", "William", "William"}, {"Geo", "George", "George"}, {"Chas", "Charles", "Charles"}, {"Thos", "Thomas", "Thomas"},
            {"Jas", "James", "James"}, {"Jno", "John", "John"}, {"Jos", "Joseph", "Joseph"}, {"Benj", "Benjamin", "Benjamin"},
            {"Robt", "Robert", "Robert"}, {"Saml", "Samuel", "Samuel"}, {"Edw", "Edward", "Edward"}, {"Hy", "Henry", "Henry"}};
    private static final Pattern[] TITLE_P = new Pattern[TITLES.length];

    static {
        // Only before a capitalized name: "Gen. 1:1" (Genesis) has a number after it.
        for (int i = 0; i < TITLES.length; i++) TITLE_P[i] = Pattern.compile("\\b" + TITLES[i][0]
                + (i < 4 ? "\\.?" : "\\.") + "(?=\\s+\\p{Lu}\\p{Ll})"); // "Mr", "Mrs", "Ms", "Dr" without a full stop too
    }

    static String titles(String s, boolean fil) {
        for (int i = 0; i < TITLES.length; i++) {
            if (s.indexOf(TITLES[i][0]) < 0) continue;
            s = TITLE_P[i].matcher(s).replaceAll(TITLES[i][fil ? 2 : 1]);
        }
        return s;
    }

    static String sayTagalog(String s) {
        for (int i = 0; i < TAGALOG_SAY.length; i++) s = TAGALOG_SAY_P[i].matcher(s).replaceAll(TAGALOG_SAY[i][1]);
        java.util.regex.Matcher m = SYON.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, m.group(1) + (Character.isUpperCase(m.group(2).charAt(0)) ? "H" : "h") + m.group(3));
        m.appendTail(sb);
        return sb.toString();
    }

    /** "SABBATH" becomes "Sabbath" so a voice says the word instead of spelling it; short acronyms stay. */
    private static String unshout(String s) {
        java.util.regex.Matcher m = SHOUT.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String w = m.group();
            m.appendReplacement(sb, w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String sayInFull(String s) {
        java.util.regex.Matcher m = WORD.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String w = m.group();
            String full = SAY.get(w.toLowerCase(Locale.ROOT));
            // "E.G. White" is a name's initials, not "e.g."
            if (full != null && w.matches("(?:\\p{Lu}\\.)+") && (w.equalsIgnoreCase("e.g.") || w.equalsIgnoreCase("i.e."))) full = null;
            if (full == null && w.endsWith(".")) {
                // "Pls." or "lng." at a sentence end: expand the word, keep the full stop.
                String bare = SAY.get(w.substring(0, w.length() - 1).toLowerCase(Locale.ROOT));
                if (bare != null) full = bare + ".";
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(full != null ? full : w));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Words that are Tagalog and not also common English words. */
    private static final Set<String> TAGALOG = new HashSet<>(Arrays.asList(
            "ang", "ng", "mga", "sa", "na", "ay", "ako", "ikaw", "ka", "mo", "ko", "siya", "kami", "tayo",
            "kayo", "sila", "ito", "iyan", "yan", "yun", "iyon", "hindi", "di", "wala", "mayroon", "meron",
            "po", "opo", "ho", "oho", "ba", "naman", "lang", "lamang", "din", "rin", "pa", "nang", "kung",
            "kapag", "pag", "para", "kasi", "pero", "ngunit", "salamat", "paki", "pakiusap", "magandang",
            "umaga", "tanghali", "hapon", "gabi", "kumusta", "kamusta", "ano", "sino", "saan", "kailan",
            "bakit", "paano", "ilan", "magkano", "mahal", "gusto", "ayaw", "oo", "tapos", "sige", "talaga",
            "sobra", "muna", "ulit", "daw", "raw", "pala", "nga", "ni", "kay", "si", "sina", "nina", "dito",
            "doon", "diyan", "ngayon", "bukas", "kahapon", "araw", "linggo", "buwan", "taon", "mag", "nag",
            "pumunta", "gawa", "kain", "inom", "tulog", "bahay", "tao", "kaibigan", "pamilya", "trabaho",
            "pasok", "uwi", "alis", "balik", "sabi", "sabihin", "tingnan", "tignan", "basahin", "buksan",
            "isara", "pindutin", "ipadala", "tanggapin", "tanggihan", "kanselahin", "magpatuloy", "bumalik",
            "mensahe", "mensahe", "bago", "luma", "lahat", "wala", "mayroong", "habang", "dahil", "kaya",
            "upang", "ayos", "mabuti", "maganda", "masaya", "malungkot", "galit", "takot", "mahal", 
            "kita", "natin", "namin", "ninyo", "nila", "akin", "iyo", "amin", "atin", "kanila", "yung", "iyong",
            "kabanata", "talata", "hanggang", "sana", "siguro", "baka", "huwag", "wag", "hwag", "tara", "tayo", "pwede", "puwede", "dapat", "kailangan",
            // More everyday, church and Bible words
            "kanya", "kaniya", "kanyang", "kaniyang", "niya", "niyang", "kanila", "kanilang", "atin", "ating", "aming",
            "inyo", "inyong", "nito", "noon", "ngayong", "rito", "roon", "dito", "ganito", "ganoon", "ganyan", "lamang",
            "upang", "nang", "ngunit", "subalit", "datapuwa't", "sapagkat", "sapagka't", "kundi", "kaysa", "bawat",
            "lahat", "isa", "dalawa", "tatlo", "apat", "lima", "anim", "pito", "walo", "siyam", "sampu", "una",
            "ikalawa", "ikatlo", "bagay", "salita", "panahon", "buhay", "mundo", "langit", "lupa", "tubig", "puso",
            "isip", "diyos", "dios", "panginoon", "espiritu", "kasulatan", "biblia", "aklat", "liksyon", "aralin",
            "pag-aaral", "basahin", "pagbasa", "sauluhin", "pahayag", "hula", "propeta", "propesiya", "alagad",
            "apostol", "iglesia", "iglesya", "simbahan", "pananampalataya", "pag-ibig", "pagibig", "biyaya", "kaligtasan",
            "kasalanan", "katotohanan", "kaharian", "patotoo", "mensahe", "tao", "mga", "bata", "anak", "ama", "ina",
            "kapatid", "kaibigan", "guro", "araw-araw", "linggo", "sabado", "lunes", "martes", "miyerkules",
            "huwebes", "biyernes", "umaga", "hapon", "gabi", "oras", "minuto", "sino", "kanino", "alin", "gaano",
            "mabuti", "masama", "malaki", "maliit", "marami", "kaunti", "mahusay", "maayos", "malinis", "bagong",
            "lumang", "ibang", "iba", "pareho", "totoo", "tunay", "tama", "mali", "talagang", "lalo", "lagi", "palagi",
            "minsan", "kahit", "basta", "naman", "nga", "pa", "na", "eh", "kasi", "kaya", "parang", "tulad", "gaya",
            "tungkol", "ayon", "bilang", "habang", "pagkatapos", "bago", "matapos", "noong", "kapag", "kung",
            "ikaw", "kayo", "ninyo", "namin", "natin", "nila", "niya", "akin", "iyo", "kanya", "sa", "ng", "ang",
            "si", "ni", "kay", "sina", "nina", "kina", "ay", "ito", "iyan", "iyon", "yan", "yun", "yung", "ung",
            "oo", "hindi", "wala", "meron", "mayroon", "may", "salamat", "paalam", "ingat", "sige", "opo", "po"));

    /** Common English words that are not Tagalog: one of these marks a line as English. */
    private static final Set<String> ENGLISH = new HashSet<>(Arrays.asList(
            "the", "and", "of", "to", "is", "are", "was", "were", "you", "your", "for", "with", "this", "that", "it",
            "on", "be", "will", "can", "not", "by", "from", "or", "have", "has", "more", "what", "when", "how", "who",
            "they", "we", "our", "my", "me", "he", "she", "his", "her", "their", "there", "here", "about", "all",
            "an", "do", "does", "did", "if", "but", "so", "just", "like", "see", "get", "new", "now", "one", "out",
            "up", "back", "read", "open", "close", "share", "settings", "search", "home", "menu", "videos", "next",
            "previous", "today", "week", "lesson", "chapter", "verse", "verses", "god", "lord", "jesus", "i", "am"));

    /** Names of icon buttons that apps expose to screen readers; not worth reading aloud. */
    private static final Set<String> UI_NOISE = new HashSet<>(Arrays.asList(
            "arrow back", "arrow drop down", "arrow forward", "arrow drop up", "more", "more options", "navigate up",
            "back", "menu", "close", "dismiss", "expand", "collapse", "drag handle", "overflow menu", "videos", "share",
            "search", "settings", "play", "pause", "next", "previous", "bookmark", "download"));

    private TextPrep() {
    }

    /** True when the text has an English word and no Tagalog word. */
    static boolean clearlyEnglish(String text) {
        boolean en = false;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}']+")) {
            if (w.isEmpty()) continue;
            if (TAGALOG.contains(w)) return false;
            if (ENGLISH.contains(w)) en = true;
        }
        return en;
    }

    /** Language of a piece: Tagalog words decide; clearly English stays English; anything unclear follows the screen. */
    static String langOf(String text, boolean screenFil) {
        if (detect(text).equals(FIL)) return FIL;
        if (clearlyEnglish(text)) return EN;
        return screenFil ? FIL : EN;
    }

    /** Whether most of the screen is Tagalog, so titles like "Pahayag at Inspirasyon" get the Tagalog voice. */
    static boolean mostlyTagalog(List<String> lines) {
        int fil = 0, en = 0;
        for (String l : lines) {
            for (String w : l.toLowerCase(Locale.ROOT).split("[^\\p{L}']+")) {
                if (TAGALOG.contains(w)) fil++;
                else if (ENGLISH.contains(w)) en++;
            }
        }
        return fil > en;
    }

    // ---- Page numbers and book codes are not read ("Page 229", "EW 15.1", "(1SM 123.2)", "Ms 12, 1890") ----
    private static final String CODE = "(?:\\d{1,2}\\s?)?[A-Z][A-Za-z]{0,5}\\s?(?:\\d{1,4}|[ivxlcdm]{1,7}|[IVXLCDM]{1,7})\\.\\d{1,3}(?:\\s?[-–]\\s?\\d{1,3})?";
    private static final String SOURCE = "(?:Ms|MS|Lt|MR|Letter|Manuscript|RH|ST|YI|Sp\\.?\\s?T)\\s?\\d{1,4}[a-z]?,?\\s\\d{4}";
    private static final String PAGE = "(?i:Page|Pg|Pp?|Pahina|Pah)\\.?\\s?\\d{1,4}(?:\\s?[-–]\\s?\\d{1,4})?";
    private static final Pattern CODE_LINE = Pattern.compile("^[\\s(\\[—–-]*(?:" + CODE + "|" + SOURCE + "|" + PAGE + "|\\[\\d{1,4}\\])[\\s)\\].,;:—–-]*$");
    private static final Pattern CODE_END = Pattern.compile("[\\s—–(\\[-]+(?:" + CODE + "|" + SOURCE + "|[A-Z]{2,5}\\s(?:[ivxlc]{2,7}|[IVXLC]{2,7}))\\.?[\\s)\\].]*$");
    /** A code stuck straight to the end of a sentence ("...Spirit of God.EW v.3"), anywhere in the line. */
    private static final Pattern CODE_STUCK = Pattern.compile("(?<=[.!?,;:”\"’)\\]])(?:(?:\\d[A-Z]{1,4}|[A-Z]{2,5}|[A-Z][a-z]{1,4})\\s?(?:\\d{1,4}|[ivxlcdm]{1,7}|[IVXLCDM]{1,7})\\.\\d{1,3}(?:\\s?[-–]\\s?\\d{1,3})?"
            + "|[A-Z]{2,5}\\s?(?:[ivxlc]{1,7}|[IVXLC]{1,7}))(?![\\p{L}\\d])");
    private static final Pattern CODE_PAREN = Pattern.compile("\\s*[(\\[]\\s*(?:" + CODE + "|" + SOURCE + "|" + PAGE + ")\\s*[)\\]]");
    private static final Pattern PAGE_BRACKET = Pattern.compile("\\s*\\[\\s*" + PAGE + "\\s*\\]", Pattern.CASE_INSENSITIVE);

    /** The line without page numbers and book codes; empty when the whole line was one. */
    static volatile String lastCodes = "";
    /** A line with several references that was read all the same (for the log). */
    static volatile String lastRefLine = "";
    private static final Pattern ODD_SPACE = Pattern.compile("[\\p{Z}\\u200B-\\u200D\\u2060\\uFEFF]+");
    /** A short line that is only letters, a number (or roman numeral), a full stop and a number: "EW vi.1". */
    private static final Pattern SHORT_CODE = Pattern.compile("^[\\s(\\[—–-]*\\d?\\p{L}{1,6}\\s?(?:\\d{1,4}|[ivxlcdmIVXLCDM]{1,7})\\s?[.,:·]\\s?\\d{1,3}[\\s)\\].,;:—–-]*$");

    static String dropCodes(String line) {
        String was = line.trim();
        String r = dropCodes0(ODD_SPACE.matcher(line).replaceAll(" "));
        if (!r.equals(was) && lastCodes.length() < 160) lastCodes += (lastCodes.isEmpty() ? "" : " | ") + (was.length() > 40 ? was.substring(Math.max(0, was.length() - 40)) : was);
        return r;
    }

    private static String dropCodes0(String line) {
        String t = line.trim();
        // "Daniel 8:14" looks like a book code ("EW 15.1") but is a Bible reference: the reference rules decide.
        if (t.length() <= 16 && SHORT_CODE.matcher(t).matches() && !Bible.onlyRef(t)) return "";
        if (CODE_LINE.matcher(t).matches()) return "";
        t = CODE_STUCK.matcher(t).replaceAll("");
        t = CODE_PAREN.matcher(t).replaceAll("");
        t = PAGE_BRACKET.matcher(t).replaceAll("");
        t = CODE_END.matcher(t).replaceAll("");
        return t.trim();
    }

    // ---- A.D. and B.C. and calendar years ----
    private static final Pattern ERA = Pattern.compile("(?<![\\p{L}.])(A\\.D|B\\.C\\.E|B\\.C)\\.?(?![\\p{L}])");

    /** "A.D." and "B.C." lose their dots, so the dot isn't taken for a sentence end: "A.D. 31" becomes "AD 31". */
    private static String eraDots(String s) {
        java.util.regex.Matcher m = ERA.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String era = m.group(1).replace(".", "");
            String after = s.substring(m.end());
            // A full stop that really ends the sentence stays.
            boolean dot = m.group().endsWith(".") && (after.trim().isEmpty() || after.matches("^\\s+[\\p{Lu}\"“‘'(].*"));
            m.appendReplacement(sb, era + (dot ? "." : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    private static String below100(int n) {
        if (n < 20) return ONES[n];
        return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + ONES[n % 10]);
    }

    /** How a year is said in English: 457 "four fifty-seven", 1844 "eighteen forty-four", 1905 "nineteen oh five", 2026 "twenty twenty-six". */
    static String yearWords(int y) {
        if (y < 100) return below100(y);
        if (y >= 2000 && y <= 2009) return y == 2000 ? "two thousand" : "two thousand " + ONES[y - 2000];
        if (y == 1000) return "one thousand";
        int hi = y / 100, lo = y % 100;
        if (lo == 0) return below100(hi) + " hundred";
        return below100(hi) + " " + (lo < 10 ? "oh " + ONES[lo] : below100(lo));
    }

    private static final String MONTHS = "(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec)\\.?";
    private static final Pattern YEAR_ERA_AFTER = Pattern.compile("\\b(\\d{1,4})\\s?(AD|BCE|BC)\\b");
    private static final Pattern YEAR_ERA_BEFORE = Pattern.compile("\\b(AD|BCE|BC)\\s?(\\d{1,4})\\b");
    private static final Pattern YEAR_CUE = Pattern.compile("\\b(in|since|until|till|by|before|after|from|during|year|circa|noong|nang|taong|taon)\\s(1[0-9]{3}|20[0-9]{2})\\b(?![.,]?\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR_DATE = Pattern.compile("\\b(" + MONTHS + "\\s\\d{1,2},?\\s)(1[0-9]{3}|20[0-9]{2})\\b");
    /** Tagalog: "noong 1844", "dekada ng 1850", "Oktubre 22, 1844" — said as English years, the way people say them. */
    private static final Pattern YEAR_CUE_FIL = Pattern.compile("\\b(noong|nang|taong|taon|ng|mula|hanggang|bago|pagkatapos|simula|"
            + "(?:Enero|Pebrero|Marso|Abril|Mayo|Hunyo|Hulyo|Agosto|Setyembre|Oktubre|Nobyembre|Disyembre)\\s\\d{1,2},?)\\s(1[0-9]{3}|20[0-9]{2})\\b(?![.,]?\\d)",
            Pattern.CASE_INSENSITIVE);
    /** Tagalog "mga pahina 232 hanggang 240": page numbers said in English, the way a reader says them. */
    private static final Pattern PAGES_FIL = Pattern.compile("\\b([Pp]ahina(?:ng)?)\\s(\\d{1,4})(?:(\\s?(?:hanggang|at|-|\u2013)\\s?)(\\d{1,4}))?\\b(?![.,]?\\d)");

    /** 232 "two hundred thirty-two", 1200 "one thousand two hundred". */
    static String numberWords(int n) {
        if (n < 100) return below100(n);
        if (n < 1000) return ONES[n / 100] + " hundred" + (n % 100 == 0 ? "" : " " + below100(n % 100));
        return below100(n / 1000) + " thousand" + (n % 1000 == 0 ? "" : " " + numberWords(n % 1000));
    }

    /** "the 1850's", "1840s": "eighteen fifties". */
    private static final Pattern DECADE = Pattern.compile("\\b(1[0-9]|20)([2-9])0(?:['\u2019]?s)\\b");
    private static final Pattern ERA_WORD = Pattern.compile("(?<![\\p{L}])(AD|BCE|BC)(?![\\p{L}])");

    private static String era(String e, boolean fil) {
        return e.equals("AD") ? "A.D." : e.equals("BC") ? "B.C." : "B.C.E.";
    }

    /**
     * What a voice should be given for eras and years, just before it speaks (so a translation sees the
     * original "A.D. 31"): the eras as letters, and in English the years as years, not as "one thousand
     * eight hundred forty-four". In Tagalog too, years are said that way, as people say them, and page
     * numbers in English ("pahina two hundred thirty-two"); other Tagalog numbers are left to the Tagalog voice.
     */
    static String sayDates(String s, String lang) {
        boolean fil = FIL.equals(lang);
        s = Bible.sayRefs(s, fil); // "Juan three verse sixteen"
        // Eras in both languages (a translation brings back "B.C. 457"): "four fifty-seven B.C.", "A.D. thirty-four".
        s = eraDots(s);
        s = replaceAll(YEAR_ERA_AFTER, s, m -> yearWords(Integer.parseInt(m.group(1))) + " " + era(m.group(2), false));
        s = replaceAll(YEAR_ERA_BEFORE, s, m -> m.group(1).equals("AD") ? "A.D. " + yearWords(Integer.parseInt(m.group(2)))
                : yearWords(Integer.parseInt(m.group(2))) + " " + era(m.group(1), false));
        if (!fil) {
            s = replaceAll(YEAR_CUE, s, m -> m.group(1) + " " + yearWords(Integer.parseInt(m.group(2))));
            s = replaceAll(YEAR_DATE, s, m -> m.group(1) + yearWords(Integer.parseInt(m.group(2))));
        } else {
            s = replaceAll(YEAR_CUE_FIL, s, m -> m.group(1) + " " + yearWords(Integer.parseInt(m.group(2))));
            s = replaceAll(PAGES_FIL, s, m -> m.group(1) + " " + numberWords(Integer.parseInt(m.group(2)))
                    + (m.group(4) == null ? "" : (m.group(3).trim().matches("[-\u2013]") ? " hanggang " : m.group(3)) + numberWords(Integer.parseInt(m.group(4)))));
        }
        s = replaceAll(DECADE, s, m -> below100(Integer.parseInt(m.group(1))) + " " + TENS[Integer.parseInt(m.group(2))].replaceAll("y$", "ies"));
        return replaceAll(ERA_WORD, s, m -> era(m.group(1), fil)).replaceAll("(A\\.D\\.|B\\.C\\.(?:E\\.)?)\\.", "$1");
    }

    private static String replaceAll(Pattern p, String s, java.util.function.Function<java.util.regex.Matcher, String> f) {
        java.util.regex.Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(f.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }

    /** A translated paragraph shared out over n runs again, in order, cut at sentence ends (else commas, else spaces). */
    /** A Bible verse kept in English inside Tagalog: read with the English voice (see Translate.VERSE_ON). */
    static final Pattern VERSE_SPAN = Pattern.compile("\u000E[^\u000F]*\u000F");

    static List<String> shareOut(String text, int n) {
        // A verse kept whole (marked \u000E…\u000F) is never cut: its spaces are hidden while cutting.
        java.util.regex.Matcher vm = VERSE_SPAN.matcher(text);
        StringBuffer vb = new StringBuffer();
        while (vm.find()) vm.appendReplacement(vb, java.util.regex.Matcher.quoteReplacement(vm.group().replace(' ', '\u0005')));
        vm.appendTail(vb);
        List<String> cut = shareOut0(vb.toString(), n);
        for (int i = 0; i < cut.size(); i++) cut.set(i, cut.get(i).replace('\u0005', ' '));
        return cut;
    }

    private static List<String> shareOut0(String text, int n) {
        List<String> pieces = new ArrayList<>(Arrays.asList(text.trim().split("(?<=[.!?…])\\s+")));
        while (pieces.size() < n) {
            int at = 0;
            for (int i = 1; i < pieces.size(); i++) if (pieces.get(i).length() > pieces.get(at).length()) at = i;
            String longest = pieces.get(at);
            int cut = longest.indexOf(',', longest.length() / 3);
            if (cut < 0 || cut > longest.length() * 2 / 3) cut = longest.indexOf(' ', longest.length() / 2);
            if (cut <= 0 || cut >= longest.length() - 1) break;
            pieces.set(at, longest.substring(0, cut + 1).trim());
            pieces.add(at + 1, longest.substring(cut + 1).trim());
        }
        List<String> out = new ArrayList<>();
        int total = 0;
        for (String p : pieces) total += p.length() + 1;
        int left = pieces.size(), k = 0;
        for (int r = 0; r < n; r++) {
            StringBuilder sb = new StringBuilder();
            int want = (total / n) * (r + 1);
            int runsLeft = n - r;
            int used = 0;
            for (int q = 0; q < r; q++) used += out.get(q).length() + 1;
            while (k < pieces.size() && (sb.length() == 0 || (pieces.size() - k > runsLeft - 1 && used + sb.length() < want))) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(pieces.get(k++));
            }
            out.add(sb.length() == 0 ? "." : sb.toString());
        }
        return out;
    }

    /** Hyphens and dashes that look like "-" or "–" but are other characters (a page's no-break hyphen, a minus sign). */
    private static final Pattern ODD_HYPHEN = Pattern.compile("[\u2010\u2011\u2012\u2212\uFE58\uFE63\uFF0D]");

    static String clean(String s) {
        s = ODD_HYPHEN.matcher(s).replaceAll("-").replace('\u2015', '\u2014');
        s = Bible.fixNames(s);
        s = eraDots(s);
        s = URL.matcher(s).replaceAll(" link ");
        s = EMAIL.matcher(s).replaceAll(" email address ");
        s = PESO.matcher(s).replaceAll("$1 pesos");
        s = tagalogOrdinals(s);
        s = DOLLAR.matcher(s).replaceAll("$1 dollars");
        s = PERCENT.matcher(s).replaceAll("$1 percent");
        s = JUNK.matcher(s).replaceAll(" ");
        s = REPEAT_PUNCT.matcher(s).replaceAll("$1");
        s = s.replace(" & ", " and ");
        s = sayInFull(unshout(s));
        s = INITIAL.matcher(s).replaceAll("$1 ");
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    /** Tagalog affixes and doubled words: "nagsalita", "pinakamahusay", "ipinaalam", "dahan-dahan". */
    private static final Pattern TAGALOG_SHAPE = Pattern.compile(
            "^(?:nag|mag|pag|ipag|ipina|ipinag|pinag|nakaka|naka|maka|makapag|nakapag|ika|paki|pinaka|napaka"
                    + "|magpa|nagpa|pagka)(?:[bdghklmnprstwy][aeiou]){2,}[a-z]*$");
    private static final Pattern DOUBLED = Pattern.compile("^(\\p{L}{2,})-\\1\\p{L}*$");

    /** One word's vote: 1 Tagalog, 0 unknown, -1 English. Contractions like "ako'y" and "sapagka't" count as their word. */
    private static int vote(String w) {
        if (w.isEmpty()) return 0;
        if (TAGALOG.contains(w)) return 1;
        if (ENGLISH.contains(w)) return -1;
        if (w.endsWith("'y") || w.endsWith("'t")) return TAGALOG.contains(w.substring(0, w.length() - 2)) ? 1 : 0;
        if (DOUBLED.matcher(w).matches()) return 1;
        // Shape alone is a weaker sign, so only longer words count (short English words look alike).
        if (w.length() >= 7 && TAGALOG_SHAPE.matcher(w).find()) return 1;
        return 0;
    }

    /** Why the last long paragraph was taken for Tagalog, for the log; cleared by the reader. */
    static volatile String lastWhy = "";

    /** "fil" when the sentence is clearly Tagalog or Taglish, otherwise "en". */
    static String detect(String sentence) {
        String[] words = sentence.toLowerCase(Locale.ROOT).split("[^\\p{L}'\\-]+");
        int total = 0, tagalog = 0, english = 0;
        StringBuilder why = new StringBuilder();
        for (String raw : words) {
            String w = raw.replaceAll("^[-']+|[-']+$", "");
            if (w.isEmpty()) continue;
            total++;
            int v = vote(w);
            if (v == 0 && w.contains("-")) {
                // "pag-aaral", "Mag-ingat": judge by the parts
                for (String part : w.split("-")) if (vote(part) > 0) v = 1;
            }
            if (v > 0) {
                tagalog++;
                if (why.length() < 120) why.append(why.length() == 0 ? "" : ", ").append(w);
            } else if (v < 0) english++;
        }
        if (total == 0) return EN;
        String lang;
        if (tagalog * 10 >= total * 3) lang = FIL;
        // Two Tagalog words make Taglish ("Ready na akong basahin"), but not in an English paragraph that
        // happens to hold words both languages have ("they may have", "Ina", "Sana"): with several English
        // words it takes the 30% above.
        else lang = tagalog >= 2 && english < 6 && tagalog * 2 > english ? FIL : EN;
        if (lang.equals(FIL) && total >= 15) lastWhy = tagalog + " Tagalog, " + english + " English of " + total + " words (" + why + ")";
        return lang;
    }

    /** Cleaned, sentence-split, language-tagged pieces; neighbours in the same language stay separate. */
    static volatile int lastDropped;
    static volatile boolean lastVerseMode;

    /** The user's own lists: "say this word like this", and words or phrases never to read. */
    static volatile List<String[]> userSay = new ArrayList<>();
    static volatile List<String> userSkip = new ArrayList<>();

    /** Parses "word = how to say it" lines (also "word -> say"); blank lines and lines without "=" are ignored. */
    static List<String[]> parseSay(String text) {
        List<String[]> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            String[] p = l.split("\\s*(?:=|->|→)\\s*", 2);
            if (p.length == 2 && !p[0].trim().isEmpty() && !p[1].trim().isEmpty()) out.add(new String[]{p[0].trim(), p[1].trim()});
        }
        return out;
    }

    static List<String> parseSkip(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) if (!l.trim().isEmpty()) out.add(l.trim());
        return out;
    }

    private static String applyUserLists(String s) {
        for (String w : userSkip) {
            s = Pattern.compile("(?<![\\p{L}\\d])" + Pattern.quote(w) + "(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(s).replaceAll(" ");
        }
        for (String[] p : userSay) {
            s = Pattern.compile("(?<![\\p{L}\\d])" + Pattern.quote(p[0]) + "(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(s).replaceAll(java.util.regex.Matcher.quoteReplacement(p[1]));
        }
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    static List<Seg> prepare(List<String> lines, boolean skipTimes) {
        return prepare(lines, skipTimes, false, false);
    }

    static List<Seg> prepare(List<String> lines, boolean skipTimes, boolean skipRefs) {
        return prepare(lines, skipTimes, skipRefs, false);
    }

    private static final Pattern BARE_RANGE = Pattern.compile("[;,\\s]*\\d{1,4}\\s?[-\u2013]\\s?\\d{1,4}[\\s.;,]*");
    private static final Pattern PAGES_END = Pattern.compile("(?i)\\bpages?\\s?\\d{1,4}(?:\\s?[-\u2013]\\s?\\d{1,4})?[\\s.;,]*$");

    /** The lines before (punctuation alone aside) end in "pages 232-240", or in another such bare range. */
    private static boolean afterPages(List<String> lines, int li) {
        for (int j = li - 1; j >= 0 && j >= li - 6; j--) {
            String l = lines.get(j);
            if (l.chars().noneMatch(Character::isLetterOrDigit)) continue;
            if (BARE_RANGE.matcher(l).matches()) continue;
            return PAGES_END.matcher(l).find();
        }
        return false;
    }

    private static final Pattern PAGE_ONLY = Pattern.compile("(?i)\\s*(?:pages?|pp?\\.?|pahina)\\s?\\d{1,4}(?:\\s?[-\u2013]\\s?\\d{1,4})?\\s*[.,;:]?\\s*");

    /** The line before ends on a word the sentence can't stop at ("of", "in", "on"): this line goes on with it. */
    private static boolean inSentence(List<String> lines, int li) {
        for (int j = li - 1; j >= 0 && j >= li - 3; j--) {
            String l = lines.get(j).trim();
            if (l.isEmpty()) continue;
            java.util.regex.Matcher w = LAST_WORD.matcher(l);
            return w.find() && HANGING.contains(w.group(1).toLowerCase(Locale.ROOT));
        }
        return false;
    }

    // A reference is a book and chapter:verses, or chapter:verses alone after one ("Jeremias 1:4–10; 26:1–15"),
    // joined by ; , and "at" / "and".
    private static final Pattern REFS_LINE = Pattern.compile("\\s*(?:(?:(?:(?:[1-3]|I{1,3})\\.?\\s?)?\\p{Lu}[\\p{L}.\\-]*\\.?\\s"
            + "(?:(?:(?:ng|mga|ni|of|kay)\\s)?\\p{Lu}[\\p{L}.\\-]*\\.?\\s){0,3})?"
            + "\\d{1,3}:\\d{1,3}(?:\\s?[-\u2013\u2014]\\s?\\d{1,3}(?::\\d{1,3})?)?"
            + "(?:,\\s?\\d{1,3}(?:\\s?[-\u2013\u2014]\\s?\\d{1,3})?(?![\\d:]))*(?:[\\s;,.]|\\b(?:at|and)\\b)*)+");

    private static final Pattern QUOTE_REF = Pattern.compile("([\u201C\"])([^\u201C\u201D\"]{12,}?)([\u201D\"])(\\s*\\(([^()]{3,80})\\))");

    /** Quotes followed by their reference whose verse is found: the English verse, marked for the English voice. */
    static String englishVerses(String s) {
        if (s.indexOf('(') < 0) return s;
        java.util.regex.Matcher m = QUOTE_REF.matcher(s);
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            String ref = Bible.firstRef(m.group(5)), en = ref == null ? null : Bible.englishFor(m.group(2), ref);
            if (en == null || !detect(m.group(2)).equals(FIL)) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
                continue;
            }
            any = true;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + "\u000E" + en + "\u000F" + m.group(3) + m.group(4)));
        }
        m.appendTail(sb);
        return any ? sb.toString() : s;
    }

    /** Applies f to the text outside kept verses only (a Tagalog respelling must not touch the English verse). */
    static String outsideVerses(String s, java.util.function.UnaryOperator<String> f) {
        if (s.indexOf('\u000E') < 0) return f.apply(s);
        java.util.regex.Matcher m = VERSE_SPAN.matcher(s);
        StringBuilder sb = new StringBuilder();
        int at = 0;
        while (m.find()) {
            sb.append(f.apply(s.substring(at, m.start()))).append(m.group());
            at = m.end();
        }
        return sb.append(f.apply(s.substring(at))).toString();
    }

    private static String hideVerseSpaces(String s) {
        java.util.regex.Matcher m = VERSE_SPAN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group().replace(' ', '\u0005')));
        m.appendTail(sb);
        return sb.toString();
    }

    /** readVerses: read the words of each cited verse (takes the place of skipRefs). */
    static List<Seg> prepare(List<String> lines, boolean skipTimes, boolean skipRefs, boolean readVerses) {
        List<Seg> out = new ArrayList<>();
        int dropped = 0;
        lastCodes = "";
        lastRefLine = "";
        boolean verse = Bible.verseMode(lines);
        boolean screenFil = mostlyTagalog(lines);
        lastVerseMode = verse;
        for (int li = 0; li < lines.size(); li++) {
            // A reference alone on its line belongs to the quote before it.
            if (lines.get(li).length() <= 40 && !out.isEmpty() && out.get(out.size() - 1).ref == null && Bible.onlyRef(lines.get(li))) {
                out.get(out.size() - 1).ref = Bible.firstRef(lines.get(li));
            }
            if (UI_NOISE.contains(lines.get(li).trim().toLowerCase(Locale.ROOT))) continue;
            if (lines.get(li).trim().matches("(?i)search \\d+ of \\d+")) continue; // the EGW app's search counter
            String cleaned = applyUserLists(dropCodes(clean(dropCodes(lines.get(li)))));
            // A link inside a sentence ("In this volume, on | page 13 | , Mrs. White states…") is part of it.
            boolean inSentence = inSentence(lines, li);
            if (cleaned.isEmpty() && inSentence && PAGE_ONLY.matcher(lines.get(li)).matches()) cleaned = applyUserLists(clean(lines.get(li)));
            // "See pages 232-240 ; 254-258": the second range is a link of its own with no word before it.
            if (BARE_RANGE.matcher(lines.get(li)).matches() && afterPages(lines, li)) {
                cleaned = "and pages " + lines.get(li).replaceAll("[^\\d\u2013-]", "");
            }
            if (cleaned.isEmpty() || cleaned.chars().noneMatch(Character::isLetterOrDigit)) continue;
            // A line of nothing but references ("Bilang 13:26–30; Jer. 1:4–10; …"), even with a book name the
            // reader doesn't know: with "skip Bible references" it is not read (only the verses' words are worth it).
            if (skipRefs && !readVerses && !inSentence) {
                String flat = SPACES.matcher(Bible.normalize(lines.get(li))).replaceAll(" ").trim();
                // (References in brackets are skipped anyway and don't make a sentence a list.)
                String open = Bible.stripRefs(ODD_HYPHEN.matcher(flat).replaceAll("-"));
                int[] share = Bible.refShare(open.isEmpty() ? flat : open);
                // Only references, or a list of three or more with hardly a word between them.
                if (REFS_LINE.matcher(ODD_HYPHEN.matcher(flat).replaceAll("-")).matches() || share[0] >= 3 && share[1] <= 12) continue;
                if (share[0] >= 2 && share[1] <= 40 && lastRefLine.isEmpty()) lastRefLine = flat; // for the log: why it was read
            }
            char endCh = cleaned.charAt(cleaned.length() - 1);
            boolean heading = cleaned.length() <= 70 && ".!?…,;:\"”’)".indexOf(endCh) < 0 && cleaned.chars().anyMatch(Character::isLetter);
            if (readVerses && !verse) {
                cleaned = Bible.withVerses(cleaned, Bible.hasTagalogBook(cleaned) || detect(cleaned).equals(FIL));
            } else if (skipRefs) {
                // A reference shown as its own link leaves "Apostol (" and "). Matapos" behind; tidy those ends.
                // A reference the sentence goes through ("foretold in | Daniel 8:14 | represented") is read.
                String stripped = Bible.stripRefs(cleaned);
                if (stripped.isEmpty() && inSentence) stripped = cleaned;
                cleaned = stripped.replaceAll("\\s*[(\\[]\\s*$", "")
                        .replaceAll("\\s*(?:[(\\[]\\s*(?:[Cc]f\\.?|[Ss]ee(?: also)?|[Cc]ompare)|[Cc]f\\.)\\s*$", "") // "(Cf." left by a skipped reference
                        .replaceAll("^[\\s)\\].]+", ""); // a comma stays: ", Ellen White deals…" goes on from the line before
                if (cleaned.chars().noneMatch(Character::isLetter)) continue;
            }
            // "(Cf." and the like, left alone on a line when its reference is a separate link.
            if (cleaned.replaceAll("(?i)\\b(?:cf|see also|see|compare)\\b", "").chars().noneMatch(Character::isLetter)) continue;
            // Before sentence splitting, so "Gen. 1:1-5" isn't cut at the abbreviation's full stop.
            boolean fil = Bible.hasTagalogBook(cleaned) || detect(cleaned).equals(FIL);
            cleaned = Bible.expand(cleaned, fil);
            if (verse) {
                cleaned = Bible.stripVerseNumbers(cleaned);
                if (cleaned.isEmpty() || cleaned.chars().noneMatch(Character::isLetterOrDigit)) continue;
            }
            if (skipTimes) {
                String before = cleaned;
                cleaned = Timestamps.strip(cleaned);
                if (cleaned.isEmpty() || cleaned.chars().noneMatch(Character::isLetterOrDigit)) {
                    if (!before.isEmpty()) dropped++;
                    continue;
                }
            }
            // A Tagalog or Taglish paragraph keeps one voice throughout, the way one person would read it,
            // instead of flipping to the English voice for each English sentence.
            boolean hasVerse = cleaned.indexOf('\u000E') >= 0;
            boolean para = fil || langOf(hasVerse ? VERSE_SPAN.matcher(cleaned).replaceAll(" ") : cleaned, screenFil).equals(FIL);
            if (para) cleaned = outsideVerses(cleaned, TextPrep::sayTagalog);
            // A kept verse is never cut into sentences or chunks: its spaces are hidden meanwhile.
            if (hasVerse) cleaned = hideVerseSpaces(cleaned);
            for (String sentence : SENTENCE_END.split(cleaned)) {
                sentence = sentence.trim();
                if (sentence.isEmpty() || sentence.chars().noneMatch(Character::isLetterOrDigit)) continue;
                for (String part : sentence.indexOf('\u000E') >= 0 ? java.util.Collections.singletonList(sentence) : chunk(sentence, 220)) {
                    out.add(new Seg(part.replace('\u0005', ' '), para ? FIL : langOf(part, screenFil), li, li, heading));
                }
            }
        }
        lastDropped = dropped;
        return out;
    }

    /** Breaks very long sentences at commas or spaces so a voice never has to hold one for ages. */
    private static List<String> chunk(String s, int max) {
        List<String> out = new ArrayList<>();
        while (s.length() > max) {
            int cut = s.lastIndexOf(',', max);
            if (cut < max / 3) cut = s.lastIndexOf(' ', max);
            if (cut < max / 3) cut = max;
            out.add(s.substring(0, cut + 1).trim());
            s = s.substring(cut + 1).trim();
        }
        if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** Joins neighbouring pieces of one language so the voice speaks them as one flowing run. */
    static List<Seg> merge(List<Seg> segs, int maxChars) {
        return merge(segs, maxChars, maxChars, false, false);
    }

    /**
     * firstMax: a short first run so speech starts quickly. anyLang: for voices that speak both languages
     * themselves, so one voice reads everything. perLine: start a new run at each screen line, so the reader
     * can pause between paragraphs.
     */
    static List<Seg> merge(List<Seg> segs, int firstMax, int maxChars, boolean anyLang, boolean perLine) {
        List<Seg> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        String lang = null;
        int first = 0, last = 0;
        boolean head = true, done = false;
        String prev = "", ref = null;
        for (Seg s : segs) {
            int max = out.isEmpty() ? firstMax : maxChars;
            boolean newLang = !anyLang && lang != null && !lang.equals(s.lang);
            // A link or an italic word inside a sentence comes as its own line ("shall" / "prophesy" / "and your…"):
            // that is still one sentence, read and translated in one go.
            boolean goesOn = perLine && lang != null && s.line != last && continues(prev, s.text);
            // A long sentence cut in pieces stays in one run, so it is never read or translated in halves
            // ("…and the early" / "1850's"), unless it is far too long.
            boolean midSentence = lang != null && s.line == last && !prev.isEmpty()
                    && ".!?…".indexOf(prev.charAt(prev.length() - 1)) < 0 && sb.length() + s.text.length() <= max * 2;
            if ((goesOn || midSentence) && sb.length() > 0 && sb.charAt(sb.length() - 1) == '.' && !prev.endsWith(".")) sb.setLength(sb.length() - 1);
            boolean newLine = perLine && lang != null && s.line != last && !goesOn;
            boolean newKind = lang != null && done != s.translated; // translated text never joins untranslated
            boolean tooLong = sb.length() + s.text.length() > max && !midSentence;
            if (lang != null && (newLang || newLine || newKind || tooLong)) {
                out.add(new Seg(sb.toString(), lang, first, last, head, done));
                out.get(out.size() - 1).ref = ref;
                ref = null;
                sb.setLength(0);
                lang = null;
                head = true;
            }
            done = s.translated;
            head &= s.heading;
            if (sb.length() == 0) first = s.line;
            last = s.lastLine;
            if (lang == null || !anyLang || s.lang.equals(FIL)) lang = s.lang;
            if (sb.length() > 0 && !(goesOn && ",;:".indexOf(s.text.charAt(0)) >= 0)) sb.append(' ');
            sb.append(endWithStop(s.text));
            prev = s.text;
            if (s.ref != null) ref = s.ref;
        }
        if (lang != null && sb.length() > 0) {
            out.add(new Seg(sb.toString(), lang, first, last, head, done));
            out.get(out.size() - 1).ref = ref;
        }
        return out;
    }

    /** The sentence in a goes on in b: a has no sentence end, and b starts small or with a comma, colon or semicolon. */
    static boolean continues(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        int e = a.length() - 1;
        while (e > 0 && "\"”’')]".indexOf(a.charAt(e)) >= 0) e--;
        if (".!?…".indexOf(a.charAt(e)) >= 0) return false;
        int i = 0;
        while (i < b.length() - 1 && "\"“‘'([ ".indexOf(b.charAt(i)) >= 0) i++;
        char c = b.charAt(i);
        if (Character.isLowerCase(c) || ",;:".indexOf(c) >= 0) return true;
        // "…the three angels of" / "Revelation 14": a sentence never ends on "of" or "the", so a link that
        // starts with a capital still belongs to it.
        java.util.regex.Matcher w = LAST_WORD.matcher(a.substring(0, e + 1));
        return w.find() && HANGING.contains(w.group(1).toLowerCase(Locale.ROOT));
    }

    private static final java.util.regex.Pattern LAST_WORD = java.util.regex.Pattern.compile("([A-Za-z]+)$");
    private static final java.util.Set<String> HANGING = new java.util.HashSet<>(java.util.Arrays.asList(
            "of", "the", "a", "an", "and", "or", "to", "in", "on", "at", "by", "for", "with", "from", "into", "upon",
            "unto", "than", "that", "as", "his", "her", "their", "our", "your", "my", "its", "whose", "which",
            "ang", "ng", "sa", "mga", "ni", "kay", "nina", "kina", "si", "sina"));

    /** A closing full stop gives the voice its sentence-final fall and a natural pause. */
    private static String endWithStop(String t) {
        char c = t.charAt(t.length() - 1);
        return (".!?…,;:".indexOf(c) >= 0) ? t : t + ".";
    }
}
