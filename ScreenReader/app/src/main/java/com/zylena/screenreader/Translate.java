package com.zylena.screenreader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

/**
 * English to Tagalog and back. With internet: Google Translate online, which translates better. Without
 * it, or when the online one doesn't answer: Google's on-device translator, whose language pack
 * downloads once (about 30 MB) and then works offline.
 */
final class Translate {
    interface Done {
        /** The translation, or null when it couldn't be done (no pack and no internet, say). */
        void done(String text);
    }

    private static Translator toFil, toEn;
    private static boolean told;

    private Translate() {
    }

    /** The other language: "en" becomes "fil" and "fil" becomes "en". */
    static String other(String lang) {
        return TextPrep.FIL.equals(lang) ? TextPrep.EN : TextPrep.FIL;
    }

    private static int onlineFails, waitMinutes;
    private static long onlineOffUntil;
    private static final java.util.concurrent.ExecutorService net = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Names a translator would translate as words ("White" becomes "puti"): kept as they are. */
    private static final java.util.regex.Pattern KEEP = java.util.regex.Pattern.compile(
            "\\b(?:(?:Mrs?\\.?|Missus|Mister|Elder|Pastor)\\s)?(?:(?:Ellen|E\\.?)\\s?G\\.?\\s?|James\\s|J\\.?\\s?)?White\\b");

    /** The Tagalog the reader respelled for its voice goes back to the usual spelling before it is translated. */
    private static final String[][] UNSAY = {
            {"Hesu Kristo", "Jesus Christ"}, {"Hesus", "Jesus"}, {"Kristo", "Christ"}, {"Huwan", "John"}, {"Hose", "Joseph"},
            {"Herusalem", "Jerusalem"}, {"Heremias", "Jeremiah"}, {"Hosue", "Joshua"}, {"Hudas", "Judas"}, {"Hudea", "Judea"},
            {"Huda", "Judah"}, {"Hordan", "Jordan"}, {"Hehoba", "Jehovah"}};

    private static String original(String text, String fromLang) {
        if (TextPrep.FIL.equals(fromLang)) {
            for (String[] u : UNSAY) text = text.replaceAll("(?<![\\p{L}-])" + u[0] + "(?![\\p{L}-])", u[1]);
        } else {
            text = text.replaceAll("\\bMissus\\b", "Mrs.").replaceAll("\\bMister\\b", "Mr.");
        }
        return text;
    }

    /** Taglish: names and these everyday English words stay English in the Tagalog. */
    private static final java.util.regex.Pattern CAPS = java.util.regex.Pattern.compile(
            "(?<=[\\p{Ll},;:] )\\p{Lu}[\\p{L}'\u2019-]*(?: \\p{Lu}[\\p{L}'\u2019-]*)*");
    private static final java.util.regex.Pattern LOAN = java.util.regex.Pattern.compile(
            "\\b(?:church|churches|Bible|prophecy|prophecies|prophet|prophets|message|messages|vision|visions|volume|experience|"
                    + "experiences|Sabbath|Christian|Christians|angel|angels|gospel|chapter|book|books|world|power|faith|grace|sin|sins|"
                    + "truth|love|life|time|people|family|work|test|study|lesson|prayer|pray|Spirit|mission|movement|doctrine|"
                    + "judgment|sanctuary|covenant|law|commandments|salvation|baptism|reform|publish|published|edition|"
                    + "page|pages|science|report|plan|point|error|proof|fact)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    static void text(Context c, String text, String fromLang, Done out) {
        text = original(text, fromLang);
        boolean toFil = !TextPrep.FIL.equals(fromLang);
        java.util.List<String> kept = new java.util.ArrayList<>();
        int strict = 0;
        String sent = text;
        if (toFil) {
            sent = protect(KEEP, sent, kept);
            strict = kept.size();
            if (Prefs.taglish(c)) {
                sent = protect(CAPS, sent, kept);
                sent = protect(LOAN, sent, kept);
            }
        }
        final String plain = text;
        final int must = strict;
        translate(c, sent, fromLang, got -> {
            if (got == null || kept.isEmpty()) {
                out.done(got);
                return;
            }
            String back = got;
            for (int i = 0; i < kept.size(); i++) {
                java.util.regex.Pattern ph = java.util.regex.Pattern.compile("ZQ\\s?" + (i + 1) + "\\s?X", java.util.regex.Pattern.CASE_INSENSITIVE);
                if (!ph.matcher(back).find()) {
                    // A name's marker was lost: take the plain translation instead. Other words just go missing.
                    if (i < must) {
                        translate(c, plain, fromLang, out);
                        return;
                    }
                    continue;
                }
                back = ph.matcher(back).replaceFirst(java.util.regex.Matcher.quoteReplacement(kept.get(i)));
            }
            out.done(toFil && must > 0 ? personMarkers(back) : back);
        });
    }

