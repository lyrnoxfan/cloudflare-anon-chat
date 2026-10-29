package com.lyrnox.qingliao;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real Android file/focus/lifecycle checks; no cloud calls and no microphone quality assertion. */
public final class MediaLifecycleInstrumentation extends Instrumentation {
  private int checks;
  private void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); checks++; }
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle result = new Bundle();
    AudioFocusLease media = null, call = null;
    try {
      if (!BuildConfig.APPLICATION_ID.endsWith(".qa") || !ChatNet.BASE.equals("http://127.0.0.1:8000"))
        throw new SecurityException("Loopback QA only");
      File cache = getTargetContext().getCacheDir();
      byte[] bytes = "保存完整文件\nmedia-byte-check\n".getBytes("UTF-8");
      File source = File.createTempFile("source-", ".txt", cache);
      java.nio.file.Files.write(source.toPath(), bytes);
      File snapshot = MediaFiles.snapshot(source, cache);
      source.delete();
      check(Arrays.equals(bytes, java.nio.file.Files.readAllBytes(snapshot.toPath())), "snapshot survives player source cleanup");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (InputStream input = new FileInputStream(snapshot)) {
        check(MediaFiles.copy(input, output) == bytes.length && Arrays.equals(bytes, output.toByteArray()), "bounded save preserves all bytes");
      }
      check(MediaFiles.displayName("../a\n/b\\c.txt").equals(".._a__b_c.txt"), "document name removes controls and separators");
      check(MediaFiles.displayName("..").equals("文件"), "dot filename replaced");
      boolean overflow = false;
      try {
        MediaFiles.copy(new InputStream() {
          long remaining = MediaFiles.MAX_BYTES + 1;
          public int read() { if (remaining-- > 0) return 0; return -1; }
          @Override public int read(byte[] b, int o, int n) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(remaining, n); remaining -= count; return count;
          }
        }, new OutputStream() { public void write(int b) {} public void write(byte[] b, int o, int n) {} });
      } catch (IOException expected) { overflow = true; }
      check(overflow, "oversize save rejected before overflow write");
      runOnMainSync(() -> {
        try {
          ChatFragment fragment = new ChatFragment();
          Field field = ChatFragment.class.getDeclaredField("exportFile"); field.setAccessible(true); field.set(fragment, snapshot);
          fragment.onActivityResult(11, Activity.RESULT_CANCELED, null);
          if (snapshot.exists() || field.get(fragment) != null) throw new AssertionError("Cancelled export retained snapshot");
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
      });
      check(!snapshot.exists(), "cancelled document picker removes pending snapshot");
      Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivitySync(intent); waitForIdleSync();
      CountDownLatch interrupted = new CountDownLatch(1);
      media = new AudioFocusLease(getTargetContext(), false, interrupted::countDown);
      media.acquire();
      call = new AudioFocusLease(getTargetContext(), true, () -> {});
      call.acquire();
      check(interrupted.await(5, TimeUnit.SECONDS), "Android audio service delivers competing focus interruption");
      call.release(); call = null;
      media.release(); media = null;
      AudioManager manager = (AudioManager)getTargetContext().getSystemService(android.content.Context.AUDIO_SERVICE);
      check(manager.getMode() == AudioManager.MODE_NORMAL, "call cleanup releases communication mode");
      try (NativeCore core = new NativeCore(); ChatNet net = new ChatNet(getTargetContext(), false)) {
        NativeCallSession cancelled = new NativeCallSession(getTargetContext(), core, net, "lobby", "qa", new NativeCallSession.Listener() {
          public void onStatus(String s) {} public void onFailure(String s) {}
          public void onLocalVideo(org.webrtc.VideoTrack t, org.webrtc.EglBase.Context e) {}
          public void onRemoteVideo(org.webrtc.VideoTrack t, org.webrtc.EglBase.Context e) {}
        });
        cancelled.requestStop();
        boolean rejected = false;
        try { cancelled.start("audio", null, ""); } catch (IOException expected) { rejected = true; }
        cancelled.stop();
        check(rejected, "pre-cancelled call cannot start signalling or capture");
      }
      result.putString("stream", checks + " Android media/file/focus assertions passed\n");
      finish(-1, result);
    } catch (Throwable e) {
      result.putString("stream", "FAILED media lifecycle: " + e + "\n"); finish(0, result);
    } finally {
      if (call != null) call.release();
      if (media != null) media.release();
    }
  }
}
