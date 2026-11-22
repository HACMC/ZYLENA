package com.zylena.screenreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Hear every voice": the same words in every kind of voice ZYLENA makes (God's, Jesus's, the Spirit's, Satan's and every
 * kind of character), each made exactly as when reading: the phone voice says the words into a file, the
 * sound is changed, and it is played the way reading plays it. At the top, the phone voice with nothing changed, to hear
 * what each change does. Each one played goes into the log with its numbers, so what sounds wrong can be found.
 */
public class VoiceTestActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private TextToSpeech tts;
    private boolean ttsReady;
    private AudioTrack track;

    private String lang = TextPrep.FIL;
    /** The phone voice for every test; empty: the one reading gives each (Jesus's, God's, the Spirit's). */
    private String voiceName = "";
    private boolean longText;
    private EditText sample;
    private TextView status;
    private Button langBtn, voiceBtn, textBtn;
    private Ui.Row jesusRow, useVoiceRow;
    private final Map<String, Ui.Row> rows = new HashMap<>();
    /** Bumped on every play and stop: a recording asked for before is not played. */
    private int playGen;
    private String playingId = "";

    private static final String SHORT_FIL = "Huwag ninyong isiping ako'y naparito upang sirain ang kautusan o ang mga propeta: "
            + "ako'y naparito hindi upang sirain, kundi upang ganapin.";
    private static final String SHORT_EN = "Think not that I am come to destroy the law, or the prophets: I am not come to destroy, but to fulfil.";

    // {id, name, what it is}. The Bible voices are said in the phone voice reading gives them; the rest in Jesus's.
    private static final String[][] BIBLE = {
            {"plain", "The phone voice, nothing changed", "Jesus's phone voice exactly as the phone says it. Compare the others with this one."},
            {"jesus", "Jesus, as reading says him now", "Exactly how reading says him, with the choice above: his own voice, God's, or the phone voice as it is"},
            {"jesus_own", "Jesus's own voice", "Natural, unhurried and clear, a little warm"},
            {"deep", "God the Father", "Deep and slow, as in a great hall"},
            {"spirit", "The Holy Spirit", "Soft and light: a still small voice"},
            {"villain", "Satan, the tempter", "Low and slow, with a growl and a hiss on the s"},
            {"demon", "Demons and unclean spirits", "Lower still, rough and torn"}};
    private static final String[][] CHARACTERS = {
            {"child", "A child"}, {"squeaky", "A squeaky voice"}, {"cute", "A cute voice"}, {"teen", "A teenager"}, {"elder", "An old person"},
            {"husky", "A husky voice"}, {"boss", "A mob boss"}, {"gruff", "A gruff voice"}, {"bass", "A deep bass"}, {"maid", "A maid"},
            {"butler", "A butler"}, {"maarte", "Maarte (sosyal, conyo)"}, {"gentle", "A gentle voice"}};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Logs.catchCrashes(this);
        Theme.applyWindow(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 16);
        col.setPadding(p, Theme.dp(this, 30), p, Theme.dp(this, 28));
        scroll.addView(col);
        setContentView(scroll);

        col.addView(Theme.eyebrow(this, "Voice"));
        col.addView(Theme.title(this, "Hear every voice", 32));
        col.addView(Theme.body(this, "Tap one to hear it. Each is made exactly as when reading: the phone voice says the words, then the "
                + "sound is changed. Start with \"nothing changed\" to hear the phone voice itself. Each one you play goes into the log "
                + "with its numbers: tell me which one sounds wrong and send the log.", Theme.MUTED, 13), Theme.spaced(this, 4));

        // What is said, in which language and phone voice.
        LinearLayout pick = new LinearLayout(this);
        langBtn = Theme.button(this, "", false);
        textBtn = Theme.button(this, "", false);
        for (Button x : new Button[]{langBtn, textBtn}) {
            x.setMinHeight(Theme.dp(this, 44));
            x.setTextSize(13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(Theme.dp(this, 8));
            pick.addView(x, lp);
        }
        col.addView(pick, Theme.spaced(this, 14));
        voiceBtn = Theme.button(this, "", false);
        voiceBtn.setMinHeight(Theme.dp(this, 44));
        voiceBtn.setTextSize(13);
        col.addView(voiceBtn, Theme.spaced(this, 8));
        langBtn.setOnClickListener(v -> {
            lang = lang.equals(TextPrep.FIL) ? TextPrep.EN : TextPrep.FIL;
            voiceName = "";
            fillText();
            refresh();
        });
        textBtn.setOnClickListener(v -> {
            longText = !longText;
            fillText();
            refresh();
        });
        voiceBtn.setOnClickListener(v -> pickVoice());

        sample = new EditText(this);
        sample.setTextColor(Theme.INK);
        sample.setTextSize(14);
        sample.setTypeface(Theme.sans(this));
        sample.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        sample.setMinLines(3);
        sample.setMaxLines(6);
        col.addView(sample, Theme.spaced(this, 8));

        Button stop = Theme.button(this, "■  Stop", false);
        stop.setOnClickListener(v -> stopAll(true));
        col.addView(stop, Theme.spaced(this, 8));
        status = Theme.body(this, "", Theme.GOLD_LIGHT, 13);
        col.addView(status, Theme.spaced(this, 8));

        col.addView(Ui.section(this, "Jesus"));
        LinearLayout jg = Ui.group(this);
        jesusRow = Ui.nav(this, R.drawable.ic_person, "Jesus's voice", "", this::pickJesus);
        Ui.add(jg, jesusRow.view);
        useVoiceRow = Ui.nav(this, R.drawable.ic_person, "Give Jesus this phone voice", "", this::useVoiceForJesus);
        Ui.add(jg, useVoiceRow.view);
        col.addView(jg);

        // A drama with every voice, one after another, each in its scene: what each sounds like in a story.
        col.addView(Ui.section(this, "Drama: every voice in one story"));
        LinearLayout dg = Ui.group(this);
        dramaRow = new Ui.Row(this, R.drawable.ic_play, "Play the drama", "A stormy night in a big house, then a dream: the storyteller and every kind of voice, each named on screen as it speaks");
        dramaRow.view.setOnClickListener(v -> playDrama());
        dramaRow.view.setContentDescription("Play the drama");
        Ui.add(dg, dramaRow.view);
        col.addView(dg);
        dramaNow = Theme.body(this, "", Theme.INK, 15);
        dramaNow.setPadding(Theme.dp(this, 6), Theme.dp(this, 10), Theme.dp(this, 6), 0);
        col.addView(dramaNow);

        col.addView(Ui.section(this, "Bible voices"));
        LinearLayout bg = Ui.group(this);
        for (String[] r : BIBLE) addRow(bg, r[0], r[1], r[2]);
        col.addView(bg);

        col.addView(Ui.section(this, "Characters"));
        LinearLayout cg = Ui.group(this);
        for (String[] r : CHARACTERS) addRow(cg, r[0], r[1], "A story's character of this kind");
        col.addView(cg);

        fillText();
        refresh();
        Speaker.get(this).whenSystemReady(this::refresh);
        String pkg = Prefs.ttsPackage(this);
        tts = new TextToSpeech(this, st -> main.post(() -> {
            ttsReady = st == TextToSpeech.SUCCESS;
            if (!ttsReady) {
                say("No voice engine found on this phone.");
                return;
            }
            tts.setAudioAttributes(Speaker.get(this).audioAttributes());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    if (id != null && id.startsWith("dr-")) main.post(() -> dramaDone(id, null));
                    else main.post(() -> done(id, null));
                }

                @Override
                public void onError(String id, int code) {
                    if (id != null && id.startsWith("dr-")) main.post(() -> dramaDone(id, Speaker.ttsError(code)));
                    else main.post(() -> done(id, Speaker.ttsError(code)));
                }

                @Override
                public void onError(String id) {
                    if (id != null && id.startsWith("dr-")) main.post(() -> dramaDone(id, "the voice could not make it"));
                    else main.post(() -> done(id, "the voice could not make it"));
                }
            });
        }), pkg.isEmpty() ? null : pkg);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAll(false);
    }

    @Override
    protected void onDestroy() {
        stopAll(false);
        if (tts != null) tts.shutdown();
        work.shutdownNow();
        super.onDestroy();
    }

    /** Work away from the main thread; none once the screen is closed. */
    private void background(Runnable r) {
        if (isFinishing() || isDestroyed()) return;
        try {
            work.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void addRow(LinearLayout group, String id, String name, String sub) {
        Ui.Row r = new Ui.Row(this, R.drawable.ic_play, name, sub);
        r.view.setOnClickListener(v -> play(id, name));
        r.view.setContentDescription("Play " + name);
        rows.put(id, r);
        Ui.add(group, r.view);
    }

    private void fillText() {
        boolean fil = lang.equals(TextPrep.FIL);
        sample.setText(fil ? SHORT_FIL : SHORT_EN);
        if (!longText) return;
        // A long part of Matthew 5, as when a lesson's reference is read: about as long as the longest piece reading records at once
        // (about a minute and a half), so a long recording can be heard as reading makes it.
        say("Getting Matthew 5 from the built-in Bible…");
        background(() -> {
            if (Bible.source == null) Bible.source = new VerseStore(getApplicationContext());
            StringBuilder sb = new StringBuilder();
            for (int v = 17; v <= 48; v++) {
                String t = null;
                try {
                    t = Bible.source.get(fil, 40, 5, v);
                } catch (Exception ignored) {
                }
                if (t == null) continue;
                if (sb.length() > 0 && sb.length() + t.length() > 1300) break;
                sb.append(sb.length() > 0 ? " " : "").append(t.trim());
            }
            String all = sb.toString();
            main.post(() -> {
                if (isFinishing() || !longText || fil != lang.equals(TextPrep.FIL)) return;
                if (!all.isEmpty()) sample.setText(all);
                say("");
            });
        });
    }

    private void refresh() {
        boolean fil = lang.equals(TextPrep.FIL);
        langBtn.setText(fil ? "Language: Tagalog" : "Language: English");
        textBtn.setText(longText ? "Text: a long part (Matthew 5)" : "Text: one verse");
        Voice jv = voiceFor("jesus_own");
        voiceBtn.setText("Phone voice: " + (voiceName.isEmpty() ? "as when reading" + (jv == null ? "" : " (" + label(jv) + " for Jesus)")
                : label(chosenVoice())));
        String jesus = Prefs.jesusVoice(this);
        jesusRow.setValue(jesus.equals("father") ? "God's" : jesus.equals("plain") ? "Unchanged" : "His own");
        jesusRow.setSub(jesus.equals("father") ? "Jesus speaks in God the Father's voice" : jesus.equals("plain")
                ? "Jesus is read in the phone voice as it is, with nothing changed" : "His own voice: natural, unhurried and clear");
        useVoiceRow.setSub(voiceName.isEmpty() ? "Pick a phone voice above first, then tap here to give it to Jesus when reading"
                : "Tap to read Jesus in " + label(chosenVoice()) + " from now on" + (jesus.equals("own") ? "" : " (when he has his own voice)"));
    }

    private void say(String s) {
        status.setText(s);
    }

    /** "Voice FIE" for a phone voice, without the guess at man or woman. */
    private static String label(Voice v) {
        return v == null ? "a voice no longer on the phone" : Speaker.friendly(v).replaceAll(" · likely.*", "");
    }

    // ------------------------------------------------------------------ which voice, which sound

    private Voice chosenVoice() {
        for (Voice v : Speaker.get(this).voicesFor(lang)) if (v.getName().equals(voiceName)) return Speaker.get(this).asRead(v);
        return null;
    }

    /** The phone voice a test is said in: the one picked above, else the one reading gives that speaker. */
    private Voice voiceFor(String id) {
        if (!voiceName.isEmpty()) {
            Voice v = chosenVoice();
            if (v != null) return v;
        }
        Speaker sp = Speaker.get(this);
        String code = id.equals("deep") ? Cast.DIVINE_CODE : id.equals("spirit") ? Cast.SPIRIT_CODE : Cast.JESUS_CODE;
        if (id.equals("jesus_own")) {
            // His own phone voice, whatever the choice for Jesus is now.
            String want = VoiceFx.customVoice(Speaker.divineStyle(lang, Cast.JESUS_CODE));
            if (want == null) want = VoiceFx.defaultVoice(Speaker.divineStyle(lang, Cast.JESUS_CODE));
            if (want != null) for (Voice v : sp.voicesFor(lang)) if (Speaker.base(v.getName()).equals(want)) return sp.asRead(v);
        }
        return sp.divineVoice(lang, code);
    }

    /** The sound change of a test, as when reading; null: nothing is changed. */
    private VoiceFx.Style styleFor(String id) {
        boolean fil = lang.equals(TextPrep.FIL);
        String jesusNow = Speaker.get(this).jesusStyle(lang);
        switch (id) {
            case "plain":
                return null;
            case "jesus":
                return jesusNow == null ? null : VoiceFx.get(jesusNow);
            case "jesus_own":
                return VoiceFx.get(fil ? "jesus_fil" : "jesus");
            case "deep":
                return VoiceFx.get(fil ? "deep_fil" : "deep");
            case "spirit":
                return VoiceFx.get(fil ? "spirit_fil" : "spirit");
            default:
                return VoiceFx.get(id);
        }
    }

    // ------------------------------------------------------------------ hearing it

    private void play(String id, String name) {
        if (!ttsReady) {
            say("The voice is still starting. Try again in a moment.");
            return;
        }
        Speaker sp = Speaker.get(this);
        if (sp.isSpeaking()) sp.pause(); // reading waits meanwhile
        stopAll(false);
        String text = TextPrep.withoutHalts(TextPrep.say(sample.getText().toString().trim(), lang));
        if (text.isEmpty()) return;
        final int gen = ++playGen;
        playingId = id;
        mark(id, "making…");
        Voice v = voiceFor(id);
        VoiceFx.Style st = styleFor(id);
        if (v != null) tts.setVoice(v);
        tts.setPitch(Prefs.pitch(this));
        float base = Prefs.rate(this) * (lang.equals(TextPrep.FIL) ? Prefs.rateFil(this) : 1f);
        String voice = v == null ? "the phone's default" : v.getName();
        if (st == null) {
            tts.setSpeechRate(base);
            say("Playing: " + name + " (nothing changed)");
            mark(id, "playing");
            Logs.d(this, "voice test: " + name + ", nothing changed, voice " + voice + ", " + text.length() + " letters");
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vt-plain:" + gen);
            return;
        }
        // Said at this speed, it comes out at the tempo wanted once the pitch is changed (as when reading).
        tts.setSpeechRate(base * st.tf / st.pf);
        say("Making the sound: " + name + "…" + (text.length() > 600 ? " (a long one takes a few seconds)" : ""));
        pending.put(gen, new Object[]{id, name, st, voice, text.length()});
        File f = new File(getCacheDir(), "voice-test.wav");
        if (tts.synthesizeToFile(text, null, f, "vt:" + gen) != TextToSpeech.SUCCESS) done("vt:" + gen, "the voice refused to record it");
    }

    private final Map<Integer, Object[]> pending = new HashMap<>();

    // ------------------------------------------------------------------ the drama

    private Ui.Row dramaRow;
    private TextView dramaNow;
    private int dramaAt = -1;

    // {who, kind of voice ("" the storyteller), m or f, the words}
    private static final String[][] DRAMA_FIL = {
            {"", "", "", "Gabi ng bagyo sa San Isidro. Kumulog nang malakas, at bumuhos ang ulan sa malaking bahay ni Don Vito."},
            {"Don Vito", "boss", "m", "Huli ka na naman, Luca. Ayoko sa lahat ang pinaghihintay ako."},
            {"Luca", "husky", "m", "Pasensya na po, boss. Sarado ang daan sa may tulay."},
            {"", "", "", "Biglang tumakbo papasok si Mia, ang pitong taong gulang na anak ng kusinera."},
            {"Mia", "child", "f", "Kuya, kuya! May aso sa labas, basang-basa!"},
            {"Tikboy", "squeaky", "m", "Ako na! Ako na ang kukuha sa kanya!"},
            {"Bea", "cute", "f", "Ang cute niya! Pwede ba natin siyang alagaan?"},
            {"Jomar", "teen", "m", "Ang ingay niyo naman. Nag-aaral ako, o."},
            {"Lola Rosa", "elder", "f", "Ay, mga bata talaga. Noong panahon ko, tahimik kami tuwing may bagyo."},
            {"Mang Berto", "gruff", "m", "Isara niyo ang pinto. Pumapasok ang hangin."},
            {"Kapitan Leon", "bass", "m", "Walang lalabas ngayong gabi. Delikado ang daan."},
            {"Yaya Carmen", "maid", "f", "Sir, handa na po ang hapunan. May mainit na sabaw."},
            {"Alfred", "butler", "m", "Inihanda ko na po ang silid ng mga panauhin, sir."},
            {"Steffi", "maarte", "f", "Oh my gosh, walang signal dito? Like, I can't even."},
            {"Ate Grace", "gentle", "f", "Halika rito, Mia. Huwag kang matakot, nandito lang ako."},
            {"", "", "", "Nang hatinggabi, nanaginip si Mia ng kuwentong binasa sa kanila ni Lola Rosa."},
            {"Satanas", "villain", "m", "Ibibigay ko sa iyo ang lahat ng ito, kung ikaw ay magpapatirapa at sasamba sa akin."},
            {"Jesus", "jesus", "m", "Lumayo ka, Satanas. Sa Panginoon mong Dios sasamba ka, at siya lamang ang iyong paglilingkuran."},
            {"Ang demonyo", "demon", "m", "Ano ang pakialam namin sa iyo, Jesus? Naparito ka ba upang kami ay pahirapan?"},
            {"Ang Dios Ama", "deep", "m", "Ito ang sinisinta kong Anak, na siya kong lubos na kinalulugdan."},
            {"Ang Espiritu Santo", "spirit", "m", "Ihiwalay ninyo sa akin si Bernabe at si Saulo, sa gawain na itinawag ko sa kanila."},
            {"", "", "", "Paggising ni Mia, tumigil na ang ulan. Ngumiti siya at niyakap ang aso."}};
    private static final String[][] DRAMA_EN = {
            {"", "", "", "A stormy night in San Isidro. Thunder rolled, and the rain poured down on Don Vito's great house."},
            {"Don Vito", "boss", "m", "You are late again, Luca. I do not like to be kept waiting."},
            {"Luca", "husky", "m", "I'm sorry, boss. The road by the bridge is closed."},
            {"", "", "", "Suddenly Mia, the cook's seven-year-old daughter, ran in."},
            {"Mia", "child", "f", "Look, look! There's a dog outside, and it's all wet!"},
            {"Tikboy", "squeaky", "m", "Me! Let me get him!"},
            {"Bea", "cute", "f", "He's so cute! Can we keep him?"},
            {"Jomar", "teen", "m", "Ugh, you're so loud. I'm trying to study here."},
            {"Lola Rosa", "elder", "f", "Oh, these children. In my day, we were quiet when a storm came."},
            {"Mang Berto", "gruff", "m", "Shut that door. The wind is coming in."},
            {"Captain Leon", "bass", "m", "Nobody goes out tonight. The roads are dangerous."},
            {"Carmen the maid", "maid", "f", "Dinner is ready, sir. There is hot soup."},
            {"Alfred the butler", "butler", "m", "I have prepared the guest room, sir."},
            {"Steffi", "maarte", "f", "Oh my gosh, there's no signal here? Like, I literally can't even."},
            {"Grace", "gentle", "f", "Come here, Mia. Don't be afraid, I'm right here."},
            {"", "", "", "At midnight, Mia dreamed of the story Lola Rosa had read to them."},
            {"Satan", "villain", "m", "All these things will I give thee, if thou wilt fall down and worship me."},
            {"Jesus", "jesus", "m", "Get thee hence, Satan: for it is written, Thou shalt worship the Lord thy God, and him only shalt thou serve."},
            {"A demon", "demon", "m", "What have we to do with thee, Jesus? Art thou come hither to torment us?"},
            {"God the Father", "deep", "m", "This is my beloved Son, in whom I am well pleased."},
            {"The Holy Spirit", "spirit", "m", "Separate me Barnabas and Saul for the work whereunto I have called them."},
            {"", "", "", "When Mia woke up, the rain had stopped. She smiled and hugged the dog."}};

    /** The drama: each line in turn, the storyteller in the usual voice and everyone else in their kind of voice. */
    private void playDrama() {
        if (!ttsReady) {
            say("The voice is still starting. Try again in a moment.");
            return;
        }
        Speaker sp = Speaker.get(this);
        if (sp.isSpeaking()) sp.pause();
        stopAll(false);
        Logs.d(this, "voice test: the drama, in " + (lang.equals(TextPrep.FIL) ? "Tagalog" : "English"));
        dramaRow.setValue("playing");
        dramaLine(playGen, 0);
    }

    private void dramaLine(int gen, int i) {
        String[][] drama = lang.equals(TextPrep.FIL) ? DRAMA_FIL : DRAMA_EN;
        if (gen != playGen || isFinishing()) return;
        if (i >= drama.length) {
            dramaAt = -1;
            dramaRow.setValue("");
            dramaNow.setText("The end. Tell me which voice to replace or remove, by the name shown (\"Mang Berto, gruff\").");
            return;
        }
        dramaAt = i;
        String[] l = drama[i];
        boolean fil = lang.equals(TextPrep.FIL);
        String kind = l[1];
        String name = kind.isEmpty() ? "Storyteller" : l[0] + "  \u00B7  " + kindName(kind);
        dramaNow.setText(name + "\n\u201C" + l[3] + "\u201D");
        Speaker sp = Speaker.get(this);
        Voice v;
        VoiceFx.Style st;
        if (kind.isEmpty()) {
            v = sp.usualVoice(lang);
            st = null;
        } else if (kind.equals("jesus") || kind.equals("deep") || kind.equals("spirit")) {
            String code = kind.equals("deep") ? Cast.DIVINE_CODE : kind.equals("spirit") ? Cast.SPIRIT_CODE : Cast.JESUS_CODE;
            v = sp.divineVoice(lang, code);
            String style = kind.equals("jesus") ? sp.jesusStyle(lang) : Speaker.divineStyle(lang, code);
            st = style == null ? null : VoiceFx.get(style);
        } else {
            List<Voice> pool = sp.castPool(lang, l[2].charAt(0));
            v = pool.isEmpty() ? sp.usualVoice(lang) : pool.get(0);
            st = VoiceFx.get(kind);
        }
        if (!voiceName.isEmpty() && !kind.isEmpty() && chosenVoice() != null) v = chosenVoice(); // the phone voice picked above, for every character
        if (v != null) tts.setVoice(v);
        tts.setPitch(Prefs.pitch(this));
        float base = Prefs.rate(this) * (fil ? Prefs.rateFil(this) : 1f);
        String text = TextPrep.withoutHalts(TextPrep.say(l[3], lang));
        if (st == null) {
            tts.setSpeechRate(base);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dr-p:" + gen + ":" + i);
            return;
        }
        tts.setSpeechRate(base * st.tf / st.pf);
        pending.put(gen, new Object[]{"drama", name, st, v == null ? "default" : v.getName(), text.length(), i});
        File f = new File(getCacheDir(), "voice-test.wav");
        if (tts.synthesizeToFile(text, null, f, "dr-r:" + gen + ":" + i) != TextToSpeech.SUCCESS) dramaNext(gen, i, 0);
    }

    /** The next line, after a pause as between two people speaking. */
    private void dramaNext(int gen, int i, long after) {
        main.postDelayed(() -> dramaLine(gen, i + 1), after + 350);
    }

    private static String kindName(String kind) {
        for (String[] r : BIBLE) if (r[0].equals(kind)) return r[1];
        for (String[] r : CHARACTERS) if (r[0].equals(kind)) return r[1].toLowerCase(Locale.ROOT);
        return kind.equals("jesus") ? "Jesus, as reading says him now" : kind;
    }

    /** A drama line's voice finished (or failed): plain speech goes on; a recording is changed, played, then it goes on. */
    private void dramaDone(String id, String error) {
        String[] p = id.split(":");
        int gen, i;
        try {
            gen = Integer.parseInt(p[1]);
            i = Integer.parseInt(p[2]);
        } catch (Exception e) {
            return;
        }
        if (gen != playGen) return;
        if (id.startsWith("dr-p:") || error != null) {
            if (error != null) Logs.d(this, "voice test: drama line " + i + " could not be made (" + error + ")");
            dramaNext(gen, i, 0);
            return;
        }
        Object[] q = pending.remove(gen);
        if (q == null) return;
        final VoiceFx.Style st = (VoiceFx.Style) q[2];
        final String name = (String) q[1], voice = (String) q[3];
        background(() -> {
            short[] pcm = null;
            int sr = 0;
            float[] stats = new float[5];
            try {
                File f = new File(getCacheDir(), "voice-test.wav");
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0, n;
                    while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                }
                int[] w = Speaker.wavInfo(b);
                if (w == null || w[3] < 400) throw new java.io.IOException("not a usable recording");
                sr = w[0];
                short[] raw = new short[w[3] / 2];
                java.nio.ByteBuffer.wrap(b, w[2], w[3]).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw);
                if (w[1] == 2) {
                    short[] mono = new short[raw.length / 2];
                    for (int k = 0; k < mono.length; k++) mono[k] = (short) ((raw[2 * k] + raw[2 * k + 1]) / 2);
                    raw = mono;
                }
                pcm = VoiceFx.process(raw, sr, st, st.level, st.presence, stats);
            } catch (Throwable e) {
                pcm = null;
            }
            final short[] out = pcm;
            final int rate = sr;
            main.post(() -> {
                if (gen != playGen || isFinishing()) return;
                if (out == null) {
                    dramaNext(gen, i, 0);
                    return;
                }
                try {
                    AudioTrack tr = new AudioTrack.Builder()
                            .setAudioAttributes(Speaker.get(this).audioAttributes())
                            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(out.length * 2).build();
                    tr.write(out, 0, out.length);
                    tr.setVolume(Math.min(1f, st.vol));
                    if (track != null) {
                        try {
                            track.release();
                        } catch (Exception ignored) {
                        }
                    }
                    track = tr;
                    tr.play();
                    Logs.d(this, "voice test: drama, " + name + ", voice " + voice + Speaker.levels(stats));
                    dramaNext(gen, i, out.length * 1000L / rate);
                } catch (Throwable e) {
                    dramaNext(gen, i, 0);
                }
            });
        });
    }

    /** The phone voice finished (or failed): a recording is changed and played; plain speech is just over. */
    private void done(String id, String error) {
        if (id == null || isFinishing() || isDestroyed()) return;
        int gen;
        try {
            gen = Integer.parseInt(id.substring(id.indexOf(':') + 1));
        } catch (Exception e) {
            return;
        }
        if (id.startsWith("vt-plain:")) {
            if (gen == playGen) {
                mark(playingId, "");
                say(error != null ? "The voice could not say it: " + error : "");
            }
            return;
        }
        Object[] p = pending.remove(gen);
        if (p == null || gen != playGen) return;
        final String rid = (String) p[0], name = (String) p[1], voice = (String) p[3];
        final VoiceFx.Style st = (VoiceFx.Style) p[2];
        final int letters = (Integer) p[4];
        if (error != null) {
            mark(rid, "");
            say("The voice could not make it: " + error + ". Reading reads such a line the plain way.");
            Logs.d(this, "voice test: " + name + " could not be made (" + error + "), voice " + voice + ", " + letters + " letters");
            return;
        }
        // Changed on a thread of its own, as reading does: a long one takes a few seconds.
        background(() -> {
            short[] pcm = null;
            int sr = 0;
            float[] stats = new float[5];
            String err = null;
            try {
                File f = new File(getCacheDir(), "voice-test.wav");
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0, n;
                    while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                }
                int[] w = Speaker.wavInfo(b);
                if (w == null || w[3] < 400) throw new java.io.IOException("not a usable recording");
                sr = w[0];
                short[] raw = new short[w[3] / 2];
                java.nio.ByteBuffer.wrap(b, w[2], w[3]).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw);
                if (w[1] == 2) {
                    short[] mono = new short[raw.length / 2];
                    for (int i = 0; i < mono.length; i++) mono[i] = (short) ((raw[2 * i] + raw[2 * i + 1]) / 2);
                    raw = mono;
                }
                pcm = VoiceFx.process(raw, sr, st, st.level, st.presence, stats);
            } catch (Throwable e) {
                err = String.valueOf(e);
            }
            final short[] out = pcm;
            final int rate = sr;
            final String er = err;
            main.post(() -> {
                if (gen != playGen || isFinishing()) return;
                if (out == null) {
                    mark(rid, "");
                    say("Could not change the sound: " + er);
                    Logs.d(this, "voice test: " + name + " could not be changed: " + er);
                    return;
                }
                playSound(gen, rid, name, st, voice, out, rate, stats);
            });
        });
    }

    /** Plays the changed sound the way reading does (the same kind of sound, the same loudness). */
    private void playSound(int gen, String id, String name, VoiceFx.Style st, String voice, short[] pcm, int sr, float[] stats) {
        try {
            AudioTrack tr = new AudioTrack.Builder()
                    .setAudioAttributes(Speaker.get(this).audioAttributes())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
            tr.write(pcm, 0, pcm.length);
            tr.setVolume(Math.min(1f, st.vol));
            track = tr;
            tr.play();
            float secs = pcm.length / (float) sr;
            mark(id, "playing");
            say(String.format(Locale.US, "Playing: %s (%.1f s)", name, secs));
            Logs.d(this, "voice test: " + name + " (" + VoiceFx.encode(st) + "), voice " + voice + ", " + Math.round(secs * 1000) + " ms, "
                    + sr + " Hz" + Speaker.levels(stats));
            main.postDelayed(() -> {
                if (gen != playGen) return;
                mark(id, "");
                say("");
            }, (long) (secs * 1000) + 200);
        } catch (Throwable e) {
            mark(id, "");
            say("Could not play it: " + e.getMessage());
            Logs.d(this, "voice test: " + name + " could not be played: " + e);
        }
    }

    private void mark(String id, String what) {
        for (Map.Entry<String, Ui.Row> e : rows.entrySet()) e.getValue().setValue(e.getKey().equals(id) ? what : "");
    }

    private void stopAll(boolean user) {
        playGen++;
        pending.clear();
        if (tts != null) tts.stop();
        if (track != null) {
            try {
                track.stop();
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
        mark("", "");
        if (dramaRow != null) dramaRow.setValue("");
        dramaAt = -1;
        if (user) say("");
    }

    // ------------------------------------------------------------------ choices

    private void pickVoice() {
        List<Voice> all = Speaker.get(this).voicesFor(lang);
        if (all.isEmpty()) {
            Toast.makeText(this, "No " + (lang.equals(TextPrep.FIL) ? "Tagalog" : "English") + " voice on this phone yet.", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> names = new ArrayList<>(), labels = new ArrayList<>();
        names.add("");
        labels.add("As when reading (each speaker's own)");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Voice v : all) {
            if (!seen.add(Speaker.base(v.getName()))) continue;
            names.add(v.getName());
            labels.add(Speaker.friendly(v) + "  ·  " + Speaker.base(v.getName()));
        }
        new AlertDialog.Builder(this)
                .setTitle("Phone voice for the test")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    voiceName = names.get(w);
                    refresh();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void pickJesus() {
        String[] ids = {"own", "father", "plain"};
        String[] labels = {"His own voice: natural, unhurried and clear", "God the Father's voice: deep and slow",
                "The phone voice as it is, nothing changed"};
        int now = java.util.Arrays.asList(ids).indexOf(Prefs.jesusVoice(this));
        new AlertDialog.Builder(this)
                .setTitle("Jesus's voice")
                .setSingleChoiceItems(labels, now, (d, w) -> {
                    Prefs.setJesusVoice(this, ids[w]);
                    Logs.d(this, "Jesus's voice: " + ids[w]);
                    d.dismiss();
                    refresh();
                    play("jesus", "Jesus, as reading says him now");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** The phone voice picked above becomes Jesus's when reading (his own numbers stay as they are). */
    private void useVoiceForJesus() {
        Voice v = chosenVoice();
        if (v == null) {
            Toast.makeText(this, "Pick a phone voice first (the Phone voice button above).", Toast.LENGTH_LONG).show();
            return;
        }
        String name = lang.equals(TextPrep.FIL) ? "jesus_fil" : "jesus";
        VoiceFx.setCustom(this, name, VoiceFx.get(name), Speaker.base(v.getName()));
        Logs.d(this, "Jesus's phone voice (" + name + "): " + Speaker.base(v.getName()));
        Toast.makeText(this, "Jesus is read in " + label(v) + " from now on" + (Prefs.jesusVoice(this).equals("own") ? "." : ", when he has his own voice."),
                Toast.LENGTH_LONG).show();
        refresh();
    }
}
