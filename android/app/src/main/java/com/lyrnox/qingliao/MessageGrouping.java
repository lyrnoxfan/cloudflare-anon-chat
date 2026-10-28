package com.lyrnox.qingliao;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Matches the webpage's adjacent-message grouping rule. */
final class MessageGrouping {
  private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

  private MessageGrouping() {}

  static boolean sameGroup(boolean mineA, String nicknameA, long createdA,
      boolean mineB, String nicknameB, long createdB) {
    return mineA == mineB
        && equal(nicknameA, nicknameB)
        && DAY.format(new Date(createdA)).equals(DAY.format(new Date(createdB)))
        && Math.abs(createdA - createdB) < 300000L;
  }

  private static boolean equal(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }
}
