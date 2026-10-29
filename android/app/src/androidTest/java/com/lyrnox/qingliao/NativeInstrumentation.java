package com.lyrnox.qingliao;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;

/** Runs the shipped JNI library on the device; does not contact the network. */
public final class NativeInstrumentation extends Instrumentation {
  int checks;
  boolean network;
  boolean mediaLifecycle;

  void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++;
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    network = args != null && "true".equals(args.getString("network"));
    mediaLifecycle = args != null && "true".equals(args.getString("mediaLifecycle"));
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      if (mediaLifecycle) {
        result.putString("stream", MediaLifecycleChecks.run(this));
        finish(-1, result);
        return;
      }
      NetworkRecoveryGate recovery = new NetworkRecoveryGate();
      recovery.reset(true);
      check(!recovery.observe(true), "initial online callback does not duplicate join");
      check(!recovery.observe(false), "network loss waits for return");
      check(recovery.observe(true), "validated network return triggers immediate rejoin");
      check(!recovery.observe(true), "duplicate online callbacks do not duplicate rejoin");
      recovery.reset(false);
      check(!recovery.observe(false), "offline startup stays pending");
      check(recovery.observe(true), "offline startup joins when validated internet arrives");
      check(!recovery.observe(true), "offline recovery is consumed once");
      try (NativeCore core = new NativeCore()) {
      check(core.run("reset", "room", "lobby").getString("room").equals("lobby"), "reset");
      check("回复 小青：你好\n".equals(ReplyDraft.prefix(" 小青 ", "你好")), "reply header matches web contract");
      String quoted160 = new String(new char[161]).replace("\0", "字");
      check(ReplyDraft.prefix("小青", quoted160).equals("回复 小青：" + quoted160.substring(0, 160) + "\n"), "outgoing reply quote matches web 160-character limit");
      check("媒体消息".equals(ReplyDraft.preview("  ")), "empty media reply gets a readable label");
      String longReply = ReplyDraft.preview(new String(new char[161]).replace("\0", "字"));
      check(longReply.length() == 161 && longReply.endsWith("…"), "reply preview truncates long source text");
      check("回复 小青：你好\n正文".equals(ReplyDraft.messageText(ReplyDraft.prefix("小青", "你好"), " 正文 ")), "reply sends quoted context plus typed body");
      check("正文".equals(ReplyDraft.messageText("", " 正文 ")), "cancelled reply sends only typed body");
      long now = 1_800_000_000_000L;
      check(MessageGrouping.sameGroup(false, "小青", now, false, "小青", now + 299_999), "same speaker groups within five minutes");
      check(!MessageGrouping.sameGroup(false, "小青", now, false, "小青", now + 300_000), "five-minute boundary ends group");
      check(!MessageGrouping.sameGroup(false, "小青", now, false, "小蓝", now + 1), "different speaker ends group");
      check(!MessageGrouping.sameGroup(false, "小青", now, true, "小青", now + 1), "mine status ends group");
      check("今天".equals(MessageGrouping.dayLabel(now, now)), "message date divider labels today");
      check("昨天".equals(MessageGrouping.dayLabel(now - 24L * 60 * 60 * 1000, now)), "message date divider labels yesterday");
      JSONObject targetedCall = NativeCallSession.startPayload("video", "guest-2");
      check("video".equals(targetedCall.getString("kind")) && "guest-2".equals(targetedCall.getString("target")), "direct call uses selected member id");
      JSONObject groupCallPayload = NativeCallSession.startPayload("audio", "");
      check(!groupCallPayload.has("target"), "group call omits direct target");
      JSONObject expiredGate = core.run("gate", "expired", true, "hasSession", false);
      check(expiredGate.getBoolean("show") && !expiredGate.getBoolean("contentVisible"), "expired gate hides chat");
      JSONObject activeGate = core.run("gate", "expired", false, "hasSession", true);
      check(!activeGate.getBoolean("show") && activeGate.getBoolean("contentVisible"), "active session restores chat");
      JSONObject recoveredGate = core.run("gate", "expired", true, "hasSession", true);
      check(!recoveredGate.getBoolean("show") && recoveredGate.getBoolean("contentVisible"), "session takes precedence");
      check("unknown".equals(core.run("session_state").getString("phase")) && core.run("session_state").getBoolean("showGate"), "unresolved session keeps entry gate visible");
      core.run("session_required");
      check(core.run("session_state").getBoolean("showGate"), "missing session requires nickname");
      JSONObject expiredSession = new JSONObject().put("id", "123e4567-e89b-12d3-a456-426614174000").put("nickname", "青").put("expires", System.currentTimeMillis() - 1);
      boolean expiredRejected = false;
      try { core.run("session_accept", "session", expiredSession, "now", System.currentTimeMillis()); } catch (Exception expected) { expiredRejected = true; }
      check(expiredRejected && "required".equals(core.run("session_state").getString("phase")), "expired response cannot open chat");
      JSONObject validSession = new JSONObject().put("id", "123e4567-e89b-12d3-a456-426614174000").put("nickname", " 青 ").put("expires", System.currentTimeMillis() + 60000);
      JSONObject acceptedSession = core.run("session_accept", "session", validSession, "now", System.currentTimeMillis());
      check("青".equals(acceptedSession.getString("nickname")) && core.run("session_state").getBoolean("contentVisible"), "validated session opens chat");
      JSONObject invalidSession = new JSONObject().put("id", "bad-id").put("nickname", "青").put("expires", System.currentTimeMillis() + 60000);
      boolean invalidRejected = false;
      try { core.run("session_accept", "session", invalidSession, "now", System.currentTimeMillis()); } catch (Exception expected) { invalidRejected = true; }
      check(invalidRejected && core.run("session_state").getBoolean("contentVisible"), "invalid response preserves last accepted session");
      JSONObject directInvite = core.run("call_invitation", "call",
          new JSONObject().put("id", "call-invite-1").put("kind", "video")
              .put("owner", "caller").put("target", "guest-2")
              .put("invited", true).put("joined", false), "self", "guest-2");
      check(directInvite.getBoolean("eligible") && directInvite.getBoolean("invited"), "targeted incoming call is eligible to answer");
      JSONObject wrongTarget = core.run("call_invitation", "call",
          new JSONObject().put("id", "call-invite-2").put("kind", "audio")
              .put("owner", "caller").put("target", "guest-2")
              .put("invited", false).put("joined", false), "self", "guest-3");
      check(!wrongTarget.getBoolean("eligible"), "direct call invitation is hidden from other guests");
      JSONObject groupCall = core.run("call_invitation", "call",
          new JSONObject().put("id", "call-group-1").put("kind", "audio")
              .put("owner", "caller").put("target", JSONObject.NULL)
              .put("invited", false).put("joined", false), "self", "guest-3");
      check(groupCall.getBoolean("eligible") && !groupCall.getBoolean("invited"), "group call can be joined without a direct invitation");
      JSONObject joinedCall = core.run("call_invitation", "call",
          new JSONObject().put("id", "call-joined-1").put("kind", "audio")
              .put("owner", "caller").put("target", "guest-2")
              .put("invited", true).put("joined", true), "self", "guest-2");
      check(joinedCall.getBoolean("eligible") && joinedCall.getBoolean("joined"), "already joined session is not offered as incoming call");
      core.run("session_required");
      check(core.run("session_state").getBoolean("showGate"), "expired session hides chat again");
      check(
          core.run("session", "nickname", "你好😀").getString("nickname").equals("你好😀"),
          "JNI Unicode");
      check(
          core.run("send", "text", "[微笑] 😀 中文\n\"quote\"", "clientId", "id-1")
              .getString("text")
              .contains("😀"),
          "JSON and supplementary Unicode");
      check(core.run("join", "password", "a").getString("password").equals("a"), "short password");
      boolean rejected = false;
      try {
        core.run("reset", "room", "../evil");
      } catch (Exception expected) {
        rejected = true;
      }
      check(rejected, "invalid room");
      rejected = false;
      try {
        core.run("session", "nickname", new String(new char[] {0xd800}));
      } catch (Exception expected) {
        rejected = true;
      }
      check(rejected, "unpaired surrogate");
      core.run("session_required");
      JSONObject created =
          SessionProtocol.create(
              core,
              (path, body) -> {
                check("/api/session".equals(path), "session mock receives contract path");
                check("Mock guest".equals(body.getString("nickname")), "native request trims nickname");
                JSONObject limits =
                    new JSONObject()
                        .put("text", 4000).put("nickname", 32).put("roomName", 80)
                        .put("passwordMin", 1).put("passwordMax", 128)
                        .put("image", 10 * 1024 * 1024).put("audio", 10 * 1024 * 1024)
                        .put("video", 100 * 1024 * 1024).put("file", 50 * 1024 * 1024)
                        .put("members", 8).put("sessionMs", 604800000)
                        .put("grantMs", 7200000).put("uploadMs", 120000).put("callLeaseMs", 45000);
                JSONObject response =
                    new JSONObject()
                        .put("id", "123e4567-e89b-12d3-a456-426614174000")
                        .put("nickname", " Mock guest ")
                        .put("expires", System.currentTimeMillis() + 60000)
                        .put("limits", limits)
                        .put("sfu", false);
                return new SessionProtocol.HttpResponse(200, response.toString());
              },
              " Mock guest ");
      check("Mock guest".equals(created.getString("nickname")), "local API mock opens native session");
      check(core.run("session_state").getBoolean("contentVisible"), "mock session response opens chat state");
      core.run("session_required");
      boolean mockUnauthorized = false;
      try {
        SessionProtocol.create(
            core,
            (path, body) ->
                new SessionProtocol.HttpResponse(401, "{\"error\":\"请先输入昵称\"}"),
            "Retry");
      } catch (Exception expected) {
        mockUnauthorized = true;
      }
      check(mockUnauthorized && core.run("session_state").getBoolean("showGate"), "mock 401 keeps nickname gate visible");
      core.run("reset", "room", "lobby");
      String quotedSend = ReplyDraft.messageText(ReplyDraft.prefix("来访者", "引用文本"), "hello Android");
      JSONObject sent =
          MessageProtocol.send(
              core,
              (path, body) -> {
                check("/api/r/lobby/send".equals(path), "message mock receives actual send contract");
                check(quotedSend.equals(body.getString("text")), "reply quote reaches send API with typed message");
                check("mock-client-1".equals(body.getString("clientId")), "client id preserved for idempotency");
                JSONObject message = new JSONObject()
                    .put("id", "server-message-1")
                    .put("nickname", "Mock guest")
                    .put("client_id", "mock-client-1")
                    .put("kind", "text")
                    .put("text", quotedSend)
                    .put("created", 1700000000000L)
                    .put("mine", true)
                    .put("deleted", 0);
                return new SessionProtocol.HttpResponse(200, new JSONObject().put("message", message).toString());
              },
              "/api/r/lobby/send",
              "lobby",
              quotedSend,
              "mock-client-1");
      check(quotedSend.equals(sent.getString("text")), "native state accepts the complete quoted reply");
      check("server-message-1".equals(sent.getString("id")), "mock send response accepted by C++ core");
      check(core.run("snapshot").getJSONArray("messages").length() == 1, "send response immediately updates native message state");
      JSONObject incoming = new JSONObject()
          .put("id", "server-message-2")
          .put("nickname", "Other guest")
          .put("client_id", "other-client")
          .put("kind", "text")
          .put("text", "received over realtime event")
          .put("created", 1700000000001L)
          .put("mine", false)
          .put("deleted", 0);
      core.run("event", "event", new JSONObject().put("type", "message").put("message", incoming));
      check(core.run("snapshot").getJSONArray("messages").length() == 2, "realtime receive merges into native message state");
      boolean sendUnauthorized = false;
      try {
        MessageProtocol.send(
            core,
            (path, body) -> new SessionProtocol.HttpResponse(401, "{\"error\":\"请先输入昵称\"}"),
            "/api/r/lobby/send",
            "lobby",
            "will not be stored",
            "mock-client-2");
      } catch (ChatNet.ApiError expected) {
        sendUnauthorized = expected.status == 401;
      }
      check(sendUnauthorized, "mock 401 is surfaced as API failure");
      check(core.run("snapshot").getJSONArray("messages").length() == 2, "failed send does not mutate message state");
      boolean malformedResponse = false;
      try {
        MessageProtocol.send(
            core,
            (path, body) -> new SessionProtocol.HttpResponse(200, "{\"message\":{}}"),
            "/api/r/lobby/send",
            "lobby",
            "bad response",
            "mock-client-3");
      } catch (Exception expected) {
        malformedResponse = true;
      }
      check(malformedResponse && core.run("snapshot").getJSONArray("messages").length() == 2, "malformed 200 response is rejected without state change");
      core.run("reset", "room", "lobby");
      JSONArray list =
          new JSONArray()
              .put(
                  new JSONObject()
                      .put("id", "m1")
                      .put("created", 2)
                      .put("kind", "text")
                      .put("text", "one")
                      .put("deleted", 0));
      core.run("history", "messages", list);
      core.run("history", "messages", list);
      check(core.run("snapshot").getJSONArray("messages").length() == 1, "dedup");
      core.run("event", "event", new JSONObject().put("type", "deleted").put("id", "m1"));
      core.run("history", "messages", list);
      check(
          core.run("snapshot").getJSONArray("messages").getJSONObject(0).getInt("deleted") == 1,
          "tombstone");
      String service =
          network
              ? ServiceIntegration.run(getTargetContext())
              : "Network exercise not requested.\n";
      result.putString("stream", checks + " Android JNI assertions passed\n" + service);
      finish(-1, result);
      }
    } catch (Throwable e) {
      result.putString("stream", "FAILED " + e.toString() + "\n");
      finish(0, result);
    }
  }
}
