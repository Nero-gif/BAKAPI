package cz.nero.bakapi.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import cz.nero.bakapi.model.GradeEntry;
import cz.nero.bakapi.service.BakalariClient;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

public final class BakapiFrame extends JFrame {
    private enum ThemeMode {
        LIGHT,
        DARK
    }

    private static final String LOGIN_CARD = "login";
    private static final String GRADES_CARD = "grades";
    private static final Pattern SINGLE_MARK_PATTERN = Pattern.compile("^([1-5])\\s*([+-]?)$");
    private static final Pattern RANGE_MARK_PATTERN = Pattern.compile("^([1-5])\\s*[-–]\\s*([1-5])$");
    private static final Pattern LEADING_NUMERIC_MARK_PATTERN = Pattern.compile("^\\s*([1-5])");

    private ThemeMode currentTheme;
    private boolean applyingTheme;

    private final JTextField baseUrlField = new JTextField(getEnvOrDefault("BAKA_BASE_URL", "https://bakalari.infis.cz"), 28);
    private final JTextField usernameField = new JTextField(getEnvOrDefault("BAKA_USER", ""), 20);
    private final JPasswordField passwordField = new JPasswordField(getEnvOrDefault("BAKA_PASS", ""), 20);
    private final JButton loginButton = new JButton("Přihlásit a načíst známky");
    private final JButton refreshButton = new JButton("Obnovit");
    private final JButton logoutButton = new JButton("Odhlásit");
    private final JToggleButton themeToggle = new JToggleButton();
    private final JLabel statusLabel = new JLabel("Připraveno");
    private final JProgressBar progressBar = new JProgressBar();

