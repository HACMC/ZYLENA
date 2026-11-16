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
            + "said|saith|spake|spoke|answered|answering|asked|asking|cried|crying|replied|commanded|shouted|told|saying|to\\s+say|(?<=they\\s|they\\sall\\s)say|magsabi)";
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
    private static final Pattern PARABLE = Pattern.compile("(?i)^\\W*(?:behold,\\s+)?(?:a\\s+certain|what\\s+man|what\\s+woman|sino\\s+sa\\s+inyo|aling\\s+tao|there\\s+was\\s+a|a\\s+sower|the\\s+kingdom\\s+of|may\\s+isang|isang|ang\\s+kaharian|narito,\\s+ang\\s+manghahasik|so\\s+is\\s+the\\s+kingdom|it\\s+is\\s+like\\s+a|ganyan\\s+ang\\s+kaharian|ito'y\\s+tulad\\s+sa)(?![\\p{L}])");
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
        // "The woman saith unto him,": somebody else answers, even in the middle of Jesus's long words.
        if (NOUN_SAID.matcher(part).find()) return true;
        return VILLAIN.matcher(part).find() || SPIRIT.matcher(OF_GOD.matcher(part).replaceAll(" ")).find();
    }

    /** A verse that tells what happened, not what is said. */
    private static final Pattern NARRATION = Pattern.compile("(?i)^(?:at|and|nang|when|then|now|so|datapuwa't|datapuwat|but|pagkatapos|noon|after|ngunit|while\\s+(?:he|they|she)|there\\s+(?:came|was|were|arose|went)|because\\s+they|they\\s+(?:came|said|were|had|did|went|brought|answered|saw|took|began|sent|laid)|sapagka't\\s+sinabi\\s+nila|nagsidating|dumating)\\b.*");
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
    private static final Pattern OF_GOD = Pattern.compile("(?i)(?<![\\p{L}])(?:(?:the\\s+)?lord['\u2019]s\\s+day|araw\\s+ng\\s+panginoon|(?:the\\s+)?(?:holy\\s+)?spirit\\s+of\\s+(?:the\\s+)?(?:god|lord)\\s+(?:came|fell|rested|was|entered|clothed|moved)\\s+(?:upon|on|in|him|them)(?:\\s+\\p{L}+)?|(?:ang\\s+)?espiritu\\s+ng\\s+(?:dios|diyos|panginoon)\\s+(?:ay\\s+)?(?:dumating|lumapit|sumakaniya|napasa|suma)\\s+\\p{L}+(?:\\s+\\p{L}+)?|(?:man|men|woman|servants?|prophets?|priests?|people|children|house|temple|ark|angels?|sons?|"
            + "lingkod|alipin|propeta|saserdote|bayan|anghel|lalake|lalaki|bahay|templo|kaban|anak|mga\\s+anak)\\s+(?:of|ng)\\s+(?:the\\s+)?(?:lord|god|dios|diyos|panginoon)"
            + "|(?:filled\\s+with|full\\s+of|in|by|through|puspos\\s+ng|napuspos\\s+ng|sa\\s+pamamagitan\\s+ng)\\s+(?:the\\s+)?(?:holy\\s+|banal\\s+na\\s+)?(?:spirit|ghost|espiritu)(?:\\s+santo)?)(?![\\p{L}])");
    /** An unclean spirit is not the Holy Spirit: it speaks as the tempter does. */
    private static final Pattern EVIL_SPIRIT = Pattern.compile("(?i)(?<![\\p{L}])(?:unclean|evil|foul|dumb|deaf|wicked|lying|familiar|masamang|masasamang|karumaldumal\\s+na|maruming|"
            + "karumaldumal)\\s+(?:spirits?|espiritu)(?![\\p{L}])");
    /**
     * Is the one named (the devil, an unclean spirit) the one who speaks? Only when the verb of speaking comes soon after the name and
     * nothing in between makes it somebody something is done to ("when the devil was cast out, the dumb spake").
     */
    private static boolean subjectOf(Pattern who, String s) {
        Matcher w = who.matcher(s);
        Matcher said = INTRO.matcher(s);
        while (w.find()) {
            // "said Satan", "answered the devil", "ni Satanas"
            String before = s.substring(Math.max(0, w.start() - 22), w.start());
            if (before.matches("(?is).*(?:" + VERBS + ")\\W+(?:the\\s+|ang\\s+|ng\\s+|ni\\s+|si\\s+)?$") || before.matches("(?is).*(?<![\\p{L}])(?:ni|si)\\s+$")) return true;
            if (!said.find(w.end())) return true; // no verb in what was given: as before
            String gap = s.substring(w.end(), said.start());
            if (gap.trim().split("\\s+").length <= 16 && !gap.matches("(?s).*[.!?]\\s.*") && !GAP_DOES.matcher(gap).find()) return true;
        }
        return false;
    }

    private static final Pattern GAP_DOES = Pattern.compile("(?i)(?<![\\p{L}])(?:was|were|been|cast|casteth|casting|possessed|with|through|from|out|nang|ay\\s+pinalabas|pinalabas)(?![\\p{L}])");

    private static final Pattern SPIRIT_SPOKEN_TO = Pattern.compile("(?i)(?<![\\p{L}])(?:rebuk\\w*|charg\\w*|command\\w*|adjur\\w*|pinagwika\\w*|pinagutus\\w*|inutusan|sinaway|sinasaway|saway|sa|kay|unto|to)(?:\\s+(?:niya|nila|ni\\s+\\p{Lu}\\p{L}+))?\\s+(?:the\\s+|ang\\s+|that\\s+|those\\s+)?(?:" + EVIL_SPIRIT.pattern().replace("(?i)", "").replace("(?<![\\p{L}])", "").replace("(?![\\p{L}])", "") + ")(?![\\p{L}])");
    /** The one who tempts or opposes: Satan speaks in a voice of his own, not Jesus's. */
    private static final Pattern VILLAIN = Pattern.compile("(?i)(?<![\\p{L}])(?:satan|satanas|devils?|diablo|lucifer|demons?|demonyo|mga\\s+demonyo|manunukso|tempter|serpent|ahas)(?![\\p{L}])");
    private static final Pattern FEMALE_CUE = Pattern.compile("(?i)(?<![\\p{L}])(?:babae|woman|women|reyna|queen|dalaga|maiden|damsel|ina|mother|she|siya'y babae|virgin|birhen)(?![\\p{L}])");
    private static final Pattern NAMED = Pattern.compile("(?:(?<![\\p{L}])(?:ni|si|ng|kay)\\s+(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+))"
            + "|(?:(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+)\\s+(?:said|saith|answered|spake|asked|replied|cried))"
            + "|(?:(?:said|saith|answered|spake|asked|replied|cried)\\s+(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+))"
            // "which was spoken by Esaias the prophet, saying,", "ng propeta Isaias": the prophet's words, in a voice of his own.
            + "|(?:(?<![\\p{L}])(?:by|through|prophet|propeta|propetang)\\s+(\\p{Lu}\\p{Ll}[\\p{L}'\u2019]+))");
    private static final Pattern CAPITAL = Pattern.compile("(?<![\\p{L}'’])\\p{Lu}\\p{Ll}[\\p{L}'’]+(?![\\p{L}'’\u2013-])"); // (not "Beth" of "Beth–lehem")
    private static final java.util.Set<String> NOT_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            ("at and nang when then now so datapuwa't datapuwat but ngunit pagkatapos noon after sumagot answered said saith sinabi sinasabi ang ng si ni kay "
                    + "mga ito iyon siya sila he she they the a an in on of to for by with from lahat every isang one").split(" ")));

    /**
     * The text with quotation marks around what people say and the voice code of each of those quotations, or null
     * when nobody speaks in it. The verse numbers are taken out (the verses stay in order, one after the other).
     */
    static Result annotate(String line) {
        String t = Bible.normalize(line);
        // A page that begins at verse 1 is the beginning of a chapter: nothing of what was read before belongs to it.
        if (t.matches("(?s)^\\s*1(?![\\d:.,])\\s*[\\p{L}\\u201C\"'(\\[].*")) forget();
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
        return core(verseList, open, close, tagged, contextVerses, true, null);
    }

    /** What nobody speaks in: a verse the plan knows and finds no speech in. */
    static Result nobody() {
        Result r = new Result();
        r.text = "";
        r.codes = new String[0];
        return r;
    }

    /**
     * A whole chapter at once (with a few verses of the chapter before as `context`): the result of each verse by its place in the list
     * (null for the context verses and for empty ones); a verse nobody speaks in has no codes. Who is speaking is worked out with
     * everything the chapter says, not one verse alone.
     */
    static Result[] annotateChapter(List<String> verseList, int contextVerses) {
        int[][] marks = new int[2][];
        Result all = core(verseList, '\u201C', '\u201D', false, contextVerses, false, marks);
        Result[] per = new Result[verseList.size()];
        for (int i = contextVerses; i < per.length; i++) per[i] = nobody();
        if (all == null || marks[0] == null) return per;
        int n = per.length;
        for (int i = contextVerses; i < n; i++) {
            if (marks[0][i] < 0) {
                per[i] = null;
                continue;
            }
            int j = i + 1;
            while (j < n && marks[0][j] < 0) j++;
            int endT = j < n ? marks[0][j] : all.text.length(), endC = j < n ? marks[1][j] : all.codes.length;
            Result r = new Result();
            r.text = all.text.substring(Math.min(marks[0][i], endT), endT).trim();
            r.codes = java.util.Arrays.copyOfRange(all.codes, Math.min(marks[1][i], endC), endC);
            r.summary = all.summary;
            per[i] = r;
        }
        return per;
    }

    private static Result core(List<String> verseList, char open, char close, boolean tagged, int contextVerses, boolean useMemory, int[][] marksOut) {
        // The page's own quotation marks are the surest sign of where somebody's words end: their places are kept (marked).
        List<String> verses = new ArrayList<>(), marked = new ArrayList<>();
        for (String v : verseList) {
            String mk = v.replace("''", "\"").replaceAll("[\u201C\u201D\"\u00AB\u00BB]", String.valueOf(Q)).trim();
            marked.add(mk);
            verses.add(mk.replace(String.valueOf(Q), "").trim());
        }
        final int total = verses.size();
        final List<Integer> origin = new ArrayList<>(); // each verse's place in the list given (a tail made a verse of its own keeps its first verse's)
        for (int i = 0; i < total; i++) origin.add(i);
        final int[] oStart = new int[total], cStart = new int[total];
        java.util.Arrays.fill(oStart, -1);
        java.util.Arrays.fill(cStart, -1);
        String carry = "";
        boolean parable = false; // Jesus is telling a story: what happens in it is his to tell, verse after verse
        boolean explainMode = false; // Jesus is saying what his parable means
        boolean evilSeen = false; // an unclean spirit has been spoken of: "the spirit" after it is that one
        boolean quoteOpen = false; // the page opened a quotation and has not closed it yet
        int context = contextVerses;
        int said = 0; // how much the one speaking has said so far
        State st = new State();
        st.passage = String.join(" ", verses);
        // A page is often one verse of a chapter: who the last page was about carries on to the next, for a little while.
        synchronized (BibleCast.class) {
            if (useMemory && memory != null && contextVerses == 0 && System.currentTimeMillis() - memoryAt < 180000L) st.mentioned = memory;
        }
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
            {
                final int o = origin.get(vi);
                if (vi >= context && oStart[o] < 0) {
                    oStart[o] = outAll.length();
                    cStart[o] = codesAll.size();
                }
            }
            // (A verse of context is read for who speaks, but what it comes to is thrown away.)
            final boolean ctxV = vi < context;
            final StringBuilder out = ctxV ? new StringBuilder() : outAll;
            final List<String> codes = ctxV ? new ArrayList<>() : codesAll;
            // Who is spoken of lately: an unnamed "he" later in the passage is most likely this one.
            String mk = JESUS.matcher(v).find() || SON_OF_MAN.matcher(v).find() ? "jesus" : FATHER.matcher(v).find() ? "father" : null;
            if (mk != null) st.mentioned = mk;
            if (EVIL_SPIRIT.matcher(v).find()) st.evilSeen = true;
            if (JESUS.matcher(v).find() || GOSPEL.matcher(v).find()) st.gospelSeen = true;
            st.hintStrong = JESUS_ONLY.matcher(v).find();
            st.hintJesus = PARABLE_TOLD.matcher(v).find() || JESUS_SAYS.matcher(v).find() || TELLS_PARABLE.matcher(v).find();
            final boolean firstVerse = vi == context || (context == 0 && !seenVerse);
            seenVerse = true;
            String narr = v, speech = null, headSpeech = null;
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
            st.jesusTeaching = inSpeech && Cast.JESUS_CODE.equals(speaker) && (parable || discourse || explainMode);
            final Matcher rsm = RESTART.matcher(v);
            final boolean restart = (rsm.find() && !TALK.matcher(v.substring(0, rsm.end())).find()) || STORY_AGAIN.matcher(v).find();
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
                        String feel = bibleFeel(Mood.feeling((near == null ? "" : near).replaceAll("(?i)\\b(?:cried|crying|cry)\\b(?!\\s+out)", "shouted"), w.startsWith("cd") ? "" : piece), piece, w.startsWith("cd"), w.contains("^villain"));
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
            // "Lord, thou knowest all things, said Peter.": the words come first and the one who says them is named at the end.
            boolean tailDone = false;
            if (!inSpeech && !INTRO.matcher(v).find()) {
                Matcher tm = TAIL_TAG.matcher(v);
                if (tm.find()) {
                    String spoken = v.substring(0, tm.start()).trim();
                    if (spoken.length() >= 12 && spoken.matches("(?s).*[,?!]$") && TALK.matcher(spoken).find()) {
                        headSpeech = spoken.replaceAll("[,\\s]+$", "");
                        narr = v.substring(tm.start()).trim();
                        speech = "";
                        who = st.who(narr, spoken);
                        tailDone = true;
                    }
                }
            }
            Matcher m = INTRO.matcher(v);
            while (!tailDone && m.find()) {
                // (Deep in a long speech a "said" is the speaker's own telling; but "Then she fell on her face, … and said unto him," begins the story again.)
                if (discourse && m.start() > 50 && (divineSpeech || !STORY_START.matcher(v).find())) break;
                // The verse before ended with "…and he cried out," and this one begins "Saying, …": the one named there goes on.
                if (inSpeech && m.start() <= 2 && SAYING.matcher(v.substring(m.start())).find()) continue;
                if ((divineSpeech || parable) && discourse && m.start() <= 50 && !restart && !namesSomeone(v.substring(0, m.end())) && !ADDRESS_LORD.matcher(v.substring(m.end())).find()) continue;
                String intro = m.group();
                if (FIRST_PERSON.matcher(intro).find()) continue; // "sinabi ko sa inyo": the speaker's own words
                String rest = v.substring(m.end()).trim();
                if (m.group().matches("(?is).*(?<![\\p{L}])(?:cried|crying|cry)(?![\\p{L}]).*") && DID.matcher(rest).find() && !FIRST_PERSON.matcher(rest.length() > 40 ? rest.substring(0, 40) : rest).find()) continue; // (what was done, not said)
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
                // Words said begin with a capital ("said, Here am I"). "And Samuel told him every whit, and hid nothing", "a man that hath
                // told you the truth, which I have heard of God", "sinabi niya na…": what follows is the story, or the same one speaking on.
                // ("I am Alpha and Omega, saith the Lord, which is…": the words before the tag go on after it, and are the Lord's.)
                if (LOWER_START.matcher(rest).find() && !(SAITH_TAG.matcher(intro).find() && v.substring(0, m.start()).trim().endsWith(","))) continue;
                String pre = v.substring(0, m.start());
                // "I am Alpha and Omega, | saith the Lord, | which is, and which was…": the words before the tag are the Lord's as well.
                if (SAITH_TAG.matcher(intro).find() && pre.trim().endsWith(",") && pre.trim().length() >= 12 && HEAD_FIRST.matcher(pre).find()) {
                    headSpeech = pre.trim().replaceAll("[,\\s]+$", "");
                    narr = intro;
                } else {
                    narr = pre + intro;
                }
                speech = rest;
                st.speechNow = rest;
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
            // ("And he said, So is the kingdom of God…" after his own long teaching: he goes on.)
            final boolean goesOnTeaching = divineSpeech && Cast.JESUS_CODE.equals(speaker) && discourse && v.length() > 20 && HE_SAID.matcher(v).find();
            // ("And these are they by the way side… And have no root in themselves…": Jesus tells what the story means, verse after verse.)
            if (!inSpeech || INTRO.matcher(v).find() || restart || JESUS.matcher(v).find()) explainMode = false;
            if (inSpeech && Cast.JESUS_CODE.equals(speaker) && EXPLAINS.matcher(v).find()) explainMode = true;
            final boolean explains = inSpeech && Cast.JESUS_CODE.equals(speaker) && explainMode;
            final boolean ends = (tellsOf && !explains) || restart || (!parable && !goesOnTeaching && !explains && narrates(v));
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
                    // "Abraham: and he said, Behold, here I am.", "My father: and he said, Here am I": a name is called and the one
                    // called answers within the same verse. The answer is read as a verse of its own, in the answerer's voice.
                    Matcher ir = INLINE_REPLY.matcher(speech);
                    if (ir.find() && (st.nameLike(vocName(speech.substring(0, ir.start()))) || CALL_WORD.matcher(speech.substring(0, ir.start()).trim()).matches())) {
                        tail = speech.substring(ir.start() + 1).trim();
                        speech = speech.substring(0, ir.start() + 1);
                        closed = true;
                    }
                    // "…he hath done to thy saints at Jerusalem. Jesus said unto her, Thou hast well said": somebody else answers mid-verse.
                    Matcher na = NAMED_ANSWER.matcher(speech);
                    if (!closed && na.find() && na.start() > 2) {
                        tail = speech.substring(na.start() + 1).trim();
                        speech = speech.substring(0, na.start() + 1);
                        closed = true;
                    }
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
            if (headSpeech == null && speech.chars().noneMatch(Character::isLetter)) {
                inSpeech = false;
                quoteOpen = false;
                out.append(out.length() > 0 ? " " : "").append(v);
                continue;
            }
            if (!ctxV) any = true;
            {
                String vn = vocName(speech);
                String lastK = st.history.isEmpty() ? null : st.history.get(st.history.size() - 1);
                if (vn != null && !vn.equals(lastK) && st.nameLike(vn)) st.spokenTo = vn; // "Samuel, Samuel.": Samuel answers next
            }
            inSpeech = !closed && headSpeech == null; // ("I am Alpha and Omega, saith the Lord, … the Almighty." is all of it)
            speaker = who;
            said = (narr.isEmpty() ? said : 0) + speech.length();
            if (PARABLE.matcher(HE_SAID_LEAD.matcher(speech).replaceFirst("")).find() || (!narr.isEmpty() && PARABLE_WORD.matcher(narr).find())) {
                said += SHORT; // a story he tells: the people in it are his to tell of
                parable = true;
            }
            // How it is said, from the words that bring it in: "cried with a loud voice" is shouted, "wept" is sad, "whispered" soft.
            // (In the Bible "cried" means a loud cry, so it is read as shouted, not as sobbing.)
            final String feelText = speech.isEmpty() && headSpeech != null ? headSpeech : speech;
            String feel = bibleFeel(Mood.feeling(narr.replaceAll("(?i)\\b(?:cried|crying|cry)\\b(?!\\s+out)", "shouted"), who != null && who.startsWith("cd") ? "" : feelText), feelText, who != null && who.startsWith("cd"), who != null && who.contains("^villain")); // (what Jesus teaches is not made angry by its words)
            String whoF = feel.isEmpty() || who == null ? who : who + "~" + feel;
            if (headSpeech != null) {
                out.append(out.length() > 0 ? " " : "").append(open);
                if (tagged) out.append('\u0004').append(whoF).append('\u0004');
                out.append(headSpeech).append(close);
                codes.add(whoF);
            }
            if (!narr.isEmpty()) out.append(out.length() > 0 ? " " : "").append(narr);
            if (!speech.trim().isEmpty()) {
                out.append(out.length() > 0 ? " " : "").append(open);
                if (tagged) out.append('\u0004').append(whoF).append('\u0004');
                out.append(speech.trim()).append(close);
                codes.add(whoF);
            }
            // What follows is read as a verse of its own: it may bring in somebody else's words.
            if (tail != null) {
                verses.add(vi + 1, tail);
                marked.add(vi + 1, tail);
                origin.add(vi + 1, origin.get(vi));
                if (ctxV) context++;
            }
        }
        {
            String last = st.history.isEmpty() ? null : st.history.get(st.history.size() - 1);
            String keep = "jesus".equals(last) || "father".equals(last) ? last : st.mentioned;
            if (useMemory) synchronized (BibleCast.class) {
                if (keep != null) {
                    memory = keep;
                    memoryAt = System.currentTimeMillis();
                }
            }
        }
        if (marksOut != null) {
            marksOut[0] = oStart;
            marksOut[1] = cStart;
        }
        if (!any) return null;
        Result r = new Result();
        r.text = outAll.toString();
        r.codes = codesAll.toArray(new String[0]);
        r.summary = st.summary();
        return r;
    }

    private static String memory; // "jesus" or "father": who the verses read lately were about
    private static long memoryAt;

    /** Forgets who the last verses were about (a new book is opened, or a test begins). */
    static synchronized void forget() {
        memory = null;
    }

    /** How the people of the Bible speak when the verse says nothing of it: "Woe unto you", "Crucify him", "Hosanna", "Fear not". */
    private static final Pattern ROLE_SPEAKER = Pattern.compile("(?i)(?<![\\p{L}])(?:(?:the\\s+|a\\s+)?(little\\s+child|child|little\\s+(?:boy|girl)|boy)|(?:the\\s+)?(old\\s+(?:man|woman|prophet)|aged\\s+(?:man|woman))|(?:the\\s+)?(lad|youth|young\\s+man))\\s+(?:answered|said|saith|spake|cried|spoke|replied)"
            + "|(?:sinabi|sumagot|nagsabi)\\s+(?:ng|ni)\\s+(?:ang\\s+)?(?:(bata|batang\\s+lalake|batang\\s+babae)|(matanda|matandang\\s+(?:lalake|babae))|(binata|kabataan))(?![\\p{L}])");
    private static final Pattern BIBLE_FIRM = Pattern.compile("(?i)^\\W*(?:woe\\b|crucify|away\\s+with|sino\\s+ang\\s+nakakaalam|sa\\s+aba|ipako|alisin\\s+mo)");
    private static final Pattern BIBLE_GLAD = Pattern.compile("(?i)^\\W*(?:hosanna|hallelujah|alleluia|rejoice\\b|osana|aleluya|magalak|magalak\\b)");

    private static String bibleFeel(String feel, String speech, boolean divine) {
        return bibleFeel(feel, speech, divine, false);
    }

    /** The tempter speaks smoothly and slyly ("m") unless the verse says he cried out or was angry. */
    private static String bibleFeel(String feel, String speech, boolean divine, boolean villain) {
        if (villain && feel.isEmpty()) return "m";
        if (!feel.isEmpty() || divine) return feel;
        if (BIBLE_FIRM.matcher(speech).find()) return "s";
        if (BIBLE_GLAD.matcher(speech).find()) return "j";
        return feel;
    }

    /** "…: and he said, Here am I", "; at sinabi niya, Narito ako": the answer within the same verse. */
    private static final Pattern INLINE_REPLY = Pattern.compile("(?i)[:;,]\\s+(?=(?:and\\s+(?:he|she)\\s+(?:said|answered|saith)|at\\s+(?:kaniyang\\s+sinabi|sinabi\\s+niya|siya'y\\s+sumagot))(?![\\p{L}]))");
    /** A call with no name in it: "My father", "My son", "Ama ko", "Anak ko". */
    private static final Pattern CALL_WORD = Pattern.compile("(?i)^(?:o\\s+)?(?:my\\s+(?:father|son|mother|daughter|lord)|(?:ama|anak|ina|panginoon)\\s+ko)[\\s.!,:;]*$");
    /** "…thy saints. Jesus said unto her, …": a named one answers in the middle of a verse. */
    private static final Pattern NAMED_ANSWER = Pattern.compile("[.!?;:]\\s+(?=(?:Then\\s+|And\\s+)?(?:Jesus|\\p{Lu}\\p{Ll}+)\\s+(?:said|saith|answered)\\s+(?:unto|to)\\s|They\\s+(?:all\\s+)?(?:say|said|saith|answered)\\s+(?:unto|to)\\s)");
    /** The words after "told him every whit," go on with the story, not with somebody's words. */
    private static final Pattern STORY_START = Pattern.compile("^(?:Then|And|So)\\s+(?:she|he|they|\\p{Lu}\\p{Ll}+)\\s+(?:\\p{Ll}+(?:ed|ell|ent|ame|ose|ook|ew|ent|aw)|fell|went|came|rose|took|bowed|wept|ran)(?![\\p{L}])");
    private static final Pattern LOWER_START = Pattern.compile("^[^\\p{L}]*\\p{Ll}");
    private static final java.util.Set<String> NOT_CALLED = new java.util.HashSet<>(java.util.Arrays.asList(
            "lord", "master", "sir", "rabbi", "rabboni", "father", "mother", "son", "god", "panginoon", "guro", "ginoo", "dios", "diyos", "behold", "lo", "woe", "amen", "hosanna", "yea", "nay", "oo", "hindi"));

    /** "Samuel, Samuel.", "Abraham", "Ananias.": only a name, called; the name (lower case), else null. */
    static String vocName(String s) {
        Matcher m = Pattern.compile("^\\s*(?:O\\s+|Oh\\s+)?(\\p{Lu}\\p{Ll}+)(?:\\s*,\\s*(?:\\1|my\\s+son|anak\\s+ko))?[\\s.!,:;]*$").matcher(s);
        if (!m.find()) return null;
        String w = m.group(1).toLowerCase(Locale.ROOT);
        return NOT_CALLED.contains(w) || NOT_NAMES.contains(w) ? null : w;
    }

    private static final Pattern SAYING = Pattern.compile("(?i)^(?:(?:and\\s+)?saying|na nagsasabi|nagsasabi|na sinasabi|sinasabi)\\s*[,:]\\s*");
    /** Words only Jesus says, as the Gospels have him: "I say unto you", "you have heard that it was said". */
    private static final Pattern JESUS_SAYS = Pattern.compile("(?i)(?:katotohanang\\s+sinasabi\\s+ko\\s+sa\\s+inyo|sinasabi\\s+ko\\s+sa\\s+inyo|narinig\\s+ninyo\\s+na\\s+sinabi|"
            + "i\\s+say\\s+unto\\s+you|verily,?\\s+(?:verily,?\\s+)?i\\s+say|ye\\s+have\\s+heard\\s+that\\s+it\\s+was\\s+said|but\\s+i\\s+say|"
            + "i\\s+am\\s+the\\s+(?:way|light|bread|door|good\\s+shepherd|vine|resurrection)|ako\\s+ang\\s+(?:daan|ilaw|tinapay|pintuan|puno\\s+ng\\s+ubas|pagkabuhay))");
    private static final Pattern AGAIN = Pattern.compile("(?i)^(?:at|and)\\s+(?:sinabi|nagsabi|said|saith|sumagot|answered)\\b[^,:]{0,40}[,:]\\s*");

    /** The words just before "said", within its own sentence: who speaks is named there, not in the sentence before. */
    private static String lead(String v, int start, int width) {
        String b = v.substring(Math.max(0, start - width), start);
        Matcher e = Pattern.compile("[.!?]\\s|[:;]\\s+(?=and\\s+(?:he|she)\\s)").matcher(b);
        int cut = 0;
        while (e.find()) cut = e.end();
        return b.substring(cut);
    }

    private static final Pattern THEY = Pattern.compile("(?i)(?<![\\p{L}])they\\s+(?:\\p{L}+\\s+){0,3}?(?:said|say|saith|answered|cried|asked|spake)(?![\\p{L}])|(?:said|asked|answered)\\s+they(?![\\p{L}])");
    private static final Pattern SHE = Pattern.compile("(?i)(?<![\\p{L}])she\\s+(?:\\p{L}+\\s+){0,3}?(?:said|saith|answered|cried|asked|spake)(?![\\p{L}])");
    private static final Pattern PRON_SUBJ = Pattern.compile("(?i)(?<![\\p{L}])(?:he|she|they|i|we|ye|you|it|one|siya|sila|niya|nila|kaniyang|kanilang)\\s+(?:\\p{L}+\\s+){0,3}?(?:said|saith|spake|answered|cried|asked|saying|sinabi|nagsabi|sumagot)(?![\\p{L}])"
            + "|(?:said|saith|answered)\\s+(?:he|she|they)(?![\\p{L}])|(?:sinabi|nagsabi|sumagot|sinasabi|nagsalita|sumigaw)\\s+(?:\\p{L}+\\s+)?(?:niya|nila|siya|sila)(?![\\p{L}])");
    private static final Pattern SENT_LEAD = Pattern.compile("(?i)^(?:(?:and|then|now|but|so|moreover|therefore),?\\s+)?(?:behold,?\\s+|lo,?\\s+)?(?:it\\s+came\\s+to\\s+pass,?\\s*(?:that\\s*)?)?(?:(?:when|as|after)\\s+|in\\s+the\\s+mean\\s*while,?\\s*)?");
    private static final Pattern OWNED_BY = Pattern.compile("(?i)(?<![\\p{L}])(?:anak|lingkod|alipin|asawa|ama|ina|kapatid|tagapangasiwa|bahay|lupain|salita|mga\\s+salita|bayan|hari)\\s*$");
    private static final Pattern AY_SPOKE = Pattern.compile("(?<![\\p{L}])(?:ang|si)\\s+(\\p{Lu}\\p{Ll}+)\\s+ay\\s+(?:nagsalita|nagsabi|sumagot|tumawag|sumigaw)(?![\\p{L}])");
    private static final Pattern SUBJ_DIVINE = Pattern.compile("(?i)^(?:the\\s+)?(?:lord(?:\\s+god)?|god|jesus)(?![\\p{L}])(?!\\s*['\u2019]s)|^(?:the\\s+)?\\p{L}+(?:\\s+\\p{L}+){0,8}?,\\s*(?:the\\s+)?(?:lord(?:\\s+god)?|god|jesus)\\s+(?!said)\\p{Ll}+ed(?![\\p{L}])");
    private static final Pattern SUBJ_GROUP = Pattern.compile("(?i)^(?:many(?:\\s+(?:more|of))?|they|all\\s+the\\s+(?:people|city|men)|the\\s+(?:people|multitude|men|crowd))(?![\\p{L}])");
    private static final Pattern SUBJ_NOUN = Pattern.compile("(?i)^(?:the|a|his|her|their|this|that|one\\s+of\\s+the)\\s+(?:\\p{L}+\\s+)?(woman|man|disciples|servant|king|queen|angel|centurion|nobleman|ruler|lawyer|maid|damsel|mother|priest|prophet|messenger|governor|captain|watchman)(\\s+of\\s+(?:the\\s+)?(?:lord|god))?(?![\\p{L}])");
    /** A name and then what the person did ("Simeon blessed them", "Zipporah took"): group 2 when a deed follows. */
    private static final Pattern VERB_NAME = Pattern.compile("^\\p{Ll}+(?:eth|ed)\\s+(\\p{Lu}\\p{Ll}+)(?![\\p{L}])");
    private static final Pattern SUBJ_NAME = Pattern.compile("^(\\p{Lu}\\p{Ll}+)(?![\\p{L}])(\\s+(?:also\\s+|then\\s+)?\\p{Ll}+(?:ed|ook|ame|ent|ose|aw|ell|ade|ave|old|aid|pake)(?![\\p{L}]))?");
    /** Words said to God or to Jesus: "Lord, …", "O my Lord", "Panginoon, …". */
    private static final Pattern ADDRESS_LORD = Pattern.compile("(?:^\\W*|,\\s*)(?:(?:O|Oh)\\s+)?(?:my\\s+)?(?:Lord|LORD|Panginoon|PANGINOON)(?=\\s*[,.?!:;])");
    private static final Pattern PRAYS_FATHER = Pattern.compile("^\\W*(?:O\\s+(?:my\\s+)?Father|Father|Abba|Ama\\s+ko|Ama)(?![\\p{L}])");
    private static final Pattern HIS_DISCIPLES = Pattern.compile("(?i)(?:unto|to|sa)\\s+(?:his|kaniyang)\\s+(?:mga\\s+)?(?:disciples|alagad)(?![\\p{L}])");
    /** "But when Paul perceived …, he cried out": the "he" is the one the "when" is about. */
    private static final Pattern WHEN_CLAUSE = Pattern.compile("(?i)^\\s*(?:(?:and|but|then|now|so)\\s+)?(?:when|as\\s+soon\\s+as|after)\\s+\\p{Lu}\\p{Ll}+\\s+\\p{Ll}+[^.!?]*,\\s*(?:he|she)\\s+[^,.!?]*$");
    private static final Pattern TO_PRONOUN = Pattern.compile("(?i)(?<![\\p{L}])(?:unto|to|sa)\\s+(?:him|her|them|kaniya|kanila)(?![\\p{L}])");
    private static final Pattern MOREOVER = Pattern.compile("(?i)(?<![\\p{L}])(?:moreover|furthermore|bukod\\s+dito)(?![\\p{L}])|(?:said|spake|sinabi)\\s+(?:moreover|again|also|pa)(?![\\p{L}])");
    /** "the Lord called Samuel: and he answered", "God met Balaam: and he said": the one met or called is the one who speaks. */
    private static final Pattern CALLED_THEN = Pattern.compile("(?<![\\p{L}])(?<!(?:to|unto|in|at|from|of|into|upon|by|with|sa|kay)\\s)(?:\\p{Ll}+\\s+)(?:unto\\s+|si\\s+)?(\\p{Lu}\\p{Ll}+)\\s*[:;]\\s*(?:and|at)\\s+(?:he|she|siya)\\s*$");
    private static final Pattern KIN_TO = Pattern.compile("(?i)(?:his|her|their|kaniyang|kanilang)\\s+(?:son|sons|father|mother|brother|brethren|daughter|wife|husband|anak|ama|ina|kapatid|asawa)\\s*$");
    private static final Pattern CALLED = Pattern.compile("(?<![\\p{L}])(?<!(?:place|city|land|mount|mountain|field|river|valley|pool|gate|is|was|be|which|that|na)\\s)(?:called|calleth|tinawag)\\s+(?:unto\\s+|si\\s+)?(\\p{Lu}\\p{Ll}+)");
    private static final Pattern TO_NAME = Pattern.compile("(?<![\\p{L}])(?:unto|to|kay)\\s+\\p{Lu}\\p{Ll}+");
    private static final Pattern TO_WHOM = Pattern.compile("(?i)(?<![\\p{L}])(?:unto|to|kay|sa)\\s+(?:the\\s+|his\\s+|her\\s+|their\\s+|a\\s+|ang\\s+|si\\s+)?(?:[\\p{L}'\u2019]+\\s+)?([\\p{L}'\u2019]+)(?=\\s*[,:])");
    private static final Pattern PERSON = Pattern.compile("woman|man|father|mother|son|daughter|servant|king|queen|centurion|lawyer|ruler|angel|babae|lalake|lalaki|ama|ina|anak|alipin|hari|reyna|senturion|anghel");
    private static final Pattern PRONOUN = Pattern.compile("him|her|them|it|me|us|thee|you|kaniya|kanila|kanya|inyo|akin|amin|atin");
    /** The story takes up again after a parable or a sermon: "And the disciples came, and said unto him,", "And it came to pass, when Jesus had ended these sayings,". */
    private static final Pattern RESTART = Pattern.compile("(?i)^(?:and|then|now|but|so|at|nang|datapuwa't|pagkatapos|when)\\b[^.!?]{0,40}?(?<![\\p{L}])(?:jesus|hesus|(?:the|his)\\s+disciples|(?:ang|ng)\\s+(?:kaniyang\\s+)?mga\\s+alagad|pharisees|fariseo|multitude|karamihan|it\\s+came\\s+to\\s+pass|nangyari)(?![\\p{L}])"
            // In Tagalog the story's own sentences begin with the word of saying: "Sinabi nga ng mga alagad sa kaniya,", "Sinalita nga ni Jesus…".
            + "|^(?:sinabi|sumagot|nagsabi|sinalita|nagtanong|tinanong|sinagot|sumigaw|nagsalita)(?![\\p{L}])[^,:.!?]{0,40}");
    private static final Pattern LORD_ONLY = Pattern.compile("(?<![\\p{L}])(?:Lord|Panginoon)(?![\\p{L}])");
    private static final Pattern GOSPEL = Pattern.compile("(?i)(?<![\\p{L}])(?:disciples?|apostles?|pharisees?|publicans?|sadducees|alagad|apostol|fariseo|publikano|saduceo)(?![\\p{L}])");
    private static final Pattern ANOTHER = Pattern.compile("(?i)(?<![\\p{L}])(?:there\\s+came\\s+(?:also\\s+)?(?:a|another|one)(?![\\p{L}])|came\\s+(?:also\\s+)?(?:a|another)\\s+(?:messenger|man|servant|woman)|(?:a|another)\\s+messenger|isa\\s+pang\\s+(?:sugo|alipin)|may\\s+dumating\\s+na\\s+(?:isang|isa))");
    /** The devil only spoken of ("possessed with a devil", "when the devil was cast out"): not the one who speaks next. */
    private static final Pattern DEVIL_DEALT_WITH = Pattern.compile("(?i)possessed|cast(?:eth|ing)?\\s+out|was\\s+cast|casteth|inalihan|pinalayas|ipinagpalayas|inaalihan");
    /** "And he said unto them," in the Gospels, once Jesus has spoken: it is he who speaks to the crowd. */
    private static final Pattern SAYS_TO_THEM = Pattern.compile("(?i)(?<![\\p{L}])(?:unto|to|sa)\\s+(?:them|kanila|kaniyang\\s+mga\\s+alagad|his\\s+disciples)(?![\\p{L}])");
    private static final Pattern HOLY = Pattern.compile("(?i)(?<![\\p{L}])(?:holy|banal|santo|ghost)(?![\\p{L}])");
    /** "and rent him sore, and came out of him": what the spirit did, not words it says. */
    private static final Pattern DID = Pattern.compile("(?i)^\\W*(?:with\\s+(?:a\\s+)?(?:\\p{L}+\\s+){0,2}voice\\W+)?(?:(?:and|he|she|they)\\s+)?(?:\\p{L}+ed|rent|tare|came|fell|ran|went|took|rose|arose|saw|was|were|had|brake|departed|threw|cast)(?![\\p{L}])");
    /** Words that are Jesus's alone, said by the voice in Revelation and in the Gospels, when nothing around them names him. */
    private static final Pattern JESUS_ONLY = Pattern.compile("(?i)write\\s+in\\s+a\\s+book,?\\s+and\\s+send\\s+it\\s+unto\\s+the\\s+seven|isulat\\s+mo\\s+sa\\s+isang\\s+aklat\\s+at\\s+iyong\\s+ipadala\\s+sa\\s+pitong|"
            + "i\\s+am\\s+(?:he\\s+that\\s+liveth|the\\s+first\\s+and\\s+the\\s+last|alpha\\s+and\\s+omega,\\s+the\\s+first)|ako\\s+ang\\s+(?:una\\s+at\\s+ang\\s+huli|buhay\\s+at)|"
            + "i\\s+have\\s+the\\s+keys\\s+of\\s+(?:hell|death)|nasa\\s+akin\\s+ang\\s+mga\\s+susi|behold,?\\s+i\\s+stand\\s+at\\s+the\\s+door|narito,?\\s+ako'?y\\s+nakatayo\\s+sa\\s+pintuan|"
            + "behold,?\\s+i\\s+come\\s+quickly|narito,?\\s+ako'?y\\s+pumaparito\\s+na\\s+madali");
    /** A tag that comes after the words and names who said them: "said Peter.", "answered Simon Peter.", "said Philip unto him.", "said she." */
    private static final Pattern TAIL_TAG = Pattern.compile("(?i)(?<=[,?!])\\s*(?:said|answered|replied|asked|saith|quoth)\\s+(?:(?:he|she)|(?-i:\\p{Lu}[\\p{L}']+(?:\\s+\\p{Lu}[\\p{L}']+)?))(?:\\s+(?:unto|to)\\s+[\\p{L}]+)?[\\s.!?]*$");
    private static final Pattern SAITH_TAG =Pattern.compile("(?i)^\\W*(?:saith|sayeth|says|sabi|ang\\s+sabi)(?![\\p{L}])");
    private static final Pattern HEAD_FIRST = Pattern.compile("(?i)^\\W*(?:for\\s+|and\\s+|at\\s+|sapagka't\\s+|behold,?\\s+)?(?:i|ako|ako'y|my|aking|ang\\s+aking)(?![\\p{L}])");
    private static final Pattern SEER = Pattern.compile("(?i)^\\W*(?:and\\s+|at\\s+|then\\s+|nang\\s+)?(?:i\\s+(?:saw|turned|heard|looked|fell|beheld|was|wept|took)|ako'?y\\s+(?:lumingon|tumingin|nakakita|nakarinig|nahulog|napasa|umiyak|kumuha)|nakita\\s+ko|narinig\\s+ko|nang\\s+ako'?y\\s+\\p{L}+)(?![\\p{L}])");
    private static final Pattern SECOND = Pattern.compile("(?i)(?<![\\p{L}'])(?:thou|thee|thy|you|ye|your|ikaw|ka|mo|iyo|iyong|inyo|inyong|kayo)(?![\\p{L}])");
    /** The one John sees in Revelation 1: "one like unto the Son of man", "isang katulad ng isang anak ng tao". */
    private static final Pattern SON_OF_MAN = Pattern.compile("(?i)(?:like\\s+(?:unto\\s+)?(?:the\\s+)?son\\s+of\\s+man|katulad\\s+ng\\s+(?:isang\\s+)?anak\\s+ng\\s+tao)");
    /** The story takes up again after Jesus's words, the surest signs: "And it came to pass", "his mother and his brethren came", "Nangyari nga", "nagsilapit". */
    private static final Pattern STORY_AGAIN = Pattern.compile("(?i)^\\W*(?:(?:and|then|now|but)\\s+)?(?:it\\s+came\\s+to\\s+pass|there\\s+came\\s+(?:then\\s+)?(?:to|unto|his|a|certain)|then\\s+came\\s+(?:his|the|to)|his\\s+mother\\s+and\\s+his\\s+brethren)"
            + "|^\\W*(?:(?:at|datapuwa't|nang)\\s+)?(?:nangyari|nagsiparoon|nagsilapit|nagsidating|dumating\\s+(?:ang\\s+kaniyang|nga)|ang\\s+kaniyang\\s+ina\\s+at\\s+ang\\s+kaniyang\\s+mga\\s+kapatid)");
    private static final Pattern JESUS_FIRST = Pattern.compile("(?i)^\\W*(?:(?:and|then|now|but|at|nang|datapuwa't|sa\\s+gayo'y)\\s+)*(?:ang\\s+|si\\s+|the\\s+)?(?:jesus|hesus|panginoong\\s+jesus)(?![\\p{L}])");
    private static final Pattern EXPLAINS = Pattern.compile("(?i)^\\W*(?:and\\s+|at\\s+)?(?:these|those)\\s+(?:are|be)\\s+they|^\\W*(?:and\\s+|at\\s+)?(?:ito|ang\\s+mga\\s+ito|ang\\s+mga\\s+nasa)\\s+(?:ang|ay|yaong)\\s+");
    private static final Pattern HE_SAID_LEAD = Pattern.compile("(?i)^\\W*(?:and\\s+|at\\s+)?(?:he|siya)\\s+(?:said|saith|spake|sinabi|nagsabi)\\s*(?:unto\\s+them|sa\\s+kanila)?\\s*[,:]?\\s*");
    private static final Pattern HE_SAID = Pattern.compile("(?i)^\\W*(?:and|at|then)?\\s*(?:he|siya)\\s+(?:said|saith|spake|sinabi|nagsabi)(?![\\p{L}])");
    /** "spake a parable unto them", "in parables": Jesus's way of teaching (not "he took up his parable and said", which is a prophet's). */
    private static final Pattern PARABLE_TOLD = Pattern.compile("(?i)(?:spake|spoke|told|taught|speaks|sinalita|pinagsalitaan|nagsalita|nagturo|nagpahayag|inihayag)\\W+(?:\\w+\\W+){0,6}?(?:(?:this|a|another)\\s+parable|parables|in\\s+parables|mga\\s+talinghaga|isang\\s+talinghaga)|in\\s+parables|sa\\s+mga\\s+talinghaga|isang\\s+talinghaga\\s+sa\\s+kanila");
    private static final Pattern TELLS_PARABLE = Pattern.compile("(?i)(?:behold,?\\s+a\\s+sower|a\\s+certain\\s+(?:man|king|householder|rich\\s+man)\\s+(?:had|made|planted|which)|the\\s+kingdom\\s+of\\s+(?:heaven|god)\\s+is\\s+(?:like|likened)|unto\\s+what\\s+is\\s+the\\s+kingdom|whereunto\\s+shall\\s+(?:we|i)\\s+(?:liken|resemble)|manghahasik|ang\\s+kaharian\\s+ng\\s+(?:langit|dios|diyos)\\s+ay\\s+(?:katulad|gaya|nagiging\\s+gaya))");
    /** Somebody other than the one the page is about is named in front of the saying. */
    private static final Pattern PRONOUN_ONLY_OTHER = Pattern.compile("(?i)(?<![\\p{L}])(?:(?:the|his|her|their|my)\\s+(?:scribes?|pharisees?|lawyer|devil|tempter|disciples?|mother|brethren|apostles?|people|multitude)|they|ang\\s+(?:kaniyang\\s+)?(?:mga\\s+)?(?:eskriba|fariseo|alagad|diablo|tao|karamihan))(?![\\p{L}])");
    private static final Pattern PARABLE_WORD = Pattern.compile("(?i)(?<![\\p{L}])(?:parable|parables|talinghaga)(?![\\p{L}])");
    private static final Pattern SI_NAME = Pattern.compile("^(?:At\\s+|Nguni't\\s+|Datapuwa't\\s+|Kaya't\\s+)?[Ss]i\\s+(\\p{Lu}\\p{Ll}[\\p{L}]+)(?![\\p{L}])(?!\\s+ay\\s+(?:sinabihan|tinanong))");
    private static final Pattern NOUN_SAID = Pattern.compile("(?i)(?<![\\p{L}])(?:the\\s+(woman|man|father|mother|son|servant|king|queen|centurion|young\\s+man|blind\\s+man|lawyer|ruler|angel|people|multitude|disciples|pharisees|scribes|governor|high\\s+priest|chief\\s+priests|captain|chief\\s+captain|elders|soldiers|officers|keeper\\s+of\\s+the\\s+prison|nobleman|damsel|maid|priest|prophet|messenger|watchman|steward)\\s+(?:said|saith|answered|asked|replied|cried)"
            + "|(?:sinabi|sumagot|nagtanong|wika|sabi)\\s+(?:nga\\s+|naman\\s+|sa\\s+kaniya\\s+|sa\\s+kanila\\s+)*ng\\s+(babae|lalake|lalaki|ama|ina|anak|alipin|hari|reyna|senturion|anghel|bayan|karamihan|mga\\s+alagad|mga\\s+fariseo|mga\\s+eskriba|gobernador|dakilang\\s+saserdote|mga\\s+pangulong\\s+saserdote|mga\\s+kawal|propeta|saserdote))(?![\\p{L}])");

    /** "said unto the woman,", "said to his father,": the one spoken to, whoever it is, is not the one who speaks. */
    private static final Pattern TO_SOMEONE = Pattern.compile("(?i)(?<![\\p{L}])(?:unto|to)\\s+(?:the\\s+|his\\s+|her\\s+|their\\s+|a\\s+|one\\s+of\\s+the\\s+)?[\\p{L}'\u2019]+(?:\\s+[\\p{L}'\u2019]+)?(?=\\s*[,:])");

    /** A verse that tells what happened: it begins like a story and nobody in it says "I" or "you". */
    private static boolean narrates(String v) {
        if (!NARRATION.matcher(v).matches() || LET.matcher(v).matches()) return false; // "And let them be for lights…" is still being said
        String head = v.length() > 140 ? v.substring(0, 140) : v;
        // The seer's own telling ("And I turned to see the voice", "At ako'y lumingon"): first person, but story, not talk to somebody.
        if (SEER.matcher(head).find() && !SECOND.matcher(head).find()) return true;
        return !TALK.matcher(head).find();
    }

    /** Who has spoken so far in the passage, and the voice code of each. */
    private static final class State {
        final List<String> keys = new ArrayList<>(); // speakers in order of first speech
        final List<String> codes = new ArrayList<>();
        final List<String> history = new ArrayList<>(); // one entry per turn
        int anon;
        final java.util.Set<Integer> usedM = new java.util.HashSet<>(), usedF = new java.util.HashSet<>();
        final java.util.Set<String> unsureKeys = new java.util.HashSet<>(); // speakers who could only be guessed, with the Lord or his people near

        /** The first number not taken yet; the villain takes one too. */
        int firstFree(java.util.Set<Integer> used) {
            int n = 0;
            while (used.contains(n)) n++;
            used.add(n);
            return n;
        }

        /** A person by name (Peter, Pedro, Sarah), not "the woman" or "someone#1". */
        boolean isName(String key) {
            return key.length() >= 3 && !key.startsWith("#") && !key.contains(" ") && !PERSON.matcher(key).matches()
                    && !key.equals("people") && !key.equals("multitude") && !key.equals("disciples") && !key.equals("pharisees") && !key.equals("scribes");
        }
        String spokenTo; // who the last words were said to, when that is told
        String speechNow = ""; // the words whose speaker is being looked for
        boolean lastToPronoun; // the last words were said "unto him" / "unto her": the one spoken to will answer
        String mentioned; // "jesus" or "father" when the verses have spoken of him lately
        boolean gospelSeen; // the verses read so far are about Jesus and his disciples: "the Lord" is Jesus
        boolean evilSeen;
        boolean jesusTeaching; // Jesus is in the middle of a long teaching or a parable: "And he said," goes on with the same one
        boolean hintStrong; // the words are ones only Jesus says ("write in a book and send it to the seven churches")
        boolean hintJesus; // the verse being read tells a parable or says what only he says: an unnamed "he" is Jesus

        boolean lordIsJesus(String s) {
            Matcher m = LORD_ONLY.matcher(s);
            if (!m.find()) return false;
            if (s.matches("(?s).*(?<![\\p{L}])(?:dios|diyos|god|jehova|jehovah|yahweh)(?![\\p{L}]).*")) return false; // "the Lord God"
            boolean upper = s.contains("LORD") || s.contains("PANGINOON");
            return !upper && (gospelSeen || "jesus".equals(mentioned));
        }

        String who(String nearIntro, String before) {
            String key = null;
            final String rawIntro = nearIntro;
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
                // "said unto his son," "sa kaniyang ama": which son is not said; who answers comes from who has been talking.
                if (KIN_TO.matcher(ts.group()).find()) continue;
                // "sa Jerico" is "in Jericho" as often as "to": only a known person is the one spoken to.
                if (ts.group().toLowerCase(Locale.ROOT).startsWith("sa") && !(keys.contains(lw) || TextPrep.MEN.contains(lw) || TextPrep.WOMEN.contains(lw) || BibleCharacters.isWoman(lw) || PERSON.matcher(lw).matches())) continue;
                if (lw.equals("father") || lw.equals("ama")) lw = "a father";
                if (VILLAIN.matcher(w).find()) to = "villain";
                else if (keys.contains(lw) || TextPrep.MEN.contains(lw) || TextPrep.WOMEN.contains(lw) || PERSON.matcher(lw).matches() || lw.equals("a father")
                        || (Character.isUpperCase(w.charAt(0)) && !NOT_NAMES.contains(lw) && !PRONOUN.matcher(lw).matches())) to = lw;
            }
            if (to == null) {
                // "he called Esau his eldest son, and said unto him": the one called answers.
                Matcher cl = CALLED.matcher(before + " " + nearIntro);
                while (cl.find()) if (nameLike(cl.group(1).toLowerCase(Locale.ROOT)) && !FATHER.matcher(cl.group(1)).matches()) to = cl.group(1).toLowerCase(Locale.ROOT);
            }
            nearIntro = TO_SOMEONE.matcher(nearIntro).replaceAll(" ");
            // "called unto Abraham out of heaven": who is called to is not who speaks.
            nearIntro = TO_NAME.matcher(nearIntro).replaceAll(" ");
            nearIntro = CALLED.matcher(nearIntro).replaceAll(" "); // ("Pharaoh called Abram, and said": Abram is called, Pharaoh speaks)
            nearIntro = OF_GOD.matcher(nearIntro).replaceAll(" ");
            // "he rebuked the foul spirit, saying unto him, Thou dumb and deaf spirit": the spirit is spoken to, not the one speaking.
            if (SPIRIT_SPOKEN_TO.matcher(before + " " + nearIntro).find()) nearIntro = EVIL_SPIRIT.matcher(SPIRIT_SPOKEN_TO.matcher(nearIntro).replaceAll(" ")).replaceAll(" ");
            // "the Lord called Samuel: and he answered,": the one called answers.
            Matcher ct = CALLED_THEN.matcher(before);
            if (ct.find() && nameLike(ct.group(1).toLowerCase(Locale.ROOT))) {
                key = ct.group(1).toLowerCase(Locale.ROOT);
            } else if (subjectOf(EVIL_SPIRIT, nearIntro)) {
                key = "villain";
            } else if (JESUS.matcher(nearIntro).find()) {
                key = "jesus";
            } else if (evilSeen && !HOLY.matcher(nearIntro).find() && SPIRIT.matcher(nearIntro).find()) {
                key = "villain"; // "And the spirit cried, and rent him sore": the unclean one
            } else if (SPIRIT.matcher(nearIntro).find()) {
                key = "spirit";
            } else if (FATHER.matcher(nearIntro).find()) {
                // In the Gospels and Acts "the Lord" (not "the LORD", not "Lord God") is Jesus.
                key = lordIsJesus(nearIntro) ? "jesus" : "father";
            } else if (subjectOf(VILLAIN, nearIntro)) {
                key = "villain";
            } else {
                // A named person: "sinabi ni Pedro", "Martha said", "said Peter".
                Matcher c = NAMED.matcher(nearIntro);
                while (c.find() && key == null) {
                    String w = (c.group(1) != null ? c.group(1) : c.group(2) != null ? c.group(2) : c.group(3) != null ? c.group(3) : c.group(4)).toLowerCase(Locale.ROOT);
                    // "Ruth the Moabitess said": Ruth.
                    if (c.group(2) != null) {
                        Matcher tn = Pattern.compile("(\\p{Lu}\\p{Ll}+)\\s+the\\s+$").matcher(nearIntro.substring(0, c.start(2)));
                        if (tn.find()) w = tn.group(1).toLowerCase(Locale.ROOT);
                    }
                    // "Josue na anak ni Nun", "tagapangasiwa ni Moises": whose son or servant, not who speaks.
                    if (c.group(1) != null && Pattern.compile("(?i)(?<![\\p{L}])(?:" + OWNED_BY.pattern().replace("(?i)(?<![\\p{L}])(?:", "").replace(")\\s*$", "") + ")\\s+(?:ni|ng)\\s+" + Pattern.quote(c.group(1)) + "(?![\\p{L}])")
                            .matcher(before + " " + nearIntro).find()) continue;
                    if (!NOT_NAMES.contains(w)) key = w;
                }
                // "the woman said", "sinabi ng babae": somebody known by what they are.
                Matcher ns = key == null ? NOUN_SAID.matcher(nearIntro) : null;
                if (ns != null && ns.find()) {
                    key = (ns.group(1) != null ? ns.group(1) : ns.group(2)).toLowerCase(Locale.ROOT);
                    if (key.equals("father") || key.equals("ama")) key = "a father"; // (not the Father)
                    key = key.replaceAll("\\s+", " ");
                    // In the Gospels the governor is Pilate: one man, one voice.
                    if ((key.equals("governor") || key.equals("gobernador")) && (keys.contains("pilate") || keys.contains("pilato") || gospelSeen)) key = keys.contains("pilato") ? "pilato" : "pilate";
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
                // "And Jesus answered and spake unto them again by parables, and said,": he is the subject of the verse, though far back.
                if (key == null && JESUS_FIRST.matcher(before).find() && !"jesus".equals(toWhom)) key = "jesus";
                // "there came also another, and said," / "a messenger came unto Job, and said,": somebody new, not who spoke before.
                if (ANOTHER.matcher(nearIntro).find()) {
                    key = "#" + (++anon);
                    spokenTo = null;
                }
                String nearTail = nearIntro.length() > 45 ? nearIntro.substring(nearIntro.length() - 45) : nearIntro;
                // "and prayed, saying, O my Father,": in the Gospels only Jesus prays so.
                if (key == null && gospelSeen && PRAYS_FATHER.matcher(speechNow).find() && !"jesus".equals(toWhom)) key = "jesus";
                // "And he said to his disciples,": in the Gospels, Jesus.
                if (key == null && gospelSeen && HIS_DISCIPLES.matcher(rawIntro).find() && !"jesus".equals(toWhom)) key = "jesus";
                // "Moreover he said,", "And he said moreover,", "And God said moreover": the same one goes on.
                if (key == null && lastKey != null && MOREOVER.matcher(nearTail).find()) key = lastKey;
                // "And they said unto him,": the people there, in one voice of their own; not whoever spoke last.
                if (key == null && THEY.matcher(nearTail).find() && !jesusTeaching) key = "people";
                // "And she said,": a woman, the one spoken to or the last woman to speak.
                if (key == null && SHE.matcher(nearTail).find()) {
                    if (spokenTo != null && womanKey(spokenTo)) key = spokenTo;
                    for (int k = history.size() - 1; k >= 0 && key == null; k--) if (womanKey(history.get(k))) key = history.get(k);
                    Matcher wn = CAPITAL.matcher(before + " " + nearIntro);
                    while (key == null && wn.find()) if (BibleCharacters.isWoman(wn.group().toLowerCase(Locale.ROOT))) key = wn.group().toLowerCase(Locale.ROOT);
                    if (key == null) {
                        key = "#" + (++anon);
                        femaleKeys.add(key);
                    }
                }
                // "Then Zipporah took a sharp stone, …, and said,", "The woman then left her waterpot, … and saith to the men,": the one
                // the sentence is about says it, though named far back.
                if (key == null && (!PRON_SUBJ.matcher(nearTail).find() || WHEN_CLAUSE.matcher(lead(before, before.length(), 200)).find())) {
                    String src = before.replaceAll("(?i)^\\W*(?:(?:and|then|but|so|now|at)\\W*)?$", "").isEmpty() ? rawIntro : before;
                    String sk = subjectKey(src);
                    if (sk != null && !sk.equals(toWhom)) key = sk;
                }
                // A parable he tells, or words only he says, are his whoever was spoken to last.
                if (key == null && hintJesus && HE.matcher(before + " " + nearIntro).find() && !"jesus".equals(toWhom) && !PRONOUN_ONLY_OTHER.matcher(nearIntro).find()
                        && !nearIntro.replaceAll("(?<![\\p{L}'])(?:And|Then|At|Nang|Kaniyang|Ang|Sa|Now|But|Pagkatapos|Datapuwa't|Kanilang|Kaniya)(?![\\p{L}])", " ").matches("(?s).*(?<![\\p{L}'])\\p{Lu}\\p{L}+.*")) key = "jesus";
                if (key == null && spokenTo != null && !spokenTo.equals(lastKey)) key = spokenTo; // the one who was just spoken to answers
                if (key == null && "villain".equals(lastKey)) key = "jesus";
                // "Now the serpent was more subtil… And he said unto the woman,": the first to speak is the one the verse is about.
                if (key == null && VILLAIN.matcher(before).find() && !JESUS.matcher(before).find() && !DEVIL_DEALT_WITH.matcher(before).find()) key = "villain";
                // "And he spake many things unto them in parables, saying, Behold, a sower went forth to sow": nobody else tells parables.
                if (key == null && (hintJesus || jesusTeaching || (gospelSeen && history.contains("jesus") && SAYS_TO_THEM.matcher(rawIntro).find())) && !"jesus".equals(toWhom) && HE.matcher(before + " " + nearIntro).find() && !PRONOUN_ONLY_OTHER.matcher(nearIntro).find()
                        && !(nearIntro).replaceAll("(?<![\\p{L}'])(?:And|Then|At|Nang|Kaniyang|Ang|Sa|Now|But|Pagkatapos|Datapuwa't|Kanilang|Kaniya)(?![\\p{L}])", " ").matches("(?s).*(?<![\\p{L}'])\\p{Lu}\\p{L}+.*")) key = "jesus"; // (somebody named is not he)
                // "…a great voice, as of a trumpet, | saying, What thou seest, write in a book": words only he says, though nobody is named.
                if (key == null && hintStrong && !"jesus".equals(toWhom)) key = "jesus";
                final boolean oneMan = HE.matcher(nearTail).find(); // "he": one person, not a crowd
                for (int k = history.size() - 2; k >= 0 && key == null; k--) {
                    if (oneMan && ("people".equals(history.get(k)) || "disciples".equals(history.get(k)))) continue;
                    // (The tempter or an unclean spirit answers again only when the verse tells of it: not "he saith unto the ruler".)
                    boolean bad = "villain".equals(history.get(k)) && !VILLAIN.matcher(before + " " + nearIntro).find() && !EVIL_SPIRIT.matcher(before + " " + nearIntro).find();
                    // (In the Gospels, once Jesus has spoken, "he said" after somebody else is Jesus, not a voice from heaven.)
                    if ("father".equals(history.get(k)) && gospelSeen && history.contains("jesus")) bad = true;
                    if (!bad && !history.get(k).equals(lastKey) && !history.get(k).equals(toWhom)) key = history.get(k);
                }
                // "And he opened his mouth, and taught them, saying": the "he" is who the chapter has been about, when nobody else is
                // named, nobody has spoken yet, and it is not "a certain lawyer … tempted him".
                if (key == null && mentioned != null && !mentioned.equals(toWhom) && (history.isEmpty() || (!keys.contains(mentioned) && !"father".equals(mentioned))) && HE.matcher(before).find() && !SOMEONE.matcher(before).find() && !PRONOUN_ONLY_OTHER.matcher(nearIntro).find()) key = mentioned;

                // "And Jehu … said unto the worshippers of Baal, … | And he said unto him that was over the vestry,": nobody else has
                // spoken and nobody was spoken to by name, so it is the same one going on.
                if (key == null && lastKey != null && !lastToPronoun && !lastKey.equals(toWhom) && !"people".equals(lastKey) && !"villain".equals(lastKey)
                        && !lastKey.startsWith("#") && history.stream().distinct().count() == 1 && !gospelSeen) key = lastKey;
                // "Who art thou, Lord?", "O my Lord, send": said to God or to Jesus, so not by him.
                if (key != null && ("father".equals(key) || "jesus".equals(key)) && !jesusTeaching && ADDRESS_LORD.matcher(speechNow).find()) {
                    key = null;
                    if (spokenTo != null && !"father".equals(spokenTo) && !"jesus".equals(spokenTo)) key = spokenTo;
                    for (int k = history.size() - 1; k >= 0 && key == null && !gospelSeen; k--) {
                        String h = history.get(k);
                        if (!"father".equals(h) && !"jesus".equals(h) && !"villain".equals(h) && !"people".equals(h)) key = h;
                    }
                }
                if (key == null) {
                    key = "#" + (++anon);
                    String cue = before + " " + nearIntro;
                    if (FEMALE_CUE.matcher(cue).find()) femaleKeys.add(key);
                    // Nothing says who this is, and Jesus, God or his disciples are in the verses: a guess would be a wrong voice for words
                    // that may be theirs. The words are said in the storyteller's voice, not in a character's.
                    if (toWhom == null && !OF_GOD.matcher(rawIntro).find()
                            && gospelSeen) unsureKeys.add(key); // (somebody who speaks to the Lord, or a man of God, is a person)
                }
            }
            spokenTo = to != null && !to.equals(key) ? to : null;
            lastToPronoun = TO_PRONOUN.matcher(rawIntro).find();
            history.add(key);
            int at = keys.indexOf(key);
            if (at < 0) {
                keys.add(key);
                String code;
                if (key.equals("father")) code = Cast.DIVINE_CODE;
                else if (key.equals("jesus")) code = Cast.JESUS_CODE;
                else if (key.equals("spirit")) code = Cast.SPIRIT_CODE;
                else if (key.equals("villain")) code = "cm" + firstFree(usedM) + "^villain";
                else {
                    boolean woman = femaleKeys.contains(key) || TextPrep.WOMEN.contains(key) || BibleCharacters.isWoman(key) || FEMALE_CUE.matcher(key).find();
                    java.util.Set<Integer> used = woman ? usedF : usedM;
                    // A named person has a voice slot of their own, the same on every page and in both languages; the rest take the next free one.
                    int n = isName(key) ? BibleCharacters.slot(key, used) : firstFree(used);
                    used.add(n);
                    code = (woman ? "cf" : "cm") + n;
                }
                // A child, an old man, a youth: told by how the verse names the one who speaks ("the old man said", "sinabi ng bata").
                if (!code.contains("^") && !code.startsWith("cd")) {
                    Matcher rs = ROLE_SPEAKER.matcher(nearIntro);
                    if (rs.find()) code += "^" + (rs.group(1) != null ? "child" : rs.group(2) != null ? "elder" : "teen");
                }
                codes.add(code);
                at = keys.size() - 1;
            }
            return unsureKeys.contains(key) ? "" : codes.get(at);
        }

        final java.util.Set<String> femaleKeys = new java.util.HashSet<>();

        String passage = ""; // all the verses, to tell a name from a word that only begins a sentence

        /** A person's name: known as one, or written with a capital in the middle of a sentence ("tempt Abraham", "named Ananias"). */
        boolean nameLike(String w) {
            if (w == null || w.length() < 2 || NOT_NAMES.contains(w) || NOT_CALLED.contains(w)) return false;
            if (TextPrep.MEN.contains(w) || TextPrep.WOMEN.contains(w) || BibleCharacters.known(w) || keys.contains(w)) return true;
            String cap = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            if (Pattern.compile(Pattern.quote(cap) + ",\\s*" + Pattern.quote(cap) + "(?![\\p{L}])").matcher(passage).find()) return true; // "Moses, Moses"
            return Pattern.compile("\\p{Ll}\\s+" + Pattern.quote(cap) + "(?![\\p{L}])").matcher(passage).find();
        }

        boolean womanKey(String k) {
            return k != null && (femaleKeys.contains(k) || TextPrep.WOMEN.contains(k) || BibleCharacters.isWoman(k) || FEMALE_CUE.matcher(k).matches());
        }

        /** Who the sentence is about: "Then Zipporah took…" is Zipporah, "The woman then left…" the woman, "his disciples prayed him" the disciples. */
        String subjectKey(String text) {
            String t = text.replaceAll("\\s+", " ");
            Matcher e = Pattern.compile("[.!?]\\s").matcher(t);
            int cut = 0;
            while (e.find()) cut = e.end();
            t = SENT_LEAD.matcher(t.substring(cut).trim()).replaceFirst("");
            // "na ang Panginoon ay nagsalita kay Josue": who "ay nagsalita".
            Matcher ay = AY_SPOKE.matcher(t);
            if (ay.find()) {
                String w = ay.group(1).toLowerCase(Locale.ROOT);
                if (FATHER.matcher(w).matches()) return lordIsJesus(ay.group()) ? "jesus" : "father";
                if (JESUS.matcher(w).matches()) return "jesus";
                if (nameLike(w)) return w;
            }
            // "And when the Lord saw that he turned aside to see, God called unto him … and said,": God.
            Matcher d = SUBJ_DIVINE.matcher(t);
            if (d.find()) return JESUS.matcher(d.group()).find() ? "jesus" : lordIsJesus(d.group()) ? "jesus" : "father";
            Matcher g = SUBJ_GROUP.matcher(t);
            if (g.find()) return "people";
            Matcher n = SUBJ_NOUN.matcher(t);
            if (n.find()) {
                if (n.group(2) != null && n.group(1).equalsIgnoreCase("angel")) return "father"; // "the angel of the Lord" (not "the man of God")
                if (n.group(2) != null) return null;
                String w = n.group(1).toLowerCase(Locale.ROOT);
                return w.equals("disciples") ? "disciples" : w;
            }
            // "These words spake Jesus, and lifted up his eyes to heaven, and said,": named where the sentence speaks of him.
            Matcher nm = NAMED.matcher(t);
            while (nm.find()) {
                String w = (nm.group(1) != null ? nm.group(1) : nm.group(2) != null ? nm.group(2) : nm.group(3) != null ? nm.group(3) : nm.group(4)).toLowerCase(Locale.ROOT);
                if (nm.group(2) == null && nm.group(3) == null || NOT_NAMES.contains(w)) continue; // ("anak ni Nun" is whose son, not who speaks)
                if (JESUS.matcher(w).matches()) return "jesus";
                if (nameLike(w)) return w;
            }
            Matcher vn = VERB_NAME.matcher(t);
            if (vn.find()) {
                String w = vn.group(1).toLowerCase(Locale.ROOT);
                if (JESUS.matcher(w).matches()) return "jesus";
                if (nameLike(w)) return w;
            }
            Matcher c = SUBJ_NAME.matcher(t);
            if (c.find()) {
                String w = c.group(1).toLowerCase(Locale.ROOT);
                if (NOT_NAMES.contains(w) || NOT_CALLED.contains(w) || FATHER.matcher(w).matches()) return null;
                if (JESUS.matcher(w).matches()) return "jesus";
                if (c.group(2) != null ? nameLike(w) : (TextPrep.MEN.contains(w) || TextPrep.WOMEN.contains(w) || BibleCharacters.isWoman(w) || keys.contains(w))) return w;
            }
            return null;
        }

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
