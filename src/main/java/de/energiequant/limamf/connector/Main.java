package de.energiequant.limamf.connector;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.JOptionPane;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.apputils.misc.ApplicationInfo;
import de.energiequant.apputils.misc.DisclaimerState;
import de.energiequant.apputils.misc.ResourceUtils;
import de.energiequant.apputils.misc.attribution.AttributionParser;
import de.energiequant.apputils.misc.attribution.CopyrightNoticeProvider;
import de.energiequant.apputils.misc.attribution.CopyrightNotices;
import de.energiequant.apputils.misc.attribution.License;
import de.energiequant.apputils.misc.attribution.Project;
import de.energiequant.apputils.misc.cli.CommandLineAbout;
import de.energiequant.limamf.connector.gui.MainWindow;
import de.energiequant.limamf.connector.panels.Panel;
import de.energiequant.limamf.connector.simulator.SimulatorClient;
import de.energiequant.limamf.connector.utils.OperatingSystem;

public class Main {
    // TODO: CLI/headless mode?

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private final DisclaimerState disclaimerState;

    private final Configuration config;
    private final AsyncMonitor<USBDevice, Set<USBDevice>> usbSerialDeviceMonitor;
    private final ModuleDiscovery moduleDiscovery;
    private final Map<String, SimulatorClient.Factory> simulatorClients;
    private final SimulatorClient.Factory simulatorClientFactory;
    private final Map<String, Panel.Factory> panelFactories;
    private final Linker linker;

    private static final String APPLICATION_JAR_NAME = "lima-mf.jar";

    private static final String OPTION_NAME_HELP = "help";
    private static final String OPTION_NAME_SHOW_DISCLAIMER = "disclaimer";
    private static final String OPTION_NAME_NO_GUI = Launcher.OPTION_NAME_NO_GUI;
    private static final String OPTION_NAME_NO_CLASSPATH_CHECK = Launcher.OPTION_NAME_NO_CLASSPATH_CHECK;
    private static final String OPTION_NAME_CONFIG_PATH = "config";
    private static final String OPTION_NAME_VERSION = "version";
    private static final String OPTION_NAME_SHOW_LICENSE = "license";

    private static final String DEFAULT_CONFIG_NAME = "lima-mf.properties";
    private static final String DEFAULT_CONFIG_PATH = OperatingSystem.resolveInUserConfigDirectory(DEFAULT_CONFIG_NAME)
                                                                     .getAbsolutePath();

    private static final ApplicationInfo APPLICATION_INFO = new ApplicationInfo() {
        @Override
        public Collection<Project> getDependencies() {
            return AttributionParser.getProjects(Main.class);
        }

        @Override
        public CopyrightNoticeProvider getCopyrightNoticeProvider() {
            return CopyrightNotices.loadXML(Main.class);
        }

        @Override
        public String getApplicationName() {
            // NOTE: also needs to be set in Launcher
            return "LiMa-MF Flight Simulation Panel Connector";
        }

        @Override
        public String getApplicationUrl() {
            return "https://github.com/dneuge/lima-mf-panel-connector";
        }

        @Override
        public String getApplicationVersion() {
            return "0.2dev";
        }

        @Override
        public String getApplicationCopyright() {
            return "Copyright (c) 2025 Daniel Neugebauer";
        }

        @Override
        public List<String> getExtraInfo() {
            return Collections.emptyList();
        }

        @Override
        public License getEffectiveLicense() {
            return License.MIT;
        }

        @Override
        public Optional<String> getDisclaimer() {
            return Optional.of(
                ResourceUtils.getRelativeResourceContentAsString(Main.class, "disclaimer.txt", StandardCharsets.UTF_8)
                             .orElseThrow(DisclaimerNotFound::new)
            );
        }

        @Override
        public Optional<String> getDisclaimerAcceptanceText() {
            return Optional.of("I understand and accept the disclaimer and licenses");
        }
    };

    private Main(Configuration config, AsyncMonitor<USBDevice, Set<USBDevice>> usbSerialDeviceMonitor, ModuleDiscovery moduleDiscovery, DisclaimerState disclaimerState) {
        Runtime.getRuntime().addShutdownHook(new Thread(this::terminate));

        this.config = config;
        this.disclaimerState = disclaimerState;
        this.usbSerialDeviceMonitor = usbSerialDeviceMonitor;
        this.moduleDiscovery = moduleDiscovery;

        panelFactories = Collections.unmodifiableMap(indexPanelFactories());
        simulatorClients = findSimulatorClients();

        // TODO: read wanted client from config instead of requiring exactly one to be present (+ select on GUI)
        if (simulatorClients.size() != 1) {
            LOGGER.error("Exactly one simulator client must be present on class path, found: {}", simulatorClients);
            System.exit(1);
        }
        simulatorClientFactory = simulatorClients.entrySet().iterator().next().getValue();

        linker = new Linker(panelFactories, moduleDiscovery.getCollectionProxy(), disclaimerState);
    }

