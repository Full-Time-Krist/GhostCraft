#!/usr/bin/env bash
# Нативные библиотеки LWJGL 3.3.3 под Android arm64 (bionic) из исходников апстрима.
#
#   runtime/lwjgl/build.sh [clone|generate|libffi|lwjgl|openal|jemalloc|freetype|all]
#
# Java-часть LWJGL берётся из Maven как на ПК, поэтому натив собирается ровно из
# того коммита, что и релиз 3.3.3 (liblwjgl.so.git в natives-jar = f0ec143), а
# сторонние библиотеки — из тех же коммитов форков LWJGL-CI.
# Результат: $GHOSTCRAFT_BUILD/out/lwjgl/*.so (плоская папка для -Dorg.lwjgl.librarypath).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${GHOSTCRAFT_BUILD:-$HOME/Projects/GhostCraft-build}"
NDK="${ANDROID_NDK:-$HOME/.local/opt/android-sdk/ndk/29.0.14206865}"
CMAKE="${CMAKE:-$HOME/.local/opt/android-sdk/cmake/3.31.6/bin/cmake}"
JAVA_HOME_BUILD="${JAVA_HOME_BUILD:-$HOME/.local/opt/jdk17}"
ANT="${ANT:-$HOME/.local/opt/ant/bin/ant}"
API="${ANDROID_API:-31}"
JOBS="${JOBS:-4}"

SRC="$WORK/lwjgl3"
DEPS="$WORK/lwjgl-deps"
OBJ="$WORK/lwjgl-build"
OUT="$WORK/out/lwjgl"
JNI_INC="$WORK/jdk21-build/images/jdk/include"   # заголовки JNI нашей сборки JDK
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
TARGET=aarch64-linux-android
CC="$TC/bin/$TARGET$API-clang"
CXX="$TC/bin/$TARGET$API-clang++"

LWJGL_COMMIT=f0ec1437a6149dae00eaa27c47268e5e4efb129f
LIBFFI_VERSION=3.4.4
OPENAL_COMMIT=d9fed51aa6391debc31dbbca550f055c980afe70
JEMALLOC_COMMIT=a442d9b895935ac872e7ccc705213537bc747c19
FREETYPE_COMMIT=45903920b984540bb629bc89f4c010159c23a89a

fetch() {  # repo commit dir
    [ -d "$3/.git" ] && return
    git init -q "$3"
    git -C "$3" fetch -q --depth 1 "$1" "$2"
    git -C "$3" checkout -q FETCH_HEAD
}

step_clone() {
    fetch https://github.com/LWJGL/lwjgl3.git $LWJGL_COMMIT "$SRC"
}

# Генератор пишет C-код привязок. Нужны все привязки: JNI.c ядра содержит
# вызыватели для сигнатур всех модулей, и он должен совпасть с JNI.class из Maven.
step_generate() {
    cd "$SRC"
    JAVA_HOME="$JAVA_HOME_BUILD" ANT_OPTS="-Xmx1800m" "$ANT" -Dbackend-threads=1 compile-templates generate
}

step_libffi() {
    local dir="$DEPS/libffi-$LIBFFI_VERSION"
    if [ ! -d "$dir" ]; then
        local tgz="$WORK/dl/libffi-$LIBFFI_VERSION.tar.gz"
        mkdir -p "$DEPS" "$WORK/dl"
        [ -f "$tgz" ] || curl -sfL -o "$tgz" \
            "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz"
        tar -xzf "$tgz" -C "$DEPS"
    fi
    cd "$dir"
    ./configure --host=$TARGET --disable-shared --enable-static --with-pic \
        --disable-docs --disable-multi-os-directory --disable-exec-static-tramp \
        --prefix="$DEPS/libffi" \
        CC="$CC" CXX="$CXX" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" >/dev/null
    make -j"$JOBS" >/dev/null
    make install >/dev/null
}

# Флаги повторяют config/linux/build.xml (gcc → clang из NDK)
CFLAGS_LWJGL=(
    -c -std=gnu11 -O3 -fPIC -DNDEBUG -DLWJGL_LINUX -DLWJGL_arm64
    -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64
    -I"$JNI_INC" -I"$JNI_INC/linux"
    -I"$SRC/modules/lwjgl/core/src/main/c" -I"$SRC/modules/lwjgl/core/src/main/c/linux"
)

compile_module() {  # name objdir extra-flags... -- sources...
    local name=$1 dir="$OBJ/$2"; shift 2
    local flags=() srcs=()
    while [ "$1" != "--" ]; do flags+=("$1"); shift; done; shift
    srcs=("$@")
    rm -rf "$dir"; mkdir -p "$dir"
    printf '%s\n' "${srcs[@]}" | xargs -P "$JOBS" -I{} sh -c \
        'f="$1"; shift; o="$0/$(basename "${f%.*}").o"; exec "$@" "$f" -o "$o"' \
        "$dir" {} "$CC" "${CFLAGS_LWJGL[@]}" "${flags[@]}"
}

