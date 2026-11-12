package tv.debloat.mirrorsend;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_AUDIO = 1002;
    /** 开机自检用的几个请求码，跟录屏授权分开，互不干扰 */
    private static final int REQ_NOTI = 1003;
    private static final int REQ_BATTERY = 1004;
    private static final int REQ_OVERLAY = 1005;
    /** 电视端 UDP 自动发现服务监听的端口（和电视端 MirrorActivity 的 DISCOVER_PORT 对应）。 */
    private static final int DISCOVER_PORT = 9100;

    private EditText ipInput;
    private TextView hint;
    private SeekBar gainBar;
    private TextView gainLabel;
    private String pendingHost = "";

    /** 点一次开始到真正投上要经过"扫描 + 授权"，这段时间里按钮先锁住，防止用户连点。 */
    private boolean startBusy = false;

    /** 投屏音量，默认 15%。响度是对数的，15% 大约 -16dB，比满幅听着明显小一档。 */
    private static final int DEFAULT_GAIN = 15;

    // 深色配色，投屏全程是全屏黑底的，界面跟着深色不扎眼
    private static final int C_BG = 0xFF0D1117;
    private static final int C_CARD = 0xFF161B22;
    private static final int C_CARD2 = 0xFF1C2430;
    private static final int C_LINE = 0xFF30363D;
    private static final int C_TEXT = 0xFFE6EDF3;
    private static final int C_TEXT2 = 0xFFC9D1D9;
    private static final int C_SUB = 0xFF8B949E;
    private static final int C_ACCENT = 0xFF2F6FED;

    /** 圆角卡片背景，纯代码画，不用 layout xml。 */
    private GradientDrawable card(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogHub.init(this);
        LogHub.startServer();
        LogHub.add("打开 App");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 顶栏：图标 + 名字 ──
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        int iconId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (iconId != 0) logo.setImageResource(iconId);
        head.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("投屏到电视");
        title.setTextSize(22);
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("手机画面连声音一起送过去");
        subtitle.setTextSize(13);
        subtitle.setTextColor(C_SUB);
        subtitle.setPadding(0, dp(4), 0, 0);
        titles.addView(subtitle);
        head.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView settingsBtn = new TextView(this);
        settingsBtn.setText("设置");
        settingsBtn.setTextSize(14);
        settingsBtn.setTextColor(C_SUB);
        settingsBtn.setPadding(dp(12), dp(10), dp(4), dp(10));
        settingsBtn.setOnClickListener(v -> showSettings());
        head.addView(settingsBtn);
        root.addView(head);

        // ── 状态卡：所有提示都落在这张卡里，不再散在按钮之间 ──
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        statusCard.setBackground(card(C_CARD, 14, 0, 0));

        TextView statusTitle = new TextView(this);
        statusTitle.setText("状态");
        statusTitle.setTextSize(12);
        statusTitle.setTextColor(C_SUB);
        statusCard.addView(statusTitle);

        hint = new TextView(this);
        hint.setTextSize(15);
        hint.setTextColor(C_TEXT2);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(0, dp(8), 0, 0);
        hint.setText("先在电视上打开「投屏电视端」，\n"
                + "然后在这里点开始，系统会弹一次录屏授权，允许即可。");
        statusCard.addView(hint);

        root.addView(statusCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 地址不用人填，点开始投屏时自己扫。这里只是个反馈，找到了才露出来。
        ipInput = new EditText(this);
        ipInput.setText("");
        ipInput.setHint("未找到电视");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ipInput.setTextSize(13);
        ipInput.setTextColor(C_SUB);
        ipInput.setVisibility(View.GONE);
        root.addView(ipInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 主按钮 ──
        Button start = new Button(this);
        start.setText("开始投屏");
        start.setTextSize(18);
        start.setTextColor(Color.WHITE);
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setAllCaps(false);
        start.setBackground(card(C_ACCENT, 14, 0, 0));
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        bpl.topMargin = dp(18);
        root.addView(start, bpl);

        // ── 音量卡：中间滑块，两头加减号 ──
        // 只改电视端那一路 PCM 的增益，电视自己的音量不动，
        // 所以投完屏换别的应用听节目，不用再把声音调回去。
        LinearLayout gainCard = new LinearLayout(this);
        gainCard.setOrientation(LinearLayout.VERTICAL);
        gainCard.setPadding(dp(18), dp(16), dp(18), dp(12));
        gainCard.setBackground(card(C_CARD, 14, 0, 0));
        LinearLayout.LayoutParams gcl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gcl.topMargin = dp(14);

        LinearLayout gainHead = new LinearLayout(this);
        gainHead.setOrientation(LinearLayout.HORIZONTAL);
        gainHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView gainTitle = new TextView(this);
        gainTitle.setText("投屏音量");
        gainTitle.setTextSize(14);
        gainTitle.setTextColor(C_TEXT2);
        gainHead.addView(gainTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        gainLabel = new TextView(this);
        gainLabel.setTextSize(16);
        gainLabel.setTextColor(C_ACCENT);
        gainLabel.setTypeface(Typeface.DEFAULT_BOLD);
        gainLabel.setText(getSavedGain() + "%");
        gainHead.addView(gainLabel);
        gainCard.addView(gainHead);

        LinearLayout gainRow = new LinearLayout(this);
        gainRow.setOrientation(LinearLayout.HORIZONTAL);
        gainRow.setGravity(Gravity.CENTER_VERTICAL);
        gainRow.setPadding(0, dp(6), 0, 0);

        Button minus = new Button(this);
        minus.setText("−");
        minus.setTextSize(20);
        minus.setTextColor(C_TEXT);
        minus.setAllCaps(false);
        minus.setBackground(card(C_CARD2, 10, 1, C_LINE));
        LinearLayout.LayoutParams mb = new LinearLayout.LayoutParams(dp(52), dp(44));
        gainRow.addView(minus, mb);

        gainBar = new SeekBar(this);
        gainBar.setMax(100);
        // 下限 0：拉到底就是完全静音，投屏时不想听声音直接关掉，不用碰电视音量
        gainBar.setMin(0);
        gainBar.setProgress(getSavedGain());
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sbp.leftMargin = dp(10);
        sbp.rightMargin = dp(10);
        gainRow.addView(gainBar, sbp);

        Button plus = new Button(this);
        plus.setText("+");
        plus.setTextSize(20);
        plus.setTextColor(C_TEXT);
        plus.setAllCaps(false);
        plus.setBackground(card(C_CARD2, 10, 1, C_LINE));
        gainRow.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(44)));

        gainCard.addView(gainRow);
        TextView gainTip = new TextView(this);
        gainTip.setText("只影响投屏这一路，不动电视本身的音量");
        gainTip.setTextSize(12);
        gainTip.setTextColor(C_SUB);
        gainTip.setPadding(0, dp(6), 0, 0);
        gainCard.addView(gainTip);
        root.addView(gainCard, gcl);

        // ── 权限卡已经删了 ──
        // 「后台运行」「悬浮窗」两个按钮原本是给用户手动补权限的，现在一进 App 就会
        // 自动串行申请（见 autoGrant / nextGrantStep），按钮属于重复入口，还占一屏高度，
        // 所以整张卡片拿掉了。申请流程本身没动。
        // 想手动再走一遍的话，右上角「设置」里还有入口。

        // ── 停止：次要动作，描边不抢眼 ──
        Button stop = new Button(this);
        stop.setText("停止投屏");
        stop.setTextSize(15);
        stop.setAllCaps(false);
        stop.setTextColor(C_SUB);
        stop.setBackground(card(C_CARD, 12, 1, C_LINE));
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        spl.topMargin = dp(14);
        root.addView(stop, spl);

        setContentView(scroll);

        start.setOnClickListener(v -> {
            // 连点两次会启两条发送线程互抢编码器、直接把投屏搞崩，
            // 所以在"扫描 + 等授权"这段时间里先把按钮锁住。
            if (startBusy) return;
            startBusy = true;
            start.setEnabled(false);
            start.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startBusy = false;
                    start.setEnabled(true);
                }
            }, 8000);
            requestCapture();
        });
        minus.setOnClickListener(v -> applyGain(gainBar.getProgress() - 1));
        plus.setOnClickListener(v -> applyGain(gainBar.getProgress() + 1));
        gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) applyGain(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                applyGain(sb.getProgress());
            }
        });
        checkBatteryHint();
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, MirrorService.class);
            i.setAction(MirrorService.ACTION_STOP);
            startService(i);
            hint.setText("已停止投屏");
        });

        try {
            IntentFilter f = new IntentFilter(MirrorService.ACTION_STATUS);
            registerReceiver(statusReceiver, f);
        } catch (Exception ignored) {}

        // 第一次打开就把"后台别杀我"那几个开关自动申请一遍
        autoGrant();
    }

    // ────────────────────── 后台常驻权限的自动申请 ──────────────────────
    //
    // 安卓并没有一个叫"后台无限制"的总开关，也没有任何 API 能保证进程永不被杀。
    // 能争取到的只是下面几项，凑在一起才最接近"常驻"：
    //   1. 通知权限      —— 前台服务的常驻通知要能显示，否则用户以为没在跑
    //   2. 电池优化白名单 —— 系统不再因为省电把进程掐掉
    //   3. 悬浮窗        —— 挂一个 1px 的窗口，进程被判定为"可见"，不容易被冻结
    //   4. 自启动管理    —— 国产 ROM 自己的开关（小米/华为/OPPO/vivo 各一套）
    // 首次安装时按顺序串行弹一遍（系统一次只允许一个权限页在前台），
    // 全部走完后在本地记一笔，以后打开只要都授过了就不再打扰。

    /** 当前走到第几步。0 表示没在引导。 */
    private int grantStep = 0;

    private void autoGrant() {
        boolean done = getSharedPreferences("mirror", MODE_PRIVATE)
                .getBoolean("autoGrantDone", false);
        if (done) return;
        grantStep = 1;
        LogHub.add("开始自动申请后台常驻权限");
        nextGrantStep();
    }

    private void nextGrantStep() {
        if (grantStep == 0) return;   // 已经走完，不要再进
        if (grantStep == 1) {
            grantStep = 2;
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                hint.setText("第 1/3 步：允许通知，\n这样投屏跑着的时候你能看见它还在。");
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTI);
                return;
            }
            nextGrantStep();
        } else if (grantStep == 2) {
            grantStep = 3;
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    try {
                        hint.setText("第 2/3 步：系统会问「是否允许后台不限电」，\n选「允许」。");
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(i, REQ_BATTERY);
                        return;
                    } catch (Exception e) {
                        LogHub.add("电池优化弹窗跳不过去，跳过这一步");
                    }
                }
            }
            nextGrantStep();
        } else if (grantStep == 3) {
            grantStep = 4;
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                try {
                    hint.setText("第 3/3 步：把「显示在其他应用上层」打开，\n进程才不容易被冻结。");
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_OVERLAY);
                    return;
                } catch (Exception e) {
                    LogHub.add("悬浮窗设置页跳不过去，跳过这一步");
                }
            }
            nextGrantStep();
        } else {
            grantStep = 0;
            try {
                getSharedPreferences("mirror", MODE_PRIVATE).edit()
                        .putBoolean("autoGrantDone", true).apply();
            } catch (Throwable ignored) {}
            LogHub.add("后台权限自动申请走完了");
            checkBatteryHint();
            hint.setText("后台权限都设好了，\n到设置里打开「投屏电视端」，\n这里点开始，系统会弹一次录屏授权，允许即可。");
        }
    }

    /** 跳到本机自启动管理页，跳得到就返回 true。 */
    private boolean openAutoStartPage() {
        String maker = String.valueOf(Build.MANUFACTURER).toLowerCase();
        ComponentName cn = null;
        if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")) {
            cn = new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity");
        } else if (maker.contains("huawei") || maker.contains("honor")) {
            cn = new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
        } else if (maker.contains("oppo") || maker.contains("realme") || maker.contains("oneplus")) {
            cn = new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity");
        } else if (maker.contains("vivo") || maker.contains("iqoo")) {
            cn = new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
        }
        if (cn == null) return false;
        try {
            Intent i = new Intent();
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            LogHub.add("已跳本机自启动管理页");
            return true;
        } catch (Exception e) {
            LogHub.add("这台机器没有自启动管理页，改跳应用详情");
            try {
                Intent i2 = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i2);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra("state");
            String detail = intent.getStringExtra("detail");
            if ("playing".equals(state)) {
                hint.setText("投屏成功，正在传 " + detail + "\n画面已经在电视上了，\n现在可以退回桌面用别的 App。");
            } else if ("error".equals(state)) {
                hint.setText("投屏中断：" + detail + "\n可以点开始再连一次。");
            } else if ("stopped".equals(state)) {
                hint.setText("投屏已停止");
            }
        }
    };

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    /**
     * UDP 广播找电视。
     *
     * 老版本只往 255.255.255.255 打一包、然后干等 2.5 秒，有两个问题：
     *
     * 1) **没有外网的热点会把广播发到移动数据上**。手机开的热点（或者家里断网的路由器）里，
     *    系统仍然认为"移动数据才是默认网络"，WiFi 因为连不上外网被标成 unvalidated。
     *    这时 new 出来的 DatagramSocket 如果不绑定，内核就会挑默认网卡（5G）发出去，
     *    广播压根没进局域网——表现就是"明明连的是同一个热点，电视也在跑，就是搜不到"。
     *    所以这里先拿到当前那个 WiFi 的 Network，用 Network.bindSocket() 把 socket
     *    钉死在它上面再发。
     *
     * 2) 全局广播 255.255.255.255 有些手机热点/路由器不转发，或者第一包丢在还没建 ARP 表的时候。
     *    所以同时再往"本网段广播地址"（拿子网掩码现算，比如 192.168.246.255）发一份，
     *    并且重发多轮，每轮 900 毫秒，最多 4.5 秒。
     *
     * 找到（或没找到）都会回调，ip 为 null 表示没搜到。
     */
    private void discover(final FoundCallback cb) {
        hint.setText("正在搜索局域网里的电视…\n（几秒左右）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.List<Cast> casts = new java.util.ArrayList<Cast>();
                String found = null;
                boolean lan = false;
                try {
                    // ── 路子一：系统认得的局域网（手机连了别人家的 WiFi / 插着网线）──
                    Network net = lanNetwork();
                    if (net != null) {
                        try {
                            DatagramSocket ds = new DatagramSocket();
                            net.bindSocket(ds);
                            ds.setBroadcast(true);
                            Cast c = new Cast();
                            c.sock = ds;
                            c.to.add(InetAddress.getByName("255.255.255.255"));
                            InetAddress sub = subnetBroadcast(net);
                            if (sub != null) c.to.add(sub);
                            casts.add(c);
                            lan = true;
                        } catch (Throwable ignored) {}
                    }

                    // ── 路子二：直接按网卡地址绑（手机自己开热点就走这条）──
                    //
                    // 手机开热点时，热点那个接口（ap0/wlan0）不会注册成一个 Network，
                    // getAllNetworks() 里只剩移动数据。于是路子一什么都拿不到，
                    // socket 又退回默认网卡（5G），广播照旧出不去。
                    //
                    // 所以这里不看 Network，改成枚举 NetworkInterface：凡是 up 着、
                    // 有 IPv4 的非 loopback 网卡，都拿它的本地地址绑一个 socket 出来，
                    // 广播就只能从这个网卡走。每个网卡再补一发它自己的子网广播地址
                    // （InterfaceAddress.getBroadcast() 直接给，不用自己算）。
                    try {
                        java.util.Enumeration<java.net.NetworkInterface> en =
                                java.net.NetworkInterface.getNetworkInterfaces();
                        while (en != null && en.hasMoreElements()) {
                            java.net.NetworkInterface nif = en.nextElement();
                            try {
                                if (!nif.isUp() || nif.isLoopback()) continue;
                                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                                    InetAddress a = ia.getAddress();
                                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                                    DatagramSocket s2 = new DatagramSocket(
                                            new java.net.InetSocketAddress(a, 0));
                                    s2.setBroadcast(true);
                                    Cast c2 = new Cast();
                                    c2.sock = s2;
                                    c2.to.add(InetAddress.getByName("255.255.255.255"));
                                    InetAddress bc = ia.getBroadcast();
                                    if (bc != null) c2.to.add(bc);
                                    casts.add(c2);
                                    lan = true;
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}

                    byte[] q = "MIRROR-FIND".getBytes("UTF-8");
                    long deadline = System.currentTimeMillis() + 4500L;
                    while (System.currentTimeMillis() < deadline && found == null) {
                        for (Cast c : casts) {
                            for (InetAddress a : c.to) {
                                try {
                                    c.sock.send(new DatagramPacket(q, q.length, a, DISCOVER_PORT));
                                } catch (Throwable ignored) {}
                            }
                        }
                        long slice = System.currentTimeMillis() + 900L;
                        while (System.currentTimeMillis() < slice && found == null) {
                            for (Cast c : casts) {
                                long left = slice - System.currentTimeMillis();
                                if (left <= 0) break;
                                byte[] buf = new byte[64];
                                DatagramPacket r = new DatagramPacket(buf, buf.length);
                                c.sock.setSoTimeout((int) Math.max(20, Math.min(120, left)));
                                try {
                                    c.sock.receive(r);
                                } catch (Throwable te) {
                                    continue;
                                }
                                String txt = new String(r.getData(), 0, r.getLength(), "UTF-8").trim();
                                if (txt.startsWith("TVMIRROR")) {
                                    found = r.getAddress().getHostAddress();
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    found = null;
                } finally {
                    for (Cast c : casts) {
                        try { c.sock.close(); } catch (Throwable ignored) {}
                    }
                }
                final String ip = found;
                final boolean hasLan = lan;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ip != null) {
                            ipInput.setText(ip);
                            ipInput.setVisibility(View.VISIBLE);
                        } else if (!hasLan) {
                            hint.setText("没搜到电视 —— 手机现在没接在任何局域网上。\n"
                                    + "先把手机连到电视所在的那个热点或 WiFi，再点一次开始投屏。");
                        } else {
                            ipInput.setVisibility(View.VISIBLE);
                            ipInput.setHint("没搜到。也可以把电视上显示的 IP 填在这里");
                            hint.setText("没搜到电视。请确认：\n"
                                    + "1. 电视真的连上了手机热点（电视端界面会显示自己的 IP）\n"
                                    + "2. 电视上「投屏电视端」正开着，并且停在前台\n"
                                    + "3. 手机热点没开「设备隔离 / 客户端隔离」\n"
                                    + "4. 刚开热点时等几秒，让电视连上分到 IP 再点开始投屏");
                        }
                        if (cb != null) cb.onFound(ip);
                    }
                });
            }
        }).start();
    }

    /** 一个已经绑好网卡的广播 socket，和它要打的目标地址。 */
    private static class Cast {
        DatagramSocket sock;
        java.util.List<InetAddress> to = new java.util.ArrayList<InetAddress>();
    }

    /**
     * 当前能用的局域网接口。优先 WiFi（最常见），其次网线（USB 网卡 / 扩展坞走以太网），
     * 都不行再退回 USB 共享之类的其他非蜂窝网络。
     *
     * 注意：不能直接依赖"默认网络"。默认网络有可能是移动数据（尤其 WiFi/热点没有外网时），
     * 把广播发到蜂窝网络上，对面那台电视永远收不到。所以宁可自己挑一个局域网接口。
     */
    private Network lanNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network usb = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return n;
                // 13 = TRANSPORT_USB（API 31 才有常量，这里直接用数值，老系统上取到 null 也无妨）
                if (nc.hasTransport(13) && usb == null) usb = n;
            }
            return usb;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 拿 WiFi 的 IPv4 地址 + 子网掩码算出本网段广播地址，作为 255.255.255.255 之外的补发目标。 */
    private InetAddress subnetBroadcast(Network n) {
        if (n == null) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (!(a instanceof Inet4Address)) continue;
                int prefix = la.getPrefixLength();
                if (prefix < 1 || prefix >= 32) continue;
                byte[] raw = a.getAddress();
                int ip = 0;
                for (byte b : raw) ip = (ip << 8) | (b & 0xff);
                int mask = 0xffffffff << (32 - prefix);
                int bc = (ip & mask) | (~mask);
                return InetAddress.getByAddress(new byte[]{
                        (byte) (bc >>> 24), (byte) (bc >>> 16), (byte) (bc >>> 8), (byte) bc});
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 发现结果的回调，ip 为 null 表示没找到。 */
    private interface FoundCallback {
        void onFound(String ip);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** 上次设的音量，重启 App 还记着。 */
    private int getSavedGain() {
        try {
            return getSharedPreferences("mirror", MODE_PRIVATE)
                    .getInt("gain", DEFAULT_GAIN);
        } catch (Throwable e) {
            return DEFAULT_GAIN;
        }
    }

    /**
     * 设投屏音量。
     *
     * 这条指令只送到服务里、再转给电视端，改的是电视上那一路 PCM 的增益；
     * 电视自己的系统音量不动，所以投完屏换回别的应用不用再把音量调回去。
     * 没在投屏的时候点也没关系，服务会把值记着，下次连上自动补发。
     */
    private void applyGain(int pct) {
        // 0% 就是彻底静音，不要再把下限卡在 1
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        if (gainBar.getProgress() != pct) gainBar.setProgress(pct);
        gainLabel.setText(pct + "%");
        try {
            getSharedPreferences("mirror", MODE_PRIVATE).edit().putInt("gain", pct).apply();
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(this, MirrorService.class);
            i.setAction(MirrorService.ACTION_GAIN);
            i.putExtra(MirrorService.EXTRA_GAIN, pct);
            startService(i);
        } catch (Throwable ignored) {}
    }

    private void requestCapture() {
        // 每次都重新广播扫一遍，不信任界面上、内存里或以前存下来的任何地址。
        // 电视重启后 IP 会变，只有当场扫到的才是准的。
        discover(new FoundCallback() {
            @Override
            public void onFound(String ip) {
                if (ip == null || ip.length() == 0) {
                    // 兜底：手填。电视端界面上会显示它自己的 IP，实在扫不到就照着填。
                    String typed = ipInput.getText().toString().trim();
                    if (typed.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                        pendingHost = typed;
                        hint.setText("按你填的 " + typed + " 去连，正在申请录屏授权…");
                        ensureAudioThenCapture();
                    }
                    return;
                }
                pendingHost = ip;
                ipInput.setVisibility(View.VISIBLE);
                ipInput.setText(ip);
                hint.setText("找到电视 " + ip + "，正在申请录屏授权…");
                ensureAudioThenCapture();
            }
        });
    }

    /** 拿录音权限（抓内录声音要用），拿到就继续去申请录屏。 */
    private void ensureAudioThenCapture() {
        // 要抓手机自己放的声音，得先给录音权限
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_AUDIO);
            return;
        }
        goCapture();
    }

    private void goCapture() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            hint.setText("这台手机不支持录屏接口");
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        if (code == REQ_AUDIO) {
            goCapture();
        } else if (code == REQ_NOTI) {
            // 通知给不给都不影响投屏，记一笔继续下一步
            LogHub.add("通知权限结果：" + (grants.length > 0 && grants[0] == 0 ? "允许" : "拒绝"));
            nextGrantStep();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void checkBatteryHint() {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            hint.setText("提示：系统还没允许后台运行，投屏可能几秒就被掐断。\n右上角「设置」里可以重新走一遍；电视端现在是常驻的，\n不用再去电视上点图标，这里点开始、允许一次录屏即可。");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "设置");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 设置页。目前只有清理缓存这一项——日志是拿来查问题的，
     * 问题查完就没必要一直占着手机空间，给个能主动清掉的地方。
     * 清缓存只删本机记录的日志，投屏本身不受任何影响。
     */
    private void showSettings() {
        String size;
        try {
            long len = LogHub.logFileSize(this);
            size = len < 1024 ? len + " 字节"
                    : (len / 1024) + " KB";
        } catch (Throwable e) {
            size = "未知";
        }

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setMessage("本机的投屏日志目前占 " + size + "。\n\n"
                        + "清理缓存会删掉这些日志（只在手机本地，不影响投屏），"
                        + "一般查完问题就可以清。\n\n"
                        + "「自启动管理」不是必须的：现在大部分机器上，"
                        + "电池白名单加悬浮窗已经够用。只有投着投着就被杀，才需要去开它。")
                .setPositiveButton("清理缓存", (d, w) -> {
                    LogHub.clear();
                    Toast.makeText(this, "日志已清理", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("自启动管理", (d, w) -> {
                    if (openAutoStartPage()) {
                        Toast.makeText(this, "在本应用那一行打开自启动", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "这台机器没有自启动页，去手机管家找", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 自动申请后台权限的这两步，回来就接着走下一步
        if (requestCode == REQ_BATTERY || requestCode == REQ_OVERLAY) {
            nextGrantStep();
            return;
        }

        if (requestCode != REQ_CAPTURE) return;

        if (resultCode != RESULT_OK || data == null) {
            hint.setText("没有授予录屏权限，无法投屏");
            return;
        }

        Intent svc = new Intent(this, MirrorService.class);
        svc.setAction(MirrorService.ACTION_START);
        svc.putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(MirrorService.EXTRA_RESULT_DATA, data);
        svc.putExtra(MirrorService.EXTRA_HOST, pendingHost);
        // 关键：把界面上这个音量值一起带给服务，否则服务用它自己的默认 15%，
        // 用户看到的是 1%、电视实际按 15% 播，只有动一下滑块才对得上。
        svc.putExtra(MirrorService.EXTRA_GAIN, getSavedGain());
        startForegroundService(svc);
        hint.setText("正在连接 " + pendingHost + " …");
    }
}
