package com.example.helditemfixture;

import java.awt.GraphicsEnvironment;

public final class FixtureMain {
    private FixtureMain() {}

    public static void main(String[] args) {
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            AttachLauncher.show();
            return;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            System.out.println("""
                    Hopliter slot-copy fixture for Minecraft Java 1.21.11 / 26.3
                    java -jar Hopliter-Multiplayer.jar                 Open the attach launcher
                    java -jar Hopliter-Multiplayer.jar --list          List Minecraft clients
                    java -jar Hopliter-Multiplayer.jar --attach <pid> [auto|1-35]

                    Copies the full item stack in HOTBAR SLOT 1 to HOTBAR SLOT 2 by default.
                    Destination index 1 means hotbar slot 2. Use auto for the first empty slot.
                    Vanilla multiplayer uses your existing operator permission (level 2 or higher).
                    Paper also supports hopliter.copy when the Hopliter plugin is installed.
                    """);
            return;
        }
        try {
            if (args.length == 1 && args[0].equals("--list")) {
                var targets = MinecraftProcesses.find();
                if (targets.isEmpty()) {
                    System.out.println("No Minecraft Java client detected. You can pass its PID to --attach.");
                }
                targets.forEach(System.out::println);
            } else if ((args.length == 2 || args.length == 3) && args[0].equals("--attach")) {
                String result = AttachLauncher.attachAndCopy(args[1], args.length == 3 ? args[2]
                        : Integer.toString(CopySlots.DEFAULT_DESTINATION));
                System.out.println(result);
                if (!result.startsWith("Copied full stack ") && !result.startsWith(FixtureRunner.COMMAND_SENT)) {
                    System.exit(1);
                }
            } else {
                throw new IllegalArgumentException("Use --help, --list, or --attach <pid> [auto|1-35].");
            }
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (NoClassDefFoundError | Exception error) {
            System.err.println("Attach error: " + AttachLauncher.errorMessage(error));
            System.exit(1);
        }
    }
}
