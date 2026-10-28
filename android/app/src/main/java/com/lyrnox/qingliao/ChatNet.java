package com.lyrnox.qingliao;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.*;
import org.json.*;

final class ChatNet implements Closeable {
  static final String BASE = "https://chat.lyrnox.com";
  final OkHttpClient client;
  final Context context;
  final boolean durable;
  private final ArrayList<Cookie> cookies = new ArrayList<>();
  static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  static class ApiError extends IOException {
    final int status;

    ApiError(int s, String m) {
      super(m);
      status = s;
    }
  }

  ChatNet(Context ctx) {
    this(ctx, true);
  }

  ChatNet(Context ctx, boolean durable) {
    context = ctx.getApplicationContext();
    this.durable = durable;
    if (durable) restore();
    client =
        new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .cookieJar(
                new CookieJar() {
                  public void saveFromResponse(HttpUrl url, List<Cookie> list) {
                    if (!url.host().equals("chat.lyrnox.com")) return;
                    synchronized (cookies) {
                      for (Cookie c : list)
                        if (c.name().equals("chat")) {
                          cookies.clear();
                          cookies.add(c);
                          if (ChatNet.this.durable) persist(c.toString());
                        }
                    }
                  }

                  public List<Cookie> loadForRequest(HttpUrl url) {
                    synchronized (cookies) {
                      ArrayList<Cookie> out = new ArrayList<>();
                      for (Cookie c : cookies)
                        if (c.expiresAt() > System.currentTimeMillis() && c.matches(url))
                          out.add(c);
                      return out;
                    }
                  }
                })
            .build();
  }

  private javax.crypto.SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias("qingliao.session")) {
      KeyGenerator gen =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      gen.init(
          new KeyGenParameterSpec.Builder(
                  "qingliao.session", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      gen.generateKey();
    }
    return (javax.crypto.SecretKey) store.getKey("qingliao.session", null);
  }

  private void persist(String s) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key());
      String v =
          Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
              + ":"
              + Base64.encodeToString(c.doFinal(s.getBytes("UTF-8")), Base64.NO_WRAP);
      context.getSharedPreferences("secure", 0).edit().putString("session", v).apply();
    } catch (Exception ignored) {
      context.getSharedPreferences("secure", 0).edit().remove("session").apply();
    }
  }

  private void restore() {
    try {
      String v = context.getSharedPreferences("secure", 0).getString("session", "");
      if (v.isEmpty()) return;
      String[] parts = v.split(":");
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          key(),
          new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
      Cookie cookie =
          Cookie.parse(
              HttpUrl.get(BASE),
              new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8"));
      if (cookie != null && cookie.expiresAt() > System.currentTimeMillis()) cookies.add(cookie);
    } catch (Exception ignored) {
      context.getSharedPreferences("secure", 0).edit().clear().apply();
    }
  }

  SessionProtocol.HttpResponse sessionPost(String path, JSONObject body) throws Exception {
    if (!"/api/session".equals(path) || body == null || !body.has("nickname"))
      throw new IOException("会话请求格式异常");
    Request request =
        new Request.Builder()
            .url(BASE + path)
            .header("Origin", BASE)
            .post(RequestBody.create(body.toString(), JSON))
            .build();
    try (Response response = client.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      if (responseBody == null) throw new IOException("服务器没有返回内容");
      return new SessionProtocol.HttpResponse(response.code(), responseBody.string());
    }
  }

  SessionProtocol.HttpResponse messagePost(String path, JSONObject body) throws Exception {
    if (path == null || !path.matches("/api/r/[A-Za-z0-9-]{1,80}/send")
        || body == null || body.length() != 2 || !body.has("text") || !body.has("clientId")
        || body.optString("text").isEmpty() || body.optString("text").length() > 4000
        || body.optString("clientId").isEmpty() || body.optString("clientId").length() > 80)
      throw new IOException("消息请求格式异常");
    Request request =
        new Request.Builder()
            .url(BASE + path)
            .header("Origin", BASE)
            .post(RequestBody.create(body.toString(), JSON))
            .build();
    try (Response response = client.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      if (responseBody == null) throw new IOException("服务器没有返回内容");
      String raw = responseBody.string();
      if (raw.length() > 65536) throw new IOException("消息响应过大");
      return new SessionProtocol.HttpResponse(response.code(), raw);
    }
  }

  JSONObject api(String path, JSONObject body) throws Exception {
    if (!path.startsWith("/api/")) throw new IOException("接口路径无效");
    Request.Builder b = new Request.Builder().url(BASE + path).header("Origin", BASE);
    if (body != null) b.post(RequestBody.create(body.toString(), JSON));
    try (Response r = client.newCall(b.build()).execute()) {
      ResponseBody rb = r.body();
      if (rb == null) throw new IOException("服务器没有返回内容");
      String text = rb.string();
      JSONObject j;
      try {
        j = new JSONObject(text);
      } catch (JSONException e) {
        throw new ApiError(r.code(), "服务器响应格式异常（" + r.code() + "）");
      }
      if (!r.isSuccessful()) throw new ApiError(r.code(), j.optString("error", "请求失败"));
      return j;
    }
  }

  WebSocket socket(String room, WebSocketListener l) {
    return client.newWebSocket(
        new Request.Builder()
            .url(BASE.replace("https:", "wss:") + "/api/r/" + room + "/ws")
            .header("Origin", BASE)
            .build(),
        l);
  }

  private String allowed(String url, boolean upload) throws IOException {
    HttpUrl u = HttpUrl.parse(url.startsWith("/") ? BASE + url : url);
    if (u == null
        || !u.isHttps()
        || (!u.host().equals("chat.lyrnox.com")
            && !(upload && u.host().endsWith(".r2.cloudflarestorage.com"))))
      throw new IOException("媒体地址不受信任");
    return u.toString();
  }

  void put(String url, File file, String mime) throws Exception {
    Request r =
        new Request.Builder()
            .url(allowed(url, true))
            .header("Origin", BASE)
            .put(RequestBody.create(file, MediaType.get(mime)))
            .build();
    try (Response result = client.newCall(r).execute()) {
      if (!result.isSuccessful())
        throw new ApiError(result.code(), "文件上传失败（" + result.code() + "）");
    }
  }

  File download(String path, long cap) throws Exception {
    JSONObject ticket = api(path, null);
    Request r = new Request.Builder().url(allowed(ticket.getString("url"), false)).build();
    File f = File.createTempFile("media-", ".bin", context.getCacheDir());
    boolean ok = false;
    try (Response res = client.newCall(r).execute()) {
      if (!res.isSuccessful() || res.body() == null) throw new ApiError(res.code(), "媒体读取失败");
      try (InputStream in = res.body().byteStream();
          OutputStream out = new FileOutputStream(f)) {
        byte[] buf = new byte[32768];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
          total += n;
          if (total > cap) throw new IOException("文件超过下载限制");
          out.write(buf, 0, n);
        }
      }
      ok = true;
      return f;
    } finally {
      if (!ok) f.delete();
    }
  }

  @Override
  public void close() {
    client.dispatcher().cancelAll();
    client.connectionPool().evictAll();
    client.dispatcher().executorService().shutdown();
  }
}
