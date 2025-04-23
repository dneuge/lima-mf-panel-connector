package de.energiequant.limamf.connector.gui;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.WindowConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.apputils.misc.gui.AboutWindow;
import de.energiequant.apputils.misc.gui.ScrollableLogOutputPaneWrapper;
import de.energiequant.limamf.connector.Main;

public class MainWindow extends JFrame {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindow.class);

    private final AboutWindow aboutWindow;

    private final ScrollableLogOutputPaneWrapper log;

    public MainWindow(Main main, Runnable onCloseCallback) {
        super(main.getApplicationInfo().getApplicationName());

        aboutWindow = new AboutWindow(main.getApplicationInfo(), main.getDisclaimerState());

        setSize(800, 600);
        setMinimumSize(new Dimension(600, 400));

        setLocationRelativeTo(null);
        aboutWindow.setLocationRelativeTo(this);

        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
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
        /*
        JButton configureButton = new JButton("Configure");
        configureButton.addActionListener(this::onConfigureClicked);
        add(configureButton, gbc);

        gbc.gridx++;
        */

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

    private void onAboutClicked(ActionEvent event) {
        aboutWindow.setVisible(true);
    }

    private void onQuitClicked(ActionEvent event) {
        System.exit(0);
    }
}
