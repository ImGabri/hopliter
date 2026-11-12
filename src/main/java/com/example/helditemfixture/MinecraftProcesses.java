package com.example.helditemfixture;

import com.sun.tools.attach.VirtualMachine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class MinecraftProcesses {
    private static final Pattern PID = Pattern.compile("[1-9][0-9]{0,9}");
    private static final String WINDOWS_GAME_PIDS = """
            $session = (Get-Process -Id $PID).SessionId
            Get-Process -Name java,javaw -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.SessionId -eq $session -and
                    $_.MainWindowTitle -match '^Minecraft(?:[ *]|$)' -and
                    $_.MainWindowTitle -notmatch '^Minecraft Launcher(?:\\s|$)'
                } |
                ForEach-Object { [Console]::WriteLine($_.Id) }
            """;

    private MinecraftProcesses() {}

    record Target(String pid, String name) {
        @Override
        public String toString() {
            return name + " — PID " + pid;
        }
    }

    static boolean isPid(String value) {
        return PID.matcher(value).matches();
    }

    static List<Target> find() {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (var vm : VirtualMachine.list()) {
            String mainClass = vm.displayName().stripLeading().split("\\s+", 2)[0];
            String label = clientName(mainClass);
            if (label != null && isPid(vm.id())) {
                targets.put(vm.id(), new Target(vm.id(), label));
            }
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            addWindowsTargets(targets);
        } else {
            addCommandLineTargets(targets);
        }
        return List.copyOf(targets.values());
    }

    private static String clientName(String mainClass) {
        return switch (mainClass) {
            case "net.minecraft.client.main.Main" -> "Minecraft (Vanilla)";
            case "net.fabricmc.loader.impl.launch.knot.KnotClient",
                    "net.fabricmc.loader.launch.knot.KnotClient" -> "Minecraft (Fabric)";
            case "org.prismlauncher.EntryPoint" -> "Minecraft (Prism Launcher)";
            case "org.multimc.EntryPoint" -> "Minecraft (MultiMC)";
            default -> null;
        };
    }

    private static void addCommandLineTargets(Map<String, Target> targets) {
        String user = ProcessHandle.current().info().user().orElse(null);
        try (var processes = ProcessHandle.allProcesses()) {
            processes.forEach(process -> {
                String pid = Long.toString(process.pid());
                if (targets.containsKey(pid)) {
                    return;
                }
                ProcessHandle.Info info = process.info();
                if (user == null || !user.equals(info.user().orElse(null))
                        || !info.command().orElse("").endsWith("/java")) {
                    return;
                }
                for (String argument : info.arguments().orElseGet(() -> new String[0])) {
                    String label = clientName(argument);
                    if (label != null) {
                        targets.put(pid, new Target(pid, label));
                        return;
                    }
                }
            });
        }
    }

    private static void addWindowsTargets(Map<String, Target> targets) {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) {
            return;
        }
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(powershell)) {
            return;
        }
        Process scan = null;
        try {
            scan = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-Command", WINDOWS_GAME_PIDS).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (scan.waitFor(4, TimeUnit.SECONDS) && scan.exitValue() == 0) {
                try (var output = scan.inputReader()) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        String pid = line.strip();
                        if (isPid(pid)) {
                            targets.putIfAbsent(pid, new Target(pid, "Minecraft (game window)"));
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // The JVM list and manual PID remain available if PowerShell is restricted.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (scan != null && scan.isAlive()) {
                scan.destroyForcibly();
            }
        }
    }
}
