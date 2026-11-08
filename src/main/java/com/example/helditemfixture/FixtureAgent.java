package com.example.helditemfixture;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FixtureAgent {
    private FixtureAgent() {}

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        if (clientName(System.getProperty("sun.java.command")) == null) {
            throw new IllegalStateException("The target is not a recognized Minecraft Java client.");
        }
        String[] request = arguments == null ? new String[0] : arguments.split("\n", 2);
        if (request.length != 2) {
            throw new IllegalArgumentException("Open the Hopliter launcher to submit a copy request.");
        }
        int slot = FixtureRunner.parseDestination(request[0]);
        Path response = Path.of(request[1]);
        if (!response.isAbsolute() || !response.getFileName().toString().startsWith("hopliter-result-")
                || !Files.isRegularFile(response, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The launcher response file is missing or invalid.");
        }

        FixtureRunner runner = FixtureRunner.bind(instrumentation.getAllLoadedClasses());
        String result = runner.copyHeldItem(slot).get();
        Files.writeString(response, result, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    static String clientName(String command) {
        if (command == null) {
            return null;
        }
        // The remainder of the command can contain launcher authentication arguments.
        return switch (command.stripLeading().split("\\s+", 2)[0]) {
            case "net.minecraft.client.main.Main" -> "Minecraft (Vanilla)";
            case "net.fabricmc.loader.impl.launch.knot.KnotClient",
                    "net.fabricmc.loader.launch.knot.KnotClient" -> "Minecraft (Fabric)";
            default -> null;
        };
    }
}
