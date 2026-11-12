package dev.alex.minlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * The room ambilight: mirrors the screen into a small capture (MediaProjection,
 * {@value #CAPTURE_W} pixels wide), works out each LED's colour from the part
 * of the picture it faces ({@link Ambilight}), and streams it to the WLED
 * controllers over UDP (DNRGB, port 21324). WLED drops back to its own state
 * two seconds after the last packet, so stopping, or the screen going off,
 * hands the strips back by themselves.
 *
 * Started from the quick actions, the config page, or by itself when the
 * screen comes on ("Start with the TV"). The capture needs a MediaProjection
 * token, which only an activity can ask for ({@link AmbilightActivity});
 * {@code build.sh install} sets the PROJECT_MEDIA app op so the system grants
 * it without a dialog. Android 10+ also wants a foreground service of type
 * mediaProjection holding it, which is this.
 *
 * Protected video (Netflix, Disney+, most paid streaming) is black in any
 * capture, so the strips go dark for it; Jellyfin, YouTube, games and the
 * home screen come through.
 *
 * Test modes need no capture: a hue wheel round the picture's border, to see
 * that the room's layout matches, or one strip's ends and a running dot, to
 * find its first LED and check its count. They run on the page's unsaved
 * settings for a minute, then the ambilight carries on as before.
 *
 * Every frame's work happens on one worker thread; the main thread only
 * takes intents and screen on/off.
 */
public class AmbilightService extends Service {

    private static final String TAG = "Ambilight";
    static final String ACTION_CAPTURE = "dev.alex.minlauncher.ambilight.CAPTURE";
    static final String ACTION_TEST = "dev.alex.minlauncher.ambilight.TEST";
    static final String ACTION_STOP = "dev.alex.minlauncher.ambilight.STOP";
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    static final String EXTRA_CONFIG = "config", EXTRA_PATTERN = "pattern", EXTRA_STRIP = "strip";
    static final String PATTERN_HUE = "hue", PATTERN_CHASE = "chase";

    private static final int CAPTURE_W = 160;
    private static final long TEST_MS = 60_000;
    private static final long CHASE_PERIOD_MS = 4000;
    /** Bars must hold this long before the crop follows them. */
    private static final long BARS_MS = 700;
    /** The whole frame black this long: say so on the page (most likely protected video). */
    private static final long BLACK_MS = 3000;
    private static final int NOTIFICATION_ID = 7;
    private static final String CHANNEL = "ambilight";

    private enum Mode { OFF, CAPTURE, TEST }

    /** The one running instance, for the page and the quick actions; set and cleared on the main thread. */
    private static volatile AmbilightService instance;
    /** Why the last start failed, for the page; "" when it didn't. */
    private static volatile String lastError = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private Store store;

    // Main thread.
    private volatile MediaProjection projection;
    private volatile boolean wantCapture;      // on, whatever the screen is doing
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) worker.post(() -> stopCapture(true));
            else if (wantCapture && projection != null) worker.post(AmbilightService.this::startCapture);
        }
    };
    private final Runnable endTest = this::endTest;
    /** The latest start; a shutdown only stops the service if nothing came in after it. */
    private int lastStartId;

    // Worker thread from here on.
    private volatile Mode mode = Mode.OFF;
    private volatile boolean paused;    // capture on, screen off
    private volatile long since;
    private JSONObject config;
    private Ambilight.Layout layout;
    private Ambilight.Tuning tuning;
    private List<double[][]> map;
    private int frameMs;
    private String pattern = PATTERN_HUE;
    private int chaseStrip;
    private long testStarted;

    private float[][] target, shown;    // per strip, 3 floats a LED
    private volatile byte[][] out;      // per strip, what was last sent
    private volatile String[] stripErrors;
    private InetAddress[] addresses;
    private DatagramSocket socket;
    private long lastTick;

    private VirtualDisplay display;
    private ImageReader reader;
    private int cw, ch;
    private byte[] frame;
    private int[] sumR, sumG, sumB;
    private Ambilight.Bars bars;
    private volatile int[] crop;
    private int[][][] boxes;           // per strip, per LED
    private int[] cropBoxed;           // the crop the boxes were made for
    private long blackSince;
    private volatile boolean black;
    private volatile int fps;           // frames captured in the last second (only changes make frames)
    private volatile int average;       // the picture's average colour, for the page
    private int framesThisSecond;
    private long secondStarted;

    private final Runnable tick = this::tick;

    // ----------------------------------------------------------- controls

    /** Turns the ambilight on: straight away if the capture is already held, else via the activity. */
    static void start(Context c) {
        AmbilightService s = instance;
        if (s != null && s.projection != null) {
            send(c, new Intent(c, AmbilightService.class).setAction(ACTION_CAPTURE));
        } else {
            lastError = "";
            c.startActivity(new Intent(c, AmbilightActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        }
    }

    /** From {@link AmbilightActivity}, with the capture token. */
    static void capture(Context c, int code, Intent data) {
        send(c, new Intent(c, AmbilightService.class).setAction(ACTION_CAPTURE)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data));
    }

    static void stop(Context c) {
        if (instance != null) send(c, new Intent(c, AmbilightService.class).setAction(ACTION_STOP));
    }

    /** A test pattern on the strips, from the page's (maybe unsaved) settings; {@code strip} for the chase. */
    static void test(Context c, JSONObject config, String pattern, int strip) {
        send(c, new Intent(c, AmbilightService.class).setAction(ACTION_TEST)
                .putExtra(EXTRA_CONFIG, AmbilightConfig.normalize(config).toString())
                .putExtra(EXTRA_PATTERN, pattern).putExtra(EXTRA_STRIP, strip));
    }

    /** On: following the screen (even while it is off and paused) or testing. */
    static boolean isOn() {
        AmbilightService s = instance;
        return s != null && (s.wantCapture || s.mode == Mode.TEST);
    }

    static void failed(String why) {
        lastError = why;
        Log.w(TAG, why);
    }

    private static void send(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    // ---------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        thread = new HandlerThread(TAG);
        thread.start();
        worker = new Handler(thread.getLooper());
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // First, every time: a foreground service of type mediaProjection must
        // be up before the token is used, and within 5 s of startForegroundService.
        startForeground(NOTIFICATION_ID, notification());
        lastStartId = startId;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutDown();
        } else if (ACTION_CAPTURE.equals(action)) {
            onCapture(intent);
        } else if (ACTION_TEST.equals(action)) {
            onTest(intent);
        } else if (projection == null) {
            shutDown();   // restarted by the system without its token: nothing to do
        }
        return START_NOT_STICKY;
    }

    private void onCapture(Intent intent) {
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data != null && projection == null) {
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            try {
                projection = m.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data);
            } catch (RuntimeException e) {
                failed("Screen capture refused: " + e.getMessage());
            }
            if (projection == null) {
                if (lastError.isEmpty()) failed("Screen capture refused");
                if (mode != Mode.TEST) shutDown();
                return;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(AmbilightService.this::projectionStopped); }
            }, main);
        }
        if (projection == null) {
            // Asked to resume, but the token went meanwhile: ask for a new one.
            start(this);
            return;
        }
        lastError = "";
        wantCapture = true;
        JSONObject c = store.ambilight();
        boolean on = isInteractive();
        worker.post(() -> {
            if (mode == Mode.TEST) return;   // carries on after the test
            configure(c);
            mode = Mode.CAPTURE;
            since = System.currentTimeMillis();
            if (on) startCapture(); else paused = true;
        });
    }

    private void onTest(Intent intent) {
        JSONObject c;
        try { c = new JSONObject(intent.getStringExtra(EXTRA_CONFIG)); }
        catch (JSONException | NullPointerException e) { c = store.ambilight(); }
        JSONObject conf = c;
        String p = intent.getStringExtra(EXTRA_PATTERN);
        int strip = intent.getIntExtra(EXTRA_STRIP, 0);
        main.removeCallbacks(endTest);
        main.postDelayed(endTest, TEST_MS);
        worker.post(() -> {
            stopCapture(false);
            configure(conf);
            pattern = PATTERN_CHASE.equals(p) ? PATTERN_CHASE : PATTERN_HUE;
            chaseStrip = strip;
            mode = Mode.TEST;
            testStarted = SystemClock.uptimeMillis();
            since = System.currentTimeMillis();
            schedule();
        });
    }

    /** Back to following the screen if it was, else off. */
    private void endTest() {
        if (wantCapture && projection != null) {
            JSONObject c = store.ambilight();
            boolean on = isInteractive();
            worker.post(() -> {
                configure(c);
                mode = Mode.CAPTURE;
                since = System.currentTimeMillis();
                if (on) startCapture(); else paused = true;
            });
        } else {
            shutDown();
        }
    }

    private void projectionStopped() {
        projection = null;
        if (mode == Mode.TEST) { wantCapture = false; return; }
        shutDown();
    }

    private void shutDown() {
        main.removeCallbacks(endTest);
        wantCapture = false;
        worker.post(() -> {
            stopCapture(false);
            mode = Mode.OFF;
            out = null;
            if (socket != null) { socket.close(); socket = null; }
        });
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            worker.post(p::stop);   // after the display is released
        }
        int id = lastStartId;
        worker.post(() -> main.post(() -> {
            if (!stopSelfResult(id)) return;   // started again meanwhile: that start carries on
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        }));
    }

    @Override
    public void onDestroy() {
        instance = null;
        try { unregisterReceiver(screen); } catch (IllegalArgumentException ignored) { }
        main.removeCallbacks(endTest);
        worker.post(() -> {
            stopCapture(false);
            if (socket != null) socket.close();
        });
        if (projection != null) projection.stop();
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @SuppressWarnings("deprecation")
    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH ? pm.isInteractive() : pm.isScreenOn();
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.ambilight), NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.ambilight))
                .setContentText(getString(R.string.ambilight_running))
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------- worker thread

    /** Takes a layout: the LEDs' picture points, fresh colour buffers, the controllers' addresses. */
    private void configure(JSONObject c) {
        config = c;
        layout = AmbilightConfig.layout(c);
        tuning = AmbilightConfig.tuning(c);
        map = Ambilight.map(layout);
        frameMs = 1000 / Math.max(1, c.optInt("fps", AmbilightConfig.DEFAULT_FPS));
        int n = layout.strips.size();
        target = new float[n][];
        shown = new float[n][];
        byte[][] o = new byte[n][];
        String[] errors = new String[n];
        addresses = new InetAddress[n];
        for (int i = 0; i < n; i++) {
            Ambilight.Strip s = layout.strips.get(i);
            target[i] = new float[s.count * 3];
            shown[i] = new float[s.count * 3];
            o[i] = new byte[s.count * 3];
            if (s.host.isEmpty()) {
                errors[i] = "No WLED address";
                continue;
            }
            try {
                addresses[i] = InetAddress.getByName(s.host);
            } catch (IOException e) {
                errors[i] = "Can't find " + s.host;
            }
        }
        out = o;
        stripErrors = errors;
        boxes = null;
        cropBoxed = null;
        if (socket == null) {
            try { socket = new DatagramSocket(); }
            catch (IOException e) { failed("No UDP socket: " + e.getMessage()); }
        }
    }

    private void startCapture() {
        if (mode != Mode.CAPTURE || projection == null) return;
        paused = false;
        if (display == null) {
            Point real = new Point();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealSize(real);
            cw = CAPTURE_W;
            ch = Math.max(2, Math.round(CAPTURE_W * (float) real.y / real.x));
            reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onImage, worker);
            sumR = new int[(cw + 1) * (ch + 1)];
            sumG = new int[sumR.length];
            sumB = new int[sumR.length];
            bars = new Ambilight.Bars(cw, ch, Math.max(1, (int) (BARS_MS / Math.max(1, frameMs))));
            crop = new int[] {0, 0, cw, ch};
            blackSince = 0;
            black = false;
            try {
                display = projection.createVirtualDisplay(TAG, cw, ch,
                        getResources().getDisplayMetrics().densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
            } catch (RuntimeException e) {
                failed("Can't mirror the screen: " + e.getMessage());
                reader.close();
                reader = null;
                return;
            }
            Log.i(TAG, "capturing " + cw + "x" + ch + " for " + layout.strips.size() + " strips");
        }
        schedule();
    }

    /** {@code pause}: the screen went off; it picks up again when it comes on. */
    private void stopCapture(boolean pause) {
        if (pause && mode == Mode.CAPTURE) paused = true;
        if (pause && mode == Mode.TEST) return;   // a test runs on, screen or not
        worker.removeCallbacks(tick);
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        fps = 0;
    }

    private void schedule() {
        worker.removeCallbacks(tick);
        lastTick = SystemClock.uptimeMillis();
        worker.post(tick);
    }

    /** A captured frame: each LED's target colour. Sending happens on the tick. */
    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); }
        catch (IllegalStateException e) { return; }
        if (img == null) return;
        try {
            if (mode != Mode.CAPTURE) return;
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int stride = plane.getRowStride();
            if (frame == null || frame.length < stride * ch) frame = new byte[stride * ch];
            buf.rewind();
            buf.get(frame, 0, Math.min(buf.remaining(), frame.length));
            onFrame(stride);
        } finally {
            img.close();
        }
        long now = SystemClock.uptimeMillis();
        framesThisSecond++;
        if (now - secondStarted >= 1000) {
            fps = framesThisSecond;
            framesThisSecond = 0;
            secondStarted = now;
        }
    }

    private void onFrame(int stride) {
        int[] found = Ambilight.content(frame, cw, ch, stride);
        long now = SystemClock.uptimeMillis();
        if (found == null) {
            if (blackSince == 0) blackSince = now;
            black = now - blackSince > BLACK_MS;
        } else {
            blackSince = 0;
            black = false;
        }
        int[] c = config.optBoolean("blackBars", true) ? bars.update(found) : new int[] {0, 0, cw, ch};
        crop = c;
        if (boxes == null || !java.util.Arrays.equals(c, cropBoxed)) {
            double depth = tuning.edgeDepth / 100.0;
            boxes = new int[map.size()][][];
            for (int s = 0; s < map.size(); s++) {
                double[][] m = map.get(s);
                boxes[s] = new int[m.length][];
                for (int i = 0; i < m.length; i++) boxes[s][i] = Ambilight.box(m[i][0], m[i][1], c, depth);
            }
            cropBoxed = c;
        }
        Ambilight.integrate(frame, cw, ch, stride, sumR, sumG, sumB);
        float[] avg = new float[3], edge = new float[3], col = new float[3];
        Ambilight.average(sumR, sumG, sumB, cw, c, avg);
        average = (Math.round(avg[0]) << 16) | (Math.round(avg[1]) << 8) | Math.round(avg[2]);
        for (int s = 0; s < map.size(); s++) {
            double[][] m = map.get(s);
            float[] t = target[s];
            for (int i = 0; i < m.length; i++) {
                Ambilight.average(sumR, sumG, sumB, cw, boxes[s][i], edge);
                Ambilight.colour(edge, avg, m[i][2], tuning, col);
                t[i * 3] = col[0]; t[i * 3 + 1] = col[1]; t[i * 3 + 2] = col[2];
            }
        }
    }

    /** Every frame interval while on: test colours, the fade, then out to WLED. */
    private void tick() {
        if (mode == Mode.OFF || (mode == Mode.CAPTURE && paused)) return;
        long now = SystemClock.uptimeMillis();
        long dt = Math.max(1, now - lastTick);
        lastTick = now;
        int tau = tuning.smoothingMs;
        if (mode == Mode.TEST) {
            testTargets(now);
            tau = 0;
        }
        byte[][] o = out;
        String[] errors = stripErrors;
        for (int s = 0; s < target.length; s++) {
            Ambilight.smooth(shown[s], target[s], dt, tau);
            byte[] b = o[s];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Math.round(shown[s][i]);
            if (addresses[s] == null || socket == null) continue;
            Ambilight.Strip st = layout.strips.get(s);
            try {
                for (byte[] p : Ambilight.dnrgb(st.start, b, Ambilight.WLED_TIMEOUT_S)) {
                    socket.send(new DatagramPacket(p, p.length, addresses[s], st.port));
                }
                errors[s] = null;
            } catch (IOException e) {
                errors[s] = "Sending to " + st.host + ": " + e.getMessage();
            }
        }
        worker.postAtTime(tick, now + frameMs);
    }

    private void testTargets(long now) {
        for (int s = 0; s < target.length; s++) {
            float[] t = target[s];
            double[][] m = map.get(s);
            int n = m.length;
            for (int i = 0; i < n; i++) {
                int rgb = pattern.equals(PATTERN_CHASE)
                        ? (s == chaseStrip ? Ambilight.chaseColour(i, n, now - testStarted, CHASE_PERIOD_MS) : 0)
                        : Ambilight.testColour(m[i][0], m[i][1], m[i][2]);
                float bri = pattern.equals(PATTERN_CHASE) ? 1 : tuning.brightness / 100f;
                t[i * 3] = ((rgb >> 16) & 0xff) * bri;
                t[i * 3 + 1] = ((rgb >> 8) & 0xff) * bri;
                t[i * 3 + 2] = (rgb & 0xff) * bri;
            }
        }
    }

    // -------------------------------------------------------------- status

    /**
     * For the config page: {state: off|on|paused|test, pattern, since,
     * error, capture: {width, height, fps, crop, black, average}, strips: [{name,
     * error, colors: "rrggbb…"}]}. Read on the web server's thread; the
     * colours may be a frame apart, which a preview doesn't mind.
     */
    static JSONObject status() throws JSONException {
        AmbilightService s = instance;
        JSONObject o = new JSONObject().put("error", lastError);
        if (s == null || s.mode == Mode.OFF) return o.put("state", "off");
        o.put("state", s.mode == Mode.TEST ? "test" : s.paused ? "paused" : "on").put("since", s.since);
        if (s.mode == Mode.TEST) o.put("pattern", s.pattern).put("strip", s.chaseStrip);
        int[] c = s.crop;
        if (s.mode == Mode.CAPTURE && c != null) {
            o.put("capture", new JSONObject().put("width", s.cw).put("height", s.ch).put("fps", s.fps)
                    .put("crop", new JSONArray().put(c[0]).put(c[1]).put(c[2]).put(c[3]))
                    .put("black", s.black).put("average", AmbilightConfig.hex(s.average)));
        }
        byte[][] out = s.out;
        String[] errors = s.stripErrors;
        Ambilight.Layout l = s.layout;
        JSONArray strips = new JSONArray();
        for (int i = 0; out != null && l != null && i < out.length && i < l.strips.size(); i++) {
            StringBuilder hex = new StringBuilder(out[i].length * 2);
            for (byte b : out[i]) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            strips.put(new JSONObject().put("name", l.strips.get(i).name)
                    .put("error", errors == null || errors[i] == null ? "" : errors[i])
                    .put("colors", hex.toString()));
        }
        return o.put("strips", strips);
    }
}
