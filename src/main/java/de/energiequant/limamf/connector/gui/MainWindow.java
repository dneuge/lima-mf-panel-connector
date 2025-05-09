package de.energiequant.limamf.connector.gui;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.WindowConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.apputils.misc.gui.AboutWindow;
import de.energiequant.apputils.misc.gui.ScrollableLogOutputPaneWrapper;
import de.energiequant.limamf.connector.Configuration;
import de.energiequant.limamf.connector.Main;
import de.energiequant.limamf.connector.ModuleDiscovery.ConnectedModule;
import de.energiequant.limamf.connector.ObservableCollectionProxy;
import de.energiequant.limamf.connector.USBDevice;

public class MainWindow extends JFrame {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindow.class);

    private final Main main;
    private final ConfigurationWindow configWindow;
    private final AboutWindow aboutWindow;

    private final ScrollableLogOutputPaneWrapper log;

    public MainWindow(Main main, Configuration config, ObservableCollectionProxy<USBDevice, ?> connectedUSBDevices, ObservableCollectionProxy<ConnectedModule, ?> connectedModules, Runnable onCloseCallback) {
        super(main.getApplicationInfo().getApplicationName());

        this.main = main;

        aboutWindow = new AboutWindow(main.getApplicationInfo(), main.getDisclaimerState());
        configWindow = new ConfigurationWindow(config, connectedUSBDevices, connectedModules, main.getPanelFactories().values());

        setSize(800, 600);
        setMinimumSize(new Dimension(600, 400));

        setLocationRelativeTo(null);
        aboutWindow.setLocationRelativeTo(this);
        configWindow.setLocationRelativeTo(this);

        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 4;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        log = new ScrollableLogOutputPaneWrapper(this::add, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        JButton runStopButton = new JButton("Run/Stop");
        runStopButton.addActionListener(this::onRunStopClicked);
        add(runStopButton, gbc);

        gbc.gridx++;
        JButton configureButton = new JButton("Configure");
        configureButton.addActionListener(this::onConfigureClicked);
        add(configureButton, gbc);

        gbc.gridx++;
        gbc.anchor = GridBagConstraints.EAST;
        JButton aboutButton = new JButton("About");
        aboutButton.addActionListener(this::onAboutClicked);
        add(aboutButton, gbc);

        gbc.gridx++;
        JButton quitButton = new JButton("Quit");
        quitButton.addActionListener(this::onQuitClicked);
        add(quitButton, gbc);

        log.appendLogOutput();

        setVisible(true);

        log.startAutoUpdate();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                log.stopAutoUpdate();
                onCloseCallback.run();
            }
        });

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    }

    public void showDisclaimer() {
        setVisible(true);
        aboutWindow.showDisclaimer();
    }

    private void onRunStopClicked(ActionEvent event) {
        if (!main.isRunning()) {
            main.startModules();
        } else {
            main.stopModules();
        }
    }

    private void onConfigureClicked(ActionEvent event) {
        if (main.isRunning()) {
            int res = JOptionPane.showConfirmDialog(this, "Connector must be stopped before entering configuration.\nStop now?", "Connector still running", JOptionPane.YES_NO_OPTION);
            if (res != JOptionPane.YES_OPTION) {
                return;
            }

            main.stopModules();
        }

        if (main.isRunning()) {
            // the only way this should be able to happen is if a module could not be disconnected
            JOptionPane.showMessageDialog(this, "Some modules could not be stopped yet, configuration cannot be entered.", "Connector could not be stopped", JOptionPane.ERROR_MESSAGE);
            return;
        }

        configWindow.setVisible(true);
    }

    private void onAboutClicked(ActionEvent event) {
        aboutWindow.setVisible(true);
    }

    private void onQuitClicked(ActionEvent event) {
        System.exit(0);
    }
}
