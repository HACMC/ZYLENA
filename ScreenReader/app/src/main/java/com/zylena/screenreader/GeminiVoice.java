package com.zylena.screenreader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reads aloud with Gemini's speech models and the user's own AI key: a natural voice that reads a whole
 * paragraph the way a person would, Tagalog and English alike. Each paragraph is asked for ahead of time and
 * played in order. When Gemini can't (no internet, the free allowance used up, too slow), the paragraphs go
 * back to the phone's voice, and Gemini is left alone for a while.
 */
final class GeminiVoice {
    /** What the reader hears back: a paragraph began, the last one ended, or these go to the phone's voice. */
    interface Events {
        void started(int gen, int run);

        void finished(int gen);

        void toPhone(Item item);
    }

    static final class Item {
        final int gen, run;
        final String text;
        final boolean last;
        final long breathMs;
        final float rate;
        String voice;     // a voice to try (the voice picker's Test); null: the chosen one
        Future<byte[]> audio;

        Item(int gen, int run, String text, boolean last, long breathMs, float rate) {
            this.gen = gen;
            this.run = run;
            this.text = text;
            this.last = last;
            this.breathMs = breathMs;
            this.rate = rate;
        }
    }

    private static final int RATE = 24000; // Gemini speaks 16-bit mono PCM at 24 kHz
    private static final int AHEAD = 2;    // paragraphs asked for while one plays
    private static volatile long offUntil;
    private static volatile String model;

