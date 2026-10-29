package com.lyrnox.qingliao;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;

/** One focus owner. Never forces speaker/Bluetooth routing or resumes capture after interruption. */
final class AudioFocusLease {
  private final AudioManager manager;
  private final boolean communication;
  private final AudioFocusRequest request;
  private boolean acquired, changedMode;
  private int previousMode;

  AudioFocusLease(Context context, boolean communication, Runnable onInterruption) {
    manager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    this.communication = communication;
    AudioAttributes attributes = new AudioAttributes.Builder()
        .setUsage(communication ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    request = new AudioFocusRequest.Builder(communication ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT : AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(change -> {
          if (change < 0) {
            synchronized (AudioFocusLease.this) {
              if (!acquired) return;
            }
            onInterruption.run();
          }
        }, new Handler(Looper.getMainLooper())).build();
  }

  synchronized void acquire() throws IOException {
    if (acquired) return;
    if (manager == null || manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
      throw new IOException("其他应用正在使用音频，请稍后再试");
    acquired = true;
    if (communication) {
      previousMode = manager.getMode();
      manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
      changedMode = true;
    }
  }

  synchronized void release() {
    if (!acquired) return;
    // Restore only this lease's mode request; do not force speaker/Bluetooth routing.
    if (changedMode && manager.getMode() == AudioManager.MODE_IN_COMMUNICATION)
      manager.setMode(previousMode);
    manager.abandonAudioFocusRequest(request);
    acquired = false;
    changedMode = false;
  }
}
