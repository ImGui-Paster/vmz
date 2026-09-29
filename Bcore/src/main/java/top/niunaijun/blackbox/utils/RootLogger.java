package top.niunaijun.blackbox.utils;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Always-on ROOT diagnostic logger.
 *
 * <p>Every step of the virtual-root chain logs here: server-side root policy
 * resolution ({@code BRootManagerService}), config delivery
 * ({@code ProcessRecord.getClientConfig}), per-process root state
 * ({@code RootConfig.init}), IO/rootfs setup ({@code IOCore#enableRedirect}),
 * libcore identity spoofing and shell rewrites ({@code OsStub},
 * {@code RootShellEmulator}). The goal: after a failing root check in a
 * virtual app, {@code root_diag_*.log} shows exactly which link of the chain
 * broke and why - no logcat hunting, no guessing.
 *
 * <p>Files land next to the {@code DiagnosticLogger} files, one per process:
 * {@code /storage/emulated/0/Android/data/<host>/files/root_diag_<proc>.log}
 * (server → {@code root_diag_server.log}, virtual apps → {@code root_diag_pN.log}).
 *
 * <p>Same safety model as {@link DiagnosticLogger}: lock-free queue drained by a
 * daemon thread, back-pressure drops instead of blocking, size rotation. Also
 * mirrors every line to logcat under tag {@code RootTrace}.
 */
public final class RootLogger {
    public static final String TAG = "RootTrace";
    public static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;

    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> PREINIT = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean WRITER_STARTED = new AtomicBoolean(false);
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final Object FILE_LOCK = new Object();

    private static volatile File sFile;

    private RootLogger() {
    }

    public static File getLogFile() {
        return sFile;
    }

    public static void i(String tag, String msg) {
        write('I', tag, msg);
    }

    public static void w(String tag, String msg) {
        write('W', tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        String stack = t == null ? "" : " | " + Log.getStackTraceString(t);
        write('E', tag, msg + stack);
    }

    /**
     * Decision line for the root chain: one call site, one line, always
     * logged (never rate limited). Prefix makes grepping easy:
     * {@code grep "ROOT-DECISION" root_diag_*.log}.
     */
    public static void decision(String tag, String msg) {
        write('I', tag, "[ROOT-DECISION] " + msg);
    }

    /**
     * Hot-path logging (uid spoofing, su-path probes). First {@code firstN}
     * calls per {@code key} log normally, then one line per {@code eachN} so a
     * chatty root app cannot flood the log file.
     */
    public static void sample(String tag, String msg, int key, int firstN, int eachN) {
        try {
            Counter c = sCounters[key % sCounters.length];
            int n = c.count.incrementAndGet();
            if (n <= firstN || n % eachN == 0) {
                write('D', tag, msg + (n > firstN ? " (x" + n + ")" : ""));
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Counter[] sCounters = new Counter[32];

    private static final class Counter {
        final AtomicInteger count = new AtomicInteger();
    }

    static {
        for (int i = 0; i < sCounters.length; i++) {
            sCounters[i] = new Counter();
        }
    }

    private static void write(char level, String tag, String msg) {
        try {
            int p = level == 'E' ? Log.ERROR : level == 'W' ? Log.WARN : Log.INFO;
            Log.println(p, TAG, tag + ": " + msg);
        } catch (Throwable ignored) {
        }
        String line = format(level, tag, msg);
        if (sFile == null) {
            tryOpenFile();
            if (sFile == null) {
                if (PREINIT.size() < 400) {
                    PREINIT.offer(line);
                }
                return;
            }
        }
        if (QUEUE.size() > 4000) {
            DROPPED.incrementAndGet();
            return;
        }
        QUEUE.offer(line);
        ensureWriter();
    }

    /**
     * DiagnosticLogger owns the log dir; if it is not initialized yet (early
     * server/client hooks) we buffer and retry on the next write.
     */
    private static void tryOpenFile() {
        try {
            File dir = DiagnosticLogger.getLogDir();
            if (dir == null || !dir.exists()) {
                return;
            }
            String label = DiagnosticLogger.getProcLabel();
            if (label == null || label.length() == 0) {
                label = "unknown";
            }
            File file = new File(dir, "root_diag_" + label + ".log");
            rotateIfNeeded(file);
            sFile = file;
            String pre;
            while ((pre = PREINIT.poll()) != null) {
                QUEUE.offer(pre);
            }
            write('I', TAG, "===== ROOT LOG INIT pid=" + android.os.Process.myPid()
                    + " label=" + label + " file=" + file.getAbsolutePath() + " =====");
        } catch (Throwable ignored) {
        }
    }

    private static String format(char level, String tag, String msg) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(now())
                .append(' ').append(level)
                .append(' ').append(DiagnosticLogger.getProcLabel())
                .append(" pid=").append(android.os.Process.myPid())
                .append(" tid=").append(android.os.Process.myTid())
                .append(' ').append(tag).append(": ")
                .append(msg);
        return sb.toString();
    }

    private static String now() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private static void ensureWriter() {
        if (!WRITER_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(RootLogger::writerLoop, "RootDiagWriter");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static void writerLoop() {
        while (true) {
            try {
                String line = QUEUE.poll();
                if (line == null) {
                    Thread.sleep(50);
                    continue;
                }
                File file = sFile;
                if (file == null) {
                    continue;
                }
                synchronized (FILE_LOCK) {
                    rotateIfNeeded(file);
                    FileOutputStream fos = new FileOutputStream(file, true);
                    OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                    w.write(line);
                    w.write('\n');
                    int extra = 0;
                    String more;
                    while (extra < 64 && (more = QUEUE.poll()) != null) {
                        w.write(more);
                        w.write('\n');
                        extra++;
                    }
                    w.flush();
                    try {
                        fos.getFD().sync();
                    } catch (Throwable ignored) {
                    }
                    w.close();
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void rotateIfNeeded(File file) {
        try {
            if (file != null && file.exists() && file.length() > MAX_FILE_BYTES) {
                File bak = new File(file.getAbsolutePath() + ".old");
                if (bak.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    bak.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(bak);
            }
        } catch (Throwable ignored) {
        }
    }
}
