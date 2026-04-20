package cz.nero.bakapi.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import cz.nero.bakapi.model.GradeEntry;
import cz.nero.bakapi.model.UserProfile;
import cz.nero.bakapi.service.BakalariClient;
import cz.nero.bakapi.service.ConsultationHoursService;
import cz.nero.bakapi.service.ProfileStore;
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
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

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
    private static final int[] GRADE_FILTER_COLUMNS = {0, 2, 3};
    private static final int[] PLAN_FILTER_COLUMNS = {0, 2, 5, 6};
    private static final List<String> PLAN_STATUS_OPTIONS = List.of(
            "Nerealizovatelné",
            "Plánováno",
            "Neplánováno",
            "K zvážení"
    );

    private ThemeMode currentTheme;
    private boolean applyingTheme;
    private boolean updatingProfileCombo;

    private final JTextField baseUrlField = new JTextField(getEnvOrDefault("BAKA_BASE_URL", "https://bakalari.infis.cz"), 28);
    private final JComboBox<String> usernameCombo = new JComboBox<>();
    private final JPasswordField passwordField = new JPasswordField(getEnvOrDefault("BAKA_PASS", ""), 20);
    private final JButton loginButton = new JButton("Přihlásit a načíst známky");
    private final JButton refreshButton = new JButton("Obnovit");
    private final JButton importConsultationsButton = new JButton("Nahrát konzultace (PDF)");
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

    private final DefaultTableModel planTableModel = new DefaultTableModel(
            new Object[]{"Předmět", "Učitel", "Známka", "Téma", "Datum", "Konzultace", "Plán doplnění"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 6;
        }
    };
    private final JTable planTable = new JTable(planTableModel);
    private final TableRowSorter<DefaultTableModel> planSorter = new TableRowSorter<>(planTableModel);
    @SuppressWarnings("unchecked")
    private final JComboBox<String>[] planFilterCombos = new JComboBox[planTableModel.getColumnCount()];
    private final List<Integer> planTableGradeIndexes = new ArrayList<>();
    private boolean updatingPlanTable;
    private boolean updatingPlanFilterOptions;

    private final Map<String, Color> subjectColorCache = new LinkedHashMap<>();

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);
    private final BakalariClient client = new BakalariClient();
    private final ConsultationHoursService consultationHoursService = new ConsultationHoursService();
    private final ProfileStore profileStore = new ProfileStore();
    private final Map<String, UserProfile> profilesByUsername = new LinkedHashMap<>();
    private List<ConsultationHoursService.ConsultationHours> consultationHours = List.of();
    private List<GradeEntry> currentGrades = List.of();
    private UserProfile currentUserProfile;
    private char[] currentSessionPassword;

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

        usernameCombo.setEditable(true);
        getUsernameEditorField().setText(getEnvOrDefault("BAKA_USER", ""));

        loginButton.addActionListener(event -> loadGrades());
        refreshButton.addActionListener(event -> loadGrades());
        importConsultationsButton.addActionListener(event -> importConsultationHoursPdf());
        logoutButton.addActionListener(event -> logout());
        baseUrlField.addActionListener(event -> loadGrades());
        getUsernameEditorField().addActionListener(event -> loadGrades());
        usernameCombo.addActionListener(event -> onUsernameSelectionChanged());
        passwordField.addActionListener(event -> loadGrades());
        themeToggle.addActionListener(event -> {
            if (!applyingTheme) {
                switchTheme(themeToggle.isSelected() ? ThemeMode.DARK : ThemeMode.LIGHT);
            }
        });
        planTableModel.addTableModelListener(this::onPlanTableEdited);

        configureComponentStyles();
        configureTables();
        switchTheme(currentTheme);
        reloadStoredProfiles();
        showLoginView();
    }

    private JPanel createHeaderPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        JLabel titleLabel = new JLabel("BAKAPI");
        titleLabel.putClientProperty("FlatLaf.styleClass", "h1");

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightPanel.setOpaque(false);
        rightPanel.add(refreshButton);
        rightPanel.add(importConsultationsButton);
        rightPanel.add(logoutButton);
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
        panel.add(usernameCombo, gbc);

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

        JPanel planTab = new JPanel(new BorderLayout(0, 8));
        planTab.add(createPlanFilterPanel(), BorderLayout.NORTH);
        planTab.add(new JScrollPane(planTable), BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Známky", gradesTab);
        tabs.addTab("Průměry předmětů", summaryTab);
        tabs.addTab("Statistika známek", subjectCountsTab);
        tabs.addTab("Známky k doplnění", planTab);
        panel.add(tabs, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createFilterPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 4, 8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Filtry sloupců"));

        for (int column : GRADE_FILTER_COLUMNS) {
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

    private JPanel createPlanFilterPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 4, 8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Filtry sloupců"));

        for (int column : PLAN_FILTER_COLUMNS) {
            JPanel oneFilterPanel = new JPanel(new BorderLayout(0, 4));
            oneFilterPanel.setOpaque(false);

            JLabel columnLabel = new JLabel(planTableModel.getColumnName(column));
            JComboBox<String> combo = new JComboBox<>();
            combo.setEditable(true);
            combo.setModel(new DefaultComboBoxModel<>(new String[]{""}));
            combo.addActionListener(event -> applyPlanFilters());
            JTextField editor = (JTextField) combo.getEditor().getEditorComponent();
            editor.putClientProperty("JTextField.placeholderText", "Vyber nebo napiš...");
            editor.getDocument().addDocumentListener(new PlanFilterChangeListener());
            planFilterCombos[column] = combo;

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

        planTable.setFillsViewportHeight(true);
        planTable.setRowSorter(planSorter);
        planTable.setAutoCreateRowSorter(false);
        planTable.setRowHeight(30);
        planTable.getTableHeader().setReorderingAllowed(false);
        applyGridStyle(planTable, gridColor);
        DefaultTableCellRenderer planRenderer = createSubjectColorRenderer(planTable, planTableModel);
        for (int column = 0; column < planTable.getColumnModel().getColumnCount(); column++) {
            planTable.getColumnModel().getColumn(column).setCellRenderer(planRenderer);
        }

        JComboBox<String> planEditorCombo = new JComboBox<>();
        planEditorCombo.addItem("");
        for (String option : PLAN_STATUS_OPTIONS) {
            planEditorCombo.addItem(option);
        }
        planTable.getColumnModel().getColumn(6).setCellEditor(new javax.swing.DefaultCellEditor(planEditorCombo));
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
        usernameCombo.putClientProperty("JComponent.roundRect", true);
        getUsernameEditorField().putClientProperty("JTextField.placeholderText", "uživatelské jméno");
        passwordField.putClientProperty("JTextField.placeholderText", "heslo");

        loginButton.putClientProperty("JButton.buttonType", "roundRect");
        refreshButton.putClientProperty("JButton.buttonType", "roundRect");
        importConsultationsButton.putClientProperty("JButton.buttonType", "roundRect");
        logoutButton.putClientProperty("JButton.buttonType", "roundRect");
        themeToggle.putClientProperty("JButton.buttonType", "roundRect");

        for (JComboBox<String> combo : filterCombos) {
            if (combo != null) {
                combo.putClientProperty("JComponent.roundRect", true);
            }
        }
        for (JComboBox<String> combo : planFilterCombos) {
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

    private void reloadStoredProfiles() {
        try {
            List<UserProfile> profiles = profileStore.loadProfiles();
            String currentInput = getEnteredUsername();

            updatingProfileCombo = true;
            try {
                profilesByUsername.clear();
                DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                for (UserProfile profile : profiles) {
                    String key = normalizeUsernameKey(profile.username());
                    profilesByUsername.put(key, profile);
                    if (model.getIndexOf(profile.username()) < 0) {
                        model.addElement(profile.username());
                    }
                }
                usernameCombo.setModel(model);
                usernameCombo.setEditable(true);
                usernameCombo.getEditor().setItem(currentInput);
            } finally {
                updatingProfileCombo = false;
            }

            onUsernameSelectionChanged();
        } catch (IOException e) {
            statusLabel.setText("Nepodařilo se načíst uložené profily.");
        }
    }

    private void onUsernameSelectionChanged() {
        if (updatingProfileCombo) {
            return;
        }

        String username = getEnteredUsername();
        UserProfile profile = profilesByUsername.get(normalizeUsernameKey(username));
        if (profile != null) {
            baseUrlField.setText(profile.baseUrl());
        }
    }

    private JTextField getUsernameEditorField() {
        return (JTextField) usernameCombo.getEditor().getEditorComponent();
    }

    private String getEnteredUsername() {
        Object item = usernameCombo.getEditor().getItem();
        return item == null ? "" : item.toString().trim();
    }

    private static String normalizeUsernameKey(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private void onSuccessfulLogin(LoadGradesResult result) {
        clearSessionCredentials();
        currentSessionPassword = Arrays.copyOf(result.sessionPassword(), result.sessionPassword().length);
        currentUserProfile = result.profile();
        consultationHours = new ArrayList<>(result.consultationHours());
        currentGrades = new ArrayList<>(result.grades());
        Arrays.fill(result.sessionPassword(), '\0');
    }

    private void clearSessionCredentials() {
        if (currentSessionPassword != null) {
            Arrays.fill(currentSessionPassword, '\0');
            currentSessionPassword = null;
        }
    }

    private void showLoginView() {
        cardLayout.show(contentPanel, LOGIN_CARD);
        refreshButton.setVisible(false);
        importConsultationsButton.setVisible(false);
        logoutButton.setVisible(false);
        getRootPane().setDefaultButton(loginButton);
    }

    private void showGradesView() {
        cardLayout.show(contentPanel, GRADES_CARD);
        refreshButton.setVisible(true);
        importConsultationsButton.setVisible(true);
        logoutButton.setVisible(true);
        getRootPane().setDefaultButton(refreshButton);
    }

    private void logout() {
        clearFilters();
        gradesTableModel.setRowCount(0);
        subjectSummaryTableModel.setRowCount(0);
        subjectGradeCountsTableModel.setRowCount(0);
        resultGradeDistributionTableModel.setRowCount(0);
        planTableModel.setRowCount(0);
        planTableGradeIndexes.clear();
        overallAverageLabel.setText("Celkový průměr výsledných známek: -");
        statusLabel.setText("Odhlášeno.");
        clearSessionCredentials();
        currentGrades = List.of();
        currentUserProfile = null;
        consultationHours = List.of();
        showLoginView();
    }

    private void importConsultationHoursPdf() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Vyber PDF s konzultačními hodinami");
        chooser.setFileFilter(new FileNameExtensionFilter("PDF dokument (*.pdf)", "pdf"));
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selectedFile = chooser.getSelectedFile();
        if (selectedFile == null) {
            return;
        }

        setLoadingState(true);
        statusLabel.setText("Načítám konzultační hodiny z PDF...");

        SwingWorker<List<ConsultationHoursService.ConsultationHours>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<ConsultationHoursService.ConsultationHours> doInBackground() throws Exception {
                return consultationHoursService.loadFromPdf(selectedFile.toPath());
            }

            @Override
            protected void done() {
                try {
                    consultationHours = get();
                    boolean saveFailed = false;
                    if (currentUserProfile != null && currentSessionPassword != null) {
                        try {
                            profileStore.saveConsultationHours(currentUserProfile, currentSessionPassword, consultationHours);
                        } catch (IOException | GeneralSecurityException e) {
                            saveFailed = true;
                            showError("Konzultace se nepodařilo uložit do lokálního profilu.");
                        }
                    }
                    if (!currentGrades.isEmpty()) {
                        rebuildTables(currentGrades);
                    }
                    int mapped = countPlanningRowsWithConsultation();
                    String baseMessage = "Načteny konzultace pro " + consultationHours.size()
                            + " vyučujících, spárováno " + mapped + " známek k doplnění.";
                    statusLabel.setText(saveFailed ? baseMessage + " Uložení do profilu selhalo." : baseMessage);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Načítání PDF bylo přerušeno.");
                    showError("Načítání PDF bylo přerušeno.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause != null ? cause.getMessage() : "Neznámá chyba při čtení PDF.";
                    statusLabel.setText("Načítání konzultací selhalo.");
                    showError(message);
                } finally {
                    setLoadingState(false);
                }
            }
        };

        worker.execute();
    }

    private void loadGrades() {
        String baseUrl = baseUrlField.getText();
        String username = getEnteredUsername();
        char[] password = passwordField.getPassword();
        char[] workerPassword = Arrays.copyOf(password, password.length);
        Arrays.fill(password, '\0');

        setLoadingState(true);
        statusLabel.setText("Přihlašuji a načítám známky...");

        SwingWorker<LoadGradesResult, Void> worker = new SwingWorker<>() {
            @Override
            protected LoadGradesResult doInBackground() throws Exception {
                try {
                    List<GradeEntry> onlineGrades = client.fetchGrades(baseUrl, username, new String(workerPassword));
                    UserProfile savedProfile = profileStore.saveOrUpdateProfile(baseUrl, username, workerPassword);
                    List<GradeEntry> mergedGrades = profileStore.saveCachedGrades(savedProfile, workerPassword, onlineGrades);
                    List<ConsultationHoursService.ConsultationHours> storedConsultations;
                    try {
                        storedConsultations = profileStore.loadConsultationHours(savedProfile, workerPassword);
                    } catch (IOException | GeneralSecurityException e) {
                        storedConsultations = List.of();
                    }
                    return new LoadGradesResult(
                            mergedGrades,
                            storedConsultations,
                            false,
                            savedProfile,
                            Arrays.copyOf(workerPassword, workerPassword.length)
                    );
                } catch (InterruptedException e) {
                    throw e;
                } catch (IOException | GeneralSecurityException onlineError) {
                    Optional<UserProfile> existingProfile = profileStore.findProfile(baseUrl, username);
                    if (existingProfile.isPresent()) {
                        List<GradeEntry> cachedGrades = profileStore.loadCachedGrades(existingProfile.get(), workerPassword);
                        List<ConsultationHoursService.ConsultationHours> storedConsultations;
                        try {
                            storedConsultations = profileStore.loadConsultationHours(existingProfile.get(), workerPassword);
                        } catch (IOException | GeneralSecurityException e) {
                            storedConsultations = List.of();
                        }
                        return new LoadGradesResult(
                                cachedGrades,
                                storedConsultations,
                                true,
                                existingProfile.get(),
                                Arrays.copyOf(workerPassword, workerPassword.length)
                        );
                    }
                    throw onlineError;
                } finally {
                    Arrays.fill(workerPassword, '\0');
                }
            }

            @Override
            protected void done() {
                try {
                    LoadGradesResult result = get();
                    onSuccessfulLogin(result);
                    rebuildTables(result.grades());
                    showGradesView();
                    reloadStoredProfiles();

                    if (result.grades().isEmpty()) {
                        statusLabel.setText("Na stránce nebyly nalezeny žádné známky.");
                    } else if (result.offline()) {
                        statusLabel.setText("Načteno " + result.grades().size() + " známek z lokálního šifrovaného úložiště.");
                    } else {
                        statusLabel.setText("Načteno " + result.grades().size() + " známek.");
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
        currentGrades = new ArrayList<>(grades);
        gradesTableModel.setRowCount(0);
        subjectSummaryTableModel.setRowCount(0);
        subjectGradeCountsTableModel.setRowCount(0);
        resultGradeDistributionTableModel.setRowCount(0);
        planTableModel.setRowCount(0);
        planTableGradeIndexes.clear();

        List<ComputedGradeRow> computedRows = new ArrayList<>();
        Map<String, Double> subjectWeightTotals = new LinkedHashMap<>();
        Map<String, SubjectStats> subjectStats = new LinkedHashMap<>();
        Map<String, String> subjectDisplayNames = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> subjectGradeCounts = new LinkedHashMap<>();
        Map<String, Integer> subjectTotals = new LinkedHashMap<>();
        Map<String, String> presentGradeBuckets = new LinkedHashMap<>();
        Map<String, String> consultationTextByTeacher = new LinkedHashMap<>();
        presentGradeBuckets.put("1", "1");
        presentGradeBuckets.put("2", "2");
        presentGradeBuckets.put("3", "3");
        presentGradeBuckets.put("4", "4");
        presentGradeBuckets.put("5", "5");

        for (int gradeIndex = 0; gradeIndex < currentGrades.size(); gradeIndex++) {
            GradeEntry grade = currentGrades.get(gradeIndex);
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

            if (isPlanningTarget(bucket)) {
                String teacherName = grade.teacher() == null ? "" : grade.teacher().trim();
                String consultationText = consultationTextByTeacher.computeIfAbsent(
                        teacherName,
                        key -> consultationHoursService.findConsultationForTeacher(key, consultationHours)
                );
                updatingPlanTable = true;
                try {
                    planTableModel.addRow(new Object[]{
                            grade.subject(),
                            grade.teacher(),
                            grade.markText(),
                            grade.caption(),
                            grade.date(),
                            consultationText,
                            safePlanStatus(grade.planStatus())
                    });
                    planTableGradeIndexes.add(gradeIndex);
                } finally {
                    updatingPlanTable = false;
                }
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
        refreshPlanFilterOptions();
    }

    private void onPlanTableEdited(TableModelEvent event) {
        if (updatingPlanTable || event.getType() != TableModelEvent.UPDATE || event.getColumn() != 6) {
            return;
        }
        int row = event.getFirstRow();
        if (row < 0 || row >= planTableGradeIndexes.size()) {
            return;
        }
        int gradeIndex = planTableGradeIndexes.get(row);
        if (gradeIndex < 0 || gradeIndex >= currentGrades.size()) {
            return;
        }

        Object rawValue = planTableModel.getValueAt(row, 6);
        String normalizedStatus = normalizePlanStatus(rawValue == null ? "" : rawValue.toString());
        GradeEntry grade = currentGrades.get(gradeIndex);
        GradeEntry updatedGrade = new GradeEntry(
                grade.sourceId(),
                grade.subject(),
                grade.teacher(),
                grade.markText(),
                grade.caption(),
                grade.note(),
                grade.weight(),
                grade.date(),
                normalizedStatus
        );
        currentGrades.set(gradeIndex, updatedGrade);

        updatingPlanTable = true;
        try {
            planTableModel.setValueAt(normalizedStatus, row, 6);
        } finally {
            updatingPlanTable = false;
        }

        tryPersistCurrentGrades();
    }

    private void tryPersistCurrentGrades() {
        if (currentUserProfile == null || currentSessionPassword == null) {
            return;
        }
        try {
            currentGrades = profileStore.saveCachedGrades(currentUserProfile, currentSessionPassword, currentGrades);
        } catch (IOException | GeneralSecurityException e) {
            statusLabel.setText("Nepodařilo se uložit lokální změnu plánu známek.");
        }
    }

    private int countPlanningRowsWithConsultation() {
        if (currentGrades.isEmpty() || consultationHours.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (GradeEntry grade : currentGrades) {
            String bucket = resolveGradeBucket(grade.markText(), parseMarkValue(grade.markText()));
            if (!isPlanningTarget(bucket)) {
                continue;
            }
            String consultation = consultationHoursService.findConsultationForTeacher(grade.teacher(), consultationHours);
            if (!consultation.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPlanningTarget(String bucket) {
        String key = bucket == null ? "" : bucket.trim().toUpperCase(Locale.ROOT);
        return key.equals("N") || key.equals("A") || key.equals("4") || key.equals("5");
    }

    private static String normalizePlanStatus(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "nerealizovatelné", "nerealizovatelne", "nelze" -> "Nerealizovatelné";
            case "plánováno", "planovano", "je v plánu", "je v planu" -> "Plánováno";
            case "neplánováno", "neplanovano", "neni v plánu", "neni v planu", "není v plánu", "není v planu" ->
                    "Neplánováno";
            case "k zvážení", "k zvažení", "k zvazeni", "mohl bych" -> "K zvážení";
            case "" -> "";
            default -> "";
        };
    }

    private static String safePlanStatus(String status) {
        return normalizePlanStatus(status);
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

    private void applyPlanFilters() {
        if (updatingPlanFilterOptions) {
            return;
        }

        boolean hasFilter = false;
        for (int column = 0; column < planFilterCombos.length; column++) {
            String filterText = getPlanFilterText(column);
            if (!filterText.isEmpty()) {
                hasFilter = true;
                break;
            }
        }

        if (!hasFilter) {
            planSorter.setRowFilter(null);
            return;
        }

        planSorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                for (int column = 0; column < planFilterCombos.length; column++) {
                    String filterText = getPlanFilterText(column);
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

    private void refreshPlanFilterOptions() {
        updatingPlanFilterOptions = true;
        try {
            for (int column = 0; column < planFilterCombos.length; column++) {
                JComboBox<String> combo = planFilterCombos[column];
                if (combo == null) {
                    continue;
                }

                String currentText = getPlanFilterTextRaw(column);
                Map<String, String> uniqueValues = new LinkedHashMap<>();
                for (int row = 0; row < planTableModel.getRowCount(); row++) {
                    Object value = planTableModel.getValueAt(row, column);
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
            updatingPlanFilterOptions = false;
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

    private String getPlanFilterText(int column) {
        return getPlanFilterTextRaw(column).toLowerCase(Locale.ROOT);
    }

    private String getPlanFilterTextRaw(int column) {
        JComboBox<String> combo = planFilterCombos[column];
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

        updatingPlanFilterOptions = true;
        try {
            for (JComboBox<String> combo : planFilterCombos) {
                if (combo != null) {
                    combo.setSelectedItem("");
                    combo.getEditor().setItem("");
                }
            }
        } finally {
            updatingPlanFilterOptions = false;
        }
        planSorter.setRowFilter(null);
    }

    private void setLoadingState(boolean loading) {
        loginButton.setEnabled(!loading);
        refreshButton.setEnabled(!loading);
        importConsultationsButton.setEnabled(!loading);
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

    private record LoadGradesResult(
            List<GradeEntry> grades,
            List<ConsultationHoursService.ConsultationHours> consultationHours,
            boolean offline,
            UserProfile profile,
            char[] sessionPassword
    ) {
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

    private final class PlanFilterChangeListener implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent e) {
            applyPlanFilters();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            applyPlanFilters();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            applyPlanFilters();
        }
    }
}
