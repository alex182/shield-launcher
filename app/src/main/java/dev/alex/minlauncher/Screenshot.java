package dev.alex.minlauncher;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What the TV shows, as a PNG, for the config page's Screenshot button, or a
 * smaller JPEG for its live view.
 * Taken by the quick actions' accessibility service (Android 11's
 * takeScreenshot, allowed by canTakeScreenshot in quick_menu_service.xml), so
 * it sees every app and overlay, without the ambilight's screen capture.
 * Protected video (Netflix and the like) comes out black, as in any capture.
 */
final class Screenshot {

    private static final long TIMEOUT_MS = 5000;

    private Screenshot() { }

    /** Full size, lossless, for saving. Blocking: call off the main thread. */
    static byte[] png(AccessibilityService service) throws IOException {
        return take(service, Bitmap.CompressFormat.PNG, 100, 1);
    }

    /** Half size JPEG, a fraction of the PNG's time, for the page's live view. */
    static byte[] jpeg(AccessibilityService service) throws IOException {
        return take(service, Bitmap.CompressFormat.JPEG, 80, 2);
    }

    private static byte[] take(AccessibilityService service, Bitmap.CompressFormat format, int quality,
            int shrink) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw new IOException("Screenshots need Android 11");
        CountDownLatch done = new CountDownLatch(1);
        HardwareBuffer[] buffer = new HardwareBuffer[1];
        ColorSpace[] space = new ColorSpace[1];
        int[] error = {0};
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult r) {
                        buffer[0] = r.getHardwareBuffer();
                        space[0] = r.getColorSpace();
                        done.countDown();
                    }
                    @Override public void onFailure(int code) {
                        error[0] = code;
                        done.countDown();
                    }
                });
        try {
            if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new IOException("The TV didn't answer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        if (buffer[0] == null) throw new IOException(why(error[0]));

        Bitmap hw = Bitmap.wrapHardwareBuffer(buffer[0], space[0]);
        try {
            if (hw == null) throw new IOException("Could not read the screenshot");
            // A hardware bitmap can't be compressed; a software copy can.
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap small = shrink > 1
                    ? Bitmap.createScaledBitmap(sw, sw.getWidth() / shrink, sw.getHeight() / shrink, true) : sw;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                small.compress(format, quality, out);
                return out.toByteArray();
            } finally {
                if (small != sw) small.recycle();
                sw.recycle();
            }
        } finally {
            if (hw != null) hw.recycle();
            buffer[0].close();
        }
    }

    private static String why(int code) {
        switch (code) {
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                return "Too soon after the last one: try again in a second";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                return "Not allowed: turn the launcher's quick actions off and on again in Accessibility settings";
            default:
                return "Screenshot failed (" + code + ")";
        }
    }
}
