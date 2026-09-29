# PROJECT_MAP — архитектурная карта VMREWORK-root-gg (форк BlackBox / NewBlackbox)

Все пути — относительно корня репо. Цель карты: любое ИИ/человек находит нужный файл за секунды, а не 5 минут поиска.

---

## 1. Модули Gradle и версии

**settings.gradle** — `rootProject.name = "NewBlackbox"`, модули: `:app`, `:Bcore`, `:black-reflection`, `:compiler`.

**build.gradle (корневой)** — ext-константы:
- `compileSdkVersion = 35`, `targetSdkVersion = 28`, `minSdk = 21`
- `versionCode = 400`, `versionName = "4.0.0"`
- `hiddenApiBypass = '4.3'`
- AGP 8.13.2, Kotlin 1.9.23 (gradle/libs.versions.toml)

| Модуль | Тип | Роль | Детали |
|---|---|---|---|
| `Bcore` | android-library (`top.niunaijun.blackbox`) | Движок виртуализации (client hooks + server services + native) | ndkBuild `src/main/cpp/Android.mk`, prefab, aidl, abiFilters arm64-v8a/armeabi-v7a, Java 21; зависит от `:black-reflection`, annotationProcessor `:compiler`, FreeReflection 3.2.2, toml4j |
| `app` | android-application (`top.niunaijun.blackboxa`, applicationId `top.niunaijun.blackbox`) | UI-оболочка (Kotlin) | targetSdk 28, splits abi, viewBinding, lifecycle/prefx; зависит от `:Bcore` |
| `black-reflection` | library (`top.niunaijun.blackreflection`) | Runtime reflection-фреймворк (аннотации BClass/BField/BMethod + BlackReflection.java) | — |
| `compiler` | annotation-processor (`top.niunaijun.blackreflection`) | APT-генератор BR*-классов для black-reflection | BlackReflectionProcessor.java и др. |

NDK 29.0.13846066 в обоих модулях с native-кодом.

---

## 2. Дерево пакетов `Bcore/src/main/java/top/niunaijun/blackbox`

- **`/` (корень)** — `BlackBoxCore.java`: главный синглтон-фасад всего движка (см. §3).
- **`app/`** — клиентский рантайм виртуального приложения: `BActivityThread.java` («ActivityThread» песочницы), `LauncherActivity.java` (дебютный экран движка), + подпакеты:
  - **`app/configuration/`** — `ClientConfiguration` (переопределяемые хостом политики: isHideRoot, isUseVpnNetwork, isEnableDaemonService, requestInstallPackage…), `AppLifecycleCallback` (каллбеки жизненного цикла виртуальных приложений).
  - **`app/dispatcher/`** — `AppServiceDispatcher` / `AppJobServiceDispatcher`: роутинг вызовов proxy-Service/JobService к настоящим виртуальным сервисам.
- **`closecode/`** — `Entry.java`: экспериментальная точка attach — инжект `inject.so` в конкретный целевой пакет через AppLifecycleCallback.
- **`core/`** — базовые подсистемы: `CrashHandler`, `GmsCore` (подмена GMS-пакетов), `IOCore` (пере-направление путей/IO-редирект), `NativeCore` (JNI-мост к libblackbox.so), + подпакеты:
  - **`core/env/`** — файловая среда песочницы: `BEnvironment` (все пути: data/system/proc/rootfs), `AppSystemEnv` (список системных пакетов/спец-обработка), `VirtualRuntime` (текущий pkg/process рантайма), `RootConfig` (кэш root-состояния процесса, без binder), `RootShellEmulator` (эмуляция su/id/whoami), `FirebaseCompat`.
  - **`core/system/`** — **server-процесс** (`:black`): `ServiceManager` (реестр сервисов), `BlackBoxSystem` (стартовый сервис-процесс), `DaemonService` (foreground-daemon + inner service), `BProcessManagerService` (реестр живых виртуальных процессов/bpid), `JarManager` (+`JarConfig`, динамические jar/dex, `JarManagerTest`), `SystemCallProvider` (ContentProvider-канал клиент↔server, authority `{host}.blackbox.SystemCallProvider`), `ProcessRecord`, `ISystemService` (интерфейс сервисов), + подпакеты:
    - **`am/`** — ActivityManager-сервер: `BActivityManagerService`, `ActiveServices`, `ActivityStack`/`ActivityRecord`/`TaskRecord`, `BroadcastManager`, `PendingIntentRecord`, `BJobManagerService`, `UserSpace`.
    - **`pm/`** — PackageManager-сервер: `BPackageManagerService`, `BPackageInstallerService`, `BPackage`/`BPackageSettings`/`BPackageUserState`, `Settings` (персистент packages.xml-аналог), `ComponentResolver`/`IntentResolver`, `PackageMonitor`, + **`installer/`** (Executor-ы: Copy/CreatePackage/CreateUser/RemoveApp/RemoveUser).
    - **`accounts/`** — BAccountManagerService + TokenCache (виртуальные аккаунты).
    - **`location/`** — BLocationManagerService + LocationRecord (fake GPS).
    - **`notification/`** — BNotificationManagerService + NotificationChannelManager.
    - **`os/`** — BStorageManagerService (виртуальный storage).
    - **`permission/`** — XiaomiPermissionManager (MIUI-специфика).
    - **`root/`** — `BRootManagerService` (root DenyList, root_conf.json).
    - **`user/`** — BUserManagerService + BUserHandle/BUserInfo/BUserStatus (многопользовательность).
