package com.zylena.screenreader;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Invisible. Lets the quick-settings panel close, then asks the service to read whatever is behind it.
 * With "clip", it reads the copied text instead (Android only shares the clipboard with the app in front).
 */
public class ReadNowActivity extends Activity {
    static final String EXTRA_CLIP = "clip";
    private boolean clip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        clip = getIntent().getBooleanExtra(EXTRA_CLIP, false);
        if (clip) return; // wait for focus, then read the clipboard
        ReaderService s = ReaderService.instance;
        if (s != null) {
            s.readScreenSoon(700);
        } else {
            Toast.makeText(this, "Turn on ZYLENA Screen Reader in Accessibility first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!clip || !hasFocus) return;
        clip = false;
        readClipboard(this);
        finish();
    }

    /** Reads what's on the clipboard out loud; the activity must have focus. */
    static void readClipboard(Activity a) {
        String text = "";
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(a);
                if (t != null) text = t.toString().trim();
            }
        } catch (Exception ignored) {
        }
        if (text.isEmpty()) {
            Toast.makeText(a, "Nothing copied yet. Copy some text first.", Toast.LENGTH_LONG).show();
            return;
        }
        Speaker.get(a).readCopied(text);
    }
}
