package com.palgate.widgethost;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

/**
 * Simulated-audio variant of the media probe (mechanism test, isolated).
 *
 * User proposal under test: start a short audio session (here: digital
 * silence, no recorded/audible content) so a media session is genuinely
 * PLAYING, then check whether the custom-action dispatch still works and how
 * audio focus behaves. This measures the APP-side focus lifecycle only.
 * Whether Android Auto shows the card/controls for such a session, and
 * whether it ducks the user's music or Waze prompts on a real phone, is host
 * behavior and is NOT provable on an emulator without an AA host.
 *
 * Key expected finding: ANY real audio stream - even digital silence -
 * takes audio focus. On a real phone that means other media ducks (with
 * MAY_DUCK) or pauses (with GAIN) for as long as the stream holds focus.
 * "Simulated" audio is therefore not interruption-free by construction.
 */
public class AudioProbeActivity extends Activity {
    private static final String TAG = "PalGateMedia";
    private static final String ACTION_OPEN = "com.palgate.carapp.OPEN_GATE_TEST";
    private MediaSession session;
    private AudioTrack track;
    private TextView status;
    private boolean reached;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        status = new TextView(this);
        status.setTextSize(14);
        status.setText("audio probe: starting");
        setContentView(status);

        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioManager.OnAudioFocusChangeListener focusListener =
                new AudioManager.OnAudioFocusChangeListener() {
                    @Override public void onAudioFocusChange(int change) {
                        Log.i(TAG, "MEDIA-PROBE audio focus change=" + change);
                    }
                };

        session = new MediaSession(this, "palgate_audio_probe");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onCustomAction(String action, Bundle extras) {
                reached = true;
                Log.i(TAG, "MEDIA-PROBE(audio) onCustomAction reached action=" + action);
                set("MEDIA-PROBE(audio): onCustomAction reached");
            }
        });

        int focus = am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        Log.i(TAG, "MEDIA-PROBE(audio) focus request (TRANSIENT_MAY_DUCK) result=" + focus
                + (focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? " GRANTED" : " NOT-GRANTED"));

        // Digital silence: zero-filled PCM, STREAM_MUSIC, ~3 seconds.
        final int rate = 44100;
        int min = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, rate * 2)).build();
        track.play();
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] silence = new byte[rate / 5 * 2];
                long end = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < end) track.write(silence, 0, silence.length);
            }
        }).start();
        Log.i(TAG, "MEDIA-PROBE(audio) silent PCM playing on STREAM_MUSIC (inaudible, focus still held)");

        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PAUSE)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        ACTION_OPEN, "Open Gate", R.drawable.ic_probe_gate).build())
                .build());
        session.setActive(true);
        Log.i(TAG, "MEDIA-PROBE(audio) session PLAYING with custom action published");

        final MediaController controller =
                new MediaController(this, session.getSessionToken());
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                controller.getTransportControls().sendCustomAction(ACTION_OPEN, null);
                Log.i(TAG, "MEDIA-PROBE(audio) custom action dispatched while PLAYING");
            }
        }, 1500);

        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                track.stop(); track.release();
                am.abandonAudioFocus(focusListener);
                Log.i(TAG, "MEDIA-PROBE(audio) focus released after 3s; reached=" + reached
                        + (reached ? "" : " TIMEOUT"));
                if (!reached) set("MEDIA-PROBE(audio): TIMEOUT");
            }
        }, 4000);
    }

    private void set(String s) { status.setText(s); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (session != null) session.release();
    }
}
