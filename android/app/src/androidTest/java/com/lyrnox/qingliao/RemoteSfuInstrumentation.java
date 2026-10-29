package com.lyrnox.qingliao;

import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
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
    String diagnosticStage = "initializing";
    String diagnosticStatus = "initializing";
    String diagnosticError = "";
    try (NativeCore core = new NativeCore(); ChatNet net = new ChatNet(getTargetContext(), !owner)) {
      if (!BuildConfig.APPLICATION_ID.endsWith(".qa") || !BuildConfig.CHAT_BASE.equals(ChatNet.BASE)
          || args == null || !BuildConfig.QA_SFU_TARGET.equals(args.getString("approvedTarget"))
          || "local-fixture".equals(BuildConfig.QA_SFU_TARGET))
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
        diagnosticStage = "native-publisher-start";
        writeDiagnostic(args.getString("role", "unknown"), diagnosticStage, status.get(), 0, "");
        call.start("video",callId,"");
        diagnosticStage = "native-publisher-ready";
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
        diagnosticStage = "activity-publisher-start";
        writeDiagnostic(args.getString("role", "unknown"), diagnosticStage, activityStatus(ui), 0, "");
        // nativeCall is assigned before its asynchronous callIo task starts. Do not tell
        // the three-emulator runner that this publisher is ready until call-start, join,
        // local publishing, ICE connection and call-ready have actually completed.
        awaitActivityPublisherReady(net, room, session.getString("id"), callId, ui, call, 120000);
        diagnosticStage = "activity-publisher-ready";
      }
      diagnosticStatus = status.get();
      // The runner's join barrier represents a server-confirmed ready publisher, not merely
      // an Activity object allocated before its asynchronous startup has begun.
      java.nio.file.Files.write(new File(getTargetContext().getFilesDir(), "sfu-publisher-ready").toPath(),
          "publisher-ready".getBytes("UTF-8"));
      // The serialized two-party phase can take time before the Activity peer starts.
      // Keep owner/extra alive through the runner's later three-party phase; the final
      // two-track requirement below remains unchanged.
      long deadline=System.currentTimeMillis()+((owner || extra) ? 600000 : 300000);
      long audioPackets=0,videoBytes=0;
      int renderedRemoteCount=0;
      boolean pairReadyWritten = false;
      boolean groupVerificationStarted = false;
      AtomicReference<Boolean> tracksDecoded = new AtomicReference<>(false);
      AtomicReference<String> liveTrackDiagnostics = new AtomicReference<>("[]");
      while(System.currentTimeMillis()<deadline) {
        if(failure.get()!=null) throw new AssertionError("Native call failed: "+failure.get()+"; lastStatus="+status.get());
        if (owner || extra) {
          groupVerificationStarted = new File(getTargetContext().getFilesDir(), "sfu-verify-group").isFile();
        } else {
          groupVerificationStarted = true;
        }
        int requiredRemoteTracks = (owner || extra) && !groupVerificationStarted ? 1 : 2;
        if (!owner && !extra) {
          if (field(fragment, "nativeCall") != call)
            throw new AssertionError("Activity call ended before receiving media; status=" + activityStatus(fragment));
          status.set(activityStatus(fragment));
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
              java.util.List<String> trackEvidence = new java.util.ArrayList<>();
              boolean eachCurrentTrackDecoded = liveTracks.size() >= 2;
              for (VideoTrack track : liveTracks) {
                AtomicInteger count = framesByTrack.get(track);
                int frames = count == null ? 0 : count.get();
                trackEvidence.add(Integer.toHexString(System.identityHashCode(track)) + ":" + frames);
                if (frames < 10) eachCurrentTrackDecoded = false;
              }
              java.util.Collections.sort(trackEvidence);
              liveTrackDiagnostics.set(trackEvidence.toString());
              tracksDecoded.set(eachCurrentTrackDecoded);
            } catch (Exception e) { throw new RuntimeException(e); }
          });
          renderedRemoteCount = visibleCount.get();
          if (renderedRemoteCount >= 2 && labels.get().size() < 2)
            throw new AssertionError("Group video grid is missing distinct participant labels: " + labels.get());
        } else {
          renderedRemoteCount = remote.size();
          tracksDecoded.set(renderedRemoteCount >= requiredRemoteTracks && remote.values().stream().allMatch(track -> {
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
            diagnosticStage = "pair-media-ready";
          }
        }
        JSONObject stats=stats(call);
        audioPackets=stats.optLong("audioPackets"); videoBytes=stats.optLong("videoBytes");
        diagnosticStage = "receiving-media";
        diagnosticStatus = status.get();
        writeMediaMetrics(args.getString("role", "unknown"), stats, stats(call, "peer"));
        writeDiagnostic(args.getString("role", "unknown"), diagnosticStage, diagnosticStatus, renderedRemoteCount, "");
        if (decodedFrames.get() >= 20 && tracksDecoded.get() && audioPackets > 10 && videoBytes > 0 &&
            renderedRemoteCount >= requiredRemoteTracks && groupVerificationStarted) break;
        Thread.sleep(1500);
      }
      if (!groupVerificationStarted || decodedFrames.get() < 20 || !tracksDecoded.get() || audioPackets <= 10 ||
          videoBytes <= 0 || renderedRemoteCount < 2)
        throw new AssertionError("Remote group media incomplete: groupVerificationStarted="+groupVerificationStarted+", requiredRemoteTracks=2, remoteVideoTracks="+renderedRemoteCount+", tracksDecoded="+tracksDecoded.get()+", visibleTrackFrames="+liveTrackDiagnostics.get()+", frames="+decodedFrames.get()+", allObservedPerTrackFrames="+frameCounts()+", audioPackets="+audioPackets+", videoBytes="+videoBytes+"; receiver="+stats(call)+"; outbound="+stats(call, "peer")+"; lastStatus="+status.get());
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
      diagnosticError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
      JSONObject receiverStats = new JSONObject();
      JSONObject publisherStats = new JSONObject();
      String statsError = "";
      if (call != null) {
        try { receiverStats = stats(call); }
        catch (Throwable statsFailure) { statsError = "receiver=" + statsFailure.getClass().getSimpleName(); }
        try { publisherStats = stats(call, "peer"); }
        catch (Throwable statsFailure) {
          statsError += (statsError.isEmpty() ? "" : "; ") + "publisher=" + statsFailure.getClass().getSimpleName();
        }
        try { writeMediaMetrics(args == null ? "unknown" : args.getString("role", "unknown"), receiverStats, publisherStats); }
        catch (Exception ignored) {}
      }
      try { writeDiagnostic(args == null ? "unknown" : args.getString("role", "unknown"),
          "failed:" + diagnosticStage, diagnosticStatus, remoteTrackCount(call), diagnosticError,
          receiverStats, publisherStats, statsError); }
      catch (Exception ignored) {}
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
  private int remoteTrackCount(NativeCallSession call) {
    return call == null ? 0 : sinksByTrack.size();
  }

  private void writeDiagnostic(String role, String stage, String currentStatus,
      int renderedRemoteTracks, String error) throws Exception {
    writeDiagnostic(role, stage, currentStatus, renderedRemoteTracks, error,
        new JSONObject(), new JSONObject(), "");
  }

  private void writeDiagnostic(String role, String stage, String currentStatus,
      int renderedRemoteTracks, String error, JSONObject receiverStats,
      JSONObject publisherStats, String statsError) throws Exception {
    JSONObject evidence = new JSONObject().put("role", role)
        .put("stage", stage).put("status", currentStatus)
        .put("renderedRemoteTracks", renderedRemoteTracks)
        .put("decodedFrames", decodedFrames.get()).put("perTrackFrames", frameCounts())
        .put("receiverStats", receiverStats).put("publisherStats", publisherStats)
        .put("statsError", statsError)
        .put("error", error).put("sampledAtMs", System.currentTimeMillis());
    java.nio.file.Files.write(new File(getTargetContext().getFilesDir(), "sfu-diagnostics.json").toPath(),
        evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private void awaitActivityPublisherReady(ChatNet net, String room, String selfId, String callId,
      ChatFragment ui, NativeCallSession call, long timeoutMs) throws Exception {
    long until = System.currentTimeMillis() + timeoutMs;
    String lastState = "unavailable";
    while (System.currentTimeMillis() < until) {
      if (field(ui, "nativeCall") != call)
        throw new AssertionError("Activity call ended before server-confirmed publisher readiness; status="
            + activityStatus(ui) + "; room=" + lastState);
      JSONObject snapshot = net.callApi(room, "call-state", null, null).optJSONObject("call");
      if (snapshot == null) {
        lastState = "call absent";
      } else if (!callId.equals(snapshot.optString("id", ""))) {
        lastState = "call changed";
      } else {
        JSONArray members = snapshot.optJSONArray("members");
        boolean joined = snapshot.optBoolean("joined");
        boolean hasAudio = false, hasVideo = false;
        if (members != null) for (int i = 0; i < members.length(); i++) {
          JSONObject member = members.optJSONObject(i);
          if (member == null || !selfId.equals(member.optString("id", ""))) continue;
          JSONArray tracks = member.optJSONArray("tracks");
          if (tracks != null) for (int j = 0; j < tracks.length(); j++) {
            String kind = tracks.optJSONObject(j) == null ? "" : tracks.optJSONObject(j).optString("kind", "");
            if ("audio".equals(kind)) hasAudio = true;
            if ("video".equals(kind)) hasVideo = true;
          }
          break;
        }
        lastState = "joined=" + joined + ", ownReadyTracks={audio:" + hasAudio + ",video:" + hasVideo + "}";
        if (joined && hasAudio && hasVideo) return;
      }
      Thread.sleep(1500);
    }
    throw new AssertionError("Activity publisher never reached server-confirmed ready state within "
        + timeoutMs + "ms; status=" + activityStatus(ui) + "; " + lastState
        + "; publisherPeerCreated=" + (field(call, "peer") != null));
  }

  private String activityStatus(ChatFragment ui) throws Exception {
    AtomicReference<String> value = new AtomicReference<>("");
    runOnMainSync(() -> {
      try {
        android.widget.TextView statusView = (android.widget.TextView) field(ui, "statusView");
        android.widget.TextView callLabel = (android.widget.TextView) field(ui, "callLabel");
        value.set("status=" + (statusView == null ? "" : statusView.getText())
            + "; call=" + (callLabel == null ? "" : callLabel.getText()));
      } catch (Exception e) { value.set("status-read-error=" + e.getClass().getSimpleName()); }
    });
    return value.get();
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
        .put("receiverCandidatePairs", stats.optJSONArray("candidatePairs") == null
            ? new JSONArray() : stats.optJSONArray("candidatePairs"))
        .put("publisherCandidatePairs", publisher.optJSONArray("candidatePairs") == null
            ? new JSONArray() : publisher.optJSONArray("candidatePairs"))
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
      JSONArray candidatePairs=new JSONArray();
      for(RTCStats stat:report.getStatsMap().values()) {
        if("candidate-pair".equals(stat.getType())) {
          Map<String,Object> members=stat.getMembers();
          JSONObject pair=new JSONObject();
          try {
            pair.put("state",members.get("state"));
            pair.put("nominated",members.get("nominated"));
            pair.put("selected",members.get("selected"));
            pair.put("bytesReceived",members.get("bytesReceived"));
            pair.put("bytesSent",members.get("bytesSent"));
            pair.put("localCandidateId",members.get("localCandidateId"));
            pair.put("remoteCandidateId",members.get("remoteCandidateId"));
            candidatePairs.put(pair);
          } catch(Exception ignored) {}
          continue;
        }
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
      // Candidate-pair IDs are joined to candidate type/protocol only; never persist IP addresses.
      for(int i=0;i<candidatePairs.length();i++) {
        JSONObject pair=candidatePairs.optJSONObject(i);
        for(String side:new String[]{"local","remote"}) {
          Object id=pair.opt(side+"CandidateId");
          RTCStats candidate=id==null?null:report.getStatsMap().get(String.valueOf(id));
          if(candidate!=null && (side.equals("local") ? "local-candidate" : "remote-candidate").equals(candidate.getType())) {
            try { pair.put(side+"CandidateType",candidate.getMembers().get("candidateType")); pair.put(side+"Protocol",candidate.getMembers().get("protocol")); } catch(Exception ignored) {}
          }
        }
      }
      try { out.put("audioPackets",audio).put("videoBytes",video).put("sentAudioBytes",outAudio).put("sentVideoBytes",outVideo).put("candidatePairs",candidatePairs); } catch(Exception ignored) {}
      done.countDown();
    });
    if(!done.await(5,TimeUnit.SECONDS)) throw new AssertionError("RTCStats timeout");
    return out;
  }
}
