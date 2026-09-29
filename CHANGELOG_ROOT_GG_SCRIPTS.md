# VMREWORK — Root Management / GameGuardian / Script Runner

Краткое описание изменений. Модуль антидетекта (`cpp/Utils/AntiDetection.cpp`, `VirtualSpoof.cpp`) не модифицирован.

## 1. Root Management / DenyList (селективный Root)

**Серверная часть (Bcore):**
- `Bcore/src/main/aidl/.../core/system/root/IBRootManagerSer
vice.aidl` — новый AIDL-сервис.
- `core/system/root/BRootManagerService.java` — персистентный конфиг `blackbox/system/root_conf.json`, политика по умолчанию **Root включён** (VMOS-style), GMS-пакеты — по умолчанию без root. Явное состояние из UI имеет приоритет над глобальным «Hide Root».
- `core/system/ServiceManager.java` — регистрация сервиса `root_manager`.
- `fake/frameworks/BRootManager.java` — клиентский фасад; `BlackBoxCore.getBRootManager()`.
- `core/env/RootConfig.java` — per-process кэш состояния Root виртуального приложения.
- `core/system/BProcessManagerService.java` — добавлен `getAllProcessRecords()` (для карты процессов GG).

**Рантайм виртуального процесса:**
- `core/IOCore.java`:
  - Root отключён → прежнее скрытие (`hideRoot`, чистое non-root окружение).
  - Root включён → `setupVirtualRoot()`: виртуальный rootfs (`blackbox/rootfs`), fake su/daemonsu/magisk/busybox/Superuser.apk, спуф `/proc/self/status` (Uid/Gid: 0).
  - `/data/local/tmp` → виртуальная директория песочницы (для модуля скриптов).
- `fake/service/libcore/OsStub.java`:
  - `getuid/geteuid/getgid/getegid` → 0 при включённом Root (uid-spoofing).
  - Хук `fork` — отслеживание fork-потомка.
  - Хук `execve` — **виртуальный su-демон**: `su -c "<cmd>"` выполняется в песочнице (`/system/bin/sh -c`), `id`/`whoami`/`which su`/`getenforce` эмулируются с root-выводом; `su` без аргументов → shell. При выключенном Root — естественный отказ (нет su).
- Нативный слой: `cpp/ProcHook.cpp/.h` (новый, подключён в `BoxCore.cpp` и `Android.mk`) — видимость su на уровне libc (`open/openat/access/faccessat/stat/lstat`), `getuid/geteuid` → 0, маппинг `/proc/<pid>/cmdline`.

**UI (app):** `RootManagerActivity` (+ `RootViewModel/RootFactory/RootAdapter`, `data/RootRepository`, `bean/RootBean`) — список всех виртуальных приложений с переключателем: ON = Root видим, OFF = скрыт (DenyList). Точка входа — Настройки → «Root Manager».

## 2. GameGuardian (GG)

- `fake/service/GameGuardianCompat.java` (новый) — автоопределение GG (`speed.dada.gameguardian*`), автоподготовка среды: Root, overlay-флаг, запуск синхронизации процессов.
- **Оверлеи (SYSTEM_ALERT_WINDOW):** `IAppOpsManagerProxy` в песочнице уже возвращает `MODE_ALLOWED` (проверки GG проходят); для реального отображения плавающего значка выдаётся разрешение «Поверх других окон» хост-лаунчеру (кнопка-подсказка в Root Manager при включении Root для GG, `SYSTEM_ALERT_WINDOW` добавлен в манифест).
- **Доступ к памяти:** имена процессов песочницы публикуются через `/proc/<pid>/cmdline` (нативный ProcHook + Java-маппинг, синхронизация с сервером каждые 3 с). `/proc/<pid>/mem`, `maps` и `ptrace` проходят насквозь без искажений — GG читает/пишет память других виртуальных процессов как процессы того же host-UID (без реального Root).

## 3. Script Runner (кастомные скрипты)

