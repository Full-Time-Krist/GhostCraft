# CLAUDE.md

GhostCraft — свой лаунчер Minecraft Java для Android (Poco X5 Pro, Snapdragon 778G, Adreno 642L) в стиле Ghost.
Весь код лаунчера наш, без форков Pojav/Zalith/FCL. OpenJDK, LWJGL и Mesa собираем сами из апстрима своими скриптами.
Инстансы приходят из Prism Launcher на ПК. Общение с пользователем — на русском.

План и подробности по каждому шагу — `docs/ROADMAP.md` (читать перед работой), обзор — `README.md`.

## Состояние (2026-11-04)

- Этап 1 готов: UI на Compose (палитра/шрифты/стикеры из Ghost Phone), `tools/ghostcraft-sync`, моды/миры/паки.
- Этап 2 (ядро запуска):
  - готовы шаги 1–5: NDK, OpenJDK 21, JVM внутри приложения, нативы LWJGL 3.3.3, свой GLFW;
  - проверено на `VanillaTech-Fabric-1.21.1`: окно 2400×1080 создаётся, падение на `glGetString` — системный EGL даёт только GLES;
  - шаг 6 готов (2026-11-04): Zink + Turnip (`runtime/mesa/build.sh`), VanillaTech (292 мода) играется в мире на 45–48 fps при масштабе 67% и дальности 6/6; вывод пока копированием (6b — без копирования);
  - узкое место — ОЗУ телефона (7.3 ГБ, zram): следить за `VmSwap` и `pgmajfault`, GC-лог идёт в logcat (`GC(`), счётчик кадров — `[GLFW] presented`;
  - отладка Mesa/ввода без пересборки: `instances/<id>/ghostcraft.env` (KEY=VALUE), например `GHOSTCRAFT_INPUT_LOG=1`;
  - сборка Mesa: venv `~/.local/opt/mesa-venv` (meson, mako, PyYAML);
  - шаг 7 готов (2026-11-04): экранное управление `Controls.kt` (джойстик, кнопки, хотбар, редактор раскладки); дальше — геймпад;
  - GC-лог при отладке: `JDK_JAVA_OPTIONS=-Xlog:gc:stdout` в `ghostcraft.env` (по умолчанию выключен, засорял журнал);
  - отдельный TODO — `libjnidispatch` (JNA) под Android.
- Экран «Настройки» (шестерёнка на главной): ник (`nick`) и масштаб рендера (`render_scale`, по умолчанию 0.67) — объект `Settings` в `Screens.kt`.
- Этап 3 начат (2026-11-04): инстансы любой версии 1.13+ (Vanilla/Fabric/Quilt/NeoForge/Forge) и сборки Modrinth создаются на телефоне — см. ROADMAP.
- `maven.neoforged.net` у провайдера пользователя заблокирован — NeoForge ставится только с VPN; обходов не делаем (решение пользователя).
- Репозиторий: github.com/Full-Time-Krist/GhostCraft (публичный); коммиты и push — только по просьбе.

## Раскладка

- `app/` — приложение `dev.krist.ghostcraft`, Kotlin + Compose, minSdk 31, только `arm64-v8a`.
  - `Launch.kt` — командная строка как у Prism и установка рантаймов во внутреннюю память.
  - `GameActivity.kt` — экран игры в процессе `:game` и живой журнал.
  - `Instances.kt` — модель `ghostcraft.json`.
  - `GameSurface.kt` — `GlfwBridge` (JNI к нашему GLFW), окно игры, касания/мышь/клавиатура → события GLFW.
  - `Controls.kt` — экранное управление: бинды из `options.txt`, логика пальцев, оверлей и редактор раскладки, свои кнопки.
  - `Install.kt` — создание инстансов на телефоне: сеть (`Net`), метаданные Prism (`Meta`), рецепт (`Recipes`, порт `resolve()` синка), задачи с прогрессом (`Jobs`).
  - `Modrinth.kt` — поиск и установка `.mrpack`; `Create.kt` — экран «Новый инстанс» и карточка прогресса.
  - `src/main/cpp/launcher.c` — `dlopen(libjli.so)` → `JLI_Launch`; вывод JVM идёт в файл журнала и дублируется в logcat (`GhostCraft-Game`).
  - `src/main/cpp/glfw/` — `libglfw.so`: GLFW 3.4 поверх `ANativeWindow` + EGL (`internal.h` описывает потоки и общее состояние).
- `runtime/jdk/build.sh` + `patches/0001-android-bionic.patch` — OpenJDK 21 (`jdk-21.0.9+10`) под bionic.
- `runtime/lwjgl/build.sh` + `include/liburing/compat.h` — нативы LWJGL и сторонние библиотеки (OpenAL Soft, jemalloc, FreeType).
- `tools/glfw-test/push.sh` — тестовый инстанс «GLFW test» (GLES-окно, события ввода в журнал) для проверки GLFW без Minecraft.
- `tools/ghostcraft-sync` — разрешает рецепт Prism, шлёт инстанс/библиотеки/ассеты/рантаймы на телефон.
- Вне репозитория: `~/Projects/GhostCraft-build/`.
  - исходники: `jdk21u`, `lwjgl3`, `lwjgl-deps`;
  - сборки: `jdk21-build`, `lwjgl-build`;
  - результат: `out/jdk21`, `out/lwjgl`.
  - Это сотни МБ–гигабайты, в репозиторий не класть.

## Окружение

