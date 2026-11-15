package com.zylena.screenreader;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kind of text a page is (a devotional, a love story, something frightening, something funny…) and how
 * a line is said (whispered, shouted, in tears…), so the voice can read it that way: a little slower and lower
 * for suspense, livelier for comedy, soft for a whisper. All of it is gentle, and the user's Tone strength scales it.
 */
final class Mood {
    private Mood() {
    }

    /** The kinds of text, with the words that give each away (English and Tagalog). */
    static final String[] KINDS = {"calm", "suspense", "romance", "lively", "sad", "action"};
    static final String[] KIND_NAMES = {"Calm (devotional, study)", "Suspense, horror", "Romance", "Lively, funny", "Sad, drama", "Action"};
    private static final String[] WORDS = {
            "god lord jesus christ bible scripture prayer pray faith prophet church holy spirit gospel sabbath sin salvation heaven verse "
                    + "diyos dios panginoon hesus jesus kristo cristo biblia bibliya kasulatan panalangin pananampalataya propeta iglesia "
                    + "banal espiritu ebanghelyo sabbath kasalanan kaligtasan langit talata aralin",
            "blood scream screamed dark darkness shadow shadows knife dead death corpse ghost fear terror killer gun murder creak whispering "
                    + "nightmare monster demon grave trembling footsteps dugo sigaw dilim anino kutsilyo patay bangkay multo takot lagim "
                    + "baril pinatay bangungot halimaw demonyo libingan nanginginig yabag kilabot kaluskos",
            "love loved kiss kissed heart lips boyfriend girlfriend crush date hug hugged blush blushed gorgeous handsome sexy wedding "
                    + "mahal pagmamahal pag-ibig halik hinalikan puso labi nobyo nobya kilig yakap niyakap namula guwapo gwapo maganda kasal "
                    + "sinta irog jowa ligaw",
            "lol haha hahaha joke joked funny laugh laughed laughing giggled grin silly prank hilarious lmao biro nagbiro tawa tumawa "
                    + "natawa nakakatawa hagikgik loko kalokohan tawanan charot char",
            "cry cried crying tears sob sobbed funeral grief alone lonely pain hurt broken goodbye miss missed sorrow mourn iyak umiyak "
                    + "luha hikbi libing dalamhati nag-iisa lungkot malungkot sakit masakit paalam pighati hinagpis pangungulila",
            "run ran running fight fought punch punched kick chase chased explode exploded explosion sword battle attack attacked shot "
                    + "dodge sprint takbo tumakbo laban lumaban suntok sinuntok sipa habol hinabol sabog sumabog espada labanan atake "
                    + "inatake binaril ilag"};
    private static final Map<String, Integer> KIND_OF = new HashMap<>();

    static {
        for (int k = 0; k < WORDS.length; k++) for (String w : WORDS[k].split(" ")) if (!KIND_OF.containsKey(w)) KIND_OF.put(w, k);
    }

    /** For the log: what the last page was taken for, and on how many words. */
    static volatile String lastWhy = "";

    /** The kind of text on this page: one of KINDS, or "" for plain text (news, chat, anything not clearly one). */
    static String of(List<String> lines, boolean bible) {
        int[] hits = new int[KINDS.length];
        int words = 0;
        for (String l : lines) {
            for (String w : l.toLowerCase(Locale.ROOT).split("[^\\p{L}-]+")) {
                if (w.length() < 2) continue;
                words++;
                Integer k = KIND_OF.get(w);
                if (k != null) hits[k]++;
            }
        }
        if (bible) hits[0] += 5;
        int best = 0;
        for (int k = 1; k < hits.length; k++) if (hits[k] > hits[best]) best = k;
        // Clearly that kind: several of its words, and a fair share of the page.
        if (hits[best] < 4 || hits[best] * 150 < words) {
            lastWhy = "";
            return "";
        }
        lastWhy = KINDS[best] + " (" + hits[best] + " of " + words + " words)";
        return KINDS[best];
    }

    /** {pitch, speed, loudness} for a kind of text; 1 is unchanged. */
    static float[] forKind(String kind) {
        switch (kind == null ? "" : kind) {
            case "calm":
                return new float[]{0.98f, 0.96f, 1f};
            case "suspense":
                return new float[]{0.93f, 0.94f, 1f};
            case "romance":
                return new float[]{1.0f, 0.96f, 0.92f};
            case "lively":
                return new float[]{1.05f, 1.05f, 1f};
            case "sad":
                return new float[]{0.95f, 0.92f, 0.95f};
            case "action":
                return new float[]{1.0f, 1.07f, 1f};
            default:
                return new float[]{1f, 1f, 1f};
        }
    }

