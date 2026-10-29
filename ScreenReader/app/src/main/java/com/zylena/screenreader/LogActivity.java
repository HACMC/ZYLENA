package com.zylena.screenreader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class LogActivity extends Activity {
    private TextView text;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.applyWindow(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(this, 16);
        col.setPadding(p, Theme.dp(this, 30), p, p);
        setContentView(col);

        col.addView(Theme.eyebrow(this, "Screen Reader"));
        col.addView(Theme.title(this, "Logs", 32));
        col.addView(Theme.body(this, "Logs include short snippets of the text that was read. Check them before sharing.",
                Theme.FAINT, 12), Theme.spaced(this, 2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = Theme.button(this, "Save .txt", true);
        Button share = Theme.button(this, "Share", false);
        Button copy = Theme.button(this, "Copy", false);
        Button clear = Theme.button(this, "Clear", false);
        for (Button x : new Button[]{save, share, copy, clear}) {
            x.setMinHeight(Theme.dp(this, 44));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(Theme.dp(this, 8));
            row.addView(x, lp);
        }
        col.addView(row, Theme.spaced(this, 12));

        scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTextColor(Theme.INK);
        text.setTextSize(11);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        scroll.addView(text);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        ((LinearLayout.LayoutParams) scroll.getLayoutParams()).topMargin = Theme.dp(this, 12);

        save.setOnClickListener(v -> saveTxt());
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "ZYLENA Screen Reader log")
                    .putExtra(Intent.EXTRA_TEXT, shareable());
            startActivity(Intent.createChooser(i, "Share log"));
        });
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("log", shareable()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        clear.setOnClickListener(v -> {
            Logs.clear(this);
            show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        show();
    }

    private String header() {
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return "ZYLENA Screen Reader " + ver + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android "
                + Build.VERSION.RELEASE + "\nphoneVoiceApp=" + (Prefs.ttsPackage(this).isEmpty() ? "default" : Prefs.ttsPackage(this))
                + " rate=" + Prefs.rate(this) + " pitch=" + Prefs.pitch(this)
                + " skipTimes=" + Prefs.skipTimestamps(this) + " resume=" + Prefs.resume(this)
                + " bibleRefs=" + Prefs.refMode(this)
                + " wholePage=" + Prefs.wholePage(this) + " serviceOn=" + (ReaderService.instance != null)
                + "\n\n";
    }

    private String shareable() {
        String all = Logs.read(this);
        // Only the newest part: older entries are rarely useful, and long pastes get cut off from the end.
        if (all.length() > 20_000) {
            all = all.substring(all.length() - 20_000);
            int nl = all.indexOf('\n');
            if (nl >= 0) all = "(older entries left out)\n" + all.substring(nl + 1);
        }
        return header() + all;
    }

    /** The whole log (not only the newest part) as a .txt file in Downloads, to attach where it's needed. */
    private void saveTxt() {
        String name = "ZYLENA-log-" + new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(new java.util.Date()) + ".txt";
        byte[] body = (header() + Logs.read(this)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ZYLENA");
                android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new java.io.IOException("no file");
                try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) {
                    o.write(body);
                }
                Toast.makeText(this, "Saved in Downloads/ZYLENA: " + name, Toast.LENGTH_LONG).show();
            } else {
                java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File f = new java.io.File(dir, name);
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                    o.write(body);
                }
                Toast.makeText(this, "Saved: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save the log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void show() {
        String all = Logs.read(this);
        text.setText(header() + (all.isEmpty() ? "(no log yet. Tap the gold button on a screen, then come back.)" : all));
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
