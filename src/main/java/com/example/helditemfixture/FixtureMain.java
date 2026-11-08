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
                    Hopliter Injector for Minecraft Java 1.21.11
                    java -jar Hopliter-Injector.jar                  Open the attach launcher
                    java -jar Hopliter-Injector.jar --list           List Minecraft clients
                    java -jar Hopliter-Injector.jar --attach <pid> [auto|0-35]

                    Open a single-player world or host a LAN world. Select Minecraft and click
                    Attach and Copy Held Item to place one copy in an empty inventory slot.
                    """);
            return;
        }
        try {
            if (args.length == 1 && args[0].equals("--list")) {
                var targets = AttachLauncher.targets();
                if (targets.isEmpty()) {
                    System.out.println("No running Minecraft Java client found.");
                }
                targets.forEach(System.out::println);
            } else if ((args.length == 2 || args.length == 3) && args[0].equals("--attach")) {
                String result = AttachLauncher.attachAndCopy(args[1], args.length == 3 ? args[2] : "auto");
                System.out.println(result);
                if (!result.startsWith("Copied one item ")) {
                    System.exit(1);
                }
            } else {
                throw new IllegalArgumentException("Use --help, --list, or --attach <pid> [auto|0-35].");
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