    // ------------------------------------------------------------ how a line is said

    private static final String[][] FEEL = {
            {"w", "whisper|whispered|whispers|murmur|murmured|murmurs|mutter|muttered|mumbled|breathed|softly|quietly|under (?:his|her|my) breath"
                    + "|bulong|ibinulong|pabulong|bumulong|anas|mahinang|mahina"},
            {"s", "shout|shouted|shouts|yell|yelled|yells|yelling|shouting|screaming|scream|screamed|screams|roared|bellowed|shrieked|screeched|hollered|barked|loudly|exclaimed|exclaims|cried out|burst out"
                    + "|sigaw|sumigaw|isinigaw|pasigaw|hiyaw|humiyaw|bulyaw|binulyawan|tili|tumili|malakas"},
            {"h", "sigh|sighed|sighs|sighing|exhaled|huminga nang malalim|buntong-hininga|napabuntong-hininga"},
            {"d", "sob|sobbed|sobs|cried|cries|wept|wailed|sniffled|sadly|tearfully"
                    + "|hikbi|humihikbi|umiiyak|umiyak|iyak|daing|malungkot|garalgal"},
            {"l", "laugh|laughed|laughs|laughing|laughter|giggled|giggles|giggling|chuckled|chuckles|chuckling|cackled|snickered|burst out laughing|with a laugh|with a giggle|with a chuckle"
                    + "|tawa|tumawa|natatawa|natawa|humagikgik|hagikgik|humalakhak|natatawang"},
            {"j", "joked|jokes|grinned|beamed|cheered|happily|excitedly|teased|squealed"
                    + "|biro|nagbiro|pabiro|ngiti|nakangiti|nakangiting|masaya|masayang|tukso|kinikilig|tuwang-tuwa"},
            {"a", "argued|argues|protested|retorted|scowled|seethed|gritted|snapped|snaps|growled|growls|hissed|hisses|snarled|spat|angrily|coldly|sharply|demanded|glared|fumed"
                    + "|galit|pagalit|nagalit|singhal|suminghal|sita|mariing|mariin|inis|naiinis|asik|umasik"},
            {"f", "stammered|stuttered|gasped|trembled|trembling|nervously|panicked|shakily|whimpered"
                    + "|nauutal|nanginginig|takot|natatakot|kinakabahan|napasinghap|taranta|natataranta"}};
    private static final Pattern[] FEEL_P = new Pattern[FEEL.length];

    static {
        for (int i = 0; i < FEEL.length; i++) FEEL_P[i] = Pattern.compile("(?i)(?<![\\p{L}-])(?:" + FEEL[i][1] + ")(?![\\p{L}-])");
    }

