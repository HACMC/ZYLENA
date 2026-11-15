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
    private static final String VERBS = "(?:sinasabi|sinabi|nagsabi|nagsasabi|sabi|wika|sumagot|sumasagot|sagot|nagtanong|tinanong|nagtatanong|"
            + "sumigaw|sumisigaw|nagsisigaw|nagsigaw|bumulong|nagsalita|nagsasalita|nagutos|iniutos|ani|"
            + "said|saith|spake|spoke|answered|answering|asked|asking|cried|crying|replied|commanded|shouted|told|saying|to\\s+say|magsabi)";
    /** A word of saying anywhere ("Jesus said to him" after the words, as newer Bibles print it). */
    private static final Pattern SAYS = Pattern.compile("(?i)(?<![\\p{L}])" + VERBS + "(?![\\p{L}])");
    private static final Pattern INTRO = Pattern.compile("(?i)(?<![\\p{L}])" + VERBS + "(?![\\p{L}])"
            // "At sinabi ng Dios Magkaroon ng liwanag": no comma, the words begin at the capital letter after who speaks.
            + "(?:(?-i:\\s+(?:ng|ni)\\s+\\p{Lu}\\p{Ll}+(?=\\s+(?!(?:Cristo|Kristo|Dios|Diyos|Jesus|Hesus|Santo|Espiritu|Panginoon|Ama|Hari|Anak)\\b)\\p{Lu}\\p{Ll}))|[^.!?;:,]{0,60}?[,:])");
    /** Where the one who tells the story takes over again inside a verse: "…Let there be light: | and there was light." */
    private static final Pattern RESULT = Pattern.compile("(?i)[:;.,]\\s+(?=(?:and\\s+there\\s+w(?:as|ere)|and\\s+it\\s+was\\s+so|at\\s+nagkaroon|at\\s+nagkagayon|at\\s+gayon\\s+nga|at\\s+nangyari|at\\s+ito\\s+nga\\s+ang\\s+nangyari)(?![\\p{L}]))");
    /** A new sentence that begins the way the story is told: after a short word to somebody, the narrator may take over here. */
    private static final Pattern TOLD = Pattern.compile("[.!?]\\s+(?=(?:And|At|Then|But|Datapuwa't|Datapuwat|Ngunit|So|Now|Nang|When|Pagkatapos)\\s)");
    private static final Pattern WILL = Pattern.compile("(?i)(?<![\\p{L}])(?:shall|shalt|will|wilt|let)(?![\\p{L}])");
    /** Up to here somebody's words are a short saying in a conversation; longer, they are a discourse that tells things itself. */
    private static final int SHORT = 140;
    private static final Pattern LET = Pattern.compile("(?i)^(?:and|at)\\s+(?:let|magkaroon|maging|lumitaw|bukalan|tubuan)(?![\\p{L}]).*");
    private static final Pattern PARABLE = Pattern.compile("(?i)^\\W*(?:behold,\\s+)?(?:a\\s+certain|what\\s+man|what\\s+woman|sino\\s+sa\\s+inyo|aling\\s+tao|there\\s+was\\s+a|a\\s+sower|the\\s+kingdom\\s+of|may\\s+isang|isang|ang\\s+kaharian|narito,\\s+ang\\s+manghahasik)(?![\\p{L}])");
    private static final char Q = '\uE000'; // where the page itself has a quotation mark
    private static final Pattern HE = Pattern.compile("(?i)(?<![\\p{L}'])(?:he|siya|niya|kaniyang|sya)(?![\\p{L}])");
    private static final Pattern SOMEONE = Pattern.compile("(?i)(?<![\\p{L}])(?:certain|isang|ilang|someone|a\\s+(?:man|woman|lawyer|ruler|scribe|centurion)|isa\\s+sa)(?![\\p{L}])");

    /** A part of a verse that names who speaks ("sinabi ni Pedro", "Peter said", the devil, the Spirit): somebody else takes over. */
    private static boolean namesSomeone(String part) {
        Matcher c = NAMED.matcher(part);
        while (c.find()) {
            String w = (c.group(1) != null ? c.group(1) : c.group(2) != null ? c.group(2) : c.group(3) != null ? c.group(3) : c.group(4)).toLowerCase(Locale.ROOT);
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
            + "|(?:(?:said|saith|answered|spake|asked|replied|cried)\\s+(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+))"
            // "which was spoken by Esaias the prophet, saying,", "ng propeta Isaias": the prophet's words, in a voice of his own.
            + "|(?:(?<![\\p{L}])(?:by|through|prophet|propeta|propetang)\\s+(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+))");
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
    static Result annotateVerses(List<String> verseList, char open, char close, boolean tagged, int contextVerses) {
        // The page's own quotation marks are the surest sign of where somebody's words end: their places are kept (marked).
        List<String> verses = new ArrayList<>(), marked = new ArrayList<>();
        for (String v : verseList) {
            String mk = v.replace("''", "\"").replaceAll("[\u201C\u201D\"\u00AB\u00BB]", String.valueOf(Q)).trim();
            marked.add(mk);
            verses.add(mk.replace(String.valueOf(Q), "").trim());
        }
        String carry = "";
        boolean parable = false; // Jesus is telling a story: what happens in it is his to tell, verse after verse
        boolean quoteOpen = false; // the page opened a quotation and has not closed it yet
        int context = contextVerses;
        int said = 0; // how much the one speaking has said so far
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
            // A long speech (a sermon, a parable) tells things itself; a short saying is part of a conversation, and
            // "And he said," after it brings in the answer.
            if (!inSpeech) parable = false;
            // A sentence that runs on from the verse before ("…taketh him up into an exceeding high mountain; | And saith unto him,"):
            // who speaks is named there.
            final String carried = carry;
            carry = "";
            final boolean discourse = inSpeech && said > SHORT;
            final Matcher rsm = RESTART.matcher(v);
            final boolean restart = rsm.find() && !TALK.matcher(v.substring(0, rsm.end())).find();
            // The page's own quotation marks, when it has them in pairs, say exactly which words are spoken
            // (“Follow me,” Jesus said to him, and Matthew got up and followed him).
            if (!quoteOpen) {
                String[] cut = marked.get(vi).split(String.valueOf(Q), -1);
                StringBuilder outside = new StringBuilder();
                for (int k = 0; k < cut.length; k += 2) outside.append(cut[k]).append(' ');
                if (cut.length >= 3 && cut.length % 2 == 1 && SAYS.matcher(outside).find()) {
                    String prev = null, told = "";
                    boolean spoke = false;
                    for (int k = 0; k < cut.length; k++) {
                        String piece = cut[k].trim();
                        if (k % 2 == 0) {
                            if (!piece.isEmpty()) out.append(out.length() > 0 ? " " : "").append(piece);
                            told += " " + piece;
                            continue;
                        }
                        String bef = cut[k - 1], aft = cut[k + 1];
                        // Who says it is told in the sentence the words stand in: just before them, or just after.
                        bef = bef.replaceAll("^.*[.!?](?=\\s)", "");
                        aft = aft.replaceAll("(?<=[.!?])\\s.*$", "");
                        bef = bef.length() > 60 ? bef.substring(bef.length() - 60) : bef;
                        aft = aft.length() > 60 ? aft.substring(0, 60) : aft;
                        String near = SAYS.matcher(bef).find() ? bef : SAYS.matcher(aft).find() ? aft : null;
                        if (near == null && piece.indexOf(' ') > 0) near = aft.chars().anyMatch(Character::isLetter) ? aft : bef.chars().anyMatch(Character::isLetter) ? bef : null;
                        // (A word or two only set off by marks, with nobody saying it: “araw”.)
                        if (near == null && (prev == null || piece.indexOf(' ') < 0)) {
                            out.append(out.length() > 0 ? " " : "").append(piece);
                            continue;
                        }
                        String w = near != null ? st.who(near, told) : prev;
                        prev = w;
                        String feel = Mood.feeling((near == null ? "" : near).replaceAll("(?i)\\b(?:cried|crying|cry)\\b(?!\\s+out)", "shouted"), w.startsWith("cd") ? "" : piece);
                        String wf = feel.isEmpty() ? w : w + "~" + feel;
                        out.append(out.length() > 0 ? " " : "").append(open);
                        if (tagged) out.append('\u0004').append(wf).append('\u0004');
                        out.append(piece).append(close);
                        codes.add(wf);
                        spoke = true;
                    }
                    if (spoke) {
                        if (!ctxV) any = true;
                        inSpeech = false;
                        said = 0;
                        continue;
                    }
                }
            }
            Matcher m = INTRO.matcher(v);
            while (m.find()) {
                if (discourse && m.start() > 50) break;
                if ((divineSpeech || parable) && discourse && m.start() <= 50 && !restart && !namesSomeone(v.substring(0, m.end()))) continue;
                String intro = m.group();
                if (FIRST_PERSON.matcher(intro).find()) continue; // "sinabi ko sa inyo": the speaker's own words
                String rest = v.substring(m.end()).trim();
                if (rest.length() < 3 || rest.chars().noneMatch(Character::isLetter)) {
                    // "…and taught them, saying," at the end of a verse: the words themselves begin with the next verse.
                    if (rest.chars().noneMatch(Character::isLetter) && pendingWho == null) {
                        pendingWho = st.who((m.start() < 25 ? lead(carried, carried.length(), 100) + " " : "") + lead(v, m.start(), 40) + " " + intro, v.substring(0, m.start()));
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
                who = st.who((m.start() < 25 ? lead(carried, carried.length(), 100) + " " : "") + lead(v, m.start(), 40) + " " + intro, v.substring(0, m.start()));
                break;
            }
            // A passage that begins inside Jesus's own words ("Sapagka't katotohanang sinasabi ko sa inyo…", "Verily I say unto you…"),
            // as a lesson quotes the Sermon on the Mount from verse 17: no "Jesus said" before it, but only he says these things.
            if (speech == null && pendingWho != null) {
                inSpeech = true;
                speaker = pendingWho;
                said = 0;
                parable = PARABLE_WORD.matcher(v).find();
                out.append(out.length() > 0 ? " " : "").append(v);
                continue;
            }
            if (speech == null && !inSpeech && firstVerse && startsInJesus) {
                narr = "";
                speech = v;
                who = st.who("Jesus said", "");
            }
            // After a short saying, a verse that tells of Jesus or the tempter by name, with no "I" or "you" in it, is the story again
            // ("Again, the devil taketh him up into an exceeding high mountain…").
            // And whoever speaks, a verse that tells of Jesus by name is not somebody else's words going on.
            final boolean noTalk = !TALK.matcher(v.length() > 140 ? v.substring(0, 140) : v).find();
            final boolean tellsOf = noTalk && ((!discourse && (JESUS.matcher(v).find() || VILLAIN.matcher(v).find()))
                    || (JESUS.matcher(v).find() && !Cast.JESUS_CODE.equals(speaker)));
            final boolean ends = tellsOf || restart || (!parable && narrates(v));
            if (speech == null && inSpeech && (quoteOpen || !ends)) {
                narr = "";
                speech = v; // the speech goes on into this verse
                who = speaker;
            }
            if (speech == null) {
                inSpeech = false;
                quoteOpen = false;
                said = 0;
                if (v.matches("(?s).*[,;:]\\s*$")) carry = v;
                out.append(out.length() > 0 ? " " : "").append(v);
                continue;
            }
            // Where the words end inside the verse: at the page's own closing quotation mark, else where the story plainly
            // goes on ("…: and there was light.", "…; at nagkaroon ng liwanag."). What follows is the narrator's again.
            String tail = null;
            boolean closed = false;
            {
                String qv = marked.get(vi);
                int sv = v.length() - speech.length();
                int[] at = new int[v.length() + 1];
                for (int i = 0, j = 0; j < v.length(); j++) {
                    while (i < qv.length() && qv.charAt(i) != v.charAt(j)) i++;
                    at[j] = i++;
                }
                at[v.length()] = qv.length();
                int from = sv > 0 ? at[sv - 1] + 1 : 0;
                boolean opens = qv.substring(Math.min(from, qv.length()), Math.min(at[sv], qv.length())).indexOf(Q) >= 0;
                boolean goesOn = sv == 0 && quoteOpen && narr.isEmpty();
                if (opens || goesOn) {
                    int end = qv.indexOf(Q, at[sv]);
                    if (end >= 0) {
                        int j = sv;
                        while (j < v.length() && at[j] < end) j++;
                        String rest = v.substring(j).trim();
                        // (A mark that only sets off a word inside the speech is not its end.)
                        if (j - sv >= 3 || rest.isEmpty()) {
                            speech = v.substring(sv, j).trim();
                            if (rest.chars().anyMatch(Character::isLetter)) tail = rest;
                            closed = true;
                        }
                    }
                    quoteOpen = !closed;
                } else {
                    quoteOpen = false;
                    // After a short saying, a new sentence that tells what happened ("Peace, be still. | And the wind ceased…"),
                    // or that brings in what somebody else says, is the narrator's.
                    Matcher dd = TOLD.matcher(speech);
                    while (!closed && dd.find()) {
                        if ((narr.isEmpty() ? said : 0) + dd.start() > SHORT) break;
                        String rest = speech.substring(dd.end());
                        Matcher ni = INTRO.matcher(rest);
                        boolean brings = ni.find() && ni.start() < 60 && !FIRST_PERSON.matcher(ni.group()).find();
                        boolean tells = !TALK.matcher(rest).find() && !WILL.matcher(rest).find();
                        if (!brings && !tells) continue;
                        tail = rest.trim();
                        speech = speech.substring(0, dd.start() + 1);
                        closed = true;
                    }
                    Matcher rs = RESULT.matcher(speech);
                    while (!closed && rs.find()) {
                        String rest = speech.substring(rs.end());
                        if (rs.start() < 2 || TALK.matcher(rest).find()) continue;
                        tail = rest.trim();
                        speech = speech.substring(0, rs.start() + 1);
                        closed = true;
                        break;
                    }
                }
            }
            if (speech.chars().noneMatch(Character::isLetter)) {
                inSpeech = false;
                quoteOpen = false;
                out.append(out.length() > 0 ? " " : "").append(v);
                continue;
            }
            if (!ctxV) any = true;
            inSpeech = !closed;
            speaker = who;
            said = (narr.isEmpty() ? said : 0) + speech.length();
            if (PARABLE.matcher(speech).find() || (!narr.isEmpty() && PARABLE_WORD.matcher(narr).find())) {
                said += SHORT; // a story he tells: the people in it are his to tell of
                parable = true;
            }
            // How it is said, from the words that bring it in: "cried with a loud voice" is shouted, "wept" is sad, "whispered" soft.
            // (In the Bible "cried" means a loud cry, so it is read as shouted, not as sobbing.)
            String feel = Mood.feeling(narr.replaceAll("(?i)\\b(?:cried|crying|cry)\\b(?!\\s+out)", "shouted"), who != null && who.startsWith("cd") ? "" : speech); // (what Jesus teaches is not made angry by its words)
            String whoF = feel.isEmpty() || who == null ? who : who + "~" + feel;
            if (!narr.isEmpty()) out.append(out.length() > 0 ? " " : "").append(narr);
            out.append(out.length() > 0 ? " " : "").append(open);
            if (tagged) out.append('\u0004').append(whoF).append('\u0004');
            out.append(speech.trim()).append(close);
            codes.add(whoF);
            // What follows is read as a verse of its own: it may bring in somebody else's words.
            if (tail != null) {
                verses.add(vi + 1, tail);
                marked.add(vi + 1, tail);
                if (ctxV) context++;
            }
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

    /** The words just before "said", within its own sentence: who speaks is named there, not in the sentence before. */
    private static String lead(String v, int start, int width) {
        String b = v.substring(Math.max(0, start - width), start);
        Matcher e = Pattern.compile("[.!?]\\s").matcher(b);
        int cut = 0;
        while (e.find()) cut = e.end();
        return b.substring(cut);
    }

    private static final Pattern TO_WHOM = Pattern.compile("(?i)(?<![\\p{L}])(?:unto|to|kay|sa)\\s+(?:the\\s+|his\\s+|her\\s+|their\\s+|a\\s+|ang\\s+|si\\s+)?(?:[\\p{L}'\u2019]+\\s+)?([\\p{L}'\u2019]+)(?=\\s*[,:])");
    private static final Pattern PERSON = Pattern.compile("woman|man|father|mother|son|daughter|servant|king|queen|centurion|lawyer|ruler|angel|babae|lalake|lalaki|ama|ina|anak|alipin|hari|reyna|senturion|anghel");
    private static final Pattern PRONOUN = Pattern.compile("him|her|them|it|me|us|thee|you|kaniya|kanila|kanya|inyo|akin|amin|atin");
    /** The story takes up again after a parable or a sermon: "And the disciples came, and said unto him,", "And it came to pass, when Jesus had ended these sayings,". */
    private static final Pattern RESTART = Pattern.compile("(?i)^(?:and|then|now|but|so|at|nang|datapuwa't|pagkatapos|when)\\b[^.!?]{0,40}?(?<![\\p{L}])(?:jesus|hesus|(?:the|his)\\s+disciples|ang\\s+(?:kaniyang\\s+)?mga\\s+alagad|pharisees|fariseo|multitude|karamihan|it\\s+came\\s+to\\s+pass|nangyari)(?![\\p{L}])"
            // In Tagalog the story's own sentences begin with the word of saying: "Sinabi nga ng mga alagad sa kaniya,", "Sinalita nga ni Jesus…".
            + "|^(?:sinabi|sumagot|nagsabi|sinalita|nagtanong|tinanong|sinagot|sumigaw|nagsalita)(?![\\p{L}])[^,:.!?]{0,40}");
    private static final Pattern PARABLE_WORD = Pattern.compile("(?i)(?<![\\p{L}])(?:parable|parables|talinghaga)(?![\\p{L}])");
    private static final Pattern SI_NAME = Pattern.compile("^(?:At\\s+|Nguni't\\s+|Datapuwa't\\s+|Kaya't\\s+)?[Ss]i\\s+(\\p{Lu}\\p{Ll}[\\p{L}]+)(?![\\p{L}])(?!\\s+ay\\s+(?:sinabihan|tinanong))");
    private static final Pattern NOUN_SAID = Pattern.compile("(?i)(?<![\\p{L}])(?:the\\s+(woman|man|father|mother|son|servant|king|queen|centurion|young\\s+man|blind\\s+man|lawyer|ruler|angel|people|multitude|disciples|pharisees|scribes)\\s+(?:said|saith|answered|asked|replied|cried)"
            + "|(?:sinabi|sumagot|nagtanong|wika|sabi)\\s+(?:nga\\s+|naman\\s+|sa\\s+kaniya\\s+|sa\\s+kanila\\s+)*ng\\s+(babae|lalake|lalaki|ama|ina|anak|alipin|hari|reyna|senturion|anghel|bayan|karamihan|mga\\s+alagad|mga\\s+fariseo|mga\\s+eskriba))(?![\\p{L}])");

    /** "said unto the woman,", "said to his father,": the one spoken to, whoever it is, is not the one who speaks. */
    private static final Pattern TO_SOMEONE = Pattern.compile("(?i)(?<![\\p{L}])(?:unto|to)\\s+(?:the\\s+|his\\s+|her\\s+|their\\s+|a\\s+|one\\s+of\\s+the\\s+)?[\\p{L}'\u2019]+(?:\\s+[\\p{L}'\u2019]+)?(?=\\s*[,:])");

    /** A verse that tells what happened: it begins like a story and nobody in it says "I" or "you". */
    private static boolean narrates(String v) {
        if (!NARRATION.matcher(v).matches() || LET.matcher(v).matches()) return false; // "And let them be for lights…" is still being said
        String head = v.length() > 140 ? v.substring(0, 140) : v;
        return !TALK.matcher(head).find();
    }

    /** Who has spoken so far in the passage, and the voice code of each. */
    private static final class State {
        final List<String> keys = new ArrayList<>(); // speakers in order of first speech
        final List<String> codes = new ArrayList<>();
        final List<String> history = new ArrayList<>(); // one entry per turn
        int men = 0, women = 0, anon;
        String spokenTo; // who the last words were said to, when that is told
        String mentioned; // "jesus" or "father" when the verses have spoken of him lately

        String who(String nearIntro, String before) {
            String key = null;
            // Who is spoken to ("said unto Jesus,", "sa Panginoon") is not who speaks, and neither is the one God's man belongs to.
            String toWhom = null;
            nearIntro = nearIntro.replaceAll("\\s+", " ");
            Matcher ad = ADDRESSED.matcher(nearIntro);
            boolean ownFather = false; // "said to his father,": a man's own father, not the Father
            if (ad.find()) {
                String g = ad.group();
                ownFather = g.matches("(?s).*(?<![\\p{L}])(?:father|ama)$");
                toWhom = ownFather ? null : JESUS.matcher(g).find() ? "jesus" : SPIRIT.matcher(g).find() ? "spirit" : "father";
                nearIntro = ad.replaceAll(" ");
            }
            // The one spoken to is likely the one who answers next, when only "he said" brings the answer in.
            String to = ownFather ? "a father" : toWhom;
            Matcher ts = TO_WHOM.matcher(nearIntro);
            while (ts.find() && to == null) {
                String w = ts.group(1), lw = w.toLowerCase(Locale.ROOT);
                if (lw.equals("father") || lw.equals("ama")) lw = "a father";
                if (VILLAIN.matcher(w).find()) to = "villain";
                else if (keys.contains(lw) || TextPrep.MEN.contains(lw) || TextPrep.WOMEN.contains(lw) || PERSON.matcher(lw).matches() || lw.equals("a father")
                        || (Character.isUpperCase(w.charAt(0)) && !NOT_NAMES.contains(lw) && !PRONOUN.matcher(lw).matches())) to = lw;
            }
            nearIntro = TO_SOMEONE.matcher(nearIntro).replaceAll(" ");
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
                    String w = (c.group(1) != null ? c.group(1) : c.group(2) != null ? c.group(2) : c.group(3) != null ? c.group(3) : c.group(4)).toLowerCase(Locale.ROOT);
                    if (!NOT_NAMES.contains(w)) key = w;
                }
                // "the woman said", "sinabi ng babae": somebody known by what they are.
                Matcher ns = key == null ? NOUN_SAID.matcher(nearIntro) : null;
                if (ns != null && ns.find()) {
                    key = (ns.group(1) != null ? ns.group(1) : ns.group(2)).toLowerCase(Locale.ROOT);
                    if (key.equals("father") || key.equals("ama")) key = "a father"; // (not the Father)
                    if (FEMALE_CUE.matcher(key).find()) femaleKeys.add(key);
                }
                // "Si Tomas nga, na tinatawag na Didimo, ay nagsabi…": the verse begins with who it is.
                Matcher sb = key == null ? SI_NAME.matcher(before) : null;
                if (sb != null && sb.find() && !NOT_NAMES.contains(sb.group(1).toLowerCase(Locale.ROOT))) key = sb.group(1).toLowerCase(Locale.ROOT);
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
                if (spokenTo != null && !spokenTo.equals(lastKey)) key = spokenTo; // the one who was just spoken to answers
                if (key == null && "villain".equals(lastKey)) key = "jesus";
                // "Now the serpent was more subtil… And he said unto the woman,": the first to speak is the one the verse is about.
                if (key == null && history.isEmpty() && VILLAIN.matcher(before).find() && !JESUS.matcher(before).find()) key = "villain";
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
            spokenTo = to != null && !to.equals(key) ? to : null;
            history.add(key);
            int at = keys.indexOf(key);
            if (at < 0) {
                keys.add(key);
                String code;
                if (key.equals("father")) code = Cast.DIVINE_CODE;
                else if (key.equals("jesus")) code = Cast.JESUS_CODE;
                else if (key.equals("spirit")) code = Cast.SPIRIT_CODE;
                else if (key.equals("villain")) code = "cm" + (men++) + "^villain";
                else if (femaleKeys.contains(key) || TextPrep.WOMEN.contains(key) || FEMALE_CUE.matcher(key).find()) code = "cf" + (women++);
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