link_module() {  # libname objdir extra-link...
    local name=$1 dir="$OBJ/$2"; shift 2
    mkdir -p "$OUT"
    "$CC" -shared -fPIC -O3 -z noexecstack -Wl,--no-undefined \
        -Wl,--version-script,"$SRC/config/linux/version.script" \
        -o "$OUT/lib$name.so" "$dir"/*.o "$@"
    "$TC/bin/llvm-strip" --strip-unneeded "$OUT/lib$name.so"
}

step_lwjgl() {
    local m="$SRC/modules/lwjgl"
    local core="$m/core/src/main/c"
    compile_module lwjgl core \
        -I"$core/libffi" -I"$core/libffi/aarch64" \
        -DLIBURING_COMPAT_H -include "$HERE/include/liburing/compat.h" -I"$core/linux/liburing" -I"$core/linux/liburing/include" -DCONFIG_HAVE_MEMFD_CREATE -- \
        "$core"/*.c "$m"/core/src/generated/c/*.c "$m"/core/src/generated/c/linux/*.c "$core"/linux/liburing/*.c
    link_module lwjgl core "$DEPS/libffi/lib/libffi.a" -ldl

    compile_module lwjgl_opengl opengl -I"$m/opengl/src/main/c" -- \
        $(ls "$m"/opengl/src/generated/c/*.c | grep -v org_lwjgl_opengl_WGL.c)
    link_module lwjgl_opengl opengl

    compile_module lwjgl_stb stb -isystem "$m/stb/src/main/c" -- "$m"/stb/src/generated/c/*.c
    link_module lwjgl_stb stb -lm

    compile_module lwjgl_tinyfd tinyfd -I"$m/tinyfd/src/main/c" -- \
        "$m"/tinyfd/src/main/c/*.c "$m"/tinyfd/src/generated/c/*.c
    link_module lwjgl_tinyfd tinyfd
}

fetch_deps() {
    fetch https://github.com/LWJGL-CI/openal-soft.git $OPENAL_COMMIT "$DEPS/openal-soft"
    fetch https://github.com/LWJGL-CI/jemalloc.git $JEMALLOC_COMMIT "$DEPS/jemalloc"
    fetch https://github.com/LWJGL-CI/freetype.git $FREETYPE_COMMIT "$DEPS/freetype"
}

android_cmake() {  # src build extra-args...
    local src=$1 build=$2; shift 2
    "$CMAKE" -S "$src" -B "$build" -G Ninja -DCMAKE_MAKE_PROGRAM="$(dirname "$CMAKE")/ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=$API -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release "$@" >/dev/null
    "$CMAKE" --build "$build" -j "$JOBS" >/dev/null
}

# Звук: OpenSL ES есть на любом Android; libc++ статически, чтобы не тащить libc++_shared
step_openal() {
    fetch_deps
    android_cmake "$DEPS/openal-soft" "$OBJ/openal" \
        -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_INSTALL=OFF \
        -DALSOFT_REQUIRE_OPENSL=ON -DALSOFT_EMBED_HRTF_DATA=ON
    mkdir -p "$OUT"
    cp "$OBJ/openal/libopenal.so" "$OUT/"
    "$TC/bin/llvm-strip" --strip-unneeded "$OUT/libopenal.so"
}

# LWJGL ищет функции с префиксом je_ (JEmalloc.java)
step_jemalloc() {
    fetch_deps
    local b="$OBJ/jemalloc"
    rm -rf "$b"; cp -r "$DEPS/jemalloc" "$b"; cd "$b"
    ./autogen.sh --host=$TARGET --with-jemalloc-prefix=je_ --disable-cxx --disable-stats \
        --disable-initial-exec-tls --disable-libdl --with-lg-page=16 \
        CC="$CC" CXX="$CXX" AR="$TC/bin/llvm-ar" NM="$TC/bin/llvm-nm" RANLIB="$TC/bin/llvm-ranlib" >/dev/null
    make -j"$JOBS" build_lib_shared >/dev/null
    mkdir -p "$OUT"
    cp -L lib/libjemalloc.so "$OUT/libjemalloc.so"
    "$TC/bin/llvm-strip" --strip-unneeded "$OUT/libjemalloc.so"
}

# Без внешних zlib/png/bzip2/brotli/harfbuzz: игре нужен только растр TTF
step_freetype() {
    fetch_deps
    android_cmake "$DEPS/freetype" "$OBJ/freetype" -DBUILD_SHARED_LIBS=ON \
        -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
    mkdir -p "$OUT"
    cp -L "$OBJ/freetype/libfreetype.so" "$OUT/libfreetype.so"
    "$TC/bin/llvm-strip" --strip-unneeded "$OUT/libfreetype.so"
}

case "${1:-all}" in
    all) step_clone; step_generate; step_libffi; step_lwjgl; step_openal; step_jemalloc; step_freetype ;;
    *) "step_$1" ;;
esac
