package com.lyrnox.qingliao;

import android.content.Context;
import android.graphics.*;
import android.widget.TextView;

/** Hand-drawn message surface, with native text selection/accessibility retained. */
final class MessageBubbleView extends TextView {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF bounds = new RectF();
  private final float radius;

  MessageBubbleView(Context context, boolean mine, boolean deleted) {
    super(context);
    float density = getResources().getDisplayMetrics().density;
    radius = 21 * density;
    paint.setColor(mine ? Color.rgb(215, 229, 188) : Color.rgb(234, 240, 227));
    setTextColor(deleted ? Color.rgb(112, 120, 109) : Color.rgb(37, 45, 37));
    setTextSize(16);
    setPadding(
        Math.round(15 * density),
        Math.round(11 * density),
        Math.round(15 * density),
        Math.round(11 * density));
    setLineSpacing(3 * density, 1);
    setFontFeatureSettings("kern");
    setBackgroundColor(Color.TRANSPARENT);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    bounds.set(0, 0, getWidth(), getHeight());
    canvas.drawRoundRect(bounds, radius, radius, paint);
    super.onDraw(canvas);
  }
}
