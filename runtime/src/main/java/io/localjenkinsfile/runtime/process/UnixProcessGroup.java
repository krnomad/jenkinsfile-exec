package io.localjenkinsfile.runtime.process;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

/** Unix process groups let us clean up children even when the original shell exits first. */
public final class UnixProcessGroup {
    private interface LibC extends Library {
        int setpgid(int pid, int group);
        int kill(int pid, int signal);
    }
    private static final LibC LIBC = Native.load(Platform.C_LIBRARY_NAME, LibC.class);
    private UnixProcessGroup() {}

    public static void establish() {
        if (LIBC.setpgid(0, 0) != 0) {
            throw new IllegalStateException("Cannot establish process group (errno " + Native.getLastError() + ")");
        }
    }

    public static void terminate(long pid) {
        if (pid > 0 && pid <= Integer.MAX_VALUE) {
            LIBC.kill(-(int) pid, 15);
            LIBC.kill(-(int) pid, 9);
        }
    }

    public static String helperClasspath() {
        try {
            return ProcessGroupMain.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()
                + java.io.File.pathSeparator
                + Native.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