    /** A person's name takes si / ni / kay in Tagalog, where the translator saw only a marker and wrote ang / ng / sa. */
    static String personMarkers(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([Aa]ng|[Nn]g|[Ss]a) (?=" + KEEP.pattern() + ")").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String w = m.group(1), l = w.toLowerCase(java.util.Locale.ROOT);
            String r = l.equals("ang") ? "si" : l.equals("ng") ? "ni" : "kay";
            if (Character.isUpperCase(w.charAt(0))) r = Character.toUpperCase(r.charAt(0)) + r.substring(1);
            m.appendReplacement(sb, r + " ");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String protect(java.util.regex.Pattern p, String text, java.util.List<String> kept) {
        java.util.regex.Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            kept.add(m.group());
            m.appendReplacement(sb, "ZQ" + kept.size() + "X");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void translate(Context c, String text, String fromLang, Done out) {
        if (!Net.online(c)) {
            offline(c, text, fromLang, out);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        net.execute(() -> {
            String said = null;
            boolean limited = false, tried = false;
            String from = TextPrep.FIL.equals(fromLang) ? "tl" : "en", to = TextPrep.FIL.equals(fromLang) ? "en" : "tl";
            String key = Prefs.translateKey(c);
            if (!key.isEmpty()) {
                try {
                    said = cloud(c, key, text, from, to);
                } catch (Exception e) {
                    Logs.d(c, "translate with your Google key failed: " + e.getMessage());
                }
            }
            if (said == null && System.currentTimeMillis() >= onlineOffUntil) {
                tried = true;
                try {
                    said = google(c, text, from, to);
                } catch (Exception e) {
                    Logs.d(c, "translate online failed: " + e);
                    limited = String.valueOf(e.getMessage()).contains("HTTP 429");
                }
            }
            final boolean googleOk = said != null && !said.isEmpty();
            // Google did not answer: the free MyMemory translator, as far as its daily allowance goes.
            if (!googleOk && System.currentTimeMillis() >= memoryOffUntil) {
                try {
                    said = memory(c, text, from, to);
                    if (said != null && !toldMemory) {
                        toldMemory = true;
                        Logs.d(c, "translate: using the free MyMemory translator");
                    }
                } catch (Exception e) {
                    said = null;
                    Logs.d(c, "translate with MyMemory failed: " + e.getMessage());
                    memoryOffUntil = System.currentTimeMillis() + 10 * 60_000L;
                }
            }
            final String got = said;
            final boolean busy = limited, asked = tried;
            main.post(() -> {
                if (googleOk) {
                    onlineFails = 0;
                    waitMinutes = 0;
                } else if (busy) {
                    // Google says too many requests: respect it. 10 minutes, then 20, up to an hour if it keeps asking.
                    waitMinutes = Math.min(60, waitMinutes == 0 ? 10 : waitMinutes * 2);
                    onlineOffUntil = System.currentTimeMillis() + waitMinutes * 60_000L;
                    onlineFails = 0;
                    Logs.d(c, "translate: Google asks to slow down, leaving it for " + waitMinutes + " minutes");
                } else if (asked && ++onlineFails >= 2) {
                    // Not answering: leave it for a while, rather than waiting on every paragraph.
                    onlineOffUntil = System.currentTimeMillis() + 10 * 60_000L;
                    onlineFails = 0;
                    Logs.d(c, "translate: Google not answering, leaving it for 10 minutes");
                }
                if (got != null && !got.isEmpty()) out.done(got);
                else offline(c, text, fromLang, out);
            });
        });
    }

    private static volatile long memoryOffUntil;
    private static volatile boolean toldMemory;

    /**
     * MyMemory's free translator (api.mymemory.translated.net): no key, 500 bytes a request and a daily
     * allowance, so the text goes in sentence-sized parts. Null when the allowance is used up. Off the main thread.
     */
    private static String memory(Context c, String text, String from, String to) throws Exception {
        StringBuilder all = new StringBuilder();
        for (String part : parts(text, 440)) {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.mymemory.translated.net/get?langpair=" + from + "%7C" + to + "&q="
                            + java.net.URLEncoder.encode(part, "UTF-8")).openConnection();
            h.setConnectTimeout(4000);
            h.setReadTimeout(8000);
            h.setRequestProperty("User-Agent", "ZYLENAScreenReader (Android)");
            try {
                int code = h.getResponseCode();
                if (code == 429) {
                    memoryOffUntil = System.currentTimeMillis() + 6 * 3600_000L;
                    Logs.d(c, "translate: MyMemory's free allowance for today is used up");
                    return null;
                }
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = h.getInputStream()) {
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                }
                Net.translated(c, part.length() + b.size() + 900);
                org.json.JSONObject j = new org.json.JSONObject(b.toString("UTF-8"));
                String got = j.optJSONObject("responseData") == null ? "" : j.getJSONObject("responseData").optString("translatedText", "");
                if (!"200".equals(j.optString("responseStatus")) || got.toUpperCase(java.util.Locale.ROOT).contains("MYMEMORY WARNING")) {
                    memoryOffUntil = System.currentTimeMillis() + 6 * 3600_000L;
                    Logs.d(c, "translate: MyMemory's free allowance for today is used up");
                    return null;
                }
                if (got.trim().isEmpty()) return null;
                if (all.length() > 0) all.append(' ');
                all.append(android.text.Html.fromHtml(got.trim(), android.text.Html.FROM_HTML_MODE_LEGACY).toString());
            } finally {
                h.disconnect();
            }
        }
        return all.toString().trim();
    }

    /** The text in parts of at most max UTF-8 bytes, cut at sentence ends, else at commas, else at spaces. */
    static java.util.List<String> parts(String text, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String rest = text.trim();
        while (!rest.isEmpty()) {
            if (rest.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= max) {
                out.add(rest);
                break;
            }
            int end = Math.min(rest.length(), max);
            while (rest.substring(0, end).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > max) end -= 8;
            int cut = -1;
            for (int i = end - 1; i > end / 3 && cut < 0; i--)
                if (".!?".indexOf(rest.charAt(i)) >= 0 && i + 1 < rest.length() && rest.charAt(i + 1) == ' ') cut = i + 1;
            for (int i = end - 1; i > end / 3 && cut < 0; i--) if (",;:".indexOf(rest.charAt(i)) >= 0) cut = i + 1;
            for (int i = end - 1; i > 0 && cut < 0; i--) if (rest.charAt(i) == ' ') cut = i;
            if (cut < 0) cut = end;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        return out;
    }

    /** Old English words the phone's small translator trips over, in today's words. */
    private static final String[][] OLD = {
            {"brethren and sisters", "brothers and sisters"}, {"brethren", "brothers"}, {"saith", "says"}, {"unto", "to"},
            {"thou", "you"}, {"thee", "you"}, {"thy", "your"}, {"thine", "yours"}, {"ye", "you"}, {"hath", "has"},
            {"doth", "does"}, {"hast", "have"}, {"art", "are"}, {"shalt", "shall"}, {"wilt", "will"}, {"whence", "from where"},
            {"thither", "there"}, {"hither", "here"}, {"wherefore", "why"}, {"behold", "look"}, {"forcible", "powerful"},
            {"verily", "truly"}, {"cometh", "comes"}, {"giveth", "gives"}, {"knoweth", "knows"}, {"loveth", "loves"},
            {"maketh", "makes"}, {"sayeth", "says"}, {"goeth", "goes"}, {"doeth", "does"}, {"believeth", "believes"}};

    static String modern(String s) {
        for (String[] o : OLD) {
            if ("art".equals(o[0])) {
                s = s.replaceAll("\\b([Tt]hou|[Yy]ou) art\\b", "$1 are");
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b" + o[0] + "\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String r = Character.isUpperCase(m.group().charAt(0)) ? Character.toUpperCase(o[1].charAt(0)) + o[1].substring(1) : o[1];
                m.appendReplacement(sb, r);
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        return s;
    }

    /** Google Cloud Translation with the user's own key: the official, accurate one. Runs off the main thread. */
    private static String cloud(Context c, String key, String text, String from, String to) throws Exception {
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(
                "https://translation.googleapis.com/language/translate/v2").openConnection();
        h.setConnectTimeout(5000);
        h.setReadTimeout(8000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        h.setRequestProperty("X-goog-api-key", key);
        try {
            byte[] body = ("q=" + java.net.URLEncoder.encode(text, "UTF-8") + "&source=" + from + "&target=" + to + "&format=text").getBytes("UTF-8");
            h.getOutputStream().write(body);
            if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = h.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            }
            Net.translated(c, body.length + b.size() + 900);
            String got = new org.json.JSONObject(b.toString("UTF-8")).getJSONObject("data").getJSONArray("translations")
                    .getJSONObject(0).getString("translatedText").trim();
            return got.isEmpty() ? null : got;
        } finally {
            h.disconnect();
        }
    }

    /** Google Translate online (the free web one). Runs off the main thread. */
    private static String google(Context c, String text, String from, String to) throws Exception {
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=" + from + "&tl=" + to).openConnection();
        h.setConnectTimeout(4000);
        h.setReadTimeout(6000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        h.setRequestProperty("User-Agent", "ZYLENAScreenReader (Android)");
        try {
            byte[] body = ("q=" + java.net.URLEncoder.encode(text, "UTF-8")).getBytes("UTF-8");
            h.getOutputStream().write(body);
            if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = h.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            }
            Net.translated(c, body.length + b.size() + 900); // plus the request's and answer's headers
            // [[["translated part","original part",...], ...], ...]
            org.json.JSONArray parts = new org.json.JSONArray(b.toString("UTF-8")).getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                org.json.JSONArray p = parts.optJSONArray(i);
                if (p != null && !p.isNull(0)) sb.append(p.getString(0));
            }
            return sb.toString().trim();
        } finally {
            h.disconnect();
        }
    }

    private static void offline(Context c, String text0, String fromLang, Done out) {
        boolean fil = TextPrep.FIL.equals(fromLang);
        final String text = fil ? text0 : modern(text0);
        Translator t = translator(fil);
        Handler main = new Handler(Looper.getMainLooper());
        // Only the first time: the pack takes a while to come, so say what's going on.
        Runnable slow = () -> {
            if (told) return;
            told = true;
            Logs.d(c, "translate: downloading the language pack");
            Toast.makeText(c, "Downloading the translation pack (once, about 30 MB)…", Toast.LENGTH_LONG).show();
        };
        main.postDelayed(slow, 1500);
        t.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> {
                    main.removeCallbacks(slow);
                    t.translate(text)
                            .addOnSuccessListener(out::done)
                            .addOnFailureListener(e -> {
                                Logs.d(c, "translate failed: " + e.getMessage());
                                out.done(null);
                            });
                })
                .addOnFailureListener(e -> {
                    main.removeCallbacks(slow);
                    Logs.d(c, "translate: language pack not available: " + e.getMessage());
                    out.done(null);
                });
    }

    private static synchronized Translator translator(boolean fromFil) {
        if (fromFil) {
            if (toEn == null) toEn = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.TAGALOG).setTargetLanguage(TranslateLanguage.ENGLISH).build());
            return toEn;
        }
        if (toFil == null) toFil = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.TAGALOG).build());
        return toFil;
    }
}
