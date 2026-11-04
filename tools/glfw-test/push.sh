#!/bin/sh
# Builds GlfwTest and puts it on the phone as the instance "glfw-test":
# it then shows up in GhostCraft and starts like any game.
# Needs the LWJGL 3.3.3 jars synced by ghostcraft-sync and the jars from Prism.
set -e
here=$(cd "$(dirname "$0")" && pwd)
adb=${ADB:-$HOME/.local/opt/platform-tools/adb}
jdk=${JDK:-$HOME/.local/opt/jdk21}
libs=${PRISM_LIBS:-$HOME/.local/share/PrismLauncher/libraries}
dest=/sdcard/Android/data/dev.krist.ghostcraft/files
cp="$libs/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar:$libs/org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar"

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
"$jdk/bin/javac" --release 21 -cp "$cp" -d "$out/classes" "$here/GlfwTest.java"
"$jdk/bin/jar" cf "$out/glfw-test.jar" -C "$out/classes" .
# logs/ made here, so adb can read the launch log the app writes into it
mkdir -p "$out/instances/glfw-test/minecraft/logs"
cat > "$out/instances/glfw-test/ghostcraft.json" <<JSON
{
  "format": 1, "id": "glfw-test", "name": "GLFW test", "group": null,
  "minecraft": "test", "loader": null, "lwjgl": {"uid": "org.lwjgl3", "version": "3.3.3"},
  "memory": {"min": 64, "max": 256}, "jvmArgsUser": "",
  "classpath": ["org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar", "org/lwjgl/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar",
                "dev/krist/glfw-test/glfw-test.jar"],
  "mavenFiles": [], "desktopNatives": [], "mainClass": "GlfwTest", "gameArgs": "", "jvmArgs": [],
  "tweakers": [], "traits": [], "mainJar": null, "assetIndex": null, "javaMajor": 21, "warnings": [],
  "syncedAt": $(date +%s)
}
JSON
mkdir -p "$out/libraries/dev/krist/glfw-test"
cp "$out/glfw-test.jar" "$out/libraries/dev/krist/glfw-test/"
# same route as ghostcraft-sync: tar through shell -T, files group-writable
# (what the app created itself, like logs/, stays the app's)
tar -C "$out" -cf - instances libraries | "$adb" shell -T "tar -xmf - -C $dest && chmod -R g+rw $dest/instances/glfw-test $dest/libraries/dev 2>/dev/null; true"
echo "glfw-test pushed"
