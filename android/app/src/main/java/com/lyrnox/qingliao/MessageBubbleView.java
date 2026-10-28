package com.lyrnox.qingliao;

import android.content.Context;
import android.graphics.*;
import android.widget.TextView;

/** Hand-drawn message surface, with native text selection/accessibility retained. */
final class MessageBubbleView extends TextView {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF bounds = new RectF();
  private final Path path = new Path();
  private final float radius;
  private final boolean mine, grouped, groupEnd;

  MessageBubbleView(Context context, boolean mine, boolean deleted, boolean grouped, boolean groupEnd) {
    super(context);
    this.mine = mine;
    this.grouped = grouped;
    this.groupEnd = groupEnd;
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
    float small = 11 * getResources().getDisplayMetrics().density;
    float topLeft = radius, topRight = radius, bottomRight = radius, bottomLeft = radius;
    if (grouped) {
      if (mine) topRight = small;
      else topLeft = small;
    }
    if (!groupEnd) {
      if (mine) bottomRight = small;
      else bottomLeft = small;
    }
    path.reset();
    path.addRoundRect(bounds,
        new float[] {topLeft, topLeft, topRight, topRight, bottomRight, bottomRight, bottomLeft, bottomLeft},
        Path.Direction.CW);
    canvas.drawPath(path, paint);
    super.onDraw(canvas);
  }
}
