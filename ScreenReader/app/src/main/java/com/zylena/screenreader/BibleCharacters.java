package com.zylena.screenreader;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who the Bible's people are: the women (every other name is a man's, as in the Bible they nearly all are), and the same person under
 * their English and Tagalog names ("Peter" and "Pedro"). A person speaks with the same voice slot wherever they are met, on any page, in
 * either language, so that Peter sounds like Peter in every verse and chapter.
 */
final class BibleCharacters {
    private BibleCharacters() {
    }

    /** The women who speak or are spoken of, English and the Ang Biblia's spellings. */
    private static final Set<String> WOMEN = new HashSet<>(Arrays.asList((
            "eve eba sarah sarai sara hagar agar rebekah rebecca rebeka rachel raquel leah lea dinah dina tamar miriam zipporah sefora rahab "
            + "deborah debora jael delilah dalila hannah hana peninnah ruth rut naomi noemi orpah orpa michal mical abigail abigail bathsheba "
            + "betsabe abishag jezebel jezabel athaliah atalia esther ester vashti vasti zeresh huldah hulda elisabeth elizabeth elisabet "
            + "mary maria martha marta anna ana joanna juana salome herodias sapphira safira rhoda rode priscilla priscila lydia dorcas "
            + "tabitha tabita bernice berenice drusilla drusila candace eunice lois claudia phebe tryphena persis hephzibah tirzah "
            + "milcah bilhah zilpah keturah asenath shiphrah puah jochebed elisheba gomer abital maacah naamah serah hodesh").split(" ")));

    /** Tagalog (Ang Biblia) and other spellings to the English name: one person, one voice. */
    private static final Map<String, String> SAME = new HashMap<>();

    static {
        String[] p = {
                "pedro", "peter", "juan", "john", "santiago", "james", "mateo", "matthew", "marcos", "mark", "lucas", "luke", "pablo", "paul",
                "bernabe", "barnabas", "timoteo", "timothy", "felipe", "philip", "andres", "andrew", "tomas", "thomas", "natanael", "nathanael",
                "nicodemo", "nicodemus", "lazaro", "lazarus", "pilato", "pilate", "herodes", "herod", "caifas", "caiaphas", "moises", "moses",
                "josue", "joshua", "gedeon", "gideon", "sanson", "samson", "salomon", "solomon", "elias", "elijah", "eliseo", "elisha",
                "isaias", "isaiah", "jeremias", "jeremiah", "ezequiel", "ezekiel", "jonas", "jonah", "oseas", "hosea", "miqueas", "micah",
                "esdras", "ezra", "nehemias", "nehemiah", "noe", "noah", "faraon", "pharaoh", "balac", "balak", "jonatan", "jonathan",
                "jose", "joseph", "jacobo", "jacob", "ana", "hannah", "agar", "hagar", "sara", "sarah", "eba", "eve", "rebeka", "rebekah",
                "rebecca", "rebekah", "raquel", "rachel", "lea", "leah", "dina", "dinah", "sefora", "zipporah", "debora", "deborah",
                "dalila", "delilah", "rut", "ruth", "noemi", "naomi", "mical", "michal", "betsabe", "bathsheba", "jezabel", "jezebel",
                "atalia", "athaliah", "ester", "esther", "vasti", "vashti", "elisabet", "elisabeth", "elizabeth", "elisabeth", "maria", "mary",
                "marta", "martha", "safira", "sapphira", "rode", "rhoda", "priscila", "priscilla", "tabita", "tabitha", "berenice", "bernice",
                "drusila", "drusilla", "hulda", "huldah", "esau", "esau"};
        for (int i = 0; i + 1 < p.length; i += 2) SAME.put(p[i], p[i + 1]);
    }

    /** The name the person is known by in English (lower case); the same for the Tagalog spelling. */
    static String canonical(String key) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT).trim();
        String s = SAME.get(k);
        return s != null ? s : k;
    }

    /** A Bible person this list knows by name (Moses, Pedro, Sarah). */
    static boolean known(String key) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT).trim();
        return SAME.containsKey(k) || SAME.containsValue(k) || WOMEN.contains(k);
    }

    static boolean isWoman(String key) {
        return WOMEN.contains(canonical(key));
    }

    /**
     * The voice slot of this person (0 to 11), the same wherever they are met; the next free one when another person of this page has it.
     * `used` holds the slots the page's other speakers have.
     */
    static int slot(String key, Set<Integer> used) {
        String k = canonical(key);
        int h = 0x811C9DC5;
        for (int i = 0; i < k.length(); i++) {
            h ^= k.charAt(i);
            h *= 16777619;
        }
        int slot = Math.floorMod(h, 12);
        for (int tries = 0; tries < 12 && used.contains(slot); tries++) slot = (slot + 1) % 12;
        return slot;
    }
}
