package com.lyrnox.qingliao;

import org.json.JSONObject;

/** Native-owned send-response contract for POST /api/r/{room}/send. */
final class MessageProtocol {
  private MessageProtocol() {}

  static JSONObject send(
      NativeCore core,
      SessionProtocol.Transport transport,
      String path,
      String room,
      String text,
      String clientId)
      throws Exception {
    JSONObject payload = core.run("send", "text", text, "clientId", clientId);
    if (room == null || !room.matches("[A-Za-z0-9-]{1,80}")
        || path == null || !path.equals("/api/r/" + room + "/send"))
      throw new java.io.IOException("消息接口路径无效");
    SessionProtocol.HttpResponse response = transport.post(path, payload);
    try {
      return core.run(
              "send_response",
              "status", response.status,
              "body", response.body,
              "clientId", payload.getString("clientId"),
              "text", payload.getString("text"),
              "room", room)
          .getJSONObject("message");
    } catch (Exception failure) {
      if (response.status < 200 || response.status >= 300)
        throw new ChatNet.ApiError(response.status, failure.getMessage());
      throw failure;
    }
  }
}
