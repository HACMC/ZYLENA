package com.zylena.screenreader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A story's characters. Works out who says each quoted line ("…," Julia sighed / she asked / I said, or by
 * whose turn it is), whether that character is a man or a woman, and gives each one a voice of their own:
 * the best voices first. The narrator, and what the narrator says in a first-person story, keep the usual voice.
 */
final class Cast {
    private Cast() {
    }

    /**
     * The Father ("cd0": majestic, deep and slow), the Son ("cd1": warm, gentle, clear) and the Holy Spirit ("cd2": soft,
     * "a still small voice"): when they speak, each has a voice of their own.
     */
    static final String DIVINE_CODE = "cd0", JESUS_CODE = "cd1", SPIRIT_CODE = "cd2";

    static boolean isDivine(String code) {
        return code != null && code.startsWith("cd");
    }

    /** The voice code of a divine name: Jesus and Christ cd1, the Spirit cd2, God (the Father) cd0. */
    private static String divineCodeOf(String name) {
        switch (name) {
            case "jesus": case "hesus": case "jesu": case "cristo": case "christ": case "messiah": case "mesias":
                return JESUS_CODE;
            case "espiritu": case "spirit": case "holy":
                return SPIRIT_CODE;
            default:
                return DIVINE_CODE;
        }
    }
    private static final Set<String> DIVINE_NAMES = new HashSet<>(Arrays.asList(("jesus hesus jesu cristo christ messiah mesias god dios diyos panginoon lord "
            + "yahweh jehovah jehova ama espiritu spirit holy father").split(" ")));

    /** A voice code: "cm0" the first man's voice, "cf1" the second woman's, "cu0" a character the reader can't tell. */
    static boolean isCode(String who) {
        who = voice(who);
        return who != null && who.length() >= 3 && who.charAt(0) == 'c' && "mfud".indexOf(who.charAt(1)) >= 0
                && Character.isDigit(who.charAt(2));
    }

    /** The voice part of "cf1^husky~w" (a character's voice, husky, whispered): "cf1". */
    static String voice(String who) {
        if (who == null) return null;
        int a = who.indexOf('~'), b = who.indexOf('^');
        int at = a < 0 ? b : b < 0 ? a : Math.min(a, b);
        return at < 0 ? who : who.substring(0, at);
    }

    /** The feeling part of "cf1^husky~w": "w"; "" when it is said plainly. */
    static String feel(String who) {
        int at = who == null ? -1 : who.indexOf('~');
        return at < 0 ? "" : who.substring(at + 1);
    }

    /** The kind of voice of "cf1^husky~w": "husky"; "" for the character's plain voice. */
    static String styleOf(String who) {
        int b = who == null ? -1 : who.indexOf('^');
        if (b < 0) return "";
        int e = who.indexOf('~', b);
        return who.substring(b + 1, e < 0 ? who.length() : e);
    }

    static char genderOf(String who) {
        return who.charAt(1);
    }

    static int slotOf(String who) {
        try {
            return Integer.parseInt(voice(who).substring(2));
        } catch (Exception e) {
            return 0;
        }
    }

    /** One character: the name as first seen, "m", "f" or "" and the voice code once they have spoken. */
    private static final class Person {
        final String name;
        String gender = "";
        String code;
        boolean sure; // the gender is from the user's list or a well-known name, not a guess
        String style = ""; // what kind of person the story says this is (a VoiceFx style), "" when it says nothing
        final Map<String, Integer> voiceHits = new HashMap<>(); // how often the story describes their voice a certain way
        boolean styleFixed; // the user chose the voice style: the story's descriptions no longer change it
        String applied = ""; // the user's choice for this character that was last put to use
        boolean frozen; // the voice was given on an earlier page: it stays, whatever is learned about the character later

        Person(String name) {
            this.name = name;
        }
    }

    /** The characters met so far, per app, so a character keeps the same voice from page to page. */
    private static final Map<String, LinkedHashMap<String, Person>> STORIES = new HashMap<>();
    /** For the log and Settings: the characters of the page just prepared. */
    static volatile String lastSummary = "";