- `script/ScriptEngine.java` (новый) — пайплайн: импорт (.sh/.zip с хоста через SAF) → распаковка в виртуальный `/data/local/tmp` → `chmod 777` рекурсивно (`android.system.Os.chmod` + fallback `chmod`) → запуск через `/system/bin/sh` с окружением песочницы (`PATH` с виртуальным tmp, `TMPDIR/HOME`, `VM_FAKE_ROOT=1`, su-обёртка в `tmp/bin/su`). Защита от path traversal в zip.
- **UI:** `ScriptRunnerActivity` (+ ViewModel/Adapter/layouts) — импорт, список, живая консоль вывода, код возврата. Точка входа — Настройки → «Script Runner».

## Ресурсы
- `values/strings.xml` — новые строки (EN), `values-ru/strings.xml` — RU-переводы.
- `res/xml/setting.xml` — пункты Root Manager и Script Runner; `AndroidManifest.xml` — 2 активити + `SYSTEM_ALERT_WINDOW`.

## Сборка и проверка
```bash
./gradlew assembleDebug
```
1. Настройки → **Root Manager** → toggle приложений (по умолчанию Root включён; переключение применяется при перезапуске виртуального приложения).
2. Установите GameGuardian в песочницу → запустите → выберите процесс игры (имена виртуальные) → правка памяти; значок GG — после выдачи хосту «Поверх других окон».
3. Настройки → **Script Runner** → Import (.sh/.zip) → тап по элементу → консоль.

## Ограничения
- Скрипты и подпроцессы выполняются от host-UID (реального рутирования нет и не требуется); `su` внутри shell-скриптов подчиняется политике ядра на исполнение файлов из data (Android 10+ ограничивает) — Java-слой su эмулируется полностью.
- ptrace между процессами одного UID зависит от `kernel.yama.ptrace_scope` (на большинстве устройств = 0, GG работает).
- Скрытие su-пакетов в PackageManager-запросах осталось глобальным (как было), чтобы не трогать серверную логику PM.

## Fix: зависший чёрный экран в части приложений (inline-хуки stat/access)

**Симптом:** часть виртуальных приложений запускается нормально, часть — чёрный
экран без краша (ANR-подобный фриз). Проявилось после включения fake-root по
умолчанию (`DEFAULT_ROOT_ENABLED = true`), т.к. нативный слой ProcHook стал
ставиться практически каждому виртуальному процессу.

**Причина:** `ProcHook.cpp` ставил Dobby inline-хуки на `access`, `faccessat`,
`stat`, `lstat`. В bionic это такие же тонкие syscall-обёртки на 2–6 инструкций,
как `getuid`/`geteuid` (тот же класс бага, что уже чинился в `efd7939`):
trampoline не помещается, патч тихо затирает соседний код libc — без краша,
просто фриз. Более тяжёлые приложения (много нативных потоков, частые
`stat`/`access`) попадали в затёртые участки почти гарантированно.

**Исправление:**
- `cpp/ProcHook.cpp`: хуки `access/faccessat/stat/lstat` удалены полностью.
  Остались только `open`/`openat` (достаточно крупные variadic-функции) — на
  них держится спуф `/proc/<pid>/cmdline` для GameGuardian и видимость su
  артефактов для нативных `open`-вызовов.
- `match_proc_cmdline()` больше не требует включённого fake-root: спуф cmdline
  работает и в GG-процессе с выключенным root.
- Видимость root для Java-слоя (`File.exists()`, `Os.stat`, `Runtime.exec("su")`)
  полностью сохранена: она покрыта IO-redirect правилами, `OsStub` и
  `UnixFileSystemHook` и от нативных хуков не зависит.
- Коммит: `fix: frozen black screen caused by inline hooks on tiny bionic stubs (access/stat/lstat/faccessat)`

## DiagnosticLogger — дамп зависшего чёрного экрана

Если после фикса ProcHook чёрный экран всё ещё воспроизводится, нужен файл-лог
критических фаз запуска (не logcat: при ANR-подобном фризе без краша logcat
часто бесполезен).

**Что добавлено:**
- `utils/DiagnosticLogger.java` — always-on файловый логгер. Пишет в
  `/storage/emulated/0/Android/data/top.niunaijun.blackbox/files/`:
  - `bb_diag_main.log` — хост UI
  - `bb_diag_server.log` — процесс `:black`
  - `bb_diag_pN.log` — виртуальный клиент `:pN`
- Асинхронная очередь (`ConcurrentLinkedQueue` + daemon `BbDiagWriter`), drop
  при backlog > 4000, ротация 2 МБ. Логирование не блокирует main thread.
