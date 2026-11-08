package com.example.helditemfixture;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

final class FixtureGui {
    private static final Color HEADER_BG = new Color(33, 37, 41);
    private static final Color ACCENT = new Color(0, 123, 255);
    private static final Color ACCENT_HOVER = new Color(0, 96, 200);
    private static final Color BG = Color.WHITE;
    private static final Color CARD_BG = new Color(248, 249, 250);
    private static final Color MUTED = new Color(108, 117, 125);
    private static final Color BORDER = new Color(222, 226, 230);

    private FixtureGui() {}

    static void show() {
        SwingUtilities.invokeLater(FixtureGui::createWindow);
    }

    private static void createWindow() {
        JFrame window = new JFrame("Held Item UID Fixture");
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setBackground(BG);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(2, 2, 2, 2));
        window.setContentPane(root);

        // ---- Header ----
        JPanel header = new JPanel();
        header.setBackground(HEADER_BG);
        header.setBorder(new EmptyBorder(22, 24, 22, 24));
        header.setLayout(new BorderLayout(0, 6));
        JLabel title = new JLabel("Held Item UID Fixture");
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(Color.WHITE);
        JLabel subtitle = new JLabel("Clone hotbar slot 1 → slot 2 so both are filled");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        subtitle.setForeground(new Color(173, 181, 189));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);

        // ---- Form card ----
        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER, 1), new EmptyBorder(20, 24, 20, 24)));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(7, 6, 7, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;

        JTextField host = field("Staging RCON host", "127.0.0.1", card, g, 0);
        JTextField port = field("RCON port", "25575", card, g, 1);
        JTextField player = field("Test player's Java username", "", card, g, 2);
        JTextField sourceSlot = field("Source hotbar slot", "1", card, g, 3);
        JTextField destSlot = field("Destination hotbar slot", "2", card, g, 4);
        JPasswordField passwordField = new JPasswordField(23);
        g.gridx = 0; g.gridy = 5; g.gridwidth = 1;
        card.add(new JLabel("RCON password"), g);
        g.gridx = 1; g.gridwidth = GridBagConstraints.REMAINDER;
        card.add(passwordField, g);

        root.add(card, BorderLayout.CENTER);

        // ---- Action bar ----
        JPanel action = new JPanel(new BorderLayout());
        action.setBackground(BG);
        action.setBorder(new EmptyBorder(10, 24, 0, 24));
        JButton clone = new JButton("Clone Hotbar 1 → 2");
        clone.setFont(new Font("Segoe UI", Font.BOLD, 15));
        clone.setBackground(ACCENT);
        clone.setForeground(Color.WHITE);
        clone.setFocusPainted(false);
        clone.setBorderPainted(false);
        clone.setOpaque(true);
        clone.setPreferredSize(new Dimension(250, 46));
        clone.setMaximumSize(new Dimension(260, 46));
        clone.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clone.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { clone.setBackground(ACCENT_HOVER); }
            @Override public void mouseExited(MouseEvent e) { clone.setBackground(ACCENT); }
        });
        JPanel btnWrap = new JPanel(new BorderLayout());
        btnWrap.setBackground(BG);
        btnWrap.setBorder(new EmptyBorder(0, 0, 0, 0));
        btnWrap.add(clone, BorderLayout.WEST);
        action.add(btnWrap, BorderLayout.WEST);
        root.add(action, BorderLayout.SOUTH);

        // ---- Status ----
        JTextArea report = new JTextArea(6, 44);
        report.setText("Fill in the connection details, then click Clone Hotbar 1 → 2.");
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        report.setEditable(false);
        report.setBackground(new Color(252, 253, 254));
        report.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER, 1), new EmptyBorder(10, 12, 10, 12)));
        report.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        report.setForeground(new Color(55, 65, 81));
        JScrollPane scroll = new JScrollPane(report);
        scroll.setBorder(BorderFactory.createEmptyBorder(10, 24, 24, 24));
        scroll.setBackground(BG);
        scroll.getVerticalScrollBar().setBackground(BG);
        root.add(scroll, BorderLayout.EAST);

        clone.addActionListener(event -> {
            FixtureRunner.Settings settings;
            try {
                settings = FixtureRunner.Settings.parse(host.getText().strip(), port.getText().strip(),
                        player.getText().strip(), sourceSlot.getText().strip(), destSlot.getText().strip());
            } catch (IllegalArgumentException error) {
                report.setText("Invalid input: " + error.getMessage());
                return;
            }
            char[] password = passwordField.getPassword();
            passwordField.setText("");
            if (password.length == 0) {
                report.setText("Enter the staging RCON password.");
                return;
            }
            clone.setEnabled(false);
            clone.setText("Cloning…");
            report.setText("Connecting to the staging backend…");
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
                        FixtureRunner.Result result = get();
                        report.setText(result.report());
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        report.setText("Run interrupted.");
                    } catch (ExecutionException error) {
                        report.setText("Error: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
                    } finally {
                        clone.setEnabled(true);
                        clone.setText("Clone Hotbar 1 → 2");
                    }
                }
            }.execute();
        });
        window.getRootPane().setDefaultButton(clone);

        window.pack();
        window.setMinimumSize(new Dimension(560, 420));
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    private static JTextField field(String label, String initial, JPanel card, GridBagConstraints g, int row) {
        g.gridx = 0; g.gridy = row; g.gridwidth = 1;
        card.add(new JLabel(label), g);
        g.gridx = 1; g.gridwidth = GridBagConstraints.REMAINDER;
        JTextField field = new JTextField(initial, 23);
        card.add(field, g);
        return field;
    }
}
