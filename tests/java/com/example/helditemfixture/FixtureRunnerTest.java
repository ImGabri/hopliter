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
        client.connection = new Minecraft.Connection();
        check(runner.copySlotOne(3).join().contains("hopliter.copy"), "Unavailable command should be reported");
        check(client.connection.sentCommand == null, "No command should be sent when the server omits it");
        client.connection.copyCommandAvailable = true;
        result = runner.copySlotOne(3).join();
        check(result.startsWith(FixtureRunner.COMMAND_SENT), "Expected command dispatch");
        check(result.contains("Minecraft chat for server confirmation"), "Expected pending server result");
        check(client.connection.sentCommand.equals("hoplitercopy 3"), "Expected the validated destination");
        check(inv.getItem(3).isEmpty(), "A remote request must leave client inventory alone");
        check(runner.copySlotOne(CopySlots.AUTO).join().startsWith(FixtureRunner.COMMAND_SENT),
                "Expected auto command dispatch");
        check(client.connection.sentCommand.equals("hoplitercopy auto"), "Expected the server to choose the slot");
        check(inv.getItem(3).isEmpty(), "A remote auto request must leave client inventory alone");
        System.out.println("PASS: local stack and UID, empty-slot checks, advertised multiplayer command");
    }
}
