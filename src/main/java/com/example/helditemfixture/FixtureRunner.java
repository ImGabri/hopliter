package com.example.helditemfixture;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class FixtureRunner {
    static final String COMMAND_SENT = "Sent /";
    private static final Pattern GAME_VERSION = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"(?:1\\.21\\.11|26\\.3)\\\"");

    private final Object client;
    private final Method integratedServer;
    private final Field localPlayer;
    private final Method playerUuid;
    private final Method playerList;
    private final Method findPlayer;
    private final Method playerInventory;
    private final Method localInventory;
    private final Method freeSlot;
    private final Method getItem;
    private final Method setItem;
    private final Method setChanged;
    private final Method isEmpty;
    private final Method copyStack;
    private final MultiplayerCommands multiplayer;

    static FixtureRunner bind(Class<?>[] loadedClasses) throws ReflectiveOperationException, IOException {
        for (Class<?> candidate : loadedClasses) {
            String name = candidate.getName();
            if (name.equals("gfj") || name.equals("net.minecraft.class_310")
                    || name.equals("net.minecraft.client.Minecraft")) {
                try (var version = candidate.getResourceAsStream("/version.json")) {
                    if (version != null && GAME_VERSION.matcher(
                            new String(version.readNBytes(8192), StandardCharsets.UTF_8)).find()) {
                        return new FixtureRunner(candidate, name.equals("net.minecraft.class_310"),
                                name.equals("net.minecraft.client.Minecraft"));
                    }
                }
            }
        }
        throw new IllegalStateException("Start Minecraft Java 1.21.11 or 26.3 before attaching.");
    }

    private FixtureRunner(Class<?> clientType, boolean fabric, boolean modern) throws ReflectiveOperationException {
        // 26.3 uses official unobfuscated member names. 1.21.11 may use obfuscated
        // Vanilla names or intermediary Fabric names.
        client = clientType.getMethod(modern ? "getInstance" : fabric ? "method_1551" : "V").invoke(null);
        if (client == null) {
            throw new IllegalStateException("Wait for Minecraft to finish starting before attaching.");
        }
        integratedServer = clientType.getMethod(modern ? "getSingleplayerServer" : fabric ? "method_1576" : "aa");
        localPlayer = clientType.getField(modern ? "player" : fabric ? "field_1724" : "s");
        playerUuid = localPlayer.getType().getMethod(modern ? "getUUID" : fabric ? "method_5667" : "cY");
        playerList = integratedServer.getReturnType().getMethod(modern ? "getPlayerList" : fabric ? "method_3760" : "aj");
        findPlayer = playerList.getReturnType().getMethod(modern ? "getPlayer" : fabric ? "method_14602" : "b", UUID.class);
        playerInventory = findPlayer.getReturnType().getMethod(modern ? "getInventory" : fabric ? "method_31548" : "gK");
        localInventory = localPlayer.getType().getMethod(playerInventory.getName());
        Class<?> inventory = playerInventory.getReturnType();
        freeSlot = inventory.getMethod(modern ? "getFreeSlot" : fabric ? "method_7376" : "k");
        getItem = inventory.getMethod(modern ? "getItem" : fabric ? "method_5438" : "a", int.class);
        Class<?> stack = getItem.getReturnType();
        setItem = inventory.getMethod(modern ? "setItem" : fabric ? "method_5447" : "a", int.class, stack);
        setChanged = inventory.getMethod(modern ? "setChanged" : fabric ? "method_5431" : "e");
        isEmpty = stack.getMethod(modern ? "isEmpty" : fabric ? "method_7960" : "f");
        copyStack = stack.getMethod(modern ? "copy" : fabric ? "method_7972" : "v");
        multiplayer = MultiplayerCommands.bind(clientType, fabric, modern);
    }

    CompletableFuture<String> copySlotOne(int destination) {
        if (destination == CopySlots.SOURCE) {
            return CompletableFuture.completedFuture("Choose a different destination from hotbar slot 1.");
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> {
            synchronized (result) {
                result.complete("Minecraft did not respond. Return to the game and try again.");
            }
        });
        try {
            ((Executor) client).execute(() -> dispatchCopy(destination, result));
        } catch (RuntimeException error) {
            reportError(result, error);
        }
        return result;
    }

    private void dispatchCopy(int destination, CompletableFuture<String> result) {
        if (result.isDone()) {
            return;
        }
        try {
            Object server = integratedServer.invoke(client);
            Object player = localPlayer.get(client);
            if (player == null) {
                result.complete("Join a world or multiplayer server before copying.");
                return;
            }
            if (server == null) {
                synchronized (result) {
                    if (!result.isDone()) {
                        result.complete(requestMultiplayerCopy(player, destination));
                    }
                }
                return;
            }
            UUID owner = (UUID) playerUuid.invoke(player);
            ((Executor) server).execute(() -> {
                synchronized (result) {
                    if (!result.isDone()) {
                        try {
                            result.complete(copyOnServer(server, owner, destination));
                        } catch (ReflectiveOperationException | RuntimeException error) {
                            reportError(result, error);
                        }
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            reportError(result, error);
        }
    }

    private String copyOnServer(Object server, UUID owner, int destination) throws ReflectiveOperationException {
        Object player = findPlayer.invoke(playerList.invoke(server), owner);
        if (player == null) {
            return "The local player left the world. Rejoin before copying.";
        }
        Object inventory = playerInventory.invoke(player);
        Object original = getItem.invoke(inventory, CopySlots.SOURCE);
        if ((boolean) isEmpty.invoke(original)) {
            return "Hotbar slot 1 is empty. Put the item in the first hotbar slot.";
        }
        int slot = destination == CopySlots.AUTO ? (int) freeSlot.invoke(inventory) : destination;
        if (slot < 0 || slot >= CopySlots.INVENTORY_SIZE) {
            return "No empty slot is available in the 36-slot main inventory.";
        }
        if (!(boolean) isEmpty.invoke(getItem.invoke(inventory, slot))) {
            return "Destination slot " + (slot + 1) + " is occupied. Choose an empty slot.";
        }
        // A deep ItemStack copy preserves count, custom components and UID values.
        // The server executes this mutation on its own main thread.
        setItem.invoke(inventory, slot, copyStack.invoke(original));
        setChanged.invoke(inventory);
        return "Copied full stack from hotbar slot 1 into inventory slot " + (slot + 1)
                + " with its original item data.";
    }

    private String requestMultiplayerCopy(Object player, int destination) throws ReflectiveOperationException {
        Object listener = multiplayer.connection().invoke(client);
        if (listener == null) {
            return "Join a multiplayer server before copying.";
        }
        Object availableCommands = multiplayer.commandRoot(listener);
        if (multiplayer.hasCommand(availableCommands, CopySlots.COMMAND)) {
            String slot = destination == CopySlots.AUTO ? "auto" : Integer.toString(destination);
            String command = CopySlots.COMMAND + " " + slot;
            multiplayer.sendCommand().invoke(listener, command);
            return COMMAND_SENT + command + ". Check Minecraft chat for the server result.";
        }
        if (!multiplayer.hasCommand(availableCommands, "execute") || !multiplayer.hasCommand(availableCommands, "item")) {
            return "Multiplayer needs server operator permission or the Paper plugin with hopliter.copy.";
        }

        int slot = destination;
        if (slot == CopySlots.AUTO) {
            Object inventory = localInventory.invoke(player);
            if ((boolean) isEmpty.invoke(getItem.invoke(inventory, CopySlots.SOURCE))) {
                return "Hotbar slot 1 is empty. Put the item in the first hotbar slot.";
            }
            slot = (int) freeSlot.invoke(inventory);
            if (slot < 1 || slot >= CopySlots.INVENTORY_SIZE) {
                return "No empty slot is available in the 36-slot main inventory.";
            }
        }
        // The server checks both slots and enforces command permissions on execution.
        multiplayer.sendCommand().invoke(listener, CopySlots.vanillaCopyCommand(slot));
        return COMMAND_SENT + "execute for inventory slot " + (slot + 1) + ". Check Minecraft chat for the server result.";
    }

    private record MultiplayerCommands(Method connection, Method commands, Method root, Method child,
                                       Method sendCommand) {
        static MultiplayerCommands bind(Class<?> clientType, boolean fabric, boolean modern)
                throws ReflectiveOperationException {
            Method connection = clientType.getMethod(modern ? "getConnection" : fabric ? "method_1562" : "R");
            Class<?> listener = connection.getReturnType();
            Method commands = listener.getMethod(modern ? "getCommands" : fabric ? "method_2886" : "t");
            Method root = commands.getReturnType().getMethod("getRoot");
            Method child = root.getReturnType().getMethod("getChild", String.class);
            Method send = listener.getMethod(modern ? "sendCommand" : fabric ? "method_45730" : "d", String.class);
            return new MultiplayerCommands(connection, commands, root, child, send);
        }

        Object commandRoot(Object listener) throws ReflectiveOperationException {
            return root.invoke(commands.invoke(listener));
        }

        boolean hasCommand(Object availableCommands, String name) throws ReflectiveOperationException {
            return child.invoke(availableCommands, name) != null;
        }
    }

    private static void reportError(CompletableFuture<String> result, Exception error) {
        error.printStackTrace(System.err);
        result.complete("Minecraft could not complete the copy. Check the game log and inventory.");
    }
}