    private final DefaultTableModel gradesTableModel = new DefaultTableModel(
            new Object[]{"Předmět", "Učitel", "Známka", "Váha", "Příspěvek do průměru", "Téma", "Poznámka", "Datum"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable gradesTable = new JTable(gradesTableModel);
    private final TableRowSorter<DefaultTableModel> gradesSorter = new TableRowSorter<>(gradesTableModel);
    @SuppressWarnings("unchecked")
    private final JComboBox<String>[] filterCombos = new JComboBox[gradesTableModel.getColumnCount()];
    private boolean updatingFilterOptions;

    private final DefaultTableModel subjectSummaryTableModel = new DefaultTableModel(
            new Object[]{"Předmět", "Vážený průměr", "Výsledná známka", "Slovní hodnocení"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable subjectSummaryTable = new JTable(subjectSummaryTableModel);
    private final JLabel overallAverageLabel = new JLabel("Celkový průměr výsledných známek: -");

    private final DefaultTableModel subjectGradeCountsTableModel = new DefaultTableModel(
            new Object[]{"Předmět", "1", "2", "3", "4", "5", "Celkem"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable subjectGradeCountsTable = new JTable(subjectGradeCountsTableModel);

    private final DefaultTableModel resultGradeDistributionTableModel = new DefaultTableModel(
            new Object[]{"Metrika", "1", "2", "3", "4", "5"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable resultGradeDistributionTable = new JTable(resultGradeDistributionTableModel);

    private final Map<String, Color> subjectColorCache = new LinkedHashMap<>();

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);
    private final BakalariClient client = new BakalariClient();

    public BakapiFrame() {
        super("BAKAPI - Přehled známek");
        currentTheme = detectSystemTheme();
        applyTheme(currentTheme);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1180, 760);
        setMinimumSize(getSize());
        setLocationRelativeTo(null);

        JPanel rootPanel = new JPanel(new BorderLayout(12, 12));
        rootPanel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(rootPanel);

        rootPanel.add(createHeaderPanel(), BorderLayout.NORTH);
        contentPanel.add(createLoginPanel(), LOGIN_CARD);
        contentPanel.add(createGradesPanel(), GRADES_CARD);
        rootPanel.add(contentPanel, BorderLayout.CENTER);
        rootPanel.add(createStatusPanel(), BorderLayout.SOUTH);

        loginButton.addActionListener(event -> loadGrades());
        refreshButton.addActionListener(event -> loadGrades());
        logoutButton.addActionListener(event -> logout());
        baseUrlField.addActionListener(event -> loadGrades());
        usernameField.addActionListener(event -> loadGrades());
        passwordField.addActionListener(event -> loadGrades());
        themeToggle.addActionListener(event -> {
            if (!applyingTheme) {
                switchTheme(themeToggle.isSelected() ? ThemeMode.DARK : ThemeMode.LIGHT);
            }
        });

        configureComponentStyles();
        configureTables();
        switchTheme(currentTheme);
        showLoginView();
    }

    private JPanel createHeaderPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        JLabel titleLabel = new JLabel("BAKAPI");
        titleLabel.putClientProperty("FlatLaf.styleClass", "h1");

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightPanel.setOpaque(false);
        themeToggle.setToolTipText("Přepnout světlý/tmavý motiv");
        rightPanel.add(themeToggle);

        panel.add(titleLabel, BorderLayout.WEST);
        panel.add(rightPanel, BorderLayout.EAST);
        return panel;
    }

    private JPanel createLoginPanel() {
        JPanel wrapper = new JPanel(new GridBagLayout());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Přihlášení do Bakalářů"),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(7, 7, 7, 7);
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

        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.EAST;
        panel.add(loginButton, gbc);

        wrapper.add(panel);
        return wrapper;
    }

    private JPanel createGradesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Přehled známek"),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(refreshButton);
        actions.add(logoutButton);

        panel.add(actions, BorderLayout.NORTH);

        JPanel gradesTab = new JPanel(new BorderLayout(0, 10));
        gradesTab.add(createFilterPanel(), BorderLayout.NORTH);
        gradesTab.add(new JScrollPane(gradesTable), BorderLayout.CENTER);

        JPanel summaryTab = new JPanel(new BorderLayout(0, 8));
        JSplitPane summarySplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(subjectSummaryTable),
                new JScrollPane(resultGradeDistributionTable)
        );
        summarySplit.setResizeWeight(0.74);
        summarySplit.setContinuousLayout(true);
        summarySplit.setBorder(null);
        summaryTab.add(summarySplit, BorderLayout.CENTER);
        summaryTab.add(overallAverageLabel, BorderLayout.SOUTH);

        JPanel subjectCountsTab = new JPanel(new BorderLayout(0, 8));
        subjectCountsTab.add(new JScrollPane(subjectGradeCountsTable), BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Známky", gradesTab);
        tabs.addTab("Průměry předmětů", summaryTab);
        tabs.addTab("Statistika známek", subjectCountsTab);
        panel.add(tabs, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createFilterPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 4, 8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Filtry sloupců"));

        for (int column = 0; column < gradesTableModel.getColumnCount(); column++) {
            JPanel oneFilterPanel = new JPanel(new BorderLayout(0, 4));
            oneFilterPanel.setOpaque(false);

            JLabel columnLabel = new JLabel(gradesTableModel.getColumnName(column));
            JComboBox<String> combo = new JComboBox<>();
            combo.setEditable(true);
            combo.setModel(new DefaultComboBoxModel<>(new String[]{""}));
            combo.addActionListener(event -> applyColumnFilters());
            JTextField editor = (JTextField) combo.getEditor().getEditorComponent();
            editor.putClientProperty("JTextField.placeholderText", "Vyber nebo napiš...");
            editor.getDocument().addDocumentListener(new FilterChangeListener());
            filterCombos[column] = combo;

            oneFilterPanel.add(columnLabel, BorderLayout.NORTH);
            oneFilterPanel.add(combo, BorderLayout.CENTER);
            panel.add(oneFilterPanel);
        }

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

    private void configureTables() {
        Color gridColor = currentTheme == ThemeMode.DARK ? new Color(115, 125, 140) : new Color(185, 195, 210);

        gradesTable.setRowSorter(gradesSorter);
        gradesTable.setFillsViewportHeight(true);
        gradesTable.setAutoCreateRowSorter(false);
        gradesTable.setRowHeight(32);
        gradesTable.getTableHeader().setReorderingAllowed(false);
        applyGridStyle(gradesTable, gridColor);
        DefaultTableCellRenderer subjectColorRenderer = createSubjectColorRenderer(gradesTable, gradesTableModel);
        for (int column = 0; column < gradesTable.getColumnModel().getColumnCount(); column++) {
            gradesTable.getColumnModel().getColumn(column).setCellRenderer(subjectColorRenderer);
        }

        subjectSummaryTable.setFillsViewportHeight(true);
        subjectSummaryTable.setRowHeight(30);
        subjectSummaryTable.getTableHeader().setReorderingAllowed(false);
        applyGridStyle(subjectSummaryTable, gridColor);
        DefaultTableCellRenderer summaryColorRenderer = createSubjectColorRenderer(subjectSummaryTable, subjectSummaryTableModel);
        for (int column = 0; column < subjectSummaryTable.getColumnModel().getColumnCount(); column++) {
            subjectSummaryTable.getColumnModel().getColumn(column).setCellRenderer(summaryColorRenderer);
        }

        subjectGradeCountsTable.setFillsViewportHeight(true);
        subjectGradeCountsTable.setRowHeight(30);
        subjectGradeCountsTable.getTableHeader().setReorderingAllowed(false);
        applyGridStyle(subjectGradeCountsTable, gridColor);
        DefaultTableCellRenderer subjectCountsRenderer = createSubjectColorRenderer(subjectGradeCountsTable, subjectGradeCountsTableModel);
        for (int column = 0; column < subjectGradeCountsTable.getColumnModel().getColumnCount(); column++) {
            subjectGradeCountsTable.getColumnModel().getColumn(column).setCellRenderer(subjectCountsRenderer);
        }

        resultGradeDistributionTable.setFillsViewportHeight(true);
        resultGradeDistributionTable.setRowHeight(30);
        resultGradeDistributionTable.getTableHeader().setReorderingAllowed(false);
        applyGridStyle(resultGradeDistributionTable, gridColor);
        DefaultTableCellRenderer resultDistributionRenderer = createStripedRenderer(resultGradeDistributionTable);
        for (int column = 0; column < resultGradeDistributionTable.getColumnModel().getColumnCount(); column++) {
            resultGradeDistributionTable.getColumnModel().getColumn(column).setCellRenderer(resultDistributionRenderer);
        }
    }

    private void applyGridStyle(JTable table, Color gridColor) {
        table.setShowVerticalLines(true);
        table.setShowHorizontalLines(true);
        table.setGridColor(gridColor);
        table.setIntercellSpacing(new Dimension(1, 1));
        table.setCellSelectionEnabled(true);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        installCrossHighlightRepaint(table);
    }

    private void installCrossHighlightRepaint(JTable table) {
        if (Boolean.TRUE.equals(table.getClientProperty("crossHighlightInstalled"))) {
            return;
        }

        ListSelectionListener repaintListener = event -> table.repaint();
        table.getSelectionModel().addListSelectionListener(repaintListener);
        table.getColumnModel().getSelectionModel().addListSelectionListener(repaintListener);
        table.putClientProperty("crossHighlightInstalled", true);
    }

    private void configureComponentStyles() {
        baseUrlField.putClientProperty("JTextField.placeholderText", "https://bakalari.infis.cz");
        usernameField.putClientProperty("JTextField.placeholderText", "uživatelské jméno");
        passwordField.putClientProperty("JTextField.placeholderText", "heslo");

        loginButton.putClientProperty("JButton.buttonType", "roundRect");
        refreshButton.putClientProperty("JButton.buttonType", "roundRect");
        logoutButton.putClientProperty("JButton.buttonType", "roundRect");
        themeToggle.putClientProperty("JButton.buttonType", "roundRect");

        for (JComboBox<String> combo : filterCombos) {
            if (combo != null) {
                combo.putClientProperty("JComponent.roundRect", true);
            }
        }
    }

    private void switchTheme(ThemeMode themeMode) {
        applyingTheme = true;
        try {
            applyTheme(themeMode);
            currentTheme = themeMode;
            subjectColorCache.clear();
            SwingUtilities.updateComponentTreeUI(this);
            configureComponentStyles();
            configureTables();
            if (themeToggle.isSelected() != (themeMode == ThemeMode.DARK)) {
                themeToggle.setSelected(themeMode == ThemeMode.DARK);
            }
            updateThemeToggleText();
        } finally {
            applyingTheme = false;
        }
    }

    private void updateThemeToggleText() {
        themeToggle.setText(themeToggle.isSelected() ? "Tmavý" : "Světlý");
    }

    private void showLoginView() {
        cardLayout.show(contentPanel, LOGIN_CARD);
        getRootPane().setDefaultButton(loginButton);
    }

    private void showGradesView() {
        cardLayout.show(contentPanel, GRADES_CARD);
        getRootPane().setDefaultButton(refreshButton);
    }

    private void logout() {
        clearFilters();
        gradesTableModel.setRowCount(0);
        subjectSummaryTableModel.setRowCount(0);
        subjectGradeCountsTableModel.setRowCount(0);
        resultGradeDistributionTableModel.setRowCount(0);
        overallAverageLabel.setText("Celkový průměr výsledných známek: -");
        statusLabel.setText("Odhlášeno.");
        showLoginView();
    }

    private void loadGrades() {
        String baseUrl = baseUrlField.getText();
        String username = usernameField.getText();
        String password = new String(passwordField.getPassword());

        setLoadingState(true);
        statusLabel.setText("Přihlašuji a načítám známky...");

        SwingWorker<List<GradeEntry>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<GradeEntry> doInBackground() throws Exception {
                return client.fetchGrades(baseUrl, username, password);
            }

            @Override
            protected void done() {
                try {
                    List<GradeEntry> grades = get();
                    rebuildTables(grades);
                    showGradesView();

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
                    setLoadingState(false);
                }
            }
        };

        worker.execute();
    }

    private void rebuildTables(List<GradeEntry> grades) {
        gradesTableModel.setRowCount(0);
        subjectSummaryTableModel.setRowCount(0);
        subjectGradeCountsTableModel.setRowCount(0);
        resultGradeDistributionTableModel.setRowCount(0);

        List<ComputedGradeRow> computedRows = new ArrayList<>();
        Map<String, Double> subjectWeightTotals = new LinkedHashMap<>();
        Map<String, SubjectStats> subjectStats = new LinkedHashMap<>();
        Map<String, String> subjectDisplayNames = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> subjectGradeCounts = new LinkedHashMap<>();
        Map<String, Integer> subjectTotals = new LinkedHashMap<>();
        Map<String, String> presentGradeBuckets = new LinkedHashMap<>();
        presentGradeBuckets.put("1", "1");
        presentGradeBuckets.put("2", "2");
        presentGradeBuckets.put("3", "3");
        presentGradeBuckets.put("4", "4");
        presentGradeBuckets.put("5", "5");

        for (GradeEntry grade : grades) {
            Double markValue = parseMarkValue(grade.markText());
            double weightValue = parseWeight(grade.weight());
            ComputedGradeRow computed = new ComputedGradeRow(grade, markValue, weightValue);
            computedRows.add(computed);

            String subjectKey = normalizeSubject(grade.subject());
            subjectDisplayNames.putIfAbsent(subjectKey, grade.subject());
            subjectGradeCounts.computeIfAbsent(subjectKey, key -> new LinkedHashMap<>());

            if (markValue != null) {
                subjectWeightTotals.merge(subjectKey, weightValue, Double::sum);
                subjectStats.computeIfAbsent(subjectKey, key -> new SubjectStats()).add(markValue, weightValue);
            }

            String bucket = resolveGradeBucket(grade.markText(), markValue);
            if (!bucket.isEmpty()) {
                subjectGradeCounts.get(subjectKey).merge(bucket, 1, Integer::sum);
                subjectTotals.merge(subjectKey, 1, Integer::sum);
                presentGradeBuckets.putIfAbsent(bucket, bucket);
            }
        }

        for (ComputedGradeRow row : computedRows) {
            GradeEntry grade = row.grade();
            String subjectKey = normalizeSubject(grade.subject());
            String contributionText = "-";
            if (row.markValue() != null) {
                double subjectWeight = subjectWeightTotals.getOrDefault(subjectKey, 0.0);
                if (subjectWeight > 0) {
                    double contribution = (row.markValue() * row.weightValue()) / subjectWeight;
                    contributionText = formatTwoDecimals(contribution);
                }
            }

            gradesTableModel.addRow(new Object[]{
                    grade.subject(),
                    grade.teacher(),
                    grade.markText(),
                    formatWeight(row.weightValue()),
                    contributionText,
                    grade.caption(),
                    grade.note(),
                    grade.date()
            });
        }

        double overallFinalGradeSum = 0.0;
        int overallFinalGradeCount = 0;
        int[] resultGradeCounts = new int[6];

        for (Map.Entry<String, SubjectStats> entry : subjectStats.entrySet()) {
            SubjectStats stats = entry.getValue();
            if (stats.totalWeight() <= 0) {
                continue;
            }

            double average = stats.weightedSum() / stats.totalWeight();
            int finalGrade = toFinalGrade(average);
            String subjectName = subjectDisplayNames.getOrDefault(entry.getKey(), entry.getKey());

            subjectSummaryTableModel.addRow(new Object[]{
                    subjectName,
                    formatTwoDecimals(average),
                    finalGrade,
                    toGradeLabel(finalGrade)
            });

            overallFinalGradeSum += finalGrade;
            overallFinalGradeCount++;
            resultGradeCounts[finalGrade]++;
        }

        List<String> extraBuckets = new ArrayList<>();
        for (String bucket : presentGradeBuckets.keySet()) {
            if (!bucket.matches("[1-5]")) {
                extraBuckets.add(bucket);
            }
        }
        extraBuckets.sort(String.CASE_INSENSITIVE_ORDER);

        List<String> orderedBuckets = new ArrayList<>(List.of("1", "2", "3", "4", "5"));
        orderedBuckets.addAll(extraBuckets);

        List<Object> subjectCountColumns = new ArrayList<>();
        subjectCountColumns.add("Předmět");
        subjectCountColumns.addAll(orderedBuckets);
        subjectCountColumns.add("Celkem");
        subjectGradeCountsTableModel.setColumnIdentifiers(subjectCountColumns.toArray());

        Map<String, Integer> totalByBucket = new LinkedHashMap<>();
        int totalMarksCount = 0;

        for (Map.Entry<String, String> subjectEntry : subjectDisplayNames.entrySet()) {
            Map<String, Integer> counts = subjectGradeCounts.getOrDefault(subjectEntry.getKey(), Map.of());
            Object[] row = new Object[subjectCountColumns.size()];
            row[0] = subjectEntry.getValue();
            for (int i = 0; i < orderedBuckets.size(); i++) {
                int count = counts.getOrDefault(orderedBuckets.get(i), 0);
                totalByBucket.merge(orderedBuckets.get(i), count, Integer::sum);
                row[i + 1] = formatCountCell(count);
            }
            int subjectTotal = subjectTotals.getOrDefault(subjectEntry.getKey(), 0);
            totalMarksCount += subjectTotal;
            row[row.length - 1] = formatCountCell(subjectTotal);
            subjectGradeCountsTableModel.addRow(row);
        }

        Object[] totalsRow = new Object[subjectCountColumns.size()];
        totalsRow[0] = formatCountCell(subjectDisplayNames.size());
        for (int i = 0; i < orderedBuckets.size(); i++) {
            totalsRow[i + 1] = formatCountCell(totalByBucket.getOrDefault(orderedBuckets.get(i), 0));
        }
        totalsRow[totalsRow.length - 1] = formatCountCell(totalMarksCount);
        subjectGradeCountsTableModel.addRow(totalsRow);

        resultGradeDistributionTableModel.setColumnIdentifiers(new Object[]{"Metrika", "1", "2", "3", "4", "5"});
        resultGradeDistributionTableModel.addRow(new Object[]{
                "Počet předmětů",
                formatCountCell(resultGradeCounts[1]),
                formatCountCell(resultGradeCounts[2]),
                formatCountCell(resultGradeCounts[3]),
                formatCountCell(resultGradeCounts[4]),
                formatCountCell(resultGradeCounts[5])
        });

        if (overallFinalGradeCount > 0) {
            double overallAverage = overallFinalGradeSum / overallFinalGradeCount;
            int overallFinalGrade = toFinalGrade(overallAverage);
            overallAverageLabel.setText(
                    "Celkový průměr výsledných známek: " + formatTwoDecimals(overallAverage)
                            + " (" + overallFinalGrade + " - " + toGradeLabel(overallFinalGrade) + ")"
            );
        } else {
            overallAverageLabel.setText("Celkový průměr výsledných známek: -");
        }

        configureTables();
        refreshFilterOptions();
    }

    private void applyColumnFilters() {
        if (updatingFilterOptions) {
            return;
        }

        boolean hasFilter = false;
        for (int column = 0; column < filterCombos.length; column++) {
            String filterText = getFilterText(column);
            if (!filterText.isEmpty()) {
                hasFilter = true;
                break;
            }
        }

        if (!hasFilter) {
            gradesSorter.setRowFilter(null);
            return;
        }

        gradesSorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                for (int column = 0; column < filterCombos.length; column++) {
                    String filterText = getFilterText(column);
                    if (filterText.isEmpty()) {
                        continue;
                    }
                    Object value = entry.getValue(column);
                    String cellValue = value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
                    if (!cellValue.contains(filterText)) {
                        return false;
                    }
                }
                return true;
            }
        });
    }

    private void refreshFilterOptions() {
        updatingFilterOptions = true;
        try {
            for (int column = 0; column < filterCombos.length; column++) {
                JComboBox<String> combo = filterCombos[column];
                if (combo == null) {
                    continue;
                }

                String currentText = getFilterTextRaw(column);
                Map<String, String> uniqueValues = new LinkedHashMap<>();
                for (int row = 0; row < gradesTableModel.getRowCount(); row++) {
                    Object value = gradesTableModel.getValueAt(row, column);
                    String text = value == null ? "" : value.toString().trim();
                    if (!text.isEmpty()) {
                        uniqueValues.putIfAbsent(text.toLowerCase(Locale.ROOT), text);
                    }
                }

                DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                model.addElement("");
                for (String value : uniqueValues.values()) {
                    model.addElement(value);
                }
                combo.setModel(model);
                combo.setEditable(true);
                combo.getEditor().setItem(currentText);
            }
        } finally {
            updatingFilterOptions = false;
        }
    }

    private String getFilterText(int column) {
        return getFilterTextRaw(column).toLowerCase(Locale.ROOT);
    }

    private String getFilterTextRaw(int column) {
        JComboBox<String> combo = filterCombos[column];
        if (combo == null) {
            return "";
        }
        Object item = combo.getEditor().getItem();
        return item == null ? "" : item.toString().trim();
    }

    private void clearFilters() {
        updatingFilterOptions = true;
        try {
            for (JComboBox<String> combo : filterCombos) {
                if (combo != null) {
                    combo.setSelectedItem("");
                    combo.getEditor().setItem("");
                }
            }
        } finally {
            updatingFilterOptions = false;
        }
        gradesSorter.setRowFilter(null);
    }

    private void setLoadingState(boolean loading) {
        loginButton.setEnabled(!loading);
        refreshButton.setEnabled(!loading);
        logoutButton.setEnabled(!loading);
        themeToggle.setEnabled(!loading);
        progressBar.setVisible(loading);
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
        UIManager.put("Table.showVerticalLines", true);
        UIManager.put("Table.rowHeight", 32);
        UIManager.put("TitlePane.unifiedBackground", true);

        if (themeMode == ThemeMode.DARK) {
            UIManager.put("Table.alternateRowColor", new Color(43, 48, 58));
        } else {
            UIManager.put("Table.alternateRowColor", new Color(244, 247, 252));
        }
    }

    private static String normalizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        return subject.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static double parseWeight(String weightText) {
        if (weightText == null || weightText.isBlank()) {
            return 1.0;
        }
        try {
            double weight = Double.parseDouble(weightText.replace(',', '.').trim());
            return weight > 0 ? weight : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    private static Double parseMarkValue(String markText) {
        if (markText == null || markText.isBlank()) {
            return null;
        }

        String normalized = markText.trim();
        String[] tokens = normalized.split("[/,;]");
        double sum = 0.0;
        int count = 0;

        for (String token : tokens) {
            Double parsed = parseSingleMarkToken(token.trim());
            if (parsed != null) {
                sum += parsed;
                count++;
            }
        }

        if (count == 0) {
            return null;
        }
        return sum / count;
    }

    private static Double parseSingleMarkToken(String token) {
        if (token.isBlank()) {
            return null;
        }

        Matcher rangeMatcher = RANGE_MARK_PATTERN.matcher(token);
        if (rangeMatcher.matches()) {
            int first = Integer.parseInt(rangeMatcher.group(1));
            int second = Integer.parseInt(rangeMatcher.group(2));
            return (first + second) / 2.0;
        }

        Matcher singleMatcher = SINGLE_MARK_PATTERN.matcher(token);
        if (!singleMatcher.matches()) {
            return null;
        }

        double value = Double.parseDouble(singleMatcher.group(1));
        String suffix = singleMatcher.group(2);
        if ("+".equals(suffix)) {
            value -= 0.25;
        } else if ("-".equals(suffix)) {
            value += 0.25;
        }
        return Math.max(1.0, Math.min(5.0, value));
    }

    private static String resolveGradeBucket(String markText, Double parsedMarkValue) {
        if (markText == null || markText.isBlank()) {
            return "";
        }

        Matcher leadingNumericMatcher = LEADING_NUMERIC_MARK_PATTERN.matcher(markText);
        if (leadingNumericMatcher.find()) {
            return leadingNumericMatcher.group(1);
        }

        if (parsedMarkValue != null) {
            return Integer.toString(toFinalGrade(parsedMarkValue));
        }

        return markText.trim().toUpperCase(Locale.ROOT);
    }

    private static String formatWeight(double weight) {
        if (Math.abs(weight - Math.rint(weight)) < 0.0001) {
            return Long.toString(Math.round(weight));
        }
        return formatTwoDecimals(weight);
    }

    private static String formatTwoDecimals(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static Object formatCountCell(int count) {
        return count == 0 ? "" : count;
    }

    private static int toFinalGrade(double average) {
        int rounded = (int) Math.round(average);
        return Math.max(1, Math.min(5, rounded));
    }

    private static String toGradeLabel(int grade) {
        return switch (grade) {
            case 1 -> "výborný";
            case 2 -> "chvalitebný";
            case 3 -> "dobrý";
            case 4 -> "dostatečný";
            default -> "nedostatečný";
        };
    }

    private DefaultTableCellRenderer createSubjectColorRenderer(JTable table, DefaultTableModel model) {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table,
                    Object value,
                    boolean isSelected,
                    boolean hasFocus,
                    int row,
                    int column
            ) {
                Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                int modelRow = table.convertRowIndexToModel(row);
                boolean isSubjectTotalsRow = model == subjectGradeCountsTableModel
                        && model.getRowCount() > 0
                        && modelRow == model.getRowCount() - 1;

                if (isSubjectTotalsRow) {
                    Color totalsBase = currentTheme == ThemeMode.DARK ? new Color(96, 102, 114) : new Color(220, 225, 234);
                    Color stripedTotals = applyRowStripe(totalsBase, row);
                    component.setBackground(applySelectionCrossHighlight(table, stripedTotals, row, column, isSelected));
                    component.setForeground(UIManager.getColor("Table.foreground"));
                    component.setFont(component.getFont().deriveFont(Font.BOLD));
                    return component;
                }

                Object subjectValue = model.getValueAt(modelRow, 0);
                String subject = subjectValue == null ? "" : subjectValue.toString();
                Color baseColor = getColorForSubject(subject);
                Color stripedColor = applyRowStripe(baseColor, row);
                component.setBackground(applySelectionCrossHighlight(table, stripedColor, row, column, isSelected));
                component.setForeground(UIManager.getColor("Table.foreground"));
                component.setFont(table.getFont());
                return component;
            }
        };
    }

    private DefaultTableCellRenderer createStripedRenderer(JTable table) {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table,
                    Object value,
                    boolean isSelected,
                    boolean hasFocus,
                    int row,
                    int column
            ) {
                Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                Color base = UIManager.getColor("Table.background");
                Color stripedColor = applyRowStripe(base, row);
                component.setBackground(applySelectionCrossHighlight(table, stripedColor, row, column, isSelected));
                component.setForeground(UIManager.getColor("Table.foreground"));
                return component;
            }
        };
    }


    private Color getColorForSubject(String subject) {
        String key = normalizeSubject(subject);
        if (key.isBlank()) {
            return UIManager.getColor("Table.background");
        }
        Color cached = subjectColorCache.get(key);
        if (cached != null) {
            return cached;
        }

        int hash = Math.abs(key.hashCode());
        float hue = (hash % 360) / 360f;
        float saturation = currentTheme == ThemeMode.DARK ? 0.45f : 0.28f;
        float brightness = currentTheme == ThemeMode.DARK ? 0.35f : 0.98f;
        Color color = Color.getHSBColor(hue, saturation, brightness);
        subjectColorCache.put(key, color);
        return color;
    }

    private Color applyRowStripe(Color baseColor, int row) {
        if (row % 2 == 0) {
            return baseColor;
        }
        return currentTheme == ThemeMode.DARK ? adjustBrightness(baseColor, 1.12) : adjustBrightness(baseColor, 0.92);
    }

    private Color applySelectionCrossHighlight(JTable table, Color baseColor, int row, int column, boolean isSelectedCell) {
        int selectedRow = table.getSelectedRow();
        int selectedColumn = table.getSelectedColumn();

        if (isSelectedCell) {
            return currentTheme == ThemeMode.DARK ? adjustBrightness(baseColor, 1.45) : adjustBrightness(baseColor, 0.65);
        }

        if (selectedRow >= 0 && selectedColumn >= 0 && (row == selectedRow || column == selectedColumn)) {
            return currentTheme == ThemeMode.DARK ? adjustBrightness(baseColor, 1.26) : adjustBrightness(baseColor, 0.80);
        }

        return baseColor;
    }

    private static Color adjustBrightness(Color color, double factor) {
        int red = clampColor((int) Math.round(color.getRed() * factor));
        int green = clampColor((int) Math.round(color.getGreen() * factor));
        int blue = clampColor((int) Math.round(color.getBlue() * factor));
        return new Color(red, green, blue);
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Chyba", JOptionPane.ERROR_MESSAGE);
    }

    private static String getEnvOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ComputedGradeRow(GradeEntry grade, Double markValue, double weightValue) {
    }

    private static final class SubjectStats {
        private double weightedSum;
        private double totalWeight;

        void add(double markValue, double weightValue) {
            weightedSum += markValue * weightValue;
            totalWeight += weightValue;
        }

        double weightedSum() {
            return weightedSum;
        }

        double totalWeight() {
            return totalWeight;
        }
    }

    private final class FilterChangeListener implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent e) {
            applyColumnFilters();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            applyColumnFilters();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            applyColumnFilters();
        }
    }
}
