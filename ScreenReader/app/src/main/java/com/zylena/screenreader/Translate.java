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

    private static int onlineFails;
    private static long onlineOffUntil;
    private static final java.util.concurrent.ExecutorService net = java.util.concurrent.Executors.newSingleThreadExecutor();

    static void text(Context c, String text, String fromLang, Done out) {
        if (System.currentTimeMillis() < onlineOffUntil || !Net.online(c)) {
            offline(c, text, fromLang, out);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        net.execute(() -> {
            String said = null;
            try {
                said = google(c, text, TextPrep.FIL.equals(fromLang) ? "tl" : "en", TextPrep.FIL.equals(fromLang) ? "en" : "tl");
            } catch (Exception e) {
                Logs.d(c, "translate online failed: " + e);
            }
            final String got = said;
            main.post(() -> {
                if (got != null && !got.isEmpty()) {
                    onlineFails = 0;
                    out.done(got);
                    return;
                }
                // Not answering: offline for a while, rather than waiting on every paragraph.
                if (++onlineFails >= 2) {
                    onlineOffUntil = System.currentTimeMillis() + 10 * 60_000L;
                    onlineFails = 0;
                    Logs.d(c, "translate: online not answering, offline for 10 minutes");
                }
                offline(c, text, fromLang, out);
            });
        });
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

    private static void offline(Context c, String text, String fromLang, Done out) {
        boolean fil = TextPrep.FIL.equals(fromLang);
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
