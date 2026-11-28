package com.lyrnox.qingliao;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Date labels and time separators for the live transcript. */
final class MessageGrouping {
  private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

  private MessageGrouping() {}

  /** Separate transcript blocks when the day changes or the adjacent gap exceeds five minutes. */
  static boolean showTimestamp(long previous, long timestamp) {
    Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
    a.setTimeInMillis(previous);
    b.setTimeInMillis(timestamp);
    return a.get(Calendar.YEAR) != b.get(Calendar.YEAR)
        || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)
        || timestamp - previous > 300000L;
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

}
