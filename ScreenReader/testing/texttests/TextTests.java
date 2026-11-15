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

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
