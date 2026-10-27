package com.zylena.screenreader;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Quick-settings tile: read the text you copied. */
public class ReadClipTileService extends TileService {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Override
    public void onClick() {
        Speaker sp = Speaker.get(this);
        if (sp.isSpeaking()) {
            sp.stop();
            return;
        }
        Intent i = new Intent(this, ReadNowActivity.class).putExtra(ReadNowActivity.EXTRA_CLIP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
