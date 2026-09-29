import top.niunaijun.blackbox.script.ModuleFiles;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Host-JVM regression tests. Never loads or executes a supplied native library. */
public class NativeModuleFilesTest {
    private static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    static void rejects(Checked task, String label) throws Exception {
        try { task.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Accepted " + label);
    }
    static byte[] elf(int cls, int machine) {
        byte[] h = new byte[64]; h[0]=127; h[1]='E'; h[2]='L'; h[3]='F';
        h[4]=(byte)cls; h[5]=1; h[6]=1; h[16]=3; h[18]=(byte)machine; return h;
    }
    static File zip(Path dir, String... names) throws Exception {
        File zip = Files.createTempFile(dir, "test", ".zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String name : names) { out.putNextEntry(new ZipEntry(name)); if (!name.endsWith("/")) out.write(elf(2,183)); out.closeEntry(); }
        }
        return zip;
    }
    static File empty(Path dir) throws Exception { return Files.createTempDirectory(dir, "out").toFile(); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("module-regression-");
        try {
            File out = empty(dir);
            ModuleFiles.unzip(zip(dir, "payload/", "payload/libPUBGM.so", "zygisk/arm64-v8a.so", "webroot/index.html"), out);
            check(new File(out,"payload/libPUBGM.so").isFile(), "nested payload retained");
            check(new File(out,"zygisk/arm64-v8a.so").isFile(), "nested stub retained");
            check(!new File(out,"payload_libPUBGM.so").exists(), "not flattened");
            check(ModuleFiles.elfClass(new File(out,"payload/libPUBGM.so"))==2, "ARM64 ABI");
            for (String name : new String[]{"../escape", "/absolute", "a/../../escape", "a/./b", "a\\b", "C:/escape"}) {
                rejects(() -> ModuleFiles.unzip(zip(dir,name),empty(dir)), name);
            }
            rejects(() -> ModuleFiles.unzip(zip(dir,"a//b", "a/b"), empty(dir)), "canonical duplicate");
            rejects(() -> ModuleFiles.unzip(zip(dir,"a", "a/b"), empty(dir)), "file/directory conflict");
            rejects(() -> ModuleFiles.unzip(zip(dir,"x"), out), "nonempty extraction root");
            rejects(() -> ModuleFiles.unzip(zip(dir), empty(dir)), "empty ZIP");
            File invalid = Files.createTempFile(dir,"invalid",".zip").toFile();
            Files.write(invalid.toPath(), "not a ZIP".getBytes("UTF-8"));
            rejects(() -> ModuleFiles.unzip(invalid,empty(dir)), "invalid ZIP");
            File linkRoot = empty(dir);
            Files.createSymbolicLink(new File(linkRoot,"link").toPath(),dir);
            rejects(() -> ModuleFiles.child(linkRoot,"link/escape"), "symlink escape");
            File arm32 = new File(out,"arm32.so"); Files.write(arm32.toPath(),elf(1,40));
            check(ModuleFiles.elfClass(arm32)==1,"ARM32 ABI");
            File x86 = new File(out,"x86.so"); Files.write(x86.toPath(),elf(2,62));
            rejects(() -> ModuleFiles.elfClass(x86),"wrong machine");
            File shortFile = new File(out,"short.so"); Files.write(shortFile.toPath(),new byte[4]);
            rejects(() -> ModuleFiles.elfClass(shortFile),"truncated ELF");
            check(ModuleFiles.validPackage("com.tencent.ig"),"valid package");
            check(!ModuleFiles.validPackage("../com.tencent.ig"),"invalid package");
            Properties rule = new Properties();
            rule.setProperty("package","com.target.game"); rule.setProperty("process","com.target.game"); rule.setProperty("user","2");
            check(ModuleFiles.matches(rule,"com.target.game","com.target.game",2),"exact scope");
            check(!ModuleFiles.matches(rule,"com.target.game","com.target.game",0),"other user rejected");
            check(!ModuleFiles.matches(rule,"com.target.game","com.target.game:service",2),"other process rejected");
            check(!ModuleFiles.matches(rule,"com.target.game2","com.target.game",2),"substring rejected");
            File stored = new File(out,"rule.properties"); ModuleFiles.write(stored,rule);
            check(ModuleFiles.read(stored).equals(rule),"atomic rule roundtrip");
            rule.setProperty("phase","before_on_create"); ModuleFiles.write(stored,rule);
            check(ModuleFiles.read(stored).equals(rule),"atomic replacement");
            Properties manifest = new Properties(); manifest.setProperty("id","test"); manifest.setProperty("library","payload/libPUBGM.so");
            ModuleFiles.write(new File(out,"native-module.properties"),manifest);
            check(ModuleFiles.inspect(out).adapter.equals("native"),"native manifest");
            manifest.setProperty("library","../outside.so"); ModuleFiles.write(new File(out,"native-module.properties"),manifest);
            rejects(() -> ModuleFiles.inspect(out),"manifest traversal");
            if (args.length > 0) {
                File magic = empty(dir);
                ModuleFiles.unzip(new File(args[0]),magic);
                ModuleFiles.Spec spec = ModuleFiles.inspect(magic);
                check(spec.adapter.equals("magicpro-payload"),"actual archive adapter");
                check("1".equals(spec.targets.getProperty("com.tencent.ig")),"actual archive target");
                check(ModuleFiles.sha256(spec.library).equals("fb8e0f7edbd780c49d30ca04e58a2a59119e77d66049e029dc8fbaa347f871c0"),"actual payload unchanged");
                check(new File(magic,"META-INF/com/google/android/update-binary").isFile(),"installer path retained");
            }
            System.out.println("PASS: " + checks + " checks; no native code executed");
        } finally {
            try (java.util.stream.Stream<Path> paths=Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {try {Files.delete(p);} catch(IOException e){throw new UncheckedIOException(e);}});
            }
        }
    }
}
