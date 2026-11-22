package com.misura.phone.hidden;

// Runs as the shell user inside a Shizuku UserService process.
interface IShellService {
    // Shizuku calls this transaction code to stop the service.
    void destroy() = 16777114;
    // Returns the new display id, or throws.
    int createDisplay(String name, int width, int height, int dpi) = 1;
    // Releases a display this service created; its tasks are destroyed with it.
    boolean releaseDisplay(int displayId) = 2;
    // Runs a command (no shell) and returns stdout + stderr.
    String exec(in String[] argv) = 3;
    // JPEG of that region of the display's latest frame, scaled down so its width is at most maxWidth; null when there is no frame or the region is empty.
    byte[] grab(int displayId, int left, int top, int right, int bottom, int maxWidth) = 4;
}
