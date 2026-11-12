package dev.alex.minlauncher;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen music visualizer over whatever app is playing: Milkdrop
 * presets drawn by butterchurn (assets/visualizer/, WebGL 2 in a WebView).
 * Opened and closed by holding play/pause (see {@link QuickMenuService}), or
 * from the config page. Its settings ({@link Store#visualizer()}) apply live:
 * the page and the sound source both re-read them while it shows.
 *
 * Sound, by the "source" setting:
 * <ul>
 *   <li>auto: an {@link Visualizer} on the playing app's own audio session,
 *       found in audio_flinger's dump ({@link AudioSessions}) and checked
 *       again every couple of seconds, so it follows a new player or app;</li>
 *   <li>app: the same, but only that app's session;</li>
 *   <li>mix: session 0, the output mix. It needs no lookup, but on the
 *       SHIELD media plays out through a bus device and session 0's capture
 *       sits on another, idle output: it hears silence there. Kept for other
 *       devices, and as auto's fallback until something plays;</li>
 *   <li>mic: the microphone (a USB one; the remote's only opens for voice
 *       search).</li>
 * </ul>
 * The lookup needs DUMP and capture RECORD_AUDIO, both granted over adb by
 * {@code build.sh install}; without them the presets still run, to
 * silence, and say why. Encoded passthrough (Dolby, DTS to a receiver) is
 * never captured either. What it listens to goes to the config page as
 * {@link Store#visualizerStatus()}.
 *
 * Left/right step through the presets, up likes or unlikes the one showing
 * ({@link Store#visualizerLiked()}), down jumps to a random one, select
 * holds the current one, holding select turns the source images
 * ({@link VisualizerImage}) on or off, Back closes. The config page can open it on a
 * playlist or a preset of its own ({@link #show(String, String)}). The window takes focus, so the app
 * underneath keeps playing but doesn't see the arrows; media keys still go
 * through. It keeps the screen on and closes when the screen turns off.
 */
final class VisualizerOverlay {

    private static final String TAG = "Visualizer";
    private static final String PAGE = "file:///android_asset/visualizer/index.html";
    /** butterchurn's own FFT size; it reads exactly this many samples a frame. */
    private static final int SAMPLES = 1024;
    /** How often to look for a new playing session, or a new source setting, while showing. */
    private static final long FOLLOW_MS = 2000;
    private static final int NONE = -1;
    private static final int MIC_RATE = 44100;
    /** As butterchurnviz.com does for its mic, which comes in quieter than a mix. */
    private static final float MIC_GAIN = 1.5f;

    private final Context context;
    private final WindowManager wm;
    private final Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Captures are made, swapped and released here, one at a time. */
    private final ExecutorService audio = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private WebView web;
    private volatile boolean showing;

    // Guarded by this: the page's bridge thread reads them every frame.
    private Visualizer capture;
    private byte[] wave;
    private byte[] micWave;         // the mic's latest block, already 8-bit
    // Audio thread only.
    private int session = NONE;
    private String sessionPkg = "";
    private Thread mic;
    private boolean micTried;
    private volatile boolean micRunning;
    private String status = "";
    private volatile String note = "";
    /** From the config page's Play buttons: a playlist and a preset for this showing only. */
    private volatile String playPlaylist = "", playPreset = "";
    private volatile int playSeq;
    /** Select's long press already acted (toggled the images), so its release doesn't hold the preset. */
    private boolean selectLong;
    /** How long select is held to toggle the images rather than hold the preset. */
    private static final long SELECT_LONG_MS = 600;

    private final Runnable follow = new Runnable() {
        @Override public void run() {
            audio.execute(VisualizerOverlay.this::followSource);
            handler.postDelayed(this, FOLLOW_MS);
        }
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { hide(); }
    };

    VisualizerOverlay(Context context, WindowManager wm, Store store) {
        this.context = context;
        this.wm = wm;
        this.store = store;
    }

    boolean isShowing() { return root != null; }

    void toggle() {
        if (root != null) hide();
        else show();
    }

    void show() { show("", ""); }

    /**
     * Shows it, on {@code playlist} ("all", "liked") and starting at
     * {@code preset} in place of the saved settings, "" to keep them. Already
     * showing, it switches over.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    void show(String playlist, String preset) {
        if (!playlist.isEmpty() || !preset.isEmpty()) {
            playPlaylist = playlist;
            playPreset = preset;
            playSeq++;
        }
        if (root != null) return;
        showing = true;
        note = "";
        follow.run();

        root = new FrameLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                return onKey(e) || super.dispatchKeyEvent(e);
            }
        };
        root.setBackgroundColor(0xFF000000);

        web = new WebView(context);
        web.setBackgroundColor(0xFF000000);
        // Keys stay with the frame above; the page has nothing to focus.
        web.setFocusable(false);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "shield");
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        // WebGL needs a GPU-backed window; a service's windows aren't by default.
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        wm.addView(root, lp);
        context.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        web.loadUrl(PAGE);
    }

    void hide() {
        if (root == null) return;
        try { context.unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { }
        wm.removeView(root);
        web.destroy();
        web = null;
        root = null;
        showing = false;
        playPlaylist = playPreset = "";
        store.setVisualizerShowing("");
        handler.removeCallbacks(follow);
        audio.execute(() -> {
            stopCapture();
            stopMic();
            setStatus("closed", "", NONE);
        });
    }

    void shutdown() {
        hide();
        audio.shutdown();
    }

    private boolean onKey(KeyEvent e) {
        String call;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (e.getAction() == KeyEvent.ACTION_UP) hide();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: call = "remote.prev()"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: call = "remote.next()"; break;
            case KeyEvent.KEYCODE_DPAD_UP: call = "remote.like()"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: call = "remote.random()"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                // Acted on at release, so a hold can mean something else.
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        selectLong = false;
                    } else if (!selectLong && e.getEventTime() - e.getDownTime() >= SELECT_LONG_MS) {
                        selectLong = true;
                        boolean on = !store.visualizerImages();
                        store.setVisualizerImages(on);
                        web.evaluateJavascript("remote.images(" + on + ")", null);
                    }
                } else if (e.getAction() == KeyEvent.ACTION_UP && !selectLong && !e.isCanceled()) {
                    web.evaluateJavascript("remote.hold()", null);
                }
                return true;
            default: return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            web.evaluateJavascript(call, null);
        }
        return true;
    }

    // ----------------------------------------------------------------- audio

    /** Puts the capture where the source setting says, every FOLLOW_MS. */
    private void followSource() {
        if (!showing) return;
        JSONObject s = store.visualizer();
        String source = s.optString("source");
        if (source.equals("mic")) {
            stopCapture();
            startMic();
            return;
        }
        stopMic();
        if (source.equals("mix")) {
            if (session != 0) attach(0, "");
            return;
        }
        String only = source.equals("app") ? s.optString("app") : "";
        String dump = AudioSessions.dump(context);
        if (AudioSessions.denied(dump) && note.isEmpty()) {
            Log.w(TAG, dump.trim());
            note = context.getString(R.string.visualizer_no_dump);
        }
        List<AudioSessions.Playing> playing = AudioSessions.active(dump, Process.myPid());
        AudioSessions.Playing want = null;
        for (AudioSessions.Playing p : playing) {
            if (!only.isEmpty() && !p.pkg.equals(only)) continue;
            if (p.session == session) {     // still playing: stay
                want = p;
                break;
            }
            if (want == null) want = p;
        }
        if (want != null) {
            if (want.session != session) attach(want.session, want.pkg);
        } else if (!only.isEmpty() && !only.equals(sessionPkg)) {
            // That app isn't playing (yet); don't draw someone else's sound.
            stopCapture();
            setStatus("waiting", only, NONE);
        } else if (session == NONE) {
            // Nothing playing yet; a pause keeps the session we had.
            attach(0, "");
        }
    }

    private void attach(int id, String pkg) {
        stopCapture();
        try {
            Visualizer v = new Visualizer(id);
            v.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.max(range[0], Math.min(range[1], SAMPLES)));
            // Same picture at any volume.
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            v.setEnabled(true);
            synchronized (this) { capture = v; }
            session = id;
            sessionPkg = pkg;
            Log.i(TAG, "Listening to audio session " + id + (pkg.isEmpty() ? "" : " (" + pkg + ")"));
            setStatus(id == 0 ? "mix" : "app", pkg, id);
        } catch (RuntimeException e) {
            // Thrown without RECORD_AUDIO, or for a session that just ended.
            Log.w(TAG, "No capture on audio session " + id, e);
            if (id == 0) {
                session = 0;    // don't retry every tick; a session that plays still gets picked up
                note = context.getString(R.string.visualizer_no_audio);
                setStatus("none", "", NONE);
            }
        }
    }

    private void stopCapture() {
        Visualizer old;
        synchronized (this) {
            old = capture;
            capture = null;
        }
        if (old != null) old.release();
        session = NONE;
        sessionPkg = "";
    }

    @SuppressLint("MissingPermission")   // granted over adb; a refusal throws and is reported
    private void startMic() {
        if (micTried) return;
        micTried = true;
        int min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLES * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                throw new IllegalStateException("no microphone");
            }
            rec.startRecording();
        } catch (RuntimeException e) {
            Log.w(TAG, "No microphone", e);
            note = context.getString(R.string.visualizer_no_mic);
            setStatus("none", "", NONE);
            return;
        }
        micRunning = true;
        mic = new Thread(() -> {
            short[] pcm = new short[SAMPLES];
            while (micRunning) {
                int n = rec.read(pcm, 0, SAMPLES);
                if (n <= 0) continue;
                byte[] block = new byte[SAMPLES];
                for (int i = 0; i < SAMPLES; i++) {
                    int v = i < n ? Math.round(pcm[i] * MIC_GAIN) >> 8 : 0;
                    block[i] = (byte) (Math.max(-128, Math.min(127, v)) + 128);
                }
                synchronized (VisualizerOverlay.this) { micWave = block; }
            }
            rec.stop();
            rec.release();
        }, "VisualizerMic");
        mic.start();
        Log.i(TAG, "Listening to the microphone");
        setStatus("mic", "", NONE);
    }

    private void stopMic() {
        micTried = false;
        if (mic == null) return;
        micRunning = false;
        try { mic.join(500); } catch (InterruptedException ignored) { }
        mic = null;
        synchronized (this) { micWave = null; }
    }

    /** Only written when it changes: the follow loop runs every 2 s. */
    private void setStatus(String listening, String pkg, int id) {
        String key = listening + "|" + pkg + "|" + id + "|" + note;
        if (key.equals(status)) return;
        status = key;
        try {
            store.setVisualizerStatus(new JSONObject().put("at", System.currentTimeMillis())
                    .put("listening", listening).put("pkg", pkg).put("session", id)
                    .put("note", note).toString());
        } catch (JSONException ignored) { }
    }

    /**
     * The latest waveform, 8-bit unsigned (128 = silence), base64; "" for
     * none. Called from the page's JavaBridge thread, once a frame, while the
     * audio thread may swap or release the capture.
     */
    private synchronized String waveBase64() {
        if (micWave != null) return Base64.encodeToString(micWave, Base64.NO_WRAP);
        if (capture == null) return "";
        try {
            int size = capture.getCaptureSize();
            if (wave == null || wave.length != size) wave = new byte[size];
            if (capture.getWaveForm(wave) != Visualizer.SUCCESS) return "";
        } catch (IllegalStateException e) {
            return "";
        }
        return Base64.encodeToString(wave, Base64.NO_WRAP);
    }

    /** The page's {@code shield} object. */
    private final class Bridge {
        @JavascriptInterface
        public String wave() { return waveBase64(); }

        /** Why there is no sound to draw, or "". */
        @JavascriptInterface
        public String note() { return note; }

        /**
         * {@link Store#visualizer()} as JSON, plus {@code liked}, and the
         * config page's {@code playlist} and {@code play} (a preset, with
         * {@code playSeq} counting the requests) when it opened this, and
         * {@code images}, the source images' ids ({@link VisualizerImage#ids});
         * the page re-reads it every second.
         */
        @JavascriptInterface
        public String settings() {
            JSONObject s = store.visualizer();
            try {
                s.put("liked", store.visualizerLiked());
                if (!playPlaylist.isEmpty()) s.put("playlist", playPlaylist);
                s.put("play", playPreset).put("playSeq", playSeq);
                s.put("images", new JSONArray(VisualizerImage.ids(context)));
                s.put("imagesOn", store.visualizerImages());
            } catch (JSONException ignored) { }
            return s.toString();
        }

        /** A source image as a data: URL, "" if it's gone; asked for when it comes up. */
        @JavascriptInterface
        public String image(String id) { return VisualizerImage.dataUrl(context, id); }

        /** Likes or unlikes a preset, from the remote's up. */
        @JavascriptInterface
        public void like(String preset, boolean liked) { store.setVisualizerLiked(preset, liked); }

        /** The preset now on screen, for the config page. */
        @JavascriptInterface
        public void showing(String preset) {
            if (showing) store.setVisualizerShowing(preset);
        }
    }
}
