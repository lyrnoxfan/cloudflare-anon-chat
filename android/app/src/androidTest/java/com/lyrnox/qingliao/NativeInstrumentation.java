package com.lyrnox.qingliao;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;

/** Runs the shipped JNI library on the device; does not contact the network. */
public final class NativeInstrumentation extends Instrumentation {
  int checks;
  boolean network;

  void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++;
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    network = args != null && "true".equals(args.getString("network"));
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try (NativeCore core = new NativeCore()) {
      check(core.run("reset", "room", "lobby").getString("room").equals("lobby"), "reset");
      JSONObject expiredGate = core.run("gate", "expired", true, "hasSession", false);
      check(expiredGate.getBoolean("show") && !expiredGate.getBoolean("contentVisible"), "expired gate hides chat");
      JSONObject activeGate = core.run("gate", "expired", false, "hasSession", true);
      check(!activeGate.getBoolean("show") && activeGate.getBoolean("contentVisible"), "active session restores chat");
      JSONObject recoveredGate = core.run("gate", "expired", true, "hasSession", true);
      check(!recoveredGate.getBoolean("show") && recoveredGate.getBoolean("contentVisible"), "session takes precedence");
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
    } catch (Throwable e) {
      result.putString("stream", "FAILED " + e.toString() + "\n");
      finish(0, result);
    }
  }
}
