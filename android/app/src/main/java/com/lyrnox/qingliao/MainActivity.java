package com.lyrnox.qingliao;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.os.*;
import android.view.*;

public final class MainActivity extends Activity {
  static final int CONTENT = 0x514c;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(250, 251, 247));
    getWindow().setNavigationBarColor(Color.rgb(250, 251, 247));
    android.widget.FrameLayout content = new android.widget.FrameLayout(this);
    content.setId(CONTENT);
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      content.setOnApplyWindowInsetsListener(
          (v, i) -> {
            android.graphics.Insets bars =
                i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return i;
          });
    } else content.setFitsSystemWindows(true);
    setContentView(content);
    if (state == null)
      getFragmentManager().beginTransaction().replace(CONTENT, new ChatFragment(), "chat").commit();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    ChatFragment f = (ChatFragment) getFragmentManager().findFragmentByTag("chat");
    if (f != null) f.handleLink(intent);
  }

  @Override
  public void onBackPressed() {
    ChatFragment f = (ChatFragment) getFragmentManager().findFragmentByTag("chat");
    if (f == null || !f.back()) super.onBackPressed();
  }
}
