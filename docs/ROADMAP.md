# GhostCraft: план

Свой лаунчер Minecraft Java для Android (Poco X5 Pro, Snapdragon 778G, Adreno 642L), без форков Pojav/Zalith/FCL. Весь код лаунчера наш. Сторонние компоненты (OpenJDK, LWJGL, Mesa) собираем сами из исходников апстрима нашими скриптами.

## Этап 1 — лаунчер и синк с Prism ✅ (2026-10-25)

- [x] Приложение Kotlin + Compose в стиле Ghost (палитра, шрифты и стикеры Sekai из Ghost Phone).
- [x] `tools/ghostcraft-sync`: разрешает рецепт запуска как Prism (`mmc-pack.json` → `meta/` → classpath, mainClass, аргументы, asset index), шлёт инстанс, библиотеки и ассеты потоковым tar через `adb exec-in`, отправляет только изменённое.
- [x] Список инстансов с группами Prism, страница инстанса: моды (имена из метаданных jar, вкл/выкл через `.disabled` как в Prism), миры, ресурспаки, шейдеры, предупреждения.
- [x] Экран «Ядро запуска»: что из рантайма уже есть на телефоне.
- [x] Проверить на телефоне: установка, синк, UI (2026-11-04).

## Этап 2 — ядро запуска (главная работа)

Цель: ванильный 1.21.1 доходит до главного меню.

1. ✅ **NDK и тулчейн** (2026-10-25): NDK r29 (`ndk;29.0.14206865`) в `~/.local/opt/android-sdk/ndk`, boot-JDK Temurin 21 в `~/.local/opt/jdk21`.
2. ✅ **OpenJDK 21 для Android arm64** (2026-10-25): `runtime/jdk/build.sh` собирает `jdk-21.0.9+10` из апстрима (исходники и сборка в `~/Projects/GhostCraft-build`, результат `out/jdk21-android-arm64.tar.xz`, 38 МБ). Server VM (C1+C2, G1/ZGC/Shenandoah), headless, API 31. Патч `runtime/jdk/patches/0001-android-bionic.patch`:
   - ELF-макросы, `confstr(_CS_GNU_*)`, `dlinfo`, `netinet/in.h`, `__xpg_strerror_r` — отличия bionic от glibc;
   - Serviceability Agent (libsaproc) отключён для Android;
   - RUNPATH вместо RPATH (линкер Android игнорирует DT_RPATH);
   - **тегирование кучи выключается в `os::init()`**: hotspot вшивает C-heap адреса в код 48-битными константами, тег теряется и `free()` абортит процесс.
   - Заглушки сборки: пустые `libpthread.a`/`librt.a`, `libasound` (libjsound в рантайм не кладётся).
   - Проверено на телефоне: `java -version`, JIT, потоки, файлы, gzip, GC, сокеты, DNS, HTTPS к Mojang.
   - Лаунчер обязан передавать `-Djava.io.tmpdir` (нет `/tmp`) и `-Duser.timezone` из Android (иначе GMT); в манифесте `android:allowNativeHeapPointerTagging="false"` для надёжности.
   - JDK 17/8 для старых версий — потом тем же скриптом.
3. ✅ **Запуск JVM внутри процесса приложения** (2026-10-25): `app/src/main/cpp/launcher.c` → `dlopen(libjli.so)` → `JLI_Launch` (как `bin/java`: понимает `--add-opens`, `-p`, `@argfiles`), процесс `:game` (`GameActivity`), командная строка как у Prism в `Launch.kt`, вывод в `minecraft/logs/ghostcraft-launch.log` с живым просмотром. Проверено: VanillaTech-Fabric-1.21.1 (288 модов) проходит Fabric, миксины, Datafixer и падает на `liblwjgl.so` — то есть дальше нужен шаг 4. Что выяснилось:
   - JDK копируется с `/sdcard` во внутреннюю память (`filesDir/runtime/jre-N`, сверка по размеру/mtime): с общего хранилища `.so` не загрузить.
   - `chdir` в папку игры (log4j пишет `logs/` относительно cwd); `LD_LIBRARY_PATH` сбрасывается, иначе libjli перезапускает процесс через exec.
   - `-Djdk.lang.Process.launchMechanism=FORK`: `posix_spawn` идёт через `jspawnhelper` (exec из данных приложения запрещён), `vfork` в процессе ART роняет родителя.
   - `-Djna.tmpdir` во внутренний кэш. Сам JNA из Maven собран под glibc (`libc.so.6`) → oshi без JNA, Minecraft это переживает. **TODO**: собрать `libjnidispatch.so` под Android.
   - Офлайн-ник пока из настроек (`nick`, по умолчанию Player) — поле в UI добавить.
