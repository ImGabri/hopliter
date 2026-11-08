package com.example.helditemfixture;

import java.awt.GraphicsEnvironment;
import java.io.Console;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class FixtureMain {
    private static final Set<String> OPTIONS = Set.of(
            "--host", "--port", "--player", "--slot", "--dest-slot");

    private FixtureMain() {}

    public static void main(String[] args) {
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            FixtureGui.show();
            return;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            System.out.println("""
                    HeldItemUidFixture
                    java -jar HeldItemUidFixture.jar --player TestPlayer
                      [--host 127.0.0.1] [--port 25575] [--slot 0] [--dest-slot 1]

                    Connects to an existing staging RCON endpoint on localhost or an SSH tunnel.
                    Duplicates the item in the first inventory slot into the second inventory slot so both are filled.
                    """);
            return;
        }
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (IOException error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(1);
        }
    }

    private static int run(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !OPTIONS.contains(args[i])
                    || args[i + 1].startsWith("--") || options.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Invalid or duplicate option. Run with --help.");
            }
        }
        FixtureRunner.Settings settings = FixtureRunner.Settings.parse(
                options.getOrDefault("--host", "127.0.0.1"), options.getOrDefault("--port", "25575"),
                options.get("--player"), options.getOrDefault("--slot", "0"), options.getOrDefault("--dest-slot", "1"));
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException("Open the JAR dialog or run from an interactive terminal.");
        }
        char[] password = console.readPassword("Staging RCON password: ");
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("An RCON password is required.");
        }
        try {
            FixtureRunner.Result result = FixtureRunner.run(settings, password);
            console.writer().println(result.report());
            console.writer().flush();
            return result.status().exitCode;
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