- `Scope` BEGIN/END вокруг фаз запуска + watchdog 8 с: если фаза не закрылась,
  дамп стеков всех потоков (`WATCHDOG` + thread dump).
- `Slog` I/W/E зеркалируются в файл без двойной записи в logcat.
- Точки: `BlackBoxCore.doAttachBaseContext` / `doCreate` / `launchApk`,
  `BActivityThread.initProcess` / `bindApplication` / `handleBindApplication`
  (NativeCore.init, makeApplication, installProviders, Application.onCreate),
  `IOCore.enableRedirect`, `HookManager.init` / `injectAll`.

**Защита от IO-redirect:** виртуальные процессы мапят `/storage/emulated/0`
в песочницу. Без исключения логи исчезли бы внутри sandbox.
- Java: `DiagnosticLogger.isProtectedPath()` в `IOCore.redirectPath`.
- Native: `IO.cpp` skip для `/bb_diag_`, `BB_DIAG_README`,
  `/Android/data/top.niunaijun.blackbox/`.

**Как снять дамп после фриза:**
1. Собрать и установить debug-сборку, воспроизвести чёрный экран.
2. `adb pull /storage/emulated/0/Android/data/top.niunaijun.blackbox/files/`
   (или Files app на устройстве).
3. Искать `BEGIN` без парного `END`, строку `WATCHDOG` и thread dump —
   это фаза, на которой процесс завис.

## Fix: чёрный экран Minesweeper Go / AppLovin (`setUidCleartextNetworkPolicy`)

Логи `bb_diag_*.log` (TECNO KJ6, Android 13) показали, что bindApplication
завершается за 135 мс — это **не фриз**, а краш Activity при старте.

**Стек:**
`AppActivity.onCreate` → AppLovin `MaxAdView` → `StrictMode.setVmPolicy` →
`$Proxy.setUidCleartextNetworkPolicy` → `UidMethodProxy.hook` → реальный NMS →
`SecurityException: Requires NETWORK_STACK / MAINLINE_NETWORK_STACK` →
`UndeclaredThrowableException` → процесс умирает, остаётся чёрный экран.

**Исправление:**
- `INetworkManagementServiceProxy`: `setUidCleartextNetworkPolicy`,
  `setUidMeteredNetworkBlacklist/Whitelist`, `setFirewallUidRule(s)`,
  `setUidOnMeteredNetworkList` — no-op (`ValueMethodProxy`), без binder-вызова.
- `UidMethodProxy`: при `SecurityException` возвращает default, а не пробрасывает.
- `ClassInvocationStub.invoke`: unwrap `InvocationTargetException`, чтобы
  checked-исключения хуков не превращались в `UndeclaredThrowableException`.

## Fix: чёрный экран Root Checker (`FirebaseInitProvider` deadlock)

Minesweeper Go после фикса NMS запускается. Root Checker
(`com.joeykrim.rootcheck`) снова давал чёрный экран — но это уже **настоящий
фриз**, не краш.

**Логи `bb_diag_p0.log`:**
- `BEGIN handleBindApplication` / `BEGIN installProviders n=7` без `END`
- WATCHDOG 8s / 16s / 24s
- main `WAITING` в:
  `FirebaseInitProvider.onCreate` → `FirebasePerfRegistrar.lambda$getComponents$0`
- другие потоки (`AsyncTask #2`, Measurement Worker) `BLOCKED` на том же
  Firebase component lock (`ai.for`)

Пустой `catch (Throwable)` в `installProviders` бесполезен: поток не бросает
исключение, а ждёт lock, который никогда не отпустится.

**Исправление:**
- `BActivityThread.isUnsafeInitProvider` — skip
  `FirebaseInitProvider` / Firebase Performance init-провайдеров на main
  во время bind. UI Root Checker от Firebase не зависит.
- Skip применяется в `installProviders`,
  `installProvidersWithErrorHandling` и `installContentProvider`.
- DiagnosticLogger пишет `skip provider` / `installProvider` /
  `installProvider done` по каждому провайдеру, чтобы следующий фриз было
  видно без полного thread dump.

## Fix: Root Checker «проверить устройство» (`FirebaseApp is not initialized`)

Skip `FirebaseInitProvider` разблокировал bind, но кнопка проверки в Root Checker
падает на main:

`IllegalStateException: Default FirebaseApp is not initialized`

(выглядит как «зависло» — процесс умирает в `onClick`).

**Исправление (без повторного `FirebaseInitProvider.onCreate`):**
- `core/env/FirebaseCompat.java` — стрип metadata Firebase Performance, затем
  `FirebaseApp.initializeApp(Context)` через reflection. `ClassNotFound` игнорируется.
- `BActivityThread.handleBindApplication` — `stripUnsafeMetadata` сразу после
  `getPackageInfo`; `initializeAppSafely` после `installProviders`.
- `IPackageManagerProxy` — тот же стрип в `getPackageInfo` / `getApplicationInfo`
  / installed lists, чтобы поздние запросы PM не вернули Performance registrar.

## Fix: клоны не видят виртуальный root (`posix_spawn` / noexec / File.exists)

Политика по умолчанию — **полноценный виртуальный root** без root на хосте
(`DEFAULT_ROOT_ENABLED = true`). Скрытие — отдельная настройка
(`isHideRoot` / Root Manager DenyList), не дефолт.

**Почему чекеры не видели root, хотя `ROOT-EMULATED` уже писался:**
1. Android 8+ `Runtime.exec` / `ProcessBuilder` идут через `Os.posix_spawn`, не
   `fork`+`execve`. Старый хук `execve` требовал `sInForkedChild` и **никогда
   не срабатывал**.
2. Fake su лежит в app-data (`noexec`) — даже при rewrite-off ядро не исполняет файл.
3. `File.exists("/system/xbin/su")` идёт в `UnixFileSystem.getBooleanAttributes0`,
   который был **определён, но не зарегистрирован** в `UnixFileSystemHook::init`.

**Исправление:**
- `core/env/RootShellEmulator.java` — rewrite `su` / `id` / `whoami` / `which su` /
  `getenforce` на `/system/bin/sh` (`printf` или `sh -c <cmd>`).
- `OsStub`: хуки `posix_spawn`/`posix_spawnp`/`execve` мутируют path+argv;
  `access` на su-путях → true; `stat` st_uid/st_gid → 0. Мёртвый `Fork`/`sInForkedChild` убран.
- `UnixFileSystemHook.cpp`: регистрация `getBooleanAttributes0(String)` и
  `(File)` + `checkAccess0`, чтобы `File.exists` / `canExecute` видели IO-карты su.
- Нативные Dobby-хуки `access`/`stat`/`getuid` **не** возвращаются (чёрный экран).

## Fix: краш Root Checker (`Default FirebaseApp is not initialized`) — повтор

**Лог (bb_diag_p1.log):** два UNCAUGHT за 2.3 с после bind:
- `AsyncTask #5` (авто-проверка, ~90 мс после bind):
  `IllegalStateException: Default FirebaseApp is not initialized in this process
  com.joeykrim.rootcheck` из `ai.for(SourceFile:55)`;
- main (клик «Verify Root»): `oK$try.onClick → VJ.f → vi.do → ai.for(SourceFile:55)`.

**Причина:** `FirebaseCompat.initializeAppSafely` искал ровно
`com.google.firebase.FirebaseApp`. Root Checker собран с R8 full mode — этот
класс **переименован** (стектрейс целиком обфусцирован: `ai.for`, `bi.do`,
`iz$if`), поэтому поиск честно падал CNFE → лог `no Firebase in this APK` →
инициализации нет → любой analytics-вызов приложения убивает процесс.
При этом `com.google.firebase.provider.FirebaseInitProvider` в APK есть
(движок сам логирует его skip во время bind — manifest-имена не обфусцируются).

**Исправление (`core/env/FirebaseCompat.java`):**
- multi-candidate поиск класса по нескольким classloader'ам (context /
  applicationContext / Application-класса / context thread /
  `LoadedApk.mClassLoader` через reflection / пересобранный `PathClassLoader`
  по sourceDir+splitSourceDirs);
- если `FirebaseApp` не найден — **fallback: запуск собственного
  `FirebaseInitProvider`** клона (newInstance → attachInfo → onCreate), т.е. тот
  же код, который движок пропускает при bind, но уже после bind и со стрипнутым
  Firebase Performance metadata (дедлок bind не воспроизводится);