    /** What the user chose for characters (Settings, Voice, Characters): lines "name|m, f, u or empty|style, none or empty". */
    private static volatile String overrides = "";

    static void setOverrides(String lines) {
        overrides = lines == null ? "" : lines;
    }

    private static void applyOverrides(Map<String, Person> people) {
        for (String line : overrides.split("\\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 3 || f[0].trim().isEmpty()) continue;
            String name = f[0].trim().toLowerCase(Locale.ROOT);
            Person p = people.get(name);
            if (p == null) {
                p = new Person(name);
                people.put(name, p);
            }
            if (p.applied.equals(line)) continue;
            p.applied = line;
            String g = f[1].trim();
            if (g.equals("m") || g.equals("f") || g.equals("u")) {
                p.gender = g.equals("u") ? "" : g;
                p.sure = true;
                p.code = null; // the user's word is new: the voice is chosen again
                p.frozen = false;
            }
            String st = f[2].trim();
            p.styleFixed = !st.isEmpty();
            if (st.equals("none")) p.style = "";
            else if (!st.isEmpty()) p.style = st;
        }
    }

    /** What is known of a character by name in this app's story: {"m"/"f"/"u", age: child, teen, adult or elder}; null if not met. */
    static synchronized String[] infoOf(String scope, String name) {
        LinkedHashMap<String, Person> people = STORIES.get(scope);
        Person p = people == null ? null : people.get(name.toLowerCase(Locale.ROOT));
        if (p == null) return null;
        return new String[]{p.gender.isEmpty() ? "u" : p.gender, Persona.ageOf(p.style)};
    }

    /** The characters met in any story so far, for the Characters screen: {name, "m"/"f"/"u", style}. */
    static synchronized List<String[]> knownList() {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LinkedHashMap<String, Person> m : STORIES.values()) {
            for (Person p : m.values()) {
                if (p.code == null || !seen.add(p.name)) continue;
                out.add(new String[]{cap(p.name), p.gender.isEmpty() ? "u" : p.gender, p.style});
            }
        }
        return out;
    }

    static synchronized void reset() {
        STORIES.clear();
        lastSummary = "";
    }

