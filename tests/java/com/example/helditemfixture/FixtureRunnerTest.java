package com.example.helditemfixture;

import net.minecraft.client.Minecraft;

public class FixtureRunnerTest {
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    public static void main(String[] args) throws Exception {
        Minecraft client = Minecraft.getInstance();
        Minecraft.Inventory inv = client.player.getInventory();
        FixtureRunner runner = FixtureRunner.bind(new Class<?>[]{Minecraft.class});
        inv.setItem(0, new Minecraft.ItemStack("test-uid-123",64));
        String result = runner.copySlotOne(1).join();
        check(result.startsWith("Copied full stack"), "Expected copy success: " + result);
        check(inv.getItem(0).count == 64, "Source stack count modified");
        check(inv.getItem(1).count == 64, "Destination stack not fully copied");
        check(inv.getItem(0) != inv.getItem(1), "Stack must be a new object");
        check(inv.getItem(1).uid.equals("test-uid-123"), "Copied UID must be preserved");
        check(inv.changed, "Inventory must be marked changed");
        check(runner.copySlotOne(1).join().contains("occupied"), "Occupied slot should reject copy");
        check(runner.copySlotOne(0).join().contains("different destination"), "Self-copy should be rejected");
        inv.setItem(0, new Minecraft.ItemStack("",0));
        check(runner.copySlotOne(2).join().contains("empty"), "Empty source should be rejected");
        inv.setItem(0, new Minecraft.ItemStack("uid-auto",1));
        check(runner.copySlotOne(-1).join().startsWith("Copied full stack"), "Auto destination should work");
        check(inv.getItem(2).uid.equals("uid-auto"), "Auto destination should pick slot 3");
        check(CopySlots.parseDestination("1") == 1, "Slot 2 index parsing");
        check(CopySlots.parseDestination("auto") == -1, "Auto index parsing");
        client.server = null;
        Minecraft.Connection connection = new Minecraft.Connection();
        client.connection = connection;
        check(runner.copySlotOne(3).join().contains("hopliter.copy"), "Unavailable command should be reported");
        check(connection.sentCommand == null, "No command should be sent when the server omits it");

        connection.advertisedCommands.add("execute");
        runner.copySlotOne(3).join();
        check(connection.sentCommand == null, "Execute alone should not enable vanilla copying");
        connection.advertisedCommands.clear();
        connection.advertisedCommands.add("item");
        runner.copySlotOne(3).join();
        check(connection.sentCommand == null, "Item alone should not enable vanilla copying");

        connection.advertisedCommands.add("execute");
        connection.advertisedCommands.add("hoplitercopy");
        check(runner.copySlotOne(3).join().startsWith("Sent /hoplitercopy"), "Expected Paper dispatch");
        check("hoplitercopy 3".equals(connection.sentCommand), "Paper should take precedence when advertised");

        connection.advertisedCommands.remove("hoplitercopy");
        Minecraft.ItemStack source = inv.getItem(0);
        inv.changed = false;
        check(runner.copySlotOne(3).join().startsWith("Sent /execute"), "Expected vanilla dispatch");
        check(("execute if items entity @s hotbar.0 * unless items entity @s hotbar.3 *"
                + " run item replace entity @s hotbar.3 from entity @s hotbar.0").equals(connection.sentCommand),
                "The command must guard both slots and copy within the operator's own inventory");
        check(runner.copySlotOne(9).join().startsWith("Sent /execute"), "Expected main inventory dispatch");
        check(connection.sentCommand.contains("unless items entity @s inventory.0 *"
                + " run item replace entity @s inventory.0 from entity @s hotbar.0"),
                "Slot index 9 must map to inventory.0");

        check(runner.copySlotOne(CopySlots.AUTO).join().startsWith("Sent /execute"), "Expected auto dispatch");
        check(connection.sentCommand.contains("run item replace entity @s hotbar.3 from entity @s hotbar.0"),
                "Auto must choose the first empty slot after the three occupied hotbar slots");
        check(inv.getItem(3).isEmpty() && inv.getItem(9).isEmpty(), "Remote dispatch must leave destinations alone");
        check(inv.getItem(0) == source && !inv.changed, "Remote dispatch must leave the local inventory alone");

        for (int slot = 3; slot < CopySlots.INVENTORY_SIZE; slot++) {
            inv.setItem(slot, new Minecraft.ItemStack("full", 1));
        }
        connection.sentCommand = null;
        check(runner.copySlotOne(CopySlots.AUTO).join().contains("No empty slot"), "Expected full inventory feedback");
        check(connection.sentCommand == null, "A full inventory should block an auto request");
        System.out.println("PASS: local copy, Paper dispatch, and permission-gated vanilla dispatch");
    }
}
