package com.lyrnox.qingliao;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.webrtc.*;

/**
 * Native SFU participant: publishes local tracks and subscribes to ready remote tracks using the
 * repository's existing call signalling contract. Network/WebRTC work stays off the UI thread.
 */
final class NativeCallSession {
  interface Listener {
    void onStatus(String message);
    void onFailure(String message);
    void onRemoteVideo(VideoTrack track, EglBase.Context eglContext);
    void onLocalVideo(VideoTrack track, EglBase.Context eglContext);
  }

  private static final Object INIT_LOCK = new Object();
  private static boolean initialized;
  private final Context context;
  private final NativeCore core;
  private final ChatNet net;
  private final String room;
  private final String selfId;
  private final Listener listener;
  private final AtomicBoolean stopRequested = new AtomicBoolean(false);
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
  private final Set<String> subscribedMembers = ConcurrentHashMap.newKeySet();

  private String kind, callId, cap;
  private boolean owner, joined;
  private boolean microphoneEnabled = true, cameraEnabled = true, frontFacing = true;
  private PeerConnectionFactory factory;
  private PeerConnection peer;
  private PeerConnection subscriberPeer;
  private EglBase egl;
  private List<PeerConnection.IceServer> iceServers = Collections.emptyList();
  private final Map<String, String> subscribedMidTrack = new ConcurrentHashMap<>();
  private VideoTrack activeRemoteVideo;
  private long subscriberStartedAt;
  private boolean subscriberReceivedMedia;
  private int subscriberRecoveries;
  private SurfaceTextureHelper textureHelper;
  private CameraVideoCapturer capturer;
  private AudioSource audioSource;
  private AudioTrack audioTrack;
  private VideoSource videoSource;
  private VideoTrack videoTrack;

  NativeCallSession(Context context, NativeCore core, ChatNet net, String room, String selfId, Listener listener) {
    this.context = context.getApplicationContext();
    this.core = core;
    this.net = net;
    this.room = room;
    this.selfId = selfId;
    this.listener = listener;
  }

  static JSONObject startPayload(String kind, String targetId) throws JSONException {
    JSONObject body = new JSONObject().put("kind", kind);
    if (targetId != null && !targetId.isEmpty()) body.put("target", targetId);
    return body;
  }

  /** Called on the app's I/O executor. It never performs network or WebRTC work on the UI thread. */
  synchronized void start(String requestedKind, String expectedCallId, String targetId) throws Exception {
    if (!"audio".equals(requestedKind) && !"video".equals(requestedKind))
      throw new IllegalArgumentException("通话类型无效");
    kind = requestedKind;
    try {
      core.run("call_begin", "kind", kind);
      JSONObject current = net.callApi(room, "call-state", null, null).optJSONObject("call");
      JSONObject call;
      if (expectedCallId == null || expectedCallId.isEmpty()) {
        if (current != null) throw new IOException("房间已有通话，请从聊天顶部选择加入");
        JSONObject started = net.callApi(room, "call-start", startPayload(kind, targetId), null);
        call = started.getJSONObject("call");
      } else {
        if (current == null || !expectedCallId.equals(current.optString("id")))
          throw new IOException("通话已结束或发生变化，请重新查看邀请");
        if (!kind.equals(current.optString("kind")))
          throw new IOException("通话类型发生变化");
        if (current.optBoolean("joined"))
          throw new IOException("当前会话已在通话中，请先退出原通话");
        call = current;
      }
      core.run("call_started", "call", call);
      callId = call.getString("id");
      owner = !selfId.isEmpty() && selfId.equals(call.optString("owner"));
      checkStopped();

      JSONObject joinResponse = net.callApi(room, "call-join", new JSONObject().put("callId", callId), null);
      JSONObject joinedCall = joinResponse.getJSONObject("call");
      JSONObject state = core.run("call_joined", "call", joinedCall, "iceServers", joinResponse.getJSONArray("iceServers"));
      if (!callId.equals(state.optString("id"))) throw new IOException("通话标识发生变化");
      cap = joinedCall.getString("cap");
      joined = true;
      checkStopped();

      iceServers = parseIceServers(joinResponse.getJSONArray("iceServers"));
      publish(joinResponse.getJSONArray("iceServers"));
      checkStopped();
      awaitConnected(peer, 20000);
      net.callApi(room, "call-ready", new JSONObject().put("callId", callId), cap);
      core.run("call_ready");
      listener.onStatus("本机媒体已发布，正在等待其他参会者并订阅其媒体");
      heartbeat.scheduleAtFixedRate(this::beat, 12, 12, TimeUnit.SECONDS);
      heartbeat.scheduleWithFixedDelay(this::pollRemoteMembers, 2, 3, TimeUnit.SECONDS);
    } catch (Exception e) {
      try { core.run("call_failed"); } catch (Exception ignored) {}
      cleanupLocal();
      signalCleanup();
      throw e;
    }
  }

