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
    private static final Pattern HE = Pattern.compile("(?i)(?<![\\p{L}'])(?:he|siya|niya|kaniyang|sya)(?![\\p{L}])");
    private static final Pattern SOMEONE = Pattern.compile("(?i)(?<![\\p{L}])(?:certain|isang|ilang|someone|a\\s+(?:man|woman|lawyer|ruler|scribe|centurion)|isa\\s+sa)(?![\\p{L}])");

    /** A part of a verse that names who speaks ("sinabi ni Pedro", "Peter said", the devil, the Spirit): somebody else takes over. */
    private static boolean namesSomeone(String part) {
        Matcher c = NAMED.matcher(part);
        while (c.find()) {
            String w = (c.group(1) != null ? c.group(1) : c.group(2) != null ? c.group(2) : c.group(3)).toLowerCase(Locale.ROOT);
            if (!NOT_NAMES.contains(w)) return true;
        }
        return VILLAIN.matcher(part).find() || SPIRIT.matcher(OF_GOD.matcher(part).replaceAll(" ")).find();
    }

    /** A verse that tells what happened, not what is said. */
    private static final Pattern NARRATION = Pattern.compile("(?i)^(?:at|and|nang|when|then|now|so|datapuwa't|datapuwat|but|pagkatapos|noon|after|ngunit)\\b.*");
    /** First or second person: people talking to each other. */
    private static final Pattern TALK = Pattern.compile("(?i)(?<![\\p{L}'])(?:ako|ako'y|ko|akin|aking|ikaw|ka|mo|iyo|iyong|inyo|inyong|kayo|kayo'y|tayo|natin|namin|amin|"
            + "i|me|my|mine|thou|thee|thy|thine|you|ye|your|yours|we|us|our|hindi baga|ought)(?![\\p{L}])");
    private static final Pattern FIRST_PERSON = Pattern.compile("(?i)(?<![\\p{L}'])(?:ko|i|we|namin|natin|amin|tayo)(?![\\p{L}])");
    private static final Pattern JESUS = Pattern.compile("(?i)(?<![\\p{L}])(?:jesus|hesus|jesu|jesucristo|hesukristo|cristo|kristo|christ|mesias|messias)(?![\\p{L}])");
    private static final Pattern SPIRIT = Pattern.compile("(?i)(?<![\\p{L}])(?:espiritu|spirit|ghost)(?![\\p{L}])");
    private static final Pattern FATHER = Pattern.compile("(?i)(?<![\\p{L}])(?:dios|diyos|god|panginoon|lord|jehova|jehovah|yahweh|se\u00F1or|(?-i:Ama)|(?:tinig|boses)\\s+(?:mula|galing)\\s+sa\\s+langit|voice\\s+(?:from|out\\s+of)\\s+(?:the\\s+)?(?:heaven|cloud))(?![\\p{L}])");
    private static final String DIVINE_WORDS = "jesus|hesus|jesu|jesucristo|hesukristo|cristo|kristo|christ|mesias|messias|dios|diyos|god|panginoon|lord|jehova|jehovah|yahweh|ama|father|"
            + "(?:holy\\s+)?(?:spirit|ghost)|espiritu(?:\\s+santo)?|(?:banal\\s+na\\s+)?espiritu";
    /**
     * The one spoken to is not the one who speaks: "Peter said unto Jesus,", "sinabi ni Moises sa Panginoon,", "Satan answered
     * the LORD, and said," (but "Then answered Jesus and said," is Jesus answering). Taken out of the words that bring the speech in before the speaker is looked for.
     */
    private static final Pattern ADDRESSED = Pattern.compile("(?i)(?<![\\p{L}])(?:(?:unto|to|kay|sa)\\s+|(?<!\\b(?:then|and|but|so|now)\\s)(?:answered|answering|asked|told|besought|tinanong|sinagot)\\s+)"
            + "(?:the\\s+|ang\\s+|si\\s+|our\\s+|his\\s+|their\\s+|ating\\s+|kaniyang\\s+|kanilang\\s+)?(?:lord\\s+|panginoong\\s+)?(?:" + DIVINE_WORDS + ")(?![\\p{L}])");
    /**
     * Somebody who only belongs to God, or is filled with the Spirit, is not God speaking: "the man of God said,", "ang anghel ng
     * Panginoon", "Peter, filled with the Holy Ghost, said unto them,".
     */
    private static final Pattern OF_GOD = Pattern.compile("(?i)(?<![\\p{L}])(?:(?:man|men|woman|servants?|prophets?|priests?|people|children|house|temple|ark|angels?|sons?|"
            + "lingkod|alipin|propeta|saserdote|bayan|anghel|lalake|lalaki|bahay|templo|kaban|anak|mga\\s+anak)\\s+(?:of|ng)\\s+(?:the\\s+)?(?:lord|god|dios|diyos|panginoon)"
            + "|(?:filled\\s+with|full\\s+of|in|by|through|puspos\\s+ng|napuspos\\s+ng|sa\\s+pamamagitan\\s+ng)\\s+(?:the\\s+)?(?:holy\\s+|banal\\s+na\\s+)?(?:spirit|ghost|espiritu)(?:\\s+santo)?)(?![\\p{L}])");
    /** An unclean spirit is not the Holy Spirit: it speaks as the tempter does. */
    private static final Pattern EVIL_SPIRIT = Pattern.compile("(?i)(?<![\\p{L}])(?:unclean|evil|foul|dumb|deaf|wicked|lying|familiar|masamang|masasamang|karumaldumal\\s+na|maruming|"
            + "karumaldumal)\\s+(?:spirits?|espiritu)(?![\\p{L}])");
    /** The one who tempts or opposes: Satan speaks in a voice of his own, not Jesus's. */
    private static final Pattern VILLAIN = Pattern.compile("(?i)(?<![\\p{L}])(?:satan|satanas|devil|diablo|lucifer|demon|demonyo|manunukso|tempter|serpent|ahas)(?![\\p{L}])");
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
        return annotateVerses(verseList, open, close, tagged, 0);
    }

    /**
     * As above, the first `context` verses being only what came before the passage in its chapter (and a little of the chapter
     * before): they are read to learn who is speaking, since the Bible says it in other verses ("And Jesus … taught them, saying",
     * five chapters of the Sermon on the Mount), but they are not part of the result.
     */
    static Result annotateVerses(List<String> verseList, char open, char close, boolean tagged, int context) {
        List<String> verses = new ArrayList<>();
        for (String v : verseList) verses.add(v.replaceAll("[\u201C\u201D\"\u00AB\u00BB]", "").trim());
        State st = new State();
        StringBuilder outAll = new StringBuilder();
        List<String> codesAll = new ArrayList<>();
        boolean inSpeech = false;
        String speaker = "";
        boolean any = false;
        boolean seenVerse = false;
        // Does the passage begin inside Jesus's own words? (A marker only he uses within the first few verses, and nothing before it
        // that tells what happened or brings somebody else's words in.)
        boolean startsInJesus = false;
        {
            int firstReal = -1;
            for (int k = context; k < verses.size() && k <= context + 3; k++) {
                String v = verses.get(k);
                if (v.isEmpty()) continue;
                if (firstReal < 0) firstReal = k;
                if (JESUS_SAYS.matcher(v).find() && !narrates(v) && !INTRO.matcher(v.substring(0, Math.min(v.length(), 40))).find()) {
                    startsInJesus = true;
                    break;
                }
                if (narrates(v) || INTRO.matcher(v).find()) break;
            }
        }
        for (int vi = 0; vi < verses.size(); vi++) {
            String v = verses.get(vi);
            if (v.isEmpty()) continue;
            // (A verse of context is read for who speaks, but what it comes to is thrown away.)
            final boolean ctxV = vi < context;
            final StringBuilder out = ctxV ? new StringBuilder() : outAll;
            final List<String> codes = ctxV ? new ArrayList<>() : codesAll;
            // Who is spoken of lately: an unnamed "he" later in the passage is most likely this one.
            String mk = JESUS.matcher(v).find() ? "jesus" : FATHER.matcher(v).find() ? "father" : null;
            if (mk != null) st.mentioned = mk;
            final boolean firstVerse = vi == context || (context == 0 && !seenVerse);
            seenVerse = true;
            String narr = v, speech = null;
            String who = null;
            String pendingWho = null;
            // Inside Jesus's (or God's) words, "you have heard that it was said to those of old, …" quotes somebody else: it
            // is still him speaking, unless somebody is named as speaking.
            final boolean divineSpeech = inSpeech && speaker != null && speaker.startsWith("cd");
            Matcher m = INTRO.matcher(v);
            while (m.find()) {
                if (inSpeech && m.start() > 50) break;
                if (divineSpeech && m.start() <= 50 && !namesSomeone(v.substring(0, m.end()))) continue;
                String intro = m.group();
                if (FIRST_PERSON.matcher(intro).find()) continue; // "sinabi ko sa inyo": the speaker's own words
                String rest = v.substring(m.end()).trim();
                if (rest.length() < 3 || rest.chars().noneMatch(Character::isLetter)) {
                    // "…and taught them, saying," at the end of a verse: the words themselves begin with the next verse.
                    if (rest.chars().noneMatch(Character::isLetter) && pendingWho == null) {
                        pendingWho = st.who(v.substring(Math.max(0, m.start() - 40), m.start()) + " " + intro, v.substring(0, m.start()));
                    }
                    continue;
                }
                // "sumagot, at sinabi, Nasusulat…": the second "said" belongs to the introduction too.
                java.util.regex.Matcher again = AGAIN.matcher(rest);
                if (again.find()) {
                    intro = intro + " " + again.group();
                    rest = rest.substring(again.end()).trim();
                    if (rest.length() < 3) continue;
                }
                // "cried with a loud voice, saying, Lazarus…": the "saying" belongs to what introduces the words.
                java.util.regex.Matcher sy = SAYING.matcher(rest);
                if (sy.find() && rest.length() - sy.end() >= 3) {
                    intro = intro + " " + sy.group().replaceAll("[\\s,:]+$", "") + ",";
                    rest = rest.substring(sy.end()).trim();
                }
                narr = v.substring(0, m.start()) + intro;
                speech = rest;
                who = st.who(v.substring(Math.max(0, m.start() - 40), m.start()) + " " + intro, v.substring(0, m.start()));
                break;
            }
            // A passage that begins inside Jesus's own words ("Sapagka't katotohanang sinasabi ko sa inyo…", "Verily I say unto you…"),
            // as a lesson quotes the Sermon on the Mount from verse 17: no "Jesus said" before it, but only he says these things.
            if (speech == null && pendingWho != null) {
                inSpeech = true;
                speaker = pendingWho;
                out.append(out.length() > 0 ? " " : "").append(v);
                continue;
            }
            if (speech == null && !inSpeech && firstVerse && startsInJesus) {
                narr = "";
                speech = v;
                who = st.who("Jesus said", "");
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
            if (!ctxV) any = true;
            inSpeech = true;
            speaker = who;
            // How it is said, from the words that bring it in: "cried with a loud voice" is shouted, "wept" is sad, "whispered" soft.
            // (In the Bible "cried" means a loud cry, so it is read as shouted, not as sobbing.)
            String feel = Mood.feeling(narr.replaceAll("(?i)\\b(?:cried|crying|cry)\\b(?!\\s+out)", "shouted"), who != null && who.startsWith("cd") ? "" : speech); // (what Jesus teaches is not made angry by its words)
            String whoF = feel.isEmpty() || who == null ? who : who + "~" + feel;
            if (!narr.isEmpty()) out.append(out.length() > 0 ? " " : "").append(narr);
            out.append(out.length() > 0 ? " " : "").append(open);
            if (tagged) out.append('\u0004').append(whoF).append('\u0004');
            out.append(speech.trim()).append(close);
            codes.add(whoF);
        }
        if (!any) return null;
        Result r = new Result();
        r.text = outAll.toString();
        r.codes = codesAll.toArray(new String[0]);
        r.summary = st.summary();
        return r;
    }

    private static final Pattern SAYING = Pattern.compile("(?i)^(?:saying|na nagsasabi|nagsasabi|na sinasabi|sinasabi)\\s*[,:]\\s*");
    /** Words only Jesus says, as the Gospels have him: "I say unto you", "you have heard that it was said". */
    private static final Pattern JESUS_SAYS = Pattern.compile("(?i)(?:katotohanang\\s+sinasabi\\s+ko\\s+sa\\s+inyo|sinasabi\\s+ko\\s+sa\\s+inyo|narinig\\s+ninyo\\s+na\\s+sinabi|"
            + "i\\s+say\\s+unto\\s+you|verily,?\\s+(?:verily,?\\s+)?i\\s+say|ye\\s+have\\s+heard\\s+that\\s+it\\s+was\\s+said|but\\s+i\\s+say|"
            + "i\\s+am\\s+the\\s+(?:way|light|bread|door|good\\s+shepherd|vine|resurrection)|ako\\s+ang\\s+(?:daan|ilaw|tinapay|pintuan|puno\\s+ng\\s+ubas|pagkabuhay))");
    private static final Pattern AGAIN = Pattern.compile("(?i)^(?:at|and)\\s+(?:sinabi|nagsabi|said|saith|sumagot|answered)\\b[^,:]{0,40}[,:]\\s*");

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
        int men = 0, women = 0, anon;
        String mentioned; // "jesus" or "father" when the verses have spoken of him lately

        String who(String nearIntro, String before) {
            String key = null;
            // Who is spoken to ("said unto Jesus,", "sa Panginoon") is not who speaks, and neither is the one God's man belongs to.
            String toWhom = null;
            nearIntro = nearIntro.replaceAll("\\s+", " ");
            Matcher ad = ADDRESSED.matcher(nearIntro);
            if (ad.find()) {
                String g = ad.group();
                toWhom = JESUS.matcher(g).find() ? "jesus" : SPIRIT.matcher(g).find() ? "spirit" : "father";
                nearIntro = ad.replaceAll(" ");
            }
            nearIntro = OF_GOD.matcher(nearIntro).replaceAll(" ");
            if (EVIL_SPIRIT.matcher(nearIntro).find()) {
                key = "villain";
            } else if (JESUS.matcher(nearIntro).find()) {
                key = "jesus";
            } else if (SPIRIT.matcher(nearIntro).find()) {
                key = "spirit";
            } else if (FATHER.matcher(nearIntro).find()) {
                key = "father";
            } else if (VILLAIN.matcher(nearIntro).find()) {
                key = "villain";
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
                // Whoever answers the tempter, unnamed, is Jesus (or God).
                if ("villain".equals(lastKey)) key = "jesus";
                for (int k = history.size() - 2; k >= 0 && key == null; k--) if (!history.get(k).equals(lastKey) && !history.get(k).equals(toWhom)) key = history.get(k);
                // "And he opened his mouth, and taught them, saying": the "he" is who the chapter has been about, when nobody else is
                // named, nobody has spoken yet, and it is not "a certain lawyer … tempted him".
                if (key == null && mentioned != null && !mentioned.equals(toWhom) && (history.isEmpty() || !keys.contains(mentioned)) && HE.matcher(before).find() && !SOMEONE.matcher(before).find()) key = mentioned;
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
                if (key.equals("father")) code = Cast.DIVINE_CODE;
                else if (key.equals("jesus")) code = Cast.JESUS_CODE;
                else if (key.equals("spirit")) code = Cast.SPIRIT_CODE;
                else if (key.equals("villain")) code = "cm" + (men++) + "^villain";
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
                sb.append(sb.length() > 0 ? ", " : "").append(k.equals("father") ? "God" : k.equals("jesus") ? "Jesus" : k.equals("spirit") ? "Holy Spirit" : k.startsWith("#") ? "someone" + k : Character.toUpperCase(k.charAt(0)) + k.substring(1))
                        .append(" (").append(codes.get(i)).append(")");
            }
            return sb.toString();
        }
    }
}