    /** The characters known for this app, as "Julia (woman), Luke (man)". */
    static synchronized String known() {
        StringBuilder sb = new StringBuilder();
        for (LinkedHashMap<String, Person> m : STORIES.values()) {
            for (Person p : m.values()) {
                if (p.code == null) continue;
                sb.append(sb.length() > 0 ? ", " : "").append(cap(p.name)).append(" (")
                        .append(p.gender.equals("m") ? "man" : p.gender.equals("f") ? "woman" : "not sure").append(")");
            }
        }
        return sb.toString();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------ quotes

    /** The quoted parts of the text as {start, end, closed}: "…" and “…”. The text may begin inside a quote. */
    static List<int[]> quoteSpans(String t, boolean inside) {
        List<int[]> out = new ArrayList<>();
        int open = inside ? 0 : -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '“' || c == '«') {
                if (open < 0) open = i;
            } else if (c == '”' || c == '»') {
                if (open >= 0) {
                    out.add(new int[]{open, i + 1, 1});
                    open = -1;
                }
            } else if (c == '"') {
                if (open < 0) {
                    open = i;
                } else {
                    out.add(new int[]{open, i + 1, 1});
                    open = -1;
                }
            }
        }
        if (open >= 0 && open < t.length() - 1) out.add(new int[]{open, t.length(), 0});
        return out;
    }

    /** Somebody speaking, not a word in quotation marks ("camp cheer"): it ends like a sentence, or is a long line of its own. */
    private static boolean spoken(String line, int[] span) {
        String c = line.substring(span[0], span[1]).replaceAll("^[\"“«\\s]+|[\"”»\\s]+$", "");
        if (c.isEmpty() || !c.chars().anyMatch(Character::isLetter)) return false;
        char end = c.charAt(c.length() - 1);
        if (",.!?…-–—".indexOf(end) >= 0) return true;
        int words = c.trim().split("\\s+").length;
        return words >= 4 && line.substring(0, span[0]).trim().isEmpty() || span[2] == 0 && words >= 4;
    }

    // ------------------------------------------------------------ who speaks

    private static final String VERBS = "said|says|say|asked|asks|ask|replied|replies|answered|answers|whispered|whispers|shouted|shouts"
            + "|yelled|yells|screamed|screams|cried|cries|muttered|mutters|mumbled|mumbles|murmured|murmurs|sighed|sighs|joked|jokes"
            + "|laughed|laughs|giggled|giggles|chuckled|chuckles|groaned|groans|growled|growls|snapped|snaps|hissed|hisses|called|calls"
            + "|added|adds|continued|continues|began|begins|explained|explains|admitted|admits|agreed|agrees|argued|argues|begged|begs"
            + "|demanded|demands|exclaimed|exclaims|gasped|gasps|greeted|greets|insisted|insists|interrupted|interrupts|offered|offers"
            + "|pleaded|pleads|promised|promises|questioned|questions|repeated|repeats|responded|responds|snorted|snorts|sobbed|sobs"
            + "|stammered|stammers|stuttered|stutters|suggested|suggests|teased|teases|told|tells|warned|warns|wondered|wonders"
            + "|announced|announces|breathed|breathes|complained|complains|corrected|corrects|declared|declares|grumbled|grumbles"
            + "|huffed|huffs|inquired|inquires|mocked|mocks|noted|notes|ordered|orders|panted|pants|protested|protests|remarked|remarks"
            + "|reminded|reminds|retorted|retorts|scoffed|scoffs|spoke|speaks|stated|states|squealed|squeals|urged|urges|whined|whines"
            + "|yelped|yelps|cut in|chimed in|went on|goes on|blurted|blurts|barked|barks|cooed|coos|drawled|drawls|purred|purrs"
            + "|rasped|rasps|roared|roars|sneered|sneers|spat|spits|wailed|wails|wept|weeps|smirked|smirks|grinned|grins|smiled|smiles"
            + "|nodded|nods|shrugged|shrugs|lied|lies|guessed|guesses|confessed|confesses|commented|comments|concluded|concludes"
            + "|countered|counters|echoed|echoes|finished|finishes|pressed|presses|prompted|prompts|reasoned|reasons|recalled|recalls"
            + "|returned|returns|sang|sings|shrieked|shrieks|thought|thinks|volunteered|volunteers|vowed|vows";
    private static final String NAME = "\\p{Lu}[\\p{L}'’]{1,}(?:[ -]\\p{Lu}[\\p{L}'’]{1,})?";
    /** "Julia sighed", "she asked", "I said happily". */
    private static final Pattern SUBJECT_VERB = Pattern.compile(
            "(?<![\\p{L}'’])(I|[Hh]e|[Ss]he|" + NAME + ")\\s+(?:(?:had|then|just|only|finally|quickly|quietly|softly|simply|almost|\\p{Ll}+ly)\\s+)?(?:" + VERBS + ")(?![\\p{L}])");
    /** "said Julia", "asked the boy" is not one. */
    private static final Pattern VERB_SUBJECT = Pattern.compile("(?<![\\p{L}])(?:" + VERBS + ")\\s+(" + NAME + ")(?![\\p{L}])");
    /** Tagalog: "sabi ni Julia", "tanong niya", "sagot ko", "ani Julia", "aniya". */
    private static final String TL_VERBS = "sabi|wika|tanong|sagot|bulong|sigaw|saad|tugon|turan|anas|usal|biro|bati|utos|pakiusap|reklamo"
            + "|paliwanag|dagdag|singit|bulalas|hiyaw|ungol|daing|tili|sambit|banggit|bigkas|tawag|pagtatapat|paalala|yaya|aya|alok|angal"
            + "|pagmamakaawa|pangako|hirit|kontra|sabat|puna|pansin|taka|tukso|asar|sermon|bulyaw|singhal|sita|hikbi|iyak|tawa|ngiti|buntong-hininga";
    private static final Pattern TL_SAID = Pattern.compile(
            "(?<![\\p{L}])(?i:(?:na)?(?:pa)?(?:" + TL_VERBS + ")(?:ng)?|ani)\\s+(?:(?i:(ko|ako)|(niya|nito|siya))|(?i:ni|ng|si)\\s+(" + NAME + ")|(" + NAME + "))(?![\\p{L}])");
    private static final Pattern TL_ANIYA = Pattern.compile("(?i)(?<![\\p{L}])aniya(?![\\p{L}])");
    /** The narrator's own name: "I'm Millie Ripley", "My name is Millie", "Ako si Millie". */
    private static final Pattern I_AM = Pattern.compile("(?:\\bI'm|\\bI’m|\\bI am|\\b[Mm]y name(?: is|'s|’s)|\\b[Aa]ko si|\\b[Aa]ko nga pala si)\\s+(" + NAME + ")");
    private static final Pattern CAP_WORD = Pattern.compile("(?<![\\p{L}'’])\\p{Lu}\\p{Ll}[\\p{L}'’]*");
    private static final Pattern PRONOUN = Pattern.compile("(?<![\\p{L}'’])(I|[Hh]e|[Ss]he)(?![\\p{L}])");
    private static final Set<String> NOT_NAMES = new HashSet<>(Arrays.asList(("the a an and but or so if when then there here this that these those "
            + "it its is was were are be been what who why how where which yes no not oh ok okay well now just my your his her our their "
            + "we they you me him them us i he she mr mrs ms dr sir miss god lord monday tuesday wednesday thursday friday saturday sunday "
            + "january february march april may june july august september october november december chapter part book "
            + "ang ng sa si ni kay mga at pero kung nang na ay ako ikaw siya kami tayo sila ko mo niya namin natin nila hindi oo opo "
            + "ito iyan iyon dito diyan doon kaya dahil para hey hi hello please thanks thank sorry wait look listen come go stop").split(" ")));

    /** One line's speaker as found: a name, "I", "he", "she", "?" (someone, not known who) or null (nobody named). */
    private static String attribution(String narration, Map<String, Person> people) {
        int best = Integer.MAX_VALUE;
        String who = null;
        Matcher m = SUBJECT_VERB.matcher(narration);
        while (m.find()) {
            String s = m.group(1);
            if (s.length() > 3 && NOT_NAMES.contains(first(s))) continue;
            if (m.start() < best) {
                best = m.start();
                who = s;
            }
            break;
        }
        m = VERB_SUBJECT.matcher(narration);
        while (m.find()) {
            if (NOT_NAMES.contains(first(m.group(1)))) continue;
            if (m.start() < best) {
                best = m.start();
                who = m.group(1);
            }
            break;
        }
        m = TL_SAID.matcher(narration);
        if (m.find() && m.start() < best) {
            best = m.start();
            who = m.group(1) != null ? "I" : m.group(2) != null ? "?" : m.group(3) != null ? m.group(3) : m.group(4);
            if (who != null && !who.equals("I") && !who.equals("?") && NOT_NAMES.contains(first(who))) who = "?";
        }
        m = TL_ANIYA.matcher(narration);
        if (m.find() && m.start() < best) {
            best = m.start();
            who = "?";
        }
        if (who != null) return norm(who);
        // No "said": the one the sentence around the quote is about ("Khloe pointed at me, …").
        int at = Integer.MAX_VALUE;
        m = CAP_WORD.matcher(narration);
        while (m.find()) {
            if (people.containsKey(m.group().toLowerCase(Locale.ROOT)) && m.start() < at) {
                at = m.start();
                who = m.group().toLowerCase(Locale.ROOT);
                break;
            }
        }
        m = PRONOUN.matcher(narration);
        if (m.find() && m.start() < at) who = norm(m.group(1));
        return who;
    }

    /** "Aling Rosa", "Kuya Ben", "Mrs. Reyes": the word before the name tells who it is. */
    private static final Map<String, String> HONOR = new HashMap<>();

    static {
        for (String w : "mang manong kuya tito lolo tatay sir mr mister uncle lord king prince father brother don senor ginoong ginoo".split(" ")) HONOR.put(w, "m");
        for (String w : ("aling manang ate tita lola nanay maam mrs ms miss aunt auntie lady queen princess mother sister donya dona senora "
                + "ginang binibini").split(" ")) HONOR.put(w, "f");
    }

    /** The name a character goes by: the first name, or the name after "Aling" / "Kuya". */
    private static String first(String name) {
        String[] w = name.toLowerCase(Locale.ROOT).split("[ -]");
        return w.length > 1 && HONOR.containsKey(w[0]) ? w[1] : w[0];
    }

    private static String honorWord(String name) {
        String[] w = name.toLowerCase(Locale.ROOT).split("[ -]");
        return w.length > 1 && HONOR.containsKey(w[0]) ? w[0] : null;
    }

    private static String honor(String name) {
        String[] w = name.toLowerCase(Locale.ROOT).split("[ -]");
        return w.length > 1 ? HONOR.get(w[0]) : null;
    }

    private static String norm(String who) {
        if (who.equals("I")) return "I";
        if (who.equalsIgnoreCase("he")) return "he";
        if (who.equalsIgnoreCase("she")) return "she";
        if (who.equals("?")) return "?";
        return first(who);
    }

    /** "m", "f" or "" for a name: the user's list first, then well-known names, then how the name ends. */
    private static String guess(String name, boolean[] sure) {
        for (String[] u : TextPrep.userPov) {
            if (name.equals(u[0].toLowerCase(Locale.ROOT).split("[ -]")[0])) {
                sure[0] = true;
                String g = u[1].toLowerCase(Locale.ROOT);
                return g.startsWith("m") || g.startsWith("l") || g.startsWith("boy") ? "m" : "f";
            }
        }
        sure[0] = true;
        if (TextPrep.MEN.contains(name)) return "m";
        if (TextPrep.WOMEN.contains(name)) return "f";
        sure[0] = false;
        if (name.matches(".*(?:a|ah|ie|elle|ette|ine|lyn|lynn|ella|ina|ita|een|eth|ey|ee|i)$")) return "f";
        if (name.matches(".*(?:o|us|son|ton|ito|ard|bert|iel|ian|er|ck|rd|ld)$")) return "m";
        return "";
    }

    private static Person person(Map<String, Person> people, String full) {
        String name = first(full), title = honor(full);
        Person p = people.get(name);
        if (p != null && title != null && !p.sure) {
            p.gender = title;
            p.sure = true;
            if (!p.frozen) p.code = null;
        }
        if (p == null) {
            p = new Person(name);
            boolean[] sure = {false};
            p.gender = guess(name, sure);
            p.sure = sure[0];
            if (title != null && !p.sure) {
                p.gender = title;
                p.sure = true;
            }
            people.put(name, p);
        }
        if (p.style.isEmpty() && honorWord(full) != null) p.style = Persona.ofHonor(honorWord(full));
        return p;
    }

    /**
     * For each line, the voice code of each quoted part in order ("" where the usual voice reads: the narrator,
     * a word in quotation marks). Null for a line without quotes, and null altogether when the page is no story.
     */
    static synchronized String[][] speakers(List<String> lines, String scope) {
        learned = false;
        rerun = false;
        String[][] out = speakers0(lines, scope);
        // "he replied" further down told what a name didn't: the page is gone through again with that known.
        if (learned && out != null) {
            rerun = true;
            out = speakers0(lines, scope);
            rerun = false;
        }
        // From here on each character keeps the voice they have: it was heard (or about to be) on this page.
        LinkedHashMap<String, Person> people = STORIES.get(scope);
        if (out != null && people != null) for (Person p : people.values()) if (p.code != null) p.frozen = true;
        return out;
    }

    private static boolean learned, rerun;

    private static final Pattern RELATIVE = Pattern.compile("(?i)\\b(?:her|his|their|my|your|our|its)\\s+(?:boss|maid|nanny|yaya|butler|grandma|grandmother|grandfather|grandpa|lola|lolo|servant|mother|father|baby|kid|child)\\b");

    /** A sentence that says who one named character is (a maid, a boy of seven, an old woman): they are that from now on. */
    private static void learnRoles(String narration, Map<String, Person> people) {
        for (String sentence : narration.split("(?<=[.!?\u2026])\\s+")) {
            // "her boss", "his grandma": somebody else's relation, not who this character is.
            String role = Persona.ofRole(RELATIVE.matcher(sentence).replaceAll(" "));
            if (role.isEmpty()) continue;
            Set<String> named = new HashSet<>();
            Matcher m = CAP_WORD.matcher(sentence);
            while (m.find()) {
                String w = m.group().toLowerCase(Locale.ROOT).replaceAll("['\u2019]s?$", "");
                if (people.containsKey(w)) named.add(w);
            }
            if (named.size() != 1) continue;
            Person p = people.get(named.iterator().next());
            if (!p.style.equals(role) && !p.styleFixed) {
                p.style = role;
                learned = true;
            }
        }
    }

    private static String[][] speakers0(List<String> lines, String scope) {
        int n = lines.size();
        List<List<int[]>> spans = new ArrayList<>();
        int talk = 0, text = 0;
        for (String l : lines) {
            List<int[]> sp = quoteSpans(l, false);
            spans.add(sp);
            if (l.trim().length() >= 20) text++;
            boolean any = false;
            for (int[] s : sp) any |= spoken(l, s);
            if (any) talk++;
        }
        // A story: people talk in it again and again. A lesson with a quotation or two is not one.
        if (talk < 4 || talk * 6 < text) return null;
        LinkedHashMap<String, Person> people = STORIES.get(scope);
        if (people == null) {
            people = new LinkedHashMap<>();
            STORIES.put(scope, people);
        }
        if (people.size() > 200) people.clear();
        applyOverrides(people);
        // First pass: who the characters are (named with a "said", or a well-known name), and the narrator's own name.
        Set<String> narrator = new HashSet<>();
        String[] narr = new String[n];
        for (int i = 0; i < n; i++) {
            String l = lines.get(i);
            StringBuilder sb = new StringBuilder(l);
            for (int[] s : spans.get(i)) for (int k = s[0]; k < s[1]; k++) sb.setCharAt(k, k == s[0] ? '\u0001' : ' ');
            narr[i] = sb.toString();
            Matcher m = SUBJECT_VERB.matcher(narr[i]);
            while (m.find()) {
                String s = m.group(1);
                if (s.length() > 3 && !NOT_NAMES.contains(first(s))) person(people, s);
            }
            m = VERB_SUBJECT.matcher(narr[i]);
            while (m.find()) if (!NOT_NAMES.contains(first(m.group(1)))) person(people, m.group(1));
            m = TL_SAID.matcher(narr[i]);
            while (m.find()) {
                String s = m.group(3) != null ? m.group(3) : m.group(4);
                if (s != null && !NOT_NAMES.contains(first(s))) person(people, s);
            }
            m = CAP_WORD.matcher(l);
            while (m.find()) {
                String w = m.group().toLowerCase(Locale.ROOT).replaceAll("['’]s?$", "");
                if (TextPrep.MEN.contains(w) || TextPrep.WOMEN.contains(w)) {
                    // "Sam", "Angel", "Grace" at a sentence start may be a plain word; in a story they are mostly names.
                    if (!NOT_NAMES.contains(w) && !TITLES.contains(w)) person(people, w);
                }
            }
        }
        // Second pass: each quote's speaker.
        String[][] out = new String[n][];
        List<String> recent = new ArrayList<>(); // who was last named or spoke, newest last
        List<String> turns = new ArrayList<>(); // the speakers of the last quoted lines, newest last
        int quiet = 0; // lines without talk since the last one with it
        String lastNarrationPerson = null;
        for (int i = 0; i < n; i++) {
            String l = lines.get(i);
            List<int[]> sp = spans.get(i);
            boolean any = false;
            for (int[] s : sp) any |= spoken(l, s);
            String narration = narr[i].replace('\u0001', ' ').trim();
            learnRoles(narration, people);
            if (!any) {
                // Who this piece of the story is about: the one a following line without a "said" most likely belongs to.
                lastNarrationPerson = null;
                String[] sentences = narration.split("(?<=[.!?…])\\s+");
                String lastSentence = sentences[sentences.length - 1];
                Matcher m = CAP_WORD.matcher(l);
                while (m.find()) {
                    String w = m.group().toLowerCase(Locale.ROOT).replaceAll("['’]s?$", "");
                    if (people.containsKey(w) && !narrator.contains(w)) {
                        mention(recent, w);
                        if (lastSentence.contains(m.group())) lastNarrationPerson = w;
                    }
                }
                if (narration.length() >= 20) quiet++;
                if (quiet >= 4) turns.clear();
                continue;
            }
            String who = attribution(narr[i], people);
            String speaker; // a person's name, "I", or "" (not known)
            if ("?".equals(who)) {
                // "sagot niya": a character, not the narrator; most likely the one last named who did not just speak.
                speaker = "";
                String last = turns.isEmpty() ? "" : turns.get(turns.size() - 1);
                for (int k = recent.size() - 1; k >= 0 && speaker.isEmpty(); k--) {
                    if (!recent.get(k).equals(last) && !narrator.contains(recent.get(k))) speaker = recent.get(k);
                }
                for (int k = turns.size() - 2; k >= 0 && speaker.isEmpty(); k--) {
                    if (!turns.get(k).equals(last) && !turns.get(k).isEmpty() && !turns.get(k).equals("I")) speaker = turns.get(k);
                }
            } else if (who == null) {
                if (quiet > 0 && lastNarrationPerson != null && narration.isEmpty()) {
                    speaker = lastNarrationPerson; // "…and in the doorway stood Luke." / "I'm here to check in."
                } else {
                    // Their turn: the one who spoke before the last speaker.
                    speaker = "";
                    String last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
                    for (int k = turns.size() - 2; k >= 0 && last != null; k--) {
                        if (!turns.get(k).equals(last) && !turns.get(k).isEmpty()) {
                            speaker = turns.get(k);
                            break;
                        }
                    }
                    if (speaker.isEmpty() && last != null && !last.equals("I") && quiet == 0 && turns.size() == 1 && isFirstPerson(lines)) speaker = "I";
                }
            } else if (who.equals("I")) {
                speaker = "I";
            } else if (who.equals("he") || who.equals("she")) {
                String g = who.equals("he") ? "m" : "f";
                speaker = "";
                for (int k = recent.size() - 1; k >= 0; k--) {
                    Person p = people.get(recent.get(k));
                    if (p == null || narrator.contains(p.name)) continue;
                    if (p.gender.equals(g) || !p.sure) {
                        if (!p.gender.equals(g)) {
                            p.gender = g; // "she asked" tells what the name didn't
                            if (!p.frozen) p.code = null; // (a voice already heard is kept)
                            learned = true;
                        }
                        p.sure = true;
                        speaker = p.name;
                        break;
                    }
                }
                if (speaker.isEmpty()) speaker = who.equals("he") ? "\u0002m" : "\u0002f"; // a man / a woman not named yet
            } else {
                speaker = person(people, who).name;
            }
            // The narrator's name, from their own words: "I'm Millie Ripley," I said.
            if (speaker.equals("I")) {
                for (int[] s : sp) {
                    Matcher m = I_AM.matcher(l.substring(s[0], s[1]));
                    if (m.find() && !NOT_NAMES.contains(first(m.group(1)))) narrator.add(first(m.group(1)));
                }
            }
            String code = speaker.isEmpty() || speaker.equals("I") ? "" : speaker.startsWith("\u0002") ? "c" + speaker.charAt(1) + "0"
                    : codeFor(people, people.get(speaker));
            // What kind of voice they have: "in a husky voice" for this line, or what the story says of them all along.
            String vs = Persona.ofVoice(narration);
            Person sp0 = people.get(speaker);
            if (!vs.isEmpty() && sp0 != null && !sp0.styleFixed && !rerun && sp0.voiceHits.merge(vs, 1, Integer::sum) >= 2 && !sp0.style.equals(vs)) {
                sp0.style = vs;
                learned = true;
            }
            String style = sp0 != null && sp0.styleFixed ? sp0.style : !vs.isEmpty() ? vs : sp0 != null ? sp0.style : "";
            // (A character the user chose a plain voice for is marked, so that no style is given to them.)
            String styleTag = code.isEmpty() || isDivine(code) ? ""
                    : style.isEmpty() ? (sp0 != null && sp0.styleFixed ? "^plain" : "") : "^" + style;
            out[i] = new String[sp.size()];
            for (int k = 0; k < sp.size(); k++) {
                if (!spoken(l, sp.get(k))) {
                    out[i][k] = "";
                    continue;
                }
                // How it is said: "she whispered", "pasigaw na sabi niya", or ALL IN CAPITALS.
                String feel = TextPrep.feelOn ? Mood.feeling(narration, l.substring(sp.get(k)[0], sp.get(k)[1])) : "";
                out[i][k] = code + styleTag + (feel.isEmpty() ? "" : "~" + feel);
            }
            // Names said in this line: who may answer, and who "she" may be next.
            Matcher m = CAP_WORD.matcher(l);
            while (m.find()) {
                String w = m.group().toLowerCase(Locale.ROOT).replaceAll("['’]s?$", "");
                if (people.containsKey(w) && !narrator.contains(w) && !w.equals(speaker)) mention(recent, w);
            }
            if (!speaker.isEmpty() && !speaker.equals("I") && !speaker.startsWith("\u0002")) mention(recent, speaker);
            turns.add(speaker);
            if (turns.size() > 8) turns.remove(0);
            quiet = 0;
            lastNarrationPerson = null;
        }
        lastSummary = known();
        return out;
    }

    private static final Set<String> TITLES = new HashSet<>(Arrays.asList(("kuya tatay papa dad daddy lolo tito sir mister mr king prince boss ate nanay mama "
            + "mom mommy lola tita maam miss mrs queen princess angel grace faith hope joy summer rain heaven will max ace grey hunter").split(" ")));

    private static boolean isFirstPerson(List<String> lines) {
        int i = 0, words = 0;
        for (String l : lines) {
            for (String w : l.split("[^\\p{L}']+")) {
                words++;
                if (w.equals("I") || w.equals("ako") || w.equals("Ako") || w.equals("ko")) i++;
            }
        }
        return words > 0 && i * 60 > words;
    }

    private static void mention(List<String> recent, String name) {
        recent.remove(name);
        recent.add(name);
        if (recent.size() > 12) recent.remove(0);
    }

    /** The character's voice: the next free one for a man, a woman, or one the reader can't tell. */
    private static String codeFor(Map<String, Person> people, Person p) {
        if (p == null) return "";
        if (p.code != null) return p.code;
        if (DIVINE_NAMES.contains(p.name)) {
            p.code = divineCodeOf(p.name);
            return p.code;
        }
        String g = p.gender.isEmpty() ? "u" : p.gender;
        int used = 0;
        for (Person o : people.values()) if (o.code != null && o.code.charAt(1) == g.charAt(0)) used++;
        p.code = "c" + g + used;
        return p.code;
    }
}