4. ✅ **LWJGL 3.3.3 arm64** (2026-10-26): `runtime/lwjgl/build.sh` → `out/lwjgl/` (плоская папка для `-Dorg.lwjgl.librarypath`), на телефон через `ghostcraft-sync runtime`, в приложении копируется в `filesDir/runtime/lwjgl`.
   - Свои модули из коммита релиза `f0ec143`: генератор LWJGL (Ant + Kotlin-шаблоны, **все** привязки — иначе `JNI.c` ядра не совпадёт с `JNI.class` из Maven: 1605 методов) и компиляция NDK-clang с флагами `config/linux/build.xml`: `liblwjgl` (+ libffi 3.4.4 статически, `--disable-exec-static-tramp`), `liblwjgl_opengl`, `liblwjgl_stb`, `liblwjgl_tinyfd`. `liburing/compat.h` подменён (`runtime/lwjgl/include`), т.к. NDK уже определяет его структуры.
   - Сторонние из коммитов форков LWJGL-CI, что в `.git`-файлах natives-jar: OpenAL Soft (OpenSL ES, libc++ статически), jemalloc (`je_`-префикс), FreeType (без внешних зависимостей). Проверено: нет символов вне bionic, все функции, что LWJGL берёт через `apiGetFunctionAddress`, на месте.
   - На телефоне: liblwjgl и модули грузятся, игра падает на `libglfw.so` (шаг 5). Предупреждение «Incompatible Java and native library versions» — сверка SHA1 с официальной arm64-сборкой, безвредно.
5. ✅ **Свой GLFW** (2026-10-26): `app/src/main/cpp/glfw/` → `libglfw.so` в APK. Все 127 функций, которые LWJGL 3.3.3 берёт из GLFW 3.4 (`GLFW`, `GLFWNativeEGL`, `GLFWVulkan`).
   - Окно — `GameSurface` (`SurfaceView`), контекст — EGL. Библиотека EGL выбирается переменной `GHOSTCRAFT_EGL` (по умолчанию системная), GL — `GHOSTCRAFT_GL`. Если desktop GL нет, контекст откатывается на GLES 3.2 с записью в журнал.
   - LWJGL получает `-Dorg.lwjgl.glfw.libname` и `-Dorg.lwjgl.opengl.libname` на этот же файл: `libglfw` отдаёт `glXGetProcAddress` через `eglGetProcAddress`. На шаге 6 достаточно подменить EGL.
   - ART грузит библиотеку через `GlfwBridge` до старта JVM, JVM — `dlopen` того же пути в том же пространстве линкера, так что состояние общее. Поток UI кладёт события в очередь, `glfwPollEvents` раздаёт их в потоке игры.
   - Поверхность при сворачивании: UI-поток публикует `NULL`, игра на ближайшем swap пересаживает контекст на pbuffer 1×1 и отпускает окно (UI ждёт до 2 с), фон — 20 fps без вывода.
   - Ввод: в меню палец = курсор (нажатие с задержкой 90 мс, чтобы двухпальцевый тап не кликал левой), в мире (курсор захвачен) — свайп = камера, тап = ПКМ, удержание = ЛКМ; два пальца — прокрутка и ПКМ. Физические мышь (с pointer capture в мире) и клавиатура, экранная клавиатура без композиции, Back = Esc, буфер обмена в обе стороны. Геймпад — на шаге 7.
   - Проверено на телефоне `tools/glfw-test` (GLES-окно, цвет по курсору, события в журнал): 120 fps, касания, мышь, клавиши, кириллица с экранной клавиатуры, захват курсора, сворачивание и возврат, мягкое закрытие. Minecraft создаёт окно 2400×1080 и падает на `glGetString`: системный EGL Adreno даёт только GLES.
