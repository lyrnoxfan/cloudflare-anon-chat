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

/** Explicit manual test of the shipped native SFU client against blue-room-test.
 * Two isolated emulators use emulated cameras; not physical-device acceptance. */
public final class RemoteSfuInstrumentation extends Instrumentation {
  private Bundle args;
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
  @Override public void onStart() {
    Bundle result = new Bundle();
    NativeCallSession call = null;
    boolean owner = args != null && "owner".equals(args.getString("role"));
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
      AtomicInteger frames = new AtomicInteger();
      AtomicReference<String> failure = new AtomicReference<>();
      AtomicReference<String> status = new AtomicReference<>("initializing");
      VideoSink sink = frame -> { if (frame.getBuffer().getWidth()>0 && frame.getBuffer().getHeight()>0) frames.incrementAndGet(); };
      AtomicReference<VideoTrack> remote = new AtomicReference<>();
      if (owner) {
      call = new NativeCallSession(getTargetContext(),core,net,room,session.getString("id"),new NativeCallSession.Listener(){
        public void onStatus(String text) { status.set(text); }
        public void onFailure(String text) { failure.set(text); }
        public void onLocalVideo(VideoTrack track,EglBase.Context egl) {}
        public void onRemoteVideo(VideoTrack track,EglBase.Context egl) {
          VideoTrack old=remote.getAndSet(track); if(old!=null) old.removeSink(sink); if(track!=null) track.addSink(sink);
        }
      });
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
      long deadline=System.currentTimeMillis()+120000;
      long audioPackets=0,videoBytes=0;
      while(System.currentTimeMillis()<deadline) {
        if(failure.get()!=null) throw new AssertionError("Native call failed: "+failure.get()+"; lastStatus="+status.get());
        if (!owner) {
          if (field(fragment, "nativeCall") != call)
            throw new AssertionError("Activity call ended before receiving media");
          VideoTrack track = (VideoTrack) field(call, "activeRemoteVideo");
          if (track != null && remote.get() != track) { remote.set(track); track.addSink(sink); }
        }
        JSONObject stats=stats(call);
        audioPackets=stats.optLong("audioPackets"); videoBytes=stats.optLong("videoBytes");
        if(frames.get()>=10 && audioPackets>10 && videoBytes>0) break;
        Thread.sleep(1500);
      }
      if(frames.get()<10 || audioPackets<=10 || videoBytes<=0)
        throw new AssertionError("Remote native media incomplete: frames="+frames.get()+", audioPackets="+audioPackets+", videoBytes="+videoBytes+"; receiver="+stats(call)+"; outbound="+stats(call, "peer")+"; lastStatus="+status.get());
      if (owner) {
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
      result.putString("stream","PASS native SFU "+(owner?"owner":"peer")+": decodedVideoFrames="+frames.get()+", receivedAudioPackets="+audioPackets+", receivedVideoBytes="+videoBytes+"; emulated camera/microphone, not physical devices\n");
      // Do not hang up merely because this receiver is ready: the other receiver may
      // still be gathering ICE. The host releases both only after both measured media.
      File ready = new File(getTargetContext().getFilesDir(), "sfu-ready");
      File release = new File(getTargetContext().getFilesDir(), "sfu-release");
      java.nio.file.Files.write(ready.toPath(), "media-ready".getBytes("UTF-8"));
      long releaseDeadline = System.currentTimeMillis() + 150000;
      while (!release.exists() && System.currentTimeMillis() < releaseDeadline) Thread.sleep(500);
      if (!release.exists()) throw new AssertionError("Peer did not confirm media before hangup barrier");
      if (owner) { call.stop(); call=null; }
      else {
        final ChatFragment ui = fragment;
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
    } finally { if(call!=null) call.stop(); }
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
