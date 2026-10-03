package io.github.goldenguts.bttvremote.core;

import android.content.Context;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import io.github.goldenguts.bttvremote.common.Protocol;

/**
 * Experiment (not verified): a media session with remote volume. When it is active, Android may send the
 * phone volume keys to it, also with the screen off. It is active only while the screen is off and the
 * remote was used recently, so the phone volume keys work normally at other times.
 */
final class VolumeSession {

    interface Sink {
        void action(int action, KeyQueue.Result result);
    }

    private final MediaSession session;

    VolumeSession(Context context, Sink sink) {
        session = new MediaSession(context, "TvCoreVolume");
        session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            @Override
            public void onAdjustVolume(int direction) {
                if (direction > 0) sink.action(Protocol.VOLUME_UP, null);
                else if (direction < 0) sink.action(Protocol.VOLUME_DOWN, null);
            }
        });
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build());
    }

    void setActive(boolean active) {
        session.setActive(active);
    }

    void release() {
        session.release();
    }
}
