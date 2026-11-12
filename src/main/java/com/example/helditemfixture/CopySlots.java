package com.example.helditemfixture;

final class CopySlots {
    static final String COMMAND = "hoplitercopy";
    static final int SOURCE = 0;
    static final int DEFAULT_DESTINATION = 1;
    static final int AUTO = -1;
    static final int INVENTORY_SIZE = 36;
    private static final int HOTBAR_SIZE = 9;

    private CopySlots() {}

    static int parseDestination(String text) {
        String value = text.strip();
        if (value.equalsIgnoreCase("auto") || value.isEmpty()) {
            return AUTO;
        }
        int slot;
        try {
            slot = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter auto or a destination index from 1 to 35 (slot 2 = 1).");
        }
        if (slot == SOURCE) {
            throw new IllegalArgumentException("Choose a different destination from hotbar slot 1.");
        }
        if (slot < 1 || slot >= INVENTORY_SIZE) {
            throw new IllegalArgumentException("Enter auto or a destination index from 1 to 35 (slot 2 = 1).");
        }
        return slot;
    }

    static String vanillaCopyCommand(int destination) {
        String slot = destination < HOTBAR_SIZE ? "hotbar." + destination
                : "inventory." + (destination - HOTBAR_SIZE);
        return "execute if items entity @s hotbar.0 * unless items entity @s " + slot
                + " * run item replace entity @s " + slot + " from entity @s hotbar.0";
    }
}
