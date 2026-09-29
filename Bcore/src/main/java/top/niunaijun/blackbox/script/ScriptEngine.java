package top.niunaijun.blackbox.script;

import android.content.Context;
import android.net.Uri;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.Slog;

/** Imports private files and runs shell scripts. Environment flags do not grant kernel privileges. */
public final class ScriptEngine {
    public static final String TAG = "ScriptEngine";
    private ScriptEngine() {}
    public static class ScriptResult {
        public final int exitCode;
        public final String output;
        public ScriptResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
        public boolean isSuccess() { return exitCode == 0; }
    }
    public interface ScriptOutputListener {
        void onOutput(String chunk);
        void onFinished(ScriptResult result);
    }

    public static List<File> listImported() {
        List<File> result = new ArrayList<>();
        File[] files = BEnvironment.getLocalTmpDir().listFiles();
        if (files != null) for (File f : files)
            if (!f.getName().startsWith(".") && !"bin".equals(f.getName())) result.add(f);
        Collections.sort(result, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    public static File importFromUri(Context context, Uri uri, String displayName) {
        if (context == null || uri == null) return null;
        File staged = null, unpacked = null;
        try {
            String name = sanitizeName(displayName == null ? "script.sh" : displayName);
            File imports = new File(context.getCacheDir(), "script_imports");
            ModuleFiles.mkdir(imports);
            staged = File.createTempFile("import-", ".tmp", imports);
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(staged)) {
                if (in == null) throw new IOException("Cannot open selected file");
                ModuleFiles.copy(in, out);
            }
            File localTmp = BEnvironment.getLocalTmpDir();
            ModuleFiles.mkdir(localTmp);
            String unique = UUID.randomUUID().toString();
            File target;
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                unpacked = new File(localTmp, ".import-" + unique);
                ModuleFiles.mkdir(unpacked);
                ModuleFiles.unzip(staged, unpacked); // Preserve paths; never sanitize individual ZIP entries.
                chmodRecursive(unpacked);
                target = new File(localTmp, name.substring(0, name.length() - 4) + "-" + unique);
                if (!unpacked.renameTo(target)) throw new IOException("Cannot commit imported archive");
                unpacked = null;
            } else {
                target = new File(localTmp, unique + "-" + name);
                try (InputStream in = new FileInputStream(staged); OutputStream out = new FileOutputStream(target)) {
                    ModuleFiles.copy(in, out);
                } catch (Exception e) { target.delete(); throw e; }
                chmodRecursive(target);
            }
            createSuWrapper();
            Slog.i(TAG, "Imported " + target);
            return target;
        } catch (Exception e) {
            Slog.e(TAG, "Import failed: " + e.getMessage());
            return null;
        } finally {
            if (staged != null) staged.delete();
            if (unpacked != null) deleteRecursive(unpacked);
        }
    }

    public static ScriptResult execute(File script, List<String> args) {
        StringBuilder output = new StringBuilder();
        return run(script, args, output, null);
    }
    public static void execute(File script, List<String> args, StringBuilder collector, ScriptOutputListener listener) {
        ScriptResult result = run(script, args, collector, listener);
        if (listener != null) listener.onFinished(result);
    }
    private static ScriptResult run(File script, List<String> args, StringBuilder collector, ScriptOutputListener listener) {
        Process process = null;
        try {
            if (script == null || !script.isFile()) throw new IOException("Select a .sh file, not a directory or Magisk module");
            File tmp = BEnvironment.getLocalTmpDir().getCanonicalFile();
            script = script.getCanonicalFile();
            if (!script.getPath().startsWith(tmp.getPath() + File.separator)) throw new IOException("Script must be imported first");
            chmodRecursive(script);
            createSuWrapper();
            List<String> command = new ArrayList<>();
            command.add("/system/bin/sh"); command.add(script.getPath());
            if (args != null) command.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(script.getParentFile());
            Map<String, String> env = pb.environment();
            env.put("PATH", new File(tmp, "bin").getPath() + ":" + tmp + ":/system/bin:/system/xbin:/vendor/bin:/product/bin:/sbin");
            env.put("TMPDIR", tmp.getPath()); env.put("HOME", tmp.getPath());
            env.put("ANDROID_DATA", "/data"); env.put("ANDROID_ROOT", "/system");
            env.put("VM_FAKE_ROOT", "1"); env.put("VM_SANDBOX", "1");
            env.put("LD_LIBRARY_PATH", "/system/lib64:/system/lib");
            pb.redirectErrorStream(true);
            process = pb.start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) emit(line + "\n", collector, listener);
            }
            int exit = process.waitFor();
            return new ScriptResult(exit, collector == null ? "" : collector.toString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String message = "Script failed: " + e.getMessage() + "\n";
            emit(message, collector, listener);
            return new ScriptResult(-1, message);
        } finally { if (process != null) process.destroy(); }
    }
    private static void emit(String text, StringBuilder collector, ScriptOutputListener listener) {
        if (collector != null) synchronized (collector) {
            // Bound retained console history for long-running scripts.
            if (collector.length() > 1048576) collector.delete(0, collector.length() - 524288);
            collector.append(text);
        }
        if (listener != null) listener.onOutput(text);
    }

    private static synchronized void createSuWrapper() {
        try {
            File bin = new File(BEnvironment.getLocalTmpDir(), "bin");
            ModuleFiles.mkdir(bin);
            File su = new File(bin, "su");
            String body = "#!/system/bin/sh\n# Compatibility shell; no real privilege elevation.\n"
                    + "if [ \"$1\" = \"-c\" ] || [ \"$1\" = \"--command\" ]; then\n"
                    + "  shift; exec /system/bin/sh -c \"$*\"\n"
                    + "elif [ \"$1\" = \"-v\" ] || [ \"$1\" = \"--version\" ]; then\n"
                    + "  printf 'su\\nMagisk SU 25.2:MAGISKSU\\n'; exit 0\n"
                    + "elif [ \"$1\" = \"-V\" ]; then printf '25200\\n'; exit 0\nfi\n"
                    + "case \"$1\" in 0|root|-|--) shift;; esac\n"
                    + "if [ \"$#\" -gt 0 ]; then exec /system/bin/sh -c \"$*\"; else exec /system/bin/sh; fi\n";
            try (OutputStream out = new FileOutputStream(su)) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            Os.chmod(su.getPath(), 0700);
        } catch (Exception e) { Slog.w(TAG, "su wrapper: " + e.getMessage()); }
    }
    private static void chmodRecursive(File file) {
        if (file.isDirectory()) {
            try { Os.chmod(file.getPath(), 0700); } catch (Exception ignored) {}
            File[] children = file.listFiles();
            if (children != null) for (File child : children) chmodRecursive(child);
        } else {
            String name = file.getName().toLowerCase(Locale.ROOT);
            boolean executable = name.endsWith(".sh") || name.endsWith(".bin") || name.endsWith(".elf") || !name.contains(".");
            try { Os.chmod(file.getPath(), executable ? 0700 : 0600); } catch (Exception ignored) {}
        }
    }
    public static boolean deleteImported(File target) {
        try {
            if (target == null || !target.getCanonicalPath().startsWith(BEnvironment.getLocalTmpDir().getCanonicalPath() + File.separator)) return false;
            return deleteRecursive(target);
        } catch (IOException e) { return false; }
    }
    private static boolean deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        return file.delete();
    }
    private static String sanitizeName(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_");
        if (clean.isEmpty() || clean.equals(".")) return "script.sh";
        return clean.length() > 160 ? clean.substring(clean.length() - 160) : clean;
    }
}
