#!/usr/bin/env bash
# Mesa под Android arm64: Zink (desktop OpenGL поверх Vulkan) и Turnip (Vulkan для Adreno через KGSL).
#
#   runtime/mesa/build.sh [clone|patch|configure|make|package|all]   (по умолчанию all)
#
# Две сборки из одного дерева:
#   zink   — libEGL_mesa, libGLESv2_mesa, libgallium. EGL на платформе
#            surfaceless: платформе android нужны gralloc и DRM-устройство,
#            которых у приложения на Snapdragon нет;
#   turnip — libvulkan_freedreno, Vulkan-драйвер, который Zink грузит
#            напрямую (ZINK_VULKAN_LIBRARY): системный Vulkan Adreno 642L
#            (1.1.128) не умеет robustness2, без которого Zink не стартует.
# patches/0001-android-zink.patch:
#   - surfaceless принимает ANativeWindow, kopper выводит его через
#     VK_KHR_android_surface (для драйверов с нормальным WSI);
#   - Zink грузит Vulkan-драйвер по пути;
#   - без platforms=android Mesa обходится без системных libsync/gralloc.
# Результат: $GHOSTCRAFT_BUILD/out/mesa/*.so — на телефон через ghostcraft-sync runtime.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${GHOSTCRAFT_BUILD:-$HOME/Projects/GhostCraft-build}"
NDK="${ANDROID_NDK:-$HOME/.local/opt/android-sdk/ndk/29.0.14206865}"
VENV="${MESA_VENV:-$HOME/.local/opt/mesa-venv}"   # meson + mako + PyYAML
API="${ANDROID_API:-31}"
JOBS="${JOBS:-3}"

MESA_TAG=mesa-26.2.5
SRC="$WORK/mesa"
OBJ="$WORK/mesa-build"
OUT="$WORK/out/mesa"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

export PATH="$VENV/bin:$PATH"

step_clone() {
    [ -d "$SRC/.git" ] || git clone -q --depth 1 -b "$MESA_TAG" https://gitlab.freedesktop.org/mesa/mesa.git "$SRC"
}

# Рабочее дерево всегда = тег + наши патчи
step_patch() {
    git -C "$SRC" checkout -q -- .
    for p in "$HERE"/patches/*.patch; do
        echo "patch: $(basename "$p")"
        git -C "$SRC" apply "$p"
    done
}

cross_file() {
    mkdir -p "$OBJ"
    cat > "$OBJ/android-aarch64.ini" <<INI
[binaries]
c = '$TC/bin/aarch64-linux-android$API-clang'
cpp = '$TC/bin/aarch64-linux-android$API-clang++'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'
# no host libraries may leak into the Android build
pkg-config = '/usr/bin/false'

[built-in options]
# libc++ inside our .so files instead of shipping libc++_shared.so;
# Mesa's Android logging goes to liblog
cpp_link_args = ['-static-libstdc++', '-llog']
c_link_args = ['-static-libstdc++', '-llog']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
INI
}

setup() {  # name meson-options...
    local dir="$OBJ/$1"; shift
    # --reconfigure keeps the objects already built (a full build is ~1300 files);
    # the cross file is read only on the first setup: after editing it, rm -rf the dir
    local again=; [ -d "$dir/meson-private" ] && again=--reconfigure
    meson setup $again "$dir" "$SRC" --cross-file "$OBJ/android-aarch64.ini" \
        --buildtype=release -Db_ndebug=true --prefix=/ --wrap-mode=nofallback \
        -Dplatforms= -Dglx=disabled -Dgbm=disabled -Dgles1=disabled \
        -Dllvm=disabled -Dzstd=disabled -Dxmlconfig=disabled -Dexpat=disabled \
        -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
        -Dvideo-codecs= -Dgallium-va=disabled -Dbuild-tests=false -Dtools= -Dvulkan-layers= \
        -Dfreedreno-kmds=kgsl "$@"
}

# Turnip stays in the zink configuration too: vulkan-drivers=freedreno with
# kgsl is what lets Mesa build without libdrm. It's linked only in its own build.
step_configure() {
    cross_file
    setup zink -Dgallium-drivers=zink -Dvulkan-drivers=freedreno \
        -Degl=enabled -Degl-native-platform=surfaceless -Dopengl=true -Dgles2=enabled \
        -Degl-lib-suffix=_mesa -Dgles-lib-suffix=_mesa
    setup turnip -Dgallium-drivers= -Dvulkan-drivers=freedreno \
        -Degl=disabled -Dopengl=false -Dgles2=disabled
}

ZINK_TARGETS="src/gallium/targets/dri/libgallium-26.2.5.so src/egl/libEGL_mesa.so src/mesa/glapi/es2api/libGLESv2_mesa.so"
TURNIP_TARGETS="src/freedreno/vulkan/libvulkan_freedreno.so"

step_make() {
    ninja -C "$OBJ/zink" -j "$JOBS" $ZINK_TARGETS
    ninja -C "$OBJ/turnip" -j "$JOBS" $TURNIP_TARGETS
}

step_package() {
    rm -rf "$OUT"
    mkdir -p "$OUT"
    for t in $ZINK_TARGETS; do cp "$OBJ/zink/$t" "$OUT/"; done
    for t in $TURNIP_TARGETS; do cp "$OBJ/turnip/$t" "$OUT/"; done
    for f in "$OUT"/*.so; do "$TC/bin/llvm-strip" --strip-unneeded "$f"; done
    ls -la "$OUT"
}

case "${1:-all}" in
    all) step_clone; step_patch; step_configure; step_make; step_package ;;
    *) "step_$1" ;;
esac
