package com.lyrnox.qingliao;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.text.style.ImageSpan;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

@SuppressWarnings("deprecation")
public final class ChatFragment extends Fragment {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newFixedThreadPool(3);
  private NativeCore core;
  private ChatNet net;
  private WebSocket socket;
  private int generation = 0, retries = 0;
  private boolean started = false, dead = false, busy = false, historyBusy = false, sessionExpired = false;
  private String room = "lobby",
      roomName = "公共大厅",
      nickname = "",
      next = null,
      status = "正在连接…",
      pendingId = "",
      pendingText = "";
  private String pendingLink = null;
  private JSONObject session;
  private JSONArray messages = new JSONArray(),
      members = new JSONArray(),
      emojiData = new JSONArray();
  private final Map<String, Drawable> emojiDrawables = new HashMap<>();
  private LinearLayout root, emojiPanel, sessionGate;
  private FrameLayout chatArea;
  private EditText gateName;
  private TextView gateError;
  private Button gateEnter;
  private ListView list;
  private TextView heading, subtitle, statusView, recordView;
  private EditText input;
  private MessageAdapter adapter;
  private Button older, send, attach, voice;
  private MediaRecorder recorder;
  private File recording;
  private long recordStart;
  private AlertDialog recordingDialog, nicknamePrompt;
  private MediaPlayer player;
  private File exportFile;
  private String exportName;

  interface Job {
    Object run() throws Exception;
  }

  interface Result {
    void done(Object value) throws Exception;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setRetainInstance(true);
    core = new NativeCore();
    net = new ChatNet(getActivity());
    nickname = getActivity().getPreferences(0).getString("nickname", "");
    try {
      core.run("reset", "room", room);
      emojiData = new JSONArray(readAsset("emoji.json"));
    } catch (Exception ignored) {
    }
    handleLink(getActivity().getIntent());
  }

