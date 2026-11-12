package com.example.helditemfixture;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class HopliterPlugin extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run /hoplitercopy in Minecraft to copy from your inventory.");
            return true;
        }
        if (!player.hasPermission("hopliter.copy")) {
            player.sendMessage("This command requires hopliter.copy.");
            return true;
        }
        if (args.length > 1) {
            return false;
        }

        int destination = CopySlots.DEFAULT_DESTINATION;
        if (args.length == 1) {
            try {
                destination = CopySlots.parseDestination(args[0]);
            } catch (IllegalArgumentException error) {
                player.sendMessage(error.getMessage());
                return true;
            }
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack source = inventory.getItem(CopySlots.SOURCE);
        if (source == null || source.isEmpty()) {
            player.sendMessage("Put an item in hotbar slot 1 first.");
            return true;
        }
        int slot = destination == CopySlots.AUTO ? inventory.firstEmpty() : destination;
        if (slot < 0 || slot >= CopySlots.INVENTORY_SIZE) {
            player.sendMessage("Your hotbar and main inventory are full.");
            return true;
        }
        ItemStack existing = inventory.getItem(slot);
        if (existing != null && !existing.isEmpty()) {
            player.sendMessage("Destination slot " + (slot + 1) + " is occupied. Choose an empty slot.");
            return true;
        }

        inventory.setItem(slot, source.clone());
        player.sendMessage("Hopliter copied " + source.getAmount() + " item(s) from hotbar slot 1 to slot "
                + (slot + 1) + ".");
        return true;
    }
}
