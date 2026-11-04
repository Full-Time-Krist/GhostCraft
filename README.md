# GhostCraft

Свой лаунчер Minecraft Java для Android в стиле Ghost. Берёт инстансы из Prism Launcher на ПК: моды, конфиги, миры и уже разрешённый рецепт запуска.

Состояние и план — [docs/ROADMAP.md](docs/ROADMAP.md). Готовы управление сборками, синк, запуск собственной сборки OpenJDK 21 внутри приложения нативы LWJGL 3.3.3 под arm64 и свой GLFW поверх SurfaceView/EGL: игра создаёт окно и останавливается на отсутствии desktop OpenGL. Дальше Mesa (Zink + Turnip) под Android.

## Сборка

Как у Ghost Phone: JDK 17, Android SDK (platform 35), Gradle 8.9 без обёртки, плюс NDK r29 и CMake 3.31.6 из SDK для нативного лаунчера.

```sh
JAVA_HOME=~/.local/opt/jdk17 ~/.local/opt/gradle/bin/gradle assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Синк с Prism

```sh
ln -s "$PWD/tools/ghostcraft-sync" ~/.local/bin/
ghostcraft-sync list                         # инстансы Prism
ghostcraft-sync push "NeoForge-1.21.1"       # моды, конфиги, библиотеки, ассеты
ghostcraft-sync push "NeoForge-1.21.1" --saves   # плюс миры (перезапишет на телефоне)
ghostcraft-sync push X --stage /path         # собрать на ПК, без телефона
ghostcraft-sync runtime                      # наши Java и LWJGL (runtime/*/build.sh) на телефон
```

Сначала установи и хотя бы раз открой приложение: папку `Android/data/dev.krist.ghostcraft` должна создать система, иначе приложение не сможет читать присланное (синк сам откроет приложение, если папки нет).

Если телефон виден по USB только на скорости USB 1.1 (через хаб, `dmesg`: «not running at top speed»), синк идёт ~1 МБ/с. По Wi-Fi быстрее в разы:

```sh
adb tcpip 5555 && adb connect <ip телефона>:5555
ANDROID_SERIAL=<ip>:5555 ghostcraft-sync push X
```

Что делает синк:

- Разрешает рецепт запуска так же, как Prism: компоненты `mmc-pack.json` → `meta/<uid>/<version>.json` → classpath, main class, аргументы, asset index. Пишет его в `instances/<id>/ghostcraft.json`, так что телефону не нужны установщики загрузчиков. Для Forge/NeoForge берёт уже пропатченные jar, созданные Prism при первом запуске на ПК.
- Отправляет только изменившиеся файлы (`~/.cache/ghostcraft-sync/<serial>.json`). Всё идёт одним tar-потоком через `adb shell -T` (ждёт, пока телефон распакует; `exec-in` возвращался, когда данные были ещё в буфере adb). Файлам выставляется запись для группы: иначе игра не может переписать свои конфиги.
- Не отправляет аккаунты, логи, кэши, бэкапы модов и нативные `.so`/`.dll`.

Данные на телефоне лежат в `/sdcard/Android/data/dev.krist.ghostcraft/files/` в той же раскладке, что у Prism: `instances/`, `libraries/`, `assets/`, плюс `runtime/` для ядра. При запуске Java копируется во внутреннюю память приложения: с общего хранилища `.so` загружать нельзя.

## Запуск

Игра идёт в отдельном процессе `:game` (`GameActivity`): `app/src/main/cpp/launcher.c` загружает `libjli.so` нашей Java и вызывает `JLI_Launch` с той же командной строкой, что строит Prism (`Launch.kt`). Вывод JVM и игры — в `instances/<id>/minecraft/logs/ghostcraft-launch.log`, он виден на экране игры и по кнопке журнала на странице инстанса.

## Лицензия

GPL-3.0. Шрифты: Google Sans Flex и Rubik (OFL), Material Symbols (Apache 2.0).
