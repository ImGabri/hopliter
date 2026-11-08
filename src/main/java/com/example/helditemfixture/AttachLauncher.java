package com.example.helditemfixture;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import java.awt.BorderLayout;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    record Target(String pid, String name) {
        @Override
        public String toString() {
            return name + " — PID " + pid;
        }
    }

    static List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        for (var vm : VirtualMachine.list()) {
            String name = FixtureAgent.clientName(vm.displayName());
            if (name != null) {
                targets.add(new Target(vm.id(), name));
            }
        }
        return targets;
    }

    static String attachAndCopy(String pid, String slot) throws IOException, AttachNotSupportedException,
            AgentInitializationException, AgentLoadException, URISyntaxException {
        if (!pid.matches("[1-9][0-9]{0,9}")) {
            throw new IllegalArgumentException("Enter a numeric Minecraft process ID from --list.");
        }
        Path jar = Path.of(AttachLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Open the packaged Hopliter-Injector.jar to attach.");
        }
        FixtureRunner.parseDestination(slot);

        Path response = Files.createTempFile("hopliter-result-", ".txt").toAbsolutePath();
        try {
            VirtualMachine target = VirtualMachine.attach(pid);
            try {
                Properties properties = target.getSystemProperties();
                if (FixtureAgent.clientName(properties.getProperty("sun.java.command")) == null) {
                    throw new IllegalArgumentException("PID " + pid + " is not a recognized Minecraft Java client.");
                }
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
            JFrame launcher = new JFrame("Hopliter Injector");
            launcher.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            JPanel content = new JPanel(new BorderLayout(8, 10));
            content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
            launcher.setContentPane(content);
            content.add(new JLabel("Hold an item in a single-player world or a LAN world you host."), BorderLayout.NORTH);

            JComboBox<Target> clients = new JComboBox<>();
            JButton refresh = new JButton("Refresh");
            JButton inject = new JButton("Attach and Copy Held Item");
            JTextField destination = new JTextField("auto", 5);
            JPanel controls = new JPanel();
            controls.add(new JLabel("Empty slot (auto or 0–35)"));
            controls.add(destination);
            controls.add(refresh);
            controls.add(inject);

            JTextArea status = new JTextArea(2, 42);
            status.setEditable(false);
            status.setLineWrap(true);
            status.setWrapStyleWord(true);
            status.setOpaque(false);
            JPanel selection = new JPanel(new BorderLayout(8, 8));
            selection.add(clients, BorderLayout.NORTH);
            selection.add(controls, BorderLayout.CENTER);
            content.add(selection, BorderLayout.CENTER);
            content.add(status, BorderLayout.SOUTH);

            Runnable refreshClients = () -> {
                clients.removeAllItems();
                try {
                    targets().forEach(clients::addItem);
                    status.setText(clients.getItemCount() == 0
                            ? "Start Minecraft with Java 21, then refresh." : "Ready to attach.");
                } catch (NoClassDefFoundError | RuntimeException error) {
                    status.setText(errorMessage(error));
                }
                inject.setEnabled(clients.getItemCount() > 0);
            };
            refresh.addActionListener(event -> refreshClients.run());
            inject.addActionListener(event -> {
                Target target = (Target) clients.getSelectedItem();
                String slot = destination.getText();
                try {
                    FixtureRunner.parseDestination(slot);
                } catch (IllegalArgumentException error) {
                    status.setText(error.getMessage());
                    return;
                }
                inject.setEnabled(false);
                refresh.setEnabled(false);
                clients.setEnabled(false);
                destination.setEnabled(false);
                launcher.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                status.setText("Copying in Minecraft. Return to the game if the world is paused.");
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() throws Exception {
                        return attachAndCopy(target.pid(), slot);
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
            return "Run Hopliter with a Java 21 JDK that includes jdk.attach.";
        }
        if (error instanceof AgentInitializationException) {
            return "Open Minecraft 1.21.11 to its title screen and try again. See the game log for details.";
        }
        if (error instanceof AttachNotSupportedException) {
            return "Could not attach. Use the same OS account and an attach-enabled Minecraft JVM.";
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