  private void publish(JSONArray rawIceServers) throws Exception {
    egl = EglBase.create();
    initializeFactory();
    if (iceServers.isEmpty()) iceServers = parseIceServers(rawIceServers);
    if (iceServers.isEmpty()) throw new IOException("通话服务没有提供 ICE 服务器");
    peer = factory.createPeerConnection(rtcConfiguration(), new NoopObserver());
    if (peer == null) throw new IOException("无法建立 WebRTC 连接");

    MediaConstraints audioConstraints = new MediaConstraints();
    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
    audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
    audioSource = factory.createAudioSource(audioConstraints);
    audioTrack = factory.createAudioTrack("local-audio", audioSource);
    peer.addTransceiver(audioTrack, new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));

    if ("video".equals(kind)) {
      createCameraTrack();
      peer.addTransceiver(videoTrack, new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));
    }

    SessionDescription offer = awaitSdp((observer, constraints) -> peer.createOffer(observer, constraints));
    awaitSdp((observer, ignored) -> peer.setLocalDescription(observer, offer));
    awaitIceGathering(peer, 12000);
    SessionDescription local = peer.getLocalDescription();
    if (local == null || local.description == null || local.description.length() >= 60000)
      throw new IOException("本机 SDP 无效或超过服务限制");

    JSONArray tracks = new JSONArray();
    List<RtpTransceiver> transceivers = peer.getTransceivers();
    for (RtpTransceiver transceiver : transceivers) {
      String mediaKind = transceiver.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO ? "audio" : "video";
      if ("video".equals(kind) || "audio".equals(mediaKind)) {
        String mid = transceiver.getMid();
        if (mid == null || mid.isEmpty()) throw new IOException("WebRTC 没有为媒体轨道分配 MID");
        tracks.put(new JSONObject().put("kind", mediaKind).put("mid", mid));
      }
    }
    JSONObject publishRequest = new JSONObject()
        .put("callId", callId)
        .put("sessionDescription", new JSONObject().put("type", "offer").put("sdp", local.description))
        .put("tracks", tracks);
    JSONObject response = net.callApi(room, "call-publish", publishRequest, cap);
    JSONObject accepted = core.run("call_published", "response", response);
    JSONObject answer = accepted.getJSONObject("sessionDescription");
    String answerSdp = answer.getString("sdp");
    awaitSdp((observer, ignored) -> peer.setRemoteDescription(observer,
        new SessionDescription(SessionDescription.Type.ANSWER, answerSdp)));
  }

  private void createCameraTrack() throws Exception {
    if (!Camera2Enumerator.isSupported(context)) throw new IOException("当前设备不支持 Camera2 视频采集");
    Camera2Enumerator cameras = new Camera2Enumerator(context);
    String selected = null;
    for (String name : cameras.getDeviceNames()) {
      if (cameras.isFrontFacing(name)) { selected = name; break; }
      if (selected == null) selected = name;
    }
    if (selected == null) throw new IOException("未发现可用摄像头");
    frontFacing = cameras.isFrontFacing(selected);
    capturer = cameras.createCapturer(selected, null);
    if (capturer == null) throw new IOException("无法打开摄像头");
    textureHelper = SurfaceTextureHelper.create("QingliaoCamera", egl.getEglBaseContext());
    videoSource = factory.createVideoSource(false);
    capturer.initialize(textureHelper, context, videoSource.getCapturerObserver());
    capturer.startCapture(640, 360, 20);
    videoTrack = factory.createVideoTrack("local-video", videoSource);
    listener.onLocalVideo(videoTrack, egl.getEglBaseContext());
  }

  private void initializeFactory() {
    synchronized (INIT_LOCK) {
      if (!initialized) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions());
        initialized = true;
      }
    }
    // A camera track alone does not install Android video codecs. Both publisher
    // encoding and subscriber decoding need explicit factories sharing our EGL context.
    factory = PeerConnectionFactory.builder()
        .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
        .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
        .createPeerConnectionFactory();
    if (factory == null) throw new IllegalStateException("无法初始化 WebRTC 媒体引擎");
  }

  private PeerConnection.RTCConfiguration rtcConfiguration() {
    PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServers);
    configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
    configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
    return configuration;
  }

  private List<PeerConnection.IceServer> parseIceServers(JSONArray servers) throws Exception {
    List<PeerConnection.IceServer> result = new ArrayList<>();
    for (int i = 0; i < servers.length(); i++) {
      JSONObject server = servers.getJSONObject(i);
      Object urlsValue = server.get("urls");
      List<String> urls = new ArrayList<>();
      if (urlsValue instanceof String) urls.add((String) urlsValue);
      else if (urlsValue instanceof JSONArray) {
        JSONArray array = (JSONArray) urlsValue;
        for (int j = 0; j < array.length(); j++) urls.add(array.getString(j));
      }
      if (urls.isEmpty()) continue;
      PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urls);
      String username = server.optString("username", "");
      String credential = server.optString("credential", "");
      if (!username.isEmpty()) builder.setUsername(username);
      if (!credential.isEmpty()) builder.setPassword(credential);
      result.add(builder.createIceServer());
    }
    return result;
  }

  private interface SdpAction { void run(SdpObserver observer, MediaConstraints constraints); }
  private SessionDescription awaitSdp(SdpAction action) throws Exception {
    CompletableFuture<SessionDescription> future = new CompletableFuture<>();
    action.run(new SdpObserver() {
      @Override public void onCreateSuccess(SessionDescription sdp) { future.complete(sdp); }
      @Override public void onSetSuccess() { future.complete(null); }
      @Override public void onCreateFailure(String error) { future.completeExceptionally(new IOException(error)); }
      @Override public void onSetFailure(String error) { future.completeExceptionally(new IOException(error)); }
    }, new MediaConstraints());
    try { return future.get(15, TimeUnit.SECONDS); }
    catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw new IOException("WebRTC SDP 操作失败");
    }
  }

  private void awaitIceGathering(PeerConnection connection, long timeoutMs) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeoutMs;
    while (connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE &&
        SystemClock.elapsedRealtime() < end) {
      checkStopped();
      Thread.sleep(50);
    }
  }

  private void awaitConnected(PeerConnection connection, long timeoutMs) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeoutMs;
    while (SystemClock.elapsedRealtime() < end) {
      checkStopped();
      PeerConnection.PeerConnectionState state = connection.connectionState();
      if (state == PeerConnection.PeerConnectionState.CONNECTED) return;
      if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED)
        throw new IOException("WebRTC 媒体连接失败");
      Thread.sleep(100);
    }
    throw new IOException("媒体连接超时，请检查网络或 TURN 配置");
  }

  private synchronized void beat() {
    if (stopRequested.get() || !joined) return;
    try {
      JSONObject fresh = net.callApi(room, "call-heartbeat", new JSONObject().put("callId", callId), cap).getJSONObject("call");
      if (!callId.equals(fresh.optString("id")) || !fresh.optBoolean("joined"))
        throw new IOException("通话授权或连接已过期，请重新加入");
      cap = fresh.getString("cap");
    }
    catch (Exception e) { failCall("通话心跳失败：" + safeMessage(e)); }
  }

  private synchronized void pollRemoteMembers() {
    if (stopRequested.get() || !joined || callId == null) return;
    try {
      JSONObject snapshot = net.callApi(room, "call-state", null, null).optJSONObject("call");
      if (snapshot == null || !callId.equals(snapshot.optString("id")))
        throw new IOException("通话已结束或发生变化");
      if (!snapshot.optBoolean("joined")) throw new IOException("通话授权或连接已过期，请重新加入");
      cap = snapshot.getString("cap");
      JSONArray members = snapshot.optJSONArray("members");
      if (members == null) return;
      Set<String> current = new HashSet<>();
      for (int i = 0; i < members.length(); i++) {
        JSONObject member = members.optJSONObject(i);
        if (member == null) continue;
        String id = member.optString("id", "");
        JSONArray tracks = member.optJSONArray("tracks");
        if (!id.isEmpty() && !id.equals(selfId) && tracks != null && tracks.length() > 0) current.add(id);
      }
      // As on the web, remove obsolete receiver tracks when a participant leaves/rejoins.
      if (!current.containsAll(subscribedMembers)) resetSubscriber();
      if (subscriberPeer != null && !subscribedMembers.isEmpty()) {
        PeerConnection.PeerConnectionState state = subscriberPeer.connectionState();
        if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED)
          throw new IOException("远端媒体连接中断");
        if (!subscriberReceivedMedia) {
          subscriberReceivedMedia = hasInboundMedia(subscriberPeer);
          if (!subscriberReceivedMedia && SystemClock.elapsedRealtime() - subscriberStartedAt > 30000)
            throw new IOException("远端连接未收到媒体，正在重建接收连接");
        }
      }
      JSONArray subscribe = new JSONArray();
      Set<String> requested = new HashSet<>();
      for (int i = 0; i < members.length(); i++) {
        JSONObject member = members.optJSONObject(i);
        if (member == null) continue;
        String id = member.optString("id", "");
        JSONArray tracks = member.optJSONArray("tracks");
        if (!id.isEmpty() && !id.equals(selfId) && tracks != null && tracks.length() > 0
            && !subscribedMembers.contains(id)) {
          subscribe.put(id);
          requested.add(id);
        }
      }
      if (subscribe.length() == 0) return;
      if (subscribe.length() >= 16) throw new IOException("通话参与人数超出接收上限");
      JSONObject response = net.callApi(room, "call-subscribe",
          new JSONObject().put("callId", callId).put("members", subscribe), cap);
      JSONObject accepted = core.run("call_subscribed", "response", response);
      JSONObject offer = accepted.getJSONObject("sessionDescription");
      JSONArray tracks = accepted.getJSONArray("tracks");
      for (int i = 0; i < tracks.length(); i++) {
        JSONObject track = tracks.getJSONObject(i);
        String mid = track.getString("mid"), trackName = track.getString("trackName");
        if (mid.length() > 16 || trackName.length() > 160)
          throw new IOException("SFU 远端轨道标识无效");
        subscribedMidTrack.put(mid, trackName);
      }
      negotiateSubscription(offer.getString("sdp"));
      for (String id : requested) subscribedMembers.add(id);
      listener.onStatus("远端 WebRTC 连接已建立");
    } catch (Exception e) {
      if (stopRequested.get()) return;
      // Reset only this participant's SFU subscriber, never another publisher or the call.
      // This is also a safe reconciliation after an uncertain subscribe/answer response.
      if (subscriberRecoveries++ < 2) {
        try {
          resetSubscriber();
          listener.onStatus("正在重新连接远端媒体：" + safeMessage(e));
        } catch (Exception resetError) { failCall("远端媒体恢复失败：" + safeMessage(resetError)); }
      } else failCall("远端媒体连接失败，请重新加入：" + safeMessage(e));
    }
  }

  private boolean hasInboundMedia(PeerConnection connection) throws Exception {
    CompletableFuture<Boolean> received = new CompletableFuture<>();
    connection.getStats(report -> {
      boolean media = false;
      for (RTCStats stat : report.getStatsMap().values()) {
        if (!"inbound-rtp".equals(stat.getType())) continue;
        Object packets = stat.getMembers().get("packetsReceived");
        if (packets instanceof Number && ((Number) packets).longValue() > 0) { media = true; break; }
      }
      received.complete(media);
    });
    return received.get(3, TimeUnit.SECONDS);
  }

  private void resetSubscriber() throws Exception {
    checkStopped();
    // Refresh the lease before any potentially slow negotiation/reconciliation.
    JSONObject fresh = net.callApi(room, "call-heartbeat", new JSONObject().put("callId", callId), cap).getJSONObject("call");
    cap = fresh.getString("cap");
    net.callApi(room, "call-reset-subscriber", new JSONObject().put("callId", callId), cap);
    closeSubscriber();
    core.run("call_subscriber_reset");
  }

  private void closeSubscriber() {
    if (activeRemoteVideo != null) {
      try { listener.onRemoteVideo(null, null); } catch (Exception ignored) {}
      activeRemoteVideo = null;
    }
    if (subscriberPeer != null) {
      try { subscriberPeer.close(); } catch (Exception ignored) {}
      try { subscriberPeer.dispose(); } catch (Exception ignored) {}
      subscriberPeer = null;
    }
    subscribedMembers.clear();
    subscribedMidTrack.clear();
    subscriberStartedAt = 0;
    subscriberReceivedMedia = false;
  }

  private void negotiateSubscription(String offerSdp) throws Exception {
    checkStopped();
    if (subscriberPeer == null) {
      subscriberPeer = factory.createPeerConnection(rtcConfiguration(), new RemoteObserver());
      if (subscriberPeer == null) throw new IOException("无法建立远端媒体接收连接");
    }
    awaitSdp((observer, ignored) -> subscriberPeer.setRemoteDescription(observer,
        new SessionDescription(SessionDescription.Type.OFFER, offerSdp)));
    SessionDescription answer = awaitSdp((observer, constraints) -> subscriberPeer.createAnswer(observer, constraints));
    if (answer == null) throw new IOException("WebRTC 没有生成远端媒体应答");
    awaitSdp((observer, ignored) -> subscriberPeer.setLocalDescription(observer, answer));
    awaitIceGathering(subscriberPeer, 12000);
    SessionDescription local = subscriberPeer.getLocalDescription();
    if (local == null || local.description == null || local.description.length() >= 60000)
      throw new IOException("本机远端应答无效或超过服务限制");
    net.callApi(room, "call-answer",
        new JSONObject().put("callId", callId)
            .put("sessionDescription", new JSONObject().put("type", "answer").put("sdp", local.description)), cap);
    core.run("call_answered");
    // A successful HTTP answer and onTrack are not proof of an established receiver.
    awaitConnected(subscriberPeer, 12000);
    subscriberStartedAt = SystemClock.elapsedRealtime();
    subscriberReceivedMedia = false;
  }

  private synchronized void failCall(String message) {
    if (!stopRequested.compareAndSet(false, true)) return;
    heartbeat.shutdownNow();
    cleanupLocal();
    try { core.run("call_failed"); } catch (Exception ignored) {}
    signalCleanup();
    listener.onRemoteVideo(null, null);
    listener.onFailure(message);
  }

  void requestStop() { stopRequested.set(true); }

  synchronized void stop() {
    requestStop();
    heartbeat.shutdownNow();
    cleanupLocal();
    signalCleanup();
    try { core.run("call_cleanup"); } catch (Exception ignored) {}
    listener.onRemoteVideo(null, null);
  }

  private void signalCleanup() {
    if (callId == null || callId.isEmpty()) return;
    try {
      if (joined) net.callApi(room, "call-leave", new JSONObject().put("callId", callId), cap);
      else if (owner) net.callApi(room, "call-cancel", new JSONObject().put("callId", callId), null);
    } catch (Exception ignored) { /* the service lease/alarm owns eventual server cleanup */ }
    joined = false;
    callId = null;
    cap = null;
  }

  private void cleanupLocal() {
    closeSubscriber();
    if (peer != null) {
      try { peer.close(); } catch (Exception ignored) {}
      try { peer.dispose(); } catch (Exception ignored) {}
      peer = null;
    }
    if (capturer != null) {
      try { capturer.stopCapture(); } catch (Exception ignored) {}
      try { capturer.dispose(); } catch (Exception ignored) {}
      capturer = null;
    }
    if (textureHelper != null) { textureHelper.dispose(); textureHelper = null; }
    if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; }
    if (videoSource != null) { videoSource.dispose(); videoSource = null; }
    if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
    if (audioSource != null) { audioSource.dispose(); audioSource = null; }
    if (factory != null) { factory.dispose(); factory = null; }
    if (egl != null) { egl.release(); egl = null; }
  }

  synchronized boolean isMicrophoneReady() { return audioTrack != null; }
  synchronized boolean isCameraReady() { return videoTrack != null; }
  synchronized boolean isMicrophoneEnabled() { return microphoneEnabled; }
  synchronized boolean isCameraEnabled() { return cameraEnabled; }
  synchronized boolean isFrontFacing() { return frontFacing; }

  synchronized boolean toggleMicrophone() throws IOException {
    if (audioTrack == null) throw new IOException("麦克风轨道尚未就绪");
    microphoneEnabled = !microphoneEnabled;
    audioTrack.setEnabled(microphoneEnabled);
    return microphoneEnabled;
  }

  synchronized boolean toggleCamera() throws IOException {
    if (videoTrack == null) throw new IOException("摄像头轨道尚未就绪");
    cameraEnabled = !cameraEnabled;
    videoTrack.setEnabled(cameraEnabled);
    return cameraEnabled;
  }

  synchronized boolean flipCamera() throws IOException {
    if (capturer == null || videoTrack == null) throw new IOException("摄像头轨道尚未就绪");
    CountDownLatch switched = new CountDownLatch(1);
    AtomicReference<String> failure = new AtomicReference<>();
    AtomicBoolean nextFacing = new AtomicBoolean(frontFacing);
    try {
      capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
        @Override public void onCameraSwitchDone(boolean isFrontFacing) {
          nextFacing.set(isFrontFacing);
          switched.countDown();
        }
        @Override public void onCameraSwitchError(String errorDescription) {
          failure.set(errorDescription == null ? "未知错误" : errorDescription);
          switched.countDown();
        }
      });
      if (!switched.await(8, TimeUnit.SECONDS)) throw new IOException("切换摄像头超时");
      if (failure.get() != null) throw new IOException(failure.get());
      frontFacing = nextFacing.get();
      return frontFacing;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("切换摄像头已中断", e);
    } catch (RuntimeException e) {
      throw new IOException("无法切换摄像头", e);
    }
  }

  private void checkStopped() throws IOException {
    if (stopRequested.get()) throw new IOException("通话已停止");
  }

  private static String safeMessage(Exception e) {
    String message = e.getMessage();
    return message == null || message.isEmpty() ? "未知错误" : message;
  }

  private final class RemoteObserver extends NoopObserver {
    @Override public void onTrack(RtpTransceiver transceiver) {
      if (transceiver == null || transceiver.getReceiver() == null) return;
      MediaStreamTrack track = transceiver.getReceiver().track();
      if (track instanceof AudioTrack) {
        track.setEnabled(true);
      } else if (track instanceof VideoTrack) {
        VideoTrack incoming = (VideoTrack) track;
        incoming.setEnabled(true);
        activeRemoteVideo = incoming;
        listener.onRemoteVideo(incoming, egl == null ? null : egl.getEglBaseContext());
      }
    }
    @Override public void onRemoveTrack(RtpReceiver receiver) {
      MediaStreamTrack track = receiver == null ? null : receiver.track();
      if (track == activeRemoteVideo) {
        activeRemoteVideo = null;
        listener.onRemoteVideo(null, null);
      }
    }
  }

  private static class NoopObserver implements PeerConnection.Observer {
    @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
    @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
    @Override public void onIceConnectionReceivingChange(boolean receiving) {}
    @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
    @Override public void onIceCandidate(IceCandidate candidate) {}
    @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
    @Override public void onAddStream(MediaStream stream) {}
    @Override public void onRemoveStream(MediaStream stream) {}
    @Override public void onDataChannel(DataChannel channel) {}
    @Override public void onRenegotiationNeeded() {}
    @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) {}
  }
}
