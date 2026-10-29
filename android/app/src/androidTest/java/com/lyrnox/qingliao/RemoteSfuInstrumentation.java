package com.lyrnox.qingliao;

import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.webrtc.*;

/** Three-client native group-call acceptance against the approved isolated SFU test service.
 * Three isolated emulators use emulated cameras; not physical-device acceptance. */
public final class RemoteSfuInstrumentation extends Instrumentation {
  private Bundle args;
  private final AtomicInteger decodedFrames = new AtomicInteger();
  private final java.util.concurrent.ConcurrentHashMap<VideoTrack, AtomicInteger> framesByTrack = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentHashMap<VideoTrack, VideoSink> sinksByTrack = new java.util.concurrent.ConcurrentHashMap<>();
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
  @Override public void onStart() {
    Bundle result = new Bundle();
    NativeCallSession call = null;
    boolean owner = args != null && "owner".equals(args.getString("role"));
    boolean extra = args != null && "extra".equals(args.getString("role"));
    android.app.Activity activity = null;
    ChatFragment fragment = null;
    try (NativeCore core = new NativeCore(); ChatNet net = new ChatNet(getTargetContext(), !owner)) {
      if (!BuildConfig.APPLICATION_ID.endsWith(".qa") || !"https://chat.lyrnox.com".equals(ChatNet.BASE)
          || args == null || !"blue-room-test".equals(args.getString("approvedTarget")))
        throw new SecurityException("Explicit isolated test target required");
      String suffix = UUID.randomUUID().toString().substring(0,8);
      JSONObject session = net.api("/api/session", core.run("session", "nickname", "Native-SFU-" + suffix));
      if (!session.optBoolean("sfu")) throw new AssertionError("SFU is not enabled at test target");
      String room, password;
      if (owner) {
        password = UUID.randomUUID().toString();
        room = net.api("/api/rooms", core.run("room", "name", "Native emulator SFU " + suffix, "password", password)).getString("id");
        // Ephemeral room invite only; never writes a session cookie, SFU cap or credential.
        java.nio.file.Files.write(new File(getTargetContext().getFilesDir(), "sfu-invite.json").toPath(),
            new JSONObject().put("room",room).put("password",password).toString().getBytes("UTF-8"));
      } else {
        room = args.getString("room"); password = args.getString("password");
        net.api("/api/r/"+room+"/join", core.run("join", "password", password));
      }
      String callId = "";
      if (!owner) {
        long deadline = System.currentTimeMillis()+90000;
        while (System.currentTimeMillis()<deadline) {
          JSONObject active = net.callApi(room,"call-state",null,null).optJSONObject("call");
          if (active != null) { callId=active.getString("id"); break; }
          Thread.sleep(1000);
        }
        if (callId.isEmpty()) throw new AssertionError("Owner did not open test call");
      }
      core.run("reset", "room", room);
      AtomicReference<String> failure = new AtomicReference<>();
      AtomicReference<String> status = new AtomicReference<>("initializing");
      java.util.concurrent.ConcurrentHashMap<String, VideoTrack> remote = new java.util.concurrent.ConcurrentHashMap<>();
      NativeCallSession.Listener listener = new NativeCallSession.Listener(){
        public void onStatus(String text) { status.set(text); }
        public void onFailure(String text) { failure.set(text); }
        public void onRecoverableFailure(String text, String callId, String kind, boolean canRejoin) { failure.set(text); }
        public void onLocalVideo(VideoTrack track,EglBase.Context egl) {}
        public void onRemoteVideo(String memberId, String memberName, VideoTrack track,EglBase.Context egl) {
          if (memberId == null) return;
          VideoTrack old = track == null ? remote.remove(memberId) : remote.put(memberId, track);
          if (old != null && old != track) removeFrameSink(old);
          if (track != null && old != track) addFrameSink(track);
        }
      };
      if (owner || extra) {
        call = new NativeCallSession(getTargetContext(),core,net,room,session.getString("id"),listener);
        call.start("video",callId,"");
      } else {
        // The test prepares a cookie and already-authorized room membership. The actual
        // foreground Activity renders the invitation; its real View click creates the session.
        activity = startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        fragment = (ChatFragment) activity.getFragmentManager().findFragmentByTag("chat");
        final ChatFragment ui = fragment;
        long until = System.currentTimeMillis() + 45000;
        while (field(ui, "session") == null && System.currentTimeMillis() < until) Thread.sleep(500);
        if (field(ui, "session") == null) throw new AssertionError("Activity did not restore isolated peer session");
        final String rid = room;
        runOnMainSync(() -> {
          try {
            java.lang.reflect.Method switchRoom = ChatFragment.class.getDeclaredMethod("switchRoom", String.class);
            switchRoom.setAccessible(true); switchRoom.invoke(ui, rid);
          } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        });
        until = System.currentTimeMillis() + 45000;
        while (!visible(ui, "callJoin") && System.currentTimeMillis() < until) Thread.sleep(500);
        if (!visible(ui, "callJoin")) throw new AssertionError("Real Activity did not render incoming group call");
        click(ui, "callJoin");
        until = System.currentTimeMillis() + 30000;
        while (field(ui, "nativeCall") == null && System.currentTimeMillis() < until) Thread.sleep(250);
        call = (NativeCallSession) field(ui, "nativeCall");
        if (call == null) throw new AssertionError("Activity join View did not create native session");
      }
      // Publish a safe phase marker after the real signaling/publisher setup. The runner
      // serializes joiners so both existing media directions stabilize before the third
      // emulator begins, avoiding three concurrent negotiations racing on the SFU.
      java.nio.file.Files.write(new File(getTargetContext().getFilesDir(), "sfu-publisher-ready").toPath(),
          "publisher-ready".getBytes("UTF-8"));
      long deadline=System.currentTimeMillis()+150000;
      long audioPackets=0,videoBytes=0;
      int renderedRemoteCount=0;
      boolean pairReadyWritten = false;
      AtomicReference<Boolean> tracksDecoded = new AtomicReference<>(false);
      while(System.currentTimeMillis()<deadline) {
        if(failure.get()!=null) throw new AssertionError("Native call failed: "+failure.get()+"; lastStatus="+status.get());
        if (!owner && !extra) {
          if (field(fragment, "nativeCall") != call)
            throw new AssertionError("Activity call ended before receiving media");
          final ChatFragment ui = fragment;
          AtomicReference<java.util.Set<String>> labels = new AtomicReference<>(new java.util.HashSet<>());
          AtomicInteger visibleCount = new AtomicInteger();
          runOnMainSync(() -> {
            try {
              @SuppressWarnings("unchecked") java.util.Map<String,?> rendered =
                  (java.util.Map<String,?>) field(ui, "remoteRenderers");
              java.util.Set<String> names = new java.util.HashSet<>();
              android.widget.GridLayout grid = (android.widget.GridLayout) field(ui, "callVideoGrid");
              int attached = 0;
              java.util.Set<VideoTrack> liveTracks = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
              for (Object binding : rendered.values()) {
                try {
                  Field trackField = binding.getClass().getDeclaredField("track"); trackField.setAccessible(true);
                  VideoTrack track = (VideoTrack) trackField.get(binding);
                  if (track != null) { liveTracks.add(track); addFrameSink(track); }
                  Field cellField = binding.getClass().getDeclaredField("cell"); cellField.setAccessible(true);
                  android.widget.FrameLayout cell = (android.widget.FrameLayout) cellField.get(binding);
                  if (grid != null && cell.getParent() == grid) attached++;
                  if (cell.getChildCount() == 2 && cell.getChildAt(1) instanceof android.widget.TextView) {
                    String label = ((android.widget.TextView) cell.getChildAt(1)).getText().toString();
                    if (!label.isEmpty()) names.add(label);
                  }
                } catch (ReflectiveOperationException ignored) {}
              }
              visibleCount.set(attached);
              labels.set(names);
              tracksDecoded.set(liveTracks.size() >= 2 && liveTracks.stream().allMatch(track -> {
                AtomicInteger count = framesByTrack.get(track);
                return count != null && count.get() >= 10;
              }));
            } catch (Exception e) { throw new RuntimeException(e); }
          });
          renderedRemoteCount = visibleCount.get();
          if (renderedRemoteCount >= 2 && labels.get().size() < 2)
            throw new AssertionError("Group video grid is missing distinct participant labels: " + labels.get());
        } else {
          renderedRemoteCount = remote.size();
          tracksDecoded.set(renderedRemoteCount >= 2 && remote.values().stream().allMatch(track -> {
            AtomicInteger count = framesByTrack.get(track);
            return count != null && count.get() >= 10;
          }));
          if ((owner || extra) && !pairReadyWritten && renderedRemoteCount >= 1 &&
              remote.values().stream().allMatch(track -> {
                AtomicInteger count = framesByTrack.get(track);
                return count != null && count.get() >= 10;
              })) {
            java.nio.file.Files.write(new File(getTargetContext().getFilesDir(), "sfu-pair-ready").toPath(),
                "pair-media-ready".getBytes("UTF-8"));
            pairReadyWritten = true;
          }
        }
        JSONObject stats=stats(call);
        audioPackets=stats.optLong("audioPackets"); videoBytes=stats.optLong("videoBytes");
        writeMediaMetrics(args.getString("role", "unknown"), stats, stats(call, "peer"));
        if (decodedFrames.get() >= 20 && tracksDecoded.get() && audioPackets > 10 && videoBytes > 0 && renderedRemoteCount >= 2) break;
        Thread.sleep(1500);
      }
      if (decodedFrames.get() < 20 || !tracksDecoded.get() || audioPackets <= 10 || videoBytes <= 0 || renderedRemoteCount < 2)
        throw new AssertionError("Remote group media incomplete: remoteVideoTracks="+renderedRemoteCount+", frames="+decodedFrames.get()+", perTrackFrames="+frameCounts()+", audioPackets="+audioPackets+", videoBytes="+videoBytes+"; receiver="+stats(call)+"; outbound="+stats(call, "peer")+"; lastStatus="+status.get());
      if (owner || extra) {
        if(call.toggleMicrophone()) throw new AssertionError("Mute did not disable audio");
        if(!call.toggleMicrophone()) throw new AssertionError("Unmute did not enable audio");
        if(call.toggleCamera()) throw new AssertionError("Camera disable failed");
        if(!call.toggleCamera()) throw new AssertionError("Camera enable failed");
      } else {
        click(fragment, "callMute"); awaitTrack(call, false, false);
        click(fragment, "callMute"); awaitTrack(call, false, true);
        click(fragment, "callCamera"); awaitTrack(call, true, false);
        click(fragment, "callCamera"); awaitTrack(call, true, true);
      }
      result.putString("stream","PASS native SFU "+(owner?"owner":extra?"extra":"peer")+": decodedVideoFrames="+decodedFrames.get()+", perTrackFrames="+frameCounts()+", receivedAudioPackets="+audioPackets+", receivedVideoBytes="+videoBytes+", renderedRemoteVideoTracks="+renderedRemoteCount+"; emulated camera/microphone, not physical devices\n");
      // Keep all three publishers active until each receiver has measured both remote streams.
      File ready = new File(getTargetContext().getFilesDir(), "sfu-ready");
      File release = new File(getTargetContext().getFilesDir(), "sfu-release");
      java.nio.file.Files.write(ready.toPath(), "media-ready".getBytes("UTF-8"));
      long releaseDeadline = System.currentTimeMillis() + 150000;
      long nextMetricsSample = 0;
      while (!release.exists() && System.currentTimeMillis() < releaseDeadline) {
        // Keep sampling while peers remain live: the runner measures constrained-network
        // progress from before/after snapshots taken during this barrier.
        if (System.currentTimeMillis() >= nextMetricsSample) {
          writeMediaMetrics(args.getString("role", "unknown"), stats(call), stats(call, "peer"));
          nextMetricsSample = System.currentTimeMillis() + 1000;
        }
        Thread.sleep(500);
      }
      if (!release.exists()) throw new AssertionError("Group did not confirm media before hangup barrier");
      if (owner || extra) { call.stop(); call=null; }
      else {
        final ChatFragment ui = fragment;
        // Exercise real screen-off/on while the native call is live. The disposable AOSP
        // emulator has no credential lock, so this tests lifecycle interruption, not PIN UX.
        shell("input keyevent 26");
        long lockDeadline = System.currentTimeMillis() + 20000;
        while ((field(ui, "nativeCall") != null || call.isMicrophoneReady() || call.isCameraReady())
            && System.currentTimeMillis() < lockDeadline) Thread.sleep(250);
        if (field(ui, "nativeCall") != null || call.isMicrophoneReady() || call.isCameraReady())
          throw new AssertionError("Screen-off did not release call and capture tracks");
        shell("input keyevent 26");
        Thread.sleep(1000);
        getTargetContext().startActivity(new android.content.Intent(getTargetContext(), MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
        Thread.sleep(1000);
        if (field(ui, "nativeCall") != null) throw new AssertionError("Screen-on silently restarted capture");
        result.putString("stream", result.getString("stream") + "PASS screen-off releases call/capture; screen-on does not restart\n");
        if (!getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
          throw new AssertionError("Android did not perform Home action");
        long until = System.currentTimeMillis() + 25000;
        while ((field(ui, "nativeCall") != null || call.isMicrophoneReady() || call.isCameraReady())
            && System.currentTimeMillis() < until) Thread.sleep(250);
        if (field(ui,"nativeCall") != null || call.isMicrophoneReady() || call.isCameraReady())
          throw new AssertionError("Home did not release call and capture tracks");
        getTargetContext().startActivity(new android.content.Intent(getTargetContext(), MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
        Thread.sleep(1500);
        if (field(ui, "nativeCall") != null) throw new AssertionError("Foreground return silently restarted capture");
        result.putString("stream", result.getString("stream") + "PASS actual Activity invitation/join, mute/camera View actions, Home capture cleanup and no automatic restart\n");
        call = null;
      }
      JSONObject state=net.callApi(room,"call-state",null,null).optJSONObject("call");
      if(state!=null && state.optBoolean("joined")) throw new AssertionError("Native leave did not remove participant");
      result.putString("stream",result.getString("stream")+"PASS native hangup leaves server participant\n");
      finish(-1,result);
    } catch(Throwable e) {
      result.putString("stream","FAILED "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n"); finish(0,result);
    } finally {
      for (java.util.Map.Entry<VideoTrack, VideoSink> entry : sinksByTrack.entrySet()) {
        try { entry.getKey().removeSink(entry.getValue()); } catch (Exception ignored) {}
      }
      sinksByTrack.clear();
      framesByTrack.clear();
      if (call != null) call.stop();
    }
  }
  private void writeMediaMetrics(String role, JSONObject stats, JSONObject publisher) throws Exception {
    JSONObject evidence = new JSONObject().put("role", role)
        .put("connectionState", stats.optString("connectionState", "unknown"))
        .put("iceState", stats.optString("iceState", "unknown"))
        .put("audioPackets", stats.optLong("audioPackets"))
        .put("videoBytes", stats.optLong("videoBytes"))
        .put("publisherConnectionState", publisher.optString("connectionState", "unknown"))
        .put("publisherIceState", publisher.optString("iceState", "unknown"))
        .put("publisherAudioBytes", publisher.optLong("sentAudioBytes"))
        .put("publisherVideoBytes", publisher.optLong("sentVideoBytes"))
        .put("decodedFrames", decodedFrames.get())
        .put("sampledAtMs", System.currentTimeMillis());
    java.nio.file.Files.write(new File(getTargetContext().getFilesDir(), "sfu-metrics.json").toPath(),
        evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  private void addFrameSink(VideoTrack track) {
    sinksByTrack.computeIfAbsent(track, key -> {
      AtomicInteger count = framesByTrack.computeIfAbsent(key, ignored -> new AtomicInteger());
      VideoSink sink = frame -> {
        if (frame.getBuffer().getWidth() > 0 && frame.getBuffer().getHeight() > 0) {
          count.incrementAndGet();
          decodedFrames.incrementAndGet();
        }
      };
      key.addSink(sink);
      return sink;
    });
  }
  private void removeFrameSink(VideoTrack track) {
    VideoSink sink = sinksByTrack.remove(track);
    if (sink != null) { try { track.removeSink(sink); } catch (Exception ignored) {} }
    framesByTrack.remove(track);
  }
  private String frameCounts() {
    java.util.List<Integer> counts = new java.util.ArrayList<>();
    for (AtomicInteger count : framesByTrack.values()) counts.add(count.get());
    java.util.Collections.sort(counts);
    return counts.toString();
  }
  private void shell(String command) throws Exception {
    android.os.ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
    try { if (descriptor != null) descriptor.close(); } finally { waitForIdleSync(); }
  }
  private static Object field(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
  }
  private boolean visible(ChatFragment fragment, String name) throws Exception {
    android.view.View v = (android.view.View) field(fragment, name);
    AtomicReference<Boolean> shown = new AtomicReference<>(false);
    runOnMainSync(() -> shown.set(v != null && v.isShown() && v.isEnabled()));
    return shown.get();
  }
  private void click(ChatFragment fragment, String name) throws Exception {
    android.view.View v = (android.view.View) field(fragment, name);
    runOnMainSync(() -> { if (v == null || !v.isShown() || !v.isEnabled() || !v.performClick())
      throw new AssertionError("Activity View action unavailable: " + name); });
  }
  private void awaitTrack(NativeCallSession call, boolean camera, boolean expected) throws Exception {
    long until = System.currentTimeMillis() + 15000;
    while ((camera ? call.isCameraEnabled() : call.isMicrophoneEnabled()) != expected && System.currentTimeMillis() < until)
      Thread.sleep(100);
    if ((camera ? call.isCameraEnabled() : call.isMicrophoneEnabled()) != expected)
      throw new AssertionError("Activity track action did not apply");
  }
  private static JSONObject stats(NativeCallSession call) throws Exception {
    return stats(call, "subscriberPeer");
  }
  private static JSONObject stats(NativeCallSession call, String fieldName) throws Exception {
    Field field=NativeCallSession.class.getDeclaredField(fieldName); field.setAccessible(true);
    PeerConnection peer=(PeerConnection)field.get(call);
    JSONObject out=new JSONObject(); if(peer==null) return out;
    out.put("connectionState",peer.connectionState().name()).put("iceState",peer.iceConnectionState().name());
    CountDownLatch done=new CountDownLatch(1);
    peer.getStats(report -> {
      long audio=0,video=0,outAudio=0,outVideo=0;
      for(RTCStats stat:report.getStatsMap().values()) {
        if(!"inbound-rtp".equals(stat.getType()) && !"outbound-rtp".equals(stat.getType())) continue;
        Object kind=stat.getMembers().get("kind"); if(kind==null) kind=stat.getMembers().get("mediaType");
        if ("outbound-rtp".equals(stat.getType())) {
          Object sent=stat.getMembers().get("bytesSent");
          if (sent instanceof Number && "audio".equals(kind)) outAudio+=((Number)sent).longValue();
          if (sent instanceof Number && "video".equals(kind)) outVideo+=((Number)sent).longValue();
          continue;
        }
        Object n=stat.getMembers().get("packetsReceived"), bytes=stat.getMembers().get("bytesReceived");
        if("audio".equals(kind) && n instanceof Number) audio+=((Number)n).longValue();
        if("video".equals(kind) && bytes instanceof Number) video+=((Number)bytes).longValue();
      }
      try { out.put("audioPackets",audio).put("videoBytes",video).put("sentAudioBytes",outAudio).put("sentVideoBytes",outVideo); } catch(Exception ignored) {}
      done.countDown();
    });
    if(!done.await(5,TimeUnit.SECONDS)) throw new AssertionError("RTCStats timeout");
    return out;
  }
}
