package de.energiequant.limamf.connector.gui;

import static de.energiequant.apputils.misc.gui.SwingHelper.styleBold;
import static de.energiequant.apputils.misc.gui.SwingHelper.stylePlain;
import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.compat.config.connector.ConnectorConfiguration;
import de.energiequant.limamf.connector.Configuration;
import de.energiequant.limamf.connector.ModuleDiscovery.ConnectedModule;
import de.energiequant.limamf.connector.ModuleId;
import de.energiequant.limamf.connector.ObservableCollectionProxy;
import de.energiequant.limamf.connector.USBDevice;
import de.energiequant.limamf.connector.panels.Panel;

public class ConfigurationWindow extends JDialog {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationWindow.class);

    private final Configuration config;
    private final SerialDeviceApprovalsWindow approvalsWindow;
    private final ModuleListPanel moduleListPanel;
    private final LinkedHashMap<String, Panel.Factory> panelFactoriesById = new LinkedHashMap<>();

    private final ObservableCollectionProxy<ConnectedModule, ?> connectedModules;
    private final ObservableCollectionProxy.Listener<ConnectedModule> connectedModulesListener;

    private boolean windowClosed = true;

    public ConfigurationWindow(Configuration config, ObservableCollectionProxy<USBDevice, ?> connectedUSBDevices, ObservableCollectionProxy<ConnectedModule, ?> connectedModules, Collection<Panel.Factory> panelFactories) {
        super();

        this.config = config;
        this.connectedModules = connectedModules;

        panelFactoriesById.put(null, null);
        panelFactories.stream()
                      .sorted(Comparator.comparing(Panel.Factory::getName))
                      .forEach(panelFactory -> {
                          panelFactoriesById.put(panelFactory.getId(), panelFactory);
                      });

        approvalsWindow = new SerialDeviceApprovalsWindow(config, connectedUSBDevices);
        approvalsWindow.setLocationRelativeTo(this);

        setTitle("Configuration");
        setModal(true);
        setSize(960, 640);
        setMinimumSize(new Dimension(600, 400));
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridwidth = 3;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        add(new ApprovalPanel(), gbc);
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        moduleListPanel = new ModuleListPanel();
        JScrollPane moduleListScrollPane = new JScrollPane(withBGColor(new TopAlignedPanel(moduleListPanel), Color.WHITE));
        add(moduleListScrollPane, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;

        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy++;
        add(new JPanel(), gbc);
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;

        gbc.gridx++;
        JButton btnApply = new JButton("Apply & Close");
        btnApply.addActionListener(this::onApplyCloseClicked);
        add(btnApply, gbc);

        gbc.gridx++;
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(this::onCancelClicked);
        add(btnCancel, gbc);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        connectedModulesListener = new ObservableCollectionProxy.Listener<ConnectedModule>() {
            @Override
            public void onAdded(ConnectedModule obj) {
                moduleListPanel.onModuleConnected(obj);
            }

            @Override
            public void onRemoved(ConnectedModule obj) {
                moduleListPanel.onModuleDisconnected(obj);
            }
        };
    }

    @Override
    protected void processWindowEvent(WindowEvent e) {
        int eventId = e.getID();
        if ((eventId == WindowEvent.WINDOW_OPENED) || (windowClosed && (eventId == WindowEvent.WINDOW_ACTIVATED))) {
            onWindowOpened();
        } else if (eventId == WindowEvent.WINDOW_CLOSING) {
            if (hasChanges()) {
                int res = JOptionPane.showConfirmDialog(this, "Do you want to apply the changes?", "Pending Changes", JOptionPane.YES_NO_CANCEL_OPTION);
                if (res == JOptionPane.YES_OPTION) {
                    applyChanges();
                } else if (res != JOptionPane.NO_OPTION) {
                    // cancel
                    return;
                }
            }

            close();
        }

        super.processWindowEvent(e);
    }

    private boolean hasChanges() {
        return !config.getModules().equals(moduleListPanel.toConfigModules());
    }

    private void applyChanges() {
        config.setModules(moduleListPanel.toConfigModules());
        config.trySave();
    }

    private void close() {
        onWindowClosing();
        setVisible(false);
    }

    private void onWindowOpened() {
        LOGGER.debug("window opened");
        windowClosed = false;
        moduleListPanel.clear();
        connectedModules.attach(true, connectedModulesListener);
    }

    private void onWindowClosing() {
        LOGGER.debug("window closing");
        windowClosed = true;
        connectedModules.detach(connectedModulesListener);
        moduleListPanel.clear();
    }

    private void onApprovalsClicked(ActionEvent e) {
        approvalsWindow.setVisible(true);
    }

    private void onApplyCloseClicked(ActionEvent e) {
        applyChanges();
        close();
    }

    private void onCancelClicked(ActionEvent e) {
        close();
    }

    private class ApprovalPanel extends JPanel {
        private ApprovalPanel() {
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();

            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            add(
                stylePlain(
                    new JLabel(
                        "<html><body>Only modules which have been manually approved and are confirmed to run a MobiFlight firmware are listed "
                            + "below. If you want to add a new module or have changed serial numbers on a previously added module, you first need to "
                            + "approve the serial device for communication.</body></html>"
                    )
                ),
                gbc
            );
            gbc.weightx = 0.0;
            gbc.fill = GridBagConstraints.NONE;

            gbc.gridx++;
            JButton btnApproval = new JButton("Manage Device Approvals");
            btnApproval.addActionListener(ConfigurationWindow.this::onApprovalsClicked);
            add(btnApproval, gbc);
        }
    }

    private class ModuleListPanel extends JPanel {
        private final Map<ModuleId, ModulePanel> modulePanels = new LinkedHashMap<>();

        private ModuleListPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(Color.WHITE);

            clear();
        }

        public void clear() {
            removeAll();
            modulePanels.clear();

            config.getModules()
                  .stream()
                  .map(Configuration.Module::getId)
                  .sorted(
                      Comparator.comparing(ModuleId::getName)
                                .thenComparing(ModuleId::getSerial)
                                .thenComparing(ModuleId::getType)
                  )
                  .forEach(this::registerModule);

            revalidate();
            repaint();
        }

        public Set<Configuration.Module> toConfigModules() {
            Set<Configuration.Module> out = new HashSet<>();

            for (ModulePanel panel : modulePanels.values()) {
                Configuration.Module configModule = panel.toConfigModule().orElse(null);
                if (configModule == null) {
                    continue;
                }

                boolean wasNew = out.add(configModule);
                if (!wasNew) {
                    LOGGER.warn("Duplicate module: {}", configModule);
                }
            }

            return out;
        }

        private ModulePanel registerModule(ModuleId id) {
            ModulePanel modulePanel = new ModulePanel(id);

            boolean isFirst = modulePanels.isEmpty();
            if (!isFirst) {
                modulePanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.BLACK));
            }

            ModulePanel previous = modulePanels.put(id, modulePanel);
            if (previous != null) {
                LOGGER.warn("replaced UI panel instance for {}", id);
                remove(previous);
            }

            LOGGER.debug("adding new UI panel instance for {}", id);
            add(modulePanel);

            return modulePanel;
        }

        private void onModuleConnected(ConnectedModule connectedModule) {
            ModuleId id = connectedModule.getModuleId();
            ModulePanel panel = modulePanels.get(id);
            if (panel == null) {
                panel = registerModule(id);
            }

            panel.onConnected(connectedModule);
        }

        private void onModuleDisconnected(ConnectedModule connectedModule) {
            ModuleId id = connectedModule.getModuleId();
            ModulePanel panel = modulePanels.get(id);
            if (panel == null) {
                LOGGER.warn("no UI panel for {}", id);
            } else {
                panel.onDisconnected();
            }
        }
    }

    private class ModulePanel extends JPanel {
        private final ModuleId id;
        private Panel.Factory panelFactory;
        private File connectorConfigFile;
        private final Set<String> foundConfigSerials = new HashSet<>();
        private String selectedConfigSerial;
        private final JLabel labelConnection;
        private final JComboBox<String> comboConfigSerials;
        private final FileField fileField;

        private static final String DISCONNECTED_TEXT = "[not connected]";
        private final String[] noSerialsComboBoxPlaceholder = new String[]{null};

        private final FileFilter mccFileFilter = new FileNameExtensionFilter("MobiFlight Connector configuration (.mcc)", "mcc");

        private ModulePanel(ModuleId id) {
            this.id = id;

            setBackground(Color.WHITE);
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();

            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;

            gbc.gridx = 0;
            gbc.gridy = 0;
            add(styleBold(new JLabel(escapeHtml4(id.getName()))), gbc);

            gbc.gridy++;
            add(styleBold(new JLabel(escapeHtml4(id.getSerial()))), gbc);

            gbc.gridy++;
            add(stylePlain(new JLabel(escapeHtml4(id.getType()))), gbc);

            gbc.gridy++;
            labelConnection = stylePlain(new JLabel(DISCONNECTED_TEXT));
            add(labelConnection, gbc);

            gbc.gridx = 1;
            gbc.gridy = 0;
            JComboBox<Panel.Factory> comboPanelFactory = new JComboBox<>(panelFactoriesById.values().toArray(new Panel.Factory[0]));
            comboPanelFactory.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    String caption = (value == null) ? "[unassigned] select implementation to use" : value.toString();
                    if (value instanceof Panel.Factory) {
                        caption = ((Panel.Factory) value).getName();
                    }
                    return super.getListCellRendererComponent(list, caption, index, isSelected, cellHasFocus);
                }
            });
            comboPanelFactory.addItemListener(this::onPanelFactorySelected);
            add(
                new HorizontalPanel(
                    withBGColor(stylePlain(new JLabel("Implementation:")), Color.WHITE),
                    comboPanelFactory
                ),
                gbc
            );

            gbc.gridy++;
            fileField = new FileField(this::onFileSelected);
            fileField.setBackground(Color.WHITE);
            JFileChooser fileChooser = fileField.getFileChooser();
            fileChooser.addChoosableFileFilter(mccFileFilter);
            fileChooser.setFileFilter(mccFileFilter);
            add(
                new HorizontalPanel(
                    withBGColor(stylePlain(new JLabel("Connector config:")), Color.WHITE),
                    fileField
                ),
                gbc
            );

            gbc.gridy++;
            comboConfigSerials = new JComboBox<>(noSerialsComboBoxPlaceholder);
            comboConfigSerials.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    String caption;
                    if (value != null) {
                        caption = escapeHtml4((String) value);
                    } else {
                        int numSerials = list.getModel().getSize();
                        if (numSerials == 1) {
                            // only placeholder entry = no serials
                            caption = "not required; no serials found in file";
                        } else {
                            // placeholder in addition to other entries = multiple serials, user must select; placeholder is neutral default/prompt
                            caption = "[unassigned] select serial to use";
                        }
                    }

                    return super.getListCellRendererComponent(list, caption, index, isSelected, cellHasFocus);
                }
            });
            add(
                new HorizontalPanel(
                    withBGColor(stylePlain(new JLabel("Config serial:")), Color.WHITE),
                    comboConfigSerials
                ),
                gbc
            );

            Configuration.Module moduleConfig = config.getModule(id).orElse(null);
            if (moduleConfig == null) {
                LOGGER.debug("not configured yet: {}", id);
            } else {
                LOGGER.debug("applying previous configuration: {}", moduleConfig);

                panelFactory = panelFactoriesById.get(moduleConfig.getPanelFactoryId());
                if (panelFactory == null) {
                    LOGGER.warn("panel factory not found: {}", moduleConfig);
                } else {
                    comboPanelFactory.setSelectedItem(panelFactory);
                }

                connectorConfigFile = moduleConfig.getConnectorConfig();
                selectedConfigSerial = moduleConfig.getConnectorConfigSerial().orElse(null);
            }

            applyConnectorConfiguration();
        }

        private void onFileSelected() {
            File file = fileField.getFile().orElse(null);
            if (file != null) {
                if (checkConnectorConfigurationParseable(file)) {
                    connectorConfigFile = file;
                } else {
                    if (connectorConfigFile == null) {
                        JOptionPane.showMessageDialog(this, "The selected file could not be loaded.", "Invalid file", JOptionPane.ERROR_MESSAGE);
                    } else {
                        int res = JOptionPane.showConfirmDialog(this, "The selected file could not be loaded. Keep previous selection?", "Invalid file", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
                        if (res != JOptionPane.YES_OPTION) {
                            connectorConfigFile = null;
                        }
                    }
                }
            }

            applyConnectorConfiguration();
        }

        private boolean checkConnectorConfigurationParseable(File file) {
            try {
                ConnectorConfiguration.fromXML(file);
            } catch (Exception ex) {
                return false;
            }

            return true;
        }

        private void onPanelFactorySelected(ItemEvent e) {
            Panel.Factory panelFactory = (Panel.Factory) e.getItem();
            if (e.getStateChange() == ItemEvent.SELECTED) {
                LOGGER.debug("selection changed to {} for {}", (panelFactory != null) ? panelFactory.getId() : null, id);
                this.panelFactory = panelFactory;
            } else if (e.getStateChange() == ItemEvent.DESELECTED && panelFactory == this.panelFactory) {
                LOGGER.debug("deselected panel factory {} for {}", (panelFactory != null) ? panelFactory.getId() : null, id);
                this.panelFactory = null;
            }
        }

        private void applyConnectorConfiguration() {
            if (connectorConfigFile == null) {
                fileField.unsetFile();
            } else {
                fileField.setFile(connectorConfigFile);
            }

            ConnectorConfiguration connectorConfiguration = null;
            if (connectorConfigFile == null) {
                LOGGER.debug("configuration file not set for {}", id);
            } else {
                try {
                    connectorConfiguration = ConnectorConfiguration.fromXML(connectorConfigFile);
                } catch (Exception ex) {
                    LOGGER.warn("failed to load connector configuration from {} for {}", connectorConfigFile, id, ex);
                }
            }

            foundConfigSerials.clear();
            if (connectorConfiguration == null) {
                LOGGER.debug("resetting configuration for {}", id);
                connectorConfigFile = null;
                selectedConfigSerial = null;
            } else {
                foundConfigSerials.addAll(connectorConfiguration.getSerials());
                LOGGER.debug("Serials found in connector config: {}", foundConfigSerials);

                if (foundConfigSerials.isEmpty()) {
                    LOGGER.debug("no serials; resetting selection");
                    selectedConfigSerial = null;
                } else if (foundConfigSerials.size() == 1) {
                    LOGGER.debug("serial is unique; selecting automatically");
                    selectedConfigSerial = foundConfigSerials.iterator().next();
                }
            }

            if (selectedConfigSerial != null && !foundConfigSerials.contains(selectedConfigSerial)) {
                LOGGER.debug("serial \"{}\" is no longer present, deselecting; got {}", selectedConfigSerial, foundConfigSerials);
                selectedConfigSerial = null;
            }

            comboConfigSerials.setModel(new DefaultComboBoxModel<>(getSerialsForComboBox()));
            comboConfigSerials.setSelectedItem(selectedConfigSerial);
            comboConfigSerials.setEnabled(foundConfigSerials.size() > 1);

            revalidate();
            repaint();
        }

        private String[] getSerialsForComboBox() {
            if (foundConfigSerials.isEmpty()) {
                // no serials
                return noSerialsComboBoxPlaceholder;
            } else if (foundConfigSerials.size() == 1) {
                // exactly one serial = serial gets selected automatically
                return foundConfigSerials.toArray(new String[0]);
            } else {
                // more than one serial => user needs to select a device with default being prompt
                ArrayList<String> tmp = new ArrayList<>();
                tmp.add(null);
                foundConfigSerials.stream()
                                  .sorted()
                                  .forEach(tmp::add);
                return tmp.toArray(new String[0]);
            }
        }

        private void onConnected(ConnectedModule connectedModule) {
            synchronized (this) {
                String devicePath = connectedModule.getUSBDevice()
                                                   .getDeviceNode()
                                                   .map(File::getAbsolutePath)
                                                   .orElseThrow(() -> new IllegalArgumentException("Connected module without device node? " + connectedModule));

                labelConnection.setText(escapeHtml4("version " + connectedModule.getVersion() + " via " + devicePath));

                revalidate();
                repaint();
            }
        }

        private void onDisconnected() {
            synchronized (this) {
                labelConnection.setText(DISCONNECTED_TEXT);

                revalidate();
                repaint();
            }
        }

        private Optional<Configuration.Module> toConfigModule() {
            if ((connectorConfigFile == null) || (panelFactory == null)) {
                return Optional.empty();
            }

            if (!foundConfigSerials.isEmpty() && (selectedConfigSerial == null)) {
                return Optional.empty();
            }

            Configuration.Module.Builder builder = Configuration.Module.builder()
                                                                       .setId(id)
                                                                       .setPanelFactoryId(panelFactory.getId())
                                                                       .setConnectorConfig(connectorConfigFile);

            if (selectedConfigSerial != null) {
                builder.setConnectorConfigSerial(selectedConfigSerial);
            }

            return Optional.of(builder.build());
        }
    }

    private static <T extends JComponent> T withBGColor(T component, Color color) {
        component.setBackground(color);
        return component;
    }

    private static class FileField extends JPanel {
        private final Runnable callback;
        private final JFileChooser fileChooser = new JFileChooser();
        private final JTextField textField;
        private File file;

        FileField(Runnable callback) {
            super();

            this.callback = callback;

            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();

            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            textField = new JTextField();
            textField.setEditable(false);
            add(textField, gbc);
            gbc.weightx = 0.0;
            gbc.fill = GridBagConstraints.NONE;

            gbc.gridx++;
            JButton btnBrowse = new JButton("...");
            btnBrowse.addActionListener(this::onBrowseClicked);
            add(btnBrowse, gbc);
        }

        public Optional<File> getFile() {
            return Optional.ofNullable(file);
        }

        public void unsetFile() {
            setFile(null);
        }

        public void setFile(File file) {
            this.file = file;
            textField.setText((file != null) ? escapeHtml4(file.getAbsolutePath()) : "");
        }

        public JFileChooser getFileChooser() {
            return fileChooser;
        }

        private void onBrowseClicked(ActionEvent event) {
            fileChooser.setCurrentDirectory((file != null) ? file.getParentFile() : null);

            int res = fileChooser.showOpenDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) {
                return;
            }

            onFileSelected(fileChooser.getSelectedFile());
        }

        private void onFileSelected(File file) {
            setFile(file);
            callback.run();
        }
    }

    private static class TopAlignedPanel extends JPanel {
        private final JPanel filler;

        TopAlignedPanel(JComponent topComponent) {
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();

            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            add(topComponent, gbc);

            gbc.gridy++;
            gbc.fill = GridBagConstraints.BOTH;
            gbc.weighty = 1.0;
            filler = new JPanel();
            filler.setBackground(getBackground());
            add(filler, gbc);
        }

        @Override
        public void setBackground(Color bg) {
            super.setBackground(bg);
            if (filler != null) {
                filler.setBackground(bg);
            }
        }
    }

    private static class HorizontalPanel extends JPanel {
        HorizontalPanel(JComponent... components) {
            super();

            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));

            for (JComponent component : components) {
                add(component);
            }
        }
    }
}
