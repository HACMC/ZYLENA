package com.zylena.screenreader;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kind of person a character is, from how the story describes them: a child, an old woman, a mafia boss,
 * a maid; and what their voice is like ("in a husky voice", "his gravelly voice"). The answer is the name of a
 * VoiceFx style, or "" when nothing is said.
 */
final class Persona {
    private Persona() {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final Pattern BOSS = p("\\b(?:mafia|mob ?boss|mobster|mafioso|godfather|crime (?:boss|lord)|gang ?(?:leader|lord|ster)|kingpin|capo|underboss|yakuza|"
            + "the don|boss)\\b");
    private static final Pattern MAID = p("\\b(?:maid|housemaid|servant|housekeeper|nanny|katulong|kasambahay|yaya)\\b");
    private static final Pattern BUTLER = p("\\b(?:butler|valet)\\b");
    /** Affected, pretentious, "sosyal" or "conyo" speech: said higher and a little slower, with care. */
    private static final Pattern MAARTE = p("\\b(?:maarte|maarteng|pa-?sosyal|sosyal|conyo|pa-?conyo|snob|snobby|snobbish|bratinella|spoiled brat)\\b");
    private static final Pattern VILLAIN = p("\\b(?:villain|witch|sorcerer|sorceress|demon|devil|satan|satanas|lucifer|manunukso|tempter|tyrant|warlock|dark lord)\\b");
    private static final Pattern ELDER = p("\\b(?:old (?:man|woman|lady|gentleman|farmer|fisherman)|elderly|grandma|grandpa|grandmother|grandfather|granny|grandpa|lola|lolo|"
            + "matanda|gray-haired|grey-haired|white-haired|wrinkled|aged)\\b");
    private static final Pattern CHILD = p("\\b(?:little (?:boy|girl|kid|one|sister|brother)|toddler|baby|infant|kid|child|preschooler|kindergartener|bata|batang|paslit|musmos|sanggol)\\b");
    private static final Pattern TEEN = p("\\b(?:teen|teenager|teenage|schoolgirl|schoolboy|high school(?:er)? student|binatilyo)\\b");
    private static final Pattern AGE = p("\\b(\\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|"
            + "sixty|seventy|eighty|ninety)[ -]?(?:years?[ -]old|taong gulang|yrs? old|yo)\\b");
    private static final Map<String, Integer> WORD_AGE = new HashMap<>();

    static {
        String[] w = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen"};
        for (int i = 0; i < w.length; i++) WORD_AGE.put(w[i], i + 1);
        WORD_AGE.put("sixty", 60);
        WORD_AGE.put("seventy", 70);
        WORD_AGE.put("eighty", 80);
        WORD_AGE.put("ninety", 90);
    }

    /** From a sentence that describes who somebody is: a style, or "". */
    static String ofRole(String s) {
        Matcher a = AGE.matcher(s);
        if (a.find()) {
            String v = a.group(1).toLowerCase(Locale.ROOT);
            int age;
            try {
                age = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                age = WORD_AGE.getOrDefault(v, 30);
            }
            if (age <= 12) return "child";
            if (age <= 17) return "teen";
            if (age >= 65) return "elder";
        }
        if (BOSS.matcher(s).find()) return "boss";
        if (MAARTE.matcher(s).find()) return "maarte";
        if (VILLAIN.matcher(s).find()) return "villain";
        if (BUTLER.matcher(s).find()) return "butler";
        if (MAID.matcher(s).find()) return "maid";
        if (ELDER.matcher(s).find()) return "elder";
        if (CHILD.matcher(s).find()) return "child";
        if (TEEN.matcher(s).find()) return "teen";
        return "";
    }

    /** A word before a name that says who it is ("Lola Rosa", "Don Vito", "Yaya Ana"). */
    static String ofHonor(String word) {
        if (word == null) return "";
        switch (word.toLowerCase(Locale.ROOT)) {
            case "lola":
            case "lolo":
            case "granny":
            case "grandma":
            case "grandpa":
                return "elder";
            case "don":
            case "dona":
            case "godfather":
            case "boss":
                return "boss";
            case "yaya":
            case "manang":
                return "maid";
            default:
                return "";
        }
    }

    // ------------------------------------------------------------------ the sound of a voice

    private static final String VOICE_NOUN = "(?:voice|tone|whisper|growl|murmur|drawl|purr)";
    private static final Pattern HUSKY = p("\\b(?:husky|raspy|rasping|gravelly|gravel|hoarse|throaty|smoky|sultry|croaky|croaking|paos|garalgal)\\b(?:\\s+\\w+)?\\s+" + VOICE_NOUN
            + "|\\b(?:huskily|hoarsely|rasped|rasps|croaked|husked)\\b|\\bpaos na boses\\b");
    private static final Pattern GRUFF = p("\\b(?:gruff|rough|growly|growling|rumbling|menacing|threatening|cold|icy|sinister)\\b(?:\\s+\\w+)?\\s+" + VOICE_NOUN
            + "|\\b(?:gruffly|growled|growls|snarled|snarls)\\b");
    private static final Pattern BASS = p("\\b(?:deep|low|baritone|bass|booming|resonant)\\b(?:\\s+\\w+)?\\s+" + VOICE_NOUN + "|\\bmalalim na boses\\b");
    private static final Pattern CUTE = p("\\b(?:cute|adorable|bubbly|perky|chirpy|cheerful|giggly|childlike|childish|baby|sweet and high)\\b(?:\\s+\\w+)?\\s+" + VOICE_NOUN
            + "|\\bmalambing na boses\\b");
    private static final Pattern SQUEAKY = p("\\b(?:squeaky|shrill|high-pitched|piping|screechy|nasal)\\b(?:\\s+\\w+)?\\s+" + VOICE_NOUN
            + "|\\b(?:squeaked|squealed|squeals|squeaks|piped up)\\b|\\bmatinis na boses\\b");
    private static final Pattern GENTLE = p("\\b(?:soft|gentle|sweet|tender|soothing|calm|timid|shy|meek|silky|velvety|warm|kind)\\b(?:\\s+\\w+)?\\s+" + VOICE_NOUN
            + "|\\bmahinang boses\\b");

    private static final Pattern MAARTE_VOICE = p("\\b(?:snobby|snobbish|posh|prissy|haughty|pretentious|affected|maarte|sosyal|conyo|nasal|bratty|diva)\\b(?:\\s+\\w+)?\\s+" + VOICE_NOUN
            + "|\\b(?:maarteng|pa-?sosyal(?:ng)?|conyo)\\s+(?:boses|pananalita|accent)|\\b(?:sniffed|drawled|simpered|huffily|haughtily|snootily)\\b");

    // ------------------------------------------------------------------ accents

    private static final String PLACE = "(?:british|english|irish|scottish|welsh|australian|aussie|indian|american|london|england|britain|ireland|dublin|scotland|sydney|australia|india|mumbai|delhi)";
    private static final Pattern ACCENT_SAID = p("\\b(" + PLACE + ")\\s+(?:accent|drawl|lilt|brogue|twang)\\b|\\baccent\\s+(?:of|from)\\s+(?:a\\s+)?(" + PLACE + ")\\b"
            + "|\\b(?:with|in)\\s+an?\\s+(" + PLACE + ")\\s+(?:voice|accent)\\b");
    private static final Pattern ACCENT_PERSON = p("\\b(?:an?\\s+|the\\s+)(british|irish|scottish|australian|aussie|indian|englishman|englishwoman|londoner|briton|scotsman|irishman|irishwoman)"
            + "(?:\\s+(?:man|woman|guy|girl|gentleman|lady|boy|lord|duke|butler|agent|doctor|teacher|actor|actress|singer|model|student|businessman|prince|princess))?\\b"
            + "|\\b(?:from|of)\\s+(london|england|britain|the uk|ireland|dublin|scotland|sydney|australia|india|mumbai|delhi)\\b");

    /** The country of the accent a sentence gives somebody ("a British accent", "an Irish girl"): gb, ie, au, in; or "" when none is said. */
    static String ofAccent(String s) {
        String w = null;
        Matcher m = ACCENT_SAID.matcher(s);
        if (m.find()) for (int g = 1; g <= m.groupCount() && w == null; g++) w = m.group(g);
        if (w == null) {
            m = ACCENT_PERSON.matcher(s);
            if (m.find()) for (int g = 1; g <= m.groupCount() && w == null; g++) w = m.group(g);
        }
        if (w == null) return "";
        w = w.toLowerCase(Locale.ROOT);
        if (w.matches("british|english|englishman|englishwoman|london|londoner|england|britain|briton|the uk|scottish|scotsman|scotland|welsh")) return "gb";
        if (w.matches("irish|irishman|irishwoman|ireland|dublin")) return "ie";
        if (w.matches("australian|aussie|sydney|australia")) return "au";
        if (w.matches("indian|india|mumbai|delhi")) return "in";
        return "";
    }

    /** The kind of voice in a style such as "child@gb" (the accent is not part of it): "child". */
    static String kindOf(String style) {
        if (style == null) return "";
        int at = style.indexOf('@');
        return at < 0 ? style : style.substring(0, at);
    }

    /** The accent in a style such as "child@gb": "gb"; "" for none. */
    static String accentIn(String style) {
        if (style == null) return "";
        int at = style.indexOf('@');
        return at < 0 ? "" : style.substring(at + 1);
    }

    /** From a sentence that tells how somebody's voice sounds ("in a husky voice", "she squeaked"): a style, or "". */
    static String ofVoice(String s) {
        if (MAARTE_VOICE.matcher(s).find()) return "maarte";
        if (HUSKY.matcher(s).find()) return "husky";
        if (GRUFF.matcher(s).find()) return "gruff";
        if (BASS.matcher(s).find()) return "bass";
        if (SQUEAKY.matcher(s).find()) return "squeaky";
        if (CUTE.matcher(s).find()) return "cute";
        if (GENTLE.matcher(s).find()) return "gentle";
        return "";
    }

    /** A VoiceFx style's age for the sounds a person makes: "child", "teen", "elder" or "adult". */
    static String ageOf(String style) {
        switch (kindOf(style)) {
            case "child":
            case "squeaky":
            case "cute":
                return "child";
            case "teen":
                return "teen";
            case "elder":
                return "elder";
            default:
                return "adult";
        }
    }
}