6. ✅ **OpenGL** (2026-10-26, мир — 2026-11-04): Mesa 26.2.5 — `runtime/mesa/build.sh` + `patches/0001-android-zink.patch`, результат `out/mesa` → `runtime/mesa` на телефоне.
   - Системный Vulkan Adreno 642L (1.1.128) не годится: Zink 26.x требует `robustness2.nullDescriptor`. Поэтому Zink грузит **Turnip** напрямую (`ZINK_VULKAN_LIBRARY`).
   - У Turnip нет Android WSI → вывод копированием: GLFW рисует в pbuffer и на swap делает `glReadPixels` в буфер `ANativeWindow` (`GHOSTCRAFT_PRESENT=copy`, отражение через `ANativeWindow_setBuffersTransform`).
   - Патч: surfaceless EGL принимает `ANativeWindow` (kopper + `VK_KHR_android_surface`, для драйверов с WSI), desktop GL на Android, своя `libsync`, без gralloc/libdrm.
   - Проверено: `zink Vulkan 1.3 (Adreno 7c+ Gen 3 (MESA_TURNIP))`, OpenGL 4.6 Core, тест 119 fps. VanillaTech (292 мода) доходит до меню (25–57 fps), тапы работают, создание мира началось — дальше не проверено.
   - 2026-11-04: **мир работает**. VanillaTech: меню ~105 с, создание мира и вход проверены, 45–48 fps в мире (масштаб 67%, дальность 6/6).
     - Главный враг — память, а не GPU: при `-Xmx` 3.6 ГБ и дальности 10/12 игра занимала 3.1 ГБ RAM + 1.8 ГБ swap, ~26 тыс. major faults/с, 0.5–2 fps. Живой объём кучи в мире ~1.7 ГБ, вне кучи — GPU ~650 МБ (`GL mtrack`), нативная куча ~450 МБ.
     - Флаги JVM в `Launch.kt`: G1, `UseStringDeduplication`, `Min/MaxHeapFreeRatio=10/25` (куча не раздувается до `-Xmx`), `G1PeriodicGCInterval`, `-Xlog:gc:stdout` (видно в logcat).
     - Экран «Настройки» в лаунчере: ник и масштаб рендера (50/67/75/100%, по умолчанию 67%: `setFixedSize`, растягивает компоновщик).
     - На телефоне в `options.txt` VanillaTech выставлено `renderDistance:6`, `simulationDistance:6` (оригинал — `options.txt.ghostcraft-bak`). При 10/12 даже с новыми флагами 3–5 fps.
     - Ассеты отправлены (797 МБ за 27 с по Wi-Fi), русский язык и звуки работают.
   - Дальше: 6b — вывод без копирования (AHardwareBuffer + ASurfaceControl), JNA; «телефонный профиль» дальностей, чтобы синк с ПК не возвращал 10/12.
7. ✅ **Экранное управление** (2026-11-04): `Controls.kt`, рисуется слоем Compose поверх игры в стиле Ghost (иконки Material Symbols, палитра).
   - Показывается только в мире (курсор захвачен) и без физической мыши. GameSurface отдаёт каждый палец сначала кнопкам; не взятый палец — камера (свайп — поворот, тап — ПКМ, удержание — ЛКМ). Джойстик и камера работают одновременно.
   - Джойстик плавающий в своей зоне (база следует за пальцем), 8 направлений; толчок вперёд за край — бег. Кнопки: прыжок, красться и бег (переключатели), удар, использовать, инвентарь, выбросить, чат (сразу открывает клавиатуру), вид (F5), пауза.
   - Касание хотбара выбирает слот: его место считается по правилу Minecraft `calculateScale` из `guiScale` и размера кадра.
   - Клавиши берутся из `options.txt` инстанса (`key_key.*`), переназначения из Prism работают; мышиные бинды тоже.
   - При уходе в меню все клавиши отпускаются (Shift в инвентаре дал бы shift-клики), включённые переключатели возвращаются в мир.
   - Клавиатура для чата сдвигает игру вверх на свою высоту, не меняя размер окна.
   - Редактор («…» → геймпад): ставит игру на паузу, перетаскивание, размер выбранной кнопки, общая прозрачность, сброс. Раскладка — SharedPreferences `controls`.
   - Проверено на телефоне (adb input, один палец): джойстик, хотбар, переключатели, инвентарь, чат, редактор. Мультитач (джойстик + камера) через adb не проверить — нужна проверка руками.
   - Свои кнопки (2026-11-04): в редакторе «+» → любая клавиша/кнопка мыши (`KeyChoices`), подпись, «удерживать» или «переключатель»; второй тап по своей кнопке — правка, корзина — удалить. Встроенные кнопки можно скрыть (вернёт «сброс»). Раскладка общая для всех инстансов.
   - Дальше: геймпад (Android `InputDevice` → `glfwGetGamepadState`), профили раскладок на инстанс, кнопка «закрыть» для экранов-меню.

