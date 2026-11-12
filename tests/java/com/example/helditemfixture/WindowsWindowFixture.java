package com.example.helditemfixture;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public final class WindowsWindowFixture {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame("Test launcher");
            owner.setSize(200, 100);
            owner.setLocationByPlatform(true);
            owner.setAutoRequestFocus(false);
            owner.setVisible(true);

            JDialog game = new JDialog(owner, "Minecraft* 1.21.11", false);
            game.setSize(200, 100);
            game.setLocationRelativeTo(owner);
            game.setAutoRequestFocus(false);
            game.setVisible(true);
        });
        Files.createFile(Path.of(args[0]));
        System.in.read();
        System.exit(0);
    }
}
