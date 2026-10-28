package com.lyrnox.qingliao;

import org.json.JSONObject;

/** Contract boundary for POST /api/session: native code owns payload and accepted session state. */
final class SessionProtocol {
  interface Transport {
    HttpResponse post(String path, JSONObject body) throws Exception;
  }

  static final class HttpResponse {
    final int status;
    final String body;

    HttpResponse(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  private SessionProtocol() {}

  static JSONObject create(NativeCore core, Transport transport, String nickname) throws Exception {
    JSONObject request = core.run("session_request", "nickname", nickname);
    try {
      HttpResponse response = transport.post(request.getString("path"), request.getJSONObject("body"));
      return core.run(
          "session_response",
          "status", response.status,
          "body", response.body,
          "now", System.currentTimeMillis());
    } catch (Exception failure) {
      try {
        core.run("session_failed");
      } catch (Exception ignored) {
      }
      throw failure;
    }
  }
}
