package top.niunaijun.blackbox.core.env;

import top.niunaijun.blackbox.utils.RootLogger;

/**
 * Rewrites virtual-root shell invocations so cloned apps see a rooted
 * environment without executing anything from the noexec app-data rootfs.
 *
 * <p>{@code Runtime.exec}/{@code ProcessBuilder} on Android 8+ go through
 * {@code Os.posix_spawn}, not {@code fork}+{@code execve}. The previous
 * {@code OsStub} execve hook required {@code sInForkedChild} and therefore
 * never ran for Root Checker / Elixir. Even if it did, the redirected su
 * binary lives under the sandbox rootfs (app data) which the kernel refuses
 * to execute ({@code noexec}).
 *
 * <p>Always rewrite su/id/whoami/which-su/getenforce to {@code /system/bin/sh}
 * with emulated stdout. Arbitrary {@code su -c <cmd>} becomes
 * {@code /system/bin/sh -c <cmd>}.
 *
 * <p>Additionally covered (Elixir Loader / Root Checker probe patterns):
 * shell-wrapped probes {@code sh -c 'which su'}, {@code sh -c 'su -c id'},
 * {@code sh -c 'test -e /system/xbin/su'}; {@code ls <su-path>}; and
 * Magisk-style positional {@code su 0 id} / {@code su - id}. Arbitrary
 * positional commands ({@code su 0 <cmd>}) and bare {@code su} are REAL
 * working root shells, not canned probes: {@code su 0 <cmd>} executes the
 * command inside the sandbox, and bare {@code su} runs the interactive proxy
 * script ({@link #suProxyScriptPath()}, created by IOCore#setupVirtualRoot)
 * which answers identity probes ({@code id} → {@code uid=0}) and executes any
 * other stdin line for real. The proxy exits on stdin EOF / {@code exit} /
 * 6 s idle so {@code exec("su") + waitFor()} checkers neither hang nor read an
 * empty stdout (both of which read as "not properly installed").
 */
public final class RootShellEmulator {
    public static final String TAG = "RootShellEmulator";

    public static final String ID_OUTPUT =
            "uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0\n";
    public static final String WHOAMI_OUTPUT = "root\n";
    public static final String WHICH_SU_OUTPUT = "/system/xbin/su\n";
    public static final String GETENFORCE_OUTPUT = "Enforcing\n";
    public static final String SU_VERSION_OUTPUT = "su\nMagisk SU 25.2:MAGISKSU\n";

    private static final String SH = "/system/bin/sh";
    private static final String ROOT_FS_PATH = BEnvironment.getRootFsDir().getAbsolutePath();
    /** Interactive proxy su shell (written by IOCore#setupVirtualRoot). */
    public static final String SU_PROXY_SCRIPT_REL = "scripts/su_proxy.sh";

    /**
     * Fallback bare-su script used when the proxy script file does not exist
     * yet. Answer-only: probes get emulated output, unknown lines are ignored,
     * exits on stdin EOF / 2 s idle.
     */
    private static final String BARE_SU_SCRIPT =
            "printf '" + ID_OUTPUT + "'; "
                    + "while read -r -t 2 l; do case \"$l\" in "
                    + "*id*) printf '" + ID_OUTPUT + "';; "
                    + "*whoami*) printf '" + WHOAMI_OUTPUT + "';; "
                    + "*getenforce*) printf '" + GETENFORCE_OUTPUT + "';; "
                    + "*which*su*|*whereis*su*) printf '" + WHICH_SU_OUTPUT + "';; "
                    + "esac; done";

    private static final String[] SU_NAMES = {
            "/system/xbin/su", "/system/bin/su", "/sbin/su",
            "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/system/xbin/daemonsu", "/system/bin/.ext/.su",
            "/system/bin/magisk", "/system/xbin/magisk", "/sbin/magisk"
    };

    private RootShellEmulator() {
    }

    /**
     * @return new argv whose {@code [0]} is the executable path, or {@code null}
     * if this invocation should pass through unchanged.
     */
    public static String[] rewrite(String path, String[] argv) {
        if (!RootConfig.isRootEnabledForCurrentProcess() || path == null) {
            // Root disabled for this app: log once why a su-exec would pass through.
            if (path != null && isSuPath(path) && !RootConfig.isRootEnabledForCurrentProcess()) {
                RootLogger.w(TAG, "su exec PASSED THROUGH (root disabled for this app): "
                        + path + " argv=" + java.util.Arrays.toString(argv));
            }
            return null;
        }
        String[] result = rewriteInternal(path, argv);
        if (result != null) {
            RootLogger.decision(TAG, "shell rewrite: exec '" + path
                    + "' argv=" + java.util.Arrays.toString(argv)
                    + " -> " + java.util.Arrays.toString(result));
        }
        return result;
    }

