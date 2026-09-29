package top.niunaijun.blackbox.script;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** File-format code kept Android-free so it can be regression-tested on the JVM. */
public final class ModuleFiles {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    private ModuleFiles() {}

    public static File child(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.startsWith("/")
                || relative.contains("\\") || relative.contains(":")) {
            throw new IOException("Invalid relative path: " + relative);
        }
        for (String part : relative.split("/", -1)) {
            if (part.equals("..") || part.equals(".") || part.indexOf('\0') >= 0)
                throw new IOException("Unsafe path: " + relative);
        }
        File file = new File(root, relative).getCanonicalFile();
        if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Path escapes root: " + relative);
        return file;
    }

    /** Extract ONLY into a new, empty private staging directory. Never runs archive code. */
    public static void unzip(File zip, File target) throws IOException {
        if (!target.isDirectory() || Objects.requireNonNull(target.list()).length != 0)
            throw new IOException("Extraction needs an empty staging directory");
        Set<String> seen = new HashSet<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > 4096) throw new IOException("Too many ZIP entries");
                String name = entry.getName();
                if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                File out = child(target, name);
                if (!seen.add(out.getCanonicalPath())) throw new IOException("Duplicate ZIP path: " + name);
                if (entry.isDirectory()) {
                    ModuleFiles.mkdir(out);
                } else {
                    ModuleFiles.mkdir(out.getParentFile());
                    if (entry.getSize() > MAX_BYTES) throw new IOException("ZIP entry too large");
                    try (OutputStream stream = new FileOutputStream(out)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            total += n;
                            if (total > MAX_BYTES) throw new IOException("Expanded ZIP exceeds 256 MiB");
                            stream.write(buffer, 0, n);
                        }
                    }
                }
                in.closeEntry();
            }
        }
        if (entries == 0) throw new IOException("Empty or invalid ZIP");
    }

    public static void mkdir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > MAX_BYTES) throw new IOException("File exceeds 256 MiB");
            out.write(buffer, 0, n);
        }
    }

    public static Properties read(File file) throws IOException {
        if (!file.isFile() || file.length() > 65536) throw new IOException("Missing/oversized config: " + file);
        Properties p = new Properties();
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    public static void write(File file, Properties p) throws IOException {
        mkdir(file.getParentFile());
        File tmp = File.createTempFile(".write-", ".tmp", file.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                p.store(writer, "BlackBox native module");
                writer.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) throw new IOException("Atomic rename failed: " + file);
        } finally { tmp.delete(); }
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : digest.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }

    public static int elfClass(File file) throws IOException {
        byte[] h = new byte[20];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) { in.readFully(h); }
        if (h[0] != 127 || h[1] != 'E' || h[2] != 'L' || h[3] != 'F'
                || h[5] != 1 || h[6] != 1 || h[16] != 3 || h[17] != 0)
            throw new IOException("Expected a little-endian ELF shared library");
        int machine = (h[18] & 255) | ((h[19] & 255) << 8);
        if ((h[4] == 2 && machine == 183) || (h[4] == 1 && machine == 40)) return h[4];
        throw new IOException("Unsupported ABI: ARM32/ARM64 only");
    }

    public static boolean validPackage(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    public static boolean matches(Properties rule, String pkg, String process, int user) {
        return pkg != null && pkg.equals(rule.getProperty("package"))
                && process != null && process.equals(rule.getProperty("process"))
                && Integer.toString(user).equals(rule.getProperty("user"));
    }

    public static final class Spec {
        public final File root, library;
        public final String adapter, id;
        public final Properties targets;
        private Spec(File root, File library, String adapter, String id, Properties targets) {
            this.root = root; this.library = library; this.adapter = adapter; this.id = id; this.targets = targets;
        }
    }

    public static Spec inspect(File root) throws IOException {
        File manifest = new File(root, "native-module.properties");
        if (manifest.isFile()) {
            Properties p = read(manifest);
            String id = p.getProperty("id", "native");
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("Invalid module id");
            File library = child(root, p.getProperty("library"));
            elfClass(library);
            return new Spec(root, library, "native", id, new Properties());
        }
        File prop = new File(root, "module.prop");
        if (prop.isFile() && "Magic".equals(read(prop).getProperty("id"))) {
            File library = child(root, "payload/libPUBGM.so");
            if (elfClass(library) != 2) throw new IOException("MagicPro payload must be ARM64");
            return new Spec(root, library, "magicpro-payload", "Magic", read(child(root, "inject.conf")));
        }
        throw new IOException("No supported native-module.properties or MagicPro payload adapter; this is not a Zygisk runtime");
    }

    public static boolean isModule(File root) {
        return root.isDirectory() && (new File(root, "module.prop").isFile()
                || new File(root, "native-module.properties").isFile() || new File(root, "zygisk").isDirectory());
    }
}
