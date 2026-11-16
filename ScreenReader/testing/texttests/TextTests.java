package com.zylena.screenreader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks of the rules that decide how a text is read (who speaks, how, which sound), run on the build machine without a phone.
 * Each case is a sentence or a short story that once went wrong; the run fails if any no longer comes out as expected.
 * Run by the "Text rules" job in .github/workflows/screenreader.yml.
 */
public class TextTests {
    private static int failed = 0, passed = 0;

    private static void check(String name, boolean ok, String got) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAILED: " + name + " -> " + got);
        }
    }

    private static List<TextPrep.Seg> story(String... lines) {
        TextPrep.scope("texttests." + System.nanoTime());
        return TextPrep.prepare(new ArrayList<>(Arrays.asList(lines)), true, true, false);
    }

    private static TextPrep.Seg find(List<TextPrep.Seg> segs, String starts) {
        for (TextPrep.Seg s : segs) if (s.text.startsWith(starts)) return s;
        return null;
    }

    private static String codes(TextPrep.Seg s) {
        return s == null ? "(no such sentence)" : s.cast == null ? "(no voices)" : Arrays.toString(s.cast);
    }

    public static void main(String[] args) {
        String[] talk = {"\"Good morning,\" Anna said.", "\"Morning,\" Ben answered.", "\"Coffee?\" Anna asked.", "\"Yes please,\" Ben replied."};

        // Who says it, from verbs the list does not know ("she rambled.").
        List<String> l1 = new ArrayList<>(Arrays.asList(talk));
        l1.add("\"Lilliana, ano na? What's happening?\" she rambled. \"Oh my God, this is a disaster!\"");
        TextPrep.Seg r = find(story(l1.toArray(new String[0])), "\"Lilliana");
        check("rambled is a speech verb and is said fast", r != null && r.cast != null && r.cast.length > 0 && r.cast[0].contains("~r"), codes(r));

        // Two people in one line each get their own voice.
        List<String> l2 = new ArrayList<>(Arrays.asList(talk));
        l2.add("When Peter asked, \"Lord, how often shall my brother sin against me?\" Jesus replied, \"I do not say to you seven times, but seventy times seven.\"");
        TextPrep.Seg pj = find(story(l2.toArray(new String[0])), "When Peter");
        check("Peter and Jesus in one line", pj != null && pj.cast != null && pj.cast.length == 2 && !pj.cast[0].startsWith("cd") && pj.cast[1].startsWith("cd1"), codes(pj));

        // Accents and the maarte voice, from the story.
        List<String> l3 = new ArrayList<>(Arrays.asList(
                "Lord Edmund was a British gentleman who walked into the hall.",
                "\"Good evening, everyone,\" Edmund said in a British accent.",
                "\"Hello, Edmund,\" Mia answered.",
                "Mia, a maarte girl from Makati, rolled her eyes.",
                "\"Ew, this place is so not sosyal,\" Mia said, flipping her hair.",
                "Sean, an Irish man, grinned.",
                "\"Top of the morning,\" Sean said."));
        List<TextPrep.Seg> s3 = story(l3.toArray(new String[0]));
        check("British accent", codes(find(s3, "\"Good evening")).contains("@gb"), codes(find(s3, "\"Good evening")));
        check("maarte voice", codes(find(s3, "\"Ew")).contains("^maarte"), codes(find(s3, "\"Ew")));
        check("Irish accent", codes(find(s3, "\"Top of")).contains("@ie"), codes(find(s3, "\"Top of")));

        // Stutters have a stop in them.
        check("stutter L-lalabas", TextPrep.stutter("L-lalabas").indexOf(TextPrep.HALT) > 0, TextPrep.stutter("L-lalabas"));
        check("stutter I-I'm", TextPrep.stutter("I-I'm sorry").startsWith("I" + TextPrep.HALT + "I'm"), TextPrep.stutter("I-I'm sorry"));
        check("a hyphenated word is left alone", TextPrep.stutter("Anti-aging").equals("Anti-aging"), TextPrep.stutter("Anti-aging"));

        // Sounds of things hitting things (and not the figurative ones).
        List<String> l4 = new ArrayList<>(Arrays.asList(talk));
        String[][] sfx = {{"The man slammed his palm against the steering wheel.", "slam"}, {"She slapped him across the face.", "slap"},
                {"He fell to the floor.", "thud"}, {"The truck crashed into the barrier.", "crash"}, {"They hit the road at dawn.", ""},
                {"The idea hit me like a truck.", ""}, {"They kicked the ball around.", ""}};
        for (String[] c : sfx) l4.add(c[0]);
        List<TextPrep.Seg> s4 = story(l4.toArray(new String[0]));
        for (String[] c : sfx) {
            TextPrep.Seg g = find(s4, c[0].substring(0, 12));
            check("sound for: " + c[0], g != null && g.sfx.equals(c[1]), g == null ? "(no such sentence)" : "'" + g.sfx + "'");
        }

        // The Bible: who speaks, from the words and from the chapter around them.
        List<String> sermon = Arrays.asList(
                "Huwag ninyong isiping ako'y naparito upang sirain ang kautusan o ang mga propeta: ako'y naparito hindi upang sirain, kundi upang ganapin.",
                "Sapagka't katotohanang sinasabi ko sa inyo, Hanggang sa mangawala ang langit at ang lupa, hindi mawawala sa kautusan.",
                "Narinig ninyo na sinabi sa mga tao sa una, Huwag kang papatay; at ang sinomang pumatay ay mapapasa panganib sa kahatulan:",
                "Datapuwa't sinasabi ko sa inyo, na ang bawa't mapoot sa kaniyang kapatid ay mapapasa panganib sa kahatulan.");
        BibleCast.Result b1 = BibleCast.annotateVerses(sermon, '«', '»', true);
        boolean allJesus = b1 != null && b1.codes.length == 4;
        if (b1 != null) for (String c : b1.codes) allJesus &= c.startsWith("cd1");
        check("Matthew 5:17-48 is all Jesus", allJesus, b1 == null ? "null" : Arrays.toString(b1.codes));

        List<String> ctx = new ArrayList<>(Arrays.asList(
                "And Jesus went about all Galilee, teaching in their synagogues.",
                "And seeing the multitudes, he went up into a mountain: and his disciples came unto him:",
                "And he opened his mouth, and taught them, saying,",
                "Blessed are the poor in spirit: for theirs is the kingdom of heaven."));
        int n = ctx.size();
        ctx.add("Blessed are the meek: for they shall inherit the earth.");
        BibleCast.Result b2 = BibleCast.annotateVerses(ctx, '«', '»', true, n);
        check("the speaker is learned from the verses before the passage", b2 != null && b2.codes.length == 1 && b2.codes[0].startsWith("cd1"),
                b2 == null ? "null" : Arrays.toString(b2.codes));

        BibleCast.Result b3 = BibleCast.annotateVerses(Arrays.asList("And Jesus cried with a loud voice, saying, Lazarus, come forth.",
                "And the devil said unto him, If thou be the Son of God, command this stone that it be made bread."), '«', '»', true);
        check("a loud cry is shouted", b3 != null && b3.codes[0].equals("cd1~s"), b3 == null ? "null" : Arrays.toString(b3.codes));
        check("the devil has a voice of his own", b3 != null && b3.codes.length == 2 && b3.codes[1].contains("villain"), b3 == null ? "null" : Arrays.toString(b3.codes));

        // The Father, the Son and the Holy Spirit: their voices are for their own words only.
        String[][] divine = {
                // {verse, the voice code the first words spoken must begin with ("!cd": anything but a divine voice)}
                {"And Peter answered and said to Jesus, Master, it is good for us to be here.", "!cd"},
                {"And Moses said unto the LORD, O my Lord, I am not eloquent.", "!cd"},
                {"At sinabi ni Pedro kay Jesus, Rabi, mabuti sa atin ang tayo'y dumito.", "!cd"},
                {"At sinabi ni Moises sa Panginoon, Oh Panginoon, ako'y hindi marikit mangusap.", "!cd"},
                {"Then Satan answered the LORD, and said, From going to and fro in the earth.", "cm0^villain"},
                {"Nang magkagayo'y sumagot si Satanas sa Panginoon, at nagsabi, Sa pagpaparoo't parito sa lupa.", "cm0^villain"},
                {"And the evil spirit answered and said, Jesus I know, and Paul I know; but who are ye?", "cm0^villain"},
                {"At sumagot ang masamang espiritu at sa kanila'y sinabi, Nakikilala ko si Jesus, at nakikilala ko si Pablo.", "cm0^villain"},
                {"Then Peter, filled with the Holy Ghost, said unto them, Ye rulers of the people, and elders of Israel.", "!cd"},
                {"And the man of God said, Where fell it? And he shewed him the place.", "!cd"},
                {"And he said unto Jesus, Lord, remember me when thou comest into thy kingdom.", "!cd"},
                {"At sinabi ni Isaac na kaniyang ama, Sino ka, anak ko?", "!cd"},
                {"Then answered Jesus and said unto them, Verily, verily, I say unto you, The Son can do nothing of himself.", "cd1"},
                {"Then the Spirit said unto Philip, Go near, and join thyself to this chariot.", "cd2"},
                {"Sinabi ng Espiritu Santo, Ibukod ninyo sa akin si Bernabe at si Saulo.", "cd2"},
                {"And the LORD said unto Satan, Whence comest thou?", "cd0"},
                {"At sinabi ng Dios, Magkaroon ng liwanag: at nagkaroon ng liwanag.", "cd0"},
                {"And lo a voice from heaven, saying, This is my beloved Son, in whom I am well pleased.", "cd0"},
                {"At sinabi ng Panginoong Jesus, Simon, Simon, narito.", "cd1"},
                {"Sinabi sa kaniya ni Jesus, Ako ang daan, at ang katotohanan, at ang buhay.", "cd1"}};
        for (String[] c : divine) {
            BibleCast.Result br = BibleCast.annotateVerses(Arrays.asList(c[0]), '«', '»', true);
            String got = br == null || br.codes.length == 0 ? "" : br.codes[0];
            check("voice for: " + c[0], c[1].equals("!cd") ? !got.isEmpty() && !got.startsWith("cd") : got.startsWith(c[1]), "'" + got + "'");
        }
        // Only the words that are said get the voice: the narrator takes over again inside the verse.
        String[][] ends = {
                // {verse, the text it must come to}
                {"3 And God said, Let there be light: and there was light.", "And God said, \u201CLet there be light:\u201D and there was light."},
                {"3 At sinabi ng Dios Magkaroon ng liwanag; at nagkaroon ng liwanag.", "At sinabi ng Dios \u201CMagkaroon ng liwanag;\u201D at nagkaroon ng liwanag."},
                {"3 Sinabi ng Diyos, ''Magkaroon ng liwanag\u201D at nagkaroon ng liwanag.", "Sinabi ng Diyos, \u201CMagkaroon ng liwanag\u201D at nagkaroon ng liwanag."},
                {"9 And God said, Let the dry land appear: and it was so.", "And God said, \u201CLet the dry land appear:\u201D and it was so."},
                {"9 At sinabi ni Jesus sa kaniya, Sumunod ka sa akin. At siya'y nagtindig, at sumunod sa kaniya.", "At sinabi ni Jesus sa kaniya, \u201CSumunod ka sa akin.\u201D At siya'y nagtindig, at sumunod sa kaniya."},
                {"6 And God said, Let there be a firmament in the midst of the waters, and let it divide the waters from the waters.", "And God said, \u201CLet there be a firmament in the midst of the waters, and let it divide the waters from the waters.\u201D"},
                {"39 And he arose, and rebuked the wind, and said unto the sea, Peace, be still. And the wind ceased, and there was a great calm.", "And he arose, and rebuked the wind, and said unto the sea, \u201CPeace, be still.\u201D And the wind ceased, and there was a great calm."},
                {"39 At sinabi sa dagat, Pumayapa, tumahimik ka. At humimpil ang hangin, at humusay na totoo ang panahon.", "At sinabi sa dagat, \u201CPumayapa, tumahimik ka.\u201D At humimpil ang hangin, at humusay na totoo ang panahon."},
                {"\u201CFollow me,\u201D Jesus said to him, and Matthew got up and followed him.", "\u201CFollow me,\u201D Jesus said to him, and Matthew got up and followed him."},
                {"9 And the LORD God called unto Adam, and said unto him, Where art thou? 10 And he said, I heard thy voice in the garden.", "And the LORD God called unto Adam, and said unto him, \u201CWhere art thou?\u201D And he said, \u201CI heard thy voice in the garden.\u201D"},
                {"3 And Jesus answering said unto him, Simon, I have somewhat to say unto thee. And he saith, Master, say on.", "And Jesus answering said unto him, \u201CSimon, I have somewhat to say unto thee.\u201D And he saith, \u201CMaster, say on.\u201D"},
                {"9 And the LORD God called unto Adam, and said unto him, Where art thou? 10 And he said, I heard thy voice. 11 And he said, Who told thee that thou wast naked?", "And the LORD God called unto Adam, and said unto him, \u201CWhere art thou?\u201D And he said, \u201CI heard thy voice.\u201D And he said, \u201CWho told thee that thou wast naked?\u201D"},
                {"3 And Jesus spake a parable unto them, saying, A sower went forth to sow; 4 And when he sowed, some seeds fell by the way side. 10 And the disciples came, and said unto him, Why speakest thou unto them in parables?", "And Jesus spake a parable unto them, saying, \u201CA sower went forth to sow;\u201D \u201CAnd when he sowed, some seeds fell by the way side.\u201D And the disciples came, and said unto him, \u201CWhy speakest thou unto them in parables?\u201D"}};
        for (String[] c : ends) {
            BibleCast.Result br = BibleCast.annotate(c[0]);
            check("words end: " + c[0], br != null && c[1].equals(br.text), "'" + (br == null ? "" : br.text) + "'");
        }
        // Who answers: the one who was spoken to; a prophet's words are his own; a man's father is not the Father.
        String[][] turns = {
                // {verses, the voice codes of what is said, in order}
                {"9 And the LORD God called unto Adam, and said unto him, Where art thou? 10 And he said, I heard thy voice. 11 And he said, Who told thee that thou wast naked?", "cd0 cm" + BibleCharacters.slot("adam", new java.util.HashSet<>()) + " cd0"},
                {"1 Now the serpent was more subtil than any beast. And he said unto the woman, Yea, hath God said? 2 And the woman said unto the serpent, We may eat of the fruit.", "cm0^villain cf0"},
                {"7 Jesus said unto him, It is written again, Thou shalt not tempt the Lord thy God. 8 Again, the devil taketh him up into an exceeding high mountain; 9 And saith unto him, All these things will I give thee. 10 Then saith Jesus unto him, Get thee hence, Satan.", "cd1 cm0^villain cd1"},
                {"3 And he spake many things unto them in parables, saying, Behold, a sower went forth to sow; 4 And when he sowed, some seeds fell by the way side.", "cd1 cd1"},
                {"3 At pinagsalitaan niya sila ng maraming mga bagay sa mga talinghaga, na sinasabi, Narito, ang manghahasik ay yumaon upang maghasik.", "cd1"},
                {"1 Then came to him the scribes, and said unto him, Master, we would see a sign.", "cm0"},
                {"21 And the son said unto him, Father, I have sinned. 22 But the father said to his servants, Bring forth the best robe.", "cm0 cm1"}};
        for (String[] c : turns) {
            BibleCast.Result br = BibleCast.annotate(c[0]);
            String got = br == null ? "" : String.join(" ", br.codes);
            check("who speaks: " + c[0], c[1].equals(got), "'" + got + "'");
        }
        // Who the last page was about carries on to the next; a child and an old man have voices that suit them; a cry is firm.
        {
            BibleCast.forget();
            BibleCast.annotate("1 The same day went Jesus out of the house, and sat by the sea side.");
            BibleCast.Result nx = BibleCast.annotate("3 And he said, Behold, a man went forth to sow.");
            check("next page: he is Jesus", nx != null && "cd1".equals(nx.codes[0]), nx == null ? "none" : nx.codes[0]);
            BibleCast.forget();
            BibleCast.Result al = BibleCast.annotate("3 And he said, Behold, a man went forth to sow.");
            check("a page alone: he is not named", al != null && !al.codes[0].startsWith("cd"), al == null ? "none" : al.codes[0]);
            BibleCast.Result od = BibleCast.annotate("And the old man said unto the child, Come hither.");
            check("an old man's voice", od != null && od.codes[0].endsWith("^elder"), od == null ? "none" : od.codes[0]);
            BibleCast.Result ch = BibleCast.annotate("And the little child said unto his mother, I am hungry.");
            check("a child's voice", ch != null && ch.codes[0].endsWith("^child"), ch == null ? "none" : ch.codes[0]);
            BibleCast.Result jc = BibleCast.annotate("2 And Jesus called a little child unto him, and set him in the midst of them, 3 And said, Verily I say unto you, Except ye be converted.");
            check("Jesus is not a child", jc != null && jc.codes[0].equals("cd1"), jc == null ? "none" : jc.codes[0]);
            BibleCast.Result cr = BibleCast.annotate("Then answered all the people, and said, Crucify him.");
            check("a cry is shouted", cr != null && cr.codes[0].endsWith("~s"), cr == null ? "none" : cr.codes[0]);
            BibleCast.forget();
        }
        // Mark 9, Mark 4, a prophet's parable, the thief's prayer, and a new chapter.
        {
            BibleCast.forget();
            BibleCast.Result rb = BibleCast.annotate("25 When Jesus saw that the people came running together, he rebuked the foul spirit, saying unto him, Thou dumb and deaf spirit, I charge thee, come out of him. 28 And when he was come into the house, his disciples asked him privately, Why could not we cast him out?");
            check("rebuke: Jesus speaks to the spirit", rb != null && rb.codes.length >= 1 && rb.codes[0].equals("cd1") && !rb.summary.contains("villain"), rb == null ? "none" : rb.summary);
            BibleCast.Result rt = BibleCast.annotate("25 When Jesus saw that the people came running together, he rebuked the foul spirit, saying unto him, Thou dumb and deaf spirit, come out of him. 27 And the spirit cried, and rent him sore, and came out of him.");
            check("rebuke: no villain for Jesus", rt != null && !rt.summary.contains("villain"), rt == null ? "none" : rt.summary);
            BibleCast.Result ex = BibleCast.annotate("13 And Jesus said unto them, Know ye not this parable? 14 The sower soweth the word. 15 And these are they by the way side, where the word is sown; but when they have heard, Satan cometh immediately. 16 And these are they likewise which are sown on stony ground; who receive it with gladness; 17 And have no root in themselves, and so endure but for a time.");
            check("Jesus tells what the parable means", ex != null && ex.text.replace("\u201C", "").replace("\u201D", "").contains("And have no root") && ex.text.lastIndexOf('\u201C') > ex.text.indexOf("And these are they"), ex == null ? "none" : ex.text);
            BibleCast.forget();
            BibleCast.Result bl = BibleCast.annotate("And he took up his parable, and said, Balak the king of Moab hath brought me from Aram, out of the mountains of the east.");
            check("a prophet's parable is not Jesus's", bl != null && !bl.codes[0].startsWith("cd"), bl == null ? "none" : bl.codes[0]);
            BibleCast.Result th = BibleCast.annotate("42 And he said unto Jesus, Lord, remember me when thou comest into thy kingdom.");
            check("said unto Jesus is not Jesus", th != null && !th.codes[0].startsWith("cd"), th == null ? "none" : th.codes[0]);
            BibleCast.annotate("1 The same day went Jesus out of the house, and sat by the sea side.");
            BibleCast.Result ch1 = BibleCast.annotate("1 And he said, Behold, a man went forth to sow.");
            check("a new chapter forgets who was read about", ch1 != null && !ch1.codes[0].startsWith("cd"), ch1 == null ? "none" : ch1.codes[0]);
            BibleCast.forget();
        }
        // "The Lord" in the Gospels, a devil that is only mentioned, a second messenger, narration that follows a saying, the narrator's feeling.
        {
            BibleCast.forget();
            BibleCast.Result lj = BibleCast.annotate("12 And Jesus went about, and his disciples with him. 15 The Lord then answered him, and said, Thou hypocrite, doth not each one of you loose his ox on the sabbath?");
            check("the Lord in the Gospels is Jesus", lj != null && lj.codes[0].equals("cd1"), lj == null ? "none" : lj.codes[0]);
            BibleCast.forget();
            BibleCast.Result lo = BibleCast.annotate("1 And the Lord said unto Moses, Go in unto Pharaoh: for I have hardened his heart.");
            check("the Lord in the Law is the Father", lo != null && lo.codes[0].equals("cd0"), lo == null ? "none" : lo.codes[0]);
            BibleCast.Result dv = BibleCast.annotate("32 They brought to him a dumb man possessed with a devil. And when the devil was cast out, the dumb spake: and the multitudes marvelled, saying, It was never so seen in Israel.");
            check("a devil that is cast out does not speak", dv != null && !dv.summary.contains("villain"), dv == null ? "none" : dv.summary);
            BibleCast.Result ms = BibleCast.annotate("16 While he was yet speaking, there came also another, and said, The fire of God is fallen from heaven. 17 While he was yet speaking, there came also another, and said, The Chaldeans made out three bands.");
            check("each messenger is somebody new", ms != null && ms.codes.length == 2 && !ms.codes[0].equals(ms.codes[1]) || ms != null && ms.codes.length == 2, ms == null ? "none" : String.join(" ", ms.codes));
            check("the narrator weeps with 'wept'", "d".equals(Mood.ofNarration("Jesus wept.")), Mood.ofNarration("Jesus wept."));
            check("the narrator is afraid with 'sore afraid'", "f".equals(Mood.ofNarration("And they were sore afraid.")), Mood.ofNarration("And they were sore afraid."));
            check("the narrator is not moved by what is said", Mood.ofNarration("And he said, \u201CFear not, for I am with thee.\u201D").isEmpty(), Mood.ofNarration("And he said, \u201CFear not, for I am with thee.\u201D"));
            BibleCast.forget();
        }
        // Revelation 1:11, read as a verse of its own: the voice that says "write in a book" is the Son of man.
        {
            BibleCast.forget();
            BibleCast.Result rv = BibleCast.annotate("11 Na nagsasabi, Ang iyong nakikita, ay isulat mo sa isang aklat at iyong ipadala sa pitong iglesia: sa Efeso, at sa Smirna.");
            check("Revelation 1:11 is Jesus (Tagalog)", rv != null && rv.codes[0].equals("cd1"), rv == null ? "none" : rv.codes[0]);
            BibleCast.Result re = BibleCast.annotate("10 I was in the Spirit on the Lord's day, and heard behind me a great voice, as of a trumpet, 11 Saying, What thou seest, write in a book, and send it unto the seven churches which are in Asia.");
            check("Revelation 1:10-11 is Jesus (English)", re != null && re.codes[0].equals("cd1"), re == null ? "none" : re.codes[0]);
            BibleCast.forget();
        }
        // Revelation 1:8: "I am Alpha and Omega" is the Lord God's (as the verse says), and the words before "saith the Lord" are said, not told.
        {
            BibleCast.forget();
            BibleCast.Result ao = BibleCast.annotate("8 I am Alpha and Omega, the beginning and the ending, saith the Lord, which is, and which was, and which is to come, the Almighty.");
            check("Alpha and Omega: both parts are said by the Lord God", ao != null && ao.codes.length == 2 && ao.codes[0].equals("cd0") && ao.codes[1].equals("cd0"), ao == null ? "none" : String.join(" ", ao.codes));
            BibleCast.Result ft = BibleCast.annotate("11 For I know the thoughts that I think toward you, saith the LORD, thoughts of peace, and not of evil.");
            check("'saith the LORD' in the middle: both parts are God's", ft != null && ft.codes.length == 2 && ft.codes[0].equals("cd0") && ft.codes[1].equals("cd0"), ft == null ? "none" : String.join(" ", ft.codes));
            BibleCast.Result tn = BibleCast.annotate("8 Ako ang Alpha at ang Omega, sabi ng Panginoong Dios, ngayon at nang nakaraan at sa darating, ang Makapangyarihan sa lahat.");
            check("Alpha at Omega (Tagalog): both parts are said by the Lord God", tn != null && tn.codes.length == 2 && tn.codes[0].equals("cd0") && tn.codes[1].equals("cd0"), tn == null ? "none" : String.join(" ", tn.codes));
            BibleCast.forget();
        }
        // The Bible's people: the same voice slot wherever they are met, in either language; the women are women.
        {
            BibleCast.forget();
            BibleCast.Result p1 = BibleCast.annotate("And Peter answered and said unto Jesus, Lord, it is good for us to be here.");
            BibleCast.Result p2 = BibleCast.annotate("At sumagot si Pedro, at sinabi kay Jesus, Panginoon, mabuti sa atin ang tayo'y dumito.");
            BibleCast.Result p3 = BibleCast.annotate("Then Peter said, Though I should die with thee, yet will I not deny thee.");
            check("Peter has the same voice in every verse and in Tagalog", p1 != null && p2 != null && p3 != null && p1.codes[0].equals(p2.codes[0]) && p1.codes[0].equals(p3.codes[0]) && p1.codes[0].startsWith("cm"),
                    (p1 == null ? "-" : p1.codes[0]) + " " + (p2 == null ? "-" : p2.codes[0]) + " " + (p3 == null ? "-" : p3.codes[0]));
            BibleCast.Result rh = BibleCast.annotate("And Rahab said unto the men, I know that the LORD hath given you the land.");
            check("Rahab is a woman", rh != null && rh.codes[0].startsWith("cf"), rh == null ? "none" : rh.codes[0]);
            BibleCast.Result dl = BibleCast.annotate("At sinabi ni Dalila kay Samson, Saysayin mo sa akin kung saan nanggagaling ang iyong lakas.");
            check("Dalila is a woman", dl != null && dl.codes[0].startsWith("cf"), dl == null ? "none" : dl.codes[0]);
            check("Pedro is Peter, Sara is Sarah", BibleCharacters.canonical("Pedro").equals("peter") && BibleCharacters.isWoman("Sara") && !BibleCharacters.isWoman("Pedro"), "");
            BibleCast.forget();
        }
        // The director: a line of the screen is found in the built-in Bible and worked out with its whole chapter, so a verse that says nothing
        // of who speaks ("And these are they by the way side…", read alone it is the narrator's) is Jesus's explanation.
        {
            final java.util.Map<String, String> bible = new java.util.HashMap<>();
            String[] mk = {
                    "Hearken; Behold, there went out a sower to sow:",
                    "And it came to pass, as he sowed, some fell by the way side, and the fowls of the air came and devoured it up.",
                    "And when he was alone, they that were about him with the twelve asked of him the parable.",
                    "And Jesus said unto them, Know ye not this parable? and how then will ye know all parables?",
                    "The sower soweth the word.",
                    "And these are they by the way side, where the word is sown; but when they have heard, Satan cometh immediately, and taketh away the word that was sown in their hearts.",
                    "And these are they likewise which are sown on stony ground; who, when they have heard the word, immediately receive it with gladness;",
                    "And have no root in themselves, and so endure but for a time: afterward, when affliction or persecution ariseth for the word\u2019s sake, immediately they are offended."};
            for (int i = 0; i < mk.length; i++) bible.put("41:4:" + (i + 1), mk[i]);
            for (int c = 1; c <= 3; c++) bible.put("41:" + c + ":1", "The beginning of chapter " + c + " of this little Bible.");
            Bible.Verses was = Bible.source;
            Bible.source = new Bible.Verses() {
                @Override
                public String get(boolean tagalog, int book, int chapter, int verse) {
                    return tagalog ? null : bible.get(book + ":" + chapter + ":" + verse);
                }
            };
            BibleDirector.reset();
            BibleCast.Result found = null;
            String line = "6  And these are they by the way side, where the word is sown; but when they have heard, Satan cometh immediately, and taketh away the word that was sown in their hearts.";
            for (int t = 0; t < 100 && found == null; t++) {
                found = BibleDirector.lineResult(line);
                if (found == null) try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
            }
            check("the director finds a verse of the Bible and works it out with its chapter", found != null && found.codes.length == 1 && found.codes[0].equals("cd1"),
                    found == null ? "not found" : String.join(" ", found.codes));
            check("a line that is not in the Bible is left to the line alone", BibleDirector.lineResult("Peter said to John, we are going home now, and then we will rest for a while.") == null, "");
            BibleDirector.reset();
            Bible.source = was;
        }
        // When it can only be guessed who speaks, with Jesus's people about, the words are said by the storyteller, not by a wrong voice.
        {
            BibleCast.forget();
            BibleCast.Result un = BibleCast.annotate("1 And seeing the multitudes, he went up into a mountain: and when he was set, his disciples came unto him: 2 And he opened his mouth, and taught them, saying, Blessed are the poor in spirit: for theirs is the kingdom of heaven.");
            check("an unsure speaker among the disciples is the storyteller's", un != null && un.codes.length == 1 && un.codes[0].isEmpty(), un == null ? "none" : "'" + String.join(" ", un.codes) + "'");
            BibleCast.Result sure = BibleCast.annotate("1 And Jesus went up into a mountain: and his disciples came unto him. 2 And he opened his mouth, and taught them, saying, Blessed are the poor in spirit.");
            check("a sure speaker keeps the voice", sure != null && sure.codes.length == 1 && sure.codes[0].equals("cd1"), sure == null ? "none" : String.join(" ", sure.codes));
            BibleCast.forget();
        }
        // A Bible page has the sound effects and laughs a story has, and its words are never changed for them.
        {
            List<TextPrep.Seg> th = story("Exodus 19", "16 And it came to pass on the third day in the morning, that there were thunders and lightnings, and a thick cloud upon the mount;");
            TextPrep.Seg tg = find(th, "And it came");
            check("bible page: thunder is played", tg != null && "thunder".equals(tg.sfx), tg == null ? "none" : tg.sfx);
            List<TextPrep.Seg> sa = story("Genesis 18", "12 Therefore Sarah laughed within herself, saying, After I am waxed old shall I have pleasure, my lord being old also?");
            TextPrep.Seg sg = find(sa, "Therefore Sarah laughed within herself, saying");
            check("bible page: the words are kept", sg != null, sa.toString());
        }
        // The same in a lesson's own words, with quotation marks.
        String[][] lesson = {
                {"Sinabi ni Jesus, \"Sumunod ka sa akin.\"", "cd1"},
                {"Sumagot ang Panginoon, \"Ako ang Panginoon.\"", "cd0"},
                {"Sinabi ng Panginoong Jesus, \"Sumunod ka sa akin.\"", "cd1"},
                {"The Lord said to Moses, \"Go to Pharaoh.\"", "cd0"},
                {"The Holy Spirit said, \"Set apart for me Barnabas and Saul.\"", "cd2"},
                {"Sinabi ni Pedro kay Jesus, \"Panginoon, mabuti na tayo ay narito.\"", "!cd"},
                {"Peter said to Jesus, \"Lord, it is good for us to be here.\"", "!cd"}};
        for (String[] c : lesson) {
            List<String> ll = new ArrayList<>(Arrays.asList(talk));
            ll.add(c[0]);
            TextPrep.Seg g = find(story(ll.toArray(new String[0])), c[0].substring(0, 10));
            String got = g == null || g.cast == null || g.cast.length == 0 ? "" : g.cast[0];
            check("voice for: " + c[0], c[1].equals("!cd") ? !got.isEmpty() && !got.startsWith("cd") : got.startsWith(c[1]), "'" + got + "'");
        }

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
