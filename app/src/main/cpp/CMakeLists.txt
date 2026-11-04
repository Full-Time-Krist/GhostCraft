cmake_minimum_required(VERSION 3.22)
project(ghostcraft C)

add_library(ghostcraft SHARED launcher.c)
target_compile_options(ghostcraft PRIVATE -Wall -Wextra -O2)
target_link_libraries(ghostcraft log dl)

# Our GLFW: loaded by the app (GlfwBridge) and by LWJGL in the game's JVM
add_library(glfw SHARED glfw/init.c glfw/window.c glfw/input.c glfw/bridge.c)
target_compile_options(glfw PRIVATE -Wall -Wextra -O2 -fvisibility=hidden)
target_link_libraries(glfw android nativewindow log dl)
