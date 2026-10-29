package com.lyrnox.qingliao;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

/** Explicit opt-in native-client exercise against the local real Worker/D1/R2/DO runtime. */
final class ServiceIntegration {
  static String run(Context context) throws Exception {
    if (!BuildConfig.APPLICATION_ID.endsWith(".qa")
        || !"http://127.0.0.1:8000".equals(ChatNet.BASE))
      throw new SecurityException("Service test requires the isolated loopback QA build");
    int checks = 0;
    String rid = "";
    StringBuilder report = new StringBuilder();
    try (ChatNet a = new ChatNet(context, false);
        ChatNet b = new ChatNet(context, false);
        NativeCore core = new NativeCore()) {
      String suffix = UUID.randomUUID().toString().substring(0, 8),
          pw = UUID.randomUUID().toString();
      a.api("/api/session", core.run("session", "nickname", "Android-test-A-" + suffix));
      checks++;
      b.api("/api/session", core.run("session", "nickname", "Android-test-B-" + suffix));
      checks++;
      JSONObject room =
          a.api(
              "/api/rooms",
              core.run("room", "name", "Android native validation " + suffix, "password", pw));
      rid = room.getString("id");
      checks++;
      String path = "/api/r/" + rid + "/";
      try {
        b.api(path + "join", core.run("join", "password", "x"));
        throw new AssertionError("wrong password accepted");
      } catch (ChatNet.ApiError e) {
        if (e.status != 403) throw e;
        checks++;
      }
      try {
        b.api(path + "history", null);
        throw new AssertionError("unjoined private history accepted");
      } catch (ChatNet.ApiError e) { if (e.status != 403) throw e; checks++; }
      b.api(path + "join", core.run("join", "password", pw));
      checks++;
      CountDownLatch ready = new CountDownLatch(1),
          received = new CountDownLatch(1),
          deleted = new CountDownLatch(1);
      String body = "Android JNI 双端验证 😀 " + suffix;
      WebSocket socket =
          b.socket(
              rid,
              new WebSocketListener() {
                @Override
                public void onOpen(WebSocket s, Response r) {
                  ready.countDown();
                }

                @Override
                public void onMessage(WebSocket s, String text) {
                  try {
                    JSONObject e = new JSONObject(text);
                    if (e.optString("type").equals("message")
                        && e.getJSONObject("message").optString("text").equals(body))
                      received.countDown();
                    if (e.optString("type").equals("deleted")) deleted.countDown();
                  } catch (Exception ignored) {
                  }
                }
              });
      try {
        if (!ready.await(30, TimeUnit.SECONDS)) throw new IOException("WebSocket not opened");
        checks++;
        String cid = UUID.randomUUID().toString();
        JSONObject payload = core.run("send", "text", body, "clientId", cid);
        JSONObject message = a.api(path + "send", payload).getJSONObject("message");
        checks++;
        JSONObject duplicate = a.api(path + "send", payload).getJSONObject("message");
        if (!duplicate.getString("id").equals(message.getString("id")))
          throw new AssertionError("send idempotency");
        checks++;
        if (!received.await(30, TimeUnit.SECONDS))
          throw new AssertionError("peer WebSocket message missing");
        checks++;
        JSONObject history = b.api(path + "history", null);
        boolean found = false;
        for (int i = 0; i < history.getJSONArray("messages").length(); i++) {
          JSONObject m = history.getJSONArray("messages").getJSONObject(i);
          if (m.getString("id").equals(message.getString("id"))) {
            found = true;
            if (m.getBoolean("mine")) throw new AssertionError("peer ownership");
          }
        }
        if (!found) throw new AssertionError("history missing message");
        checks++;
        core.run("reset", "room", rid);
        core.run("history", "messages", history.getJSONArray("messages"));
        checks++;
        try {
          b.api(path + "delete/" + message.getString("id"), new JSONObject());
          throw new AssertionError("peer deletion accepted");
        } catch (ChatNet.ApiError e) { if (e.status != 403) throw e; checks++; }
        a.api(path + "delete/" + message.getString("id"), new JSONObject());
        if (!deleted.await(30, TimeUnit.SECONDS)) throw new AssertionError("delete event missing");
        checks++;
        File image = File.createTempFile("integration-", ".png", context.getCacheDir());
        try {
          Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
          bitmap.eraseColor(0xff54734b);
          try (FileOutputStream out = new FileOutputStream(image)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
          }
          bitmap.recycle();
          JSONObject grant =
              a.api(
                  path + "upload",
                  core.run(
                      "upload",
                      "size",
                      image.length(),
                      "mime",
                      "image/png",
                      "filename",
                      "validation.png",
                      "clientId",
                      UUID.randomUUID().toString()));
          a.put(grant.getString("url"), image, "image/png");
          JSONObject media =
              a.api(path + "confirm-upload/" + grant.getString("id"), new JSONObject())
                  .getJSONObject("message");
          checks++;
          File copy = b.download(path + "download/" + media.getString("id"), 1024 * 1024);
          try {
            if (!Arrays.equals(
                java.nio.file.Files.readAllBytes(image.toPath()),
                java.nio.file.Files.readAllBytes(copy.toPath())))
              throw new AssertionError("media bytes");
            checks++;
          } finally {
            copy.delete();
          }
          a.api(path + "delete/" + media.getString("id"), new JSONObject());
          checks++;
        } finally {
          image.delete();
        }
      } finally {
        socket.close(1000, "test complete");
      }
      report
          .append(checks)
          .append(" local Worker/D1/R2/DO assertions passed; isolated room ")
          .append(rid)
          .append(" remains; text/image test messages were deleted by their test author.\n");
    }
    return report.toString();
  }
}
