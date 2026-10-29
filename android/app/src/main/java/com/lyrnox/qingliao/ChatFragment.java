package com.lyrnox.qingliao;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.util.LruCache;
import android.media.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

@SuppressWarnings("deprecation")
public final class ChatFragment extends Fragment {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newFixedThreadPool(3);
  // Call startup can block on ICE/SDP for many seconds; keep chat refreshes and uploads
  // from starving the real join action when the room screen has several I/O jobs queued.
  private final ExecutorService callIo = Executors.newSingleThreadExecutor();
  private NativeCore core;
  private ChatNet net;
  private ConnectivityManager connectivityManager;
  private ConnectivityManager.NetworkCallback connectivityCallback;
  private final NetworkRecoveryGate networkRecoveryGate = new NetworkRecoveryGate();
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
  private final LruCache<String, Bitmap> inlineImageCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
    @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
  };
  private final Set<String> inlineImagesLoading = Collections.synchronizedSet(new HashSet<>());
  private FrameLayout root, sessionOverlay, chatArea, drawerOverlay;
  private LinearLayout drawerPanel;
  private LinearLayout content, emojiPanel, sessionGate;
  private ScrollView emojiScroll;
  private EditText gateName;
  private TextView gateError;
  private Button gateEnter;
  private ListView list;
  private LinearLayout emptyState;
  private TextView heading, subtitle, identityBadge, onlineDot, statusView, recordView, callLabel;
  private LinearLayout uploadPanel;
  private TextView uploadLabel;
  private ProgressBar uploadProgress;
  private Button uploadRetry, uploadCancel;
  private UploadJob uploadJob;
  private LinearLayout callPanel, callButtons;
  private LinearLayout callVideoContainer;
  private GridLayout callVideoGrid;
  private SurfaceViewRenderer localRenderer;
  private FrameLayout localVideoCell;
  private VideoTrack localVideoTrack;
  private static final class UploadJob {
    final File file;
    final String name, mime, room, clientId;
    final boolean voice;
    final double duration;
    final int generation;
    volatile boolean cancelled, confirming, uploaded, running;
    volatile int progressPermille;
    volatile Call activeCall;
    volatile String grantId, uploadUrl, acceptedMime;
    UploadJob(File file, String name, String mime, String room, String clientId,
        boolean voice, double duration, int generation) {
      this.file=file; this.name=name; this.mime=mime; this.room=room; this.clientId=clientId;
      this.voice=voice; this.duration=duration; this.generation=generation;
    }
  }
  private static final class RemoteRendererBinding {
    final SurfaceViewRenderer renderer;
    final FrameLayout cell;
    final VideoTrack track;
    RemoteRendererBinding(SurfaceViewRenderer renderer, FrameLayout cell, VideoTrack track) {
      this.renderer = renderer; this.cell = cell; this.track = track;
    }
  }
  private final Map<String, RemoteRendererBinding> remoteRenderers = new LinkedHashMap<>();
  private Button callJoin, callDecline, callMute, callCamera, callFlip, callEnd;
  private HorizontalScrollView callButtonScroll;
  private long observedCallStartedAt;
  private JSONObject observedCall;
  private boolean callStateBusy = false;
  private EditText input;
  private LinearLayout replyDraft;
  private TextView replyName, replyText;
  private String replyPrefix = "";
  private MessageAdapter adapter;
  private boolean followLatestMessages = true;
  private PopupWindow messagePopup;
  private Button older, send, attach, voice, unread, emojiButton;
  private MediaRecorder recorder;
  private File recording;
  private long recordStart;
  private AlertDialog recordingDialog, nicknamePrompt, attachmentPrompt;
  private MediaPlayer player;
  private VideoView activeInlineVideo;
  private File activeInlineVideoFile;
  private TextView inlineVideoPlaceholder;
  private String inlineVideoLoadingId = "";
  private int inlineVideoGeneration = 0;
  private AudioPlayback activeAudio, pendingAudio;
  private Runnable audioProgress;
  private int audioGeneration = 0;
  private File exportFile;
  private boolean exportPreparing;
  private Dialog mediaDialog;
  private AudioFocusLease mediaFocus;
  private int pendingCallGeneration = -1;
  private NativeCallSession nativeCall;
  private int nativeCallRecoveryAttempts;
  private boolean callRecoveryPending;
  private int callRecoveryGeneration = -1;
  private String callRecoveryId = "", callRecoveryKind = "";
  private String pendingCallKind = "";
  private String pendingCallId = "";
  private String pendingCallTargetId = "";
  private String selectedCallTargetId = "";
  private String selectedCallTargetName = "";

  private final Runnable callPoll = new Runnable() {
    @Override public void run() {
      if (!started) return;
      refreshCallState();
      main.postDelayed(this, 7000);
    }
  };

  interface Job {
    Object run() throws Exception;
  }

  interface Result {
    void done(Object value) throws Exception;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    applyPalette();
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

  private int bg, ink, green, pale, mine, muted, line, emptyMark, avatarInk, controlMuted;
  private boolean nightMode;

  private void applyPalette() {
    nightMode = (getResources().getConfiguration().uiMode &
        android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    if (nightMode) {
      bg = Color.rgb(32, 41, 31);       // #20291f webpage surface
      ink = Color.rgb(237, 242, 231);   // #edf2e7
      green = Color.rgb(196, 217, 171); // #c4d9ab
      pale = Color.rgb(45, 57, 42);     // #2d392a webpage message surface
      mine = Color.rgb(66, 87, 51);     // #425733
      muted = Color.rgb(176, 189, 171);// #b0bdab
      line = Color.rgb(48, 61, 44);     // #303d2c
      emptyMark = Color.rgb(146, 171, 124);
      avatarInk = Color.rgb(39, 59, 32);
      controlMuted = Color.rgb(135, 151, 128);
    } else {
      bg = Color.rgb(248, 250, 243);
      ink = Color.rgb(37, 45, 37);
      green = Color.rgb(82, 107, 64);
      pale = Color.rgb(234, 240, 227);
      mine = Color.rgb(215, 229, 188);
      muted = Color.rgb(112, 120, 109);
      line = Color.rgb(230, 234, 224);
      emptyMark = Color.rgb(183, 201, 165);
      avatarInk = Color.rgb(52, 72, 46);
      controlMuted = Color.rgb(174, 178, 174);
    }
  }

  private GradientDrawable messageShape(int color, boolean mine, boolean grouped, boolean groupEnd) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    float full = dp(21), small = dp(11);
    float tl = full, tr = full, br = full, bl = full;
    if (grouped) { if (mine) tr = small; else tl = small; }
    if (!groupEnd) { if (mine) br = small; else bl = small; }
    d.setCornerRadii(new float[] {tl, tl, tr, tr, br, br, bl, bl});
    return d;
  }

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

  private Button iconButton(String glyph, String description, Runnable action) {
    Button b = button(glyph, action);
    b.setTextSize(20);
    b.setGravity(Gravity.CENTER);
    b.setPadding(0, 0, 0, 0);
    if (ChatIconDrawable.supports(description)) {
      b.setText("");
      Drawable icon = new ChatIconDrawable(description, ink, getResources().getDisplayMetrics().density);
      b.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(
          nightMode ? 0x33ffffff : 0x22000000), icon, null));
    } else b.setBackgroundColor(Color.TRANSPARENT);
    b.setContentDescription(description);
    b.setMinHeight(dp(40));
    b.setMinimumHeight(dp(40));
    return b;
  }

  private Button callButton(String title, Runnable action) {
    Button b = button(title, action);
    b.setTextSize(11);
    b.setPadding(dp(4), dp(6), dp(4), dp(6));
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
    applyPalette();
    root = new FrameLayout(getActivity());
    root.setBackgroundColor(bg);
    root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      resizeCallVideoGrid();
      resizeEmojiPanel();
    });
    content = vertical();
    content.setBackgroundColor(bg);
    root.addView(content, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout header = new LinearLayout(getActivity());
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(10), dp(11), dp(10), dp(10));
    // Match the current website mobile topbar minimum height and spacing.
    header.setMinimumHeight(dp(77));
    Button drawerToggle = iconButton("‹", "打开房间导航", this::openRoomDrawer);
    drawerToggle.setTextSize(31);
    header.addView(drawerToggle, new LinearLayout.LayoutParams(dp(36), dp(40)));
    ImageView roomMark = new ImageView(getActivity());
    roomMark.setImageDrawable(new ChatIconDrawable("轻聊房间", green,
        getResources().getDisplayMetrics().density));
    roomMark.setScaleType(ImageView.ScaleType.CENTER);
    roomMark.setBackground(shape(pale, 22));
    roomMark.setContentDescription("轻聊房间");
    LinearLayout.LayoutParams roomMarkParams = new LinearLayout.LayoutParams(dp(34), dp(34));
    roomMarkParams.leftMargin = dp(4);
    roomMarkParams.rightMargin = dp(8);
    header.addView(roomMark, roomMarkParams);
    LinearLayout titles = vertical();
    LinearLayout titleLine = new LinearLayout(getActivity());
    titleLine.setGravity(Gravity.CENTER_VERTICAL);
    heading = text(roomName, 18, ink);
    heading.setTypeface(null, Typeface.BOLD);
    titleLine.addView(heading);
    identityBadge = text("匿名", 9, muted);
    identityBadge.setGravity(Gravity.CENTER);
    identityBadge.setPadding(dp(6), dp(3), dp(6), dp(3));
    identityBadge.setBackground(shape(pale, 12));
    LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
    badgeParams.leftMargin = dp(5);
    titleLine.addView(identityBadge, badgeParams);
    identityBadge.setContentDescription("更改昵称");
    identityBadge.setOnClickListener(v -> nicknameDialog(false));
    titles.addView(titleLine);
    LinearLayout presence = new LinearLayout(getActivity());
    presence.setGravity(Gravity.CENTER_VERTICAL);
    subtitle = text("正在连接…", 11, muted);
    subtitle.setContentDescription("在线人数；点击选择一对一通话对象");
    subtitle.setOnClickListener(v -> showMembers());
    presence.addView(subtitle);
    onlineDot = text("●", 9, muted);
    onlineDot.setContentDescription("连接状态");
    LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(-2, -2);
    dotParams.leftMargin = dp(5);
    presence.addView(onlineDot, dotParams);
    titles.addView(presence);
    heading.setOnClickListener(v -> shareRoom());
    header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    Button audioAction = iconButton("☎", "发起语音通话", () -> startCallFromToolbar("audio"));
    header.addView(audioAction, new LinearLayout.LayoutParams(dp(35), dp(40)));
    Button videoAction = iconButton("▣", "发起视频通话", () -> startCallFromToolbar("video"));
    header.addView(videoAction, new LinearLayout.LayoutParams(dp(35), dp(40)));
    Button more = iconButton("···", "更多房间操作", this::roomMenu);
    header.addView(more, new LinearLayout.LayoutParams(dp(35), dp(40)));
    content.addView(header);
    View headerLine = new View(getActivity());
    headerLine.setBackgroundColor(line);
    content.addView(headerLine, new LinearLayout.LayoutParams(-1, dp(1)));
    statusView = text(status, 12, muted);
    statusView.setGravity(Gravity.CENTER);
    statusView.setPadding(0, dp(3), 0, dp(7));
    statusView.setVisibility(View.GONE);
    statusView.setOnClickListener(
        v -> {
          if (session != null) enter();
          else showSessionGate();
        });
    content.addView(statusView);
    callPanel = vertical();
    callPanel.setVisibility(View.GONE);
    callPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
    callPanel.setBackground(shape(pale, 18));
    callLabel = text("", 13, ink);
    callPanel.addView(callLabel);
    callVideoContainer = new LinearLayout(getActivity());
    callVideoContainer.setOrientation(LinearLayout.VERTICAL);
    callVideoContainer.setVisibility(View.GONE);
    callVideoContainer.setBackground(shape(Color.rgb(38, 48, 41), 18));
    callVideoContainer.setClipToOutline(true);
    callVideoGrid = new GridLayout(getActivity());
    callVideoGrid.setColumnCount(2);
    callVideoGrid.setUseDefaultMargins(false);
    callVideoContainer.addView(callVideoGrid, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(-1, dp(220));
    videoParams.topMargin = dp(8);
    callPanel.addView(callVideoContainer, videoParams);
    callButtonScroll = new HorizontalScrollView(getActivity());
    callButtonScroll.setHorizontalScrollBarEnabled(false);
    callButtons = new LinearLayout(getActivity());
    callButtons.setGravity(Gravity.CENTER_VERTICAL);
    callJoin = callButton("接听 / 加入", this::acceptObservedCall);
    callDecline = callButton("拒绝", this::declineObservedCall);
    callMute = callButton("静音", this::toggleCallMicrophone);
    callCamera = callButton("关摄像头", this::toggleCallCamera);
    callFlip = callButton("切后摄", this::flipCallCamera);
    callEnd = callButton("挂断", this::stopNativeCall);
    for (Button control : new Button[] {callJoin, callDecline, callMute, callCamera, callFlip, callEnd}) {
      LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(dp(76), dp(40));
      controlParams.rightMargin = dp(5);
      callButtons.addView(control, controlParams);
    }
    callButtonScroll.addView(callButtons, new HorizontalScrollView.LayoutParams(-2, dp(42)));
    callPanel.addView(callButtonScroll, new LinearLayout.LayoutParams(-1, dp(44)));
    content.addView(callPanel, new LinearLayout.LayoutParams(-1, -2));
    older = button("加载更早消息", () -> history(true));
    older.setVisibility(next == null ? View.GONE : View.VISIBLE);
    content.addView(older, new LinearLayout.LayoutParams(-1, dp(36)));
    list = new ListView(getActivity());
    list.setDivider(null);
    list.setDividerHeight(0);
    list.setCacheColorHint(bg);
    // Match public/style.css mobile #messages insets.
    list.setPadding(dp(13), dp(14), dp(13), dp(20));
    list.setClipToPadding(false);
    // Keep the tail anchored while the composer/IME changes the available height.
    // A one-shot setSelection can otherwise be overwritten by ListView's resize layout.
    list.setStackFromBottom(true);
    list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
    list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      if (b - t != ob - ot && followLatestMessages) scrollToLatestMessage();
    });
    adapter = new MessageAdapter();
    list.setAdapter(adapter);
    list.setOnScrollListener(
        new AbsListView.OnScrollListener() {
          @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
            if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL)
              followLatestMessages = false;
            else if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE)
              followLatestMessages = isNearBottom();
          }
          @Override public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            if (isNearBottom() && unread != null) unread.setVisibility(View.GONE);
          }
        });
    chatArea = new FrameLayout(getActivity());
    chatArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
    emptyState = vertical();
    emptyState.setGravity(Gravity.CENTER_HORIZONTAL);
    emptyState.setPadding(dp(12), Math.round(getResources().getDisplayMetrics().heightPixels * 0.13f), dp(12), 0);
    TextView emptyMark = text("◌", 34, this.emptyMark);
    emptyMark.setGravity(Gravity.CENTER);
    emptyMark.setBackground(shape(pale, 22));
    emptyMark.setContentDescription(null);
    emptyMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    emptyState.addView(emptyMark, new LinearLayout.LayoutParams(dp(58), dp(58)));
    TextView emptyTitle = text("这里还很安静", 19, ink);
    emptyTitle.setGravity(Gravity.CENTER);
    emptyTitle.setTypeface(null, Typeface.NORMAL);
    LinearLayout.LayoutParams emptyTitleParams = new LinearLayout.LayoutParams(-1, -2);
    emptyTitleParams.topMargin = dp(12);
    emptyState.addView(emptyTitle, emptyTitleParams);
    TextView emptyHint = text("发一句话，开始今天的对话。", 13, muted);
    emptyHint.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams emptyHintParams = new LinearLayout.LayoutParams(-1, -2);
    emptyHintParams.topMargin = dp(4);
    emptyState.addView(emptyHint, emptyHintParams);
    FrameLayout.LayoutParams emptyParams = new FrameLayout.LayoutParams(-1, -1);
    chatArea.addView(emptyState, emptyParams);
    list.setEmptyView(emptyState);
    unread = button("有新消息 · 回到底部 ↓", () -> scrollToLatestMessage());
    unread.setTextSize(12);
    unread.setBackground(shape(mine, 22));
    unread.setElevation(dp(5));
    unread.setVisibility(View.GONE);
    unread.setContentDescription("有新消息，返回聊天底部");
    FrameLayout.LayoutParams unreadParams = new FrameLayout.LayoutParams(-2, dp(42), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    unreadParams.bottomMargin = dp(12);
    chatArea.addView(unread, unreadParams);
    sessionOverlay = new FrameLayout(getActivity());
    View scrim = new View(getActivity());
    scrim.setBackgroundColor(0x660f1710);
    sessionOverlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    sessionGate = vertical();
    sessionGate.setGravity(Gravity.CENTER_HORIZONTAL);
    sessionGate.setPadding(dp(24), dp(26), dp(24), dp(24));
    sessionGate.setBackground(shape(bg, 28));
    sessionGate.setElevation(dp(4));
    TextView gateMark = text("☺", 18, green);
    gateMark.setGravity(Gravity.CENTER);
    gateMark.setBackground(shape(pale, 24));
    sessionGate.addView(gateMark, new LinearLayout.LayoutParams(dp(38), dp(38)));
    TextView gateWelcome = text("欢迎来到轻聊", 12, muted);
    LinearLayout.LayoutParams welcomeParams = new LinearLayout.LayoutParams(-1, -2);
    welcomeParams.topMargin = dp(18);
    sessionGate.addView(gateWelcome, welcomeParams);
    TextView gateTitle = text("怎么称呼你？", 22, ink);
    gateTitle.setTypeface(null, Typeface.BOLD);
    LinearLayout.LayoutParams gateTitleParams = new LinearLayout.LayoutParams(-1, -2);
    gateTitleParams.topMargin = dp(10);
    sessionGate.addView(gateTitle, gateTitleParams);
    TextView gateCopy = text("昵称不是账号，可以和别人重名。", 13, muted);
    gateCopy.setPadding(0, dp(8), 0, dp(16));
    sessionGate.addView(gateCopy);
    TextView gateLabel = text("昵称", 12, ink);
    sessionGate.addView(gateLabel);
    gateName = field("", false);
    gateName.setContentDescription("昵称");
    gateName.setText(nickname);
    gateName.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32)});
    GradientDrawable gateField = shape(pale, 24);
    gateField.setStroke(dp(2), green);
    gateName.setBackground(gateField);
    LinearLayout.LayoutParams gateFieldParams = new LinearLayout.LayoutParams(-1, dp(58));
    gateFieldParams.topMargin = dp(4);
    sessionGate.addView(gateName, gateFieldParams);
    gateError = text("", 12, green);
    gateError.setVisibility(View.GONE);
    sessionGate.addView(gateError);
    gateEnter = button("进入聊天", this::submitGateNickname);
    gateEnter.setTextSize(15);
    gateEnter.setTypeface(null, Typeface.BOLD);
    gateEnter.setBackground(shape(mine, 22));
    LinearLayout.LayoutParams gateButtonParams = new LinearLayout.LayoutParams(-1, dp(48));
    gateButtonParams.topMargin = dp(20);
    sessionGate.addView(gateEnter, gateButtonParams);
    sessionOverlay.setVisibility(View.GONE);
    int cardWidth = Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(32));
    FrameLayout.LayoutParams gateParams = new FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER);
    gateParams.setMargins(dp(16), dp(16), dp(16), dp(16));
    sessionOverlay.addView(sessionGate, gateParams);
    root.addView(sessionOverlay, new FrameLayout.LayoutParams(-1, -1));
    buildRoomDrawer();
    content.addView(chatArea, new LinearLayout.LayoutParams(-1, 0, 1));
    emojiPanel = vertical();
    emojiPanel.setVisibility(View.GONE);
    emojiScroll = new ScrollView(getActivity());
    emojiScroll.setFillViewport(false);
    emojiScroll.setVerticalScrollBarEnabled(false);
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
    emojiScroll.addView(grid);
    emojiPanel.addView(emojiScroll, new LinearLayout.LayoutParams(-1, dp(220)));
    content.addView(emojiPanel);
    replyDraft = new LinearLayout(getActivity());
    replyDraft.setGravity(Gravity.CENTER_VERTICAL);
    replyDraft.setPadding(dp(14), dp(8), dp(8), dp(8));
    replyDraft.setBackground(shape(pale, 18));
    replyDraft.setVisibility(View.GONE);
    LinearLayout replyCopy = vertical();
    replyCopy.setPadding(dp(2), 0, dp(6), 0);
    replyName = text("", 11, green);
    replyText = text("", 13, ink);
    replyText.setSingleLine(true);
    replyText.setEllipsize(android.text.TextUtils.TruncateAt.END);
    replyCopy.addView(replyName);
    replyCopy.addView(replyText);
    replyDraft.addView(replyCopy, new LinearLayout.LayoutParams(0, -2, 1));
    Button cancelReply = button("×", this::clearReplyDraft);
    cancelReply.setContentDescription("取消回复");
    cancelReply.setTextSize(20);
    replyDraft.addView(cancelReply, new LinearLayout.LayoutParams(dp(40), dp(40)));
    LinearLayout.LayoutParams replyParams = new LinearLayout.LayoutParams(-1, -2);
    replyParams.setMargins(dp(12), dp(3), dp(12), 0);
    content.addView(replyDraft, replyParams);
    uploadPanel = vertical();
    uploadPanel.setPadding(dp(14), dp(8), dp(14), dp(8));
    uploadPanel.setBackground(shape(pale, 16));
    uploadPanel.setVisibility(View.GONE);
    uploadLabel = text("", 12, ink);
    uploadPanel.addView(uploadLabel, new LinearLayout.LayoutParams(-1, -2));
    uploadProgress = new ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal);
    uploadProgress.setMax(1000);
    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
    progressParams.topMargin = dp(6);
    uploadPanel.addView(uploadProgress, progressParams);
    LinearLayout uploadActions = new LinearLayout(getActivity());
    uploadActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    uploadRetry = button("重试", () -> { if (uploadJob != null) runUpload(uploadJob); });
    uploadCancel = button("取消上传", this::cancelUpload);
    uploadActions.addView(uploadRetry);
    uploadActions.addView(uploadCancel);
    uploadPanel.addView(uploadActions);
    LinearLayout.LayoutParams uploadParams = new LinearLayout.LayoutParams(-1, -2);
    uploadParams.setMargins(dp(12), dp(2), dp(12), dp(2));
    content.addView(uploadPanel, uploadParams);
    if (uploadJob != null) {
      uploadPanel.setVisibility(View.VISIBLE);
      uploadProgress.setProgress(uploadJob.progressPermille);
      uploadLabel.setText(uploadJob.confirming ? "正在确认并保存文件…"
          : uploadJob.cancelled ? "正在取消上传…"
          : uploadJob.running ? uploadJob.name + " · " + (uploadJob.progressPermille / 10) + "%"
          : uploadJob.name + " · 上传失败，可重试或取消");
      uploadRetry.setVisibility(uploadJob.running || uploadJob.cancelled ? View.GONE : View.VISIBLE);
      uploadCancel.setEnabled(!uploadJob.confirming && !uploadJob.cancelled);
    }
    LinearLayout composer = new LinearLayout(getActivity());
    composer.setGravity(Gravity.CENTER_VERTICAL);
    // Match the website mobile composer-wrap inset and safe-area breathing room.
    composer.setPadding(dp(10), dp(8), dp(10), dp(12));
    LinearLayout composerPill = new LinearLayout(getActivity());
    composerPill.setGravity(Gravity.CENTER_VERTICAL);
    composerPill.setPadding(dp(5), dp(3), dp(5), dp(3));
    composerPill.setBackground(shape(pale, 30));
    attach = iconButton("＋", "添加照片或文件", this::pickFile);
    input = field("说点什么…", false);
    input.setContentDescription("消息内容");
    input.setBackgroundColor(Color.TRANSPARENT);
    input.setPadding(dp(5), dp(9), dp(3), dp(9));
    input.setSingleLine(false);
    input.setMaxLines(5);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4000)});
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    input.setOnEditorActionListener((v, actionId, event) -> {
      boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
          && event.getAction() == KeyEvent.ACTION_DOWN && !event.isShiftPressed();
      if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || enter) {
        sendText();
        return true;
      }
      return false;
    });
    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
    ip.leftMargin = dp(6);
    ip.rightMargin = dp(6);
    composerPill.addView(attach, new LinearLayout.LayoutParams(dp(37), dp(46)));
    composerPill.addView(input, ip);
    emojiButton = iconButton("☻", "打开表情", () -> {
      boolean show = emojiPanel.getVisibility() != View.VISIBLE;
      if (show) {
        ((InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(input.getWindowToken(), 0);
      }
      emojiPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    });
    composerPill.addView(emojiButton, new LinearLayout.LayoutParams(dp(38), dp(44)));
    voice = iconButton("🎙", "录制语音", this::startRecording);
    composerPill.addView(voice, new LinearLayout.LayoutParams(dp(38), dp(44)));
    send = iconButton("➤", "发送消息", this::sendText);
    send.setVisibility(View.GONE);
    composerPill.addView(send, new LinearLayout.LayoutParams(dp(38), dp(44)));
    composer.addView(composerPill, new LinearLayout.LayoutParams(0, -2, 1));
    content.addView(composer);
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int n) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            boolean empty = s.toString().trim().isEmpty();
            send.setVisibility(empty ? View.GONE : View.VISIBLE);
            voice.setVisibility(empty ? View.VISIBLE : View.GONE);
          }

          public void afterTextChanged(Editable e) {}
        });
    input.setOnFocusChangeListener(
        (v, f) -> {
          if (f) emojiPanel.setVisibility(View.GONE);
        });
    render(false);
    updateSessionGate();
    return root;
  }

  @Override
  public void onStart() {
    super.onStart();
    started = true;
    registerNetworkMonitor();
    if (session == null) restoreSession();
    else enter();
  }

  private void registerNetworkMonitor() {
    if (connectivityCallback != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
    Context context = getContext();
    if (context == null) return;
    ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (manager == null) return;
    connectivityManager = manager;
    networkRecoveryGate.reset(hasValidatedInternet(manager));
    ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
      @Override public void onAvailable(Network network) { observeConnectivity(); }
      @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
        observeConnectivity();
      }
      @Override public void onLost(Network network) { observeConnectivity(); }
    };
    connectivityCallback = callback;
    try {
      manager.registerDefaultNetworkCallback(callback);
    } catch (RuntimeException unavailable) {
      connectivityCallback = null;
      connectivityManager = null;
    }
  }

  private void observeConnectivity() {
    main.post(() -> {
      if (!started || connectivityManager == null) return;
      boolean online = hasValidatedInternet(connectivityManager);
      if (networkRecoveryGate.observe(online)) {
        // The browser calls enter() as soon as the device raises its online event.
        // Keep Android's retry backoff, but rejoin immediately when validated internet returns.
        main.removeCallbacks(reconnect);
        enter();
      }
    });
  }

  private static boolean hasValidatedInternet(ConnectivityManager manager) {
    try {
      Network active = manager.getActiveNetwork();
      NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
      return capabilities != null
          && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  @Override
  public void onStop() {
    started = false;
    unregisterNetworkMonitor();
    clearPendingCall();
    main.removeCallbacks(callClock);
    main.removeCallbacks(callPoll);
    stopNativeCall();
    disconnect();
    cancelRecording();
    if (attachmentPrompt != null) attachmentPrompt.dismiss();
    stopPlayer();
    stopInlineVideo();
    if (mediaDialog != null) mediaDialog.dismiss();
    super.onStop();
  }

  private void unregisterNetworkMonitor() {
    ConnectivityManager manager = connectivityManager;
    ConnectivityManager.NetworkCallback callback = connectivityCallback;
    connectivityManager = null;
    connectivityCallback = null;
    if (manager != null && callback != null) {
      try { manager.unregisterNetworkCallback(callback); }
      catch (RuntimeException ignored) {}
    }
  }

  @Override
  public void onDestroyView() {
    stopInlineVideo();
    clearRemoteRenderer();
    clearLocalRenderer();
    main.removeCallbacks(callClock);
    if (messagePopup != null) { messagePopup.dismiss(); messagePopup = null; }
    if (nicknamePrompt != null) {
      nicknamePrompt.dismiss();
      nicknamePrompt = null;
    }
    send = null;
    voice = null;
    attach = null;
    older = null;
    unread = null;
    root = null;
    list = null;
    input = null;
    adapter = null;
    heading = null;
    subtitle = null;
    identityBadge = null;
    onlineDot = null;
    emojiButton = null;
    statusView = null;
    callPanel = null;
    callButtons = null;
    callVideoContainer = null;
    callLabel = null;
    callJoin = null;
    callDecline = null;
    callMute = null;
    callCamera = null;
    callFlip = null;
    callButtonScroll = null;
    callEnd = null;
    emojiPanel = null;
    emojiScroll = null;
    replyDraft = null;
    replyName = null;
    replyText = null;
    chatArea = null;
    emptyState = null;
    sessionGate = null;
    sessionOverlay = null;
    content = null;
    gateName = null;
    gateError = null;
    gateEnter = null;
    super.onDestroyView();
  }

  @Override
  public void onDestroy() {
    dead = true;
    main.removeCallbacksAndMessages(null);
    inlineImageCache.evictAll();
    inlineImagesLoading.clear();
    stopNativeCall();
    disconnect();
    stopInlineVideo();
    if (exportFile != null) { exportFile.delete(); exportFile = null; }
    stopPlayer();
    if (mediaDialog != null) mediaDialog.dismiss();
    net.close();
    io.shutdownNow();
    callIo.shutdownNow();
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
                  if (dead) { discardResult(value); return; }
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
          else discardResult(v);
        });
  }

  private void discardResult(Object value) {
    if (value instanceof File) ((File) value).delete();
    else if (value instanceof Object[]) for (Object item : (Object[]) value) discardResult(item);
  }

  private String endpoint(String op) {
    return "/api/r/" + room + "/" + op;
  }

  private void setStatus(String s) {
    status = s;
    if (statusView != null) {
      statusView.setText(s);
      boolean steady = s.startsWith("已连接") || s.isEmpty();
      statusView.setVisibility(steady ? View.GONE : View.VISIBLE);
    }
    if (onlineDot != null) {
      boolean connected = s.startsWith("已连接");
      onlineDot.setTextColor(connected ? green : muted);
      onlineDot.setContentDescription(connected ? "在线" : "连接中");
    }
  }

  private void updateSessionGate() {
    if (sessionGate == null || list == null) return;
    boolean show;
    boolean showContent;
    try {
      JSONObject screen = core.run("session_state");
      show = screen.getBoolean("showGate");
      showContent = screen.getBoolean("contentVisible");
    } catch (Exception ignored) {
      show = sessionExpired && session == null;
      showContent = session != null && !show;
    }
    if (content != null) {
      content.setVisibility(showContent ? View.VISIBLE : View.GONE);
      content.setImportantForAccessibility(
          showContent ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    if (sessionOverlay != null) sessionOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    if (older != null) older.setVisibility(show || next == null ? View.GONE : View.VISIBLE);
  }

  private void showSessionGate() {
    sessionExpired = true;
    try { core.run("session_required"); } catch (Exception ignored) {}
    setStatus("会话过期，请重新输入昵称");
    if (gateName != null && !gateName.hasFocus()) gateName.setText(nickname);
    if (gateError != null) gateError.setVisibility(View.GONE);
    updateSessionGate();
  }

  private void submitGateNickname() {
    if (session == null && gateName != null && gateEnter != null) {
      String requestedNickname = gateName.getText().toString();
      gateEnter.setEnabled(false);
      setStatus("正在进入轻聊…");
      task(
          () -> SessionProtocol.create(core, (path, body) -> net.sessionPost(path, body), requestedNickname),
          value -> {
            JSONObject accepted = (JSONObject) value;
            applyAcceptedSession(accepted.getJSONObject("session"));
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
    }
  }

  private void toast(String s) {
    if (getActivity() != null) Toast.makeText(getActivity(), s, Toast.LENGTH_LONG).show();
  }

  private void error(Exception e) {
    if (!inlineVideoLoadingId.isEmpty() && inlineVideoPlaceholder != null) {
      inlineVideoPlaceholder.setVisibility(View.VISIBLE);
      inlineVideoPlaceholder.setText("视频读取失败，轻触重试");
      inlineVideoPlaceholder = null;
    }
    inlineVideoLoadingId = "";
    busy = false;
    historyBusy = false;
    if (send != null) send.setEnabled(true);
    if (gateEnter != null) gateEnter.setEnabled(true);
    String s = e.getMessage();
    if (e instanceof ChatNet.ApiError) {
      int code = ((ChatNet.ApiError) e).status;
      if (code == 401) {
        session = null;
        try { core.run("session_required"); } catch (Exception ignored) {}
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
    if (session == null && gateError != null) {
      gateError.setText(status);
      gateError.setVisibility(View.VISIBLE);
    } else toast(status);
  }

  private void acceptSession(JSONObject response) throws Exception {
    JSONObject accepted = core.run("session_accept", "session", response, "now", System.currentTimeMillis());
    applyAcceptedSession(accepted.getJSONObject("session"));
  }

  private void applyAcceptedSession(JSONObject acceptedSession) throws Exception {
    session = new JSONObject(acceptedSession.toString());
    nickname = acceptedSession.getString("nickname");
    sessionExpired = false;
    updateSessionGate();
  }

  private void restoreSession() {
    setStatus("正在恢复会话…");
    if (gateEnter != null) gateEnter.setEnabled(false);
    task(
        () -> net.api("/api/session", null),
        value -> {
          acceptSession((JSONObject) value);
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
                      String requestedNickname = name.getText().toString();
                      d.getButton(-1).setEnabled(false);
                      task(
                          () -> {
                            try {
                              return SessionProtocol.create(
                                  core,
                                  (path, body) -> net.sessionPost(path, body),
                                  requestedNickname);
                            } catch (Exception failure) {
                              main.post(
                                  () -> {
                                    if (d.isShowing()) {
                                      d.getButton(-1).setEnabled(true);
                                      name.setError(failure.getMessage());
                                    }
                                  });
                              throw failure;
                            }
                          },
                          value -> {
                            JSONObject accepted = (JSONObject) value;
                            applyAcceptedSession(accepted.getJSONObject("session"));
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
                    }));
    d.show();
  }

  private void buildRoomDrawer() {
    drawerOverlay = new FrameLayout(getActivity());
    drawerOverlay.setVisibility(View.GONE);
    View scrim = new View(getActivity());
    scrim.setBackgroundColor(0x550f1710);
    scrim.setOnClickListener(v -> closeRoomDrawer());
    drawerOverlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    drawerPanel = vertical();
    drawerPanel.setPadding(dp(20), dp(24), dp(18), dp(20));
    drawerPanel.setBackground(shape(bg, 0));
    drawerPanel.setElevation(dp(12));
    TextView brand = text("轻聊", 22, ink);
    brand.setTypeface(null, Typeface.BOLD);
    drawerPanel.addView(brand);
    TextView hint = text("房间与成员", 13, muted);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
    hp.topMargin = dp(3);
    drawerPanel.addView(hint, hp);
    TextView roomCard = text("", 14, ink);
    roomCard.setId(View.generateViewId());
    roomCard.setPadding(dp(14), dp(14), dp(14), dp(14));
    roomCard.setBackground(shape(pale, 18));
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
    rp.topMargin = dp(20);
    drawerPanel.addView(roomCard, rp);
    drawerPanel.setTag(roomCard);
    addDrawerAction("公共大厅", () -> switchRoom("lobby"));
    addDrawerAction("通过房号加入", () -> { closeRoomDrawer(); joinDialog(""); });
    addDrawerAction("创建私密房间", () -> { closeRoomDrawer(); createDialog(); });
    addDrawerAction("在线成员", () -> { closeRoomDrawer(); showMembers(); });
    addDrawerAction("复制房间链接", () -> { closeRoomDrawer(); shareRoom(); });
    addDrawerAction("更改昵称", () -> { closeRoomDrawer(); nicknameDialog(false); });
    TextView footer = text("聊天与房间操作", 12, muted);
    LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
    fp.topMargin = dp(18);
    drawerPanel.addView(footer, fp);
    ScrollView drawerScroll = new ScrollView(getActivity());
    drawerScroll.setFillViewport(true);
    drawerScroll.addView(drawerPanel, new ScrollView.LayoutParams(-1, -2));
    FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
        Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(48)), -1, Gravity.START);
    drawerOverlay.addView(drawerScroll, panelParams);
    root.addView(drawerOverlay, new FrameLayout.LayoutParams(-1, -1));
    refreshRoomDrawer();
  }

  private void addDrawerAction(String label, Runnable action) {
    Button b = button(label, action);
    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    b.setPadding(dp(14), dp(7), dp(12), dp(7));
    b.setBackground(shape(bg, 14));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
    p.topMargin = dp(3);
    drawerPanel.addView(b, p);
  }

  private void refreshRoomDrawer() {
    if (drawerPanel == null) return;
    View view = drawerPanel.getTag() instanceof View ? (View) drawerPanel.getTag() : null;
    if (view instanceof TextView) {
      ((TextView) view).setText((room.equals("lobby") ? "公共大厅" : "私密房间  ·  " + room)
          + "\n" + members.length() + " 人在线\n轻触标题复制房间链接");
      view.setOnClickListener(v -> { closeRoomDrawer(); shareRoom(); });
    }
  }

  private void openRoomDrawer() {
    if (drawerOverlay != null) {
      refreshRoomDrawer();
      drawerOverlay.setVisibility(View.VISIBLE);
      drawerPanel.setTranslationX(-dp(12));
      drawerPanel.animate().translationX(0).setDuration(140).start();
    }
  }

  private void closeRoomDrawer() {
    if (drawerOverlay != null) drawerOverlay.setVisibility(View.GONE);
  }

  private void startCallFromToolbar(String kind) {
    closeRoomDrawer();
    if (session == null) {
      toast("请先设置昵称");
      nicknameDialog(true);
      return;
    }
    requestNativeCall(kind, null);
  }

  private void roomMenu() {
    if (getActivity() == null) return;
    new AlertDialog.Builder(getActivity())
        .setTitle("轻聊")
        .setItems(
            new String[] {"公共大厅", "创建私密房间", "通过房号加入", "复制房间链接", "在线成员", "语音/视频通话（测试）", "关于此版本"},
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
                  nativeCallMenu();
                  break;
                case 6:
                  new AlertDialog.Builder(getActivity())
                      .setTitle("轻聊 Android · 实验版")
                      .setMessage(
                          "原生 Android 测试版\n"
                              + "单 Activity / 自定义 Fragment 与 View / C++ 协议及消息核心\n\n"
                              + "支持文字、参考表情、房号私聊、图片与文件、语音录制和媒体播放。\n\n"
                              + "语音/视频通话仍属实验，模拟媒体验收记录见随包报告；未完成实体设备验收。退到后台会清理本机通话与实时连接，返回后补取历史；没有后台推送。清除数据后匿名身份及消息删除权无法恢复。\n\n"
                              + "私密房间使用服务端口令授权，不是端到端加密。表情素材沿用网站，公开分发前需确认许可。")
                      .setPositiveButton("知道了", null)
                      .show();
              }
            })
        .show();
  }

  private void nativeCallMenu() {
    if (getActivity() == null) return;
    if (session == null) {
      toast("请先设置昵称");
      nicknameDialog(true);
      return;
    }
    if (nativeCall != null) {
      new AlertDialog.Builder(getActivity())
          .setTitle("当前通话")
          .setMessage("正在连接房间内的音视频。退到后台或被其他音频中断时，本机会结束通话。")
          .setPositiveButton("挂断", (d, w) -> stopNativeCall())
          .setNegativeButton("返回", null)
          .show();
      return;
    }
    new AlertDialog.Builder(getActivity())
        .setTitle("音视频通话")
        .setMessage("选择发起语音或视频通话；收到的房间邀请会显示在聊天顶部。")
        .setItems(new String[] {"语音通话", "视频通话"},
            (d, which) -> requestNativeCall(which == 0 ? "audio" : "video", null))
        .setNegativeButton("取消", null)
        .show();
  }

  private void requestNativeCall(String kind, String expectedCallId) {
    if (!started || getActivity() == null || session == null) return;
    ArrayList<String> missing = new ArrayList<>();
    if (getActivity().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
      missing.add(Manifest.permission.RECORD_AUDIO);
    if ("video".equals(kind) && getActivity().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      missing.add(Manifest.permission.CAMERA);
    if (!missing.isEmpty()) {
      pendingCallGeneration = generation;
      pendingCallKind = kind;
      pendingCallId = expectedCallId == null ? "" : expectedCallId;
      pendingCallTargetId = expectedCallId == null ? selectedCallTargetId : "";
      requestPermissions(missing.toArray(new String[0]), 41);
      return;
    }
    startNativeCall(kind, expectedCallId, expectedCallId == null ? selectedCallTargetId : "");
  }

  private void startNativeCall(String kind, String expectedCallId, String targetId) {
    if (!started || getActivity() == null || session == null || nativeCall != null || callRecoveryPending) return;
    stopPlayer();
    stopInlineVideo();
    cancelRecording();
    final String selfId = session.optString("id", "");
    final NativeCallSession[] callRef = new NativeCallSession[1];
    final NativeCallSession call = new NativeCallSession(getActivity(), core, net, room, selfId,
        new NativeCallSession.Listener() {
          @Override public void onStatus(String message) {
            main.post(() -> {
              if (nativeCall == callRef[0] && getActivity() != null) {
                setStatus(message);
                if ("远端媒体已恢复".equals(message)) nativeCallRecoveryAttempts = 0;
                renderCallPanel();
              }
            });
          }
          @Override public void onRecoverableFailure(String message, String callId, String failedKind, boolean canRejoin) {
            main.post(() -> {
              if (nativeCall != callRef[0]) return;
              nativeCall = null;
              clearRemoteRenderer();
              clearLocalRenderer();
              renderCallPanel();
              if (canRejoin && started && getActivity() != null && nativeCallRecoveryAttempts < 2) {
                nativeCallRecoveryAttempts++;
                callRecoveryPending = true;
                callRecoveryGeneration = generation;
                callRecoveryId = callId;
                callRecoveryKind = failedKind;
                setStatus("媒体连接中断，准备重新加入（" + nativeCallRecoveryAttempts + "/2）");
                toast(message);
                final int attemptGeneration = generation;
                main.postDelayed(() -> {
                  if (!callRecoveryPending || callRecoveryGeneration != attemptGeneration || generation != attemptGeneration || !started) return;
                  String retryId = callRecoveryId, retryKind = callRecoveryKind;
                  callRecoveryPending = false;
                  callRecoveryId = "";
                  callRecoveryKind = "";
                  startNativeCall(retryKind, retryId, "");
                }, 1500);
              } else {
                callRecoveryPending = false;
                nativeCallRecoveryAttempts = 0;
                callRecoveryId = "";
                callRecoveryKind = "";
                if (getActivity() != null) {
                  setStatus("通话已结束");
                  toast(message);
                }
              }
            });
          }
          @Override public void onFailure(String message) {
            main.post(() -> {
              if (nativeCall != callRef[0]) return;
              nativeCall = null;
              clearRemoteRenderer();
              clearLocalRenderer();
              renderCallPanel();
              if (getActivity() != null) {
                setStatus("通话已结束");
                toast(message);
              }
            });
          }
          @Override public void onRemoteVideo(String memberId, String memberName, VideoTrack track, EglBase.Context eglContext) {
            main.post(() -> attachRemoteCallVideo(memberId, memberName, track, eglContext, callRef[0]));
          }
          @Override public void onLocalVideo(VideoTrack track, EglBase.Context eglContext) {
            main.post(() -> attachCallVideo(track, eglContext, true, callRef[0]));
          }
        });
    callRef[0] = call;
    nativeCall = call;
    setStatus("正在准备通话…");
    renderCallPanel();
    try {
      callIo.execute(() -> {
        try {
          call.start(kind, expectedCallId, targetId);
          main.post(() -> {
            if (nativeCall == callRef[0]) { callRecoveryPending = false; refreshCallState(); }
          });
        } catch (Exception e) {
          call.stop();
          main.post(() -> {
            if (nativeCall != callRef[0]) return;
            nativeCall = null;
            clearRemoteRenderer();
            clearLocalRenderer();
            renderCallPanel();
            if (getActivity() != null) {
              setStatus("通话未启动");
              toast(e.getMessage() == null ? "通话测试失败" : e.getMessage());
            }
          });
        }
      });
    } catch (RejectedExecutionException e) {
      nativeCall = null;
      clearRemoteRenderer();
      clearLocalRenderer();
      renderCallPanel();
      call.requestStop();
      toast("应用正在关闭，无法启动通话测试");
    }
  }

  private void refreshCallState() {
    if (!started || session == null || callStateBusy) return;
    callStateBusy = true;
    final int g = generation;
    final String rid = room;
    try {
      io.execute(() -> {
        JSONObject result = null;
        Exception failure = null;
        try { result = net.callApi(rid, "call-state", null, null).optJSONObject("call"); }
        catch (Exception e) { failure = e; }
        final JSONObject call = result;
        final Exception error = failure;
        main.post(() -> {
          if (g != generation) return;
          callStateBusy = false;
          if (started && error == null) observeCall(call);
        });
      });
    } catch (RejectedExecutionException e) {
      callStateBusy = false;
    }
  }

  private void observeCall(JSONObject call) {
    String incomingId = call == null ? "" : call.optString("id", "");
    if (!incomingId.equals(observedCall == null ? "" : observedCall.optString("id", "")))
      observedCallStartedAt = call == null ? 0L : call.optLong("created", System.currentTimeMillis());
    observedCall = call;
    main.removeCallbacks(callClock);
    // Incoming invitations do not display elapsed time. Re-rendering that card every
    // second needlessly invalidates the accessibility tree while the user is deciding.
    if (call != null && nativeCall != null) main.postDelayed(callClock, 1000);
    if (call == null || session == null) {
      renderCallPanel();
      return;
    }
    try {
      JSONObject invite = core.run("call_invitation", "call", call, "self", session.optString("id", ""));
      if (!invite.optBoolean("eligible") || invite.optBoolean("joined") || nativeCall != null) {
        renderCallPanel();
        return;
      }
    } catch (Exception ignored) {
      observedCall = null;
    }
    renderCallPanel();
  }

  private void renderCallPanel() {
    if (callPanel == null || callLabel == null) return;
    if (nativeCall != null) {
      String kind = observedCall == null ? "" : observedCall.optString("kind", "");
      JSONArray callMembers = observedCall == null ? null : observedCall.optJSONArray("members");
      int memberCount = callMembers == null ? 0 : callMembers.length();
      boolean direct = observedCall != null && !observedCall.isNull("target");
      long startedAt = observedCallStartedAt > 0 ? observedCallStartedAt : System.currentTimeMillis();
      long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
      String elapsed = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
      callLabel.setText((direct ? "一对一" : "多人") + ("video".equals(kind) ? "视频" : "语音")
          + " · " + memberCount + " 人 · " + elapsed + " · " + status);
      updateCallVideoVisibility("video".equals(kind));
      callJoin.setVisibility(View.GONE);
      callDecline.setVisibility(View.GONE);
      callMute.setText(nativeCall.isMicrophoneEnabled() ? "静音" : "开麦");
      callCamera.setText(nativeCall.isCameraEnabled() ? "关摄像头" : "开摄像头");
      callMute.setEnabled(nativeCall.isMicrophoneReady());
      callCamera.setEnabled("video".equals(kind) && nativeCall.isCameraReady());
      callMute.setVisibility(View.VISIBLE);
      callCamera.setVisibility("video".equals(kind) ? View.VISIBLE : View.GONE);
      callFlip.setVisibility("video".equals(kind) && nativeCall.isCameraReady() ? View.VISIBLE : View.GONE);
      callFlip.setText(nativeCall.isFrontFacing() ? "切后摄" : "切前摄");
      callEnd.setVisibility(View.VISIBLE);
      callPanel.setVisibility(View.VISIBLE);
      return;
    }
    if (observedCall == null || session == null) {
      callPanel.setVisibility(View.GONE);
      return;
    }
    try {
      JSONObject invite = core.run("call_invitation", "call", observedCall, "self", session.optString("id", ""));
      if (!invite.optBoolean("eligible") || invite.optBoolean("joined")) {
        callPanel.setVisibility(View.GONE);
        return;
      }
      String kind = invite.optString("kind", "audio");
      JSONArray members = observedCall.optJSONArray("members");
      int count = members == null ? 0 : members.length();
      callLabel.setText(("video".equals(kind) ? "视频" : "语音") + "通话 · " + count + " 人已加入");
      callJoin.setText(invite.optBoolean("invited") ? "接听" : "加入");
      callJoin.setVisibility(View.VISIBLE);
      callDecline.setVisibility(invite.optBoolean("invited") ? View.VISIBLE : View.GONE);
      callMute.setVisibility(View.GONE);
      callCamera.setVisibility(View.GONE);
      callFlip.setVisibility(View.GONE);
      callEnd.setVisibility(View.GONE);
      callPanel.setVisibility(View.VISIBLE);
    } catch (Exception e) {
      callPanel.setVisibility(View.GONE);
    }
  }

  private void acceptObservedCall() {
    if (observedCall == null) return;
    String id = observedCall.optString("id", "");
    String kind = observedCall.optString("kind", "audio");
    if (id.isEmpty()) return;
    requestNativeCall(kind, id);
  }

  private void declineObservedCall() {
    if (observedCall == null || session == null) return;
    String id = observedCall.optString("id", "");
    if (id.isEmpty()) return;
    observedCall = null;
    renderCallPanel();
    final String rid = room;
    task(() -> net.callApi(rid, "call-decline", new JSONObject().put("callId", id), null), value -> refreshCallState());
  }

  private void toggleCallMicrophone() { toggleCallTrack(false); }
  private void toggleCallCamera() { toggleCallTrack(true); }

  private void flipCallCamera() {
    NativeCallSession call = nativeCall;
    if (call == null) return;
    try {
      io.execute(() -> {
        try {
          boolean frontFacing = call.flipCamera();
          main.post(() -> {
            if (nativeCall == call && callFlip != null) callFlip.setText(frontFacing ? "切后摄" : "切前摄");
          });
        } catch (Exception e) {
          main.post(() -> { if (getActivity() != null) toast("切换摄像头失败：" + e.getMessage()); });
        }
      });
    } catch (RejectedExecutionException ignored) {}
  }

  private void toggleCallTrack(boolean camera) {
    NativeCallSession call = nativeCall;
    if (call == null) return;
    try {
      io.execute(() -> {
        try {
          boolean enabled = camera ? call.toggleCamera() : call.toggleMicrophone();
          main.post(() -> {
            if (nativeCall != call) return;
            if (camera && callCamera != null) callCamera.setText(enabled ? "关摄像头" : "开摄像头");
            if (!camera && callMute != null) callMute.setText(enabled ? "静音" : "开麦");
          });
        } catch (Exception e) { main.post(() -> toast(e.getMessage())); }
      });
    } catch (RejectedExecutionException ignored) {}
  }

  private final Runnable callClock = new Runnable() {
    @Override public void run() {
      if (!started || observedCall == null) return;
      renderCallPanel();
      main.postDelayed(this, 1000);
    }
  };

  private void attachCallVideo(VideoTrack track, EglBase.Context eglContext, boolean local,
      NativeCallSession call) {
    if (!local || nativeCall != call || getActivity() == null || callVideoContainer == null) return;
    if (track == null || eglContext == null) {
      clearLocalRenderer();
      return;
    }
    try {
      SurfaceViewRenderer renderer = new SurfaceViewRenderer(getActivity());
      renderer.init(eglContext, new RendererCommon.RendererEvents() {
        @Override public void onFirstFrameRendered() {}
        @Override public void onFrameResolutionChanged(int width, int height, int rotation) {}
      });
      renderer.setMirror(local);
      renderer.setEnableHardwareScaler(true);
      renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
      clearLocalRenderer();
      localRenderer = renderer;
      localVideoTrack = track;
      track.addSink(renderer);
      localVideoCell = callVideoTile(renderer, nickname + "（我）");
      rebuildCallVideoGrid();
      updateCallVideoVisibility(true);
      renderCallPanel();
    } catch (RuntimeException ignored) {
      clearLocalRenderer();
    }
  }

  private void attachRemoteCallVideo(String memberId, String memberName, VideoTrack track, EglBase.Context eglContext,
      NativeCallSession call) {
    if (nativeCall != call || getActivity() == null || callVideoContainer == null || memberId == null) return;
    if (track == null || eglContext == null) {
      clearRemoteRenderer(memberId);
      return;
    }
    try {
      SurfaceViewRenderer renderer = new SurfaceViewRenderer(getActivity());
      renderer.init(eglContext, new RendererCommon.RendererEvents() {
        @Override public void onFirstFrameRendered() {}
        @Override public void onFrameResolutionChanged(int width, int height, int rotation) {}
      });
      renderer.setMirror(false);
      renderer.setEnableHardwareScaler(true);
      renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
      clearRemoteRenderer(memberId);
      track.addSink(renderer);
      remoteRenderers.put(memberId, new RemoteRendererBinding(renderer, callVideoTile(renderer,
          memberName == null || memberName.isEmpty() ? memberId : memberName), track));
      rebuildCallVideoGrid();
      updateCallVideoVisibility(true);
    } catch (RuntimeException ignored) {
      clearRemoteRenderer(memberId);
    }
  }

  private FrameLayout callVideoTile(SurfaceViewRenderer renderer, String label) {
    FrameLayout tile = new FrameLayout(getActivity());
    tile.addView(renderer, new FrameLayout.LayoutParams(-1, -1));
    TextView caption = text(label == null ? "" : label, 10, Color.WHITE);
    caption.setBackgroundColor(0x99000000);
    caption.setPadding(dp(6), dp(2), dp(6), dp(2));
    FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
    tile.addView(caption, captionParams);
    return tile;
  }

  private void rebuildCallVideoGrid() {
    if (callVideoGrid == null) return;
    callVideoGrid.removeAllViews();
    ArrayList<FrameLayout> tiles = new ArrayList<>();
    if (localVideoCell != null) tiles.add(localVideoCell);
    for (RemoteRendererBinding binding : remoteRenderers.values()) tiles.add(binding.cell);
    int count = tiles.size();
    int columns = count <= 1 ? 1 : 2;
    callVideoGrid.setColumnCount(columns);
    callVideoGrid.setRowCount(Math.max(1, (count + columns - 1) / columns));
    resizeCallVideoGrid();
    for (int i = 0; i < count; i++) {
      GridLayout.LayoutParams params = new GridLayout.LayoutParams(
          GridLayout.spec(i / columns, 1, 1f), GridLayout.spec(i % columns, 1, 1f));
      params.width = 0;
      params.height = 0;
      params.setMargins(dp(2), dp(2), dp(2), dp(2));
      callVideoGrid.addView(tiles.get(i), params);
    }
    // Recalculate after children are attached; the layout listener handles later resizes.
    resizeCallVideoGrid();
  }

  /** Keep the native participant grid readable on short screens without hiding the chat.
   * The web call panel is capped at 46vh; reserve a little room for its heading and controls.
   */
  private void resizeCallVideoGrid() {
    if (callVideoContainer == null) return;
    int count = callVideoGrid == null ? 0 : callVideoGrid.getChildCount();
    int rows = Math.max(1, (count + 1) / 2);
    int viewport = root == null ? 0 : root.getHeight();
    if (viewport <= 0) viewport = getResources().getDisplayMetrics().heightPixels;
    int target = Math.min(dp(250) * rows, Math.round(viewport * 0.42f));
    target = Math.max(dp(120), target);
    ViewGroup.LayoutParams params = callVideoContainer.getLayoutParams();
    if (params != null && params.height != target) {
      params.height = target;
      callVideoContainer.setLayoutParams(params);
    }
  }

  /** Match the web picker’s viewport cap and preserve a touch-scrollable grid above the keyboard. */
  private void resizeEmojiPanel() {
    if (emojiScroll == null) return;
    int viewport = root == null ? 0 : root.getHeight();
    if (viewport <= 0) viewport = getResources().getDisplayMetrics().heightPixels;
    int target = Math.max(dp(84), Math.min(dp(220), viewport - dp(190)));
    ViewGroup.LayoutParams params = emojiScroll.getLayoutParams();
    if (params != null && params.height != target) {
      params.height = target;
      emojiScroll.setLayoutParams(params);
    }
  }

  private void updateCallVideoVisibility(boolean videoCall) {
    if (callVideoContainer == null) return;
    callVideoContainer.setVisibility(videoCall && callVideoGrid != null && callVideoGrid.getChildCount() > 0 ? View.VISIBLE : View.GONE);
  }

  private void clearLocalRenderer() {
    if (localVideoTrack != null && localRenderer != null) localVideoTrack.removeSink(localRenderer);
    if (localRenderer != null) {
      if (localRenderer.getParent() instanceof ViewGroup)
        ((ViewGroup) localRenderer.getParent()).removeView(localRenderer);
      localRenderer.release();
      localRenderer = null;
      localVideoCell = null;
    }
    localVideoTrack = null;
    rebuildCallVideoGrid();
    updateCallVideoVisibility(true);
  }

  private void clearRemoteRenderer(String memberId) {
    RemoteRendererBinding binding = remoteRenderers.remove(memberId);
    if (binding != null) {
      try { binding.track.removeSink(binding.renderer); } catch (Exception ignored) {}
      if (binding.renderer.getParent() instanceof ViewGroup)
        ((ViewGroup) binding.renderer.getParent()).removeView(binding.renderer);
      try { binding.renderer.release(); } catch (Exception ignored) {}
    }
    rebuildCallVideoGrid();
    updateCallVideoVisibility(true);
  }

  private void clearRemoteRenderer() {
    for (String memberId : new ArrayList<>(remoteRenderers.keySet())) clearRemoteRenderer(memberId);
  }

  private void stopNativeCall() {
    callRecoveryPending = false;
    nativeCallRecoveryAttempts = 0;
    callRecoveryId = "";
    callRecoveryKind = "";
    clearRemoteRenderer();
    clearLocalRenderer();
    NativeCallSession call = nativeCall;
    if (call == null) return;
    nativeCall = null;
    renderCallPanel();
    call.requestStop();
    try {
      io.execute(call::stop);
    } catch (RejectedExecutionException ignored) {
      call.stop();
    }
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
    if (getActivity() == null) return;
    ArrayList<String> choices = new ArrayList<>();
    ArrayList<String> ids = new ArrayList<>();
    choices.add("多人通话");
    ids.add("");
    String selfId = session == null ? "" : session.optString("id", "");
    int checked = selectedCallTargetId.isEmpty() ? 0 : -1;
    for (int i = 0; i < members.length(); i++) {
      JSONObject member = members.optJSONObject(i);
      if (member == null) continue;
      String id = member.optString("id", "");
      if (id.isEmpty() || id.equals(selfId)) continue;
      if (id.equals(selectedCallTargetId)) checked = choices.size();
      choices.add(member.optString("nickname", "匿名"));
      ids.add(id);
    }
    String title = members.length() + " 人在线 · 选择通话范围";
    if (ids.size() == 1) {
      new AlertDialog.Builder(getActivity()).setTitle(title)
          .setMessage("暂时没有其他在线成员；语音/视频按钮将发起多人通话。")
          .setPositiveButton("知道了", null).show();
      return;
    }
    new AlertDialog.Builder(getActivity())
        .setTitle(title)
        .setSingleChoiceItems(choices.toArray(new String[0]), checked, (dialog, which) -> {
          selectedCallTargetId = ids.get(which);
          selectedCallTargetName = choices.get(which).equals("多人通话") ? "" : choices.get(which);
          render(false);
          toast(selectedCallTargetId.isEmpty()
              ? "已选择多人通话；语音/视频按钮将呼叫房间成员"
              : "已选择 " + selectedCallTargetName + "；语音/视频按钮将发起一对一通话");
          dialog.dismiss();
        })
        .setNegativeButton("关闭", null)
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
    closeRoomDrawer();
    if (busy || uploadJob != null) {
      toast("请先完成或取消当前上传");
      return;
    }
    try {
      clearPendingCall();
      stopNativeCall();
      disconnect();
      cancelRecording();
      stopPlayer();
      stopInlineVideo();
      generation++;
      callStateBusy = false;
      observedCall = null;
      renderCallPanel();
      room = rid;
      inlineImageCache.evictAll();
      roomName = rid.equals("lobby") ? "公共大厅" : "私密房间";
      next = null;
      core.run("reset", "room", rid);
      messages = new JSONArray();
      members = new JSONArray();
      if (unread != null) unread.setVisibility(View.GONE);
      selectedCallTargetId = "";
      selectedCallTargetName = "";
      pendingId = "";
      pendingText = "";
      if (input != null) input.setText("");
      clearReplyDraft();
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
    // A call-state request from the prior Activity generation may complete late.
    // Its callback is intentionally ignored; clear the old in-flight guard here so
    // the resumed Activity can issue fresh call-state reads and receive invitations.
    callStateBusy = false;
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
          refreshCallState();
          main.removeCallbacks(callPoll);
          main.postDelayed(callPoll, 7000);
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
                        JSONObject incoming = event.optJSONObject("message");
                        boolean showUnread = "message".equals(event.optString("type"))
                            && incoming != null
                            && !incoming.optBoolean("mine")
                            && !containsMessage(incoming.optString("id"))
                            && !isNearBottom();
                        core.run("event", "event", event);
                        if ("call-changed".equals(event.optString("type"))) refreshCallState();
                        refreshSnapshot(true);
                        if (showUnread && unread != null) unread.setVisibility(View.VISIBLE);
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

  private boolean isNearBottom() {
    if (list == null || adapter == null || adapter.getCount() == 0) return true;
    return list.getLastVisiblePosition() >= adapter.getCount() - 2;
  }

  private boolean containsMessage(String id) {
    if (id == null || id.isEmpty()) return false;
    for (int i = 0; i < messages.length(); i++) {
      JSONObject message = messages.optJSONObject(i);
      if (message != null && id.equals(message.optString("id"))) return true;
    }
    return false;
  }

  private void scrollToLatestMessage() {
    if (list == null || adapter == null || adapter.getCount() == 0) return;
    followLatestMessages = true;
    list.post(() -> {
      if (list != null && adapter != null && adapter.getCount() > 0) {
        list.setSelection(adapter.getCount() - 1);
        if (unread != null) unread.setVisibility(View.GONE);
      }
    });
  }

  private void refreshSnapshot(boolean follow) throws Exception {
    JSONObject s = core.run("snapshot");
    messages = s.getJSONArray("messages");
    members = s.getJSONArray("members");
    if (!selectedCallTargetId.isEmpty()) {
      boolean stillOnline = false;
      for (int i = 0; i < members.length(); i++) {
        JSONObject member = members.optJSONObject(i);
        if (member != null && selectedCallTargetId.equals(member.optString("id"))) {
          stillOnline = true;
          selectedCallTargetName = member.optString("nickname", "匿名");
          break;
        }
      }
      if (!stillOnline) {
        selectedCallTargetId = "";
        selectedCallTargetName = "";
        toast("通话对象已离线，已切回多人通话");
      }
    }
    render(follow);
  }

  private void render(boolean follow) {
    if (root == null) return;
    updateSessionGate();
    boolean bottom = list.getCount() == 0 || list.getLastVisiblePosition() >= list.getCount() - 2;
    heading.setText(roomName);
    subtitle.setText(members.length() + " 人在线");
    subtitle.setContentDescription(
        members.length() + " 人在线；点击选择一对一通话对象"
            + (selectedCallTargetName.isEmpty() ? "" : "；当前呼叫 " + selectedCallTargetName));
    identityBadge.setText("匿名");
    if (drawerPanel != null) refreshRoomDrawer();
    older.setVisibility(next == null ? View.GONE : View.VISIBLE);
    adapter.notifyDataSetChanged();
    if (follow && bottom && messages.length() > 0) scrollToLatestMessage();
  }

  private void sendText() {
    if (busy || session == null || input == null) return;
    String raw = input.getText().toString();
    String sentReplyPrefix = replyPrefix;
    String outgoing = ReplyDraft.messageText(sentReplyPrefix, raw);
    if (outgoing.trim().isEmpty()) return;
    if (outgoing.length() > 4000) {
      toast("引用和消息合计不能超过 4000 字，请缩短后发送");
      return;
    }
    try {
      if (!outgoing.equals(pendingText) || pendingId.isEmpty()) {
        pendingText = outgoing;
        pendingId = UUID.randomUUID().toString();
      }
      String path = endpoint("send");
      int g = generation;
      String sentRoom = room;
      String sentClientId = pendingId;
      busy = true;
      send.setEnabled(false);
      setStatus("正在发送…");
      task(
          () -> MessageProtocol.send(core, (p, body) -> net.messagePost(p, body), path, sentRoom, outgoing, sentClientId),
          value -> {
            busy = false;
            if (send != null) send.setEnabled(true);
            if (g != generation) return;
            if (input != null && input.getText().toString().equals(raw)) input.setText("");
            if (replyPrefix.equals(sentReplyPrefix)) clearReplyDraft();
            pendingId = "";
            pendingText = "";
            setStatus("已发送");
            refreshSnapshot(true);
            scrollToLatestMessage();
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

  private String firstCodePoint(String value) {
    if (value == null || value.trim().isEmpty()) return "?";
    return value.trim().substring(0, value.trim().offsetByCodePoints(0, 1));
  }

  private int avatarColor(String nickname) {
    int[] colors = nightMode
        ? new int[] {Color.rgb(126, 153, 107), Color.rgb(111, 142, 104), Color.rgb(149, 160, 116),
            Color.rgb(111, 146, 132), Color.rgb(150, 151, 104), Color.rgb(130, 158, 139)}
        : new int[] {Color.rgb(184, 202, 167), Color.rgb(166, 193, 154), Color.rgb(200, 213, 187),
            Color.rgb(180, 201, 179), Color.rgb(181, 199, 162), Color.rgb(191, 208, 184)};
    String value = nickname == null || nickname.isEmpty() ? "?" : nickname;
    int hash = 0x811c9dc5;
    for (int i = 0; i < value.length(); ) {
      int codePoint = value.codePointAt(i);
      hash = (hash ^ codePoint) * 0x01000193;
      i += Character.charCount(codePoint);
    }
    return colors[Integer.remainderUnsigned(hash, colors.length)];
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
      long ts = m.optLong("created");
      String nick = m.optString("nickname", "匿名");
      JSONObject previous = p > 0 ? getItem(p - 1) : null;
      JSONObject following = p + 1 < getCount() ? getItem(p + 1) : null;
      boolean grouped = previous != null && MessageGrouping.sameGroup(
          previous.optBoolean("mine"), previous.optString("nickname", "匿名"), previous.optLong("created"),
          mine, nick, ts);
      boolean groupEnd = following == null || !MessageGrouping.sameGroup(
          mine, nick, ts, following.optBoolean("mine"), following.optString("nickname", "匿名"),
          following.optLong("created"));
      // Mirror the web mobile message rhythm: 6px between rows, 21px after a group.
      row.setPadding(0, dp(grouped ? 1 : 0), 0, dp(groupEnd ? 21 : 6));
      if (p == 0
          || !time(ts, "yyyy-MM-dd")
              .equals(time(getItem(p - 1).optLong("created"), "yyyy-MM-dd"))) {
        TextView day = text(MessageGrouping.dayLabel(ts, System.currentTimeMillis()), 11, muted);
        day.setGravity(Gravity.CENTER);
        day.setPadding(dp(12), dp(4), dp(12), dp(4));
        day.setBackground(shape(pale, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER;
        lp.bottomMargin = dp(14);
        lp.topMargin = dp(8);
        row.addView(day, lp);
      }
      LinearLayout messageRow = new LinearLayout(getActivity());
      messageRow.setOrientation(LinearLayout.HORIZONTAL);
      // Baseline alignment overrides per-child bottom gravity and clips short avatar TextViews.
      messageRow.setBaselineAligned(false);
      messageRow.setGravity(Gravity.TOP);
      messageRow.setPadding(dp(mine ? 37 : 0), 0, 0, 0);
      if (!mine) {
        TextView avatar = text(firstCodePoint(nick), 12, avatarInk);
        avatar.setGravity(Gravity.CENTER);
        avatar.setContentDescription(nick);
        avatar.setBackground(shape(avatarColor(nick), 50));
        avatar.setVisibility(groupEnd ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        avatarParams.rightMargin = dp(8);
        avatarParams.bottomMargin = dp(24);
        avatarParams.gravity = Gravity.BOTTOM;
        messageRow.addView(avatar, avatarParams);
      }
      LinearLayout wrap = vertical();
      wrap.setGravity(mine ? Gravity.END : Gravity.START);
      if (!mine && !grouped) {
        TextView name = text(nick, 11, muted);
        name.setPadding(dp(12), 0, 0, dp(4));
        wrap.addView(name);
      }
      TextView bubble = new MessageBubbleView(getActivity(), mine, deleted, grouped, groupEnd, ChatFragment.this.mine, pale, ink, muted);
      bubble.setLineSpacing(dp(5), 1);
      bubble.setMaxWidth(Math.min(dp(580),
          Math.max(dp(180), getResources().getDisplayMetrics().widthPixels - dp(mine ? 84 : 93))));
      bubble.setPadding(dp(15), dp(11), dp(15), dp(11));
      String kind = m.optString("kind");
      View bubbleView = bubble;
      if (deleted) {
        bubble.setText("消息已删除");
        bubble.setContentDescription(nick + "：消息已删除");
      } else if (kind.equals("text")) {
        bubble.setText(rich(m.optString("text")));
        bubble.setContentDescription(nick + "：" + m.optString("text"));
      } else if (kind.equals("image")) {
        LinearLayout card = vertical();
        card.setBackground(messageShape(mine ? ChatFragment.this.mine : pale, mine, grouped, groupEnd));
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setContentDescription(nick + "：图片，轻触查看大图");
        FrameLayout imageFrame = new FrameLayout(getActivity());
        int previewWidth = Math.min(dp(280), Math.max(dp(190),
            getResources().getDisplayMetrics().widthPixels - dp(110)));
        imageFrame.setLayoutParams(new LinearLayout.LayoutParams(previewWidth, dp(176)));
        imageFrame.setBackground(shape(pale, 16));
        imageFrame.setClipToOutline(true);
        ImageView preview = new ImageView(getActivity());
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(m.optString("filename", "聊天图片"));
        imageFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        TextView placeholder = text("▧", 28, green);
        placeholder.setGravity(Gravity.CENTER);
        imageFrame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        loadInlineImage(preview, placeholder, m.optString("id"), endpoint("download/") + m.optString("id"), m.optLong("size"));
        card.addView(imageFrame);
        TextView caption = text(m.optString("filename", "图片") + fileSizeSuffix(m.optLong("size")), 10, muted);
        caption.setPadding(dp(8), dp(5), dp(8), dp(3));
        card.addView(caption);
        card.setOnClickListener(v -> openMedia(m));
        card.setOnLongClickListener(v -> { messageMenu(m, v); return true; });
        card.setFocusable(true);
        bubbleView = card;
      } else if (kind.equals("audio")) {
        LinearLayout card = vertical();
        card.setBackground(messageShape(mine ? ChatFragment.this.mine : pale, mine, grouped, groupEnd));
        card.setPadding(dp(12), dp(9), dp(12), dp(7));
        card.setContentDescription(nick + "：语音消息，轻触播放");
        LinearLayout controls = new LinearLayout(getActivity());
        controls.setGravity(Gravity.CENTER_VERTICAL);
        Button toggle = button("▶", () -> {});
        toggle.setTextSize(13);
        toggle.setContentDescription("播放语音");
        controls.addView(toggle, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView clock = text("0:00", 12, ink);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        clock.setPadding(dp(3), 0, dp(5), 0);
        controls.addView(clock, new LinearLayout.LayoutParams(dp(42), dp(38)));
        SeekBar seek = new SeekBar(getActivity());
        seek.setMax(0);
        seek.setContentDescription("语音播放进度");
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(controlMuted));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(controlMuted));
        controls.addView(seek, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView volume = text("◖))", 14, ink);
        volume.setGravity(Gravity.CENTER);
        volume.setContentDescription("媒体音量由设备控制");
        controls.addView(volume, new LinearLayout.LayoutParams(dp(34), dp(38)));
        card.addView(controls, new LinearLayout.LayoutParams(-1, dp(40)));
        String audioInfo = "";
        if (m.optDouble("duration", 0) > 0)
          audioInfo = Math.round(m.optDouble("duration")) + " 秒";
        if (m.optLong("size") > 0) {
          if (!audioInfo.isEmpty()) audioInfo += " · ";
          audioInfo += android.text.format.Formatter.formatShortFileSize(getActivity(), m.optLong("size"));
        }
        if (!audioInfo.isEmpty()) {
          TextView meta = text(audioInfo, 10, muted);
          meta.setPadding(dp(7), dp(3), dp(7), 0);
          card.addView(meta);
        }
        String audioId = m.optString("id");
        AudioPlayback playback = activeAudio != null && audioId.equals(activeAudio.messageId)
            ? activeAudio : pendingAudio != null && audioId.equals(pendingAudio.messageId)
                ? pendingAudio : new AudioPlayback(card, audioId, m.optLong("size"), toggle, seek, clock);
        // ListView rebuilds rows on scroll/presence events; keep controls bound to
        // the actual player or pending download instead of a detached old View.
        int oldMax = playback.seek.getMax(), oldProgress = playback.seek.getProgress();
        playback.card = card; playback.toggle = toggle; playback.seek = seek; playback.clock = clock;
        seek.setMax(oldMax); seek.setProgress(oldProgress);
        if (playback.loading) toggle.setText("…");
        else if (playback == activeAudio && player != null && playback.ready) {
          boolean playing = player.isPlaying();
          toggle.setText(playing ? "Ⅱ" : "▶");
          toggle.setContentDescription(playing ? "暂停语音" :
              player.getCurrentPosition() >= seek.getMax() ? "重播语音" : "继续播放语音");
          clock.setText(durationLabel(oldProgress));
        }
        card.setTag(playback);
        toggle.setOnClickListener(v -> playInlineAudio(m, card));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser && activeAudio == playback && player != null)
              clock.setText(durationLabel(progress));
          }
          public void onStartTrackingTouch(SeekBar bar) {}
          public void onStopTrackingTouch(SeekBar bar) {
            if (activeAudio == playback && player != null) player.seekTo(bar.getProgress());
          }
        });
        card.setOnLongClickListener(v -> { messageMenu(m, v); return true; });
        card.setFocusable(true);
        bubbleView = card;
      } else if (kind.equals("video")) {
        LinearLayout card = vertical();
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(messageShape(mine ? ChatFragment.this.mine : pale, mine, grouped, groupEnd));
        card.setContentDescription(nick + "：视频，轻触预览播放");
        LinearLayout infoRow = new LinearLayout(getActivity());
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView glyph = text("▶", 18, green);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(shape(mine ? pale : ChatFragment.this.mine, 24));
        infoRow.addView(glyph, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout details = vertical();
        details.setPadding(dp(10), 0, dp(4), 0);
        int detailWidth = Math.min(dp(238), Math.max(dp(112),
            getResources().getDisplayMetrics().widthPixels - dp(mine ? 180 : 220)));
        TextView name = text(m.optString("filename", "视频"), 13, ink);
        name.setMaxWidth(detailWidth);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(name);
        String detail = android.text.format.Formatter.formatShortFileSize(getActivity(), m.optLong("size"));
        if (m.optDouble("duration", 0) > 0) detail += "  ·  " + Math.round(m.optDouble("duration")) + " 秒";
        details.addView(text(detail + "  ·  轻触播放", 10, muted));
        infoRow.addView(details, new LinearLayout.LayoutParams(detailWidth, -2));
        Button fullscreen = button("全屏", () -> openMedia(m));
        fullscreen.setTextSize(12);
        fullscreen.setContentDescription("全屏播放视频：" + m.optString("filename", "视频"));
        infoRow.addView(fullscreen, new LinearLayout.LayoutParams(dp(58), dp(38)));
        card.addView(infoRow);

        FrameLayout preview = new FrameLayout(getActivity());
        preview.setBackground(shape(nightMode ? Color.rgb(20, 25, 20) : Color.rgb(224, 230, 218), 12));
        TextView play = text("▶  轻触播放", 14, ink);
        play.setGravity(Gravity.CENTER);
        preview.addView(play, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
            Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(72)), dp(180));
        previewParams.topMargin = dp(9);
        card.addView(preview, previewParams);
        preview.setOnClickListener(v -> playInlineVideo(m, preview, play));
        card.setOnLongClickListener(v -> { messageMenu(m, v); return true; });
        card.setFocusable(true);
        bubbleView = card;
      } else if (kind.equals("file")) {
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.setBackground(messageShape(mine ? ChatFragment.this.mine : pale, mine, grouped, groupEnd));
        card.setContentDescription(nick + "：文件，轻触保存");
        LinearLayout infoRow = new LinearLayout(getActivity());
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView glyph = text("▤", 20, green);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(shape(mine ? pale : ChatFragment.this.mine, 24));
        infoRow.addView(glyph, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout details = vertical();
        details.setPadding(dp(10), 0, dp(4), 0);
        int detailWidth = Math.min(dp(220), Math.max(dp(96),
            getResources().getDisplayMetrics().widthPixels - dp(mine ? 194 : 238)));
        TextView name = text(m.optString("filename", "文件"), 13, ink);
        name.setMaxWidth(detailWidth);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(name);
        String detail = android.text.format.Formatter.formatShortFileSize(getActivity(), m.optLong("size"));
        details.addView(text(detail + "  ·  点击保存", 10, muted));
        infoRow.addView(details, new LinearLayout.LayoutParams(detailWidth, -2));
        card.addView(infoRow, new LinearLayout.LayoutParams(0, -2, 1f));
        Button save = button("保存", () -> openMedia(m));
        save.setTextSize(12);
        save.setContentDescription("保存文件：" + m.optString("filename", "文件"));
        card.addView(save, new LinearLayout.LayoutParams(dp(62), dp(38)));
        card.setOnClickListener(v -> openMedia(m));
        card.setOnLongClickListener(v -> { messageMenu(m, v); return true; });
        card.setFocusable(true);
        bubbleView = card;
      }
      wrap.addView(bubbleView);
      if (groupEnd) {
        TextView stamp = text(time(ts, "HH:mm"), 10, muted);
        stamp.setPadding(dp(12), dp(3), dp(12), 0);
        wrap.addView(stamp);
      }
      LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
      messageRow.addView(wrap, wp);
      row.addView(messageRow, new LinearLayout.LayoutParams(-1, -2));
      if (!deleted) {
        if (kind.equals("text")) bubbleView.setOnLongClickListener(
            v -> {
              messageMenu(m, v);
              return true;
            });
      }
      return row;
    }
  }

  private void clearReplyDraft() {
    replyPrefix = "";
    if (replyDraft != null) replyDraft.setVisibility(View.GONE);
  }

  private void messageMenu(JSONObject m, View anchor) {
    if (getActivity() == null || root == null || anchor == null || !anchor.isShown()) return;
    if (messagePopup != null && messagePopup.isShowing()) messagePopup.dismiss();
    boolean mineMessage = m.optBoolean("mine");
    boolean copyable = m.optString("kind").equals("text");
    ArrayList<String> actions = new ArrayList<>();
    if (copyable) actions.add("复制");
    actions.add("回复");
    if (!copyable) actions.add("保存");
    if (mineMessage) actions.add("删除");

    LinearLayout menu = new LinearLayout(getActivity());
    menu.setOrientation(LinearLayout.VERTICAL);
    menu.setPadding(dp(4), dp(5), dp(4), dp(5));
    menu.setBackground(shape(bg, 20));
    menu.setElevation(dp(12));
    for (String action : actions) {
      TextView item = text(action, 14, ink);
      item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
      item.setPadding(dp(17), 0, dp(17), 0);
      item.setMinHeight(dp(44));
      item.setBackground(ripple(Color.TRANSPARENT));
      menu.addView(item, new LinearLayout.LayoutParams(-1, dp(44)));
      item.setOnClickListener(v -> {
        if (messagePopup != null) messagePopup.dismiss();
        handleMessageAction(m, action);
      });
    }
    int width = dp(176), height = dp(10 + actions.size() * 44);
    messagePopup = new PopupWindow(menu, width, height, true);
    messagePopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    messagePopup.setOutsideTouchable(true);
    messagePopup.setElevation(dp(12));
    int[] location = new int[2];
    anchor.getLocationOnScreen(location);
    int screenWidth = getResources().getDisplayMetrics().widthPixels;
    int screenHeight = getResources().getDisplayMetrics().heightPixels;
    int x = Math.max(dp(8), Math.min(location[0] + anchor.getWidth() - width, screenWidth - width - dp(8)));
    int y = location[1] + Math.max(0, (anchor.getHeight() - height) / 2);
    if (y + height > screenHeight - dp(24)) y = location[1] - height + dp(12);
    y = Math.max(dp(28), Math.min(y, screenHeight - height - dp(28)));
    messagePopup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
  }

  private void handleMessageAction(JSONObject m, String action) {
    String value = m.optString("kind").equals("text")
        ? m.optString("text") : m.optString("filename");
    if (action.equals("复制")) {
      ((android.content.ClipboardManager)
              getActivity().getSystemService(Context.CLIPBOARD_SERVICE))
          .setPrimaryClip(ClipData.newPlainText("消息", value));
      toast("已复制");
    } else if (action.equals("保存")) {
      downloadForSave(m);
    } else if (action.equals("回复")) {
      if (input != null) {
        replyPrefix = ReplyDraft.prefix(m.optString("nickname", "匿名用户"), value);
        if (replyName != null) replyName.setText("回复给 " + m.optString("nickname", "匿名用户"));
        if (replyText != null) replyText.setText(ReplyDraft.preview(value));
        if (replyDraft != null) replyDraft.setVisibility(View.VISIBLE);
        input.requestFocus();
        input.post(() -> {
          EditText target = input;
          if (target != null && target.isAttachedToWindow()) {
            InputMethodManager keyboard = (InputMethodManager)
                target.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
          }
        });
      }
    } else if (action.equals("删除") && m.optBoolean("mine")) {
      new AlertDialog.Builder(getActivity())
          .setMessage("删除这条消息？其他成员也会看到删除状态。")
          .setNegativeButton("取消", null)
          .setPositiveButton("删除", (x, y) -> {
            String path = endpoint("delete/") + m.optString("id");
            int g = generation;
            task(() -> net.api(path, new JSONObject()), v -> {
              if (g != generation) return;
              core.run("event", "event", new JSONObject()
                  .put("type", "deleted").put("id", m.optString("id")));
              refreshSnapshot(false);
            });
          })
          .show();
    }
  }

  private android.graphics.drawable.Drawable ripple(int color) {
    android.util.TypedValue value = new android.util.TypedValue();
    getActivity().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
    try {
      return getActivity().getDrawable(value.resourceId);
    } catch (Exception ignored) {
      return new ColorDrawable(color);
    }
  }

  private void pickFile() {
    if (busy || session == null) return;
    if (uploadJob != null) { toast("请先重试或取消当前上传"); return; }
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    startActivityForResult(i, 10);
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 11) {
      File snapshot = exportFile;
      exportFile = null;
      if (snapshot == null) return;
      if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
        snapshot.delete();
        return;
      }
      Uri uri = data.getData();
      Context context = getActivity().getApplicationContext();
      task(() -> {
        try (InputStream in = new FileInputStream(snapshot);
            OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
          return MediaFiles.copy(in, out);
        } finally { snapshot.delete(); }
      }, value -> toast("文件已保存"));
      return;
    }
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
            long cap = uploadLimit(mime, false);
            if (f.length() <= 0 || f.length() > cap) {
              f.delete();
              throw new IOException("文件为空或超过 " + android.text.format.Formatter.formatShortFileSize(ctx, cap) + " 的限制");
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
    }
  }

  private long uploadLimit(String mime, boolean voice) {
    String base = mime == null ? "application/octet-stream" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (voice || base.equals("image/png") || base.equals("image/jpeg") || base.equals("image/gif") || base.equals("image/webp"))
      return 10L * 1024 * 1024;
    if (base.equals("video/mp4") || base.equals("video/webm") || base.equals("video/ogg") || base.equals("video/quicktime"))
      return 100L * 1024 * 1024;
    return 50L * 1024 * 1024;
  }

  private void cancelUpload() {
    UploadJob job = uploadJob;
    if (job == null) return;
    if (job.confirming) { toast("文件正在确认保存，暂时不能取消"); return; }
    job.cancelled = true;
    Call call = job.activeCall;
    if (call != null) call.cancel();
    uploadCancel.setEnabled(false);
    uploadRetry.setVisibility(View.GONE);
    uploadLabel.setText("正在取消上传…");
    if (!job.running) {
      job.running = true;
      io.execute(() -> finishUploadCancellation(job));
    }
  }

  private void finishUploadCancellation(UploadJob job) {
    if (job.grantId != null && net != null) {
      try { net.api("/api/r/" + job.room + "/cancel-upload/" + job.grantId, new JSONObject()); }
      catch (Exception ignored) {}
    }
    job.running = false;
    main.post(() -> {
      if (uploadJob != job) return;
      uploadJob = null;
      busy = false;
      job.file.delete();
      uploadPanel.setVisibility(View.GONE);
      setStatus("上传已取消");
    });
  }

  private void runUpload(UploadJob job) {
    if (uploadJob != job || job.cancelled || job.running || net == null) return;
    uploadRetry.setVisibility(View.GONE);
    uploadCancel.setEnabled(!job.confirming);
    busy = true;
    job.running = true;
    io.execute(() -> {
      try {
        String path = "/api/r/" + job.room + "/";
        if (job.grantId == null) {
          JSONObject payload = core.run("upload", "size", job.file.length(), "mime", job.mime,
              "filename", job.name, "clientId", job.clientId, "voice", job.voice,
              "duration", job.duration);
          JSONObject grant = net.api(path + "upload", payload);
          job.grantId = grant.getString("id");
          job.uploadUrl = grant.getString("url");
          job.acceptedMime = grant.getString("mime");
        }
        if (job.cancelled) throw new IOException("上传已取消");
        if (!job.uploaded) {
        Call call = net.uploadCall(job.uploadUrl, job.file, job.acceptedMime, (sent, total) -> {
          int progress = total <= 0 ? 0 : (int) Math.min(1000, sent * 1000 / total);
          job.progressPermille = progress;
          if (sent != total && sent % (512 * 1024) >= 32 * 1024) return;
          main.post(() -> {
            if (uploadJob == job && uploadProgress != null) {
              uploadProgress.setProgress(progress);
              uploadLabel.setText(job.name + " · " + (progress / 10) + "%");
            }
          });
        });
        job.activeCall = call;
        if (job.cancelled) call.cancel();
        try (Response response = call.execute()) {
          if (!response.isSuccessful()) throw new ChatNet.ApiError(response.code(), "文件上传失败（" + response.code() + "）");
        } finally { job.activeCall = null; }
        if (job.cancelled) throw new IOException("上传已取消");
        job.uploaded = true;
        }
        if (job.cancelled) throw new IOException("上传已取消");
        job.confirming = true;
        main.post(() -> {
          if (uploadJob == job) {
            uploadLabel.setText("正在确认并保存文件…");
            uploadCancel.setEnabled(false);
          }
        });
        JSONObject result = net.api(path + "confirm-upload/" + job.grantId, new JSONObject());
        job.running = false;
        main.post(() -> {
          if (uploadJob != job) return;
          uploadJob = null;
          busy = false;
          job.file.delete();
          uploadPanel.setVisibility(View.GONE);
          if (job.generation != generation) return;
          try {
            core.run("event", "event", new JSONObject().put("type", "message")
                .put("message", result.getJSONObject("message")));
            setStatus("已发送");
            refreshSnapshot(true);
            scrollToLatestMessage();
          } catch (Exception e) { error(e); }
        });
      } catch (Exception e) {
        job.running = false;
        if (job.cancelled) {
          if (job.grantId != null && net != null) {
            try { net.api("/api/r/" + job.room + "/cancel-upload/" + job.grantId, new JSONObject()); }
            catch (Exception ignored) {}
          }
          main.post(() -> {
            if (uploadJob != job) return;
            uploadJob = null;
            busy = false;
            job.file.delete();
            uploadPanel.setVisibility(View.GONE);
            setStatus("上传已取消");
          });
          return;
        }
        main.post(() -> {
          if (uploadJob != job) return;
          busy = false;
          uploadRetry.setVisibility(View.VISIBLE);
          uploadLabel.setText(job.name + " · " + (e.getMessage() == null ? "上传失败" : e.getMessage()));
          uploadCancel.setEnabled(!job.confirming);
          setStatus(job.confirming ? "确认结果未返回，可重试确认" : "上传失败，可重试或取消");
          if (e instanceof ChatNet.ApiError && (((ChatNet.ApiError) e).status == 401 || ((ChatNet.ApiError) e).status == 403)) error(e);
        });
      }
    });
  }

  private void confirmAttachment(
      File f, String name, String mime, boolean voice, double duration, String rid, int g) {
    if (!started || getActivity() == null) {
      busy = false;
      f.delete();
      return;
    }
    final boolean[] submitted = {false};
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
        .setTitle(voice ? "发送录音" : "发送附件")
        .setMessage(
            name
                + "\n"
                + android.text.format.Formatter.formatShortFileSize(getActivity(), f.length())
                + "\n将发送到「"
                + roomName
                + "」")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "发送",
            (d, w) -> {
              submitted[0] = true;
              stopPlayer();
              setStatus("正在上传…");
              UploadJob job = new UploadJob(f, name, mime, rid, UUID.randomUUID().toString(), voice, duration, g);
              uploadJob = job;
              busy = true;
              uploadPanel.setVisibility(View.VISIBLE);
              uploadLabel.setText(name + " · 准备上传");
              uploadProgress.setProgress(0);
              uploadRetry.setVisibility(View.GONE);
              uploadCancel.setEnabled(true);
              runUpload(job);
            })
        ;
    if (voice) builder.setNeutralButton("试听", null);
    attachmentPrompt = builder.create();
    AlertDialog prompt = attachmentPrompt;
    prompt.setOnDismissListener(d -> {
      stopPlayer();
      if (!submitted[0]) { busy = false; f.delete(); }
      if (attachmentPrompt == prompt) attachmentPrompt = null;
    });
    prompt.show();
    if (voice) prompt.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
      if (player != null) {
        stopPlayer();
        prompt.getButton(AlertDialog.BUTTON_NEUTRAL).setText("试听");
        return;
      }
      try {
        acquireMediaFocus();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        player.setDataSource(f.getAbsolutePath());
        player.setOnPreparedListener(p -> {
          p.start();
          prompt.getButton(AlertDialog.BUTTON_NEUTRAL).setText("停止试听");
        });
        player.setOnCompletionListener(p -> {
          stopPlayer();
          prompt.getButton(AlertDialog.BUTTON_NEUTRAL).setText("试听");
        });
        player.setOnErrorListener((p, what, extra) -> {
          stopPlayer();
          prompt.getButton(AlertDialog.BUTTON_NEUTRAL).setText("试听");
          toast("录音无法播放，可以取消后重新录制");
          return true;
        });
        player.prepareAsync();
      } catch (Exception e) { stopPlayer(); toast("无法试听录音"); }
    });
  }

  private void startRecording() {
    if (!started || busy || session == null || nativeCall != null) return;
    if (getActivity().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 20);
      return;
    }
    stopPlayer();
    stopInlineVideo();
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
    if (request == 41) {
      String kind = pendingCallKind;
      String callId = pendingCallId;
      String targetId = pendingCallTargetId;
      boolean current = pendingCallGeneration == generation && started;
      clearPendingCall();
      boolean granted = result.length > 0;
      for (int value : result) if (value != PackageManager.PERMISSION_GRANTED) granted = false;
      if (granted && current && !kind.isEmpty()) startNativeCall(kind, callId.isEmpty() ? null : callId, targetId);
      else toast(granted ? "状态已变化，未启动通话" : "未获得媒体权限，未启动通话");
    }
  }

  private void clearPendingCall() {
    pendingCallGeneration = -1;
    pendingCallKind = "";
    pendingCallId = "";
    pendingCallTargetId = "";
  }

  private void acquireMediaFocus() throws IOException {
    if (mediaFocus == null) mediaFocus = new AudioFocusLease(getActivity(), false, this::stopPlayer);
    mediaFocus.acquire();
  }

  private static final class AudioPlayback {
    LinearLayout card;
    final String messageId;
    final long expectedSize;
    Button toggle;
    SeekBar seek;
    TextView clock;
    File file;
    boolean ready = false, loading = false;
    AudioPlayback(LinearLayout card, String messageId, long expectedSize, Button toggle, SeekBar seek, TextView clock) {
      this.card = card; this.messageId = messageId; this.expectedSize = expectedSize;
      this.toggle = toggle; this.seek = seek; this.clock = clock;
    }
  }

  private void stopPlayer() {
    if (pendingAudio != null) {
      pendingAudio.loading = false;
      pendingAudio.toggle.setText("▶");
      pendingAudio = null;
    }
    if (mediaFocus != null) { mediaFocus.release(); mediaFocus = null; }
    audioGeneration++;
    if (audioProgress != null) main.removeCallbacks(audioProgress);
    audioProgress = null;
    if (player != null) {
      try { player.stop(); } catch (RuntimeException ignored) {}
      player.release();
      player = null;
    }
    if (activeAudio != null) {
      activeAudio.ready = false;
      activeAudio.toggle.setText("▶");
      activeAudio.toggle.setContentDescription("播放语音");
      if (activeAudio.file != null) { activeAudio.file.delete(); activeAudio.file = null; }
      activeAudio.seek.setProgress(0);
      activeAudio.clock.setText(durationLabel(0) + " / " + durationLabel(activeAudio.seek.getMax()));
    }
    activeAudio = null;
  }

  private String durationLabel(int millis) {
    int seconds = Math.max(0, millis / 1000);
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }

  private void playInlineAudio(JSONObject message, LinearLayout card) {
    Object tag = card.getTag();
    if (!(tag instanceof AudioPlayback)) return;
    AudioPlayback playback = (AudioPlayback) tag;
    if (!started || nativeCall != null || playback.loading) return;
    if (playback.file != null) {
      if (activeAudio == playback && player != null) {
        if (player.isPlaying()) {
          player.pause(); playback.toggle.setText("▶"); playback.toggle.setContentDescription("继续播放语音");
          setStatus("语音已暂停");
          if (mediaFocus != null) mediaFocus.release();
        }
        else {
          if (player.getCurrentPosition() >= playback.seek.getMax()) player.seekTo(0);
          try { acquireMediaFocus(); } catch (IOException e) { toast(e.getMessage()); return; }
          player.start(); playback.toggle.setText("Ⅱ"); playback.toggle.setContentDescription("暂停语音");
          setStatus("语音正在播放"); scheduleAudioProgress(playback);
        }
      } else startInlineAudio(playback);
      return;
    }
    if (pendingAudio != null && pendingAudio != playback) {
      pendingAudio.loading = false;
      pendingAudio.toggle.setText("▶");
    }
    pendingAudio = playback;
    playback.loading = true;
    playback.toggle.setText("…");
    String path = endpoint("download/") + playback.messageId;
    int g = generation;
    setStatus("正在读取语音…");
    scoped(() -> {
      try {
        return net.download(path, Math.min(100L * 1024 * 1024,
            Math.max(1, playback.expectedSize)) + 1024);
      } catch (Exception e) {
        main.post(() -> {
          if (g == generation && pendingAudio == playback) {
            pendingAudio = null;
            playback.loading = false;
            playback.toggle.setText("▶");
          }
        });
        throw e;
      }
    }, value -> {
      File file = (File) value;
      playback.loading = false;
      if (g != generation || !started || pendingAudio != playback || !playback.card.isAttachedToWindow()) {
        if (pendingAudio == playback) pendingAudio = null;
        playback.toggle.setText("▶");
        file.delete();
        return;
      }
      pendingAudio = null;
      playback.file = file;
      startInlineAudio(playback);
    });
  }

  private void startInlineAudio(AudioPlayback playback) {
    stopInlineVideo();
    stopPlayer();
    activeAudio = playback;
    try {
      acquireMediaFocus();
      player = new MediaPlayer();
      MediaPlayer prepared = player;
      if (playback.file == null) throw new IOException("语音文件尚未读取");
      prepared.setDataSource(playback.file.getAbsolutePath());
      prepared.setAudioAttributes(new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
      prepared.setOnPreparedListener(mp -> {
        if (player != prepared || activeAudio != playback) return;
        playback.ready = true;
        playback.seek.setMax(mp.getDuration());
        playback.clock.setText(durationLabel(0));
        playback.toggle.setText("Ⅱ");
        playback.toggle.setContentDescription("暂停语音");
        mp.start();
        setStatus("语音正在播放");
        scheduleAudioProgress(playback);
      });
      prepared.setOnCompletionListener(mp -> {
        if (activeAudio != playback) return;
        if (audioProgress != null) main.removeCallbacks(audioProgress);
        audioProgress = null;
        playback.seek.setProgress(playback.seek.getMax());
        playback.clock.setText(durationLabel(playback.seek.getMax()));
        playback.toggle.setText("▶");
        playback.toggle.setContentDescription("重播语音");
        setStatus("语音播放结束");
        if (mediaFocus != null) mediaFocus.release();
      });
      prepared.setOnErrorListener((mp, what, extra) -> {
        if (activeAudio == playback) {
          toast("此音频无法在设备上播放");
          stopPlayer();
        }
        return true;
      });
      prepared.prepareAsync();
    } catch (Exception e) {
      stopPlayer();
      toast("此音频无法在设备上播放");
    }
  }

  private void scheduleAudioProgress(AudioPlayback playback) {
    if (audioProgress != null) main.removeCallbacks(audioProgress);
    final int token = audioGeneration;
    audioProgress = new Runnable() {
      @Override public void run() {
        if (token != audioGeneration || player == null || activeAudio != playback) return;
        if (player.isPlaying()) {
          int position = player.getCurrentPosition();
          playback.seek.setProgress(position);
          playback.clock.setText(durationLabel(position));
          // Leave a quiet interval longer than Android accessibility's 1-second idle window.
          main.postDelayed(this, 1500);
        }
      }
    };
    main.post(audioProgress);
  }

  private String fileSizeSuffix(long bytes) {
    return bytes > 0 && getActivity() != null
        ? " · " + android.text.format.Formatter.formatShortFileSize(getActivity(), bytes)
        : "";
  }

  private void loadInlineImage(ImageView target, TextView placeholder, String id, String path, long expectedBytes) {
    final String imageKey = room + ":" + id;
    target.setTag(imageKey);
    if (id.isEmpty() || expectedBytes <= 0 || expectedBytes > 10L * 1024 * 1024) return;
    Bitmap cached = inlineImageCache.get(imageKey);
    if (cached != null) {
      target.setImageBitmap(cached);
      placeholder.setVisibility(View.GONE);
      return;
    }
    if (!inlineImagesLoading.add(imageKey)) return;
    final int requestGeneration = generation;
    try {
      io.execute(() -> {
        Bitmap bitmap = null;
        File file = null;
        try {
          file = net.download(path, Math.min(10L * 1024 * 1024, expectedBytes + 1024));
          BitmapFactory.Options bounds = new BitmapFactory.Options();
          bounds.inJustDecodeBounds = true;
          BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
          if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            int sample = 1;
            while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
          }
        } catch (Exception ignored) {
          // Keep the lightweight placeholder; tapping the card still uses the existing viewer.
        } finally {
          if (file != null) file.delete();
        }
        final Bitmap decoded = bitmap;
        main.post(() -> {
          inlineImagesLoading.remove(imageKey);
          if (decoded == null || dead || requestGeneration != generation
              || target.getTag() == null || !imageKey.equals(target.getTag())
              || !target.isAttachedToWindow()) return;
          inlineImageCache.put(imageKey, decoded);
          target.setImageBitmap(decoded);
          placeholder.setVisibility(View.GONE);
        });
      });
    } catch (RejectedExecutionException ignored) {
      inlineImagesLoading.remove(imageKey);
    }
  }

  private void playInlineVideo(JSONObject message, FrameLayout preview, TextView placeholder) {
    if (!isAdded() || !started || nativeCall != null) return;
    stopPlayer();
    String messageId = message.optString("id");
    if (!inlineVideoLoadingId.isEmpty()) return;
    if (activeInlineVideo != null && activeInlineVideo.getParent() == preview) {
      if (activeInlineVideo.isPlaying()) activeInlineVideo.pause();
      else activeInlineVideo.start();
      return;
    }
    stopInlineVideo();
    inlineVideoLoadingId = messageId;
    int videoGeneration = ++inlineVideoGeneration;
    inlineVideoPlaceholder = placeholder;
    placeholder.setText("正在读取视频…");
    String path = endpoint("download/") + messageId;
    int requestGeneration = generation;
    setStatus("正在读取视频…");
    scoped(
        () -> net.download(path,
            Math.min(100L * 1024 * 1024, Math.max(1, message.optLong("size"))) + 1024),
        value -> {
          File file = (File) value;
          if (videoGeneration != inlineVideoGeneration || requestGeneration != generation
              || !started || !isAdded() || !preview.isAttachedToWindow()) {
            file.delete();
            if (videoGeneration == inlineVideoGeneration) {
              inlineVideoLoadingId = "";
              placeholder.setText("视频未能打开，轻触重试");
            }
            return;
          }
          inlineVideoLoadingId = "";
          VideoView video = new VideoView(getActivity());
          video.setBackgroundColor(Color.BLACK);
          MediaController controls = new MediaController(getActivity());
          video.setMediaController(controls);
          video.setVideoURI(Uri.fromFile(file));
          FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
          preview.addView(video, params);
          activeInlineVideo = video;
          activeInlineVideoFile = file;
          placeholder.setVisibility(View.GONE);
          preview.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
              if (activeInlineVideo == video) {
                placeholder.setVisibility(View.VISIBLE);
                placeholder.setText("轻触播放");
                stopInlineVideo();
              }
            }
          });
          video.setOnPreparedListener(player -> {
            if (video != activeInlineVideo) return;
            player.setOnVideoSizeChangedListener((mp, width, height) -> {
              if (width > 0 && height > 0 && video.getParent() == preview) {
                float aspect = (float) width / height;
                int maxWidth = Math.max(dp(120), Math.min(dp(320),
                    getResources().getDisplayMetrics().widthPixels - dp(72)));
                int maxHeight = dp(220);
                int w = maxWidth, h = Math.round(w / aspect);
                if (h > maxHeight) { h = maxHeight; w = Math.round(h * aspect); }
                FrameLayout.LayoutParams resized = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
                video.setLayoutParams(resized);
                preview.getLayoutParams().height = h;
                preview.requestLayout();
              }
            });
            video.start();
            setStatus("视频正在播放");
          });
          video.setOnCompletionListener(mp -> setStatus("视频播放结束"));
          video.setOnErrorListener((mp, what, extra) -> {
            if (video == activeInlineVideo) {
              stopInlineVideo();
              placeholder.setText("此视频编码暂不支持，可用全屏或保存后播放");
              setStatus("视频无法播放");
            }
            return true;
          });
        });
  }

  private void stopInlineVideo() {
    inlineVideoGeneration++;
    inlineVideoLoadingId = "";
    VideoView video = activeInlineVideo;
    File file = activeInlineVideoFile;
    TextView placeholder = inlineVideoPlaceholder;
    activeInlineVideo = null;
    activeInlineVideoFile = null;
    inlineVideoPlaceholder = null;
    if (placeholder != null) {
      placeholder.setVisibility(View.VISIBLE);
      placeholder.setText("▶  轻触播放");
    }
    if (video != null) {
      try { video.stopPlayback(); } catch (RuntimeException ignored) {}
      ViewGroup parent = (ViewGroup) video.getParent();
      if (parent != null) parent.removeView(video);
    }
    if (file != null && file.exists()) file.delete();
  }

  private void downloadForSave(JSONObject message) {
    final String path = endpoint("download/") + message.optString("id");
    scoped(() -> net.download(path, Math.min(MediaFiles.MAX_BYTES, Math.max(1, message.optLong("size"))) + 1024), value -> {
      File file = (File) value;
      saveFile(file, message.optString("filename", "文件"), message.optString("mime"), true);
    });
  }

  private void openMedia(JSONObject m) {
    if (m.optString("kind").equals("video")) stopInlineVideo();
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
            showImageLightbox(image, name, f, m.optString("mime"));
          } else if (kind.equals("audio")) {
            stopPlayer();
            acquireMediaFocus();
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
            AlertDialog audioDialog = new AlertDialog.Builder(getActivity())
                .setTitle("语音播放")
                .setMessage(name)
                .setPositiveButton("停止", (d, w) -> stopPlayer())
                .setNeutralButton("保存", null)
                .setOnDismissListener(d -> { stopPlayer(); f.delete(); mediaDialog = null; })
                .show();
            mediaDialog = audioDialog;
            audioDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> saveFile(f, name, m.optString("mime")));
          } else if (kind.equals("video")) {
            showVideoViewer(f, name, m.optString("mime"));
          } else saveFile(f, name, m.optString("mime"), true);
        });
  }

  private void showImageLightbox(Bitmap image, String name, File file, String mime) {
    Dialog dialog = new Dialog(getActivity(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    LinearLayout page = vertical();
    page.setBackgroundColor(Color.BLACK);
    LinearLayout toolbar = new LinearLayout(getActivity());
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
    TextView title = text(name, 14, Color.WHITE);
    title.setMaxLines(1);
    title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
    Button save = button("保存", () -> saveFile(file, name, mime));
    save.setContentDescription("保存图片");
    toolbar.addView(save, new LinearLayout.LayoutParams(dp(64), dp(42)));
    Button close = button("关闭", dialog::dismiss);
    close.setContentDescription("关闭图片预览");
    LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(64), dp(42));
    closeParams.leftMargin = dp(6);
    toolbar.addView(close, closeParams);
    page.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
    ZoomImageView view = new ZoomImageView(getActivity());
    view.setImageBitmap(image);
    view.setContentDescription("图片全屏预览，可双指缩放：" + name);
    page.addView(view, new LinearLayout.LayoutParams(-1, 0, 1f));
    dialog.setContentView(page);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setLayout(-1, -1);
      window.setBackgroundDrawableResource(android.R.color.black);
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    mediaDialog = dialog;
    dialog.setOnDismissListener(d -> { image.recycle(); file.delete(); if (mediaDialog == dialog) mediaDialog = null; });
    dialog.show();
    if (window != null) window.setLayout(-1, -1);
  }

  private final class ZoomImageView extends ImageView {
    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private float baseScale = 1f, userScale = 1f, lastX, lastY;

    ZoomImageView(Context context) {
      super(context);
      setScaleType(ScaleType.MATRIX);
      scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override public boolean onScale(ScaleGestureDetector detector) {
          float next = Math.max(1f, Math.min(5f, userScale * detector.getScaleFactor()));
          float factor = next / userScale;
          userScale = next;
          matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
          setImageMatrix(matrix);
          return true;
        }
      });
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
      super.onSizeChanged(w, h, oldw, oldh);
      Drawable d = getDrawable();
      if (d == null || w <= 0 || h <= 0) return;
      float dw = Math.max(1, d.getIntrinsicWidth()), dh = Math.max(1, d.getIntrinsicHeight());
      baseScale = Math.min(w / dw, h / dh);
      userScale = 1f;
      matrix.reset();
      matrix.postScale(baseScale, baseScale);
      matrix.postTranslate((w - dw * baseScale) / 2f, (h - dh * baseScale) / 2f);
      setImageMatrix(matrix);
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
      scaleDetector.onTouchEvent(event);
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          lastX = event.getX();
          lastY = event.getY();
          return true;
        case MotionEvent.ACTION_MOVE:
          if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
            float dx = event.getX() - lastX, dy = event.getY() - lastY;
            if (userScale > 1f) {
              matrix.postTranslate(dx, dy);
              setImageMatrix(matrix);
            }
            lastX = event.getX();
            lastY = event.getY();
          }
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          performClick();
          return true;
        default:
          return true;
      }
    }

    @Override public boolean performClick() {
      super.performClick();
      return true;
    }
  }

  private void showVideoViewer(File file, String name, String mime) {
    Dialog dialog = new Dialog(getActivity(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    FrameLayout page = new FrameLayout(getActivity());
    page.setBackgroundColor(Color.BLACK);
    VideoView video = new VideoView(getActivity());
    video.setVideoURI(Uri.fromFile(file));
    FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(-1, -1);
    page.addView(video, videoParams);
    MediaController controls = new MediaController(getActivity());
    video.setMediaController(controls);
    LinearLayout toolbar = new LinearLayout(getActivity());
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
    toolbar.setBackgroundColor(0x99000000);
    TextView title = text(name, 14, Color.WHITE);
    title.setMaxLines(1);
    title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
    Button save = button("保存", () -> saveFile(file, name, mime));
    save.setContentDescription("保存视频");
    toolbar.addView(save, new LinearLayout.LayoutParams(dp(64), dp(42)));
    Button close = button("关闭", dialog::dismiss);
    close.setContentDescription("关闭视频预览");
    LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(64), dp(42));
    closeParams.leftMargin = dp(6);
    toolbar.addView(close, closeParams);
    FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    page.addView(toolbar, toolbarParams);
    dialog.setContentView(page);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setLayout(-1, -1);
      window.setBackgroundDrawableResource(android.R.color.black);
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    video.setOnPreparedListener(p -> video.start());
    video.setOnErrorListener(
        (p, a, b) -> {
          toast("此视频编码暂不支持，可保存后播放");
          return true;
        });
    mediaDialog = dialog;
    dialog.setOnDismissListener(d -> { video.stopPlayback(); file.delete(); if (mediaDialog == dialog) mediaDialog = null; });
    dialog.show();
    if (window != null) window.setLayout(-1, -1);
  }

  private void saveFile(File f, String name, String mime) {
    saveFile(f, name, mime, false);
  }

  private void saveFile(File f, String name, String mime, boolean deleteSource) {
    if (!started || exportFile != null || exportPreparing || getActivity() == null) {
      if (deleteSource) f.delete();
      toast("请先完成或取消当前保存");
      return;
    }
    exportPreparing = true;
    Context context = getActivity().getApplicationContext();
    // Snapshot before opening DocumentsUI: onStop may release the source player/file.
    task(() -> {
      try { return MediaFiles.snapshot(f, context.getCacheDir()); }
      catch (Exception error) { main.post(() -> exportPreparing = false); throw error; }
      finally { if (deleteSource) f.delete(); }
    }, value -> {
      exportPreparing = false;
      File snapshot = (File) value;
      if (!started || getActivity() == null) { snapshot.delete(); return; }
      exportFile = snapshot;
      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
      intent.putExtra(Intent.EXTRA_TITLE, MediaFiles.displayName(name));
      try { startActivityForResult(intent, 11); }
      catch (ActivityNotFoundException error) {
        snapshot.delete(); exportFile = null; toast("设备未提供文件保存器");
      }
    });
  }

  boolean back() {
    if (drawerOverlay != null && drawerOverlay.getVisibility() == View.VISIBLE) {
      closeRoomDrawer();
      return true;
    }
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
