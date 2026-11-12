package dev.alex.minlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Asks for the screen capture token the ambilight needs and hands it to
 * {@link AmbilightService}. Only an activity can ask, so this one is
 * invisible and gone at once. With the PROJECT_MEDIA app op allowed (set by
 * {@code build.sh install}) the system answers without a dialog; otherwise
 * the TV shows "Start now?" once per start.
 */
public class AmbilightActivity extends Activity {

    private static final int REQUEST = 1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) return;   // recreated mid-request: the answer still comes
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(m.createScreenCaptureIntent(), REQUEST);
        } catch (ActivityNotFoundException e) {
            AmbilightService.failed("This device can't capture the screen");
            close();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST) return;
        if (result == RESULT_OK && data != null) {
            AmbilightService.capture(this, result, data);
        } else {
            AmbilightService.failed("Screen capture was refused. Allow it with: adb shell appops set "
                    + getPackageName() + " PROJECT_MEDIA allow (build.sh install does this)");
        }
        close();
    }

    private void close() {
        finish();
        overridePendingTransition(0, 0);
    }
}
