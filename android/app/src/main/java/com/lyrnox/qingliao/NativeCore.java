package com.lyrnox.qingliao;

import org.json.*;

final class NativeCore implements AutoCloseable {
  static {
    System.loadLibrary("qingliao");
  }

  private long handle = create();

  private static native long create();

  private static native String execute(long handle, String input);

  private static native void destroy(long handle);

  synchronized JSONObject run(String op, Object... pairs) throws Exception {
    JSONObject j = new JSONObject();
    j.put("op", op);
    for (int i = 0; i < pairs.length; i += 2) j.put((String) pairs[i], pairs[i + 1]);
    JSONObject result = new JSONObject(execute(handle, j.toString()));
    if (!result.optBoolean("ok")) throw new Exception(result.optString("error", "核心错误"));
    return result.getJSONObject("value");
  }

  @Override
  public synchronized void close() {
    if (handle != 0) {
      destroy(handle);
      handle = 0;
    }
  }
}
