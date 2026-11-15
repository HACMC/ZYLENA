package com.zylena.screenreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.media.AudioAttributes;
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
import java.util.List;
import java.util.Locale;

/**
 * The voice lab: a place to try a kind of voice (God's, a child's, a boss's) with exact numbers, away from the real settings.
 * Nothing here changes how ZYLENA reads; the numbers can be copied and sent, and become the real ones when they are asked for.
 */
public class VoiceLabActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady;
    private AudioTrack track;
    private SharedPreferences lab;

    private String lang = TextPrep.EN;
    private String voiceName = "";
    private String preset = "deep";
    private float pf = 0.84f, tf = 0.84f, vib = 0f, husk = 0f, drive = 0f, vol = 1f, level = 0.17f, presence = 0f;
    private boolean dull;

    private EditText sample;
    private Button langBtn, voiceBtn, presetBtn, dullBtn;
    private TextView status, summary;
    private LinearLayout sliders;

    private static final String SAMPLE_EN = "In the beginning God created the heaven and the earth. And God said, Let there be light: and there was light.";
    private static final String SAMPLE_FIL = "Nang pasimula ay nilikha ng Dios ang langit at ang lupa. At sinabi ng Dios, Magkaroon ng liwanag: at nagkaroon ng liwanag.";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.applyWindow(this);
        lab = getSharedPreferences("voice_lab", MODE_PRIVATE); // apart from ZYLENA's own settings
        lang = lab.getString("lang", TextPrep.EN);
        voiceName = lab.getString("voice", "");
        preset = lab.getString("preset", "deep");
        load(preset, false);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 16);
        col.setPadding(p, Theme.dp(this, 30), p, Theme.dp(this, 28));
        scroll.addView(col);
        setContentView(scroll);

        col.addView(Theme.eyebrow(this, "Screen Reader"));
        col.addView(Theme.title(this, "Voice lab", 32));
        col.addView(Theme.body(this, "Try a kind of voice with exact numbers. Nothing here changes how ZYLENA reads. When it sounds right, "
                + "copy the numbers and send them to me.", Theme.FAINT, 13), Theme.spaced(this, 2));

        sample = new EditText(this);
        sample.setTextColor(Theme.INK);
        sample.setTextSize(14);
        sample.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        sample.setMinLines(3);
        sample.setText(lab.getString("text_" + lang, lang.equals(TextPrep.FIL) ? SAMPLE_FIL : SAMPLE_EN));
        col.addView(sample, Theme.spaced(this, 14));

        LinearLayout row1 = new LinearLayout(this);
        langBtn = Theme.button(this, "", false);
        voiceBtn = Theme.button(this, "", false);
        presetBtn = Theme.button(this, "", false);
        for (Button x : new Button[]{langBtn, voiceBtn}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(Theme.dp(this, 8));
            row1.addView(x, lp);
        }
        col.addView(row1, Theme.spaced(this, 10));
        col.addView(presetBtn, Theme.spaced(this, 8));
        langBtn.setOnClickListener(v -> pickLanguage());
        voiceBtn.setOnClickListener(v -> pickVoice());
        presetBtn.setOnClickListener(v -> pickPreset());

        sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.VERTICAL);
        col.addView(sliders, Theme.spaced(this, 14));

        LinearLayout row2 = new LinearLayout(this);
        Button play = Theme.button(this, "Play with these numbers", true);
        Button plain = Theme.button(this, "Plain voice", false);
        Button stop = Theme.button(this, "Stop", false);
        for (Button x : new Button[]{play, plain, stop}) {
            x.setMinHeight(Theme.dp(this, 44));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, x == play ? 2f : 1f);
            lp.setMarginEnd(Theme.dp(this, 8));
            row2.addView(x, lp);
        }
        col.addView(row2, Theme.spaced(this, 14));
        play.setOnClickListener(v -> play(true));
        plain.setOnClickListener(v -> play(false));
        stop.setOnClickListener(v -> stopAll());

        status = Theme.body(this, "", Theme.MUTED, 13);
        col.addView(status, Theme.spaced(this, 8));

        summary = new TextView(this);
        summary.setTextColor(Theme.INK);
        summary.setTextSize(12);
        summary.setTypeface(Typeface.MONOSPACE);
        summary.setTextIsSelectable(true);
        col.addView(summary, Theme.spaced(this, 14));

        LinearLayout row3 = new LinearLayout(this);
        Button copy = Theme.button(this, "Copy numbers", true);
        Button share = Theme.button(this, "Share", false);
        Button reset = Theme.button(this, "Start again from the preset", false);
        for (Button x : new Button[]{copy, share, reset}) {
            x.setMinHeight(Theme.dp(this, 44));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(Theme.dp(this, 8));
            row3.addView(x, lp);
        }
        col.addView(row3, Theme.spaced(this, 8));
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("voice lab", summaryText()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "ZYLENA voice lab numbers").putExtra(Intent.EXTRA_TEXT, summaryText()), "Share")));
        reset.setOnClickListener(v -> {
            load(preset, true);
            buildSliders();
            refresh();
        });

        buildSliders();
        refresh();
        // The phone's voices (for the list of base voices) may not be started yet.
        Speaker.get(this).whenSystemReady(this::refresh);
        String pkg = Prefs.ttsPackage(this);
        tts = new TextToSpeech(this, status -> main.post(() -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        if (id.startsWith("lab:")) main.post(() -> playRecording(id));
                    }

                    @Override
                    public void onError(String id) {
                        main.post(() -> say("The voice could not make the sound."));
                    }
                });
                refresh();
            } else {
                say("No voice engine found on this phone.");
            }
        }), pkg.isEmpty() ? null : pkg);
    }

    @Override
    protected void onDestroy() {
        stopAll();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the numbers

    /** The preset's numbers, or the ones last kept for it here. */
    private void load(String name, boolean fresh) {
        VoiceFx.Style s = VoiceFx.get(name);
        if (s == null) s = VoiceFx.get("deep");
        preset = name;
        pf = s.pf;
        tf = s.tf;
        vib = s.vib;
        husk = s.husk;
        drive = s.drive;
        vol = s.vol;
        dull = s.dull;
        level = s.level;
        presence = s.presence;
        if (!fresh && lab.contains("pf_" + name)) {
            pf = lab.getFloat("pf_" + name, pf);
            tf = lab.getFloat("tf_" + name, tf);
            vib = lab.getFloat("vib_" + name, vib);
            husk = lab.getFloat("husk_" + name, husk);
            drive = lab.getFloat("drive_" + name, drive);
            vol = lab.getFloat("vol_" + name, vol);
            dull = lab.getBoolean("dull_" + name, dull);
            level = lab.getFloat("level_" + name, level);
            presence = lab.getFloat("pres_" + name, presence);
        }
    }

    private void keep() {
        lab.edit().putString("lang", lang).putString("voice", voiceName).putString("preset", preset)
                .putString("text_" + lang, sample.getText().toString())
                .putFloat("pf_" + preset, pf).putFloat("tf_" + preset, tf).putFloat("vib_" + preset, vib).putFloat("husk_" + preset, husk)
                .putFloat("drive_" + preset, drive).putFloat("vol_" + preset, vol).putBoolean("dull_" + preset, dull)
                .putFloat("level_" + preset, level).putFloat("pres_" + preset, presence).apply();
    }

    private void buildSliders() {
        sliders.removeAllViews();
        LinearLayout g = Ui.group(this);
        Ui.add(g, Ui.slider(this, R.drawable.ic_eq, "Pitch (1.00 as it is; lower is deeper)", 0.60f, 1.50f, 0.01f, pf, "%.2f", v -> {
            pf = v;
            refresh();
        }));
        Ui.add(g, Ui.slider(this, R.drawable.ic_speed, "Tempo (1.00 as it is; lower is slower)", 0.60f, 1.30f, 0.01f, tf, "%.2f", v -> {
            tf = v;
            refresh();
        }));
        Ui.add(g, Ui.slider(this, R.drawable.ic_tune, "Tremble (an old voice shakes)", 0f, 0.06f, 0.002f, vib, "%.3f", v -> {
            vib = v;
            refresh();
        }));
        Ui.add(g, Ui.slider(this, R.drawable.ic_tune, "Breath (husky, soft)", 0f, 0.40f, 0.01f, husk, "%.2f", v -> {
            husk = v;
            refresh();
        }));
        Ui.add(g, Ui.slider(this, R.drawable.ic_tune, "Grit (a rough, gravelly edge)", 0f, 0.60f, 0.01f, drive, "%.2f", v -> {
            drive = v;
            refresh();
        }));
        Ui.add(g, Ui.slider(this, R.drawable.ic_eq, "Clarity (more upper-middle range, for understanding)", 0f, 1f, 0.05f, presence, "%.2f", v -> {
            presence = v;
            refresh();
        }));
        Ui.add(g, Ui.slider(this, R.drawable.ic_eq, "Level (how loud the sound is made; speech is 0.10 to 0.20)", 0.08f, 0.30f, 0.01f, level, "%.2f", v -> {
            level = v;
            refresh();
        }));
        Ui.add(g, Ui.slider(this, R.drawable.ic_eq, "Volume (the final volume)", 0.30f, 1f, 0.05f, vol, "%.2f", v -> {
            vol = v;
            refresh();
        }));
        dullBtn = Theme.button(this, "", false);
        dullBtn.setOnClickListener(v -> {
            dull = !dull;
            refresh();
        });
        g.addView(dullBtn);
        sliders.addView(g);
    }

    private String voiceLabel() {
        return voiceName.isEmpty() ? "the usual voice" : Speaker.base(voiceName);
    }

    private String summaryText() {
        StringBuilder sb = new StringBuilder("ZYLENA voice lab\n");
        sb.append("kind of voice (preset): ").append(preset).append('\n');
        sb.append("language: ").append(lang.equals(TextPrep.FIL) ? "Tagalog" : "English").append('\n');
        sb.append("base voice: ").append(voiceLabel()).append('\n');
        sb.append(String.format(Locale.US, "pitch %.2f, tempo %.2f, tremble %.3f, breath %.2f, grit %.2f%n", pf, tf, vib, husk, drive));
        sb.append(String.format(Locale.US, "clarity %.2f, level %.2f, volume %.2f, muffle %s%n", presence, level, vol, dull ? "on" : "off"));
        VoiceFx.Style s = VoiceFx.get(preset);
        if (s != null) {
            sb.append(String.format(Locale.US, "(the preset's own: pitch %.2f, tempo %.2f, tremble %.3f, breath %.2f, grit %.2f, volume %.2f, muffle %s)%n",
                    s.pf, s.tf, s.vib, s.husk, s.drive, s.vol, s.dull ? "on" : "off"));
        }
        return sb.toString().trim();
    }

    private void refresh() {
        langBtn.setText(lang.equals(TextPrep.FIL) ? "Language: Tagalog" : "Language: English");
        voiceBtn.setText("Voice: " + voiceLabel());
        presetBtn.setText("Kind of voice: " + preset + "  (tap to change)");
        if (dullBtn != null) dullBtn.setText("Muffle the high end (a heavy voice): " + (dull ? "on" : "off"));
        summary.setText(summaryText());
        if (sample != null) keep();
    }

    private void say(String t) {
        status.setText(t);
    }

    // ------------------------------------------------------------------ choices

    private void pickLanguage() {
        String[] names = {"English", "Tagalog"};
        new AlertDialog.Builder(this).setTitle("Language").setItems(names, (d, w) -> {
            keep();
            lang = w == 0 ? TextPrep.EN : TextPrep.FIL;
            voiceName = "";
            sample.setText(lab.getString("text_" + lang, lang.equals(TextPrep.FIL) ? SAMPLE_FIL : SAMPLE_EN));
            refresh();
        }).show();
    }

    private List<Voice> voices() {
        List<Voice> v = new ArrayList<>(Speaker.get(this).voicesFor(lang));
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Voice> out = new ArrayList<>();
        for (Voice x : v) if (seen.add(Speaker.base(x.getName()))) out.add(x);
        return out;
    }

    private void pickVoice() {
        List<Voice> list = voices();
        String[] names = new String[list.size() + 1];
        names[0] = "The usual voice";
        for (int i = 0; i < list.size(); i++) {
            char g = Speaker.get(this).genderOf(list.get(i));
            names[i + 1] = Speaker.base(list.get(i).getName()) + (g == 'm' ? " (man)" : g == 'f' ? " (woman)" : "");
        }
        new AlertDialog.Builder(this).setTitle("Base voice").setItems(names, (d, w) -> {
            voiceName = w == 0 ? "" : list.get(w - 1).getName();
            refresh();
        }).show();
    }

    private static final String[][] PRESET_NOTES = {
            {"deep", "God the Father (English)"}, {"deep_fil", "God the Father (Tagalog)"}, {"jesus", "Jesus (English)"},
            {"jesus_fil", "Jesus (Tagalog)"}, {"spirit", "Holy Spirit (English)"}, {"spirit_fil", "Holy Spirit (Tagalog)"},
            {"child", "a child"}, {"squeaky", "a squeaky voice"}, {"cute", "a cute voice"}, {"teen", "a teenager"}, {"elder", "an old person"},
            {"husky", "a husky voice"}, {"boss", "a mob boss"}, {"gruff", "a gruff voice"}, {"bass", "a deep bass"}, {"villain", "a villain / the tempter"},
            {"maid", "a maid"}, {"butler", "a butler"}, {"gentle", "a gentle voice"},
            {"feel_s", "a line SHOUTED"}, {"feel_a", "an angry line"}, {"feel_d", "a sad line"}, {"feel_f", "an afraid line"}, {"feel_l", "said while laughing"},
            {"feel_j", "a glad line"}, {"feel_w", "a whispered line"}, {"feel_t", "a tender line"}, {"feel_p", "a pleading line"}, {"feel_h", "a sigh"}};

    private void pickPreset() {
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String[] n : PRESET_NOTES) {
            if (VoiceFx.get(n[0]) == null) continue;
            ids.add(n[0]);
            names.add(n[0] + " — " + n[1]);
        }
        new AlertDialog.Builder(this).setTitle("Start from which kind of voice?").setItems(names.toArray(new String[0]), (d, w) -> {
            keep();
            load(ids.get(w), false);
            buildSliders();
            refresh();
        }).show();
    }

    // ------------------------------------------------------------------ hearing it

    private VoiceFx.Style style() {
        return new VoiceFx.Style("lab", pf, tf, vib, husk, drive, vol, dull);
    }

    private void stopAll() {
        if (tts != null) tts.stop();
        if (track != null) {
            try {
                track.stop();
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
    }

    private void play(boolean processed) {
        if (!ttsReady) {
            say("The voice engine is not ready yet.");
            return;
        }
        String text = sample.getText().toString().trim();
        if (text.isEmpty()) return;
        keep();
        stopAll();
        if (!voiceName.isEmpty()) {
            for (Voice v : Speaker.get(this).voicesFor(lang)) {
                if (v.getName().equals(voiceName)) {
                    tts.setVoice(v);
                    break;
                }
            }
        }
        float base = Prefs.rate(this) * (lang.equals(TextPrep.FIL) ? Prefs.rateFil(this) : 1f);
        tts.setPitch(1f);
        if (!processed) {
            tts.setSpeechRate(base);
            say("Playing the plain voice…");
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lab-plain");
            return;
        }
        // Said at this speed, it comes out at the tempo asked for once the pitch has been changed (as when reading).
        tts.setSpeechRate(base * tf / pf);
        say("Making the sound…");
        File f = new File(getCacheDir(), "voice-lab.wav");
        tts.synthesizeToFile(text, null, f, "lab:" + System.nanoTime());
    }

    private void playRecording(String id) {
        File f = new File(getCacheDir(), "voice-lab.wav");
        try {
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            int[] w = Speaker.wavInfo(b);
            if (w == null || w[3] < 400) throw new java.io.IOException("not a usable recording");
            int sr = w[0], ch = w[1];
            short[] pcm = new short[w[3] / 2];
            java.nio.ByteBuffer.wrap(b, w[2], w[3]).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            if (ch == 2) {
                short[] mono = new short[pcm.length / 2];
                for (int i = 0; i < mono.length; i++) mono[i] = (short) ((pcm[2 * i] + pcm[2 * i + 1]) / 2);
                pcm = mono;
            }
            pcm = VoiceFx.process(pcm, sr, style(), level, presence);
            AudioTrack tr = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
            tr.write(pcm, 0, pcm.length);
            tr.setVolume(Math.min(1f, vol));
            track = tr;
            tr.play();
            say(String.format(Locale.US, "Playing with these numbers (%.1f s)…", pcm.length / (float) sr));
        } catch (Throwable e) {
            say("Could not play it: " + e.getMessage());
        }
    }
}