- каждый шаг пишется в DiagnosticLogger — следующий лог сразу покажет, каким
  loader'ом нашёлся класс и что вернул `initializeApp`.

## Fix: Elixir Loader «требуется рут» — недостающие паттерны проб root

**Лог (bb_diag_p0.log):** `ROOT-EMULATED` активен, но ни одной строки
`root-shell rewrite ...` — Elixir **не** exec'ает `su`/`which`/`id` напрямую,
значит проверяет root другими путями, которые rewrite не покрывал.

**Исправление (`core/env/RootShellEmulator.java`):**
- shell-обёртки: `sh -c 'which su'` / `sh -c 'su -c id'` / `sh -c 'id'` /
  `sh -c 'whoami'` / `sh -c 'getenforce'` / `sh -c 'test -e /system/xbin/su'` /
  `[ -e ... ]` / `sh -c 'ls <su>'` — теперь эмулируются (раньше argv[0] был сам
  shell, и реальные бинари честно отвечали «not found»);
- прямой `ls <su-path>` → печать пути, exit 0;
- Magisk-позиционные формы: `su 0 id` / `su - id` / `su root whoami`;
- **голый `su` — эмуляция интерактивного root-shell** (`BARE_SU_SCRIPT`):
  сразу печатает `uid=0(root) ...`, отвечает на команды из stdin (`id`, `whoami`,
  `getenforce`, `which su`) и завершается по EOF stdin / 2 с простоя — чекеры
  `exec("su") + waitFor()` не висят, а читающие stdout получают `uid=0`
  (реальный su над pipe-stdin просто ждёт ввода — чекер висел вечно);
- диагностика: `OsStub` (`access`, `stat`) и rewrite логируют su-пробы тегом
  `RootProbe` в DiagnosticLogger — по следующему логу будет видно, как именно
  клоны щупают root.

**Ограничения (без изменений):** нативные `access/stat/getuid` из JNI-библиотек
приложения по-прежнему не перехватываются (риск чёрного экрана, см. фикс
`fix: frozen black screen...`); Java-слой (File.exists, Os.*, Runtime.exec)
покрыт полностью.

## Fix: Root Checker «Error! Root access is not properly installed» — пустой stdout у голого su

**Тест пользователя (сборка 4ad1d74):** краша Firebase больше нет (фикс
`FirebaseCompat` сработал), приложение не зависает, но «Verify Root» отвечает
«Error! Root access is not properly installed or configured on this device».

**Причина:** Root Checker проверяет доступ так: `exec("su")` → пишет `id\n` в
stdin процесса → читает **первую строку stdout** → ищет `uid=0`. После
предыдущего фикса голый `su` превращался в `sh -c 'exit 0'`: процесс завершался
(зависание убрано), но stdout был **пустой** → `readLine()` вернул null →
чекер честно ответил «not properly installed».

**Исправление (`core/env/RootShellEmulator.java`):** голый `su` теперь —
эмуляция интерактивного su с уже выданным доступом (`BARE_SU_SCRIPT`):
- сразу печатает строку `uid=0(root) gid=0(root) groups=0(root)
  context=u:r:magisk:s0` (покрывает чекеры, читающие stdout до записи в stdin);
- отвечает на команды из stdin как настоящий su после grant: `id` →
  `uid=0(root) ...`, `whoami` → `root`, `getenforce` → `Enforcing`,
  `which su` → `/system/xbin/su`;
- завершается по EOF stdin или после 2 с простоя → `exec("su") + waitFor()`
  висеть не может в принципе;
- даже если ROM-овый `sh` не поймёт `read -t`, скрипт завершится сразу после
  первой строки — Root Checker всё равно получит свой `uid=0`.

**Проверено локально (bash, контракт Root Checker):** write `id` → read →
`uid=0(...)` ✓; read-first ✓; stdin-ответы whoami/getenforce/which su ✓;
выход за ~2 с при открытом stdin ✓; multi-command сессия ✓.



## Fix: виртуальный root действительно исполняет команды + полная диагностика root-цепочки

**Симптом:** рут-чекеры (Root Checker и др.) не видели root; приложения,
которые реально используют su (`su 0 <cmd>`, интерактивный `su`), не получали
исполнения команд.

