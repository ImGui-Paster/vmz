package top.niunaijun.blackbox.core.env;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.utils.DiagnosticLogger;
import top.niunaijun.blackbox.utils.RootLogger;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Firebase in a virtual process cannot run the real
 * {@code FirebaseInitProvider.onCreate}: that builds the full component graph
 * including Firebase Performance and deadlocks the main thread (Root Checker
 * black screen). Skipping the provider unblocks bind, but later
 * {@code FirebaseApp.getInstance()} then throws
 * {@code Default FirebaseApp is not initialized} — Root Checker "check device"
 * looks like a freeze and dies on the click handler.
 *
 * <p>Fix: strip Performance (and similar) from {@link ApplicationInfo#metaData}
 * so {@code FirebaseApp.initializeApp} does not discover the deadlock registrar,
 * then call {@code initializeApp(Context)} via reflection. No Firebase classes
 * are on the host compile classpath.
 */
public final class FirebaseCompat {
    public static final String TAG = "FirebaseCompat";

    private FirebaseCompat() {
    }

    public static void stripUnsafeMetadata(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return;
        }
        stripUnsafeMetadata(packageInfo.applicationInfo);
    }

    public static void stripUnsafeMetadata(ApplicationInfo info) {
        if (info == null) {
            return;
        }
        stripBundle(info.metaData);
    }

    public static void stripBundle(Bundle meta) {
        if (meta == null || meta.isEmpty()) {
            return;
        }
        ArrayList<String> toRemove = new ArrayList<>();
        for (String key : meta.keySet()) {
            if (isUnsafeFirebaseMetadataKey(key)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            try {
                meta.remove(key);
            } catch (Throwable ignored) {
            }
        }
        if (!toRemove.isEmpty()) {
            Slog.w(TAG, "stripped unsafe Firebase metadata: " + toRemove);
            DiagnosticLogger.w(TAG, "stripped unsafe Firebase metadata: " + toRemove);
        }
    }

    public static boolean isUnsafeFirebaseMetadataKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        if (!lower.contains("firebase")) {
            return false;
        }
        return lower.contains("perf")
                || lower.contains("firebaseperf")
                || lower.contains("performance");
    }

    /**
     * Lightweight Firebase default-app init. Safe to call when the cloned APK
     * has no Firebase at all.
     *
     * <p>Root Checker (com.joeykrim.rootcheck) ships an R8-fully-obfuscated
     * build: {@code com.google.firebase.FirebaseApp} does NOT exist under that
     * name (its "Default FirebaseApp is not initialized" IllegalStateException
     * comes from a renamed class, e.g. {@code ai.for}), so the previous
     * single-name lookup reported CNFE and silently did nothing. The app then
     * crashed in its own analytics logging (auto-check AsyncTask ~90 ms after
     * bind, and again in the "Verify Root" click handler).
     *
     * <p>Strategy:
     * <ol>
     *   <li>probe several classloader candidates for
     *       {@code com.google.firebase.FirebaseApp} and call
     *       {@code initializeApp(Context)} when found;</li>
     *   <li>otherwise fall back to instantiating
     *       {@code com.google.firebase.provider.FirebaseInitProvider} — its
     *       manifest name survives even fully obfuscated APKs — and run its
     *       {@code attachInfo + onCreate}, i.e. the exact code the engine skips
     *       during bind, but on our terms: Firebase Performance metadata is
     *       already stripped (see {@link #stripUnsafeMetadata}), so the
     *       original Firebase Performance bind deadlock cannot come back.</li>
     * </ol>
     */
    public static void initializeAppSafely(Context context) {
        if (context == null) {
            return;
        }
        try {
            stripUnsafeMetadata(context.getApplicationInfo());
        } catch (Throwable ignored) {
        }
        DiagnosticLogger.Scope scope = DiagnosticLogger.scope("FirebaseApp.initializeApp");
        try {
            ClassLoader[] candidates = candidateClassLoaders(context);
            // 1) Direct FirebaseApp init (no component provider machinery).
            for (ClassLoader cl : candidates) {
                Class<?> firebaseApp = findClass("com.google.firebase.FirebaseApp", cl);
                if (firebaseApp == null) {
                    continue;
                }
                DiagnosticLogger.i(TAG, "FirebaseApp found via " + describe(cl));
                if (initFirebaseApp(firebaseApp, context)) {
                    return;
                }
                break;
            }
            // 2) Obfuscated APK: run the app's own FirebaseInitProvider, whose
            //    manifest-preserved class internally calls the renamed
            //    FirebaseApp.initializeApp.
            for (ClassLoader cl : candidates) {
                Class<?> provider = findClass("com.google.firebase.provider.FirebaseInitProvider", cl);
                if (provider == null) {
                    continue;
                }
                DiagnosticLogger.i(TAG, "FirebaseInitProvider found via " + describe(cl));
                if (runFirebaseInitProvider(provider, context)) {
                    return;
                }
                break;
            }
            DiagnosticLogger.i(TAG, "no Firebase in this APK");
            Slog.i(TAG, "no Firebase in this APK");
            // No Firebase classes found at all. If the app later crashes with
            // "Default FirebaseApp is not initialized", it ships its own renamed
            // copy that our classloaders could not see - this line is the marker
            // to look for in root_diag_*.log when diagnosing such a crash.
            RootLogger.w(TAG, "no Firebase classes found via " + candidates.length
                    + " classloaders - if the app crashes with 'Default FirebaseApp is not"
                    + " initialized', its Firebase copy is renamed beyond our probes");
        } finally {
            try {
                scope.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static ClassLoader[] candidateClassLoaders(Context context) {
        ArrayList<ClassLoader> list = new ArrayList<>();
        tryAdd(list, context.getClassLoader());
        try {
            Context appContext = context.getApplicationContext();
            if (appContext != null && appContext != context) {
                tryAdd(list, appContext.getClassLoader());
            }
        } catch (Throwable ignored) {
        }
        tryAdd(list, context.getClass().getClassLoader());
        tryAdd(list, Thread.currentThread().getContextClassLoader());
        tryAdd(list, loadedApkClassLoader(context));
        tryAdd(list, rebuiltClassLoader(context));
        return list.toArray(new ClassLoader[0]);
    }

    private static void tryAdd(ArrayList<ClassLoader> list, ClassLoader cl) {
        if (cl != null && !list.contains(cl)) {
            list.add(cl);
        }
    }

    private static String describe(ClassLoader cl) {
        if (cl == null) {
            return "<null>";
        }
        return cl.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(cl));
    }

    private static Class<?> findClass(String name, ClassLoader cl) {
        if (cl == null) {
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reflection lookup of the real {@code LoadedApk.mClassLoader} behind the
     * context — the loader ActivityThread itself uses for app classes.
     */
    private static ClassLoader loadedApkClassLoader(Context context) {
        try {
            Context impl = context;
            int depth = 0;
            while (impl instanceof android.content.ContextWrapper) {
                impl = ((android.content.ContextWrapper) impl).getBaseContext();
                if (++depth > 10) {
                    break;
                }
            }
            if (impl == null) {
                return null;
            }
            Object loadedApk = getFieldValue(impl, "mPackageInfo");
            if (loadedApk == null) {
                return null;
            }
            Object cl = getFieldValue(loadedApk, "mClassLoader");
            return cl instanceof ClassLoader ? (ClassLoader) cl : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getFieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Last-resort loader built straight over the virtual APK path(s). */
    private static ClassLoader rebuiltClassLoader(Context context) {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info == null || info.sourceDir == null) {
                return null;
            }
            StringBuilder paths = new StringBuilder(info.sourceDir);
            if (info.splitSourceDirs != null) {
                for (String split : info.splitSourceDirs) {
                    if (split != null && !split.isEmpty()) {
                        paths.append(':').append(split);
                    }
                }
            }
            ClassLoader parent = context.getClassLoader();
            return new dalvik.system.PathClassLoader(paths.toString(),
                    parent != null ? parent : ClassLoader.getSystemClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean initFirebaseApp(Class<?> firebaseApp, Context context) {
        try {
            try {
                Method getApps = firebaseApp.getMethod("getApps", Context.class);
                Object apps = getApps.invoke(null, context);
                if (apps instanceof List && !((List<?>) apps).isEmpty()) {
                    DiagnosticLogger.i(TAG, "FirebaseApp already initialized, skip");
                    Slog.i(TAG, "FirebaseApp already initialized, skip");
                    return true;
                }
            } catch (Throwable ignored) {
            }
            Method initializeApp = firebaseApp.getMethod("initializeApp", Context.class);
            Object app = initializeApp.invoke(null, context);
            DiagnosticLogger.i(TAG, "FirebaseApp.initializeApp " + (app != null ? "ok" : "returned null"));
            Slog.i(TAG, "FirebaseApp.initializeApp " + (app != null ? "ok" : "returned null"));
            return app != null;
        } catch (Throwable t) {
            Slog.w(TAG, "FirebaseApp.initializeApp failed: " + t);
            DiagnosticLogger.w(TAG, "FirebaseApp.initializeApp failed: " + t);
            return false;
        }
    }

    /**
     * Runs the app's own {@code FirebaseInitProvider} logic without going
     * through the ActivityThread provider machinery (which is where the
     * original bind deadlock lived).
     */
    private static boolean runFirebaseInitProvider(Class<?> providerClass, Context context) {
        try {
            Object provider = providerClass.newInstance();
            try {
                Method attach = providerClass.getMethod("attachInfo", Context.class,
                        android.content.pm.ProviderInfo.class);
                android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
                info.name = providerClass.getName();
                info.packageName = context.getPackageName();
                attach.invoke(provider, context, info);
            } catch (Throwable t) {
                DiagnosticLogger.w(TAG, "FirebaseInitProvider.attachInfo failed: " + t);
            }
            Method onCreate = providerClass.getMethod("onCreate");
            Object result = onCreate.invoke(provider);
            DiagnosticLogger.i(TAG, "FirebaseInitProvider.onCreate -> " + result);
            Slog.i(TAG, "FirebaseInitProvider.onCreate -> " + result);
            return true;
        } catch (Throwable t) {
            Slog.w(TAG, "FirebaseInitProvider.onCreate failed: " + t);
            DiagnosticLogger.w(TAG, "FirebaseInitProvider.onCreate failed: " + t);
            return false;
        }
    }
}
