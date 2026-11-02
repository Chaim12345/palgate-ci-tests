package com.palgate.widgethost;

import android.app.Activity;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

/**
 * MediaSession custom-action probe (mechanism test, no audio).
 *
 * Question it answers: can a media-style "button" (a MediaSession custom
 * action) invoke app behavior when tapped, with no audio ever playing?
 * This publishes a session in STATE_PAUSED with one custom action, then
 * dispatches that action through a MediaController - the same dispatch the
 * Android Auto host performs when its media control is tapped. It does NOT
 * prove that Android Auto displays the card/controls without audio; that is
 * host behavior and needs a real host.
 */
public class MediaProbeActivity extends Activity {
    private static final String TAG = "PalGateMedia";
    private static final String ACTION_OPEN = "com.palgate.carapp.OPEN_GATE_TEST";
    private MediaSession session;
    private TextView status;
    private boolean reached;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        status = new TextView(this);
        status.setTextSize(14);
        status.setText("media probe: starting");
        setContentView(status);

        session = new MediaSession(this, "palgate_media_probe");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onCustomAction(String action, Bundle extras) {
                reached = true;
                Log.i(TAG, "MEDIA-PROBE onCustomAction reached action=" + action);
                set("MEDIA-PROBE: onCustomAction reached: " + action);
            }
        });
        PlaybackState st = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        ACTION_OPEN, "Open Gate", R.drawable.ic_probe_gate).build())
                .build();
        session.setPlaybackState(st);
        session.setActive(true);
        Log.i(TAG, "MEDIA-PROBE published state=PAUSED customAction=" + ACTION_OPEN
                + " (no audio focus requested, nothing playing)");

        final MediaController controller =
                new MediaController(this, session.getSessionToken());
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "MEDIA-PROBE dispatching custom action via MediaController");
                controller.getTransportControls().sendCustomAction(ACTION_OPEN, null);
            }
        }, 1500);
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                if (!reached) {
                    Log.i(TAG, "MEDIA-PROBE TIMEOUT: custom action never reached callback");
                    set("MEDIA-PROBE: TIMEOUT");
                }
            }
        }, 5000);
    }

    private void set(String s) { status.setText(s); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (session != null) session.release();
    }
}
