package com.lyrnox.qingliao;

import java.text.SimpleDateFormat;
import java.util.Calendar;
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

  static String dayLabel(long timestamp, long now) {
    String day = DAY.format(new Date(timestamp));
    if (day.equals(DAY.format(new Date(now)))) return "今天";
    Calendar yesterday = Calendar.getInstance();
    yesterday.setTimeInMillis(now);
    yesterday.add(Calendar.DAY_OF_YEAR, -1);
    if (day.equals(DAY.format(yesterday.getTime()))) return "昨天";
    Calendar message = Calendar.getInstance();
    Calendar current = Calendar.getInstance();
    message.setTimeInMillis(timestamp);
    current.setTimeInMillis(now);
    return message.get(Calendar.YEAR) == current.get(Calendar.YEAR)
        ? new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(timestamp))
        : new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(new Date(timestamp));
  }

  private static boolean equal(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }
}