**Причина:** `RootShellEmulator.emulateSuPositionalArgs` обрабатывал только
два захардкоженных случая (`id`, `whoami`) и молча возвращал `null` для любых
других команд после `su 0`/`su -` — команда терялась. Bare `su` отвечал на
известные пробы canned-выводом, но произвольные команды из stdin не исполнял.
su-обёртка ScriptEngine ломала `su -c <cmd>` (`sh -c "-c cmd"`).

**Изменения:**
- `core/env/RootShellEmulator.java`:
  - `su 0 <cmd>` / `su - <cmd>` / `su root <cmd...>`: пропускаются флаги
    (`-`, `--`, `-mm`, `-u 0`, `--user 0`), первая не-флаговая лексема начинает
    команду; известные пробы → canned root-вывод, всё остальное — **реальное
    исполнение** `/system/bin/sh -c <cmd>` внутри песочницы.
  - Bare `su` → интерактивный прокси-shell `rootfs/scripts/su_proxy.sh`
    (создаёт IOCore): отвечает на identity-пробы (`id`→uid=0 …) и исполняет
    любую другую строку stdin через `eval`; выход по EOF / `exit` / 3×2 s idle.
    Fallback — прежний inline-скрипт (probe-only).
  - Каждое решение о рерарайте логируется (`[ROOT-DECISION] shell rewrite …`).
- `core/IOCore.java`: генерация `su_proxy.sh` в `setupVirtualRoot`; логи
  решений (ветка root вкл/выкл, native fake-root, параметры rootfs).
- `script/ScriptEngine.java`: su-обёртка теперь понимает `su -c/--command`,
  `su 0/-/root/--`, `-v/--version`, bare `su`; лог запуска скрипта.
- **Новый `utils/RootLogger.java`** — root-диагностика в
  `root_diag_{main,server,pN}.log` (рядом с bb_diag_*): решения сервера
  (`BRootManagerService.isRootEnabled` с указанием источника: явная запись /
  GMS-список / глобальный переключатель / дефолт), доставка конфига
  (`ProcessRecord.getClientConfig`), инициализация в клиенте (`RootConfig.init`
  с источником и диагностикой NULL/mismatch AppConfig), uid-спуф OsStub
  (sampled), su-path пробы stat/access, shell-рерарайты, GG-env, ошибки
  FirebaseCompat. Защита от редиректа путей лог-каталога.
- `utils/DiagnosticLogger.java`: `getProcLabel()`; `root_diag_*` добавлен в
  protected-пути.
- **`PROJECT_MAP.md`** — полная архитектурная карта проекта для ИИ/человека:
  модули, дерево пакетов, оркестраторы, прокси-таблица, native-слой, AIDL,
  UI, детальная root-подсистема с цепочкой решений и таблицей точек отказа.

**Проверка:** Настройки → Root Manager → включить root для приложения →
запустить → в `/storage/emulated/0/Android/data/<host>/files/root_diag_pN.log`
видна вся цепочка; рут-чекер должен показать root; приложение, вызывающее
`su 0 ls`, получает реальный вывод.

## Fix: нативный execvp-хук — Runtime.exec наконец доходит до эмуляции su

**Симптом (логи 31.08):** вся Java-цепочка root работает (сервер: rootEnabled=true,
RootConfig: ROOT-EMULATED, getuid→0, su-файлы доступны), Root Checker больше не
падает на Firebase, но вердикт «Root access is not properly installed»; в
root_diag_pN.log ноль строк `shell rewrite` — эмуляция su не срабатывает при
реальном запуске su. Elixir Loader: «Для продолжения необходим Root-доступ».

**Причина (подтверждено исходниками AOSP):** на Android 7+ `Runtime.exec`/
`ProcessBuilder` идут через `java.lang.UNIXProcess.forkAndExec` — форкнутый
ребёнок вызывает libc **`execvp` напрямую**, мимо `Libcore.os`. Java-хуки
OsStub (`execve`/`posix_spawn`) на этот путь не срабатывают вообще (предпосылка
прежних фиксов была неверной). su-файлы «находятся», но их исполнение падает
(реального файла нет / noexec) → «found but not properly installed».

**Изменения (`cpp/ProcHook.cpp`):**
- Новый ленивый Dobby-хук libc `execvp` (ставится только для root-эмулируемых
  процессов, вместе с open/openat; рантайм-гейт `g_fake_root_enabled`).
  Функция большая (PATH-поиск), безопасна для trampoline — в отличие от
  stat/access/getuid, которые намеренно не трогаются.
