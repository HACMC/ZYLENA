package com.zylena.screenreader;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Joins the per-paragraph WAV files the voice made into one file the user can keep or share. */
final class Audio {
    private Audio() {
    }

    private static final class Pcm {
        int rate, channels;
        short[] samples; // mono 16-bit
    }

    /** Returns where the file went, e.g. "Download/ZYLENA/ZYLENA-20261024-2130.wav". */
    static String join(Context c, File dir, int count) throws Exception {
        int rate = 0;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        short[] gap = null;
        for (int i = 0; i < count; i++) {
            File f = new File(dir, String.format(Locale.US, "%04d.wav", i));
            if (!f.exists() || f.length() < 44) continue;
            Pcm p = read(f);
            if (p == null) continue;
            if (rate == 0) {
                rate = p.rate;
                gap = new short[rate * 35 / 100]; // a short breath between paragraphs
            }
            short[] s = p.rate == rate ? p.samples : resample(p.samples, p.rate, rate);
            if (all.size() > 0) write(all, gap);
            write(all, s);
            f.delete();
        }
        if (rate == 0) throw new Exception("the voice made no sound");
        byte[] pcm = all.toByteArray();
        byte[] wav = header(pcm.length, rate);

        String name = "ZYLENA-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".wav";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ZYLENA");
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("Downloads isn't available");
            try (OutputStream o = c.getContentResolver().openOutputStream(uri)) {
                o.write(wav);
                o.write(pcm);
            }
            return "Download/ZYLENA/" + name;
        }
        File out = new File(c.getExternalFilesDir(Environment.DIRECTORY_MUSIC), name);
        try (OutputStream o = new FileOutputStream(out)) {
            o.write(wav);
            o.write(pcm);
        }
        return out.getAbsolutePath();
    }

    private static void write(ByteArrayOutputStream out, short[] s) {
        ByteBuffer b = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short x : s) b.putShort(x);
        out.write(b.array(), 0, b.capacity());
    }

    /** Reads a 16-bit PCM WAV into mono samples; null if it's some other format. */
    private static Pcm read(File f) throws Exception {
        byte[] data;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            data = o.toByteArray();
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 12 || !new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")) return null;
        Pcm p = new Pcm();
        int bits = 16, pos = 12;
        while (pos + 8 <= data.length) {
            String id = new String(data, pos, 4, StandardCharsets.US_ASCII);
            int len = b.getInt(pos + 4);
            int body = pos + 8;
            if (id.equals("fmt ")) {
                p.channels = b.getShort(body + 2);
                p.rate = b.getInt(body + 4);
                bits = b.getShort(body + 14);
            } else if (id.equals("data")) {
                if (bits != 16 || p.rate <= 0) return null;
                int end = (len <= 0 || body + len > data.length) ? data.length : body + len; // streams may leave the size at 0
                int ch = Math.max(1, p.channels);
                int frames = (end - body) / (2 * ch);
                p.samples = new short[frames];
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int k = 0; k < ch; k++) sum += b.getShort(body + (i * ch + k) * 2);
                    p.samples[i] = (short) (sum / ch);
                }
                return p;
            }
            pos = body + Math.max(0, len) + (len & 1);
        }
        return null;
    }

    private static short[] resample(short[] in, int from, int to) {
        int n = (int) ((long) in.length * to / from);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double x = (double) i * from / to;
            int a = (int) x;
            int bIdx = Math.min(a + 1, in.length - 1);
            double t = x - a;
            out[i] = (short) Math.round(in[Math.min(a, in.length - 1)] * (1 - t) + in[bIdx] * t);
        }
        return out;
    }

    private static byte[] header(int pcmBytes, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcmBytes).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcmBytes);
        return b.array();
    }
}
