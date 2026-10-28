package com.lyrnox.qingliao;

/** Pure formatting shared by the native reply preview and outgoing quoted text. */
final class ReplyDraft {
  private ReplyDraft() {}

  static String preview(String value) {
    if (value == null || value.trim().isEmpty()) return "媒体消息";
    String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
    return clean.length() > 160 ? clean.substring(0, 160) + "…" : clean;
  }

  static String prefix(String nickname, String value) {
    String name = nickname == null || nickname.trim().isEmpty() ? "匿名用户" : nickname.trim();
    String quoted = value == null || value.trim().isEmpty() ? "媒体消息" : value.trim();
    return "回复 " + name + "：" + quoted.substring(0, Math.min(160, quoted.length())) + "\n";
  }

  static String messageText(String prefix, String body) {
    return (prefix == null ? "" : prefix) + (body == null ? "" : body).trim();
  }
}
