package top.niunaijun.blackboxa.view.script;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.util.*;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.script.ModuleFiles;
import top.niunaijun.blackbox.script.NativeModuleManager;
import top.niunaijun.blackbox.script.NativeModuleLauncher;

/** Uses existing Script Runner; no exported component or additional Android permission. */
public final class NativeModulesDialog {
    public interface Output { void append(String text); }
    public interface Runner { void run(File file); }
    private interface Work { String run() throws Exception; }
    private NativeModulesDialog() {}

    public static boolean open(Activity activity, File selected, Output output, Runner runner) {
        if (!selected.isDirectory()) return false;
        if (!ModuleFiles.isModule(selected)) {
            File[] children = selected.listFiles(f -> !f.getName().startsWith(".")
                    && (f.isDirectory() || f.getName().endsWith(".sh")));
            if (children == null || children.length == 0) { output.append("No script or supported module in this directory.\n"); return true; }
            Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
            String[] names = new String[children.length];
            for (int i = 0; i < children.length; i++) names[i] = children[i].getName();
            new AlertDialog.Builder(activity).setTitle(selected.getName()).setItems(names, (d, which) -> {
                File file = children[which];
                if (!open(activity, file, output, runner)) runner.run(file);
            }).setNegativeButton("Cancel", null).show();
            return true;
        }
        new AlertDialog.Builder(activity).setTitle("Native module")
                .setItems(new String[]{"Включить модуль и запустить игру", "Last load status", "Disable for a guest"},
                        (d, action) -> chooseTarget(activity, selected, action, output))
                .setNegativeButton("Cancel", null).show();
        return true;
    }

    private static void chooseTarget(Activity activity, File source, int action, Output output) {
        new Thread(() -> {
            try {
                List<BUserInfo> users = BlackBoxCore.get().getUsers();
                if (users == null || users.isEmpty()) throw new IllegalStateException("No virtual users available");
                String[] labels = new String[users.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = "User " + users.get(i).id;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    new AlertDialog.Builder(activity).setTitle("Virtual user").setItems(labels,
                            (d, which) -> chooseApp(activity, source, users.get(which).id, action, output)).show();
                });
            } catch (Exception e) { publish(activity, output, "Module error: " + e.getMessage() + "\n"); }
        }, "ModuleTargets").start();
    }

    private static void chooseApp(Activity activity, File source, int user, int action, Output output) {
        new Thread(() -> {
            try {
                ModuleFiles.Spec spec = action == 0 ? ModuleFiles.inspect(source) : null;
                List<ApplicationInfo> apps = BlackBoxCore.get().getInstalledApplications(0, user);
                List<String> packages = new ArrayList<>();
                if (apps != null) for (ApplicationInfo app : apps) {
                    if (app.packageName.equals(BlackBoxCore.getHostPkg())) continue;
                    if (spec != null && spec.adapter.equals("magicpro-payload") && !"1".equals(spec.targets.getProperty(app.packageName))) continue;
                    packages.add(app.packageName);
                }
                Collections.sort(packages);
                if (packages.isEmpty()) throw new IllegalStateException("No matching installed guest; install the target inside this virtual user first");
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    new AlertDialog.Builder(activity).setTitle("Target guest / user " + user)
                            .setItems(packages.toArray(new String[0]), (d, which) -> {
                                String pkg = packages.get(which);
                                if (action == 1) work(activity, output, () -> NativeModuleManager.status(pkg, user));
                                else if (action == 2) work(activity, output, () -> NativeModuleManager.disable(pkg, user));
                                else confirm(activity, source, pkg, user, output);
                            }).show();
                });
            } catch (Exception e) { publish(activity, output, "Module error: " + e.getMessage() + "\n"); }
        }, "ModulePackages").start();
    }

    private static void confirm(Activity activity, File source, String pkg, int user, Output output) {
        new AlertDialog.Builder(activity).setTitle("Включить модуль и запустить " + pkg + "?")
                .setMessage("Only enable code you trust. Guest processes share the host application's OS identity; this is not a security boundary. Native code may crash the guest.\n\nMagicPro uses an experimental payload-only adapter, NOT Zygisk. Module shell scripts and key_gate.sh are not run.\n\nПосле выбора фазы модуль будет включён, а выбранная игра автоматически запущена ВНУТРИ BlackBox, у пользователя " + user + ". Если игра уже работает, она будет остановлена и запущена заново. Несохранённый прогресс может быть потерян. Данные приложения не очищаются. Другие виртуальные пользователи не затрагиваются.")
                .setNegativeButton("Cancel", null).setPositiveButton("Choose load phase", (d, w) -> {
                    String[] phases = {"before_on_create", "before_create", "after_on_create"};
                    new AlertDialog.Builder(activity).setTitle("Load phase")
                            .setItems(new String[]{"Before Application.onCreate (default)", "Before Application creation", "After Application.onCreate"},
                                    (dialog, index) -> work(activity, output,
                                            () -> NativeModuleLauncher.enableAndLaunch(source, pkg, user, phases[index]))).show();
                }).show();
    }
    private static void work(Activity activity, Output output, Work task) {
        new Thread(() -> {
            try { publish(activity, output, task.run()); }
            catch (Exception e) { publish(activity, output, "Module error: " + e.getMessage() + "\n"); }
        }, "NativeModuleConfig").start();
    }
    private static void publish(Activity activity, Output output, String text) {
        activity.runOnUiThread(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) output.append(text); });
    }
}