    public boolean isRunning() {
        return linker.isRunning();
    }

    public int getNumActiveModules() {
        return linker.getNumActivePanels();
    }

    public boolean startModules() {
        if (!disclaimerState.isAccepted()) {
            LOGGER.warn("Disclaimer must be accepted to start connector.");
            return false;
        }

        if (config.getModules().isEmpty()) {
            LOGGER.warn("No modules have been configured yet, unable to start.");
            return false;
        }

        linker.enable(simulatorClientFactory, config.getModules());
        return true;
    }

    public boolean stopModules() {
        return linker.disable();
    }

    private Map<String, Panel.Factory> indexPanelFactories() {
        Map<String, Panel.Factory> out = new HashMap<>();

        Set<String> disabledFactoryIds = new HashSet<>();
        for (Panel.Factory factory : ServiceLoader.load(Panel.Factory.class)) {
            String id = factory.getId();
            if (disabledFactoryIds.contains(id)) {
                LOGGER.warn("Not recording disabled factory: \"{}\" {}", id, factory.getClass().getCanonicalName());
                continue;
            }

            Panel.Factory previous = out.put(id, factory);
            if (previous != null) {
                LOGGER.warn(
                    "Multiple factories use same ID, disabling: \"{}\" {} {}",
                    id, factory.getClass().getCanonicalName(), previous.getClass().getCanonicalName()
                );
                out.remove(id);
                disabledFactoryIds.add(id);
                continue;
            }

            LOGGER.debug("Recorded panel factory: \"{}\" {}", id, factory.getClass().getCanonicalName());
        }

        return out;
    }

    public Map<String, Panel.Factory> getPanelFactories() {
        return panelFactories;
    }

    private static Map<String, SimulatorClient.Factory> findSimulatorClients() {
        Map<String, SimulatorClient.Factory> out = new HashMap<>();

        for (SimulatorClient.Factory factory : ServiceLoader.load(SimulatorClient.Factory.class)) {
            String id = factory.getClientId();
            SimulatorClient.Factory previous = out.put(id, factory);
            if (previous != null) {
                LOGGER.warn("Multiple simulator client implementations found identifying as {}: {}, {}", id, previous.getClass().getCanonicalName(), factory.getClass().getCanonicalName());
            }
        }

        return out;
    }

    private static Stream<String> sortedLicenseKeys() {
        return Arrays.stream(License.values())
                     .map(License::name)
                     .sorted();
    }

    private static void addOptions(Options options) {
        options.addOption(Option.builder()
                                .longOpt(OPTION_NAME_HELP)
                                .desc("prints the help text")
                                .build());

        options.addOption(Option.builder()
                                .longOpt(OPTION_NAME_VERSION)
                                .desc("prints all version, dependency and license information")
                                .build());

        options.addOption(Option.builder()
                                .longOpt(OPTION_NAME_SHOW_DISCLAIMER)
                                .desc("prints the disclaimer")
                                .build());

        options.addOption(Option.builder()
                                .longOpt(OPTION_NAME_SHOW_LICENSE)
                                .hasArg()
                                .desc("prints the specified license, available: "
                                          + sortedLicenseKeys().collect(Collectors.joining(", ")))
                                .build());

        options.addOption(Option.builder()
                                .longOpt(OPTION_NAME_NO_GUI)
                                .desc("disables GUI to force running headless on CLI")
                                .build());

        options.addOption(Option.builder()
                                .longOpt(OPTION_NAME_NO_CLASSPATH_CHECK)
                                .desc("disables check for possibly broken Java class path at application startup")
                                .build());

        options.addOption(Option.builder()
                                .longOpt(OPTION_NAME_CONFIG_PATH)
                                .hasArg()
                                .desc("path to configuration file to be used (default: " + DEFAULT_CONFIG_PATH + ")")
                                .build());
    }

