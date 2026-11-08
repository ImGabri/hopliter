package com.example.helditemfixture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class FixtureRunner {
    static final int MAIN_SLOTS = 27;
    private static final int HOTBAR_SLOTS = 9;
    private static final String QUERY_PREFIX = " has the following entity data: ";
    private static final String COPY_COMMAND =
            "execute as %s %sif items entity @s %s * unless items entity @s inventory.%d * "
            + "run item replace entity @s inventory.%d from entity @s %s "
            + "{function:\"minecraft:set_count\",count:1}";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    record Settings(InetAddress host, int port, String player, List<String> uidPath, Snbt.Type uidType,
            Integer multiplyFactor) {
        static Settings parse(String host, String portText, String player, String uidPath, String uidType,
                String factorText) {
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
            if (uidPath.isBlank() != uidType.isBlank()) {
                throw new IllegalArgumentException("Supply both the UID path and type, or leave both blank.");
            }
            Integer factor = factorText == null ? null : parseFactor(factorText);
            if (uidPath.isBlank()) {
                return new Settings(address, port, player, List.of(), null, factor);
            }
            Snbt.Type type;
            try {
                type = Snbt.Type.valueOf(uidType.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Unknown NBT type. See --help for UID types.");
            }
            return new Settings(address, port, player, Snbt.keyPath(uidPath), type, factor);
        }

        private static int parseFactor(String text) {
            int factor;
            try {
                factor = Integer.parseInt(text);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Enter a multiply factor from 1 to 28.");
            }
            if (factor < 1 || factor > MAIN_SLOTS + 1) {
                throw new IllegalArgumentException("Enter a multiply factor from 1 to 28.");
            }
            return factor;
        }

        int copyCount() {
            return multiplyFactor == null ? 1 : multiplyFactor - 1;
        }
    }

    enum Status {
        PASS(0), UID_UNVERIFIED(0), NO_COPY(3), PARTIAL(4), INCONCLUSIVE(4), RECOVERY_READBACK(4);

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
        try (RunGate gate = new RunGate(settings);
                RconClient rcon = new RconClient(settings.host(), settings.port(), password)) {
            List<Integer> pending = gate.pendingSlots();
            if (!pending.isEmpty()) {
                return recover(settings, rcon, gate, pending);
            }
            if (settings.copyCount() == 0) {
                return new Result(Status.NO_COPY, "Factor ×1 requests zero copies.");
            }
            Source source;
            List<Integer> destinations;
            try {
                source = readSource(rcon, settings);
                if (source.item() == null) {
                    return new Result(Status.NO_COPY, "The test player's main hand is empty.");
                }
                destinations = planDestinations(rcon, settings);
            } catch (PlayerOffline error) {
                return new Result(Status.NO_COPY, "The test player is offline.");
            }
            if (destinations.size() < settings.copyCount()) {
                String message = settings.multiplyFactor() == null
                        ? "The first main inventory slot (inventory.0) is occupied."
                        : "Insufficient space. Need " + settings.copyCount() + " empty main-inventory slots. Found "
                                + destinations.size() + ". Zero writes sent.";
                return new Result(Status.NO_COPY, message);
            }
            return copyItems(rcon, gate, settings, source, destinations);
        }
    }

    private static List<Integer> planDestinations(RconClient rcon, Settings settings) throws IOException {
        List<Integer> destinations = new ArrayList<>();
        int limit = settings.multiplyFactor() == null ? 1 : MAIN_SLOTS;
        for (int slot = 0; slot < limit && destinations.size() < settings.copyCount(); slot++) {
            if (readDestination(rcon, settings.player(), slot) == null) {
                destinations.add(slot);
            }
        }
        return destinations;
    }

    private static Result copyItems(RconClient rcon, RunGate gate, Settings settings, Source source,
            List<Integer> destinations) throws IOException {
        List<Integer> verified = new ArrayList<>();
        Snbt.Tag sourceUid = source.item().uid(settings.uidPath());
        boolean uidVerified = settings.uidType() != null && sourceUid != null && sourceUid.type() == settings.uidType();
        Integer inFlight = null;
        try {
            for (int slot : destinations) {
                if (settings.multiplyFactor() != null && !source.equals(readSource(rcon, settings))) {
                    return stopped(settings, verified, null, "The held stack or its selected slot changed.");
                }
                gate.markPending(slot);
                inFlight = slot;
                String reply = rcon.command(source.copyCommand(settings.player(), slot));
                Item copy = readDestination(rcon, settings.player(), slot);
                if (!reply.stripLeading().startsWith("Replaced a slot on ")) {
                    return stopped(settings, verified, slot, "The backend did not acknowledge the copy.");
                }
                if (copy != null && uidVerified && !sourceUid.equals(copy.uid(settings.uidPath()))) {
                    return stopped(settings, verified, slot, "The configured UID differs at readback.");
                }
                if (copy == null || copy.count() != 1 || !source.item().data().equals(copy.data())) {
                    return stopped(settings, verified, slot, "The destination did not contain the expected one-unit copy.");
                }
                verified.add(slot);
                inFlight = null;
            }
            if (!source.equals(readSource(rcon, settings))) {
                return stopped(settings, verified, null, "The held stack or its selected slot changed.");
            }
            gate.confirmReadback();
        } catch (IOException error) {
            if (inFlight == null && verified.isEmpty()) {
                throw error;
            }
            return stopped(settings, verified, inFlight, "The run was interrupted. " + error.getMessage());
        }

        String copied = settings.multiplyFactor() == null
                ? "Copied one item into inventory.0 with matching item data."
                : batchSummary(settings, verified);
        copied += " The original count is " + source.item().count() + ".";
        return verifyUid(settings, copied, uidVerified ? sourceUid : null);
    }

    private static Result verifyUid(Settings settings, String copied, Snbt.Tag uid) {
        if (settings.uidType() == null) {
            return new Result(Status.UID_UNVERIFIED, copied + " Supply a UID path and type for an exact comparison.");
        }
        if (uid == null) {
            return new Result(Status.UID_UNVERIFIED, copied + " The UID is absent at that path or has a different NBT type.");
        }
        return new Result(Status.PASS, copied + "\nMatching UID ("
                + settings.uidType().name().toLowerCase(Locale.ROOT) + ") = " + Snbt.display(uid));
    }

    private static Result stopped(Settings settings, List<Integer> verified, Integer inFlight, String reason) {
        String summary = batchSummary(settings, verified) + "\n" + reason;
        if (inFlight != null) {
            summary += "\nUncertain slot = inventory." + inFlight + ".";
        }
        if (verified.isEmpty() && inFlight == null) {
            return new Result(Status.NO_COPY, summary);
        }
        Status status = inFlight == null && settings.multiplyFactor() != null ? Status.PARTIAL : Status.INCONCLUSIVE;
        return new Result(status, summary + "\nThe next run for this player and endpoint will only read the attempted slots.");
    }

    private static String batchSummary(Settings settings, List<Integer> verified) {
        return "Requested copies = " + settings.copyCount() + ". Verified copies = " + verified.size()
                + ". Verified slots = " + verified.stream().map(slot -> "inventory." + slot).toList() + ".";
    }

    private static Result recover(Settings settings, RconClient rcon, RunGate gate, List<Integer> pending)
            throws IOException {
        StringBuilder report = new StringBuilder("Read-only check after an interrupted run.");
        try {
            for (int slot : pending) {
                Item item = readDestination(rcon, settings.player(), slot);
                report.append("\ninventory.").append(slot).append(" = ")
                        .append(item == null ? "empty" : item.summary()).append('.');
            }
        } catch (PlayerOffline error) {
            return new Result(Status.INCONCLUSIVE, "Recovery readback requires the test player online.");
        }
        gate.confirmReadback();
        report.append("\nThese are the current slot contents. Review them before starting another copy.");
        return new Result(Status.RECOVERY_READBACK, report.toString());
    }

    private static Source readSource(RconClient rcon, Settings settings) throws IOException {
        if (settings.multiplyFactor() == null) {
            return new Source(-1, readItem(rcon, settings.player(), "SelectedItem", -1));
        }
        String payload = queryPayload(rcon, settings.player(), "SelectedItemSlot");
        int slot;
        try {
            slot = payload == null ? -1 : number(Snbt.read(payload));
        } catch (IllegalArgumentException error) {
            throw new IOException("The backend did not return a valid selected hotbar slot.", error);
        }
        if (slot < 0 || slot >= HOTBAR_SLOTS) {
            throw new IOException("The backend did not return a valid selected hotbar slot.");
        }
        return new Source(slot, readInventorySlot(rcon, settings.player(), slot));
    }

    private static Item readDestination(RconClient rcon, String player, int slot) throws IOException {
        return readInventorySlot(rcon, player, HOTBAR_SLOTS + slot);
    }

    private static Item readInventorySlot(RconClient rcon, String player, int slot) throws IOException {
        // Player inventory NBT puts the main inventory after the nine hotbar slots.
        Item item = readItem(rcon, player, "Inventory[{Slot:" + slot + "b}]", slot);
        return item == null ? readItem(rcon, player, "Inventory[{Slot:" + slot + "}]", slot) : item;
    }

    private static String queryPayload(RconClient rcon, String player, String path) throws IOException {
        String output = rcon.command("data get entity " + player + " " + path).strip();
        if (output.equals("No entity was found") || output.equals("No player was found")) {
            throw new PlayerOffline();
        }
        if (output.equals("Found no elements matching " + path)
                || output.equals("Can't get " + path + "; tag doesn't exist")) {
            return null;
        }
        int prefix = output.indexOf(QUERY_PREFIX);
        if (prefix < 0) {
            throw new IOException("The backend did not return item data for " + path + ".");
        }
        return output.substring(prefix + QUERY_PREFIX.length());
    }

    private static Item readItem(RconClient rcon, String player, String path, int expectedSlot) throws IOException {
        String payload = queryPayload(rcon, player, path);
        if (payload == null) {
            return null;
        }
        try {
            if (!(Snbt.read(payload) instanceof Snbt.Compound compound)) {
                throw new IllegalArgumentException("Expected an item compound");
            }
            if (compound.values().isEmpty() && path.equals("SelectedItem")) {
                return null;
            }
            Map<String, Snbt.Tag> data = new HashMap<>(compound.values());
            if (!(data.get("id") instanceof Snbt.Atom id) || id.type() != Snbt.Type.STRING) {
                throw new IllegalArgumentException("Missing item ID");
            }
            if (expectedSlot >= 0 && number(data.get("Slot")) != expectedSlot) {
                throw new IllegalArgumentException("Readback is for a different inventory slot");
            }
            data.remove("Slot");
            Snbt.Tag count = data.remove("count");
            int amount = count == null ? 1 : number(count);
            if (id.value().equals("minecraft:air") || amount == 0) {
                return null;
            }
            if (amount < 0) {
                throw new IllegalArgumentException("Invalid stack count");
            }
            return new Item(amount, Map.copyOf(data));
        } catch (IllegalArgumentException error) {
            throw new IOException("Could not read the backend's SNBT for " + path + ": " + error.getMessage(), error);
        }
    }

    private static int number(Snbt.Tag tag) {
        if (tag instanceof Snbt.Atom atom && (atom.type() == Snbt.Type.BYTE || atom.type() == Snbt.Type.INT)) {
            return Integer.parseInt(atom.value());
        }
        throw new IllegalArgumentException("Expected a byte or int tag");
    }

    private record Item(int count, Map<String, Snbt.Tag> data) {
        Snbt.Tag uid(List<String> path) {
            return Snbt.atPath(data.get("components"), path);
        }

        String summary() {
            return count + " × " + Snbt.display(data.get("id"));
        }
    }

    private record Source(int hotbarSlot, Item item) {
        String copyCommand(String player, int destination) {
            String slot = hotbarSlot < 0 ? "weapon.mainhand" : "hotbar." + hotbarSlot;
            String selectionGuard = hotbarSlot < 0 ? "" : "if data entity @s {SelectedItemSlot:" + hotbarSlot + "} ";
            return COPY_COMMAND.formatted(player, selectionGuard, slot, destination, destination, slot);
        }
    }

    private static final class PlayerOffline extends IOException {
        private static final long serialVersionUID = 1L;

        PlayerOffline() {
            super("The test player is offline.");
        }
    }
}
