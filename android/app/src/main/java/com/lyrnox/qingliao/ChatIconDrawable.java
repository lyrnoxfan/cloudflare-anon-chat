package com.lyrnox.qingliao;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Hand-drawn native vector controls using the same 24-unit strokes as the web toolbar. */
final class ChatIconDrawable extends Drawable {
  private final String name;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float density;

  ChatIconDrawable(String name, int color, float density) {
    this.name = name;
    this.density = density;
    paint.setColor(color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
  }

  static boolean supports(String name) {
    return name.equals("打开房间导航") || name.equals("发起语音通话")
        || name.equals("发起视频通话") || name.equals("更多房间操作")
        || name.equals("添加照片或文件") || name.equals("打开表情")
        || name.equals("录制语音") || name.equals("发送消息");
  }

  private void lines(Canvas canvas, float... points) {
    Path path = new Path();
    path.moveTo(points[0], points[1]);
    for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
    canvas.drawPath(path, paint);
  }

  @Override public void draw(Canvas canvas) {
    canvas.save();
    float size = Math.min(24 * density, Math.min(getBounds().width(), getBounds().height()));
    canvas.translate(getBounds().exactCenterX() - size / 2,
        getBounds().exactCenterY() - size / 2);
    canvas.scale(size / 24, size / 24);
    switch (name) {
      case "打开房间导航":
        lines(canvas, 14, 5, 7, 12, 14, 19); lines(canvas, 7, 12, 21, 12); break;
      case "发起语音通话":
        Path phone = new Path();
        phone.moveTo(7, 3); phone.lineTo(4, 3); phone.quadTo(3, 3, 3, 4);
        phone.cubicTo(3, 13.4f, 10.6f, 21, 20, 21); phone.quadTo(21, 21, 21, 20);
        phone.lineTo(21, 17); phone.lineTo(16, 15); phone.lineTo(14, 17);
        phone.quadTo(9, 15, 7, 10); phone.lineTo(9, 8); phone.close();
        canvas.drawPath(phone, paint); break;
      case "发起视频通话":
        canvas.drawRoundRect(3, 5, 15, 19, 3, 3, paint);
        lines(canvas, 15, 10, 21, 6, 21, 18, 15, 14); break;
      case "更多房间操作":
        for (int x : new int[] {5, 12, 19}) canvas.drawCircle(x, 12, 1, paint); break;
      case "添加照片或文件":
        lines(canvas, 12, 5, 12, 19); lines(canvas, 5, 12, 19, 12); break;
      case "打开表情":
        canvas.drawCircle(12, 12, 9, paint);
        canvas.drawPoint(8, 9, paint); canvas.drawPoint(16, 9, paint);
        Path smile = new Path(); smile.moveTo(8, 14); smile.cubicTo(9, 18, 15, 18, 16, 14);
        canvas.drawPath(smile, paint); break;
      case "录制语音":
        canvas.drawRoundRect(8, 3, 16, 15, 4, 4, paint);
        Path mic = new Path(); mic.moveTo(5, 10); mic.lineTo(5, 12);
        mic.cubicTo(5, 21.3f, 19, 21.3f, 19, 12); mic.lineTo(19, 10);
        canvas.drawPath(mic, paint); lines(canvas, 12, 19, 12, 22); lines(canvas, 9, 22, 15, 22); break;
      case "发送消息":
        lines(canvas, 5, 12, 20, 4, 15, 20, 12, 14, 5, 12);
        lines(canvas, 12, 14, 20, 4); break;
      default: break;
    }
    canvas.restore();
  }

  @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
  @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
  @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
