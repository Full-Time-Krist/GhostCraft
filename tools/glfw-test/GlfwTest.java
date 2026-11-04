// Checks our GLFW and the GL stack: a desktop GL 3.2 core window (GLES
// without Mesa) whose colour follows the cursor (x → red, y → green, left
// button → blue), the renderer and every input event in the log. Right click toggles the captured cursor (the in-world mode),
// Escape/Back or the launcher's stop button closes the window.
import static org.lwjgl.glfw.GLFW.*;

import org.lwjgl.system.JNI;

public class GlfwTest {
    static void toggleCapture(long win) {
        boolean captured = glfwGetInputMode(win, GLFW_CURSOR) == GLFW_CURSOR_DISABLED;
        glfwSetInputMode(win, GLFW_CURSOR, captured ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
        System.out.println("cursor " + (captured ? "normal" : "captured"));
    }

    public static void main(String[] args) {
        glfwSetErrorCallback((code, desc) -> System.out.println("error " + code));
        if (!glfwInit()) throw new IllegalStateException("glfwInit");
        System.out.println("GLFW " + glfwGetVersionString());
        // what Minecraft asks for; our GLFW falls back to GLES without Mesa
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long w = glfwCreateWindow(854, 480, "GLFW test", 0, 0);
        glfwMakeContextCurrent(w);
        glfwSwapInterval(1);
        long clearColor = glfwGetProcAddress("glClearColor");
        long clear = glfwGetProcAddress("glClear");
        long viewport = glfwGetProcAddress("glViewport");
        long getString = glfwGetProcAddress("glGetString");
        for (int name : new int[] {0x1F00, 0x1F01, 0x1F02}) // vendor, renderer, version
            System.out.println(org.lwjgl.system.MemoryUtil.memASCIISafe(JNI.callP(name, getString)));

        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(w, fw, fh);
        System.out.println("framebuffer " + fw[0] + "x" + fh[0]);
        glfwSetFramebufferSizeCallback(w, (win, x, y) -> System.out.println("fbsize " + x + "x" + y));
        glfwSetKeyCallback(w, (win, key, sc, action, mods) -> {
            System.out.println("key " + key + " action " + action + " mods " + mods);
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) glfwSetWindowShouldClose(win, true);
            if (key == GLFW_KEY_C && action == GLFW_PRESS) toggleCapture(win);
        });
        glfwSetCharCallback(w, (win, cp) -> System.out.println("char '" + new String(Character.toChars(cp)) + "'"));
        glfwSetMouseButtonCallback(w, (win, b, action, mods) -> {
            System.out.println("button " + b + " action " + action);
            if (b == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS) toggleCapture(win);
        });
        glfwSetScrollCallback(w, (win, x, y) -> System.out.println("scroll " + x + " " + y));
        glfwSetWindowFocusCallback(w, (win, f) -> System.out.println("focus " + f));
        glfwSetWindowIconifyCallback(w, (win, i) -> System.out.println("iconify " + i));
        glfwSetWindowCloseCallback(w, win -> System.out.println("close requested"));

        long frames = 0, since = System.nanoTime();
        double[] cx = new double[1], cy = new double[1];
        while (!glfwWindowShouldClose(w)) {
            glfwPollEvents();
            glfwGetFramebufferSize(w, fw, fh);
            glfwGetCursorPos(w, cx, cy);
            float r = (float) Math.floorMod((long) cx[0], fw[0]) / fw[0];
            float g = (float) Math.floorMod((long) cy[0], fh[0]) / fh[0];
            float b = glfwGetMouseButton(w, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS ? 1 : 0;
            JNI.callV(0, 0, fw[0], fh[0], viewport);
            JNI.callV(r, g, b, 1f, clearColor);
            JNI.callV(0x4000, clear);
            glfwSwapBuffers(w);
            frames++;
            if (System.nanoTime() - since > 5_000_000_000L) {
                System.out.printf("%.1f fps, cursor %.0f %.0f%n", frames / 5.0, cx[0], cy[0]);
                frames = 0;
                since = System.nanoTime();
            }
        }
        System.out.println("bye");
        glfwDestroyWindow(w);
        glfwTerminate();
    }
}