## Этап 3 — самостоятельность от ПК

- Вход через Microsoft (device code flow) прямо на телефоне. Пока офлайн-ник для одиночной игры.
- ✅ **Инстансы на телефоне** (2026-11-04): «+» на главной → «Новый инстанс». `Install.kt` берёт метаданные с `meta.prismlauncher.org` (тот же формат, что синк читает из Prism) и собирает тот же `ghostcraft.json` — порт `resolve()` из `ghostcraft-sync`. Файлы ложатся в общие `libraries/`, `assets/` как у Prism, качаются параллельно (12 потоков, sha1, `.part`).
  - Vanilla / Fabric / Quilt / NeoForge / Forge, любая версия 1.13+. LWJGL всегда 3.3.3 (наши нативы). 26.x — нужна Java 25, 1.12.2 и старше — LWJGL 2: в списке серые с причиной.
  - NeoForge/Forge через ForgeWrapper: `Launch.kt` передаёт `-Dforgewrapper.librariesDir/installer/minecraft`, процессоры установщика идут в JVM игры при первом запуске. Проверено: NeoForge 21.11.48 до меню за ~50 с.
  - `maven.neoforged.net` у провайдера заблокирован: без VPN NeoForge не скачать. Решено не обходить — таймаут превращается в ошибку «хост не отвечает, включи VPN».
  - Новым инстансам пишется телефонный `options.txt`: язык системы, дальность 6/6, без онбординга (`Recipes.phoneOptions`).
- ✅ **Сборки Modrinth** (2026-11-04): вкладка Modrinth — поиск (по популярности, когда запрос пуст), версии с отметкой несовместимых, авто-выбор первой подходящей. `.mrpack`: моды из `modrinth.index.json` (без server-only), `overrides/` + `client-overrides/` (пути проверяются на выход из папки), иконка. Проверено: Fabulously Optimized 12.3.0 (1.21.11, 50 модов) — ~30 с установка, 60–70 fps в мире.
  - `PhoneIncompatible` (Instances.kt): моды, которые на телефоне ломают игру, выключаются как в Prism (`.disabled`) при установке и перед каждым запуском. Пока там Ixeris — переносит GLFW в свой поток, касания не доходят.
- Поиск и установка отдельных модов в инстанс (Modrinth/CurseForge), как в Prism.
- Обратный синк миров на ПК (`ghostcraft-sync pull`), разрешение конфликтов по времени.

## Этап 4 — производительность и старые версии

- Подбор флагов JVM (G1/ZGC, `-Xmx` по свободной памяти), профили для 778G.
- Sodium/Embeddium и Iris поверх Zink: проверить, где ломается.
- LWJGL 2 → 3 мост для 1.7.10 (GTNH): своя реализация `org.lwjgl.opengl.Display`/`Mouse`/`Keyboard` поверх LWJGL 3 или запуск GTNH на Java 21 через lwjgl3ify.
- Палитра из Ghost на ПК (как в `ghost-phone-sync`).

## Известные ограничения

- Моды с нативными x86-библиотеками (`.so`/`.dll` в инстансе, например Effekseer) на ARM не загрузятся. Синк их не шлёт и показывает предупреждение.
- GTNH и ATM10 очень тяжёлые для 6–8 ГБ ОЗУ телефона. Ориентир — сборки помельче.