    private final Context ctx;
    private final Events events;
    private final java.util.function.IntSupplier currentGen;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final ExecutorService fetch = Executors.newFixedThreadPool(3);
    private final java.util.LinkedHashMap<String, byte[]> saved = new java.util.LinkedHashMap<String, byte[]>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, byte[]> e) {
            return size() > 6;
        }
    };
    private Thread worker;
    private AudioTrack track;
    private volatile long written, startedAt; // frames given to the track since it was made
    private volatile boolean busy;

    GeminiVoice(Context c, Events e, java.util.function.IntSupplier gen) {
        ctx = c.getApplicationContext();
        events = e;
        currentGen = gen;
    }

    /** Why it can't speak now (no key, offline, resting), or null when it can. */
    static String whyNot(Context c) {
        if (Prefs.aiKey(c).isEmpty()) return "Set the AI key first";
        if (!Net.online(c)) return "No internet";
        long rest = offUntil - System.currentTimeMillis();
        if (rest > 0) return "Gemini is resting for " + (rest / 60_000 + 1) + " min (busy or the free allowance is used up)";
        return null;
    }

    /** On, with a key, online, and not resting after a failure. */
    static boolean usable(Context c) {
        return Prefs.geminiVoice(c) && !Prefs.aiKey(c).isEmpty() && Net.online(c) && System.currentTimeMillis() >= offUntil;
    }

    boolean isPlaying() {
        AudioTrack t = track;
        try {
            return busy || (t != null && t.getPlaybackHeadPosition() < written);
        } catch (Exception e) {
            return false;
        }
    }

    void add(Item it) {
        synchronized (queue) {
            queue.add(it);
            askAhead();
            queue.notifyAll();
        }
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::work, "zylena-gemini-voice");
            worker.start();
        }
    }

    /** Stops at once and forgets what was waiting. */
    void stop() {
        synchronized (queue) {
            for (Item it : queue) if (it.audio != null) it.audio.cancel(true);
            queue.clear();
            queue.notifyAll();
        }
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.release();
            } catch (Exception ignored) {
            }
        }
        track = null;
        written = 0;
    }

    private void askAhead() {
        int n = 0;
        for (Item it : queue) {
            if (n++ >= AHEAD + 1) break;
            if (it.audio == null) {
                final String text = it.text, voice = it.voice != null ? it.voice : Prefs.geminiVoiceName(ctx);
                it.audio = fetch.submit(() -> audioFor(text, voice));
            }
        }
    }

    private void work() {
        while (true) {
            Item it;
            synchronized (queue) {
                while (queue.isEmpty()) {
                    try {
                        queue.wait(30_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (queue.isEmpty()) return; // nothing for a while: the thread ends, a new one starts with the next
                }
                it = queue.peek();
            }
            if (it.gen != currentGen.getAsInt()) {
                synchronized (queue) {
                    queue.remove(it);
                }
                continue;
            }
            busy = true;
            byte[] pcm = null;
            String why = null;
            try {
                if (it.audio == null) synchronized (queue) {
                    askAhead();
                }
                pcm = it.audio.get(25, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                why = String.valueOf(e.getCause() == null ? e : e.getCause().getMessage());
            } catch (Exception e) {
                why = "too slow";
            }
            if (it.gen != currentGen.getAsInt()) {
                busy = false;
                continue; // stopped meanwhile (stop() emptied the queue)
            }
            if (pcm == null || pcm.length == 0) {
                // Gemini can't now: this paragraph and all waiting ones go to the phone's voice, in order.
                boolean allowance = why != null && why.contains("429");
                offUntil = System.currentTimeMillis() + (allowance ? 15 * 60_000L : 3 * 60_000L);
                Logs.d(ctx, "Gemini voice: " + (why == null ? "no sound came" : why) + (allowance ? " (the free allowance is used up for now)" : "")
                        + ", the phone's voice reads for " + (allowance ? "15" : "3") + " minutes");
                java.util.List<Item> rest;
                synchronized (queue) {
                    rest = new java.util.ArrayList<>(queue);
                    queue.clear();
                }
                busy = false;
                waitPlayed(it.gen);
                for (Item r : rest) if (r.gen == it.gen) events.toPhone(r);
                continue;
            }
            synchronized (queue) {
                queue.remove(it);
                askAhead();
            }
            play(it, pcm);
            busy = false;
            if (it.last) {
                waitPlayed(it.gen);
                if (it.gen == currentGen.getAsInt()) events.finished(it.gen);
            }
        }
    }

    private void play(Item it, byte[] pcm) {
        AudioTrack t = track;
        if (t == null) {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min * 2, RATE)) // half a second
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            track = t;
            written = 0;
        }
        try {
            t.setPlaybackParams(new android.media.PlaybackParams().setSpeed(Math.max(0.5f, Math.min(2f, it.rate))).setPitch(1f));
        } catch (Exception ignored) {
        }
        if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) t.play();
        if (it.breathMs > 0) write(t, it.gen, new byte[(int) (RATE * it.breathMs / 1000) * 2]);
        events.started(it.gen, it.run);
        write(t, it.gen, pcm);
    }

    private void write(AudioTrack t, int gen, byte[] b) {
        int at = 0;
        while (at < b.length && gen == currentGen.getAsInt() && track == t) {
            int n = t.write(b, at, Math.min(8192, b.length - at));
            if (n <= 0) break;
            at += n;
            written += n / 2;
        }
    }

    /** Until what was written has been heard (or reading was stopped). */
    private void waitPlayed(int gen) {
        AudioTrack t = track;
        long until = System.currentTimeMillis() + 120_000;
        while (t != null && track == t && gen == currentGen.getAsInt() && System.currentTimeMillis() < until) {
            try {
                if (t.getPlaybackHeadPosition() >= written) return;
                Thread.sleep(50);
            } catch (Exception e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ Gemini

    private byte[] audioFor(String text, String voice) throws Exception {
        String key0 = voice + "|" + text;
        synchronized (saved) {
            byte[] b = saved.get(key0);
            if (b != null) return b;
        }
        String key = Prefs.aiKey(ctx);
        String m = pickModel(key);
        if (m == null) throw new java.io.IOException("this key has no Gemini voice");
        long t0 = System.currentTimeMillis();
        org.json.JSONObject body = new org.json.JSONObject()
                .put("contents", new org.json.JSONArray().put(new org.json.JSONObject()
                        // Only the words to say: an instruction in front was read aloud too.
                        .put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text", text)))))
                .put("generationConfig", new org.json.JSONObject()
                        .put("responseModalities", new org.json.JSONArray().put("AUDIO"))
                        .put("speechConfig", new org.json.JSONObject().put("voiceConfig", new org.json.JSONObject()
                                .put("prebuiltVoiceConfig", new org.json.JSONObject().put("voiceName", voice)))));
        java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent").openConnection();
        h.setConnectTimeout(6000);
        h.setReadTimeout(25000);
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json");
        h.setRequestProperty("x-goog-api-key", key); // the key goes only to Google, never in the log
        try {
            byte[] out = body.toString().getBytes("UTF-8");
            h.getOutputStream().write(out);
            int code = h.getResponseCode();
            if (code == 404) model = null; // that model is gone: look again next time
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = h.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            }
            org.json.JSONArray parts = new org.json.JSONObject(b.toString("UTF-8")).getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < parts.length(); i++) {
                org.json.JSONObject d = parts.getJSONObject(i).optJSONObject("inlineData");
                if (d != null) pcm.write(android.util.Base64.decode(d.getString("data"), android.util.Base64.DEFAULT));
            }
            byte[] audio = pcm.toByteArray();
            Prefs.addData(ctx, "data_voice", b.size()); // the answer's real size, counted with the voice's data
            Logs.d(ctx, "Gemini voice (" + m + ", " + voice + "): " + audio.length / 2 / RATE + " s of speech in "
                    + (System.currentTimeMillis() - t0) + " ms for " + text.length() + " letters");
            synchronized (saved) {
                saved.put(key0, audio);
            }
            return audio;
        } finally {
            h.disconnect();
        }
    }

    /** The newest Gemini speech model this key has ("…-tts"), plain ones before previews and lite ones. */
    private synchronized String pickModel(String key) {
        if (model != null) return model;
        String names;
        try {
            names = Translate.aiNames(key);
        } catch (Exception e) {
            return null;
        }
        String best = null;
        double bestRank = -1e9;
        for (String n : names.split(", ")) {
            if (!n.endsWith("-tts") || !n.startsWith("gemini-")) continue;
            double r = 0;
            java.util.regex.Matcher v = java.util.regex.Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)").matcher(n);
            if (v.find()) r += Double.parseDouble(v.group(1)) * 10;
            if (n.contains("preview")) r -= 3;
            if (n.contains("lite")) r -= 4;
            if (n.contains("pro")) r -= 6; // slower, and a smaller free allowance
            if (r > bestRank) {
                bestRank = r;
                best = n;
            }
        }
        if (best != null) Logs.d(ctx, "Gemini voice: using " + best);
        model = best;
        return best;
    }
}
