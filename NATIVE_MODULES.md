# Guest native modules: first implementation

## Scope

This adds **cooperative, in-process native loading on the next guest start**, not a privileged injector daemon. It does not grant host root, change SELinux, implement Magisk/Zygisk, or make arbitrary APK/ELF injectors work unchanged. BlackBox guests share the host application's OS identity; approved native code must be trusted. Java exception handling cannot contain native crashes or prevent a loaded module from accessing anything the host UID can access.

## Using the existing Script Runner

1. Import the ZIP again. Archives imported by the old version had flattened paths and cannot be repaired by this change.
2. Tap the imported directory. For a ZIP with one enclosing directory, open that directory first.
3. A recognized module opens **Native module** actions instead of executing its scripts.
4. Choose **Включить модуль и запустить игру** (Enable module and launch game), select the virtual user and installed package, confirm the restart warning, then choose a load phase. The default is **Before Application.onCreate** (after Application construction and provider initialization).
5. The module rule is saved first, then only that package in that virtual user is stopped and launched through BlackBox automatically. Existing gameplay is interrupted; unsaved progress may be lost. App data is not cleared. Only the process named exactly like the selected package is targeted; secondary/custom-named processes are not enabled by this UI. `LAUNCH_REQUESTED` means BlackBox accepted the request, not that the guest or module finished starting. If automatic launch fails after activation, the rule stays enabled and the UI explains that a manual guest start is still possible.
6. Reopen the imported module and use **Last load status** for the same user/package.
7. **Disable for a guest** removes its activation rule. Restart that guest to remove already loaded code. There is no live dlclose or remote-process attachment.

One module may be enabled per package/user in this version. Enabling another replaces its rule; it does not unload the existing library. The rule pins the SHA-256 of the payload; changing it requires explicit re-enabling. Importing a ZIP creates a unique directory and does not overwrite an existing enabled module.

## Generic native payload format

Put `native-module.properties` at the module root:

```properties
id=my_module
library=payload/libexample.so
```

The relative library path must stay inside the imported module. ARM32/ARM64 little-endian ELF shared libraries are accepted; the actual guest bitness is checked immediately before loading. The platform linker remains authoritative for library validity, dependencies, namespace visibility and executable-mapping permissions. `System.load` runs ELF initialization and, if present, JNI_OnLoad under normal Android rules. Loading is through the host/core class loader; payloads requiring a guest-specific JNI class loader may need a dedicated adapter.

## MagicPro adapter — experimental

Recognized by `module.prop` with `id=Magic`, `payload/libPUBGM.so`, and `inject.conf`.

- Selectable packages are limited to enabled `package=1` entries in `inject.conf`.
- ARM64 payload is staged in the selected guest's `files/Magic/libPUBGM.so`.
- `kami.conf` and `features.conf`, when supplied, are copied into that same guest directory. Default feature settings are created if absent and no existing settings are present.
- The library is loaded **inside that guest process**, without loading the Zygisk stub.
- `customize.sh`, `post-fs-data.sh`, `service.sh`, `key_gate.sh`, Zygisk companion callbacks and WebUI are **not executed/emulated**. Authorization flags are not patched.
- This reproduces only the observed payload staging/loading portion. It does NOT establish equivalence to the original module or demonstrate working game features. The supplied module also contains a separate memory-access worker; its behavior is outside this adapter.
- The supplied binaries and keys are not included in the repository or the built APK.

## Diagnostics

Records are in `blackbox/system/native_modules/<user>_<package>.properties.status`, and events use the existing `DiagnosticLogger` tag `NativeModules` (therefore join the existing per-process diagnostics).

States: `ARMED`, `PREPARING`, `LOAD_STARTED`, `LIBRARY_LOADED`, `LOAD_FAILED`, `DISABLED`.

The UI shows the last recorded attempt, not current process liveness. It includes PID, target user/package/process, timestamp, phase, revision and payload SHA-256. `ARMED`/`DISABLED` are host-side configuration events; load states are emitted by the guest. `LIBRARY_LOADED` means only that `System.load` returned. It does not mean a menu appeared, authorization succeeded, or game modifications work. A final `LOAD_STARTED` followed by process death is evidence to investigate a native crash/hang, not success. Configuration contents and license keys are not logged by this feature.

## Validation

Run `bash tests/run-native-module-tests.sh` on a JDK. An optional local ZIP argument adds static checks against the supplied MagicPro sample; no native library is executed. CI runs the archive/ELF/path/target-matching regressions and the launch-coordinator tests before the existing debug/release builds. `tests/test_native_module_launcher.py` compiles the actual coordinator against test doubles and verifies activation-before-stop-before-launch, exact package/user forwarding, and failure paths; it does not test Android runtime behavior.

Required device checks: debug and release startup; fresh nested-ZIP import; activation in user 0 versus another user; guest main process versus secondary process; incompatible ABI; failed dependency resolution; changed payload hash; disable + restart; and unchanged launch when no rule exists. Use the existing diagnostic APK/log collection for device evidence. No Android-device execution or full Android build was performed during local source validation.

## Code map

- `ModuleFiles`: bounded path-preserving ZIP extraction, manifest parsing, target matching, ELF header checks and hashes; JVM-testable.
- `ScriptEngine`: staged imports, scoped shell working directory, private file permissions, per-run results, bounded retained output.
- `NativeModuleManager`: explicit activation, staging and guest-only lifecycle load/status.
- `Entry`: idempotent callback registration. `App.kt` references it directly so release shrinking cannot remove a reflection-only entry.
- `NativeModulesDialog`: existing Script Runner directory navigation, target selection and activation/status/disable actions.
- `NativeModuleLauncher`: explicit activate-and-launch action; strict virtual-PM preflight, scoped stop, BlackBox launch, failure reporting, and duplicate in-flight request guard. Does not overwrite the guest's native-load status or launch a host-installed game directly.
