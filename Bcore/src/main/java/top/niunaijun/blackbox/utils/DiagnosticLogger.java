package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Always-on file logger used to diagnose frozen black screens and install bugs.
 *
 * <p>Every host / server / virtual process writes its own log under the host app's
 * public-ish external files dir (no extra permission needed):
 * {@code /storage/emulated/0/Android/data/top.niunaijun.blackbox/files/}
 *
 * <p>Writes never run on the caller thread (a daemon drains a lock-free queue), so
 * logging cannot itself deadlock the main thread. Phase scopes start a watchdog
 * that dumps every thread's stack if a phase runs longer than
 * {@link #WATCHDOG_MS}.
 *
 * <p>IMPORTANT: IOCore must NOT redirect this directory, otherwise virtual apps
 * would write into their sandboxed external tree and the user would never see
 * the logs. See {@link top.niunaijun.blackbox.core.IOCore#redirectPath(String)}.
 */
public final class DiagnosticLogger {
    public static final String TAG = "BbDiag";
    public static final String HOST_PKG_FALLBACK = "top.niunaijun.blackbox";
    public static final long WATCHDOG_MS = 8_000L;
    public static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;

    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> PREINIT = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PREINIT_COUNT = new AtomicInteger();
    private static final AtomicBoolean WRITER_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean INITED = new AtomicBoolean(false);
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final ConcurrentHashMap<Integer, Watch> OPEN = new ConcurrentHashMap<>();
    private static final AtomicInteger SCOPE_SEQ = new AtomicInteger();
    private static final Object FILE_LOCK = new Object();

    private static volatile File sDir;
    private static volatile File sFile;
    private static volatile String sProcLabel = "unknown";
    private static volatile WatchdogThread sWatchdog;

    private DiagnosticLogger() {
    }

    public static File getLogDir() {
        return sDir;
    }

    /** Short process label used in log file names (main/server/pN/unknown). */
    public static String getProcLabel() {
        return sProcLabel;
    }

    public static File getLogFile() {
        return sFile;
    }

    public static boolean isInitialized() {
        return INITED.get();
    }

    /**
     * Safe to call many times, from any process, even before {@link Context} exists.
     */
    public static void init(Context context) {
        init(context, null);
    }

    public static void init(Context context, String processNameHint) {
        try {
            String proc = processNameHint;
            if (proc == null || proc.length() == 0) {
                proc = currentProcessName();
            }
            sProcLabel = shortenProc(proc);

            File dir = resolveLogDir(context);
            if (dir == null) {
                android.util.Log.w(TAG, "init: log dir is null");
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                android.util.Log.w(TAG, "init: mkdirs failed for " + dir);
            }
            sDir = dir;
            File file = new File(dir, "bb_diag_" + sProcLabel + ".log");
            rotateIfNeeded(file);
            sFile = file;
            writeReadme(dir);
            ensureWriter();
            ensureWatchdog();
            INITED.set(true);

            String pre;
            while ((pre = PREINIT.poll()) != null) {
                QUEUE.offer(pre);
            }

            i(TAG, "===== DIAG INIT pid=" + Process.myPid()
                    + " proc=" + proc
                    + " label=" + sProcLabel
                    + " sdk=" + Build.VERSION.SDK_INT
                    + " release=" + Build.VERSION.RELEASE
                    + " brand=" + Build.BRAND
                    + " model=" + Build.MODEL
                    + " file=" + file.getAbsolutePath()
                    + " mainThread=" + (Looper.myLooper() == Looper.getMainLooper())
                    + " =====");
            installCrashHook();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "init failed", t);
        }
    }

    /**
     * Host app-private external dir. Matches what the user asked to inspect:
     * {@code /storage/emulated/0/Android/data/&lt;hostPkg&gt;/files/}.
     */
    public static File resolveLogDir(Context context) {
        try {
            if (context != null) {
                Context app = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                File ext = app.getExternalFilesDir(null);
                if (ext != null) {
                    return ext;
                }
                File inner = app.getFilesDir();
                if (inner != null) {
                    return inner;
                }
            }
        } catch (Throwable ignored) {
        }
        return new File("/storage/emulated/0/Android/data/" + HOST_PKG_FALLBACK + "/files");
    }

    /**
     * True when {@code path} points at the host diagnostic directory and must
     * skip IO redirection inside virtual processes.
     */
    public static boolean isProtectedPath(String path) {
        if (path == null || path.length() == 0) {
            return false;
        }
        if (path.contains("/bb_diag_") || path.contains("BB_DIAG_README")
                || path.contains("/root_diag_") || path.contains("/root_native_")) {
            return true;
        }
        File dir = sDir;
        if (dir != null) {
            String abs = dir.getAbsolutePath();
            if (abs.length() > 0 && path.startsWith(abs)) {
                return true;
            }
        }
        // Host app-private external tree. Virtual IO redirect maps /storage/emulated/0
        // onto the sandbox, which would hide these logs from the user.
        if (path.contains("/Android/data/" + HOST_PKG_FALLBACK + "/")) {
            return true;
        }
        try {
            Class<?> bbc = Class.forName("top.niunaijun.blackbox.BlackBoxCore");
            Object inst = bbc.getMethod("get").invoke(null);
            if (inst != null) {
                Object pkg = bbc.getMethod("getHostPkg").invoke(null);
                if (pkg instanceof String && ((String) pkg).length() > 0
                        && path.contains("/Android/data/" + pkg + "/")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void i(String tag, String msg) {
        write('I', tag, msg, null);
    }

    public static void w(String tag, String msg) {
        write('W', tag, msg, null);
    }

    public static void e(String tag, String msg) {
        write('E', tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        write('E', tag, msg, t);
    }

    /**
     * Cheap Slog mirror. Never blocks; drops when the queue is backlogged so a
     * log storm cannot freeze the process we are trying to observe.
     */
    public static void mirror(char level, String tag, String msg, Throwable t) {
        if (level == 'V' || level == 'D') {
            return;
        }
        write(level, tag, msg, t);
    }

    /**
     * File-only write used by {@link Slog} so we do not double-print to logcat.
     */
    public static void file(char level, String tag, String msg, Throwable t) {
        try {
            String line = format(level, tag, msg, t);
            if (!INITED.get()) {
                if (PREINIT_COUNT.incrementAndGet() < 400) {
                    PREINIT.offer(line);
                }
                return;
            }
            if (QUEUE.size() > 4000) {
                DROPPED.incrementAndGet();
                return;
            }
            QUEUE.offer(line);
        } catch (Throwable ignored) {
        }
    }

    public static Scope scope(String name) {
        return new Scope(name);
    }

    public static void dumpThreads(String reason) {
        try {
            StringBuilder sb = new StringBuilder(4096);
            sb.append("----- thread dump (").append(reason).append(") pid=")
                    .append(Process.myPid()).append(" -----\n");
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread th = e.getKey();
                sb.append('#').append(th.getId()).append(' ').append(th.getName())
                        .append(" state=").append(th.getState())
                        .append(th.isDaemon() ? " daemon" : "")
                        .append('\n');
                StackTraceElement[] st = e.getValue();
                if (st != null) {
                    int n = Math.min(st.length, 40);
                    for (int i = 0; i < n; i++) {
                        sb.append("    at ").append(st[i]).append('\n');
                    }
                }
            }
            sb.append("----- end thread dump -----");
            write('W', TAG, sb.toString(), null);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "dumpThreads failed", t);
        }
    }

    private static void write(char level, String tag, String msg, Throwable t) {
        try {
            android.util.Log.println(priorityOf(level), tag == null ? TAG : tag,
                    msg == null ? "" : msg);
            if (t != null) {
                android.util.Log.println(android.util.Log.ERROR, tag == null ? TAG : tag,
                        Log.getStackTraceString(t));
            }
        } catch (Throwable ignored) {
        }
        String line = format(level, tag, msg, t);
        if (!INITED.get()) {
            if (PREINIT_COUNT.incrementAndGet() < 400) {
                PREINIT.offer(line);
            }
            return;
        }
        if (QUEUE.size() > 4000) {
            DROPPED.incrementAndGet();
            return;
        }
        QUEUE.offer(line);
    }

    private static int priorityOf(char level) {
        switch (level) {
            case 'E':
                return android.util.Log.ERROR;
            case 'W':
                return android.util.Log.WARN;
            case 'D':
                return android.util.Log.DEBUG;
            case 'V':
                return android.util.Log.VERBOSE;
            default:
                return android.util.Log.INFO;
        }
    }

    private static String format(char level, String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(now())
                .append(' ').append(level)
                .append(' ').append(sProcLabel)
                .append(" pid=").append(Process.myPid())
                .append(" tid=").append(Process.myTid());
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sb.append(" MAIN");
        }
        sb.append(' ').append(tag == null ? TAG : tag).append(": ")
                .append(msg == null ? "" : msg);
        if (t != null) {
            sb.append('\n').append(stack(t));
        }
        return sb.toString();
    }

    private static String now() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter(1024);
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static void ensureWriter() {
        if (!WRITER_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(DiagnosticLogger::writerLoop, "BbDiagWriter");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static void writerLoop() {
        while (true) {
            try {
                String line = QUEUE.poll();
                if (line == null) {
                    Thread.sleep(40);
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
                    // Drain a burst so we fsync less often but still flush this batch.
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
                    android.util.Log.w(TAG, "writerLoop: " + t.getMessage());
                } catch (Throwable ignored) {
                }
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

    private static void writeReadme(File dir) {
        try {
            File readme = new File(dir, "BB_DIAG_README.txt");
            if (readme.exists() && readme.length() > 40) {
                return;
            }
            String text = "BlackBox diagnostic logs\n"
                    + "========================\n"
                    + "This folder is:\n"
                    + "  /storage/emulated/0/Android/data/top.niunaijun.blackbox/files/\n\n"
                    + "Files:\n"
                    + "  bb_diag_main.log     host UI process\n"
                    + "  bb_diag_server.log   :black server process\n"
                    + "  bb_diag_pN.log       virtual app process :pN\n\n"
                    + "If a cloned app shows a frozen black screen, open the matching\n"
                    + "bb_diag_pN.log and look for:\n"
                    + "  BEGIN handleBindApplication\n"
                    + "  WATCHDOG STILL IN ...\n"
                    + "  ----- thread dump -----\n"
                    + "The last BEGIN without a matching END is the stuck phase.\n"
                    + "Send ALL bb_diag_*.log files (and .old) to the developer.\n";
            FileOutputStream fos = new FileOutputStream(readme, false);
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static void ensureWatchdog() {
        if (sWatchdog != null) {
            return;
        }
        synchronized (DiagnosticLogger.class) {
            if (sWatchdog == null) {
                WatchdogThread w = new WatchdogThread();
                w.setDaemon(true);
                w.start();
                sWatchdog = w;
            }
        }
    }

    private static void installCrashHook() {
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        DiagnosticLogger.e(TAG, "UNCAUGHT in " + (t == null ? "?" : t.getName()), e);
                        dumpThreads("uncaught:" + (t == null ? "?" : t.getName()));
                        // Give the writer a moment to flush.
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ignored) {
                        }
                    } catch (Throwable ignored) {
                    }
                    if (prev != null) {
                        prev.uncaughtException(t, e);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String currentProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                String n = Application.getProcessNameSafe();
                if (n != null && n.length() > 0) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File f = new File("/proc/self/cmdline");
            byte[] buf = new byte[256];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            if (n > 0) {
                int end = 0;
                while (end < n && buf[end] != 0) {
                    end++;
                }
                return new String(buf, 0, end, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        return "pid" + Process.myPid();
    }

    private static String shortenProc(String proc) {
        if (proc == null || proc.length() == 0) {
            return "unknown";
        }
        int colon = proc.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < proc.length()) {
            String suffix = proc.substring(colon + 1);
            if ("black".equals(suffix) || suffix.contains("black")) {
                return "server";
            }
            return suffix.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
        return "main";
    }

    /**
     * Isolated so we don't import android.app.Application at class init in
     * weird process states. Hidden behind a version check.
     */
    private static final class Application {
        static String getProcessNameSafe() {
            return android.app.Application.getProcessName();
        }
    }

    public static final class Scope implements AutoCloseable {
        private final int id;
        private final String name;
        private final long startMs;
        private final boolean main;
        private volatile boolean closed;

        private Scope(String name) {
            this.id = SCOPE_SEQ.incrementAndGet();
            this.name = name == null ? "phase" : name;
            this.startMs = SystemClock.uptimeMillis();
            this.main = Looper.myLooper() == Looper.getMainLooper();
            OPEN.put(this.id, new Watch(this.id, this.name, this.startMs, this.main, Process.myTid()));
            ensureWatchdog();
            i(TAG, "BEGIN " + this.name + " scope=" + this.id + (this.main ? " MAIN" : ""));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            OPEN.remove(id);
            long dt = SystemClock.uptimeMillis() - startMs;
            i(TAG, "END " + name + " scope=" + id + " +" + dt + "ms");
        }
    }

    private static final class Watch {
        final int id;
        final String name;
        final long startMs;
        final boolean main;
        final int tid;
        volatile long lastWarnMs;

        Watch(int id, String name, long startMs, boolean main, int tid) {
            this.id = id;
            this.name = name;
            this.startMs = startMs;
            this.main = main;
            this.tid = tid;
            this.lastWarnMs = startMs;
        }
    }

    private static final class WatchdogThread extends Thread {
        WatchdogThread() {
            super("BbDiagWatchdog");
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(1000);
                    long now = SystemClock.uptimeMillis();
                    for (Watch w : OPEN.values()) {
                        long age = now - w.startMs;
                        if (age < WATCHDOG_MS) {
                            continue;
                        }
                        if (now - w.lastWarnMs < WATCHDOG_MS) {
                            continue;
                        }
                        w.lastWarnMs = now;
                        w(TAG, "WATCHDOG STILL IN " + w.name
                                + " scope=" + w.id
                                + " age=" + age + "ms"
                                + " main=" + w.main
                                + " tid=" + w.tid
                                + " — dumping threads");
                        dumpThreads("watchdog:" + w.name);
                    }
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable t) {
                    try {
                        android.util.Log.w(TAG, "watchdog: " + t.getMessage());
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }
}
