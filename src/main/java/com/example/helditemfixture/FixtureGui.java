package com.example.helditemfixture;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

final class FixtureGui {
    private FixtureGui() {}

    static void show() {
        SwingUtilities.invokeLater(FixtureGui::createWindow);
    }

    private static void createWindow() {
        JFrame window = new JFrame("Held Item UID Fixture");
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel fields = new JPanel(new GridLayout(0, 2, 10, 7));
        JTextField host = field(fields, "Staging RCON host", "127.0.0.1");
        JTextField port = field(fields, "RCON port", "25575");
        JTextField player = field(fields, "Test player's Java username", "");
        JTextField sourceSlot = field(fields, "First slot id (source)", "0");
        JTextField destSlot = field(fields, "Second slot id (destination)", "1");
        JPasswordField passwordField = new JPasswordField(23);
        fields.add(new JLabel("RCON password"));
        fields.add(passwordField);
        content.add(fields, BorderLayout.NORTH);

        JTextArea report = new JTextArea("Click Duplicate Item to copy the first slot item into the second slot so both are filled.", 7, 46);
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        report.setEditable(false);
        content.add(new JScrollPane(report), BorderLayout.CENTER);
        JButton create = new JButton("Duplicate Item");
        content.add(create, BorderLayout.SOUTH);
        create.addActionListener(event -> {
            FixtureRunner.Settings settings;
            try {
                settings = FixtureRunner.Settings.parse(host.getText().strip(), port.getText().strip(),
                        player.getText().strip(), sourceSlot.getText().strip(), destSlot.getText().strip());
            } catch (IllegalArgumentException error) {
                report.setText(error.getMessage());
                return;
            }
            char[] password = passwordField.getPassword();
            passwordField.setText("");
            if (password.length == 0) {
                report.setText("Enter the staging RCON password.");
                return;
            }
            create.setEnabled(false);
            report.setText("Creating item on the staging backend…");
            new SwingWorker<FixtureRunner.Result, Void>() {
                @Override
                protected FixtureRunner.Result doInBackground() throws Exception {
                    try {
                        return FixtureRunner.run(settings, password);
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                }

                @Override
                protected void done() {
                    try {
                        report.setText(get().report());
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        report.setText("Run interrupted.");
                    } catch (ExecutionException error) {
                        report.setText("Error: " + error.getCause().getMessage());
                    } finally {
                        create.setEnabled(true);
                    }
                }
            }.execute();
        });
        window.setContentPane(content);
        window.getRootPane().setDefaultButton(create);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    private static JTextField field(JPanel panel, String label, String initial) {
        JTextField field = new JTextField(initial, 23);
        panel.add(new JLabel(label));
        panel.add(field);
        return field;
    }
}