- **`entity/`** — Parcelable-сущности: `AppConfig` (конфиг запускаемого процесса: pkg, bpid/buid, token, **rootEnabled**, **overlayGranted**), `JobRecord`, `ServiceRecord`, `UnbindRecord`, + `am/` (PendingResultData, ReceiverData, RunningAppProcessInfo, RunningServiceInfo), `pm/` (InstallOption, InstallResult, InstalledPackage), `location/` (BLocation, BCell, BLocationConfig).
- **`fake/`** — клиентская подсистема хуков: `FakeCore` (инициализация jnihook ReflectCore на android.app.ActivityThread), + подпакеты:
  - **`fake/hook/`** — инфраструктура хуков: `HookManager` (реестр/инжект всех прокси), `BinderInvocationStub`/`ClassInvocationStub` (динамические прокси binder-сервисов/классов), `MethodHook`, `ProxyMethod`, `IInjectHook`, `ScanClass`, `ProxyMethods`.
  - **`fake/frameworks/`** — клиентские фасады к server-сервисам: `BActivityManager`, `BPackageManager`, `BJobManager`, `BUserManager`, `BStorageManager`, `BAccountManager`, `BLocationManager`, `BNotificationManager`, `BResourcesManager`, `BRootManager` (root-фасад), `BlackManager`.
  - **`fake/service/`** — ~85 прокси системных сервисов (см. §4) + подпакеты:
    - **`base/`** — generic `UidMethodProxy`, `PkgMethodProxy`, `ValueMethodProxy`.
    - **`context/`** — `ContentServiceStub`, `RestrictionsManagerStub`, + `providers/` (`BContentProvider`, `ContentProviderStub`, `SystemProviderStub`) — виртуальные ContentProvider-стабы.
    - **`libcore/`** — `OsStub` (прокси `libcore.io.Os`).
  - **`fake/delegate/`** — `AppInstrumentation` (подмена Instrumentation для активити), `BaseInstrumentationDelegate`, `InnerReceiverDelegate`, `ServiceConnectionDelegate`, `ContentProviderDelegate`.
  - **`fake/provider/`** — `FileProvider`/`FileProviderHandler` (хост-FileProvider песочницы).
- **`proxy/`** — stub-компоненты хост-манифеста: `ProxyActivity`, `TransparentProxyActivity`, `ProxyPendingActivity` (внутренние классы `$P0…$P49`), `ProxyService`, `ProxyJobService`, `ProxyBroadcastReceiver`, `ProxyContentProvider`, `ProxyVpnService`, `ProxyManifest` (генератор имён/authority), + **`record/`** (ProxyActivityRecord, ProxyServiceRecord, ProxyBroadcastRecord, ProxyPendingRecord — маппинг token→реальный компонент).
- **`script/`** — `ScriptEngine`: импорт/распаковка .sh/.zip в виртуальный /data/local/tmp, chmod 777, запуск с эмуляцией root-окружения.
- **`util/`** — `XiaomiDeviceDetector`.
- **`utils/`** — тулбокс: `Reflector`/`ReflectionClass`, `Slog`/`DiagnosticLogger`/`RootLogger`/`LogSender`, `ShellUtils`, crash-превентивы (`SimpleCrashFix`, `DexCrashPrevention`, `NativeCrashPrevention`, `SocialMediaAppCrashPrevention`, `CrashMonitor`, `DexFileRecovery`), `UIDSpoofingHelper`, `StoragePermissionHelper`, `TrieTree`, `ComponentUtils`, `HackAppUtils`, `QQUtils`, `SystemHookManager`, `StackTraceFilter`, `TransactionThrottler` и др., + подпакеты:
  - **`utils/compat/`** — version-шимы (BuildCompat, ActivityManagerCompat, PackageParserCompat, ParceledListSliceCompat, ApplicationThreadCompat…).
  - **`utils/provider/`** — `ProviderCall`: синхронный binder-вызов к хостовому провайдеру (используется для старта black-процесса).

