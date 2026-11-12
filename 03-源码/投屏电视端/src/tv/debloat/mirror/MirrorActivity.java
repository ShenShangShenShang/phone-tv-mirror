package tv.debloat.mirror;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * 电视端屏幕镜像接收器。
 * 手机端连上来后先发一段配置（CSD 里的 SPS/PPS + 分辨率），
 * 之后每一帧是 4 字节长度 + H.264 数据，这里用系统硬解直接画到 Surface 上。
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "TVMIRROR";
    private static final int PORT = 9000;

    private SurfaceView surfaceView;
    private TextView statusView;
    private TextView probeView;
    private final Handler ui = new Handler();

    private volatile Surface surface;
    private final Object surfLock = new Object();
    private int surfSerial = 0;
    private volatile boolean running = true;
    private volatile String stateText = "启动中";
    private volatile int frameCount = 0;
    private volatile String clientAddr = "-";

    private MediaCodec audioCodec;
    private AudioTrack audioTrack;
    private ByteBuffer[] audioInBufs;
    private ByteBuffer[] audioOutBufs;

    /**
     * 播放增益。投屏的声音常常比电视本身的节目大得多。
     * 注意响度是对数的：0.3 只等于 -10dB，人耳听着只是"小一点"，远没到"小三倍"。
     * 要主观上明显变小，得压到 0.15 上下（约 -16dB），所以默认取这个值。
     * 只管我们这一路 PCM，电视的系统音量一个字节都不动，
     * 不投屏时听别的节目不用再调回去。
     * 遥控器上键 / 下键实时加减，每档 0.05，范围 0 ~ 1.0（调到 0 就是静音）。
     */
    private volatile float audioGain = 0.15f;

    /** 收到并播出去的音频帧数。为 0 就说明手机端根本没送来声音，不是电视这边的问题。 */
    private volatile int audioFrames = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFEDEFF3);
        statusView.setTextSize(30);
        statusView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 50;
        root.addView(statusView, lp);

        probeView = new TextView(this);
        probeView.setTextSize(1);
        probeView.setTextColor(0x00000000);
        root.addView(probeView, new FrameLayout.LayoutParams(1, 1));

        setContentView(root);
        surfaceView.getHolder().addCallback(this);

        ensureService();
        takePending();
        ui.post(refreshTask);
    }

        @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 遥控器上下键实时调投屏音量。只影响本 App 这条 PCM 流，
        // 电视的系统音量不动，所以不投屏时该怎么听还怎么听。
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            audioGain = Math.min(1.0f, Math.round((audioGain + 0.05f) * 100) / 100f);
            showGain();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // 下限 0：调到 0 就是彻底静音，遥控器上也能直接关掉投屏声音
            audioGain = Math.max(0f, Math.round((audioGain - 0.05f) * 100) / 100f);
            showGain();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showGain() {
        try {
            Toast.makeText(this, "投屏音量 " + Math.round(audioGain * 100) + "%",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (statusView != null) statusView.setText(stateText);
            if (probeView != null) {
                probeView.setText("MIRROR|" + stateText + "|F:" + frameCount + "|C:" + clientAddr);
            }
            ui.postDelayed(this, 800);
        }
    };

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        bumpSurface();
        Log.i(TAG, "显示面就绪");
        startWhenSurfaceReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surface = holder.getSurface();
        bumpSurface();
        Log.i(TAG, "显示面尺寸变化 " + width + "x" + height);
        startWhenSurfaceReady();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
    }

    /** 已经被服务接进来、正等着显示的连接。 */
    private volatile Socket pendingSocket;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask 下再次被拉起会走这里：服务刚 accept 到新连接
        takePending();
    }

    /** 保证常驻服务在跑（用户自己点图标打开时，服务可能还没起来）。 */
    private void ensureService() {
        try {
            startService(new Intent(this, MirrorService.class));
        } catch (Throwable e) {
            Log.e(TAG, "启动常驻服务失败: " + e);
        }
    }

    /** 把服务递过来的连接取走；没有就显示等待。 */
    private void takePending() {
        Socket s = MirrorService.pending;
        MirrorService.pending = null;
        if (s == null) {
            stateText = "等待手机连接（端口 " + PORT + "）";
            return;
        }
        if (pendingSocket != null) {
            try { pendingSocket.close(); } catch (Throwable ignored) {}
        }
        try { clientAddr = s.getInetAddress().getHostAddress(); } catch (Throwable ignored) {}
        pendingSocket = s;
        running = true;
        stateText = "手机已接入，准备画面…";
        startWhenSurfaceReady();
    }

    /** 显示面就绪后，才真正开始握手解码。 */
    private void startWhenSurfaceReady() {
        Socket s = pendingSocket;
        if (s == null) return;
        Surface sf = surface;
        if (sf == null || !sf.isValid()) return;   // 等 surfaceCreated/Changed 再叫
        pendingSocket = null;
        stateText = "手机已连接";
        frameCount = 0;
        audioFrames = 0;
        new Thread(new Runnable() {
            @Override
            public void run() { handle(s); }
        }, "session").start();
    }

    private void bumpSurface() {
        synchronized (surfLock) {
            surfSerial++;
            surfLock.notifyAll();
        }
    }

    /**
     * SurfaceView 一改尺寸就会销毁旧 Surface 重建一个新的，旧的那块拿来挂解码器就是黑屏。
     * 所以改完尺寸得等回调把新 Surface 交上来，等不到（尺寸没真的变）就退回原来那块。
     */
    private Surface awaitFreshSurface(long millis) {
        int before;
        synchronized (surfLock) {
            before = surfSerial;
        }
        long end = System.currentTimeMillis() + millis;
        synchronized (surfLock) {
            while (surfSerial == before) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    surfLock.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        Surface now = surface;
        if (now == null || !now.isValid()) {
            Log.e(TAG, "等不到新的显示面");
            return null;
        }
        return now;
    }

    private void setupAudio(byte[] csd) {
        try {
            tearDownAudio();
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            if (csd != null && csd.length > 0) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            }
            audioCodec = MediaCodec.createDecoderByType("audio/mp4a-latm");
            audioCodec.configure(fmt, null, null, 0);
            audioCodec.start();
            audioInBufs = audioCodec.getInputBuffers();
            audioOutBufs = audioCodec.getOutputBuffers();

            int bufSize = AudioTrack.getMinBufferSize(44100,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufSize <= 0) bufSize = 44100;
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 44100,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 2, AudioTrack.MODE_STREAM);
            audioTrack.play();
        } catch (Exception e) {
            Log.e(TAG, "音频初始化失败: " + e);
            tearDownAudio();
        }
    }

    private void feedAudio(byte[] data) {
        if (audioCodec == null) return;
        try {
            int idx = audioCodec.dequeueInputBuffer(10000);
            if (idx >= 0) {
                ByteBuffer ib = audioInBufs[idx];
                ib.clear();
                ib.put(data);
                audioCodec.queueInputBuffer(idx, 0, data.length, System.nanoTime() / 1000, 0);
            }
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int guard = 0;
            int oi = audioCodec.dequeueOutputBuffer(bi, 0);
            while (guard++ < 8) {
                if (oi >= 0) {
                    ByteBuffer ob = audioOutBufs[oi];
                    if (ob != null && bi.size > 0 && audioTrack != null) {
                        byte[] pcm = new byte[bi.size];
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        ob.get(pcm);
                        // 4.3 的 AudioTrack 只认 short[]，还得自己拼
                        short[] s = new short[pcm.length / 2];
                        float g = audioGain;
                        for (int i = 0; i < s.length; i++) {
                            // 两个字节都得 & 0xFF，否则高位是 1 时会做符号扩展，声音会失真
                            short raw = (short) ((pcm[2 * i] & 0xFF) | ((pcm[2 * i + 1] & 0xFF) << 8));
                            int v = (int) (raw * g);
                            if (v > 32767) v = 32767;
                            else if (v < -32768) v = -32768;
                            s[i] = (short) v;
                        }
                        audioTrack.write(s, 0, s.length);
                        audioFrames++;
                        if (audioFrames == 1 || audioFrames % 200 == 0) {
                            Log.i(TAG, "音频帧 " + audioFrames + "，增益 "
                                    + Math.round(audioGain * 100) + "%");
                        }
                    }
                    audioCodec.releaseOutputBuffer(oi, false);
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "音频输出格式: " + audioCodec.getOutputFormat());
                } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    audioOutBufs = audioCodec.getOutputBuffers();
                } else {
                    break;
                }
                oi = audioCodec.dequeueOutputBuffer(bi, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "音频处理失败: " + e);
        }
    }

    private void tearDownAudio() {
        try {
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
        } catch (Exception ignored) {}
        audioTrack = null;
        try {
            if (audioCodec != null) {
                audioCodec.stop();
                audioCodec.release();
            }
        } catch (Exception ignored) {}
        audioCodec = null;
    }

    /** 按画面比例把显示区摆成合适大小并居中，省下的区域留黑。 */
    private void adjustSurface(final int vw, final int vh) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                int sw = getWindow().getDecorView().getWidth();
                int sh = getWindow().getDecorView().getHeight();
                if (sw <= 0 || sh <= 0 || vw <= 0 || vh <= 0) return;
                float videoRatio = (float) vw / (float) vh;
                float screenRatio = (float) sw / (float) sh;
                int nw;
                int nh;
                if (videoRatio > screenRatio) {
                    nw = sw;
                    nh = (int) (sw / videoRatio);
                } else {
                    nh = sh;
                    nw = (int) (sh * videoRatio);
                }
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(nw, nh);
                lp.gravity = Gravity.CENTER;
                surfaceView.setLayoutParams(lp);
                Log.i(TAG, "显示区 " + nw + "x" + nh + "（画面 " + vw + "x" + vh
                        + "，屏幕 " + sw + "x" + sh + "）");
            }
        });
    }

        private void handle(Socket s) {
        MediaCodec codec = null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
            // 关键：一定要有读超时。否则手机那头断网/异常退出时，这里会永远阻塞在
            // readInt() 上，accept 循环再也回不去，端口看似在听、实际没人接，
            // 表现就是"手机一直在正在连接…"，只能拔电源。15 秒没帧就当断了。
            s.setSoTimeout(15000);

            byte[] magic = new byte[4];
            in.readFully(magic);
            String m = new String(magic, "US-ASCII");
            if (!"MRR1".equals(m)) {
                Log.e(TAG, "未知握手: " + m);
                return;
            }

            int csdLen = in.readInt();
            if (csdLen <= 0 || csdLen > 300000) {
                Log.e(TAG, "CSD 长度异常: " + csdLen);
                return;
            }
            byte[] csd = new byte[csdLen];
            in.readFully(csd);

            int w = in.readInt();
            int h = in.readInt();
            Log.i(TAG, "画面参数 " + w + "x" + h + "，CSD " + csdLen + " 字节");
            // 先把显示区摆成画面的比例，view 尺寸一变底层 Surface 就会重做，得等新的到位
            adjustSurface(w, h);
            Surface first = awaitFreshSurface(2500);
            if (first != null) {
                surface = first;
            }
            if (surface == null) {
                Log.e(TAG, "显示面还没准备好");
                stateText = "显示面未就绪";
                return;
            }

            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd));

            codec = MediaCodec.createDecoderByType("video/avc");
            codec.configure(fmt, surface, null, 0);
            codec.start();
            Log.i(TAG, "解码器已启动");
            stateText = "正在镜像 " + w + "x" + h;
            // 画面已经上屏了，就别再压一行字在顶上了
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (statusView != null) statusView.setVisibility(View.GONE);
                }
            });

            ByteBuffer[] inBufs = codec.getInputBuffers();
            ByteBuffer[] outBufs = codec.getOutputBuffers();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

            while (running) {
                int len = in.readInt();
                if (len == -2 || len == -3) {
                    if (len == -2) {
                        int acsdLen = in.readInt();
                        byte[] acsd = new byte[acsdLen];
                        in.readFully(acsd);
                        setupAudio(acsd);
                        Log.i(TAG, "音频解码器已启动，配置 " + acsdLen + " 字节");
                    } else {
                        int alen = in.readInt();
                        byte[] adata = new byte[alen];
                        in.readFully(adata);
                        feedAudio(adata);
                    }
                    continue;
                }
                if (len == -5) {
                    // 手机那头主动报的失败原因，直接摆到屏幕上
                    int mlen = in.readInt();
                    if (mlen <= 0 || mlen > 100000) break;
                    byte[] md = new byte[mlen];
                    in.readFully(md);
                    final String msg = new String(md, "UTF-8");
                    Log.e(TAG, "手机报告: " + msg);
                    stateText = msg;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (statusView != null) {
                                statusView.setVisibility(View.VISIBLE);
                                statusView.setText(msg);
                            }
                        }
                    });
                    break;
                }
                if (len == -4) {
                    // 手机端拖音量滑块：后面跟一个百分比，直接换算成增益
                    int pct = in.readInt();
                    // 下限 0：0 就是彻底静音，手机端把滑块拉到底就等于关掉投屏声音
                    if (pct < 0) pct = 0;
                    if (pct > 100) pct = 100;
                    audioGain = pct / 100f;
                    Log.i(TAG, "手机设定音量 " + pct + "%");
                    continue;
                }
                if (len == -1) {
                    // 手机那头换方向了，重新给一套参数
                    int ncsdLen = in.readInt();
                    if (ncsdLen <= 0 || ncsdLen > 300000) {
                        Log.e(TAG, "重配 CSD 长度异常: " + ncsdLen);
                        break;
                    }
                    byte[] ncsd = new byte[ncsdLen];
                    in.readFully(ncsd);
                    int nw = in.readInt();
                    int nh = in.readInt();
                    Log.i(TAG, "换方向，重配 " + nw + "x" + nh);
                    try { codec.stop(); } catch (Exception ignored) {}
                    try { codec.release(); } catch (Exception ignored) {}
                    adjustSurface(nw, nh);
                    Surface cur = awaitFreshSurface(2500);
                    if (cur == null) {
                        Log.e(TAG, "换方向后没有可用的显示面，放弃重配");
                        break;
                    }
                    MediaFormat nf = MediaFormat.createVideoFormat("video/avc", nw, nh);
                    nf.setByteBuffer("csd-0", ByteBuffer.wrap(ncsd));
                    codec = MediaCodec.createDecoderByType("video/avc");
                    codec.configure(nf, cur, null, 0);
                    codec.start();
                    inBufs = codec.getInputBuffers();
                    outBufs = codec.getOutputBuffers();
                    stateText = "正在镜像 " + nw + "x" + nh;
                    continue;
                }
                if (len < 0) break;
                if (len > 4000000) {
                    Log.e(TAG, "帧过大: " + len);
                    break;
                }
                byte[] data = new byte[len];
                in.readFully(data);

                int idx = codec.dequeueInputBuffer(30000);
                if (idx >= 0) {
                    ByteBuffer ib = inBufs[idx];
                    ib.clear();
                    ib.put(data);
                    codec.queueInputBuffer(idx, 0, data.length, System.nanoTime() / 1000, 0);
                }

                // 把解码器里积压的帧一次吐干净，别让输出队列堵住拖慢读数据
                int guard = 0;
                int oi = codec.dequeueOutputBuffer(bi, 10000);
                while (guard++ < 64) {
                    if (oi >= 0) {
                        codec.releaseOutputBuffer(oi, true);
                        frameCount++;
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "输出格式变化: " + codec.getOutputFormat());
                    } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        outBufs = codec.getOutputBuffers();
                    } else {
                        break;
                    }
                    oi = codec.dequeueOutputBuffer(bi, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "会话结束: " + e);
        } finally {
            tearDownAudio();
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            try { s.close(); } catch (Exception ignored) {}
            clientAddr = "-";
            stateText = "连接已断开";
            // 这一场投完了，界面就该退场，别占着电视的显示。
            // 端口交给常驻服务继续听，下一台手机连上来会自动再拉起这个界面。
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try { finish(); } catch (Throwable ignored) {}
                }
            }, 3000);
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        ui.removeCallbacks(refreshTask);
        super.onDestroy();
    }
}