    /**
     * How the quoted words are said, from the words around them ("she whispered", "pasigaw na sabi niya") and
     * from the words themselves (ALL IN CAPITALS is shouted): "w" whispered, "s" shouted, "d" sad, "j" glad, "l" laughing, "h" sighing,
     * "a" angry, "f" afraid, or "" plainly.
     */
    static String feeling(String narration, String quote) {
        int at = Integer.MAX_VALUE;
        String feel = "";
        for (int i = 0; i < FEEL_P.length; i++) {
            Matcher m = FEEL_P[i].matcher(narration);
            if (m.find() && m.start() < at) {
                at = m.start();
                feel = FEEL[i][0];
            }
        }
        if (!feel.isEmpty()) return feel;
        int caps = 0, letters = 0;
        for (int i = 0; i < quote.length(); i++) {
            char c = quote.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) caps++;
            }
        }
        if (letters >= 6 && caps * 10 >= letters * 8) return "s";
        return ofWords(quote);
    }

    // ---- How a line is said, from what it says (when nothing around it tells)

    private static final String[][] SAYS = {
            {"a", "hate you|shut up|get out|how dare you|damn it|idiot|stupid|liar|leave me alone|i said no|bastard|asshole|bullshit|what the hell|go away"
                    + "|ayoko|galit na galit|tumigil ka|umalis ka|bwisit|gago|tanga|ulol|layas"},
            {"f", "help me|please no|don'?t hurt|what was that|i'?m scared|i'?m afraid|oh no|someone help|let me go|stay away|don'?t touch"
                    + "|tulong|takot ako|huwag po|bitawan mo ako|lumayo ka"},
            {"d", "i miss you|don'?t leave|please don'?t go|it hurts|i can'?t do this|i'?m so sorry|forgive me|it'?s over|goodbye|she'?s gone|he'?s gone"
                    + "|miss na kita|nasasaktan|patawarin mo ako|patawad|paalam|iniwan mo ako|wala na siya"},
            {"t", "i love you|i missed you|sweetheart|darling|my love|honey|you'?re safe|it'?s okay"
                    + "|mahal kita|mahal na mahal|iniibig kita|aking mahal"},
            {"p", "please,|please\\.\\.\\.|begging you|i beg you|pakiusap|makiusap|maawa ka"},
            {"j", "thank you so much|we did it|finally|yay|congratulations|i'?m so happy|can'?t wait|awesome|amazing"
                    + "|salamat po|yehey|congrats|sa wakas|masaya ako|ang saya"}};
    private static final Pattern[] SAYS_P = new Pattern[SAYS.length];

    static {
        for (int i = 0; i < SAYS.length; i++) SAYS_P[i] = Pattern.compile("(?i)(?<![\\p{L}'-])(?:" + SAYS[i][1] + ")(?![\\p{L}-])");
    }

    /** The feeling of the words themselves ("I hate you!" is angry, "Please don't go" sad), or "". */
    static String ofWords(String quote) {
        int at = Integer.MAX_VALUE;
        String feel = "";
        for (int i = 0; i < SAYS.length; i++) {
            Matcher m = SAYS_P[i].matcher(quote);
            if (m.find() && m.start() < at) {
                at = m.start();
                feel = SAYS[i][0];
            }
        }
        return feel;
    }

    // ---- The storyteller's feeling in a sentence ("my heart pounded", "tears streamed down")

    private static final String[][] NARR = {
            {"f", "froze|trembl|terrified|panic|heart (?:pounded|raced|skipped|hammered)|goosebumps|shiver|dread|horrified|nanginig|kinabahan|natakot|nanlamig"},
            {"d", "tears|crying|cried|sobbed|heartbroken|sorrow|grief|ached|lonely|nalungkot|umiyak|luha|nasaktan"},
            {"a", "furious|angry|glared|rage|slammed|fuming|seething|clenched (?:his|her|my) fists|galit|nagalit|nanggigil"},
            {"j", "smiled|beamed|delighted|joy|happy|excited|grinned|giggled|ngumiti|nangiti|masaya|natuwa|kilig"},
            {"h", "sighed|exhaled|exhausted|nagbuntong"}};
    private static final Pattern[] NARR_P = new Pattern[NARR.length];

    static {
        for (int i = 0; i < NARR.length; i++) NARR_P[i] = Pattern.compile("(?i)(?<![\\p{L}-])(?:" + NARR[i][1] + ")");
    }

    /** The feeling the storyteller's own words carry (read at half strength), or "". */
    static String ofNarration(String sentence) {
        int at = Integer.MAX_VALUE;
        String feel = "";
        for (int i = 0; i < NARR.length; i++) {
            Matcher m = NARR_P[i].matcher(sentence);
            if (m.find() && m.start() < at) {
                at = m.start();
                feel = NARR[i][0];
            }
        }
        return feel;
    }

    /** {pitch, speed, loudness} for a feeling; 1 is unchanged. */
    static float[] forFeeling(String feel) {
        switch (feel == null ? "" : feel) {
            case "w":
                return new float[]{0.92f, 0.90f, 0.5f};
            case "s":
                return new float[]{1.18f, 1.08f, 1.3f};
            case "d":
                return new float[]{0.90f, 0.88f, 0.85f};
            case "j":
                return new float[]{1.12f, 1.06f, 1.05f};
            case "l": // laughing while speaking: lighter, quicker, brighter
                return new float[]{1.15f, 1.10f, 1.08f};
            case "t": // tender: soft and slow
                return new float[]{1.0f, 0.92f, 0.88f};
            case "p": // pleading
                return new float[]{1.06f, 0.93f, 0.9f};
            case "h": // a sigh: breathy, slow, low
                return new float[]{0.92f, 0.86f, 0.8f};
            case "a":
                return new float[]{0.90f, 1.08f, 1.25f};
            case "f":
                return new float[]{1.10f, 1.12f, 0.95f};
            default:
                return new float[]{1f, 1f, 1f};
        }
    }

    static List<String> kinds() {
        return Arrays.asList(KINDS);
    }
}
