package cz.nero.bakapi.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import cz.nero.bakapi.model.GradeEntry;
import cz.nero.bakapi.service.BakalariClient;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.util.List;
import java.util.Locale;
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
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.table.DefaultTableModel;

public final class BakapiFrame extends JFrame {
    private enum ThemeMode {
        LIGHT,
        DARK
    }

    private ThemeMode currentTheme;

    private final JTextField baseUrlField = new JTextField(getEnvOrDefault("BAKA_BASE_URL", "https://bakalari.infis.cz"), 28);
    private final JTextField usernameField = new JTextField(getEnvOrDefault("BAKA_USER", ""), 20);
    private final JPasswordField passwordField = new JPasswordField(getEnvOrDefault("BAKA_PASS", ""), 20);
    private final JButton loadButton = new JButton("Načíst známky");
    private final JToggleButton themeToggle = new JToggleButton();
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
        currentTheme = detectSystemTheme();
        applyTheme(currentTheme);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1080, 680);
        setMinimumSize(getSize());
        setLocationRelativeTo(null);

        JPanel rootPanel = new JPanel(new BorderLayout(12, 12));
        rootPanel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(rootPanel);

        rootPanel.add(createFormPanel(), BorderLayout.NORTH);
        rootPanel.add(createTablePanel(), BorderLayout.CENTER);
        rootPanel.add(createStatusPanel(), BorderLayout.SOUTH);

        loadButton.addActionListener(event -> loadGrades());
        themeToggle.addActionListener(event -> switchTheme(themeToggle.isSelected() ? ThemeMode.DARK : ThemeMode.LIGHT));

        configureComponentStyles();
        configureTable();
        switchTheme(currentTheme);
    }

    private JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Přihlášení"),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
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

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionsPanel.setOpaque(false);
        themeToggle.setToolTipText("Přepnout světlý/tmavý motiv");
        actionsPanel.add(themeToggle);
        actionsPanel.add(loadButton);

        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.EAST;
        panel.add(actionsPanel, gbc);

        return panel;
    }

    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Přehled známek"));
        panel.add(new JScrollPane(gradesTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(statusLabel);

        panel.add(left, BorderLayout.WEST);
        panel.add(progressBar, BorderLayout.EAST);
        return panel;
    }

    private void configureTable() {
        gradesTable.setFillsViewportHeight(true);
        gradesTable.setAutoCreateRowSorter(true);
        gradesTable.setRowHeight(32);
        gradesTable.setShowVerticalLines(false);
        gradesTable.setShowHorizontalLines(true);
        gradesTable.getTableHeader().setReorderingAllowed(false);
    }

    private void configureComponentStyles() {
        baseUrlField.putClientProperty("JTextField.placeholderText", "https://bakalari.infis.cz");
        usernameField.putClientProperty("JTextField.placeholderText", "uživatelské jméno");
        passwordField.putClientProperty("JTextField.placeholderText", "heslo");
        loadButton.putClientProperty("JButton.buttonType", "roundRect");
        themeToggle.putClientProperty("JButton.buttonType", "roundRect");
    }

    private void switchTheme(ThemeMode themeMode) {
        applyTheme(themeMode);
        currentTheme = themeMode;
        SwingUtilities.updateComponentTreeUI(this);
        configureTable();
        themeToggle.setSelected(themeMode == ThemeMode.DARK);
        updateThemeToggleText();
    }

    private void updateThemeToggleText() {
        themeToggle.setText(themeToggle.isSelected() ? "Tmavý" : "Světlý");
    }

    private static ThemeMode detectSystemTheme() {
        String gtkTheme = System.getenv("GTK_THEME");
        if (gtkTheme != null && gtkTheme.toLowerCase(Locale.ROOT).contains("dark")) {
            return ThemeMode.DARK;
        }

        Object gnomeTheme = Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Net/ThemeName");
        if (gnomeTheme instanceof String gnomeThemeName
                && gnomeThemeName.toLowerCase(Locale.ROOT).contains("dark")) {
            return ThemeMode.DARK;
        }

        Object windowsDarkMode = Toolkit.getDefaultToolkit().getDesktopProperty("win.darkMode");
        if (windowsDarkMode instanceof Boolean darkMode) {
            return darkMode ? ThemeMode.DARK : ThemeMode.LIGHT;
        }

        Color panelBackground = UIManager.getColor("Panel.background");
        if (panelBackground != null && isDarkColor(panelBackground)) {
            return ThemeMode.DARK;
        }

        return ThemeMode.LIGHT;
    }

    private static boolean isDarkColor(Color color) {
        double luminance = (0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue()) / 255.0;
        return luminance < 0.5;
    }

    private static void applyTheme(ThemeMode themeMode) {
        try {
            if (themeMode == ThemeMode.DARK) {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } else {
                UIManager.setLookAndFeel(new FlatLightLaf());
            }
            applyModernDefaults(themeMode);
        } catch (UnsupportedLookAndFeelException e) {
            throw new IllegalStateException("Nepodařilo se nastavit vybraný motiv.", e);
        }
    }

    private static void applyModernDefaults(ThemeMode themeMode) {
        UIManager.put("Component.arc", 14);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 12);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.rowHeight", 32);
        UIManager.put("TitlePane.unifiedBackground", true);

        if (themeMode == ThemeMode.DARK) {
            UIManager.put("Table.alternateRowColor", new Color(43, 48, 58));
        } else {
            UIManager.put("Table.alternateRowColor", new Color(244, 247, 252));
        }
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
