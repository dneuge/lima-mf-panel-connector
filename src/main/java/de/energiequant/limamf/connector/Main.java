package de.energiequant.limamf.connector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

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
import de.energiequant.limamf.connector.gui.MainWindow;
import de.energiequant.limamf.connector.panels.Panel;
import de.energiequant.limamf.connector.simulator.SimulatorClient;

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

    private static final String DEFAULT_CONFIG_PATH = "lima-mf.properties";

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
            return "0.1dev";
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
            return Optional.empty();
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
            LOGGER.warn("At least one module must be configured to start connector.");
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

    public static void main(String[] args) {
        // TODO: add more command line options
        String configPath = DEFAULT_CONFIG_PATH;
        if (args.length > 0) {
            configPath = args[0];
        }

        DisclaimerState disclaimerState = new DisclaimerState(APPLICATION_INFO);

        Configuration config;
        File configFile = new File(configPath);
        if (configFile.exists()) {
            config = Configuration.loadProperties(configFile, disclaimerState);
        } else {
            config = Configuration.createFromDefaults(disclaimerState).setSaveLocation(configFile);
        }

        disclaimerState.addListener(() -> {
            LOGGER.debug("Disclaimer state changed, persisting in config...");

            if (disclaimerState.isAccepted()) {
                config.setAcceptedDisclaimer(disclaimerState.getDisclaimerHash());
            } else {
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

            // TODO: enable headless operation
            MainWindow mainWindow = new MainWindow(main, config, usbSerialDeviceMonitor.getCollectionProxy(), moduleDiscovery.getCollectionProxy(), main::terminate);
            if (!disclaimerState.isAccepted()) {
                mainWindow.showDisclaimer();
            }

            // TODO: auto-start only if configured to do so
            main.startModules();
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
