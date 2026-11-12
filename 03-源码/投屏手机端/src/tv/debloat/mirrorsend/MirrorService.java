package tv.debloat.mirrorsend;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 屏幕采集 + H.264 编码 + 网线那头扔给电视。
 * Android 14 起录屏必须挂在 mediaProjection 类型的前台服务上，所以全都放这里。
 */
public class MirrorService extends Service {

    private static final int NOTI_ID = 1001;
    private static final int PORT = 9000;

    public static final String ACTION_START = "tv.debloat.mirrorsend.START";
    public static final String ACTION_STOP = "tv.debloat.mirrorsend.STOP";
    /** 转屏时拿到的新授权，只重建采集链路，socket 不动，所以电视那头不会断。 */
    public static final String ACTION_REBUILD = "tv.debloat.mirrorsend.REBUILD";
    /** 调投屏音量：只改电视端那一路 PCM 的增益，不碰电视的系统音量。 */
    public static final String ACTION_GAIN = "tv.debloat.mirrorsend.GAIN";
    public static final String EXTRA_GAIN = "gain";
    public static final String EXTRA_RESULT_CODE = "rc";
    public static final String EXTRA_RESULT_DATA = "rd";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_REJECTED = "rejected";

    public static final String ACTION_STATUS = "tv.debloat.mirrorsend.STATUS";

    private MediaProjection projection;
    private MediaCodec encoder;
    private Surface inputSurface;
    private VirtualDisplay display;
    private Socket socket;

    private WindowManager windowManager;
    private View overlayView;
    private PowerManager.WakeLock wakeLock;

    private final Object writeLock = new Object();
    private DataOutputStream outStream;
    private Thread audioThread;
    private volatile boolean audioRunning;

    private volatile boolean running = false;

    /** 当前在跑的那条发送线程。再点一次开始时要先把它请走，绝不能两条并存。 */
    private volatile Thread sendThread = null;
    private volatile String lastError = "";

    /** 转屏流程用：等新授权的期间先把画面暂停，别让循环空转。 */
    private volatile int pendingRc = 0;
    private volatile Intent pendingData = null;
    private volatile boolean rebuildPending = false;
    private volatile boolean rotateWaiting = false;
    private volatile String tvHost = "";

    /** 手机端设定的投屏音量百分比，重连带新画面参数时补发一次。 */
    private volatile int gainPct = 15;

    /**
     * 正在拆旧会话换新会话。
     *
     * 拆旧会话必须调 projection.stop()，但这一调系统就会回调旧会话的 onStop()，
     * 而那个回调里原本写死 running=false —— 结果是授权拿到了、新会话也建好了，
     * 主循环却在重建途中被自己停掉，表现就是"点了允许还是投不上"。
     * 重建期间用这个标志把 onStop 的"停机"副作用挡掉。
     */
    private volatile boolean rebuilding = false;

    /**
     * 会话代号。每次新拿一个 MediaProjection 就 +1。
     *
     * 为什么要这个：拆旧会话时调 projection.stop() 会触发旧会话的 onStop，
     * 而那个回调是 post 到主线程上的，什么时候真正执行完全不由我们决定——
     * 可能比重建收尾还晚。单靠 rebuilding 一个布尔挡不住它（重建一收尾
     * 标志就复位了，回调此时才跑，照样把主循环停掉）。
     * 所以回调里带上自己那一代的号，对不上就直接忽略。
     */
    private volatile int projGen = 0;

    /** 悬浮窗是否已经挂上。重复 add 会抛 "has already been added"，用这个挡一下。 */
    private volatile boolean overlayAdded = false;

