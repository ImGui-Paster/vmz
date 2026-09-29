package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import black.android.os.BRINetworkManagementServiceStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public class INetworkManagementServiceProxy extends BinderInvocationStub {
    public static final String NAME = "network_management";

    public INetworkManagementServiceProxy() {
        super(BRServiceManager.get().getService(NAME));
    }

    @Override
    protected Object getWho() {
        return BRINetworkManagementServiceStub.get().asInterface(BRServiceManager.get().getService(NAME));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        // These NMS methods require NETWORK_STACK / MAINLINE_NETWORK_STACK.
        // Virtual apps never hold that permission; forwarding (even after a uid
        // rewrite) throws SecurityException. AppLovin / StrictMode.setVmPolicy
        // call setUidCleartextNetworkPolicy from Activity.onCreate — the
        // undeclared exception kills the activity (black screen).
        // Stub as no-ops. Void methods ignore the returned value.
        addMethodHook(new ValueMethodProxy("setUidCleartextNetworkPolicy", 0));
        addMethodHook(new ValueMethodProxy("setUidMeteredNetworkBlacklist", 0));
        addMethodHook(new ValueMethodProxy("setUidMeteredNetworkWhitelist", 0));
        addMethodHook(new ValueMethodProxy("setFirewallUidRule", 0));
        addMethodHook(new ValueMethodProxy("setFirewallUidRules", 0));
        addMethodHook(new ValueMethodProxy("setUidOnMeteredNetworkList", 0));
    }

    @ProxyMethod("getNetworkStatsUidDetail")
    public static class getNetworkStatsUidDetail extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstUid(args);
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
}
