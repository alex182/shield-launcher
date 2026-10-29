package dev.alex.minlauncher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

/**
 * Plays a generated sound (the EAS attention signal, the reminder chime)
 * over whatever is playing, which only ducks. Main thread.
 *
 * Media usage, not alarm: it must reach the speakers the show is on. An
 * alarm goes to the built-in output and only maybe to Bluetooth too, so with
 * Bluetooth headphones or a speaker it was often never heard.
 */
final class Tone {

    private static final String TAG = "Tone";

    private final Context context;
    private final Handler handler;
    private final Runnable done = this::stop;
    private AudioTrack track;
    private AudioFocusRequest focus;

    Tone(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
    }

    /** 16-bit mono PCM; stops whatever this was playing first. */
    void play(short[] pcm, int sampleRate) {
        stop();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).build();
            audio.requestAudioFocus(focus);
        }
        try {
            track = new AudioTrack(attrs, new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    pcm.length * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.write(pcm, 0, pcm.length);
            track.play();
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "cannot play: " + e);
            stop();
            return;
        }
        handler.postDelayed(done, pcm.length * 1000L / sampleRate + 200);
    }

    void stop() {
        handler.removeCallbacks(done);
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
            track = null;
        }
        if (focus != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(focus);
            focus = null;
        }
    }
}