    @Override
    public void onCreate() {
        super.onCreate();
        // 先把落盘日志接上，这样后面崩了也留得下现场
        LogHub.init(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running = false;
            dropNotification();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            return START_NOT_STICKY;
        }

        // 音量滑块：只发一条指令过去，别的什么都不动
        if (ACTION_GAIN.equals(intent.getAction())) {
            // 没在投屏时只是把值记下来，别为这单独挂一条通知
            if (!running) return START_STICKY;
            startForeground(NOTI_ID, buildNotification("正在投屏"));
            int pct = intent.getIntExtra(EXTRA_GAIN, 15);
            // 下限放到 0：0 就是彻底静音，用户想关声音时不用去动电视的音量
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;
            gainPct = pct;
            sendGain(pct);
            LogHub.add("投屏音量设为 " + pct + "%");
            return START_STICKY;
        }

        if (ACTION_REBUILD.equals(intent.getAction())) {
            startForeground(NOTI_ID, buildNotification("正在投屏"));
            if (intent.getBooleanExtra(EXTRA_REJECTED, false)) {
                rotateWaiting = false;
                LogHub.add("用户没给转屏授权，恢复原方向继续投");
                return START_STICKY;
            }
            int rc2 = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data2 = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (data2 != null) {
                pendingRc = rc2;
                pendingData = data2;
                rebuildPending = true;
                LogHub.add("收到转屏新授权，准备重建采集");
            }
            return START_STICKY;
        }

        holdOverlay();
        holdWakeLock();

        int rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        String host = intent.getStringExtra(EXTRA_HOST);
        // 界面上的音量要跟着这次投屏一起带过来。
        // 不带的话服务里只有字段默认值，用户看到的 1% 跟电视实际做的增益就对不上，
        // 表现就是"明明设了 1%，声音还是原来那么大，动一下滑块才生效"。
        gainPct = intent.getIntExtra(EXTRA_GAIN, gainPct);
        if (gainPct < 0) gainPct = 0;
        if (gainPct > 100) gainPct = 100;

        if (data != null && host != null && host.length() > 0) {
            // 连着点两次「开始」，或者上一次还没退干净就又来一发，
            // 两条发送线程会抢同一个编码器，直接抛
            // "Invalid to call while another dequeue output request is pending" 后双双退出。
            // 所以先把上一轮彻底请走，再起新的。
            Thread prev = sendThread;
            if (prev != null && prev.isAlive()) {
                LogHub.add("上一个投屏会话还没退，先停掉它再重连");
                running = false;
                try { prev.join(1500); } catch (Throwable ignored) {}
                if (prev.isAlive()) {
                    LogHub.add("上一轮 1.5 秒没退干净，强行继续（可能丢一次画面）");
                }
            }

            // 系统要求前台服务启动后 5 秒内必须挂出通知，
            // 所以连接阶段先显示"正在连接"，画面真正上墙后再改成"正在投屏"。
            startForeground(NOTI_ID, buildNotification("正在连接 " + host + " …"));
            running = true;
            tvHost = host;
            final int myStartId = startId;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    runMirror(rc, data, host, myStartId);
                }
            }, "mirror-send");
            sendThread = t;
            t.start();
        } else {
            // 参数不全，别留一条空通知在那儿
            dropNotification();
            stopSelf();
        }
        return START_STICKY;
    }

    /**
     * 抓手机自己放出来的声音（不是麦克风），编成 AAC 跟着画面一起送。
     * 只有 Android 10 以上才有这个"内录"能力。
     */
    private void runAudio(MediaProjection proj) {
        AudioRecord rec = null;
        MediaCodec enc = null;
        try {
            if (Build.VERSION.SDK_INT < 29) {
                LogHub.add("系统版本太低，抓不了内录声音");
                return;
            }
            AudioPlaybackCaptureConfiguration cfg =
                    new AudioPlaybackCaptureConfiguration.Builder(proj)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioFormat af = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int minBuf = AudioRecord.getMinBufferSize(44100,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(af)
                    .setBufferSizeInBytes(Math.max(minBuf * 2, 8192))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();

            MediaFormat ef = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            rec.startRecording();
            LogHub.add("声音采集已启动");

            if (rec.getState() != AudioRecord.STATE_INITIALIZED
                    || rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                // 重建时前一条音轨刚被拆掉，系统的录音通道可能还没腾出来，
                // 这时 startRecording 会无声地失败，再去 read 就抛异常。
                // 先退出去，让主循环下一轮再建，别把整个投屏带崩。
                LogHub.add("声音通道还没就绪，这一轮先不抓声音");
                try { rec.release(); } catch (Throwable ignored) {}
                try { enc.stop(); } catch (Throwable ignored) {}
                try { enc.release(); } catch (Throwable ignored) {}
                return;
            }

            byte[] pcm = new byte[2048];
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (audioRunning && running) {
                int n = rec.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                int idx = enc.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    ByteBuffer ib = enc.getInputBuffer(idx);
                    if (ib != null) {
                        ib.clear();
                        ib.put(pcm, 0, n);
                        enc.queueInputBuffer(idx, 0, n, System.nanoTime() / 1000, 0);
                    }
                }
                while (true) {
                    int oi = enc.dequeueOutputBuffer(bi, 0);
                    if (oi >= 0) {
                        ByteBuffer ob = enc.getOutputBuffer(oi);
                        if (ob != null && bi.size > 0) {
                            ob.position(bi.offset);
                            ob.limit(bi.offset + bi.size);
                            byte[] data = new byte[bi.size];
                            ob.get(data);
                            synchronized (writeLock) {
                                if (outStream == null) break;
                                boolean cfgBuf =
                                        (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                                outStream.writeInt(cfgBuf ? -2 : -3);
                                outStream.writeInt(data.length);
                                outStream.write(data);
                                outStream.flush();
                            }
                        }
                        enc.releaseOutputBuffer(oi, false);
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        LogHub.add("音频输出格式: " + enc.getOutputFormat());
                    } else {
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            LogHub.add("声音中断: " + e);
        } finally {
            audioRunning = false;
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception ignored) {}
            LogHub.add("声音线程退出");
        }
    }

    /** 给界面报个状态，让手机上能看到"正在投屏"。 */
    private void broadcast(String state, String detail) {
        try {
            Intent i = new Intent(ACTION_STATUS);
            i.setPackage(getPackageName());
            i.putExtra("state", state);
            i.putExtra("detail", detail);
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    /**
     * 把音量百分比发给电视端（协议里的 -4 指令）。
     * 电视端只把它换算成本路 PCM 的增益，电视自己的系统音量不动，
     * 所以投屏完换回别的应用时不用再把音量调回去。
     */
    private void sendGain(int pct) {
        try {
            synchronized (writeLock) {
                if (outStream == null) return;
                outStream.writeInt(-4);
                outStream.writeInt(pct);
                outStream.flush();
            }
        } catch (Exception ignored) {}
    }

    /** 把一句话递给电视端显示，用于把失败原因说清楚，而不是让电视干卡着。 */
    private void tellTv(String msg) {
        try {
            byte[] mb = msg.getBytes("UTF-8");
            synchronized (writeLock) {
                if (outStream == null) return;
                outStream.writeInt(-5);
                outStream.writeInt(mb.length);
                outStream.write(mb);
                outStream.flush();
            }
        } catch (Exception ignored) {}
    }

    /**
     * 转屏时请系统再给一次授权。安卓 14 不允许复用旧会话开第二个虚拟屏，
     * 所以只能这么办：旧会话作废，重新申请一次，用户点一下允许。
     */
    private void requestReauth() {
        if (rotateWaiting) return;
        rotateWaiting = true;
        try {
            Intent i = new Intent(this, ReauthActivity.class);
            // 独立任务栈（Manifest 里给它单独设了 taskAffinity），所以授权页弹完关掉
            // 会回到用户手头那个应用，不会把本 App 的主界面拉到前台。
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
            LogHub.add("已拉起重授权页");
        } catch (Throwable e) {
            rotateWaiting = false;
            LogHub.add("拉不起授权页（多半是没有悬浮窗权限，系统不许后台起界面）: " + e);
            broadcast("rotate", String.valueOf(e));
        }
    }

    /**
     * 用刚拿到的授权重建整条采集链路。socket 不动，所以电视那头画面只是停一瞬，
     * 不会像以前那样整条断掉重连。
     *
     * @return 新尺寸 {w,h}；还没等到授权就返回 null。
     */
    private int[] rebuildWithPending(WindowManager wm, DisplayMetrics dm) throws Exception {
        Intent data = pendingData;
        int rc = pendingRc;
        if (data == null) return null;

        // 1. 先把声音收掉，AudioRecord 是绑在旧会话上的
        audioRunning = false;
        Thread at = audioThread;
        if (at != null) {
            try { at.join(800); } catch (Exception ignored) {}
        }
        audioThread = null;

        // 2. 拆掉旧的采集链路（不碰 socket / outStream）
        //    这一段会触发旧会话的 onStop，先立好重建标记，免得它把主循环停掉
        rebuilding = true;
        // 代号要在拆之前就换代。放到建新会话时再 +1 就晚了：
        // 旧会话那个回调可能在"已经拆完、新会话还没建"的空档里执行，
        // 那时代号还没变，它就被当成当前会话，照样把主循环停掉。
        projGen++;
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        display = null;
        try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
        try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
        encoder = null;
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
        inputSurface = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        rebuilding = false;

        // 3. 用新授权起新会话。这里的会话就是刚换好的那一代，不用再加号。
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection proj = mpm.getMediaProjection(rc, data);
        if (proj == null) {
            throw new IllegalStateException("新授权拿不到录屏会话");
        }
        projection = proj;
        final int myGen = projGen;
        proj.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // 换代之后旧会话的回调迟到了，直接忽略，别再动主循环
                if (myGen != projGen) return;
                LogHub.add("系统收回了录屏权限（重建后）");
                if (!rebuilding) running = false;
            }
        }, new Handler(Looper.getMainLooper()));

        // 4. 按新方向建编码器 + 虚拟屏
        int[] size = calcSize(wm);
        int nw = size[0];
        int nh = size[1];
        MediaCodec enc = buildEncoder(nw, nh);
        Surface surf = enc.createInputSurface();
        enc.start();
        encoder = enc;
        inputSurface = surf;
        VirtualDisplay vd = proj.createVirtualDisplay("mirror", nw, nh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null);
        display = vd;

        // 5. 声音跟着新会话重新开
        final MediaProjection fproj = proj;
        audioRunning = true;
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runAudio(fproj);
            }
        }, "mirror-audio");
        audioThread.start();

        rebuildPending = false;
        pendingData = null;
        rotateWaiting = false;
        return new int[]{nw, nh};
    }

    /** 按当前屏幕方向算采集尺寸，长边压到 1280，16 对齐。 */
    private int[] calcSize(WindowManager wm) {
        Display d = wm.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        d.getRealMetrics(dm);
        // getRealMetrics 报的是物理尺寸，转不转屏都一样，所以方向得单独看
        int pw = Math.min(dm.widthPixels, dm.heightPixels);
        int ph = Math.max(dm.widthPixels, dm.heightPixels);
        int rot = d.getRotation();
        boolean landscape = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
        int w;
        int h;
        if (landscape) {
            w = 1280;
            h = (int) (pw * 1280.0 / ph);
        } else {
            h = 1280;
            w = (int) (pw * 1280.0 / ph);
        }
        w = (w / 16) * 16;
        h = (h / 16) * 16;
        if (w < 16) w = 16;
        if (h < 16) h = 16;
        return new int[]{w, h};
    }

    private MediaCodec buildEncoder(int w, int h) throws Exception {
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 5000000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        // 电视那头是 4.3 的老解码器，用 Baseline 兼容性最好
        try {
            fmt.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            fmt.setInteger(MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        } catch (Exception ignored) {}
        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return enc;
    }

    private void runMirror(int resultCode, Intent data, String host, int startId) {
        DataOutputStream out = null;
        try {
            LogHub.add("连接电视 " + host + ":" + PORT);
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, PORT), 6000);
            s.setTcpNoDelay(true);
            socket = s;
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
            LogHub.add("已连接电视");

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);

            int[] size = calcSize(wm);
            int w = size[0];
            int h = size[1];
            LogHub.add("采集尺寸 " + w + "x" + h + " (屏幕 " + dm.widthPixels + "x" + dm.heightPixels + ")");

            MediaCodec enc = buildEncoder(w, h);
            Surface surf = enc.createInputSurface();
            enc.start();
            encoder = enc;
            inputSurface = surf;
            outStream = out;
            LogHub.add("编码器就绪");

            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection proj = mpm.getMediaProjection(resultCode, data);
            if (proj == null) {
                throw new IllegalStateException("拿不到录屏会话");
            }
            projection = proj;
            final int myGen = ++projGen;
            proj.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (myGen != projGen) return;
                    LogHub.add("系统收回了录屏权限");
                    if (!rebuilding) running = false;
                }
            }, new Handler(Looper.getMainLooper()));

            VirtualDisplay vd = proj.createVirtualDisplay("mirror", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null);
            display = vd;
            LogHub.add("开始传画面");
            broadcast("playing", w + "x" + h);

            // 音轨单独一条线程，掺着画面一起往同一个 socket 里送
            final MediaProjection fproj = proj;
            audioRunning = true;
            audioThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runAudio(fproj);
                }
            }, "mirror-audio");
            audioThread.start();

            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean sentCsd = false;
            boolean reconfigured = false;
            int sentFrames = 0;
            long lastBeat = System.currentTimeMillis();
            int lastW = w;
            int lastH = h;
            while (running) {
                // 拿到转屏新授权了：按新方向重建采集链路，socket 不动，电视那头不会断
                if (rebuildPending) {
                    int[] nb = rebuildWithPending(wm, dm);
                    if (nb != null) {
                        lastW = nb[0];
                        lastH = nb[1];
                        sentCsd = false;
                        reconfigured = true;
                        broadcast("playing", nb[0] + "x" + nb[1]);
                        LogHub.add("已按新方向重建 " + nb[0] + "x" + nb[1]);
                        continue;
                    }
                }

                // 方向变了：不能硬改旧会话（安卓 14 会直接拒），改成重新申请一次授权
                int[] nowSize = calcSize(wm);
                if (nowSize[0] != lastW || nowSize[1] != lastH) {
                    if (!rotateWaiting && !rebuildPending) {
                        LogHub.add("方向变了 " + lastW + "x" + lastH
                                + " -> " + nowSize[0] + "x" + nowSize[1] + "，重新申请授权");
                        broadcast("rotate", nowSize[0] + "x" + nowSize[1]);
                        requestReauth();
                    }
                    // 等用户点授权的那几秒别空转
                    try { Thread.sleep(300); } catch (Exception ignored) {}
                    continue;
                }

                MediaCodec cur = encoder;
                if (cur == null) {
                    try { Thread.sleep(50); } catch (Exception ignored) {}
                    continue;
                }

                int idx = cur.dequeueOutputBuffer(bi, 10000);
                if (idx >= 0) {
                    ByteBuffer bb = cur.getOutputBuffer(idx);
                    if (bb != null && bi.size > 0) {
                        bb.position(bi.offset);
                        bb.limit(bi.offset + bi.size);
                        byte[] chunk = new byte[bi.size];
                        bb.get(chunk);

                        if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            synchronized (writeLock) {
                                if (reconfigured) {
                                    outStream.writeInt(-1);
                                } else {
                                    outStream.write("MRR1".getBytes(StandardCharsets.US_ASCII));
                                }
                                outStream.writeInt(chunk.length);
                                outStream.write(chunk);
                                outStream.writeInt(lastW);
                                outStream.writeInt(lastH);
                                outStream.flush();
                            }
                            sentCsd = true;
                            LogHub.add(reconfigured ? "已发送新画面参数" : "已发送画面参数");
                            // 画面真正开工了，通知改成"正在投屏"并带上尺寸
                            updateNotification("正在投屏 " + lastW + "x" + lastH
                                    + " · 音量 " + gainPct + "%");
                            // 握手完成后把当前音量补发一次，保证重连后还是手机上调好的那个值
                            sendGain(gainPct);
                        } else if (sentCsd) {
                            synchronized (writeLock) {
                                outStream.writeInt(chunk.length);
                                outStream.write(chunk);
                                // 局域网带宽够，立刻推出去，别攒着，攒帧就是延迟
                                outStream.flush();
                            }
                            sentFrames++;
                            long now = System.currentTimeMillis();
                            if (now - lastBeat > 3000) {
                                LogHub.add("已发 " + sentFrames + " 帧，最近一帧 " + chunk.length + " 字节");
                                lastBeat = now;
                            }
                        }
                    }
                    cur.releaseOutputBuffer(idx, false);
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    LogHub.add("输出格式: " + cur.getOutputFormat());
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 正常，继续等
                }
            }
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            StringBuilder sb = new StringBuilder();
            sb.append("投屏中断: ").append(e).append('\n');
            for (StackTraceElement el : e.getStackTrace()) {
                sb.append("    at ").append(el).append('\n');
            }
            LogHub.add(sb.toString());
            broadcast("error", String.valueOf(e));
        } finally {
            audioRunning = false;
            running = false;
            broadcast("stopped", "");
            LogHub.add("发送线程退出");
            if (out != null) {
                try { out.flush(); } catch (Exception ignored) {}
                try { out.close(); } catch (Exception ignored) {}
            }
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            release();
            // 投屏断了就把常驻通知撤掉，通知栏里不留一条假装还在投的。
            // stopSelf 带上自己的 startId：万一这中间用户又点了开始，
            // 更新的那一次还没结束，就只停自己，别把新的会话一起误杀。
            dropNotification();
            stopSelf(startId);
        }
    }

    /**
     * 挂一个 1x1 的透明悬浮窗。国产 ROM 对"屏幕上有窗"的进程下手会犹豫，
     * 这是让投屏活过切后台这一步的关键一招。
     */
    private void holdOverlay() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                LogHub.add("没有悬浮窗权限，保活会弱一档");
                return;
            }
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (overlayView == null) {
                overlayView = new View(this);
            }
            if (overlayAdded) return;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = 0;
            lp.y = 0;
            windowManager.addView(overlayView, lp);
            overlayAdded = true;
            LogHub.add("悬浮窗保活已挂上");
        } catch (Exception e) {
            LogHub.add("悬浮窗挂不上: " + e);
        }
    }

    private void holdWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mirror:keep");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            LogHub.add("唤醒锁已持有");
        } catch (Exception e) {
            LogHub.add("唤醒锁失败: " + e);
        }
    }

    private void releaseOverlay() {
        try {
            if (windowManager != null && overlayView != null) {
                windowManager.removeView(overlayView);
                LogHub.add("悬浮窗已撤");
            }
        } catch (Exception ignored) {}
        overlayView = null;
        overlayAdded = false;
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void release() {
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        display = null;
        try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
        try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
        encoder = null;
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
        inputSurface = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    "mirror", "投屏", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, "mirror");
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("投屏到电视")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true);
        // 点通知回到 App，想停的时候能直接点进去停
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            b.setContentIntent(pi);
        } catch (Throwable ignored) {}
        return b.build();
    }

    /** 投屏真的开始了才更新成这个，告诉用户现在投的是什么尺寸。 */
    private void updateNotification(String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNotification(text));
        } catch (Throwable ignored) {}
    }

    /** 投屏停了 / 断了就把常驻通知撤掉，通知栏里不留尾巴。 */
    private void dropNotification() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        LogHub.add("被从最近任务里划掉了，尝试重新挂上");
        releaseOverlay();
        releaseWakeLock();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        LogHub.add("服务被销毁");
        running = false;
        releaseOverlay();
        releaseWakeLock();
        release();
        dropNotification();
        super.onDestroy();
    }
}