- **Сборка:**
  - приложение собирается под `~/.local/opt/jdk17`;
  - boot-JDK для OpenJDK — `~/.local/opt/jdk21`;
  - SDK — `~/.local/opt/android-sdk`: NDK `29.0.14206865`, CMake `3.31.6`;
  - Ant — `~/.local/opt/ant`;
  - Gradle — `~/.local/opt/gradle`, без обёртки.
- **Память:** на ПК 7.6 ГБ ОЗУ. Сборку JDK держать на `JOBS=4`, генератор LWJGL — с `-Xmx1800m`. Фоновые задачи Claude Code могут быть сняты при нехватке памяти — тогда не перезапускать молча.
- **adb:** `~/.local/opt/platform-tools/adb`.
  - **USB через хаб идёт на 12 Мбит/с** (~1 МБ/с) и отваливается в offline. Работать по Wi-Fi: `adb tcpip 5555; adb connect 192.168.1.100:5555`, затем `ANDROID_SERIAL=192.168.1.100:5555` для adb и синка. После перезагрузки телефона режим tcpip сбрасывается.
  - Экран не гаснет при USB: `adb shell svc power stayon usb`.
  - `uiautomator dump` на Compose виснет и вешает adb — для проверки UI использовать `screencap` + `input tap`.
- **Не делать `pkill -f`/`pgrep -f` по шаблону**, который входит в текст твоей же команды: убьёт собственный shell.
- Данные на телефоне: `/sdcard/Android/data/dev.krist.ghostcraft/files/` (`instances/`, `libraries/`, `assets/`, `runtime/`).
  Журнал запуска: `instances/<id>/minecraft/logs/ghostcraft-launch.log`. Если `logs/` создало приложение, shell его не прочитает — читать `adb logcat -s GhostCraft-Game GhostCraft-GLFW`.
  - `GameActivity` не экспортирована: запускать через UI (тап по инстансу, затем по кнопке запуска). `monkey` кладёт новую `MainActivity` поверх игры — для возврата в игру используй «назад».

## Команды

```sh
JAVA_HOME=~/.local/opt/jdk17 ~/.local/opt/gradle/bin/gradle --offline -q assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

runtime/jdk/build.sh [clone|patch|deps|configure|make|package|all]       # MAKE_FLAGS=-k — собрать все ошибки за прогон
runtime/lwjgl/build.sh [clone|generate|libffi|lwjgl|openal|jemalloc|freetype|all]

tools/ghostcraft-sync list
tools/ghostcraft-sync runtime                          # out/jdk21 → runtime/jre-21, out/lwjgl → runtime/lwjgl
tools/ghostcraft-sync push "VanillaTech-Fabric-1.21.1" [--no-assets] [--saves] [--forget]
```

Патч JDK обновлять из рабочего дерева: `git -C ~/Projects/GhostCraft-build/jdk21u diff > runtime/jdk/patches/0001-android-bionic.patch`
(`build.sh patch` делает `git checkout -- .` и накладывает патчи заново).

## Подводные камни Android (уже решены, не ломать)

- **Тегирование кучи:** выключается в `os::init()` hotspot и `allowNativeHeapPointerTagging="false"` в манифесте. Hotspot вшивает C-heap адреса в код 48-битными константами, тег теряется, `free()` абортит процесс.
- **Линкер Android игнорирует DT_RPATH:** JDK линкуется с `--enable-new-dtags`.
- **`.so` нельзя грузить с `/sdcard`:** рантаймы копируются в `filesDir/runtime/` по подписи размер+mtime.
- **libjli перезапускает процесс через exec,** если задан `LD_LIBRARY_PATH`, поэтому он сбрасывается перед запуском.
- **Рабочая папка JVM:** `chdir` в папку игры, иначе log4j пишет `/logs`.
- **Запуск внешних команд:** `-Djdk.lang.Process.launchMechanism=FORK`. `posix_spawn` идёт через `jspawnhelper` (exec из данных приложения запрещён), а `vfork` в процессе ART роняет родителя.
- **Нет `/tmp` и часового пояса:** передаются `-Djava.io.tmpdir` и `-Duser.timezone`.
- **`libglfw.so` должен лежать на диске** (`useLegacyPackaging = true`): JVM грузит его по пути из `nativeLibraryDir`. ART обязан загрузить его первым (`GlfwBridge.setDisplay` в `onCreate`), иначе у игры и UI будут разные копии состояния.
- **EGL client API — на поток:** `glfwMakeContextCurrent` заново вызывает `eglBindAPI`. Дисплей EGL общий с HWUI — `eglTerminate` не вызывать.
- **Папку `Android/data/<pkg>` должна создать система** при первом открытии приложения. Если её создал adb, владельцем будет shell, и приложение ничего не прочитает. Синк открывает приложение сам.
- **Права на папки:** tar при синке создаёт родительские папки с umask shell (755) — приложение не могло добавлять свои загрузки. Синк теперь делает `chmod g+rwxs` на все папки после распаковки.
- **Синк:**
  - шлёт tar через `adb shell -T`, а не `exec-in`: тот возвращается, пока данные ещё в буфере adb;
  - выставляет файлам `g+rw`, иначе игра не перепишет свои конфиги.
- **ICU-регулярки Android требуют экранировать `}`:** `\$\{(\w+)\}`.
- **LWJGL:**
  - генерировать со **всеми** привязками, чтобы `JNI.c` совпал с `JNI.class` из Maven;
  - предупреждение «Incompatible Java and native library versions» безвредно (сверка SHA1 с официальной сборкой).