  private String readAsset(String name) throws IOException {
    try (InputStream in = getActivity().getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toString("UTF-8");
    }
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private final int bg = Color.rgb(248, 250, 243),
      ink = Color.rgb(37, 45, 37),
      green = Color.rgb(82, 107, 64),
      pale = Color.rgb(234, 240, 227),
      mine = Color.rgb(215, 229, 188),
      muted = Color.rgb(112, 120, 109);

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private TextView text(String value, int size, int color) {
    TextView t = new TextView(getActivity());
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    return t;
  }

  private Button button(String title, Runnable action) {
    Button b = new Button(getActivity());
    b.setText(title);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTextColor(ink);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setPadding(dp(12), dp(8), dp(12), dp(8));
    b.setBackground(shape(pale, 22));
    b.setContentDescription(title);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private EditText field(String hint, boolean password) {
    EditText e = new EditText(getActivity());
    e.setSingleLine(true);
    e.setTextSize(16);
    e.setPadding(dp(16), dp(12), dp(16), dp(12));
    e.setBackground(shape(pale, 20));
    e.setHint(hint);
    e.setTextColor(ink);
    e.setHintTextColor(muted);
    if (password) e.setInputType(129);
    return e;
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(getActivity());
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private void margin(View v, LinearLayout parent, int top) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(top);
    parent.addView(v, p);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
    root = vertical();
    root.setBackgroundColor(bg);
    LinearLayout header = new LinearLayout(getActivity());
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(12), dp(8), dp(12), dp(8));
    header.addView(
        button(
            "‹",
            () -> {
              if (!room.equals("lobby")) switchRoom("lobby");
              else roomMenu();
            }),
        new LinearLayout.LayoutParams(dp(44), dp(44)));
    LinearLayout titles = vertical();
    titles.setPadding(dp(12), 0, dp(8), 0);
    heading = text(roomName, 19, ink);
    heading.setTypeface(null, Typeface.BOLD);
    subtitle = text("轻聊 · 原生 Android", 12, muted);
    titles.addView(heading);
    titles.addView(subtitle);
    titles.setOnClickListener(v -> shareRoom());
    TextView roomMark = text("聊", 13, green);
    roomMark.setGravity(Gravity.CENTER);
    roomMark.setBackground(shape(pale, 22));
    header.addView(roomMark, new LinearLayout.LayoutParams(dp(34), dp(34)));
    header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    Button identity = button("匿名", () -> nicknameDialog(false));
    identity.setTextSize(11);
    identity.setPadding(dp(9), dp(5), dp(9), dp(5));
    header.addView(identity);
    header.addView(button("⋯", this::roomMenu), new LinearLayout.LayoutParams(dp(48), dp(44)));
    root.addView(header);
    statusView = text(status, 12, muted);
    statusView.setGravity(Gravity.CENTER);
    statusView.setPadding(0, dp(3), 0, dp(7));
    statusView.setOnClickListener(
        v -> {
          if (session != null) enter();
          else showSessionGate();
        });
    root.addView(statusView);
    older = button("加载更早消息", () -> history(true));
    older.setVisibility(next == null ? View.GONE : View.VISIBLE);
    root.addView(older, new LinearLayout.LayoutParams(-1, dp(36)));
    list = new ListView(getActivity());
    list.setDivider(null);
    list.setDividerHeight(0);
    list.setCacheColorHint(bg);
    list.setPadding(dp(10), dp(6), dp(10), dp(8));
    list.setClipToPadding(false);
    list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
    adapter = new MessageAdapter();
    list.setAdapter(adapter);
    chatArea = new FrameLayout(getActivity());
    chatArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
    sessionGate = vertical();
    sessionGate.setGravity(Gravity.CENTER_HORIZONTAL);
    sessionGate.setPadding(dp(24), dp(26), dp(24), dp(24));
    sessionGate.setBackground(shape(bg, 28));
    sessionGate.setElevation(dp(4));
    TextView gateMark = text("轻聊", 12, green);
    gateMark.setGravity(Gravity.CENTER);
    gateMark.setBackground(shape(pale, 24));
    sessionGate.addView(gateMark, new LinearLayout.LayoutParams(dp(52), dp(34)));
    TextView gateTitle = text("欢迎来到轻聊", 22, ink);
    gateTitle.setTypeface(null, Typeface.BOLD);
    LinearLayout.LayoutParams gateTitleParams = new LinearLayout.LayoutParams(-1, -2);
    gateTitleParams.topMargin = dp(16);
    sessionGate.addView(gateTitle, gateTitleParams);
    TextView gateCopy = text("取个昵称，就可以进入公共大厅聊天。", 13, muted);
    gateCopy.setPadding(0, dp(5), 0, dp(12));
    sessionGate.addView(gateCopy);
    gateName = field("昵称（最多 32 字）", false);
    gateName.setText(nickname);
    gateName.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32)});
    sessionGate.addView(gateName, new LinearLayout.LayoutParams(-1, -2));
    gateError = text("", 12, green);
    gateError.setVisibility(View.GONE);
    sessionGate.addView(gateError);
    gateEnter = button("进入聊天室", this::submitGateNickname);
    gateEnter.setTextSize(15);
    gateEnter.setTypeface(null, Typeface.BOLD);
    gateEnter.setBackground(shape(mine, 18));
    LinearLayout.LayoutParams gateButtonParams = new LinearLayout.LayoutParams(-1, dp(50));
    gateButtonParams.topMargin = dp(16);
    sessionGate.addView(gateEnter, gateButtonParams);
    sessionGate.setVisibility(View.GONE);
    FrameLayout.LayoutParams gateParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
    gateParams.setMargins(dp(16), dp(16), dp(16), dp(16));
    chatArea.addView(sessionGate, gateParams);
    root.addView(chatArea, new LinearLayout.LayoutParams(-1, 0, 1));
    emojiPanel = vertical();
    emojiPanel.setVisibility(View.GONE);
    ScrollView sc = new ScrollView(getActivity());
    GridLayout grid = new GridLayout(getActivity());
    grid.setColumnCount(7);
    grid.setPadding(dp(6), dp(6), dp(6), dp(6));
    for (int i = 0; i < emojiData.length(); i++) {
      try {
        JSONArray item = emojiData.getJSONArray(i);
        String symbol = item.getString(0);
        TextView cell = text(symbol, 25, ink);
        Drawable drawable = emojiDrawable(symbol);
        if (drawable != null) {
          SpannableString span = new SpannableString(symbol);
          span.setSpan(new ImageSpan(drawable), 0, symbol.length(), 0);
          cell.setText(span);
        }
        cell.setGravity(Gravity.CENTER);
        cell.setContentDescription("插入表情 " + symbol);
        cell.setOnClickListener(
            v -> {
              int start = Math.max(0, input.getSelectionStart());
              input.getText().insert(start, symbol);
            });
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0;
        p.height = dp(44);
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        grid.addView(cell, p);
      } catch (Exception ignored) {
      }
    }
    sc.addView(grid);
    emojiPanel.addView(sc, new LinearLayout.LayoutParams(-1, dp(220)));
    root.addView(emojiPanel);
    LinearLayout composer = new LinearLayout(getActivity());
    composer.setGravity(Gravity.BOTTOM);
    composer.setPadding(dp(8), dp(8), dp(8), dp(8));
    attach = button("＋", this::pickFile);
    composer.addView(attach, new LinearLayout.LayoutParams(dp(42), dp(46)));
    input = field("说点什么…", false);
    input.setSingleLine(false);
    input.setMaxLines(5);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4000)});
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
    ip.leftMargin = dp(6);
    ip.rightMargin = dp(6);
    composer.addView(input, ip);
    composer.addView(
        button(
            "表情",
            () -> {
              boolean show = emojiPanel.getVisibility() != View.VISIBLE;
              if (show) {
                ((InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
              }
              emojiPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            }),
        new LinearLayout.LayoutParams(dp(54), dp(46)));
    voice = button("录音", this::startRecording);
    composer.addView(voice, new LinearLayout.LayoutParams(dp(54), dp(46)));
    send = button("发送", this::sendText);
    send.setVisibility(View.GONE);
    composer.addView(send, new LinearLayout.LayoutParams(dp(54), dp(46)));
    root.addView(composer);
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int n) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            send.setVisibility(s.toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
            voice.setVisibility(s.toString().trim().isEmpty() ? View.VISIBLE : View.GONE);
          }

          public void afterTextChanged(Editable e) {}
        });
    input.setOnFocusChangeListener(
        (v, f) -> {
          if (f) emojiPanel.setVisibility(View.GONE);
        });
    render(false);
    return root;
  }

  @Override
  public void onStart() {
    super.onStart();
    started = true;
    if (session == null) restoreSession();
    else enter();
  }

  @Override
  public void onStop() {
    started = false;
    disconnect();
    cancelRecording();
    stopPlayer();
    super.onStop();
  }

  @Override
  public void onDestroyView() {
    if (nicknamePrompt != null) {
      nicknamePrompt.dismiss();
      nicknamePrompt = null;
    }
    send = null;
    voice = null;
    attach = null;
    older = null;
    root = null;
    list = null;
    input = null;
    adapter = null;
    heading = null;
    subtitle = null;
    statusView = null;
    emojiPanel = null;
    chatArea = null;
    sessionGate = null;
    gateName = null;
    gateError = null;
    gateEnter = null;
    super.onDestroyView();
  }

  @Override
  public void onDestroy() {
    dead = true;
    main.removeCallbacksAndMessages(null);
    disconnect();
    net.close();
    io.shutdownNow();
    core.close();
    super.onDestroy();
  }

  private void task(Job job, Result result) {
    if (dead) return;
    final int startedGeneration = generation;
    io.execute(
        () -> {
          try {
            Object value = job.run();
            main.post(
                () -> {
                  if (dead) return;
                  try {
                    result.done(value);
                  } catch (Exception e) {
                    error(e);
                  }
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!dead && startedGeneration == generation) error(e);
                });
          }
        });
  }

  private void scoped(Job job, Result result) {
    int g = generation;
    task(
        job,
        v -> {
          if (g == generation) result.done(v);
        });
  }

  private String endpoint(String op) {
    return "/api/r/" + room + "/" + op;
  }

  private void setStatus(String s) {
    status = s;
    if (statusView != null) statusView.setText(s);
  }

  private void updateSessionGate() {
    if (sessionGate == null || list == null) return;
    boolean show = sessionExpired && session == null;
    sessionGate.setVisibility(show ? View.VISIBLE : View.GONE);
    list.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
    if (older != null && show) older.setVisibility(View.GONE);
  }

  private void showSessionGate() {
    sessionExpired = true;
    setStatus("会话过期，请重新输入昵称");
    if (gateName != null && !gateName.hasFocus()) gateName.setText(nickname);
    if (gateError != null) gateError.setVisibility(View.GONE);
    updateSessionGate();
  }

  private void submitGateNickname() {
    if (session == null && gateName != null && gateEnter != null) {
      try {
        JSONObject payload = core.run("session", "nickname", gateName.getText().toString());
        gateEnter.setEnabled(false);
        setStatus("正在进入轻聊…");
        task(
            () -> net.api("/api/session", payload),
            value -> {
              session = (JSONObject) value;
              nickname = session.optString("nickname");
              if (getActivity() != null)
                getActivity().getPreferences(0).edit().putString("nickname", nickname).apply();
              sessionExpired = false;
              updateSessionGate();
              if (gateEnter != null) gateEnter.setEnabled(true);
              if (pendingLink != null) {
                String rid = pendingLink;
                pendingLink = null;
                joinDialog(rid);
              } else enter();
            });
      } catch (Exception e) {
        if (gateError != null) {
          gateError.setText(e.getMessage());
          gateError.setVisibility(View.VISIBLE);
        } else toast(e.getMessage());
      }
    }
  }

  private void toast(String s) {
    if (getActivity() != null) Toast.makeText(getActivity(), s, Toast.LENGTH_LONG).show();
  }

  private void error(Exception e) {
    busy = false;
    historyBusy = false;
    if (send != null) send.setEnabled(true);
    if (gateEnter != null) gateEnter.setEnabled(true);
    String s = e.getMessage();
    if (e instanceof ChatNet.ApiError) {
      int code = ((ChatNet.ApiError) e).status;
      if (code == 401) {
        session = null;
        disconnect();
        showSessionGate();
        return;
      }
      if (code == 403) {
        setStatus(s);
        if (started && !room.equals("lobby") && !(getFragmentManager().isDestroyed()))
          toast("点击菜单，通过房号和口令重新加入");
        return;
      }
    }
    setStatus(s == null ? "网络请求失败，请重试" : s);
    if (sessionExpired && gateError != null) {
      gateError.setText(status);
      gateError.setVisibility(View.VISIBLE);
    } else toast(status);
  }

  private void restoreSession() {
    setStatus("正在恢复会话…");
    task(
        () -> net.api("/api/session", null),
        value -> {
          session = (JSONObject) value;
          nickname = session.optString("nickname");
          sessionExpired = false;
          updateSessionGate();
          if (pendingLink != null) {
            String r = pendingLink;
            pendingLink = null;
            joinDialog(r);
          } else enter();
        });
  }

  private void nicknameDialog(boolean required) {
    if (required) {
      showSessionGate();
      return;
    }
    if (getActivity() == null || nicknamePrompt != null && nicknamePrompt.isShowing()) return;
    EditText name = field("昵称（最多 32 字）", false);
    name.setText(nickname);
    name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32)});
    LinearLayout content = vertical();
    content.setPadding(dp(20), dp(8), dp(20), 0);
    content.addView(name);
    AlertDialog d =
        new AlertDialog.Builder(getActivity())
            .setTitle(required ? "欢迎来到轻聊" : "修改昵称")
            .setView(content)
            .setPositiveButton("进入", null)
            .setNegativeButton("取消", (x, w) -> {})
            .create();
    nicknamePrompt = d;
    d.setOnDismissListener(x -> nicknamePrompt = null);
    d.setOnShowListener(
        x ->
            d.getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        JSONObject payload =
                            core.run("session", "nickname", name.getText().toString());
                        d.getButton(-1).setEnabled(false);
                        task(
                            () -> {
                              try {
                                return net.api("/api/session", payload);
                              } catch (Exception e) {
                                main.post(() -> d.getButton(-1).setEnabled(true));
                                throw e;
                              }
                            },
                            value -> {
                              session = (JSONObject) value;
                              nickname = session.optString("nickname");
                              if (getActivity() != null)
                                getActivity()
                                    .getPreferences(0)
                                    .edit()
                                    .putString("nickname", nickname)
                                    .apply();
                              d.dismiss();
                              if (pendingLink != null) {
                                String rid = pendingLink;
                                pendingLink = null;
                                joinDialog(rid);
                              } else enter();
                            });
                      } catch (Exception e) {
                        name.setError(e.getMessage());
                      }
                    }));
    d.show();
  }

  private void roomMenu() {
    if (getActivity() == null) return;
    new AlertDialog.Builder(getActivity())
        .setTitle("轻聊")
        .setItems(
            new String[] {"公共大厅", "创建私密房间", "通过房号加入", "复制房间链接", "在线成员", "关于此版本"},
            (d, w) -> {
              switch (w) {
                case 0:
                  switchRoom("lobby");
                  break;
                case 1:
                  createDialog();
                  break;
                case 2:
                  joinDialog("");
                  break;
                case 3:
                  shareRoom();
                  break;
                case 4:
                  showMembers();
                  break;
                case 5:
                  new AlertDialog.Builder(getActivity())
                      .setTitle("轻聊 0.1.0")
                      .setMessage(
                          "原生 Android 测试版\n"
                              + "单 Activity / 自定义 Fragment 与 View / C++ 消息校验与排序核心\n\n"
                              + "支持文字、参考表情、房号私聊、图片与文件、语音录制和媒体播放。\n\n"
                              + "音视频实时通话尚未实现。退到后台会断开实时连接，返回后补取历史；没有后台推送。清除数据后匿名身份及消息删除权无法恢复。\n\n"
                              + "私密房间使用服务端口令授权，不是端到端加密。表情素材沿用网站，公开分发前需确认许可。")
                      .setPositiveButton("知道了", null)
                      .show();
              }
            })
        .show();
  }

  private void createDialog() {
    if (session == null) {
      nicknameDialog(true);
      return;
    }
    LinearLayout fields = vertical();
    fields.setPadding(dp(20), dp(8), dp(20), 0);
    EditText name = field("房间名称", false), password = field("房间口令", true);
    margin(name, fields, 0);
    margin(password, fields, 12);
    AlertDialog d =
        new AlertDialog.Builder(getActivity())
            .setTitle("创建私密房间")
            .setView(fields)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .create();
    d.setOnShowListener(
        x ->
            d.getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        JSONObject data =
                            core.run(
                                "room",
                                "name",
                                name.getText().toString(),
                                "password",
                                password.getText().toString());
                        d.getButton(-1).setEnabled(false);
                        task(
                            () -> {
                              try {
                                return net.api("/api/rooms", data);
                              } catch (Exception e) {
                                main.post(() -> d.getButton(-1).setEnabled(true));
                                throw e;
                              }
                            },
                            value -> {
                              JSONObject r = (JSONObject) value;
                              d.dismiss();
                              switchRoom(r.getString("id"));
                            });
                      } catch (Exception e) {
                        password.setError(e.getMessage());
                      }
                    }));
    d.show();
  }

  private void joinDialog(String code) {
    if (getActivity() == null) return;
    if (session == null) {
      pendingLink = code;
      nicknameDialog(true);
      return;
    }
    LinearLayout fields = vertical();
    fields.setPadding(dp(20), dp(8), dp(20), 0);
    EditText rid = field("房号", false), pw = field("房间口令", true);
    rid.setText(code);
    margin(rid, fields, 0);
    margin(pw, fields, 12);
    AlertDialog d =
        new AlertDialog.Builder(getActivity())
            .setTitle("加入私密房间")
            .setView(fields)
            .setPositiveButton("加入", null)
            .setNegativeButton("取消", null)
            .create();
    d.setOnShowListener(
        x ->
            d.getButton(-1)
                .setOnClickListener(
                    v -> {
                      String target = rid.getText().toString().trim();
                      if (!target.matches("[A-Za-z0-9-]{1,80}")) {
                        rid.setError("房号格式不正确");
                        return;
                      }
                      try {
                        JSONObject payload = core.run("join", "password", pw.getText().toString());
                        d.getButton(-1).setEnabled(false);
                        task(
                            () -> {
                              try {
                                return net.api("/api/r/" + target + "/join", payload);
                              } catch (Exception e) {
                                main.post(() -> d.getButton(-1).setEnabled(true));
                                throw e;
                              }
                            },
                            value -> {
                              d.dismiss();
                              switchRoom(target);
                            });
                      } catch (Exception e) {
                        pw.setError(e.getMessage());
                      }
                    }));
    d.show();
  }

  void handleLink(Intent intent) {
    if (intent == null || intent.getData() == null) return;
    Uri u = intent.getData();
    if (!"https".equals(u.getScheme()) || !"chat.lyrnox.com".equals(u.getHost())) return;
    String rid = u.getQueryParameter("room");
    if (rid == null || !rid.matches("[A-Za-z0-9-]{1,80}")) return;
    pendingLink = rid;
    if (session != null && root != null) {
      pendingLink = null;
      joinDialog(rid);
    }
  }

  private void shareRoom() {
    if (getActivity() == null) return;
    String link = ChatNet.BASE + (room.equals("lobby") ? "/" : "/?room=" + room);
    ((android.content.ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("轻聊房间", link));
    toast(room.equals("lobby") ? "大厅链接已复制" : "房号 " + room + " 的链接已复制，口令请另行告知对方");
  }

  private void showMembers() {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < members.length(); i++)
      s.append(members.optJSONObject(i).optString("nickname")).append('\n');
    new AlertDialog.Builder(getActivity())
        .setTitle(members.length() + " 人在线")
        .setMessage(s.length() == 0 ? "暂时没有在线成员" : s.toString())
        .setPositiveButton("关闭", null)
        .show();
  }

  private void disconnect() {
    main.removeCallbacks(heartbeat);
    main.removeCallbacks(reconnect);
    if (socket != null) {
      socket.close(1000, "离开前台");
      socket = null;
    }
  }

  private final Runnable heartbeat =
      new Runnable() {
        public void run() {
          if (started && socket != null) {
            socket.send("ping");
            main.postDelayed(this, 25000);
          }
        }
      };
  private final Runnable reconnect =
      () -> {
        if (started && session != null) enter();
      };

  private void switchRoom(String rid) {
    if (busy) {
      toast("请等待当前发送结束");
      return;
    }
    try {
      disconnect();
      cancelRecording();
      stopPlayer();
      generation++;
      room = rid;
      roomName = rid.equals("lobby") ? "公共大厅" : "私密房间";
      next = null;
      core.run("reset", "room", rid);
      messages = new JSONArray();
      members = new JSONArray();
      pendingId = "";
      pendingText = "";
      if (input != null) input.setText("");
      render(true);
      enter();
    } catch (Exception e) {
      error(e);
    }
  }

  private void enter() {
    if (!started || session == null) return;
    disconnect();
    int g = ++generation;
    String path = endpoint("state");
    setStatus("正在连接…");
    task(
        () -> net.api(path, null),
        value -> {
          if (g != generation || !started) return;
          JSONObject r = ((JSONObject) value).getJSONObject("room");
          roomName = r.getString("name");
          render(false);
          connect(g);
          history(false);
        });
  }

  private void connect(int g) {
    String rid = room;
    socket =
        net.socket(
            rid,
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket s, Response response) {
                main.post(
                    () -> {
                      if (g != generation || !started) {
                        s.close(1000, "旧连接");
                        return;
                      }
                      retries = 0;
                      setStatus("已连接 · 消息实时同步");
                      main.removeCallbacks(heartbeat);
                      main.postDelayed(heartbeat, 25000);
                    });
              }

              @Override
              public void onMessage(WebSocket s, String value) {
                main.post(
                    () -> {
                      if (g != generation || !started) return;
                      if (value.length() > 1048576) {
                        s.close(1009, "消息过大");
                        return;
                      }
                      try {
                        JSONObject event = new JSONObject(value);
                        core.run("event", "event", event);
                        refreshSnapshot(true);
                        if ("ready".equals(event.optString("type"))) resync(g, null, null);
                      } catch (Exception e) {
                        error(e);
                      }
                    });
              }

              @Override
              public void onClosed(WebSocket s, int code, String why) {
                lost(g, code);
              }

              @Override
              public void onFailure(WebSocket s, Throwable t, Response response) {
                lost(g, response == null ? 0 : response.code());
              }
            });
  }

  private void lost(int g, int code) {
    main.post(
        () -> {
          if (g != generation || !started) return;
          main.removeCallbacks(heartbeat);
          if (code == 4003 || code == 401 || code == 403) {
            socket = null;
            setStatus("授权失效，请重新加入");
            if (code == 401) {
              session = null;
              nicknameDialog(true);
            } else joinDialog(room);
            return;
          }
          setStatus("连接中断，正在重连…");
          try {
            long delay = core.run("retry", "attempt", retries++).getLong("delay");
            main.removeCallbacks(reconnect);
            main.postDelayed(reconnect, delay);
          } catch (Exception e) {
            error(e);
          }
        });
  }

  private void history(boolean more) {
    if (historyBusy || session == null) return;
    historyBusy = true;
    String cursor = more ? next : null;
    if (more && cursor == null) {
      historyBusy = false;
      return;
    }
    String path = endpoint("history") + (cursor == null ? "" : "?before=" + Uri.encode(cursor));
    int g = generation;
    int pos = list == null ? 0 : list.getFirstVisiblePosition();
    int top = list == null || list.getChildAt(0) == null ? 0 : list.getChildAt(0).getTop();
    int before = messages.length();
    task(
        () -> net.api(path, null),
        value -> {
          if (g != generation) {
            historyBusy = false;
            return;
          }
          historyBusy = false;
          JSONObject j = (JSONObject) value;
          core.run("history", "messages", j.getJSONArray("messages"));
          next = j.isNull("next") ? null : j.getString("next");
          refreshSnapshot(!more);
          if (more && list != null)
            list.setSelectionFromTop(pos + Math.max(0, messages.length() - before), top);
        });
  }

  private void resync(int g, String cursor, String oldest) {
    if (g != generation) return;
    String boundary = oldest;
    if (boundary == null && messages.length() > 0)
      boundary = messages.optJSONObject(0).optString("id");
    final String end = boundary;
    String path = endpoint("history") + (cursor == null ? "" : "?before=" + Uri.encode(cursor));
    task(
        () -> net.api(path, null),
        value -> {
          if (g != generation) return;
          JSONObject j = (JSONObject) value;
          JSONArray rows = j.getJSONArray("messages");
          core.run("history", "messages", rows);
          refreshSnapshot(true);
          String more = j.isNull("next") ? null : j.getString("next");
          boolean found = end == null;
          for (int i = 0; i < rows.length(); i++)
            if (rows.getJSONObject(i).optString("id").equals(end)) found = true;
          if (!found && more != null) resync(g, more, end);
        });
  }

  private void refreshSnapshot(boolean follow) throws Exception {
    JSONObject s = core.run("snapshot");
    messages = s.getJSONArray("messages");
    members = s.getJSONArray("members");
    render(follow);
  }

  private void render(boolean follow) {
    if (root == null) return;
    updateSessionGate();
    boolean bottom = list.getCount() == 0 || list.getLastVisiblePosition() >= list.getCount() - 2;
    heading.setText(roomName);
    subtitle.setText(
        (room.equals("lobby") ? "轻聊" : ("房号 " + room)) + " · " + members.length() + " 人在线");
    older.setVisibility(next == null ? View.GONE : View.VISIBLE);
    adapter.notifyDataSetChanged();
    if (follow && bottom && messages.length() > 0)
      list.post(
          () -> {
            if (list != null) list.setSelection(adapter.getCount() - 1);
          });
  }

  private void sendText() {
    if (busy || session == null || input == null) return;
    String raw = input.getText().toString();
    try {
      if (!raw.equals(pendingText) || pendingId.isEmpty()) {
        pendingText = raw;
        pendingId = UUID.randomUUID().toString();
      }
      JSONObject payload = core.run("send", "text", raw, "clientId", pendingId);
      String path = endpoint("send");
      int g = generation;
      busy = true;
      send.setEnabled(false);
      setStatus("正在发送…");
      task(
          () -> net.api(path, payload),
          value -> {
            busy = false;
            if (send != null) send.setEnabled(true);
            if (g != generation) return;
            core.run(
                "event",
                "event",
                new JSONObject()
                    .put("type", "message")
                    .put("message", ((JSONObject) value).getJSONObject("message")));
            if (input != null && input.getText().toString().equals(raw)) input.setText("");
            pendingId = "";
            pendingText = "";
            setStatus("已发送");
            refreshSnapshot(true);
          });
    } catch (Exception e) {
      error(e);
    }
  }

  private Drawable emojiDrawable(String symbol) {
    if (emojiDrawables.containsKey(symbol)) return emojiDrawables.get(symbol);
    try {
      for (int i = 0; i < emojiData.length(); i++) {
        JSONArray pair = emojiData.getJSONArray(i);
        if (pair.getString(0).equals(symbol)) {
          try (InputStream in = getActivity().getAssets().open("emoji/" + pair.getString(1))) {
            Drawable d = Drawable.createFromStream(in, null);
            if (d != null) {
              d.setBounds(0, 0, dp(27), dp(27));
              emojiDrawables.put(symbol, d);
            }
            return d;
          }
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private CharSequence rich(String value) {
    SpannableString out = new SpannableString(value);
    for (int i = 0; i < emojiData.length(); i++) {
      JSONArray p = emojiData.optJSONArray(i);
      String symbol = p.optString(0);
      int start = 0;
      while ((start = value.indexOf(symbol, start)) >= 0) {
        Drawable d = emojiDrawable(symbol);
        if (d != null)
          out.setSpan(
              new ImageSpan(d, ImageSpan.ALIGN_BOTTOM),
              start,
              start + symbol.length(),
              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start += symbol.length();
      }
    }
    return out;
  }

  private String time(long ms, String pattern) {
    return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(ms));
  }

  private final class MessageAdapter extends BaseAdapter {
    public int getCount() {
      return messages.length();
    }

    public JSONObject getItem(int p) {
      return messages.optJSONObject(p);
    }

    public long getItemId(int p) {
      return p;
    }

    public View getView(int p, View recycled, ViewGroup parent) {
      JSONObject m = getItem(p);
      boolean mine = m.optBoolean("mine"), deleted = m.optInt("deleted") != 0;
      LinearLayout row = vertical();
      row.setPadding(dp(4), dp(5), dp(4), dp(5));
      long ts = m.optLong("created");
      if (p == 0
          || !time(ts, "yyyy-MM-dd")
              .equals(time(getItem(p - 1).optLong("created"), "yyyy-MM-dd"))) {
        TextView day = text(time(ts, "M月d日"), 11, muted);
        day.setGravity(Gravity.CENTER);
        day.setPadding(dp(12), dp(4), dp(12), dp(4));
        day.setBackground(shape(pale, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER;
        lp.bottomMargin = dp(14);
        lp.topMargin = dp(8);
        row.addView(day, lp);
      }
      LinearLayout wrap = vertical();
      wrap.setGravity(mine ? Gravity.END : Gravity.START);
      String nick = m.optString("nickname", "匿名");
      if (!mine) {
        TextView name = text(nick, 11, muted);
        name.setPadding(dp(12), 0, 0, dp(4));
        wrap.addView(name);
      }
      TextView bubble = new MessageBubbleView(getActivity(), mine, deleted);
      bubble.setLineSpacing(dp(3), 1);
      bubble.setMaxWidth(
          Math.max(dp(180), getResources().getDisplayMetrics().widthPixels - dp(84)));
      bubble.setPadding(dp(15), dp(11), dp(15), dp(11));
      String kind = m.optString("kind");
      if (deleted) bubble.setText("消息已删除");
      else if (kind.equals("text")) bubble.setText(rich(m.optString("text")));
      else
        bubble.setText(
            (kind.equals("image")
                    ? "图片"
                    : kind.equals("audio") ? "语音" : kind.equals("video") ? "视频" : "文件")
                + "  ·  "
                + m.optString("filename", "媒体")
                + "\n"
                + android.text.format.Formatter.formatShortFileSize(
                    getActivity(), m.optLong("size"))
                + "  ·  点击打开");
      bubble.setContentDescription(nick + "：" + bubble.getText());
      wrap.addView(bubble);
      TextView stamp = text(time(ts, "HH:mm"), 10, muted);
      stamp.setPadding(dp(12), dp(3), dp(12), 0);
      wrap.addView(stamp);
      LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
      row.addView(wrap, wp);
      if (!deleted) {
        bubble.setOnLongClickListener(
            v -> {
              messageMenu(m);
              return true;
            });
        if (!kind.equals("text")) bubble.setOnClickListener(v -> openMedia(m));
      }
      return row;
    }
  }

  private void messageMenu(JSONObject m) {
    boolean mine = m.optBoolean("mine");
    new AlertDialog.Builder(getActivity())
        .setItems(
            mine ? new String[] {"复制", "回复", "删除"} : new String[] {"复制", "回复"},
            (d, w) -> {
              String value =
                  m.optString("kind").equals("text")
                      ? m.optString("text")
                      : m.optString("filename");
              if (w == 0) {
                ((android.content.ClipboardManager)
                        getActivity().getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("消息", value));
                toast("已复制");
              } else if (w == 1) {
                if (input != null) {
                  input.setText("> " + m.optString("nickname") + "：" + value + "\n");
                  input.setSelection(input.length());
                  input.requestFocus();
                }
              } else
                new AlertDialog.Builder(getActivity())
                    .setMessage("删除这条消息？其他成员也会看到删除状态。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "删除",
                        (x, y) -> {
                          String path = endpoint("delete/") + m.optString("id");
                          int g = generation;
                          task(
                              () -> net.api(path, new JSONObject()),
                              v -> {
                                if (g != generation) return;
                                core.run(
                                    "event",
                                    "event",
                                    new JSONObject()
                                        .put("type", "deleted")
                                        .put("id", m.optString("id")));
                                refreshSnapshot(false);
                              });
                        })
                    .show();
            })
        .show();
  }

  private void pickFile() {
    if (busy || session == null) return;
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    startActivityForResult(i, 10);
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
    if (request == 10) {
      Uri uri = data.getData();
      int g = generation;
      String rid = room;
      Context ctx = getActivity().getApplicationContext();
      busy = true;
      setStatus("正在准备文件…");
      task(
          () -> {
            String name = "文件", mime = ctx.getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            try (Cursor c =
                ctx.getContentResolver()
                    .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
              if (c != null && c.moveToFirst()) name = c.getString(0);
            }
            File f = File.createTempFile("upload-", ".tmp", ctx.getCacheDir());
            boolean ok = false;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(f)) {
              if (in == null) throw new IOException("无法读取所选文件");
              byte[] buf = new byte[32768];
              long total = 0;
              int n;
              while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 100L * 1024 * 1024) throw new IOException("文件超过 100 MB");
                out.write(buf, 0, n);
              }
              ok = true;
            } finally {
              if (!ok) f.delete();
            }
            return new Object[] {f, name, mime};
          },
          value -> {
            Object[] v = (Object[]) value;
            if (g != generation) {
              ((File) v[0]).delete();
              busy = false;
              return;
            }
            confirmAttachment((File) v[0], (String) v[1], (String) v[2], false, 0, rid, g);
          });
    } else if (request == 11 && exportFile != null) {
      File f = exportFile;
      Uri uri = data.getData();
      Context ctx = getActivity().getApplicationContext();
      task(
          () -> {
            try (InputStream in = new FileInputStream(f);
                OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
              if (out == null) throw new IOException("无法保存文件");
              byte[] b = new byte[32768];
              int n;
              while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            return true;
          },
          v -> toast("文件已保存"));
    }
  }

  private void confirmAttachment(
      File f, String name, String mime, boolean voice, double duration, String rid, int g) {
    if (!started || getActivity() == null) {
      busy = false;
      f.delete();
      return;
    }
    new AlertDialog.Builder(getActivity())
        .setTitle(voice ? "发送录音" : "发送附件")
        .setMessage(
            name
                + "\n"
                + android.text.format.Formatter.formatShortFileSize(getActivity(), f.length())
                + "\n将发送到「"
                + roomName
                + "」")
        .setNegativeButton(
            "取消",
            (d, w) -> {
              busy = false;
              f.delete();
            })
        .setOnCancelListener(
            d -> {
              busy = false;
              f.delete();
            })
        .setPositiveButton(
            "发送",
            (d, w) -> {
              setStatus("正在上传…");
              task(
                  () -> {
                    try {
                      JSONObject payload =
                          core.run(
                              "upload",
                              "size",
                              f.length(),
                              "mime",
                              mime,
                              "filename",
                              name,
                              "clientId",
                              UUID.randomUUID().toString(),
                              "voice",
                              voice,
                              "duration",
                              duration);
                      String path = "/api/r/" + rid + "/";
                      JSONObject grant = net.api(path + "upload", payload);
                      net.put(grant.getString("url"), f, mime);
                      return net.api(
                          path + "confirm-upload/" + grant.getString("id"), new JSONObject());
                    } finally {
                      f.delete();
                    }
                  },
                  v -> {
                    busy = false;
                    if (g != generation) return;
                    core.run(
                        "event",
                        "event",
                        new JSONObject()
                            .put("type", "message")
                            .put("message", ((JSONObject) v).getJSONObject("message")));
                    setStatus("已发送");
                    refreshSnapshot(true);
                  });
            })
        .show();
  }

  private void startRecording() {
    if (busy || session == null) return;
    if (getActivity().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 20);
      return;
    }
    try {
      recording = File.createTempFile("voice-", ".m4a", getActivity().getCacheDir());
      recorder = new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setAudioEncodingBitRate(64000);
      recorder.setAudioSamplingRate(44100);
      recorder.setOutputFile(recording.getAbsolutePath());
      recorder.setMaxDuration(120000);
      recorder.setMaxFileSize(9 * 1024 * 1024);
      recorder.setOnInfoListener(
          (r, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
              finishRecording();
          });
      recorder.prepare();
      recorder.start();
      recordStart = System.currentTimeMillis();
      recordView = text("录音中 00:00", 20, ink);
      recordView.setGravity(Gravity.CENTER);
      recordView.setPadding(0, dp(30), 0, dp(30));
      recordingDialog =
          new AlertDialog.Builder(getActivity())
              .setTitle("录制语音 · 最长 2 分钟")
              .setView(recordView)
              .setNegativeButton("取消", (d, w) -> cancelRecording())
              .setPositiveButton("完成", (d, w) -> finishRecording())
              .setOnCancelListener(d -> cancelRecording())
              .create();
      recordingDialog.show();
      main.post(recordTick);
    } catch (Exception e) {
      cancelRecording();
      error(e);
    }
  }

  private final Runnable recordTick =
      new Runnable() {
        public void run() {
          if (recorder != null) {
            long seconds = (System.currentTimeMillis() - recordStart) / 1000;
            recordView.setText(
                String.format(Locale.CHINA, "录音中 %02d:%02d", seconds / 60, seconds % 60));
            main.postDelayed(this, 500);
          }
        }
      };

  private void finishRecording() {
    if (recorder == null) return;
    File f = recording;
    double duration = (System.currentTimeMillis() - recordStart) / 1000.0;
    boolean success = true;
    try {
      recorder.stop();
    } catch (RuntimeException e) {
      success = false;
    }
    recorder.release();
    recorder = null;
    recording = null;
    main.removeCallbacks(recordTick);
    if (recordingDialog != null) {
      recordingDialog.dismiss();
      recordingDialog = null;
    }
    if (!success || duration < 0.5) {
      f.delete();
      toast("录音太短，请重试");
      return;
    }
    busy = true;
    confirmAttachment(f, "语音.m4a", "audio/mp4", true, duration, room, generation);
  }

  private void cancelRecording() {
    main.removeCallbacks(recordTick);
    if (recorder != null) {
      try {
        recorder.stop();
      } catch (RuntimeException ignored) {
      }
      recorder.release();
      recorder = null;
    }
    if (recording != null) {
      recording.delete();
      recording = null;
    }
    if (recordingDialog != null) {
      recordingDialog.dismiss();
      recordingDialog = null;
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] names, int[] result) {
    super.onRequestPermissionsResult(request, names, result);
    if (request == 20) {
      if (result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED) startRecording();
      else toast("未获麦克风权限，仍可发送文字和文件");
    }
  }

  private void stopPlayer() {
    if (player != null) {
      player.release();
      player = null;
    }
  }

  private void openMedia(JSONObject m) {
    String path = endpoint("download/") + m.optString("id");
    int g = generation;
    setStatus("正在读取媒体…");
    scoped(
        () ->
            net.download(path, Math.min(100L * 1024 * 1024, Math.max(1, m.optLong("size"))) + 1024),
        value -> {
          File f = (File) value;
          if (g != generation || !started) {
            f.delete();
            return;
          }
          setStatus("媒体已读取");
          String kind = m.optString("kind"), name = m.optString("filename", "文件");
          if (kind.equals("image")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), options);
            int sample = 1;
            while (options.outWidth / sample > 1600 || options.outHeight / sample > 1600)
              sample *= 2;
            options.inSampleSize = sample;
            options.inJustDecodeBounds = false;
            Bitmap image = BitmapFactory.decodeFile(f.getAbsolutePath(), options);
            if (image == null) throw new IOException("图片无法解码");
            ImageView view = new ImageView(getActivity());
            view.setImageBitmap(image);
            view.setAdjustViewBounds(true);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            new AlertDialog.Builder(getActivity())
                .setTitle(name)
                .setView(view)
                .setPositiveButton("关闭", null)
                .setNeutralButton("保存", (d, w) -> saveFile(f, name, m.optString("mime")))
                .show();
          } else if (kind.equals("audio")) {
            stopPlayer();
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnErrorListener(
                (p, a, b) -> {
                  toast("此音频无法在设备上播放");
                  return true;
                });
            player.prepareAsync();
            new AlertDialog.Builder(getActivity())
                .setTitle("语音播放")
                .setMessage(name)
                .setPositiveButton("停止", (d, w) -> stopPlayer())
                .setNeutralButton("保存", (d, w) -> saveFile(f, name, m.optString("mime")))
                .setOnDismissListener(d -> stopPlayer())
                .show();
          } else if (kind.equals("video")) {
            VideoView video = new VideoView(getActivity());
            video.setVideoURI(Uri.fromFile(f));
            MediaController controls = new MediaController(getActivity());
            video.setMediaController(controls);
            video.setOnPreparedListener(p -> video.start());
            video.setOnErrorListener(
                (p, a, b) -> {
                  toast("此视频编码暂不支持，可保存后播放");
                  return true;
                });
            AlertDialog d =
                new AlertDialog.Builder(getActivity())
                    .setTitle(name)
                    .setView(video)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("保存", (x, w) -> saveFile(f, name, m.optString("mime")))
                    .create();
            d.setOnDismissListener(x -> video.stopPlayback());
            d.show();
          } else saveFile(f, name, m.optString("mime"));
        });
  }

  private void saveFile(File f, String name, String mime) {
    exportFile = f;
    exportName = name;
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType(mime.isEmpty() ? "application/octet-stream" : mime);
    i.putExtra(Intent.EXTRA_TITLE, name);
    startActivityForResult(i, 11);
  }

  boolean back() {
    if (emojiPanel != null && emojiPanel.getVisibility() == View.VISIBLE) {
      emojiPanel.setVisibility(View.GONE);
      return true;
    }
    if (!room.equals("lobby")) {
      switchRoom("lobby");
      return true;
    }
    return false;
  }
}
