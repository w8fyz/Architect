package sh.fyz.architect.migration;

import sh.fyz.architect.Architect;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public class MigrationToolGUI {

    private static final Logger LOG = Logger.getLogger(MigrationToolGUI.class.getName());
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    private final MigrationManager manager;
    private JFrame frame;

    private MigrationToolGUI(MigrationManager manager) {
        this.manager = manager;
    }

    public static void open(Architect architect, Path migrationDirectory) {
        MigrationManager mgr = new MigrationManager(architect, migrationDirectory);
        MigrationToolGUI gui = new MigrationToolGUI(mgr);
        SwingUtilities.invokeLater(gui::buildAndShow);
    }

    private void buildAndShow() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        frame = new JFrame("Architect Migration Tool");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setSize(960, 680);
        frame.setMinimumSize(new Dimension(720, 500));
        frame.setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Create", buildCreatePanel());
        tabs.addTab("Execute", buildExecutePanel());
        tabs.addTab("Clear", buildClearPanel());
        tabs.addTab("View", buildViewPanel());

        frame.setContentPane(tabs);
        frame.setVisible(true);
    }

    // =========================================================================
    // Create tab
    // =========================================================================

    private JPanel buildCreatePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel topRow = new JPanel(new BorderLayout(8, 0));
        JLabel nameLabel = new JLabel("Migration name:");
        JTextField nameField = new JTextField(20);
        JButton previewBtn = new JButton("Generate Preview");
        JButton saveBtn = new JButton("Save Migration");
        saveBtn.setEnabled(false);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnPanel.add(previewBtn);
        btnPanel.add(saveBtn);

        topRow.add(nameLabel, BorderLayout.WEST);
        topRow.add(nameField, BorderLayout.CENTER);
        topRow.add(btnPanel, BorderLayout.EAST);

        JTextArea sqlArea = new JTextArea();
        sqlArea.setFont(MONO);
        sqlArea.setEditable(false);
        sqlArea.setTabSize(4);
        JScrollPane scroll = new JScrollPane(sqlArea);

        JLabel statusLabel = new JLabel(" ");

        previewBtn.addActionListener(e -> {
            previewBtn.setEnabled(false);
            statusLabel.setText("Generating schema...");
            sqlArea.setText("");

            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    return manager.generateSchema();
                }

                @Override
                protected void done() {
                    try {
                        String ddl = get();
                        sqlArea.setText(ddl);
                        sqlArea.setCaretPosition(0);
                        saveBtn.setEnabled(!nameField.getText().isBlank());
                        statusLabel.setText("Preview generated (" + ddl.length() + " chars)");
                    } catch (Exception ex) {
                        sqlArea.setText("ERROR: " + ex.getCause().getMessage());
                        statusLabel.setText("Generation failed");
                    }
                    previewBtn.setEnabled(true);
                }
            }.execute();
        });

        saveBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isBlank()) {
                statusLabel.setText("Please enter a migration name");
                return;
            }
            saveBtn.setEnabled(false);
            statusLabel.setText("Saving...");

            new SwingWorker<Path, Void>() {
                @Override
                protected Path doInBackground() {
                    return manager.createMigration(name);
                }

                @Override
                protected void done() {
                    try {
                        Path path = get();
                        statusLabel.setText("Saved: " + path.toAbsolutePath());
                    } catch (Exception ex) {
                        statusLabel.setText("Save failed: " + ex.getCause().getMessage());
                    }
                    saveBtn.setEnabled(true);
                }
            }.execute();
        });

        nameField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                saveBtn.setEnabled(!nameField.getText().isBlank() && !sqlArea.getText().isBlank());
            }
        });

        panel.add(topRow, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    // =========================================================================
    // Execute tab
    // =========================================================================

    private JPanel buildExecutePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        DefaultListModel<String> listModel = new DefaultListModel<>();
        JList<String> fileList = new JList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScroll = new JScrollPane(fileList);
        listScroll.setPreferredSize(new Dimension(220, 0));

        JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
        JButton refreshBtn = new JButton("Refresh");
        leftPanel.add(refreshBtn, BorderLayout.NORTH);
        leftPanel.add(listScroll, BorderLayout.CENTER);

        JTextArea sqlPreview = new JTextArea();
        sqlPreview.setFont(MONO);
        sqlPreview.setEditable(false);
        JScrollPane sqlScroll = new JScrollPane(sqlPreview);

        JPanel bottomPanel = new JPanel(new BorderLayout(8, 0));
        JButton executeBtn = new JButton("Execute Migration");
        executeBtn.setEnabled(false);
        JLabel statusLabel = new JLabel(" ");
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);

        JPanel bottomRight = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomRight.add(progressBar);
        bottomRight.add(executeBtn);
        bottomPanel.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.add(bottomRight, BorderLayout.EAST);

        Runnable refreshAction = () -> {
            listModel.clear();
            new SwingWorker<List<String>, Void>() {
                @Override
                protected List<String> doInBackground() {
                    return manager.listMigrations();
                }

                @Override
                protected void done() {
                    try {
                        for (String f : get()) listModel.addElement(f);
                        statusLabel.setText(listModel.size() + " migration(s) found");
                    } catch (Exception ex) {
                        statusLabel.setText("Failed to list migrations");
                    }
                }
            }.execute();
        };

        refreshBtn.addActionListener(e -> refreshAction.run());

        fileList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String selected = fileList.getSelectedValue();
            if (selected == null) {
                sqlPreview.setText("");
                executeBtn.setEnabled(false);
                return;
            }
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    return manager.readMigrationContent(selected);
                }

                @Override
                protected void done() {
                    try {
                        sqlPreview.setText(get());
                        sqlPreview.setCaretPosition(0);
                        executeBtn.setEnabled(true);
                    } catch (Exception ex) {
                        sqlPreview.setText("ERROR: " + ex.getCause().getMessage());
                        executeBtn.setEnabled(false);
                    }
                }
            }.execute();
        });

        executeBtn.addActionListener(e -> {
            String selected = fileList.getSelectedValue();
            if (selected == null) return;

            int confirm = JOptionPane.showConfirmDialog(frame,
                    "Execute migration \"" + selected + "\"?\nThis will run SQL statements against the database.",
                    "Confirm Execution", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;

            executeBtn.setEnabled(false);
            progressBar.setIndeterminate(true);
            progressBar.setVisible(true);
            statusLabel.setText("Executing...");

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    manager.executeMigration(selected);
                    return null;
                }

                @Override
                protected void done() {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisible(false);
                    try {
                        get();
                        statusLabel.setText("Migration \"" + selected + "\" executed successfully");
                    } catch (Exception ex) {
                        statusLabel.setText("Execution failed: " + ex.getCause().getMessage());
                        JOptionPane.showMessageDialog(frame,
                                "Migration failed:\n" + ex.getCause().getMessage(),
                                "Execution Error", JOptionPane.ERROR_MESSAGE);
                    }
                    executeBtn.setEnabled(true);
                }
            }.execute();
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, sqlScroll);
        split.setDividerLocation(220);

        panel.add(split, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        SwingUtilities.invokeLater(refreshAction::run);

        return panel;
    }

    // =========================================================================
    // Clear tab
    // =========================================================================

    private JPanel buildClearPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

        JLabel warningIcon = new JLabel("WARNING", UIManager.getIcon("OptionPane.warningIcon"), SwingConstants.CENTER);
        warningIcon.setFont(warningIcon.getFont().deriveFont(Font.BOLD, 18f));
        warningIcon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel warningText = new JLabel("<html><center>This will <b>permanently delete all tables and data</b> in the database.<br>"
                + "This operation cannot be undone.</center></html>");
        warningText.setAlignmentX(Component.CENTER_ALIGNMENT);
        warningText.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel instructionLabel = new JLabel("Type " + MigrationManager.CLEAR_CONFIRMATION + " to confirm:");
        instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JTextField confirmField = new JTextField(20);
        confirmField.setMaximumSize(new Dimension(320, 30));
        confirmField.setAlignmentX(Component.CENTER_ALIGNMENT);
        confirmField.setHorizontalAlignment(JTextField.CENTER);

        JButton clearBtn = new JButton("Clear Database");
        clearBtn.setEnabled(false);
        clearBtn.setAlignmentX(Component.CENTER_ALIGNMENT);

        JTextArea logArea = new JTextArea(8, 60);
        logArea.setFont(MONO);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);

        confirmField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                clearBtn.setEnabled(MigrationManager.CLEAR_CONFIRMATION.equals(confirmField.getText()));
            }
        });

        clearBtn.addActionListener(e -> {
            clearBtn.setEnabled(false);
            confirmField.setEnabled(false);
            logArea.setText("Clearing database...\n");

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    manager.clearDatabase(MigrationManager.CLEAR_CONFIRMATION);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        logArea.append("Database cleared successfully.\n");
                    } catch (Exception ex) {
                        logArea.append("FAILED: " + ex.getCause().getMessage() + "\n");
                    }
                    confirmField.setText("");
                    confirmField.setEnabled(true);
                }
            }.execute();
        });

        centerPanel.add(Box.createVerticalGlue());
        centerPanel.add(warningIcon);
        centerPanel.add(Box.createVerticalStrut(16));
        centerPanel.add(warningText);
        centerPanel.add(Box.createVerticalStrut(24));
        centerPanel.add(instructionLabel);
        centerPanel.add(Box.createVerticalStrut(8));
        centerPanel.add(confirmField);
        centerPanel.add(Box.createVerticalStrut(12));
        centerPanel.add(clearBtn);
        centerPanel.add(Box.createVerticalStrut(16));
        centerPanel.add(Box.createVerticalGlue());

        panel.add(centerPanel, BorderLayout.NORTH);
        panel.add(logScroll, BorderLayout.CENTER);
        return panel;
    }

    // =========================================================================
    // View tab
    // =========================================================================

    private JPanel buildViewPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JComboBox<String> tableCombo = new JComboBox<>();
        tableCombo.setPreferredSize(new Dimension(250, 26));
        JButton refreshBtn = new JButton("Refresh Tables");
        JLabel infoLabel = new JLabel(" ");
        topBar.add(new JLabel("Table:"));
        topBar.add(tableCombo);
        topBar.add(refreshBtn);
        topBar.add(infoLabel);

        JTabbedPane subTabs = new JTabbedPane();

        DefaultTableModel schemaModel = new DefaultTableModel(
                new String[]{"Column", "Type", "Size", "Nullable", "PK", "Default"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable schemaTable = new JTable(schemaModel);
        subTabs.addTab("Schema", new JScrollPane(schemaTable));

        DefaultTableModel dataModel = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable dataTable = new JTable(dataModel);
        dataTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        JPanel dataPanel = new JPanel(new BorderLayout());
        dataPanel.add(new JScrollPane(dataTable), BorderLayout.CENTER);

        JPanel pagingPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JButton prevBtn = new JButton("< Previous");
        JButton nextBtn = new JButton("Next >");
        JLabel pageLabel = new JLabel("Page 1");
        JLabel rowCountLabel = new JLabel("");
        pagingPanel.add(prevBtn);
        pagingPanel.add(pageLabel);
        pagingPanel.add(nextBtn);
        pagingPanel.add(Box.createHorizontalStrut(20));
        pagingPanel.add(rowCountLabel);
        dataPanel.add(pagingPanel, BorderLayout.SOUTH);
        subTabs.addTab("Data", dataPanel);

        final int PAGE_SIZE = 50;
        final int[] currentPage = {0};

        Runnable loadTables = () -> {
            tableCombo.removeAllItems();
            new SwingWorker<List<DatabaseInspector.TableInfo>, Void>() {
                @Override
                protected List<DatabaseInspector.TableInfo> doInBackground() {
                    return manager.listTables();
                }

                @Override
                protected void done() {
                    try {
                        List<DatabaseInspector.TableInfo> tables = get();
                        for (var t : tables) {
                            tableCombo.addItem(t.name());
                        }
                        infoLabel.setText(tables.size() + " table(s)");
                    } catch (Exception ex) {
                        infoLabel.setText("Failed to load tables");
                    }
                }
            }.execute();
        };

        Runnable loadSchema = () -> {
            String tableName = (String) tableCombo.getSelectedItem();
            if (tableName == null) return;
            schemaModel.setRowCount(0);

            new SwingWorker<DatabaseInspector.TableSchema, Void>() {
                @Override
                protected DatabaseInspector.TableSchema doInBackground() {
                    return manager.getTableSchema(tableName);
                }

                @Override
                protected void done() {
                    try {
                        var schema = get();
                        for (var col : schema.columns()) {
                            schemaModel.addRow(new Object[]{
                                    col.name(),
                                    col.type(),
                                    col.size(),
                                    col.nullable() ? "YES" : "NO",
                                    col.primaryKey() ? "PK" : "",
                                    col.defaultValue() != null ? col.defaultValue() : ""
                            });
                        }
                    } catch (Exception ex) {
                        infoLabel.setText("Failed to load schema");
                    }
                }
            }.execute();
        };

        Runnable loadData = () -> {
            String tableName = (String) tableCombo.getSelectedItem();
            if (tableName == null) return;
            dataModel.setRowCount(0);
            dataModel.setColumnCount(0);

            new SwingWorker<DatabaseInspector.TableData, Void>() {
                @Override
                protected DatabaseInspector.TableData doInBackground() {
                    return manager.getTableData(tableName, currentPage[0], PAGE_SIZE);
                }

                @Override
                protected void done() {
                    try {
                        var data = get();
                        for (String col : data.columnNames()) {
                            dataModel.addColumn(col);
                        }
                        for (var row : data.rows()) {
                            dataModel.addRow(row.toArray());
                        }
                        long totalPages = Math.max(1, (data.totalRows() + PAGE_SIZE - 1) / PAGE_SIZE);
                        pageLabel.setText("Page " + (currentPage[0] + 1) + " / " + totalPages);
                        rowCountLabel.setText(data.totalRows() + " row(s) total");
                        prevBtn.setEnabled(currentPage[0] > 0);
                        nextBtn.setEnabled((currentPage[0] + 1) < totalPages);
                    } catch (Exception ex) {
                        infoLabel.setText("Failed to load data");
                    }
                }
            }.execute();
        };

        refreshBtn.addActionListener(e -> loadTables.run());

        tableCombo.addActionListener(e -> {
            if (tableCombo.getSelectedItem() != null) {
                currentPage[0] = 0;
                loadSchema.run();
                loadData.run();
            }
        });

        prevBtn.addActionListener(e -> {
            if (currentPage[0] > 0) {
                currentPage[0]--;
                loadData.run();
            }
        });

        nextBtn.addActionListener(e -> {
            currentPage[0]++;
            loadData.run();
        });

        panel.add(topBar, BorderLayout.NORTH);
        panel.add(subTabs, BorderLayout.CENTER);

        SwingUtilities.invokeLater(loadTables::run);

        return panel;
    }
}
