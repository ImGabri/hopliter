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
    private static final int AUTO_SLOT = -1;
    private static final int INVENTORY_SIZE = 36;
    private static final Pattern GAME_VERSION = Pattern.compile("\"id\"\\s*:\\s*\"1\\.21\\.11\"");

    private final Object client;
    private final Method integratedServer;
    private final Field localPlayer;
    private final Method playerUuid;
    private final Method playerList;
    private final Method findPlayer;
    private final Method playerInventory;
    private final Method selectedItem;
    private final Method freeSlot;
    private final Method getItem;
    private final Method setItem;
    private final Method setChanged;
    private final Method isEmpty;
    private final Method copyOne;

    static FixtureRunner bind(Class<?>[] loadedClasses) throws ReflectiveOperationException, IOException {
        for (Class<?> candidate : loadedClasses) {
            String name = candidate.getName();
            if (name.equals("gfj") || name.equals("net.minecraft.class_310")) {
                try (var version = candidate.getResourceAsStream("/version.json")) {
                    if (version != null && GAME_VERSION.matcher(
                            new String(version.readNBytes(8192), StandardCharsets.UTF_8)).find()) {
                        return new FixtureRunner(candidate, name.equals("net.minecraft.class_310"));
                    }
                }
            }
        }
        throw new IllegalStateException("Wait for the Minecraft Java 1.21.11 title screen before attaching.");
    }

    private FixtureRunner(Class<?> clientType, boolean fabric) throws ReflectiveOperationException {
        // Minecraft 1.21.11 uses Mojang's obfuscated names or Fabric's intermediary names at runtime.
        client = clientType.getMethod(fabric ? "method_1551" : "V").invoke(null);
        if (client == null) {
            throw new IllegalStateException("Wait for Minecraft to finish starting before attaching.");
        }
        integratedServer = clientType.getMethod(fabric ? "method_1576" : "aa");
        localPlayer = clientType.getField(fabric ? "field_1724" : "s");
        playerUuid = localPlayer.getType().getMethod(fabric ? "method_5667" : "cY");
        playerList = integratedServer.getReturnType().getMethod(fabric ? "method_3760" : "aj");
        findPlayer = playerList.getReturnType().getMethod(fabric ? "method_14602" : "b", UUID.class);
        playerInventory = findPlayer.getReturnType().getMethod(fabric ? "method_31548" : "gK");
        Class<?> inventory = playerInventory.getReturnType();
        selectedItem = inventory.getMethod(fabric ? "method_7391" : "h");
        freeSlot = inventory.getMethod(fabric ? "method_7376" : "k");
        getItem = inventory.getMethod(fabric ? "method_5438" : "a", int.class);
        Class<?> stack = selectedItem.getReturnType();
        setItem = inventory.getMethod(fabric ? "method_5447" : "a", int.class, stack);
        setChanged = inventory.getMethod(fabric ? "method_5431" : "e");
        isEmpty = stack.getMethod(fabric ? "method_7960" : "f");
        copyOne = stack.getMethod(fabric ? "method_46651" : "c", int.class);
    }

    static int parseDestination(String text) {
        String value = text.strip();
        if (value.equalsIgnoreCase("auto") || value.isEmpty()) {
            return AUTO_SLOT;
        }
        try {
            int slot = Integer.parseInt(value);
            if (slot >= 0 && slot < INVENTORY_SIZE) {
                return slot;
            }
        } catch (NumberFormatException ignored) {
            // Invalid text and out-of-range numbers share the same message.
        }
        throw new IllegalArgumentException("Enter auto or a slot from 0 to 35.");
    }

    CompletableFuture<String> copyHeldItem(int destination) {
        CompletableFuture<String> result = new CompletableFuture<>();
        // Use the result as a lock so a timed-out request cannot later change the inventory.
        CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> {
            synchronized (result) {
                result.complete("The local world did not respond. Return to the game and try again.");
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
            if (server == null || player == null) {
                result.complete("Open a single-player world or a LAN world you host.");
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
        Object held = selectedItem.invoke(inventory);
        if ((boolean) isEmpty.invoke(held)) {
            return "Hold an item in your main hand first.";
        }
        int slot = destination == AUTO_SLOT ? (int) freeSlot.invoke(inventory) : destination;
        if (slot < 0) {
            return "Your hotbar and main inventory are full.";
        }
        if (!(boolean) isEmpty.invoke(getItem.invoke(inventory, slot))) {
            return "Slot " + slot + " is occupied. Choose an empty slot or use auto.";
        }
        setItem.invoke(inventory, slot, copyOne.invoke(held, 1));
        setChanged.invoke(inventory);
        return "Copied one item into slot " + slot + " with its original item data.";
    }

    private static void reportError(CompletableFuture<String> result, Exception error) {
        error.printStackTrace(System.err);
        result.complete("Minecraft could not complete the copy. Check the game log and inventory.");
    }
}
