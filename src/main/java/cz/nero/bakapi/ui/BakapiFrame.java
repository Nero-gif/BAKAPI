package cz.nero.bakapi.ui;

import cz.nero.bakapi.model.GradeEntry;
import cz.nero.bakapi.service.BakalariClient;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

public final class BakapiFrame extends JFrame {
    private final JTextField baseUrlField = new JTextField(getEnvOrDefault("BAKA_BASE_URL", "https://bakalari.infis.cz"), 28);
    private final JTextField usernameField = new JTextField(getEnvOrDefault("BAKA_USER", ""), 20);
    private final JPasswordField passwordField = new JPasswordField(getEnvOrDefault("BAKA_PASS", ""), 20);
    private final JButton loadButton = new JButton("Načíst známky");
    private final JLabel statusLabel = new JLabel("Připraveno");
    private final JProgressBar progressBar = new JProgressBar();
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Předmět", "Učitel", "Známka", "Téma", "Poznámka", "Váha", "Datum"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable gradesTable = new JTable(tableModel);
    private final BakalariClient client = new BakalariClient();

    public BakapiFrame() {
        super("BAKAPI - Přehled známek");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(980, 600);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        add(createFormPanel(), BorderLayout.NORTH);
        add(new JScrollPane(gradesTable), BorderLayout.CENTER);
        add(createStatusPanel(), BorderLayout.SOUTH);

        loadButton.addActionListener(event -> loadGrades());
    }

    private JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("URL:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(baseUrlField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Uživatel:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        panel.add(new JLabel("Heslo:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(passwordField, gbc);

        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.EAST;
        panel.add(loadButton, gbc);

        return panel;
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(statusLabel);

        panel.add(left, BorderLayout.WEST);
        panel.add(progressBar, BorderLayout.EAST);
        return panel;
    }

    private void loadGrades() {
        String baseUrl = baseUrlField.getText();
        String username = usernameField.getText();
        String password = new String(passwordField.getPassword());

        loadButton.setEnabled(false);
        progressBar.setVisible(true);
        statusLabel.setText("Načítám známky...");

        SwingWorker<List<GradeEntry>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<GradeEntry> doInBackground() throws Exception {
                return client.fetchGrades(baseUrl, username, password);
            }

            @Override
            protected void done() {
                try {
                    List<GradeEntry> grades = get();
                    updateTable(grades);

                    if (grades.isEmpty()) {
                        statusLabel.setText("Na stránce nebyly nalezeny žádné známky.");
                    } else {
                        statusLabel.setText("Načteno " + grades.size() + " známek.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Načítání bylo přerušeno.");
                    showError("Načítání bylo přerušeno.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause != null ? cause.getMessage() : "Neznámá chyba.";
                    statusLabel.setText("Načítání selhalo.");
                    showError(message);
                } finally {
                    loadButton.setEnabled(true);
                    progressBar.setVisible(false);
                }
            }
        };

        worker.execute();
    }

    private void updateTable(List<GradeEntry> grades) {
        tableModel.setRowCount(0);
        for (GradeEntry grade : grades) {
            tableModel.addRow(new Object[]{
                    grade.subject(),
                    grade.teacher(),
                    grade.markText(),
                    grade.caption(),
                    grade.note(),
                    grade.weight(),
                    grade.date()
            });
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Chyba", JOptionPane.ERROR_MESSAGE);
    }

    private static String getEnvOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