- Логика зеркалит RootShellEmulator.java: прямые `id`/`whoami`/`getenforce`/
  `which su` → canned root-вывод; shell-wrapped пробы (`sh -c 'su -c id'`,
  `test -e <su>`, `ls <su>`) → эмуляция; `su -c/--command <cmd>`, позиционная
  форма `su [-|--|0|root|-u N|--user N] <cmd...>`, bare `su` → proxy-shell
  `rootfs/scripts/su_proxy.sh` (реальное исполнение команд в песочнице).
  Всё остальное — без изменений (оригинальный execvp).
- Код между fork и exec без malloc/std::string — только фиксированные буферы
  (безопасность форк-чайлда: блокировка malloc-лока другим потоком).

**Проверка:** рут-чекер → «Root access is properly installed»; приложения с
`su -c <cmd>` получают реальный вывод; в logcat тег `ProcHook: exec ... ->`
показывает каждое перенаправление.

## 2026-08-31 (2): нативный лог exec-решений + su -V + долгоживущая su-сессия

**Симптом (логи logvm2):** Root Checker прошёл («properly installed») — execvp-хук
работает. Elixir Loader по-прежнему «Ошибка инициализации. Для продолжения
необходим Root-доступ»: в root_diag_p1.log Java-цепочка включена, но после init
ни одного exec-вызова не видно — проверка идёт нативно, в логcat не попала.

**Изменения:**
- `ProcHook.cpp`: нативный файловый логгер `nlog()` → `root_native_<proc>.log`
  рядом с bb_diag/root_diag (путь передаётся из IOCore через новый JNI-метод
  `NativeCore.setExecLogPath`). Пишет: установку каждого хука (open/openat/
  execvp, включая ошибки Dobby), каждый execvp с полным argv, каждое решение
  (rewrite/canned/proxy/passthrough) и перенаправления open. Только
  open/write/close и фиксированные буферы — безопасно в fork-чайлде.
- `su -V` / `--version-code` → canned `25200` (libsu спрашивает числовой код
  интерфейса до старта сессии).
- `su_proxy.sh`: idle-таймаут сессии продлён с 6 с (3×2 с) до 15 мин
  (450×2 с) — root-приложения на libsu держат su-сессию открытой всё время
  работы, а не делают один запрос как рут-чекер. EOF/`exit` завершают сразу.
- `DiagnosticLogger.isProtectedPath`: `root_native_` тоже защищён от
  IO-redirect.

**Диагностика дальше:** если root-приложение снова отклоняет root — смотреть
`root_native_<proc>.log`: каждая строка `execvp '<path>' argv=[...]` и решение
рядом показывают, что именно приложение исполняло и что ему ответила эмуляция.

## 2026-09-02: логи loggg.zip — Elixir доходит до `su`, вердикт всё равно отрицательный; сессионный лог su-прокси

**Логи (loggg.zip, 2026-09-02, сборка ede97ba):** вся Java/server-цепочка корректна:
`BRootManagerService.isRootEnabled(com.elixir.loader,0)=true [default]` →
`ProcessRecord rootEnabled=true` → `RootConfig ROOT-EMULATED (AppConfig,
server-resolved)` → `setupVirtualRoot done (suRules=11, markerRules=6, proxyScript
есть)` → native `open/openat/execvp installed`. Java-слой тоже: все `Os.access`
su-пути → allowed, `getuid -> 0` ×5.

**root_native_p0.log (новое):** Elixir **исполняет голый `su` нативно** — дважды
(19:33:11.852 pid=11063 и 19:33:15.779 pid=11163, retry через ~4 с), оба раза хук
принимает решение `bare su -> proxy shell su_proxy.sh` (прокси запускается, `sh`
виден в логе). Параллельно после каждого `su` — **голый `sh` argv=[sh]** (без
аргументов; join_args логирует argv целиком, усечения нет). Несмотря на это —
вердикт «Для продолжения необходим Root-доступ». Чего не видно: **разговора
Elixir с su-сессией** (что пишется в stdin прокси и что он отвечает) — прокси
ничего не логировал.

