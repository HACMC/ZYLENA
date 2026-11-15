package com.zylena.screenreader;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The sounds people make without words: a sigh, a gasp, a groan, a scoff, a cleared throat. When a story tells of one, the
 * character makes it ("Haaah…") instead of the words that describe it being read, if the author wrote little more than the sound;
 * when the author wrote more ("I let out a long, awkward sigh when the reality finally sank in"), the sound comes first and the
 * sentence is read as written. How it sounds follows what the author wrote ("a long sigh", "a sigh of relief", "an annoyed sigh").
 */
final class NonVerbal {
    private NonVerbal() {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final String[] KINDS = {"sigh", "gasp", "groan", "scoff", "ahem", "scream"};
    private static final Pattern[] CUE = {
            p("(?<![\\p{L}])(?:sigh(?:ed|s|ing)?|exhaled|buntong-?hininga|napabuntong-?hininga|huminga nang malalim)(?![\\p{L}])"),
            p("(?<![\\p{L}])(?:gasp(?:ed|s|ing)?|napasinghap|singhap)(?![\\p{L}])"),
            p("(?<![\\p{L}])(?:groan(?:ed|s|ing)?|moan(?:ed|s)?|napaungol|ungol)(?![\\p{L}])"),
            p("(?<![\\p{L}])(?:scoff(?:ed|s)?|snort(?:ed|s)?|tsk(?:ed)?)(?![\\p{L}])"),
            p("(?<![\\p{L}])(?:cleared (?:his|her|their|my) throat|ahem)(?![\\p{L}])"),
            p("(?<![\\p{L}])(?:screamed|shrieked|let out a (?:\\w+ )?scream|tumili)(?![\\p{L}])")};
    private static final Pattern WITH_SOUND = p(",?\\s*(?:and\\s+)?(?:with|in)\\s+(?:a|an|some)\\s+(?:\\w+\\s+){0,2}?(?:sigh|gasp|groan|scoff|snort)\\w*(?:\\s+of\\s+\\w+)?");
    private static final Pattern ING = p(",?\\s*(?:sighing|gasping|groaning|scoffing)(?=[.!?,\"”’]|\\s*$)");
    private static final Pattern VERB = p("(?<![\\p{L}])(?:sighed|gasped|groaned|moaned|scoffed|snorted)(?![\\p{L}])");
    private static final Pattern SUBORDINATE = p("(?<![\\p{L}])(?:when|as|because|while|after|before|that|until|since|then|but|and)(?![\\p{L}])");

    /** The kind of sound the sentence tells of ("sigh", "gasp", …), or null. */
    static String kindOf(String sentence) {
        for (int i = 0; i < KINDS.length; i++) {
            if (!CUE[i].matcher(sentence).find()) continue;
            // A scream with words around it ("…," she screamed) is a way of speaking, already shouted.
            if (KINDS[i].equals("scream") && hasQuote(sentence)) continue;
            return KINDS[i];
        }
        return null;
    }

    static boolean hasQuote(String s) {
        return s.indexOf('"') >= 0 || s.indexOf('“') >= 0 || s.indexOf('”') >= 0;
    }

    /** The author wrote little more than the sound: a few words, no "when…" that goes on to say more. */
    static boolean onlySound(String sentence, String kind) {
        if (hasQuote(sentence)) return false;
        String t = sentence.trim();
        if (t.split("\\s+").length > 6) return false;
        return !SUBORDINATE.matcher(t).find();
    }

    /** A tag after what was said, without the words about the sound: "…," she said with a sigh. becomes "…," she said. */
    static String without(String sentence, String kind) {
        String t = WITH_SOUND.matcher(sentence).replaceAll("");
        t = ING.matcher(t).replaceAll("");
        if (hasQuote(t) && VERB.matcher(t).find()) t = VERB.matcher(t).replaceFirst("said");
        return t.trim();
    }

    /** {what is said, the feeling it is said with}, from the sentence the author wrote. */
    static String[] say(String kind, String sentence) {
        String s = sentence.toLowerCase(Locale.ROOT);
        switch (kind) {
            case "sigh":
                if (s.matches(".*\\b(?:relie(?:f|ved)|phew)\\b.*")) return new String[]{"Phew…", "h"};
                if (s.matches(".*\\b(?:annoy\\w*|exasperat\\w*|irritat\\w*|frustrat\\w*|impatien\\w*|tired of)\\b.*")) return new String[]{"Hmph… haaah.", "a"};
                if (s.matches(".*\\b(?:sad|heartbroken|sorrowful|mournful|weary|defeated|hopeless)\\b.*")) return new String[]{"Haaaah…", "d"};
                if (s.matches(".*\\b(?:happy|content|contented|dreamy|wistful|lovesick|longing)\\b.*")) return new String[]{"Haaah.", "t"};
                if (s.matches(".*\\b(?:long|deep|heavy|big|awkward|nervous|shaky|trembling)\\b.*")) return new String[]{"Haaaaah…", "h"};
                return new String[]{"Haaah…", "h"};
            case "gasp":
                return new String[]{"Hah!", "f"};
            case "groan":
                if (s.matches(".*\\b(?:annoy\\w*|frustrat\\w*|irritat\\w*|exasperat\\w*).*")) return new String[]{"Ugh!", "a"};
                return new String[]{"Uuugh…", "d"};
            case "scoff":
                return new String[]{"Hmph.", "a"};
            case "ahem":
                return new String[]{"Ahem.", ""};
            default: // scream
                return new String[]{"Aaaah!", "s"};
        }
    }
}
