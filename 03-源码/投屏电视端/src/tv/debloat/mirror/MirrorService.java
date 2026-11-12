package tv.debloat.mirror;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 常驻的接收服务。
 *
 * 把 9000 的监听和 9100 的应答从界面里搬出来，目的是让"端口"跟界面彻底脱钩：
 *
 *  - 界面关了、被切走、电视在放别的内容，端口照样在听，手机随时连得进来；
 *  - 界面被重新打开也不会再 bind 一次 9000，不会再有"端口占用，启动失败"；
 *  - 投屏断开后服务自己还在，下一台手机直接接；
 *  - 端口万一真出问题（被系统收走之类），这里 1 秒后自己重建，不用人管。
 *
 * 界面（MirrorActivity）只在真的有人连上来时才被拉起来显示画面，断开就退掉。
 */
public class MirrorService extends Service {

    static final String TAG = "TVMIRROR";
    static final int PORT = 9000;
    static final int DISCOVER_PORT = 9100;

    /** 已经 accept 到、还没被界面取走的连接。界面起来后会立刻取走并置 null。 */
    static volatile Socket pending;

    /** 保证整套监听只起一次（服务被重复 startService 也不会多一条线程）。 */
    private static volatile boolean started = false;

    private volatile boolean alive = true;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) {
            started = true;
            new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "mirror-accept").start();
            new Thread(new Runnable() {
                @Override public void run() { discoveryLoop(); }
            }, "mirror-discovery").start();
            Log.i(TAG, "常驻服务启动，监听 " + PORT + " / " + DISCOVER_PORT);
        }
        // 被系统清理过就再拉起来，保证一直挂着
        return START_STICKY;
    }

    private void acceptLoop() {
        ServerSocket ss = null;
        while (alive) {
            try {
                if (ss == null || ss.isClosed()) {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(PORT));
                    Log.i(TAG, "监听端口 " + PORT + " 就绪");
                }
                Socket s = ss.accept();
                try {
                    Log.i(TAG, "手机接入 " + s.getInetAddress().getHostAddress());
                } catch (Throwable ignored) {}
                Socket old = pending;
                pending = s;
                if (old != null) {
                    try { old.close(); } catch (Throwable ignored) {}
                }
                popUp();
            } catch (Throwable e) {
                // 端口被占、accept 出错：关掉重来，别让线程退出。
                // 以前这里一失败线程就死了，之后永远接不到新连接。
                Log.e(TAG, "监听出错，1 秒后重建: " + e);
                if (ss != null) {
                    try { ss.close(); } catch (Throwable ignored) {}
                    ss = null;
                }
                sleep(1000);
            }
        }
    }

    /** 有手机连进来了，把界面拉到前台显示画面。 */
    private void popUp() {
        try {
            Intent i = new Intent(this, MirrorActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable e) {
            Log.e(TAG, "拉起界面失败: " + e);
        }
    }

    private void discoveryLoop() {
        while (alive) {
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(DISCOVER_PORT));
                Log.i(TAG, "自动发现服务监听 " + DISCOVER_PORT);
                byte[] buf = new byte[64];
                while (alive) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    byte[] rb = ("TVMIRROR:" + PORT).getBytes("UTF-8");
                    try {
                        ds.send(new DatagramPacket(rb, rb.length, p.getAddress(), p.getPort()));
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable e) {
                Log.e(TAG, "发现服务出错，1 秒后重建: " + e);
            } finally {
                if (ds != null) {
                    try { ds.close(); } catch (Throwable ignored) {}
                }
            }
            sleep(1000);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }
}
