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
 * English to Tagalog and back with Google's on-device translator. The language pack downloads once
 * (about 30 MB); after that it works offline and nothing leaves the phone.
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

    static void text(Context c, String text, String fromLang, Done out) {
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