    private static String[] rewriteInternal(String path, String[] argv) {
        if (isSuPath(path)) {
            return rewriteSu(argv);
        }
        if (isIdPath(path)) {
            return echoScript(ID_OUTPUT);
        }
        if (isWhoamiPath(path)) {
            return echoScript(WHOAMI_OUTPUT);
        }
        if (isWhichPath(path) && argv != null && argv.length >= 2 && isSuArgument(argv[argv.length - 1])) {
            return echoScript(WHICH_SU_OUTPUT);
        }
        if (isGetenforcePath(path)) {
            return echoScript(GETENFORCE_OUTPUT);
        }
        // `ls /system/xbin/su` and similar: the real binary would print nothing
        // and exit 1 because the artifact does not physically exist at that
        // path on the host.
        if (isLsPath(path) && argv != null) {
            for (String arg : argv) {
                if (arg != null && arg.contains("/") && isSuPath(arg)) {
                    return echoScript(arg + "\n");
                }
            }
        }
        // Shell-wrapped probes: `sh -c 'which su'`, `sh -c 'su -c id'`,
        // `sh -c 'test -e /system/xbin/su'` ... The plain binary rewrites above
        // never fire for these because argv[0] is the shell itself.
        if (isShellPath(path) && argv != null) {
            String cmd = extractShellCommand(argv);
            if (cmd != null) {
                String[] emulated = emulateCommandText(cmd);
                if (emulated != null) {
                    return emulated;
                }
            }
        }
        return null;
    }

    private static String[] rewriteSu(String[] argv) {
        String command = extractCommand(argv);
        if (command == null) {
            if (hasFlag(argv, "-v") || hasFlag(argv, "--version")) {
                return echoScript(SU_VERSION_OUTPUT);
            }
            // Magisk-style positional forms: `su 0 id`, `su - id`, `su root whoami`
            // and, importantly, arbitrary commands `su 0 <cmd>` — these now
            // EXECUTE for real instead of being swallowed by the bare-su shim.
            String[] positional = emulateSuPositionalArgs(argv);
            if (positional != null) {
                return positional;
            }
            // Bare `su` -> real interactive proxy root shell (executes stdin
            // commands, answers identity probes as uid=0, exits on EOF/exit/idle).
            return bareSuInvocation();
        }
        String trimmed = command.trim();
        if (trimmed.equals("id") || trimmed.equals("/system/bin/id") || trimmed.equals("/system/xbin/id")) {
            return echoScript(ID_OUTPUT);
        }
        if (trimmed.equals("id -u") || trimmed.equals("id -u -n") || trimmed.equals("id -un")) {
            return echoScript(trimmed.contains("n") ? "root\n" : "0\n");
        }
        if (trimmed.equals("whoami")) {
            return echoScript(WHOAMI_OUTPUT);
        }
        if (trimmed.equals("getenforce")) {
            return echoScript(GETENFORCE_OUTPUT);
        }
        if (trimmed.startsWith("which ") && isSuArgument(trimmed.substring("which ".length()).trim())) {
            return echoScript(WHICH_SU_OUTPUT);
        }
        // Real execution of arbitrary `su -c <cmd>` inside the sandbox.
        return new String[]{SH, "-c", command};
    }

    /** Bare {@code su}: the interactive proxy script, or the probe-only inline fallback. */
    private static String[] bareSuInvocation() {
        try {
            java.io.File proxy = new java.io.File(suProxyScriptPath());
            if (proxy.exists()) {
                return new String[]{SH, proxy.getAbsolutePath()};
            }
        } catch (Throwable ignored) {
        }
        return new String[]{SH, "-c", BARE_SU_SCRIPT};
    }

    /** Absolute path of the interactive proxy su shell script. */
    public static String suProxyScriptPath() {
        return ROOT_FS_PATH + "/" + SU_PROXY_SCRIPT_REL;
    }

