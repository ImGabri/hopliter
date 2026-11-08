package com.example.helditemfixture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class FixtureRunner {
    static final int MAIN_SLOTS = 27;
    private static final String QUERY_PREFIX = " has the following entity data: ";
    private static final String CLONE_COMMAND_WITH_COMPONENTS =
            "execute as %s if items entity @s inventory.%d * unless items entity @s inventory.%d * "
            + "run item replace entity @s inventory.%d with %s{components:%s} 1";
    private static final String CLONE_COMMAND_PLAIN =
            "execute as %s if items entity @s inventory.%d * unless items entity @s inventory.%d * "
            + "run item replace entity @s inventory.%d with %s 1";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    record Settings(InetAddress host, int port, String player, int sourceSlot, int destSlot) {
        static Settings parse(String host, String portText, String player, String sourceText, String destText) {
            String literal = switch (host) {
                case "localhost", "127.0.0.1" -> "127.0.0.1";
                case "::1" -> "::1";
                default -> throw new IllegalArgumentException(
                        "Use localhost, 127.0.0.1, or ::1 for your staging RCON endpoint or SSH tunnel.");
            };
            InetAddress address = new InetSocketAddress(literal, 0).getAddress();
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Enter a port from 1 to 65535.");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Enter a port from 1 to 65535.");
            }
            if (player == null || !USERNAME.matcher(player).matches()) {
                throw new IllegalArgumentException("Enter the test player's Java username (3–16 letters, digits, or underscores).");
            }
            int sourceSlot;
            try {
                sourceSlot = Integer.parseInt(sourceText);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Enter the source slot id from 0 to 26.");
            }
            if (sourceSlot < 0 || sourceSlot > 26) {
                throw new IllegalArgumentException("Enter the source slot id from 0 to 26.");
            }
            int destSlot;
            try {
                destSlot = Integer.parseInt(destText);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Enter the destination slot id from 0 to 26.");
            }
            if (destSlot < 0 || destSlot > 26 || destSlot == sourceSlot) {
                throw new IllegalArgumentException("Enter a different destination slot id from 0 to 26.");
            }
            return new Settings(address, port, player, sourceSlot, destSlot);
        }
    }

    enum Status {
        CLONED(0), ERROR(1);

        final int exitCode;

        Status(int exitCode) {
            this.exitCode = exitCode;
        }
    }

    record Result(Status status, String message) {
        String report() {
            return status + "\n" + message;
        }
    }

    static Result run(Settings settings, char[] password) throws IOException {
        try (RconClient rcon = new RconClient(settings.host(), settings.port(), password)) {
            Item source = readItem(rcon, settings.player(), settings.sourceSlot);
            if (source == null) {
                return new Result(Status.ERROR, "The source slot inventory." + settings.sourceSlot + " is empty.");
            }
            if (source.id() == null || source.id().isBlank() || source.id().equals("unknown")) {
                return new Result(Status.ERROR,
                        "The source slot inventory." + settings.sourceSlot
                                + " does not contain a readable item.");
            }
            Item dest = readItem(rcon, settings.player(), settings.destSlot);
            if (dest != null) {
                return new Result(Status.ERROR, "The destination slot inventory." + settings.destSlot + " is occupied.");
            }
            String command;
            if (source.components().isEmpty()) {
                command = CLONE_COMMAND_PLAIN.formatted(
                        settings.player(), settings.sourceSlot, settings.destSlot,
                        settings.destSlot, source.id());
            } else {
                command = CLONE_COMMAND_WITH_COMPONENTS.formatted(
                        settings.player(), settings.sourceSlot, settings.destSlot,
                        settings.destSlot, source.id(), source.componentsSnb());
            }
            String reply = rcon.command(command);
            if (reply != null && reply.startsWith("Replaced")) {
                return new Result(Status.CLONED,
                        "Cloned item from inventory." + settings.sourceSlot + " into inventory." + settings.destSlot
                                + ". Both slots are now filled.");
            }
            return new Result(Status.ERROR, "The server did not accept the clone command: " + reply);
        }
    }

    private static Item readItem(RconClient rcon, String player, int slot) throws IOException {
        String output = rcon.command("data get entity " + player + " Inventory[{Slot:" + slot + "b}]").strip();
        if (output.equals("No entity was found") || output.equals("No player was found")) {
            throw new IOException("The test player is offline.");
        }
        if (output.equals("Found no elements matching Inventory[{Slot:" + slot + "b}]")
                || output.equals("Can't get Inventory[{Slot:" + slot + "b}]; tag doesn't exist")) {
            return null;
        }
        int prefix = output.indexOf(QUERY_PREFIX);
        if (prefix < 0) {
            throw new IOException("The backend did not return item data for inventory." + slot + ".");
        }
        String payload = output.substring(prefix + QUERY_PREFIX.length());
        if (Snbt.isFolded(payload)) {
            throw new IOException("The backend truncated the item data for inventory." + slot
                    + ". The item's data is too large for /data get to return fully.");
        }
        try {
            if (!(Snbt.read(payload) instanceof Snbt.Compound compound)) {
                throw new IOException("Expected an item compound for inventory." + slot + ".");
            }
            Map<String, Snbt.Tag> data = new LinkedHashMap<>(compound.values());
            Snbt.Tag countTag = data.get("count");
            int count = countTag == null ? 1 : number(countTag);
            if (count <= 0) {
                return null;
            }
            Snbt.Tag idTag = data.get("id");
            String id = idTag instanceof Snbt.Atom ? ((Snbt.Atom) idTag).value() : null;
            if (id == null || id.isBlank()) {
                return new Item(0, "unknown", Map.of());
            }
            Snbt.Tag componentsTag = data.get("components");
            Map<String, Snbt.Tag> components = new LinkedHashMap<>();
            if (componentsTag instanceof Snbt.Compound c) {
                components.putAll(c.values());
            }
            return new Item(count, id, components);
        } catch (IllegalArgumentException error) {
            throw new IOException("Could not read the backend's SNBT for inventory." + slot + ": " + error.getMessage(), error);
        }
    }

    private static int number(Snbt.Tag tag) {
        if (tag instanceof Snbt.Atom atom) {
            switch (atom.type()) {
                case BYTE, SHORT, INT, LONG -> {
                    return Integer.parseInt(atom.value());
                }
                default -> {
                }
            }
        }
        throw new IllegalArgumentException("Expected a byte, short, int, or long tag");
    }

    private record Item(int count, String id, Map<String, Snbt.Tag> components) {
        String componentsSnb() {
            return Snbt.write(new Snbt.Compound(components));
        }
    }
}
