package com.example.helditemfixture;

import com.example.helditemfixture.MinecraftProcesses.Target;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import java.awt.BorderLayout;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

final class AttachLauncher {
    private AttachLauncher() {}

    static String attachAndCopy(String pid, String slot) throws IOException, AttachNotSupportedException,
            AgentInitializationException, AgentLoadException, URISyntaxException {
        if (!MinecraftProcesses.isPid(pid)) {
            throw new IllegalArgumentException("Enter a numeric Minecraft process ID from the process list.");
        }
        Path jar = Path.of(AttachLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Open the packaged Hopliter-Multiplayer.jar to attach.");
        }
        CopySlots.parseDestination(slot);
        Path response = Files.createTempFile("hopliter-result-", ".txt").toAbsolutePath();
        try {
            VirtualMachine target = VirtualMachine.attach(pid);
            try {
                Properties properties = target.getSystemProperties();
                String version = properties.getProperty("java.specification.version", "0");
                if (version.startsWith("1.") || Integer.parseInt(version) < 21) {
                    throw new IllegalArgumentException("The Minecraft process must be running on Java 21 or newer.");
                }
                target.loadAgent(jar.toString(), slot.strip() + "\n" + response);
            } finally {
                target.detach();
            }
            String result = Files.readString(response);
            if (result.isBlank()) {
                throw new IOException("Minecraft returned no copy result. Check your inventory before retrying.");
            }
            return result;
        } finally {
            Files.deleteIfExists(response);
        }
    }

    static void show() {
        SwingUtilities.invokeLater(() -> {
            JFrame launcher = new JFrame("Hopliter");
            launcher.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            JPanel content = new JPanel(new BorderLayout(8, 10));
            content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
            launcher.setContentPane(content);
            content.add(new JLabel("Copy hotbar slot 1. Multiplayer requires the server plugin."), BorderLayout.NORTH);

            JComboBox<Target> clients = new JComboBox<>();
            JTextField manualPid = new JTextField(8);
            JPanel pidControls = new JPanel();
            pidControls.add(new JLabel("Minecraft PID (optional)"));
            pidControls.add(manualPid);
            JPanel processSelection = new JPanel(new BorderLayout(8, 0));
            processSelection.add(clients, BorderLayout.CENTER);
            processSelection.add(pidControls, BorderLayout.EAST);
            JButton refresh = new JButton("Refresh");
            JButton inject = new JButton("Copy Slot 1");
            JTextField destination = new JTextField(Integer.toString(CopySlots.DEFAULT_DESTINATION), 5);
            JPanel controls = new JPanel();
            controls.add(new JLabel("Destination index (1 = slot 2, or auto)"));
            controls.add(destination);
            controls.add(refresh);
            controls.add(inject);

            JTextArea status = new JTextArea(3, 42);
            status.setEditable(false);
            status.setLineWrap(true);
            status.setWrapStyleWord(true);
            status.setOpaque(false);
            JPanel selection = new JPanel(new BorderLayout(8, 8));
            selection.add(processSelection, BorderLayout.NORTH);
            selection.add(controls, BorderLayout.CENTER);
            content.add(selection, BorderLayout.CENTER);
            content.add(status, BorderLayout.SOUTH);

            Runnable refreshClients = () -> {
                refresh.setEnabled(false);
                inject.setEnabled(false);
                status.setText("Looking for running Minecraft clients…");
                new SwingWorker<List<Target>, Void>() {
                    @Override
                    protected List<Target> doInBackground() {
                        return MinecraftProcesses.find();
                    }

                    @Override
                    protected void done() {
                        try {
                            var found = get();
                            clients.removeAllItems();
                            found.forEach(clients::addItem);
                            status.setText(found.isEmpty()
                                    ? "Start Minecraft and refresh, or enter its PID from Task Manager."
                                    : "Ready to attach.");
                        } catch (ExecutionException error) {
                            status.setText(errorMessage(error.getCause()));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            status.setText("Process scan interrupted.");
                        } finally {
                            refresh.setEnabled(true);
                            inject.setEnabled(true);
                        }
                    }
                }.execute();
            };
            refresh.addActionListener(event -> refreshClients.run());
            inject.addActionListener(event -> {
                Target target = (Target) clients.getSelectedItem();
                String enteredPid = manualPid.getText().strip();
                String pid = enteredPid.isEmpty() && target != null ? target.pid() : enteredPid;
                if (pid.isEmpty()) {
                    status.setText("Select Minecraft or enter its PID.");
                    return;
                }
                String slot = destination.getText();
                try {
                    CopySlots.parseDestination(slot);
                } catch (IllegalArgumentException error) {
                    status.setText(error.getMessage());
                    return;
                }
                inject.setEnabled(false);
                refresh.setEnabled(false);
                clients.setEnabled(false);
                manualPid.setEnabled(false);
                destination.setEnabled(false);
                launcher.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                status.setText("Waiting for Minecraft. Return to the game if it is paused.");
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() throws Exception {
                        return attachAndCopy(pid, slot);
                    }
                    @Override
                    protected void done() {
                        try {
                            status.setText(get());
                        } catch (ExecutionException error) {
                            status.setText(errorMessage(error.getCause()));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            status.setText("Attach interrupted.");
                        } finally {
                            inject.setEnabled(true);
                            refresh.setEnabled(true);
                            clients.setEnabled(true);
                            manualPid.setEnabled(true);
                            destination.setEnabled(true);
                            launcher.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                        }
                    }
                }.execute();
            });
            refreshClients.run();
            launcher.getRootPane().setDefaultButton(inject);
            launcher.pack();
            launcher.setLocationRelativeTo(null);
            launcher.setVisible(true);
        });
    }

    static String errorMessage(Throwable error) {
        if (error instanceof NoClassDefFoundError) {
            return "Run Hopliter with a JDK 21 or newer that includes jdk.attach.";
        }
        if (error instanceof AgentInitializationException) {
            return "Start a supported Minecraft world and try again. Check the game log for details.";
        }
        if (error instanceof AttachNotSupportedException) {
            return "Could not attach. Use the same OS account and elevation level as Minecraft.";
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
