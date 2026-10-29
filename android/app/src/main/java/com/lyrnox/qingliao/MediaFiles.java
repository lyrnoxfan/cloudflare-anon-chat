package com.lyrnox.qingliao;

import java.io.*;

/** Platform-independent bounded copies used by the system document flow. */
final class MediaFiles {
  static final long MAX_BYTES = 100L * 1024 * 1024;
  // Matches the web recorder cap; server upload limits remain authoritative.
  static final long MAX_VOICE_BYTES = 10L * 1024 * 1024;
  static final int MAX_VOICE_DURATION_MS = 2 * 60 * 60 * 1000;
  private MediaFiles() {}

  static String displayName(String value) {
    String name = value == null ? "" : value.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
    if (name.isEmpty() || name.equals(".") || name.equals("..")) return "文件";
    return name.length() > 180 ? name.substring(0, 180) : name;
  }

  static long copy(InputStream input, OutputStream output) throws IOException {
    if (input == null || output == null) throw new IOException("无法打开文件");
    byte[] buffer = new byte[32768];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      total += count;
      if (total > MAX_BYTES) throw new IOException("文件超过 100 MB");
      output.write(buffer, 0, count);
    }
    output.flush();
    return total;
  }

  static File snapshot(File source, File cache) throws IOException {
    if (!source.isFile() || source.length() > MAX_BYTES) throw new IOException("文件不可读取或超过 100 MB");
    File target = File.createTempFile("export-", ".tmp", cache);
    boolean success = false;
    try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
      long copied = copy(in, out);
      if (copied != source.length()) throw new IOException("文件在准备保存时发生变化");
      success = true;
      return target;
    } finally {
      if (!success) target.delete();
    }
  }
}