    public static void main(String[] args) {
        Options options = new Options();
        addOptions(options);

        CommandLineParser parser = new DefaultParser();
        CommandLine parameters = null;
        try {
            parameters = parser.parse(options, args);
        } catch (ParseException ex) {
            System.err.println("Failed to parse command line: " + ex.getMessage());
            System.err.flush();
        }
        if (parameters == null || parameters.hasOption(OPTION_NAME_HELP)) {
            new HelpFormatter().printHelp(APPLICATION_JAR_NAME, options);
            System.exit((parameters == null) ? 1 : 0);
            return;
        }

        CommandLineAbout about = new CommandLineAbout(System.out, APPLICATION_INFO, "--" + OPTION_NAME_SHOW_LICENSE);
        if (parameters.hasOption(OPTION_NAME_VERSION)) {
            about.printVersion();
            System.exit(0);
            return;
        }

        if (parameters.hasOption(OPTION_NAME_SHOW_LICENSE)) {
            String licenseName = parameters.getOptionValue(OPTION_NAME_SHOW_LICENSE);
            about.printLicenseAndQuit(licenseName);
            return;
        }

        boolean shouldRunHeadless = GraphicsEnvironment.isHeadless() || parameters.hasOption(OPTION_NAME_NO_GUI);

        DisclaimerState disclaimerState = new DisclaimerState(APPLICATION_INFO);

        Configuration config;
        String configPath = parameters.getOptionValue(OPTION_NAME_CONFIG_PATH, DEFAULT_CONFIG_PATH);
        File configFile = new File(configPath);
        if (configFile.exists()) {
            config = Configuration.loadProperties(configFile, disclaimerState);
        } else {
            config = Configuration.createFromDefaults(disclaimerState).setSaveLocation(configFile);
        }

        if (parameters.hasOption(OPTION_NAME_SHOW_DISCLAIMER)
            || (!disclaimerState.isAccepted() && shouldRunHeadless)) {
            about.printDisclaimer();

            if (shouldRunHeadless) {
                System.out.println();
                System.out.println("=== Disclaimer can only be accepted on GUI ===");
            }

            System.exit(disclaimerState.isAccepted() ? 0 : 1);
            return;
        }

        if (!(OperatingSystem.isLinux() || OperatingSystem.isMacOS())) {
            String title = "Unsupported operating system";
            String msg = APPLICATION_INFO.getApplicationName() + " only runs on Linux and macOS.\n\nFound: " + System.getProperty("os.name");

            LOGGER.error("{}; {}", title, msg.replaceAll("\\R+", " "));

            if (!shouldRunHeadless) {
                JOptionPane.showMessageDialog(null, msg, title, JOptionPane.ERROR_MESSAGE);
            }

            System.exit(1);
            return;
        }

        disclaimerState.addListener(() -> {
            LOGGER.debug("Disclaimer state changed, persisting in config...");

            if (disclaimerState.isAccepted()) {
                LOGGER.info("Disclaimer has been accepted. You can now configure modules and connect them to your flight simulator by clicking on \"Run/Stop\".");
                config.setAcceptedDisclaimer(disclaimerState.getDisclaimerHash());
            } else {
                LOGGER.warn("Disclaimer acceptance has been revoked. You need to accept the disclaimer to connect modules with this application.");
                config.unsetAcceptedDisclaimer();
            }

            config.trySave();
        });

        // TODO: add option to override device node name filter
        AsyncMonitor<USBDevice, Set<USBDevice>> usbSerialDeviceMonitor = DeviceDiscovery.getInstance().monitorUSBSerialDevices();
        usbSerialDeviceMonitor.start();

        ModuleDiscovery moduleDiscovery = new ModuleDiscovery(config, usbSerialDeviceMonitor.getCollectionProxy(), disclaimerState);
        moduleDiscovery.start();

        try {
            Main main = new Main(config, usbSerialDeviceMonitor, moduleDiscovery, disclaimerState);

            if (!shouldRunHeadless) {
                MainWindow mainWindow = new MainWindow(main, config, usbSerialDeviceMonitor.getCollectionProxy(), moduleDiscovery.getCollectionProxy(), main::terminate);
                if (!disclaimerState.isAccepted()) {
                    mainWindow.showDisclaimer();
                }
            }

            // TODO: auto-start only if configured to do so
            boolean started = main.startModules();
            if (!started && shouldRunHeadless) {
                LOGGER.error("Error during startup, not recoverable without GUI.");
                System.exit(1);
            }
        } catch (Exception ex) {
            LOGGER.error("application startup failed", ex);
            moduleDiscovery.shutdown();
            usbSerialDeviceMonitor.shutdown();
        }
    }

    private void terminate() {
        // TODO: AFAIR logger may have been terminated at this point except it isn't (maybe due to threading?)
        if (linker != null) {
            linker.disable();
        }

        if (moduleDiscovery != null) {
            moduleDiscovery.shutdown();
        }

        if (usbSerialDeviceMonitor != null) {
            usbSerialDeviceMonitor.shutdown();
        }
    }

    public ApplicationInfo getApplicationInfo() {
        return APPLICATION_INFO;
    }

    public DisclaimerState getDisclaimerState() {
        return disclaimerState;
    }

    private static class DisclaimerNotFound extends RuntimeException {
        public DisclaimerNotFound() {
            super("Disclaimer could not be found");
        }
    }
}
