package com.lyrnox.qingliao;

import android.content.Context;
import android.widget.TextView;

/** 聊记-style transcript typography; the row supplies the incoming/outgoing drawable. */
final class MessageBubbleView extends TextView {
  MessageBubbleView(Context context, int textColor) {
    super(context);
    float density = getResources().getDisplayMetrics().density;
    setTextColor(textColor);
    setTextSize(15);
    int horizontal = Math.round(12 * density);
    int vertical = Math.round(9 * density);
    setPaddingRelative(horizontal, vertical, horizontal, vertical);
    setLineSpacing(Math.round(2 * density), 1);
    setFontFeatureSettings("kern");
  }
}
