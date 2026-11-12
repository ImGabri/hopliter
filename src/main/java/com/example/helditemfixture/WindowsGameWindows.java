package com.example.helditemfixture;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class WindowsGameWindows {
    private static final Pattern GAME_TITLE = Pattern.compile("^Minecraft(?:[ *]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAUNCHER_TITLE = Pattern.compile("^Minecraft Launcher(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    private WindowsGameWindows() {}

    static Set<String> findPids() {
        User32 user32 = User32.INSTANCE;
        Set<String> pids = new LinkedHashSet<>();
        // Only the start of the caption is needed. Never expose titles or process arguments.
        char[] title = new char[128];
        IntByReference owner = new IntByReference();
        boolean enumerated = user32.EnumWindows((window, unused) -> {
            if (!user32.IsWindowVisible(window)) {
                return true;
            }
            int length = user32.GetWindowTextW(window, title, title.length);
            if (length == 0) {
                return true;
            }
            String caption = new String(title, 0, length);
            if (!GAME_TITLE.matcher(caption).find() || LAUNCHER_TITLE.matcher(caption).find()
                    || user32.GetWindowThreadProcessId(window, owner) == 0) {
                return true;
            }
            long pid = Integer.toUnsignedLong(owner.getValue());
            if (ProcessHandle.of(pid).flatMap(process -> process.info().command())
                    .map(WindowsGameWindows::isJavaExecutable).orElse(false)) {
                pids.add(Long.toString(pid));
            }
            return true;
        }, null);
        if (!enumerated) {
            throw new IllegalStateException("Windows could not enumerate desktop windows.");
        }
        return pids;
    }

    private static boolean isJavaExecutable(String command) {
        String imageName = Path.of(command).getFileName().toString();
        return imageName.equalsIgnoreCase("javaw.exe") || imageName.equalsIgnoreCase("java.exe");
    }

    private interface WindowCallback extends StdCallLibrary.StdCallCallback {
        boolean callback(Pointer window, Pointer data);
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        boolean EnumWindows(WindowCallback callback, Pointer data);
        boolean IsWindowVisible(Pointer window);
        int GetWindowTextW(Pointer window, char[] title, int maxLength);
        int GetWindowThreadProcessId(Pointer window, IntByReference processId);
    }
}
