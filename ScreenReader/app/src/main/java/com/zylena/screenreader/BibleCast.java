package com.zylena.screenreader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who speaks in Bible text. The Bible prints no quotation marks ("And he said unto him, What is written in the
 * law?"), so the words people say are found from how they are introduced ("said", "sinabi niya sa kaniya,"), and
 * who says them from who is named or, when only "he" is said, from whose turn it is. Quotation marks are put
 * back around those words and each speaker gets a voice code, so the story machinery reads the narrator in the
 * Bible voice and each person in a voice of their own. Jesus and God always get the first man's voice.
 */
final class BibleCast {
    private BibleCast() {
    }

    static final class Result {
        String text;
        String[] codes;
        String summary = "";
    }

    private static final Pattern LEAD = Pattern.compile("^\\s*(\\d{1,3})\\s*[.)]?\\s*(?=[\\p{L}“\"‘'(\\[])");
    private static final Pattern INLINE = Pattern.compile("(?<=[.!?;:,”’\"')])\\s+(\\d{1,3})\\s+(?=[\\p{Lu}“\"‘'(])");

    /** The words that bring in what somebody says: up to the comma or colon after them. */
    private static final Pattern INTRO = Pattern.compile("(?i)(?<![\\p{L}])(?:sinasabi|sinabi|nagsabi|nagsasabi|sabi|wika|sumagot|sumasagot|sagot|nagtanong|tinanong|nagtatanong|"
            + "sumigaw|sumisigaw|nagsisigaw|nagsigaw|bumulong|nagsalita|nagsasalita|nagutos|iniutos|ani|"
            + "said|saith|spake|spoke|answered|answering|asked|asking|cried|crying|replied|commanded|shouted|told|saying)(?![\\p{L}])[^.!?;:,]{0,60}?[,:]");
    /** A verse that tells what happened, not what is said. */
    private static final Pattern NARRATION = Pattern.compile("(?i)^(?:at|and|nang|when|then|now|so|datapuwa't|datapuwat|but|pagkatapos|noon|after|ngunit)\\b.*");
    /** First or second person: people talking to each other. */
    private static final Pattern TALK = Pattern.compile("(?i)(?<![\\p{L}'])(?:ako|ako'y|ko|akin|aking|ikaw|ka|mo|iyo|iyong|inyo|inyong|kayo|kayo'y|tayo|natin|namin|amin|"
            + "i|me|my|mine|thou|thee|thy|thine|you|ye|your|yours|we|us|our|hindi baga|ought)(?![\\p{L}])");
    private static final Pattern FIRST_PERSON = Pattern.compile("(?i)(?<![\\p{L}'])(?:ko|i|we|namin|natin|amin|tayo)(?![\\p{L}])");
    private static final Pattern DIVINE = Pattern.compile("(?i)(?<![\\p{L}])(?:jesus|hesus|jesu|cristo|christ|mesias|messias|dios|diyos|god|panginoon|lord|jehova|jehovah|yahweh|señor)(?![\\p{L}])");
    private static final Pattern FEMALE_CUE = Pattern.compile("(?i)(?<![\\p{L}])(?:babae|woman|women|reyna|queen|dalaga|maiden|damsel|ina|mother|she|siya'y babae|virgin|birhen)(?![\\p{L}])");
    private static final Pattern NAMED = Pattern.compile("(?:(?<![\\p{L}])(?:ni|si|ng|kay)\\s+(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+))"
            + "|(?:(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+)\\s+(?:said|saith|answered|spake|asked|replied|cried))"
            + "|(?:(?:said|saith|answered|spake|asked|replied|cried)\\s+(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+))");
    private static final Pattern CAPITAL = Pattern.compile("(?<![\\p{L}'’])\\p{Lu}\\p{Ll}[\\p{L}'’]+");
    private static final java.util.Set<String> NOT_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            ("at and nang when then now so datapuwa't datapuwat but ngunit pagkatapos noon after sumagot answered said saith sinabi sinasabi ang ng si ni kay "
                    + "mga ito iyon siya sila he she they the a an in on of to for by with from lahat every isang one").split(" ")));

    /**
     * The text with quotation marks around what people say and the voice code of each of those quotations, or null
     * when nobody speaks in it. The verse numbers are taken out (the verses stay in order, one after the other).
     */
    static Result annotate(String line) {
        String t = Bible.normalize(line);
        Matcher lead = LEAD.matcher(t);
        if (lead.find()) t = t.substring(lead.end());
        List<String> verses = new ArrayList<>();
        Matcher in = INLINE.matcher(t);
        int last = 0;
        while (in.find()) {
            verses.add(t.substring(last, in.start()).trim());
            last = in.end();
        }
        verses.add(t.substring(last).trim());
        return annotateVerses(verses, '\u201C', '\u201D', false);
    }

    /**
     * The same for verses already apart (the built-in Bible's). tagged: each opening quotation mark is followed by
     * the speaker's voice code between two \u0004 marks, for the caller to take out again (see Bible.takeCodes).
     */
    static Result annotateVerses(List<String> verseList, char open, char close, boolean tagged) {
        List<String> verses = new ArrayList<>();
        for (String v : verseList) verses.add(v.replaceAll("[\u201C\u201D\"\u00AB\u00BB]", "").trim());
        State st = new State();
        StringBuilder out = new StringBuilder();
        List<String> codes = new ArrayList<>();
        boolean inSpeech = false;
        String speaker = "";
        boolean any = false;
        for (String v : verses) {
            if (v.isEmpty()) continue;
            String narr = v, speech = null;
            String who = null;
            Matcher m = INTRO.matcher(v);
            while (m.find()) {
                if (inSpeech && m.start() > 50) break;
                String intro = m.group();
                if (FIRST_PERSON.matcher(intro).find()) continue; // "sinabi ko sa inyo": the speaker's own words
                String rest = v.substring(m.end()).trim();
                if (rest.length() < 3 || rest.chars().noneMatch(Character::isLetter)) continue;
                narr = v.substring(0, m.start()) + intro;
                speech = rest;
                who = st.who(v.substring(Math.max(0, m.start() - 40), m.start()) + " " + intro, v.substring(0, m.start()));
                break;
            }
            if (speech == null && inSpeech && !narrates(v)) {
                narr = "";
                speech = v; // the speech goes on into this verse
                who = speaker;
            }
            if (speech == null) {
                inSpeech = false;
                out.append(out.length() > 0 ? " " : "").append(v);
                continue;
            }
            any = true;
            inSpeech = true;
            speaker = who;
            if (!narr.isEmpty()) out.append(out.length() > 0 ? " " : "").append(narr);
            out.append(out.length() > 0 ? " " : "").append(open);
            if (tagged) out.append('\u0004').append(who).append('\u0004');
            out.append(speech.trim()).append(close);
            codes.add(who);
        }
        if (!any) return null;
        Result r = new Result();
        r.text = out.toString();
        r.codes = codes.toArray(new String[0]);
        r.summary = st.summary();
        return r;
    }

    /** A verse that tells what happened: it begins like a story and nobody in it says "I" or "you". */
    private static boolean narrates(String v) {
        if (!NARRATION.matcher(v).matches()) return false;
        String head = v.length() > 140 ? v.substring(0, 140) : v;
        return !TALK.matcher(head).find();
    }

    /** Who has spoken so far in the passage, and the voice code of each. */
    private static final class State {
        final List<String> keys = new ArrayList<>(); // speakers in order of first speech
        final List<String> codes = new ArrayList<>();
        final List<String> history = new ArrayList<>(); // one entry per turn
        int men = 1, women = 0, anon;

        String who(String nearIntro, String before) {
            String key = null;
            Matcher d = DIVINE.matcher(nearIntro);
            if (d.find()) {
                key = "divine";
            } else {
                // A named person: "sinabi ni Pedro", "Martha said", "said Peter".
                Matcher c = NAMED.matcher(nearIntro);
                while (c.find() && key == null) {
                    String w = (c.group(1) != null ? c.group(1) : c.group(2) != null ? c.group(2) : c.group(3)).toLowerCase(Locale.ROOT);
                    if (!NOT_NAMES.contains(w)) key = w;
                }
                if (key == null) {
                    Matcher c2 = CAPITAL.matcher(nearIntro);
                    while (c2.find() && key == null) {
                        String w = c2.group().toLowerCase(Locale.ROOT);
                        if (TextPrep.MEN.contains(w) || TextPrep.WOMEN.contains(w)) key = w;
                    }
                }
            }
            if (key == null) {
                // Only "he" / "niya": the other one of the two who talk, else somebody new.
                String lastKey = history.isEmpty() ? null : history.get(history.size() - 1);
                for (int k = history.size() - 2; k >= 0 && key == null; k--) if (!history.get(k).equals(lastKey)) key = history.get(k);
                if (key == null) {
                    key = "#" + (++anon);
                    String cue = before + " " + nearIntro;
                    if (FEMALE_CUE.matcher(cue).find()) femaleKeys.add(key);
                }
            }
            history.add(key);
            int at = keys.indexOf(key);
            if (at < 0) {
                keys.add(key);
                String code;
                if (key.equals("divine")) code = "cm0";
                else if (femaleKeys.contains(key) || TextPrep.WOMEN.contains(key)) code = "cf" + (women++);
                else code = "cm" + (men++);
                codes.add(code);
                at = keys.size() - 1;
            }
            return codes.get(at);
        }

        final java.util.Set<String> femaleKeys = new java.util.HashSet<>();

        String summary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                sb.append(sb.length() > 0 ? ", " : "").append(k.equals("divine") ? "Jesus/God" : k.startsWith("#") ? "someone" + k : Character.toUpperCase(k.charAt(0)) + k.substring(1))
                        .append(" (").append(codes.get(i)).append(")");
            }
            return sb.toString();
        }
    }
}