### Shadow-пакеты (вне top.niunaijun)
- **`Bcore/src/main/java/android/`** — compile-stub-копии скрытых framework-классов: `android.app.ActivityThread`, `android.app.ContentProviderHolder`, `android.os.ServiceManager`, `android.content.IContentProvider`/`SyncInfo`, `android.content.pm.*`, `android.location.LocationRequest` — дают компилируемый доступ к @hide API (реальные подменяются рантаймом).
- **`Bcore/src/main/java/black/`** — зеркала Android-интерналов для black-reflection (сотни `BR*`/дескрипторов): `black.android.app` (ActivityThread, IActivityManager(L/N), ContextImpl…), `black.android.os`, `black.android.view`, `black.android.telephony`, `black.com.android.internal.*`, `black.dalvik.system` (VMRuntime), `black.libcore.io` (Libcore), `black.java.io/lang` — type-safe отражение скрытых полей/методов.

---

## 3. Ключевые классы-оркестраторы

| Класс | Путь | Роль / ключевые методы |
|---|---|---|
| **BlackBoxCore** | `Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java` | Синглтон-фасад движка (extends ClientConfiguration). В static-блоке ставит crash-превентивы. Методы: `doAttachBaseContext()` / `doCreate()` (init клиента: FakeCore, NativeCore.init, ServiceManager.initBlackManager), `getService(name)` + `areServicesAvailable()` + `startBlackProcess()` (старт server-процесса через ProviderCall/DaemonService, с retry/fallback), `launchApk(pkg, userId)`, `startActivity(intent, userId)`, `installPackageAsUser/uninstallPackage/clearPackage/stopPackage`, `getBActivityManager()/getBPackageManager()/getBRootManager()/…`, `setCurrentAppUid()` (детект песочницы), хелперы разрешений на хранилище. |
| **BActivityThread** | `Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java` | «ActivityThread» виртуального приложения, `IBActivityThread.Stub`. Ключевое: `initProcess(AppConfig)` (привязка + binderDied), `bindApplication(pkg, procName)` → `handleBindApplication()` (~370 строк: reflection-инициализация, IOCore.enableRedirect, RootConfig.init, JarManager, провайдеры, FirebaseCompat.initializeAppSafely), `createService()/createJobService()`, `installProviders()/installProvider()` (+`shouldSkipContentProvider` — skip FirebaseInitProvider/FirebasePerf), `handleNewIntent()/finishActivity()`, `scheduleReceiver()`, `hookActivityThread()`, статические `getAppConfig()/getBUid()/getAppProcessName()/currentActivityThread()`. |
| **ServiceManager (server)** | `Bcore/src/main/java/top/niunaijun/blackbox/core/system/ServiceManager.java` | Реестр-кэш (`mCaches`) всех 9 серверных сервисов: ACTIVITY/JOB/PACKAGE/STORAGE/USER/ACCOUNT/LOCATION/NOTIFICATION/**ROOT**_MANAGER. `getService(name)`, `initBlackManager()` (прогрев всех binder-ссылок в клиенте). |
| **ServiceManager (shadow-stub)** | `Bcore/src/main/java/android/os/ServiceManager.java` | Compile-stub `android.os.ServiceManager.getService` (throw) — реальный перехват через OsStub/binder-прокси. |
| **BProcessManagerService** | `Bcore/src/main/java/top/niunaijun/blackbox/core/system/BProcessManagerService.java` | Серверный реестр живых виртуальных процессов (bpid/buid, ProcessRecord): выдача конфигов, статусы процессов, используется AMS и BRootManagerService (`getAllProcessRecords` для GG-карты процессов). |
| **Эквивалент AppEnvironment** | `core/env/BEnvironment.java` + `BlackBoxCore.doAttachBaseContext/doCreate` + `core/IOCore.java#enableRedirect(context)` | Классов `AppEnvironment`/`EnvironmentInitializer` в этом форке НЕТ; их роль несут эти три точки. |
| **Stub-активности** | `Bcore/src/main/java/top/niunaijun/blackbox/proxy/` + `Bcore/src/main/AndroidManifest.xml` | `ProxyActivity$P0..P49`, `TransparentProxyActivity$P…`, `ProxyPendingActivity$P…`, `ProxyService$P…`, `ProxyJobService$P…` — по 50 экземпляров каждого; имена строит `proxy/ProxyManifest.java` (`getProxyActivity(i)`, `getProcessName(bPid)={host}:p{bPid}`, authority `proxy_content_provider_%d`). Реальные компоненты подставляются через `proxy/record/Proxy*Record`. |
| **Запуск виртуального приложения** | цепочка | Хост UI → `BlackBoxCore.launchApk()` → `BActivityManager.startActivity()` (`fake/frameworks/BActivityManager.java:87`) → startActivityAms → server `BActivityManagerService` стартует `:p{bpid}`-процесс хоста → в нём `SystemCallProvider`/ProviderCall отдает `AppConfig` (в т.ч. rootEnabled) → `BActivityThread.initProcess` → `handleBindApplication` → real Activity запускается внутри `ProxyActivity$Pxx`. |

---

## 4. fake/service — прокси-слой и OsStub

Реестр — `Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java#init()` (инжект только в black/server процессах). Основные перехватчики (все в `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/`, если не указано иное):

| Прокси | Что перехватывает |
|---|---|
| `IActivityManagerProxy` | binder `IActivityManager` (AMS): старт/finish активити, сервисы, провайдеры, таски |
| `IActivityTaskManagerProxy` (Q+) | `IActivityTaskManager`: task/activity-операции Android 10+ |
| `IActivityClientProxy` (S+) | клиентская сторона ActivityClient |
| `HCallbackProxy` | внутренний handler `H` ActivityThread: подмена сообщений launchActivity/serviceCreate |
| `IPackageManagerProxy` / `IPermissionManagerProxy` (R+) | PM: запросы package info, install, разрешения — видимость только виртуальных+системных пакетов |
| `IAppOpsManagerProxy` | AppOps (op-проверки, режимы; GG overlay-флаг) |
| `IUserManagerProxy` | виртуальные пользователи |
| `IWindowManagerProxy` / `IWindowSessionProxy` | окно/flag secure/инжекты в window-атрибуты |
| `INotificationManagerProxy` | уведомления виртуальных приложений |
| `IAudioServiceProxy`, `AudioRecordProxy`, `AudioPermissionProxy`, `MediaRecorderProxy`/`MediaRecorderClassProxy` | аудио/запись — разрешения и REC-индикация |
| `ILocationManagerProxy` | fake GPS (BLocationManager) |
| `IStorageManagerProxy`, `IStorageStatsManagerProxy` (O+) | виртуальный storage, статистика |
| `ITelephonyManagerProxy`, `ITelephonyRegistryProxy`, `IPhoneSubInfoProxy` | IMEI/subId/телефония-спуф |
| `IWifiManagerProxy`, `IWifiScannerProxy`, `IConnectivityManagerProxy`, `IDnsResolverProxy`, `NetworkPermissionCompat`, `IVpnManagerProxy` (S+) | сеть/wifi/VPN-режим (isUseVpnNetwork) |
| `ISettingsProviderProxy`, `ISettingsSystemProxy` | Settings.Global/System/Secure — спуф значений |
| `ContentServiceStub`, `ContentResolverProxy`, `IContentProviderProxy` | ContentProvider-резолюция и вызовы в песочнице |
| `ClassLoaderProxy`, `SystemLibraryProxy`, `ReLinkerProxy`, `ApkAssetsProxy`, `ResourcesManagerProxy` | загрузка кода/ресурсов виртуального APK |
| `GmsProxy`, `GoogleAccountManagerProxy`, `AuthenticationProxy` | GMS-подмена (GmsCore) |
| `DeviceIdProxy`, `AndroidIdProxy`, `IDeviceIdentifiersPolicyProxy` (O+) | ANDROID_ID/SSAID/identifiers-спуф |
| `FileSystemProxy`, `SQLiteDatabaseProxy`, `LevelDbProxy` | файловый уровень: редирект путей в песочницу |
| `AntiVirtualDetectProxy`, `GameGuardianCompat` | анти-детект виртуализации; GG-совместимость (env-подготовка, синк process-name) |
| Xiaomi-специфика: `IMiuiSecurityManagerProxy`, `IXiaomiMiuiServicesProxy`, `IXiaomiSettingsProxy`, `IXiaomiAttributionSourceProxy`, `IAttributionSourceProxy` | MIUI security/attribution |
| `WorkManagerProxy`, `BrowserEngineProxy`, `ISensitiveContentProtectionManagerProxy` (S) | WorkManager/браузерные движки/защита контента |
| `base/` (`UidMethodProxy`, `PkgMethodProxy`, `ValueMethodProxy`) | генерические подстановки uid/pkg/значений |
| `delegate/AppInstrumentation` (`fake/delegate/`) | подмена `Instrumentation` (execStartActivity/newActivity) |

**OsStub** — `fake/service/libcore/OsStub.java`: прокси-подмена статического поля `libcore.io.Os` (`BRLibcore._set_os`). В `invoke()` перехватывает **все** POSIX-вызовы: строки-пути прогоняет через `IOCore.redirectPath()`, хуки `@ProxyMethod("getuid"/"geteuid"/…)` при включённом root-режиме возвращают uid 0 (эмуляция root), иначе фейковый uid песочницы; exec-пути переписываются (связка с `RootShellEmulator`: `execve`/`posix_spawn` → `/system/bin/sh` с эмулированным/реальным выполнением).

---

## 5. Native-слой `Bcore/src/main/cpp/`

Сборка: `Android.mk` → модуль **`libblackbox.so`**, статические либы: **Dobby** (inline-хуки, prebuilt `Dobby/<abi>/libdobby.a`) и **xdl** (свой linker-lookup). `Application.mk` задаёт ABI. Подключение: `NativeCore.java` (`System.loadLibrary("blackbox")`) и `BoxCore.cpp`.

| Файл | Роль |
|---|---|
| `BoxCore.cpp/.h` | JNI-вход (NativeCore natives: init/enableIO/addIORule/setFakeRootEnabled/setRootFsPath/addProcMapping…), `getCallingUid` через Java-callback, регистрация всех подсистем хуков |
| `IO.cpp/.h` | IO-редирект: список правил relocate, `replace()`, `IO::redirectPath()` — маппинг реальных путей в песочницу |
| `ProcHook.cpp/.h` | Ленивые libc-хуки (Dobby): fake-root (su-пути `g_su_paths[]`, `g_fake_root_enabled`, `g_root_fs_path`), spoof `/proc/<pid>/cmdline|status` через `g_proc_paths` map. Хукатся только open/openat/execvp (безопасные для trampoline); access/stat/getuid намеренно не трогаются — Java-слой закрывает. Нативный diag-логгер `nlog()` пишет `root_native_<proc>.log` (путь — JNI `setExecLogPath` из IOCore): установка хуков, каждый execvp с argv, все rewrite-решения |
| `hidden_api.cpp/.h` | Отключение hidden-API enforcement (`disable_hidden_api` по api_level) |
| `Hook/BaseHook.cpp/.h` | База JNI-хуков (регистрация/подмена функций ART) |
| `Hook/JniHook/JniHook.cpp/.h` (+`ArtMethod.h`) | Ядро перехвата JNI-функций (таблицы fnPtr, размеры ART-структур по api_level) |
| `Hook/BinderHook.cpp` | Хук binder-транспорта (получение calling uid через Java) |
| `Hook/DexFileHook.cpp` | Хук `openDexFileNative` — защита/подмена путей `/blackbox/` при загрузке dex |
| `Hook/FileSystemHook.cpp` | Хуки file-IO (open/stat/access → редирект) |
| `Hook/UnixFileSystemHook.cpp` | Хук `UnixFileSystem.canonicalize0` и соседние — redirect путей java.io.File |
| `Hook/VMClassLoaderHook.cpp` | Скрытие Xposed/инжект-классов из boot classloader (`hideXposedClass`) |
| `Hook/RuntimeHook.cpp` | Хук `nativeLoad` — в Android.mk **не включён** в SRC-список (не компилируется) |
| `Utils/VirtualSpoof.cpp` | Спуф системных свойств/параметров виртуальной среды (таблица `SpoofedProp`) |
| `Utils/AntiDetection.cpp` | Анти-детект песочницы на native-уровне (признаки виртуализации) |
| `Utils/elf_util.cpp/.h` | SandHook ELF-парсер (поиск символов в .so) |
| `Utils/HexDump.cpp/.h` | Отладочный hexdump |

---

## 6. AIDL-сервисы

AIDL-каталог: `Bcore/src/main/aidl/` (внутри `android/**` — каркасные AOSP-интерфейсы).

| Сервис | AIDL | Реализация (server) | Клиентский фасад | Назначение |
|---|---|---|---|---|
| activity_manager | `.../am/IBActivityManagerService.aidl` | `core/system/am/BActivityManagerService.java` | `fake/frameworks/BActivityManager.java` | активити/сервисы/броадкасты/таски/процессы |
| job_manager | `.../am/IBJobManagerService.aidl` | `core/system/am/BJobManagerService.java` | `fake/frameworks/BJobManager.java` | JobScheduler песочницы |
| package_manager | `.../pm/IBPackageManagerService.aidl` + `IBPackageInstallerService.aidl` + `BPackageSettings.aidl` | `core/system/pm/BPackageManagerService.java`, `BPackageInstallerService.java` | `fake/frameworks/BPackageManager.java` | установка/удаление/запросы пакетов |
| storage_manager | `.../os/IBStorageManagerService.aidl` | `core/system/os/BStorageManagerService.java` | `fake/frameworks/BStorageManager.java` | виртуальные тома/очистка данных |
| user_manager | `.../user/IBUserManagerService.aidl` + `BUserInfo.aidl` | `core/system/user/BUserManagerService.java` | `fake/frameworks/BUserManager.java` | многопользовательские профили |
| account_manager | `.../accounts/IBAccountManagerService.aidl` | `core/system/accounts/BAccountManagerService.java` | `fake/frameworks/BAccountManager.java` | виртуальные аккаунты/tokens |
| location_manager | `.../location/IBLocationManagerService.aidl` | `core/system/location/BLocationManagerService.java` | `fake/frameworks/BLocationManager.java` | fake-GPS/соты |
| notification_manager | `.../notification/IBNotificationManagerService.aidl` | `core/system/notification/BNotificationManagerService.java` | `fake/frameworks/BNotificationManager.java` | каналы/уведомления |
| **root_manager** | `.../root/IBRootManagerService.aidl` | `core/system/root/BRootManagerService.java` | `fake/frameworks/BRootManager.java` | root DenyList/enable, `root_conf.json`, hide-root политика |
| — | `core/IBActivityThread.aidl` (+ `IEmpty.aidl`) | `app/BActivityThread.java` (Stub) | вызывается сервером | обратный канал: сервер → клиентский процесс |
| — | `entity/*.aidl` | Parcelable-классы `entity/` | — | AppConfig, JobRecord и др. |

Server-процесс поднимает всё в `core/system/BlackBoxSystem.java`, транспорт к хосту — `SystemCallProvider` + `DaemonService`.

---

## 7. UI-модуль `app`

| Активити (пакет `app/src/main/java/top/niunaijun/blackboxa/view/`) | Назначение |
|---|---|
| `main/WelcomeActivity.kt` | LAUNCHER-экран, инициализация BlackBoxCore |
| `main/MainActivity.kt` | Главный экран (ViewPager: список приложений) |
| `main/ShortcutActivity.kt` | Создание ярлыков виртуальных приложений |
| `main/BlackBoxLoader.kt` | Асинхронный загрузчик движка |
| `list/ListActivity.kt` (+ListAdapter/ListFactory/ListViewModel) | Список виртуальных приложений |
| `apps/` (AppsFragment, AppsAdapter, AppsViewModel) | Swipe/удаление приложений (фрагмент) |
| `setting/SettingActivity.kt` + `setting/SettingFragment.kt` | Настройки (`res/xml/setting.xml`: тема, переходы к GMS/Root/Script-менеджерам) |
| `gms/GmsManagerActivity.kt` | Управление GMS-приложениями в песочнице |
| `root/RootManagerActivity.kt` (+RootAdapter/RootFactory/RootViewModel) | **Root Manager UI**: per-app тумблеры «виртуальный root» (пишет через BRootManager → root_conf.json) |
| `script/ScriptRunnerActivity.kt` (+ScriptAdapter/ScriptViewModel) | **Script Runner UI**: импорт .sh/.zip, запуск через ScriptEngine |
| `fake/FakeManagerActivity.kt` + `FollowMyLocationOverlay.kt` | Fake-локация (osmdroid-карта) |
| `base/` (BaseActivity, BaseViewModel, LoadingActivity) | Базовые классы UI |

---

## 8. Root-подсистема — детальная карта (ключевая)

Цель: виртуальные приложения видят и используют работающий root на нерутированном хосте (VMOS-style), без реального рута устройства.

### Цепочка решения (кто и когда определяет root для приложения)

1. **`core/system/root/BRootManagerService.java`** (server) — per-app (`pkg|userId`) root-состояние, персист `blackbox/system/root_conf.json`. Приоритет: явная запись из UI > GMS-чёрный список (`DEFAULT_ROOT_HIDDEN`) > глобальный `ClientConfiguration.isHideRoot()` > `DEFAULT_ROOT_ENABLED=true`.
2. **`core/system/ProcessRecord.java#getClientConfig`** (server) — кладёт `rootEnabled`/`overlayGranted` в Parcelable `AppConfig` **до** старта процесса (без binder-block на main thread клиента).
3. **`entity/AppConfig.java`** — транспорт полей до виртуального процесса.
4. **`core/env/RootConfig.java#init`** (клиент, вызывается из IOCore.enableRedirect при handleBindApplication) — per-process кэш; берёт из AppConfig, fallback — локальный расчёт. Все рантайм-хуки спрашивают `RootConfig.isRootEnabledForCurrentProcess()`.
5. **`core/IOCore.java#enableRedirect`** — ветвление: root включён → `setupVirtualRoot(rule)` (фейковые su-бинарники + маркеры magisk/Superuser/busybox в `blackbox/rootfs`, скрипт `rootfs/scripts/su_proxy.sh`, спуф `/proc/self/status` Uid/Gid=0), иначе → `hideRoot(rule)` (su-пути → `-fake`). Также `/data/local/tmp` → виртуальная песочница, `/proc/<pid>/cmdline` → спуф-файлы (GG).
6. **`fake/service/libcore/OsStub.java`** — Java-слой идентичности: `getuid/geteuid/getgid/getegid` → 0; `stat` → st_uid=0; `access` на su-пути → true; `execve`/`posix_spawn` → рерайт через RootShellEmulator.
7. **`core/env/RootShellEmulator.java`** — эмуляция su-семантики: известные пробы (id/whoami/which su/getenforce/su -v) → canned root-вывод; `su -c <cmd>` и `su 0 <cmd>` → **реальное исполнение** `/system/bin/sh -c <cmd>`; bare `su` → интерактивный прокси `su_proxy.sh` (пробы отвечают uid=0, произвольные строки stdin исполняются, exit по EOF/`exit`/6s idle).
8. **`cpp/ProcHook.cpp`** — native-слой: `setFakeRootEnabled(true)` ставит Dobby-хуки на libc `open/openat` → резолв su-путей в rootfs; **`execvp`** → перехват нативного `Runtime.exec` (Android 7+: `UNIXProcess.forkAndExec` → libc `execvp`, Java-хуки OsStub этот путь НЕ видят) — su-семантика зеркалит RootShellEmulator (canned пробы, `su -c/позиционный/bare` → `/system/bin/sh`); `/proc/<pid>/cmdline` → спуф-файл. `setRootFsPath` — путь rootfs.
9. **`fake/service/GameGuardianCompat.java`** — для GG-пакетов: overlay-флаг, периодический синк pid→имя (сервер `getVirtualProcessMapJson` → `NativeCore.addProcMapping`), память (`/proc/<pid>/mem`, maps, ptrace) проходит насквозь.
10. **`script/ScriptEngine.java`** — скрипты: PATH с `tmp/bin/su`-обёрткой (обрабатывает `su -c`/`su 0`/bare `su`), `VM_FAKE_ROOT=1`.

### Точки отказа (что проверять, если «root не работает»)

| Симптом | Смотреть |
|---|---|
| Приложение не видит root вообще | root_diag: `ProcessRecord.getClientConfig` → `RootConfig.init` — дошло ли rootEnabled=true; `IOCore.enableRedirect` — какая ветка (setupVirtualRoot/hideRoot) |
| Root Checker виснет/падает до проверки | `bb_diag_pN.log`: FirebaseCompat (skip FirebaseInitProvider + initializeAppSafely), CrashMonitor |
| `su` не выполняет команды | root_diag: `shell rewrite` строки (RootShellEmulator.rewrite) — был ли рерайт; **root_native_pN.log**: `execvp '<path>' argv=[...]` + `exec decision` — что исполнялось нативно и что ответила эмуляция, `hook execvp installed/FAILED` — встал ли хук; `su_proxy.sh` существует в rootfs |
| Java `File("/system/bin/su").exists()` = false | OsStub stat/access хуки + нативный ProcHook (ALOGD "ProcHook: hooked") |
| getuid ≠ 0 | OsStub getuid/geteuid (проверить что хук установлен — HookManager) |
| GG не видит процессы/память | GameGuardianCompat sync-loop, `/proc/<pid>/cmdline` спуф, ptrace_scope |
| Toggle в UI не применяется | Требуется перезапуск виртуального приложения (root_conf.json персист) |

### Логи

- `utils/DiagnosticLogger.java` — общий диагностический: `bb_diag_{main,server,pN}.log` в `/storage/emulated/0/Android/data/<host>/files/`. Watchdog-дампы зависаний, crash-hook, thread dumps.
- `utils/RootLogger.java` — root-специфичный: `root_diag_{main,server,pN}.log` рядом с bb_diag. Все решения цепочки (`[ROOT-DECISION]`), shell-рерарайты, uid-спуф (sampled), ошибки. Grep: `ROOT-DECISION`, `shell rewrite`, `getuid -> 0`.
- `root_native_{pN}.log` — нативная сторона (ProcHook `nlog`): `hook <sym> installed/FAILED`, `execvp '<path>' argv=[...]`, `exec decision: ...`, `open/openat '<from>' -> '<to>'`. Именно здесь видно, что root-приложение реально исполняет нативно (Runtime.exec мимо Java-хуков).

---

## 9. Данные / персистентные конфиги

- **Корень песочницы**: `BEnvironment.java` — `sVirtualRoot = {host cacheDir parent}/blackbox`. Ключевые пути: `getSystemDir()` (`blackbox/system`), `getProcDir()`/`getProcDir(pid)`/`getProcSpoofDir()`, `getRootFsDir()` (виртуальный rootfs), `getLocalTmpDir()` (виртуальный /data/local/tmp), `getUserDir(userId)`, `getDataDir(pkg,userId)` + De/External/Cache/Lib/Databases, `getAppDir(pkg)`.
- **Конфиги** (в `blackbox/system`): `root_conf.json` (root-состояния per-app), `getUserInfoConf()`, `getAccountsConf()`, `getUidConf()`, `getFakeLocationConf()`, `getPackageConf(pkg)`, `getXPModuleConf()`.
- **Классов `FakeConfig`/`BlackBoxConfiguration` НЕТ** — их роль: `app/configuration/ClientConfiguration.java` (политики хоста) и `entity/AppConfig.java` (конфиг процесса).
- Кэш: `blackbox/cache/junit.apk`, `empty.apk`.

---

## 10. Документация

- **README.md** — обзор движка, сборка debug/release, интеграция SDK (ClientConfiguration), troubleshooting, credits.
- **Docs.md** — «Complete User Guide»: установка/инициализация, управление приложениями, WebView, Google Services, фоновые задачи, UID-spoofing, troubleshooting.
- **RELEASE_NOTES.md** — релиз 2026-01-31: VPN-режим, фиксы, известные проблемы, матрица совместимости.
- **CHANGELOG_ROOT_GG_SCRIPTS.md** — журнал подсистем Root/GG/Scripts: серия фиксов (чёрные экраны, Firebase, posix_spawn/noexec, Elixir-пробы, bare su).

---

### Дополнительные ориентиры

- Хост-манифест движка: `Bcore/src/main/AndroidManifest.xml` (~600+ строк: SystemCallProvider, FileProvider, DaemonService, ProxyActivity$P0…).
- Точка инициализации reflection: `fake/FakeCore.java` → `ReflectCore.set(android.app.ActivityThread.class)`.
- Экспериментальный инжект-вход: `closecode/Entry.java`.
- Логирование: `utils/Slog.java` (logcat), `utils/DiagnosticLogger.java` (bb_diag), `utils/RootLogger.java` (root_diag), `utils/LogSender.java`.
