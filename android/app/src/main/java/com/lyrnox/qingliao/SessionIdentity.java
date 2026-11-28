package com.lyrnox.qingliao;

/** Matches the web label. The short code is a display aid, never an authorization key. */
final class SessionIdentity {
  private SessionIdentity() {}

  static String label(String nickname, String id) {
    String name = nickname == null || nickname.isEmpty() ? "匿名用户" : nickname;
    return id == null || id.isEmpty() ? name : name + " · #" + id.substring(0, Math.min(8, id.length()));
  }
}
