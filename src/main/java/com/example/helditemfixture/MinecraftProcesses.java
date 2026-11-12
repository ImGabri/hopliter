package com.example.helditemfixture;

import com.sun.tools.attach.VirtualMachine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class MinecraftProcesses {
    private static final Pattern PID = Pattern.compile("[1-9][0-9]{0,9}");
    private static final Pattern MAIN_CLASS_SEPARATOR = Pattern.compile("\\s+");
    static final String DISCOVERY_INCOMPLETE = "Process discovery was incomplete. You can enter Minecraft's PID manually.";

    private MinecraftProcesses() {}

    record Target(String pid, String name) {
        @Override
        public String toString() {
            return name + " — PID " + pid;
        }
    }

    record Discovery(List<Target> targets, boolean incomplete) {}

    static boolean isPid(String value) {
        return PID.matcher(value).matches() && Long.parseLong(value) <= Integer.MAX_VALUE;
    }

    static Discovery find() {
        Map<String, Target> targets = new LinkedHashMap<>();
        boolean incomplete = false;
        try {
            for (var vm : VirtualMachine.list()) {
                String label = clientNameForLaunch(vm.displayName());
                if (label != null && isPid(vm.id())) {
                    targets.put(vm.id(), new Target(vm.id(), label));
                }
            }
        } catch (LinkageError | RuntimeException error) {
            incomplete = true;
        }
        try {
            if (isWindows()) {
                for (String pid : WindowsGameWindows.findPids()) {
                    if (isPid(pid)) {
                        targets.putIfAbsent(pid, new Target(pid, "Minecraft (game window)"));
                    }
                }
            } else {
                addCommandLineTargets(targets);
            }
        } catch (LinkageError | RuntimeException error) {
            incomplete = true;
        }
        return new Discovery(List.copyOf(targets.values()), incomplete);
    }

    static boolean isMinecraftLaunch(String launch, String pid) {
        if (clientNameForLaunch(launch) != null) {
            return true;
        }
        try {
            return isWindows() && WindowsGameWindows.findPids().contains(pid);
        } catch (LinkageError | RuntimeException error) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String clientNameForLaunch(String launch) {
        return clientName(MAIN_CLASS_SEPARATOR.split(launch.stripLeading(), 2)[0]);
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

}
