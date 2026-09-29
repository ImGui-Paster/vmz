package top.niunaijun.blackbox.fake.service.base;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.MethodHook;


public class UidMethodProxy extends MethodHook {
    private final int index;
    private final String name;

    public UidMethodProxy(String name, int index) {
        this.index = index;
        this.name = name;
    }

    @Override
    protected String getMethodName() {
        return name;
    }

    @Override
    protected Object hook(Object who, Method method, Object[] args) throws Throwable {
        try {
            if (args != null && index >= 0 && index < args.length && args[index] instanceof Integer) {
                int uid = (int) args[index];
                if (uid == BActivityThread.getBUid() || uid == BActivityThread.getUid()) {
                    args[index] = BlackBoxCore.getHostUid();
                }
            }
            return method.invoke(who, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (isPrivilegedDenied(cause)) {
                return defaultValue(method);
            }
            throw cause != null ? cause : e;
        } catch (SecurityException e) {
            return defaultValue(method);
        }
    }

    private static boolean isPrivilegedDenied(Throwable t) {
        return t instanceof SecurityException;
    }

    private static Object defaultValue(Method method) {
        Class<?> ret = method.getReturnType();
        if (ret == void.class || ret == Void.class) {
            return 0;
        }
        if (ret == boolean.class) {
            return false;
        }
        if (ret == int.class || ret == long.class || ret == float.class || ret == double.class
                || ret == short.class || ret == byte.class || ret == char.class) {
            return 0;
        }
        return null;
    }
}