**Изменения (инструментация):**
- `core/IOCore.java`: `su_proxy.sh` теперь ведёт сессионный лог
  **`root_su_session.log`** рядом с bb_diag/root_diag/root_native (в той же
  diagnostics-папке): `===== su session start pid/ppid/args`, `<< banner`,
  каждая полученная строка stdin (`>> [line]`), `<< exit command` /
  `<< idle timeout`, `===== su session end`. Контент строит
  `buildSuProxyScript(sessionLogPath)`; путь берётся из
  `DiagnosticLogger.getLogDir()`, fallback — `rootfs/logs/su_session.log`.
  Автообновление скрипта на устройстве — по прежнему size-эвристикой в
  `ensureSuProxyScript` (длина изменилась → перезапись при следующем bind).
- `cpp/ProcHook.cpp`: если argv не влез в буфер nlog (payload > ~1 КБ), теперь
  пишется явная пометка `...<truncated, total=N>` вместо тихой потери аргументов.

**Проверено локально (bash, извлечение скрипта прямо из Java-билдера):** баннер
`uid=0(root)...` первой строкой; ответы `id`/`id -u`/`whoami`/`getenforce`/
`which su`; произвольная строка stdin исполняется реально; `exit` завершает
сессию (rc=0); мгновенный EOF завершает сессию быстро (rc=0); сессионный лог
содержит старт/баннер/все команды/причину завершения. PASS по всем пунктам.

**Дальше:** пересобрать APK, запустить Elixir, прислать
`root_su_session.log` + свежий `root_native_p0.log`. Если сессия пустая
(команд нет) — Elixir не разговаривает с su, а ждёт/таймаутит, и проверка
находится в другом месте (нативные access/stat/getuid из JNI — они намеренно
не хукаются); если команды видны — сравним их с ответами прокси и закроем пробел.

## 2026-09-02 (2): newlog.zip — прокси умирает на SIGPIPE; fix: `trap '' PIPE`

**Логи (newlog.zip, 21:05, сборка eeb5285):** Java/native-цепочка снова вся
зелёная (ROOT-EMULATED, setupVirtualRoot done, хуки installed, getuid→0).
Elixir нативно exec-ит голый `su` → `bare su -> proxy shell` (21:05:33.939
pid=30470, retry 21:05:37.659 pid=30555 — через ~3.7 с). Параллельно после
каждого su — голый `sh` argv=[sh] (54/16 мс спустя).

**Ключевое — root_su_session.log:** в нём только две строки
`===== su session start` — и ВСЁ. Ни баннера, ни команд, ни завершения. Скрипт
успел выполнить только ПЕРВУЮ строку (echo в файл); следующая — `printf` баннера
в stdout — убила процесс. Хук исполняет прокси как `execvp("sh", {sh, скрипт})`
с наследованием дескрипторов приложения: если чекер закрывает/не читает
stdout-пайп su-чайлда, запись баннера получает SIGPIPE → прокси умирает до
того, как ответит на stdin или завершится по EOF с кодом 0. Двойной
детерминированный повтор — ровно эта картина. Чекер Elixir, судя по retry
через ~3.7 с, ждёт от `su` завершения (или ответа в узкое окно) — мёртвый
прокси трактуется как провал.

**Fix:** `su_proxy.sh` начинается с `trap '' PIPE` — записи в закрытый stdout
возвращают EPIPE вместо смерти шелла. Сценарий Elixir теперь: баннер-запись
молча проваливается → `read` получает EOF мгновенно → прокси завершается
с кодом 0 за миллисекунды → wait-for-exit чекер должен засчитать root. Если
чекер всё же пишет команды в stdin — сессия теперь доживает и логируется
целиком.

**Проверено локально (bash, извлечение из Java-билдера):** 9/9 PASS, включая
воспроизведение паттерна Elixir (stdin=/dev/null, stdout=пайп с закрытым
read-end): rc=0, в сессионном логе есть старт/баннер/конец. Прежние
контракты (баннер первой строкой, id/whoami/getenforce/which su, eval,
exit, EOF) не сломаны. Скрипт автообновится на устройстве (size-эвристика).

**Дальше:** пересобрать/установить, повторить Elixir, прислать
`root_su_session.log`. Если проверка снова падает — по логу сессии будет
видно, что именно чекер пишет в su и чего ждёт.
