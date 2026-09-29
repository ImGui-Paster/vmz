#!/usr/bin/env python3
"""Compile the real launch coordinator with test doubles; no Android/device/native code runs."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
fixtures = {
    'top/niunaijun/blackbox/core/system/pm/IBPackageManagerService.java': '''
package top.niunaijun.blackbox.core.system.pm;
public interface IBPackageManagerService {
 boolean isInstalled(String pkg, int user) throws Exception;
 void stopPackage(String pkg, int user) throws Exception;
}
''',
    'top/niunaijun/blackbox/BlackBoxCore.java': '''
package top.niunaijun.blackbox;
import java.util.*;
import top.niunaijun.blackbox.core.system.pm.IBPackageManagerService;
public class BlackBoxCore {
 public static boolean main=true, available=true, installed=true, intent=true, arm=true, stop=true, launch=true;
 public static final List<String> events=new ArrayList<>();
 public static void reset() {main=available=installed=intent=arm=stop=launch=true; events.clear();}
 public static BlackBoxCore get(){return new BlackBoxCore();}
 public boolean isMainProcess(){return main;}
 public static String getHostPkg(){return "vm.host";}
 public static Pm getBPackageManager(){return new Pm();}
 public boolean launchApk(String pkg,int user){events.add("launch:"+pkg+":"+user);return launch;}
 public static class Pm {
  public Object getLaunchIntentForPackage(String pkg,int user){return intent?new Object():null;}
  public IBPackageManagerService getServiceWithFallback(){
   if(!available)return null;
   return new IBPackageManagerService(){
    public boolean isInstalled(String pkg,int user){return installed;}
    public void stopPackage(String pkg,int user) throws Exception {
     events.add("stop:"+pkg+":"+user); if(!stop)throw new Exception("stop failed");
    }
   };
  }
 }
}
''',
    'top/niunaijun/blackbox/script/NativeModuleManager.java': '''
package top.niunaijun.blackbox.script;
import java.io.File;
import top.niunaijun.blackbox.BlackBoxCore;
public class NativeModuleManager {
 public static String enable(File f,String p,int u,String phase) throws Exception {
  BlackBoxCore.events.add("arm:"+p+":"+u+":"+phase);
  if(!BlackBoxCore.arm)throw new Exception("arm failed");return "ARMED";
 }
}
''',
    'top/niunaijun/blackbox/utils/DiagnosticLogger.java': '''
package top.niunaijun.blackbox.utils;
public class DiagnosticLogger {public static void i(String t,String m){} public static void w(String t,String m){}}
''',
    'LaunchTest.java': '''
import java.io.File;
import java.util.Arrays;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.script.NativeModuleLauncher;
public class LaunchTest {
 static int checks;
 interface Work {void run() throws Exception;}
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
 static String run() throws Exception {return NativeModuleLauncher.enableAndLaunch(new File("sample"),"com.target.game",2,"before_on_create");}
 static String failure(Work w) throws Exception {
  try{w.run();}catch(Exception expected){checks++;return expected.getMessage();}
  throw new AssertionError("Expected failure");
 }
 static void events(String... e){check(BlackBoxCore.events.equals(Arrays.asList(e)),"operation order/scope: "+BlackBoxCore.events);}
 public static void main(String[] args) throws Exception {
  BlackBoxCore.reset();check(run().startsWith("LAUNCH_REQUESTED:"),"success is a request, not library-loaded");
  events("arm:com.target.game:2:before_on_create","stop:com.target.game:2","launch:com.target.game:2");
  BlackBoxCore.reset();BlackBoxCore.available=false;failure(()->run());events();
  BlackBoxCore.reset();BlackBoxCore.installed=false;failure(()->run());events();
  BlackBoxCore.reset();BlackBoxCore.intent=false;failure(()->run());events();
  BlackBoxCore.reset();BlackBoxCore.arm=false;failure(()->run());events("arm:com.target.game:2:before_on_create");
  BlackBoxCore.reset();BlackBoxCore.stop=false;
  check(failure(()->run()).contains("remains enabled"),"stop failure preserves activation and explains it");
  events("arm:com.target.game:2:before_on_create","stop:com.target.game:2");
  BlackBoxCore.reset();BlackBoxCore.launch=false;
  check(failure(()->run()).contains("remains enabled"),"launch failure does not claim success");
  events("arm:com.target.game:2:before_on_create","stop:com.target.game:2","launch:com.target.game:2");
  BlackBoxCore.reset();BlackBoxCore.main=false;failure(()->run());events();
  BlackBoxCore.reset();failure(()->NativeModuleLauncher.enableAndLaunch(new File("x"),"vm.host",0,"before_on_create"));events();
  BlackBoxCore.reset();failure(()->NativeModuleLauncher.enableAndLaunch(new File("x"),"../outside",0,"before_on_create"));events();
  BlackBoxCore.reset();failure(()->NativeModuleLauncher.enableAndLaunch(new File("x"),"com.target.game",-1,"before_on_create"));events();
  BlackBoxCore.reset();check(run().startsWith("LAUNCH_REQUESTED:"),"pending guard released after failure");
  System.out.println("PASS: "+checks+" launch-coordinator checks (test doubles; no device execution)");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='native-launch-test-') as tmp:
    dest = Path(tmp)
    files = []
    for name, content in fixtures.items():
        path = dest / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        files.append(str(path))
    base = root / 'Bcore/src/main/java/top/niunaijun/blackbox/script'
    files += [str(base / 'ModuleFiles.java'), str(base / 'NativeModuleLauncher.java')]
    subprocess.run(['java', '--add-modules', 'jdk.compiler', 'com.sun.tools.javac.Main',
                    '--release', '8', '-d', str(dest), *files], check=True)
    subprocess.run(['java', '-cp', str(dest), 'LaunchTest'], check=True)