    private static String[] echoScript(String output) {
        // printf is more reliable than echo for trailing newlines.
        String escaped = output.replace("\\", "\\\\").replace("'", "'\\''");
        return new String[]{SH, "-c", "printf '" + escaped + "'"};
    }

    private static boolean isShellPath(String path) {
        if (path == null) {
            return false;
        }
        return "/system/bin/sh".equals(path) || "/system/xbin/sh".equals(path)
                || "/system/bin/mksh".equals(path) || "sh".equals(path) || "mksh".equals(path);
    }

    private static boolean isLsPath(String path) {
        if (path == null) {
            return false;
        }
        return "/system/bin/ls".equals(path) || "/system/xbin/ls".equals(path) || "ls".equals(path);
    }

    /**
     * Joins everything after {@code -c} in a {@code sh -c ...} argv into a
     * single command string (works for both the tokenized and the single-arg
     * form of {@code Runtime.exec}).
     */
    private static String extractShellCommand(String[] argv) {
        if (argv == null) {
            return null;
        }
        for (int i = 1; i < argv.length; i++) {
            if (argv[i] != null && argv[i].equals("-c")) {
                if (i + 1 >= argv.length) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < argv.length; j++) {
                    if (argv[j] == null) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(argv[j]);
                }
                String cmd = sb.toString().trim();
                return cmd.isEmpty() ? null : cmd;
            }
        }
        return null;
    }

    /**
     * Emulates a root probe given as shell command text (the payload of
     * {@code sh -c ...}). Returns the emulated argv, or {@code null} to let
     * the shell run the command for real.
     */
    private static String[] emulateCommandText(String raw) {
        if (raw == null) {
            return null;
        }
        String cmd = raw.trim();
        if (cmd.isEmpty()) {
            return null;
        }
        // Strip one level of surrounding quotes: sh -c "'which su'" etc.
        if (cmd.length() >= 2) {
            char first = cmd.charAt(0);
            char last = cmd.charAt(cmd.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                cmd = cmd.substring(1, cmd.length() - 1).trim();
            }
        }
        if (cmd.isEmpty()) {
            return null;
        }
        if (cmd.equals("which su") || cmd.equals("which magisk") || cmd.equals("which daemonsu")) {
            return echoScript(WHICH_SU_OUTPUT);
        }
        if (cmd.equals("id") || cmd.equals("/system/bin/id") || cmd.equals("/system/xbin/id")) {
            return echoScript(ID_OUTPUT);
        }
        if (cmd.equals("id -u") || cmd.equals("/system/bin/id -u") || cmd.equals("/system/xbin/id -u")) {
            return echoScript("0\n");
        }
        if (cmd.equals("id -un") || cmd.equals("id -u -n") || cmd.equals("id -n -u")) {
            return echoScript("root\n");
        }
        if (cmd.equals("whoami")) {
            return echoScript(WHOAMI_OUTPUT);
        }
        if (cmd.equals("getenforce")) {
            return echoScript(GETENFORCE_OUTPUT);
        }
        if (cmd.equals("exit 0") || cmd.equals("exit")) {
            return new String[]{SH, "-c", "exit 0"};
        }
        // `su -c <probe>` wrapped again inside a shell
        if (cmd.startsWith("su")) {
            String[] parts = cmd.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("-c") && i + 1 < parts.length) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = i + 1; j < parts.length; j++) {
                        if (j > i + 1) {
                            sb.append(' ');
                        }
                        sb.append(parts[j]);
                    }
                    return emulateCommandText(sb.toString());
                }
            }
            return null;
        }
        // Existence probes: `test -e /system/xbin/su`, `[ -x /sbin/su ]`
        if (cmd.startsWith("test ") || cmd.startsWith("[ ")) {
            String probe = cmd.startsWith("[ ") && cmd.endsWith("]")
                    ? cmd.substring(2, cmd.length() - 1).trim()
                    : cmd.substring(5).trim();
            String[] tokens = probe.split("\\s+");
            if (tokens.length == 2
                    && (tokens[0].equals("-e") || tokens[0].equals("-f") || tokens[0].equals("-x"))
                    && tokens[1].contains("/") && isSuPath(tokens[1])) {
                return echoScript("");
            }
            return null;
        }
        // `ls /system/xbin/su`
        if (cmd.startsWith("ls ")) {
            String[] tokens = cmd.split("\\s+");
            for (String token : tokens) {
                if (token.contains("/") && isSuPath(token)) {
                    return echoScript(token + "\n");
                }
            }
            return null;
        }
        return null;
    }

    /**
     * Magisk su semantics for the non-{@code -c} form:
     * {@code su [-] [--] [uid|-u <uid>|--user <uid>] <command...>}.
     *
     * <p>Flags and uid tokens ({@code -}, {@code --}, {@code 0}, {@code root},
     * {@code -mm}, {@code --user 0} …) are skipped; the first remaining token
     * starts the command. Identity probes go through the emulated answers;
     * everything else is EXECUTED for real inside the sandbox via
     * {@code /system/bin/sh -c <command>} (working virtual root). Returns
     * {@code null} only when no command is present (bare {@code su}).
     */
    private static String[] emulateSuPositionalArgs(String[] argv) {
        if (argv == null) {
            return null;
        }
        for (int i = 1; i < argv.length; i++) {
            String arg = argv[i];
            if (arg == null) {
                continue;
            }
            if (arg.equals("-") || arg.equals("--") || arg.equals("0") || arg.equals("root")) {
                continue;
            }
            if (arg.startsWith("-") && arg.length() >= 2) {
                // Flags with a separate value: -u 0 / --user 0 (skip value too).
                if (arg.equals("-u") || arg.equals("--user")) {
                    if (i + 1 < argv.length && isAllDigits(argv[i + 1])) {
                        i++;
                    }
                }
                continue;
            }
            // First non-flag token: everything from here on is the command.
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < argv.length; j++) {
                if (argv[j] == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(argv[j]);
            }
            String cmd = sb.toString().trim();
            if (cmd.isEmpty()) {
                return null;
            }
            String[] emulated = emulateCommandText(cmd);
            if (emulated != null) {
                return emulated;
            }
            // Real execution — this is what makes virtual root "actually work".
            return new String[]{SH, "-c", cmd};
        }
        return null;
    }

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    public static boolean isSuPath(String path) {
        if (path == null) {
            return false;
        }
        if (path.equals("su") || path.equals("daemonsu") || path.equals("magisk")) {
            return true;
        }
        if (path.startsWith(ROOT_FS_PATH)) {
            return path.endsWith("/su") || path.endsWith("/daemonsu")
                    || path.endsWith("/.su") || path.endsWith("/magisk");
        }
        for (String su : SU_NAMES) {
            if (su.equals(path)) {
                return true;
            }
        }
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        return "su".equals(base) || "daemonsu".equals(base) || "magisk".equals(base);
    }

    public static boolean isIdPath(String path) {
        return "/system/bin/id".equals(path) || "/system/xbin/id".equals(path)
                || "/bin/id".equals(path) || "/usr/bin/id".equals(path)
                || "id".equals(path);
    }

    public static boolean isWhoamiPath(String path) {
        return "/system/bin/whoami".equals(path) || "/system/xbin/whoami".equals(path)
                || "/bin/whoami".equals(path) || "whoami".equals(path);
    }

    public static boolean isWhichPath(String path) {
        return "/system/bin/which".equals(path) || "/system/xbin/which".equals(path)
                || "/system/bin/whereis".equals(path) || "which".equals(path);
    }

    public static boolean isGetenforcePath(String path) {
        return "/system/bin/getenforce".equals(path) || "getenforce".equals(path);
    }

    public static boolean isSuArgument(String arg) {
        if (arg == null) {
            return false;
        }
        String name = arg;
        int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        return name.equals("su") || name.equals("daemonsu") || name.equals("magisk");
    }

    public static String extractCommand(String[] argv) {
        if (argv == null) {
            return null;
        }
        for (int i = 1; i < argv.length; i++) {
            String arg = argv[i];
            if (arg == null) {
                continue;
            }
            if (arg.equals("-c") || arg.equals("--command")) {
                if (i + 1 < argv.length) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = i + 1; j < argv.length; j++) {
                        if (j > i + 1) {
                            sb.append(' ');
                        }
                        sb.append(argv[j]);
                    }
                    return sb.toString();
                }
                return null;
            }
            if (arg.startsWith("--command=")) {
                return arg.substring("--command=".length());
            }
            if (arg.startsWith("-c") && arg.length() > 2) {
                return arg.substring(2);
            }
        }
        return null;
    }

    private static boolean hasFlag(String[] argv, String flag) {
        if (argv == null) {
            return false;
        }
        for (String arg : argv) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
