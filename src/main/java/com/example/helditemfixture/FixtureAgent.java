package com.example.helditemfixture;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FixtureAgent {
    private FixtureAgent() {}

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        String[] request = arguments == null ? new String[0] : arguments.split("\n", 2);
        if (request.length != 2) {
            throw new IllegalArgumentException("Open the Hopliter launcher to submit a copy request.");
        }
        int slot = CopySlots.parseDestination(request[0]);
        Path response = Path.of(request[1]);
        if (!response.isAbsolute() || !response.getFileName().toString().startsWith("hopliter-result-")
                || !Files.isRegularFile(response, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The launcher response file is missing or invalid.");
        }
        String result;
        try {
            FixtureRunner runner = FixtureRunner.bind(instrumentation.getAllLoadedClasses());
            result = runner.copySlotOne(slot).get();
        } catch (IllegalStateException error) {
            result = error.getMessage();
        } catch (ReflectiveOperationException error) {
            result = "Select a Vanilla or Fabric client for Minecraft Java 1.21.11 or 26.3.";
        }
        Files.writeString(response, result, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }
}
