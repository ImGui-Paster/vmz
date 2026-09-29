package top.niunaijun.blackbox.closecode;

import android.app.Application;
import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.script.NativeModuleManager;

/** Register before guest binding. No native library is loaded into the host Application. */
public class Entry {
    private static final AtomicBoolean attached = new AtomicBoolean();
    public static void attach() {
        if (!attached.compareAndSet(false, true)) return;
        BlackBoxCore.get().addAppLifecycleCallback(new AppLifecycleCallback() {
            @Override public void beforeCreateApplication(String pkg, String process, Context context, int user) {
                NativeModuleManager.load(context, pkg, process, user, "before_create");
            }
            @Override public void beforeApplicationOnCreate(String pkg, String process, Application app, int user) {
                NativeModuleManager.load(app, pkg, process, user, "before_on_create");
            }
            @Override public void afterApplicationOnCreate(String pkg, String process, Application app, int user) {
                NativeModuleManager.load(app, pkg, process, user, "after_on_create");
            }
        });
    }
}
