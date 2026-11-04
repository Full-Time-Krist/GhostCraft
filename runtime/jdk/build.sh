#!/usr/bin/env bash
# Кросс-сборка OpenJDK 21 под Android arm64 (bionic) из апстрима openjdk/jdk21u.
#
#   runtime/jdk/build.sh [clone|patch|deps|configure|make|package|all]   (по умолчанию all)
#
# Исходники и сборка лежат вне репозитория: $GHOSTCRAFT_BUILD (~/Projects/GhostCraft-build).
# Результат: $GHOSTCRAFT_BUILD/out/jdk21-android-arm64.tar.xz — его кладёт на телефон лаунчер/синк.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${GHOSTCRAFT_BUILD:-$HOME/Projects/GhostCraft-build}"
NDK="${ANDROID_NDK:-$HOME/.local/opt/android-sdk/ndk/29.0.14206865}"
BOOT_JDK="${BOOT_JDK:-$HOME/.local/opt/jdk21}"
API="${ANDROID_API:-31}"          # = minSdk приложения
TAG="${JDK_TAG:-jdk-21.0.9+10}"
JOBS="${JOBS:-4}"                 # 7 ГБ ОЗУ: больше 4 потоков hotspot не влезает

SRC="$WORK/jdk21u"
DEPS="$WORK/deps"
BUILD="$WORK/jdk21-build"
OUT="$WORK/out"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
TARGET=aarch64-linux-android

step_clone() {
    [ -d "$SRC/.git" ] && return
    git clone --depth 1 --branch "$TAG" https://github.com/openjdk/jdk21u.git "$SRC"
}

# Патчи накладываются на чистое дерево, поэтому повторный запуск безопасен.
step_patch() {
    git -C "$SRC" checkout -q -- .
    for p in "$HERE"/patches/*.patch; do
        [ -e "$p" ] || continue
        echo "patch: $(basename "$p")"
        git -C "$SRC" apply --whitespace=nowarn "$p"
    done
}

# configure под Linux требует заголовки X11/CUPS/fontconfig/ALSA даже для headless.
# Берём заголовки хоста (они не зависят от архитектуры), а libasound заменяем пустышкой:
# javax.sound Minecraft не нужен, звук идёт через OpenAL.
step_deps() {
    rm -rf "$DEPS"; mkdir -p "$DEPS/include/X11" "$DEPS/lib"
    cp -r /usr/include/X11/. "$DEPS/include/X11/"
    cp -r /usr/include/alsa /usr/include/cups /usr/include/fontconfig "$DEPS/include/"
    echo 'int snd_pcm_open(void){return -1;}' > "$DEPS/asound.c"
    "$TC/bin/$TARGET$API-clang" -shared -fPIC -Wl,-soname,libasound.so.2 \
        -o "$DEPS/lib/libasound.so" "$DEPS/asound.c"
    # В bionic pthread и rt входят в libc, а сборка JDK линкует -lpthread -lrt.
    for l in pthread rt; do "$TC/bin/llvm-ar" rc "$DEPS/lib/lib$l.a"; done
}

step_configure() {
    mkdir -p "$BUILD"
    cd "$BUILD"
    bash "$SRC/configure" \
        --openjdk-target=$TARGET \
        --with-boot-jdk="$BOOT_JDK" \
        --with-toolchain-type=clang \
        --with-sysroot="$TC/sysroot" \
        --with-jvm-variants=server \
        --with-debug-level=release \
        --with-native-debug-symbols=none \
        --enable-headless-only \
        --disable-warnings-as-errors \
        --disable-precompiled-headers \
        --with-x="$DEPS" --with-cups="$DEPS" --with-fontconfig="$DEPS" --with-alsa="$DEPS" \
        --with-freetype=bundled --with-libjpeg=bundled --with-giflib=bundled \
        --with-libpng=bundled --with-lcms=bundled --with-zlib=bundled \
        --with-extra-ldflags="-L$DEPS/lib -Wl,--undefined-version" \
        --with-vendor-name=GhostCraft \
        --with-version-opt=ghostcraft \
        --with-jobs="$JOBS" \
        CC="$TC/bin/$TARGET$API-clang" \
        CXX="$TC/bin/$TARGET$API-clang++" \
        AR="$TC/bin/llvm-ar" NM="$TC/bin/llvm-nm" \
        OBJCOPY="$TC/bin/llvm-objcopy" STRIP="$TC/bin/llvm-strip" \
        BUILD_CC=clang BUILD_CXX=clang++ BUILD_NM=nm BUILD_AR=ar \
        BUILD_OBJCOPY=objcopy BUILD_STRIP=strip
}

step_make() {
    make -C "$BUILD" JOBS="$JOBS" ${MAKE_FLAGS:-} images
}

# В рантайм идёт только то, что нужно игре: без исходников, демо и man.
step_package() {
    local img="$BUILD/images/jdk" pkg="$OUT/jdk21"
    rm -rf "$pkg"; mkdir -p "$pkg"
    cp -r "$img/bin" "$img/conf" "$img/lib" "$img/release" "$pkg/"
    # libjsound тянет настоящий libasound, которого на Android нет (см. step_deps)
    rm -f "$pkg/lib/src.zip" "$pkg/lib/libjsound.so"
    find "$pkg" -name '*.so' -exec "$TC/bin/llvm-strip" --strip-unneeded {} +
    tar -C "$OUT" -cJf "$OUT/jdk21-android-arm64.tar.xz" jdk21
    ls -lh "$OUT/jdk21-android-arm64.tar.xz"
}

[ -x "$TC/bin/$TARGET$API-clang" ] || { echo "нет NDK: $NDK" >&2; exit 1; }
case "${1:-all}" in
    all) step_clone; step_patch; step_deps; step_configure; step_make; step_package ;;
    *) "step_$1" ;;
esac
