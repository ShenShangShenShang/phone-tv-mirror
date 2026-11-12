package tv.debloat.mirrorsend;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 手机端自己的小日志本 + 一个只读的 HTTP 口子。
 * 投屏断了以后可以从电脑上直接拉日志，不用猜。
 *
 * 除了内存里留一份，还同时追加到 data/data/<包名>/files/mirror.log。
 * 内存那份会跟着进程一起消失，进程一崩就什么都查不到了；
 * 落盘这份在进程死了以后还在，下次打开能直接看到上一轮到底崩在哪。
 */
public class LogHub {

    private static final int MAX_LINES = 400;
    /** 落盘文件超过这个大小就清空重来，免得越写越大。 */
    private static final long MAX_FILE = 300 * 1024;

    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static Context APP;
    private static File LOG_FILE;

    /** 早先的成员都不带 Context，这里补一个入口，在 Activity/Service 的 onCreate 里调。 */
    public static void init(Context c) {
        if (APP != null || c == null) return;
        APP = c.getApplicationContext();
        LOG_FILE = new File(APP.getFilesDir(), "mirror.log");
    }

    public static synchronized void add(String s) {
        String line;
        try {
            line = FMT.format(new Date()) + " " + s;
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        } catch (Exception ignored) {
            line = s;
        }
        Log.i("MIRRORSEND", s);
        appendFile(line);
    }

    /**
     * 追加一行到盘上。
     * 用 FileOutputStream(..., true) 打开-写-关，不复用句柄：
     * 进程被直接杀掉时也不会留下半截缓冲，下次打开就是完整的。
     */
    private static void appendFile(String line) {
        File f = LOG_FILE;
        if (f == null) return;
        try {
            if (f.length() > MAX_FILE) {
                // 太大就从头来，但保留一行说明，别让人以为日志是空的
                FileOutputStream reset = new FileOutputStream(f, false);
                reset.write((FMT.format(new Date()) + " 日志超过上限，已重置\n").getBytes("UTF-8"));
                reset.flush();
                reset.close();
            }
            FileOutputStream out = new FileOutputStream(f, true);
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {}
    }

    /** 读盘上那份，给"上次崩在哪"用。 */
    public static String dumpFile() {
        File f = LOG_FILE;
        if (f == null || !f.exists()) return "（还没有落盘日志）";
        try {
            byte[] b = new byte[(int) Math.min(f.length(), MAX_FILE)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            if (n <= 0) return "（落盘日志是空的）";
            return "最近一次落盘日志\n\n" + new String(b, 0, n, "UTF-8");
        } catch (Throwable e) {
            return "读落盘日志失败: " + e;
        }
    }

    /** 日志文件现在多大，给设置页显示用。 */
    public static long logFileSize(Context c) {
        File f = LOG_FILE;
        if (f == null && c != null) {
            f = new File(c.getApplicationContext().getFilesDir(), "mirror.log");
        }
        try {
            return f != null && f.exists() ? f.length() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 清缓存：内存那份和盘上那份一起清掉。
     * 清完什么都不影响，投屏照旧，只是"上次崩在哪"的现场没了。
     */
    public static synchronized void clear() {
        LINES.clear();
        try {
            File f = LOG_FILE;
            if (f != null && f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("先进来的在最上面\n\n");
        for (String l : LINES) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static void startServer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket(9999);
                    add("日志口 9999 已开");
                    while (true) {
                        Socket s = ss.accept();
                        try {
                            BufferedReader r = new BufferedReader(
                                    new InputStreamReader(s.getInputStream(), "UTF-8"));
                            String line = r.readLine();
                            PrintWriter w = new PrintWriter(s.getOutputStream());
                            // 路径 /file 给盘上那份，其他给内存那份
                            String body = (line != null && line.contains("/file"))
                                    ? dumpFile() : dump();
                            byte[] data = body.getBytes("UTF-8");
                            w.print("HTTP/1.1 200 OK\r\n");
                            w.print("Content-Type: text/plain; charset=utf-8\r\n");
                            w.print("Content-Length: " + data.length + "\r\n");
                            w.print("Connection: close\r\n\r\n");
                            w.flush();
                            s.getOutputStream().write(data);
                            s.getOutputStream().flush();
                        } catch (Exception ignored) {
                        } finally {
                            try { s.close(); } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    add("日志口启动失败 " + e);
                }
            }
        }, "log-http");
        t.setDaemon(true);
        t.start();
    }
}
