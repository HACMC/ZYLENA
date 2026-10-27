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
                "vs.", "versus", "a.d.", "A D", "b.c.", "B C", "vs", "versus", "approx.", "about",
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
            {"Jehova|Jehová", "Hehoba"}, {"Santiago", "Santiyago"}};
    private static final Pattern[] TAGALOG_SAY_P = new Pattern[TAGALOG_SAY.length];

    static {
        for (int i = 0; i < TAGALOG_SAY.length; i++) {
            TAGALOG_SAY_P[i] = Pattern.compile("(?<![\\p{L}-])(?:" + TAGALOG_SAY[i][0] + ")(?![\\p{L}-])", Pattern.CASE_INSENSITIVE);
        }
    }

    /** "-syon" is said "-shon" (liksyon = lik-SHON); the Tagalog voice says "lik-si-yon". */
    private static final Pattern SYON = Pattern.compile("(?<=\\p{L})([sS])([yY])([oO][nN])(?=g?\\b|es\\b)");

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

    static String clean(String s) {
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

    /** "fil" when the sentence is clearly Tagalog or Taglish, otherwise "en". */
    static String detect(String sentence) {
        String[] words = sentence.toLowerCase(Locale.ROOT).split("[^\\p{L}'\\-]+");
        int total = 0, tagalog = 0, english = 0;
        for (String raw : words) {
            String w = raw.replaceAll("^[-']+|[-']+$", "");
            if (w.isEmpty()) continue;
            total++;
            int v = vote(w);
            if (v == 0 && w.contains("-")) {
                // "pag-aaral", "Mag-ingat": judge by the parts
                for (String part : w.split("-")) if (vote(part) > 0) v = 1;
            }
            if (v > 0) tagalog++;
            else if (v < 0) english++;
        }
        if (total == 0) return EN;
        if (tagalog * 10 >= total * 3) return FIL;
        // Two Tagalog words make Taglish ("Ready na akong basahin"), but not in a long English paragraph that
        // happens to hold words both languages have ("they may have", "Ina", "Sana"): English words outvote them.
        return tagalog >= 2 && tagalog * 2 > english ? FIL : EN;
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

    /** readVerses: read the words of each cited verse (takes the place of skipRefs). */
    static List<Seg> prepare(List<String> lines, boolean skipTimes, boolean skipRefs, boolean readVerses) {
        List<Seg> out = new ArrayList<>();
        int dropped = 0;
        boolean verse = Bible.verseMode(lines);
        boolean screenFil = mostlyTagalog(lines);
        lastVerseMode = verse;
        for (int li = 0; li < lines.size(); li++) {
            if (UI_NOISE.contains(lines.get(li).trim().toLowerCase(Locale.ROOT))) continue;
            String cleaned = applyUserLists(clean(lines.get(li)));
            if (cleaned.isEmpty() || cleaned.chars().noneMatch(Character::isLetterOrDigit)) continue;
            char endCh = cleaned.charAt(cleaned.length() - 1);
            boolean heading = cleaned.length() <= 70 && ".!?…,;:\"”’)".indexOf(endCh) < 0 && cleaned.chars().anyMatch(Character::isLetter);
            if (readVerses && !verse) {
                cleaned = Bible.withVerses(cleaned, Bible.hasTagalogBook(cleaned) || detect(cleaned).equals(FIL));
            } else if (skipRefs) {
                // A reference shown as its own link leaves "Apostol (" and "). Matapos" behind; tidy those ends.
                cleaned = Bible.stripRefs(cleaned).replaceAll("\\s*[(\\[]\\s*$", "").replaceAll("^[\\s)\\];,.]+", "");
                if (cleaned.chars().noneMatch(Character::isLetter)) continue;
            }
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
            boolean para = fil || langOf(cleaned, screenFil).equals(FIL);
            if (para) cleaned = sayTagalog(cleaned);
            for (String sentence : SENTENCE_END.split(cleaned)) {
                sentence = sentence.trim();
                if (sentence.isEmpty() || sentence.chars().noneMatch(Character::isLetterOrDigit)) continue;
                for (String part : chunk(sentence, 220)) out.add(new Seg(part, para ? FIL : langOf(part, screenFil), li, li, heading));
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
        for (Seg s : segs) {
            int max = out.isEmpty() ? firstMax : maxChars;
            boolean newLang = !anyLang && lang != null && !lang.equals(s.lang);
            boolean newLine = perLine && lang != null && s.line != last;
            boolean newKind = lang != null && done != s.translated; // translated text never joins untranslated
            if (lang != null && (newLang || newLine || newKind || sb.length() + s.text.length() > max)) {
                out.add(new Seg(sb.toString(), lang, first, last, head, done));
                sb.setLength(0);
                lang = null;
                head = true;
            }
            done = s.translated;
            head &= s.heading;
            if (sb.length() == 0) first = s.line;
            last = s.lastLine;
            if (lang == null || !anyLang || s.lang.equals(FIL)) lang = s.lang;
            if (sb.length() > 0) sb.append(' ');
            sb.append(endWithStop(s.text));
        }
        if (lang != null && sb.length() > 0) out.add(new Seg(sb.toString(), lang, first, last, head, done));
        return out;
    }

    /** A closing full stop gives the voice its sentence-final fall and a natural pause. */
    private static String endWithStop(String t) {
        char c = t.charAt(t.length() - 1);
        return (".!?…,;:".indexOf(c) >= 0) ? t : t + ".";
    }
}
