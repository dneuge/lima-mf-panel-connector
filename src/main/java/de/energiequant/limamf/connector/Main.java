package de.energiequant.limamf.connector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

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
import de.energiequant.limamf.compat.config.connector.ConfigNode;
import de.energiequant.limamf.compat.config.connector.ConnectorConfiguration;
import de.energiequant.limamf.compat.config.connector.ModuleBindable;
import de.energiequant.limamf.connector.gui.MainWindow;
import de.energiequant.limamf.connector.panels.DCPCCPPanel;
import de.energiequant.limamf.connector.panels.Panel;
import de.energiequant.limamf.connector.panels.PanelEventListener;
import de.energiequant.limamf.connector.simulator.SimulatorClient;
import de.energiequant.limamf.connector.simulator.SimulatorEventListener;

public class Main {
    // FIXME: only run while disclaimer is accepted
    // TODO: open disclaimer if not accepted on startup
    // TODO: configuration (generalized => app-utils?)
    // TODO: CLI/headless mode?
    // TODO: wrap with launcher (see legacy proxy)
    // TODO: restart/rescan

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private final Set<String> enabledSerialIds = new HashSet<>();
    private final ConnectorConfiguration connectorConfiguration;

    private final List<Panel> activePanels = new ArrayList<>();

    private final SimulatorClient simulatorClient;

    private final SimulatorEventProxy simulatorEventProxy;
    private final PanelEventProxy panelEventProxy;

    private final DisclaimerState disclaimerState = new DisclaimerState(APPLICATION_INFO);

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

    private abstract static class EventProxy<T> {
        private final Collection<T> targets = new ArrayList<>();

        protected Collection<T> copyTargets() {
            Collection<T> out;
            synchronized (targets) {
                out = new ArrayList<>(targets);
            }
            return out;
        }

        public void attachListener(T listener) {
            synchronized (targets) {
                if (!targets.contains(listener)) {
                    targets.add(listener);
                }
            }
        }

        public void detachListener(T listener) {
            synchronized (targets) {
                targets.remove(listener);
            }
        }
    }

    private static class SimulatorEventProxy extends EventProxy<SimulatorEventListener> implements SimulatorEventListener {
        @Override
        public void onSimStatusChanged(SimulatorStatus status, String msg) {
            for (SimulatorEventListener listener : copyTargets()) {
                try {
                    listener.onSimStatusChanged(status, msg);
                } catch (Exception ex) {
                    LOGGER.warn("onSimStatusChanged: failed to notify listener {}", listener, ex);
                }
            }
        }

        @Override
        public void onSimPanelBrightnessChanged(double fraction) {
            for (SimulatorEventListener listener : copyTargets()) {
                try {
                    listener.onSimPanelBrightnessChanged(fraction);
                } catch (Exception ex) {
                    LOGGER.warn("onSimPanelBrightnessChanged: failed to notify listener {}", listener, ex);
                }
            }
        }
    }

    private static class PanelEventProxy extends EventProxy<PanelEventListener> implements PanelEventListener {
        @Override
        public void onPanelEvent(DCPCCPPanel.Event event) {
            for (PanelEventListener listener : copyTargets()) {
                try {
                    listener.onPanelEvent(event);
                } catch (Exception ex) {
                    LOGGER.warn("onPanelEvent: failed to notify listener {}", listener, ex);
                }
            }
        }
    }

    private Main(Configuration config) {
        // TODO: relocate to GUI, accept none-existing config

        config.getUSBInterfaceIds()
              .getAllPresent()
              .stream()
              .map(USBDeviceId::getSerial)
              .map(x -> x.orElseThrow(() -> new IllegalArgumentException("approved devices are required to have a serial ID")))
              .forEach(enabledSerialIds::add);
        if (enabledSerialIds.isEmpty()) {
            throw new IllegalArgumentException("at least one serial ID is required");
        }

        Collection<Configuration.Module> moduleConfigs = config.getModules();
        if (moduleConfigs.size() != 1) {
            throw new IllegalArgumentException("exactly one module config is required, got " + moduleConfigs.size());
        }

        Configuration.Module moduleConfig = moduleConfigs.iterator().next();

        // TODO: restrict to configured type, name and device serial

        File connectorConfigFile = new File(moduleConfig.getConnectorConfig());

        simulatorEventProxy = new SimulatorEventProxy();
        panelEventProxy = new PanelEventProxy();

        Map<String, SimulatorClient.Factory> simulatorClients = findSimulatorClients();

        // TODO: read wanted client from config instead of requiring exactly one to be present (+ select on GUI)
        if (simulatorClients.size() != 1) {
            LOGGER.error("Exactly one simulator client must be present on class path, found: {}", simulatorClients);
            System.exit(1);
        }
        SimulatorClient.Factory simulatorClientFactory = simulatorClients.entrySet().iterator().next().getValue();

        // TODO: provide actual configuration from sub-properties
        Properties simulatorClientProperties = new Properties();

        LOGGER.info("Using simulator client: {} [{}]", simulatorClientFactory.getClientName(), simulatorClientFactory.getClientId());
        simulatorClient = simulatorClientFactory.createClient(simulatorClientProperties, simulatorEventProxy).orElse(null);
        if (simulatorClient == null) {
            LOGGER.error("Failed to create simulator client: {} [{}]", simulatorClientFactory.getClientName(), simulatorClientFactory.getClientId());
            System.exit(1);
        }
        panelEventProxy.attachListener(simulatorClient.getPanelEventListener());

        connectorConfiguration = ConnectorConfiguration.fromXML(connectorConfigFile);
        Set<String> serials = getSerials(connectorConfiguration);
        LOGGER.debug("Serials in connector configuration: {}", serials);
        if (serials.size() != 1) {
            // TODO: map config serial to device serial; auto-select if unambiguous
            throw new IllegalArgumentException("Unsupported number of serials in config file; found " + serials.size() + ", expected exactly 1");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::terminate));
    }

    private void connect() {
        LOGGER.info("Searching USB devices...");
        DeviceDiscovery deviceDiscovery = DeviceDiscovery.getInstance();
        Collection<USBDevice> usbDevices = deviceDiscovery.findUSBSerialDevices()
                                                          .stream()
                                                          .filter(deviceDiscovery::isKnownUSBProduct)
                                                          .collect(Collectors.toList());
        if (usbDevices.isEmpty()) {
            LOGGER.error("no supported USB devices found");
            return;
        }

        LOGGER.debug("Found USB devices: {}", usbDevices);

        try {
            for (USBDevice usbDevice : usbDevices) {
                String serialId = usbDevice.getId().getSerial().orElse(null);
                if (serialId == null) {
                    LOGGER.warn("Ignoring USB device without serial: {}", usbDevice);
                    continue;
                }

                if (!enabledSerialIds.contains(serialId)) {
                    LOGGER.warn("USB device is not enabled, ignoring: {}", usbDevice);
                    continue;
                }

                DCPCCPPanel panel = DCPCCPPanel.tryConnect(panelEventProxy, usbDevice, connectorConfiguration).orElse(null);
                if (panel != null) {
                    activePanels.add(panel);
                    panel.getSimulatorEventListener().ifPresent(simulatorEventProxy::attachListener);
                }
            }
        } catch (InterruptedException ex) {
            LOGGER.error("interrupted, exiting", ex);
            System.exit(1);
        }

        // TODO: monitor for panels to get connected later
        if (activePanels.isEmpty()) {
            LOGGER.error("no panels found, exiting");
            System.exit(1);
        }
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
        Configuration config = Configuration.loadProperties(new File(args[0]));
        AsyncMonitor<USBDevice, Set<USBDevice>> usbSerialDeviceMonitor = DeviceDiscovery.getInstance().monitorUSBSerialDevices(); // TODO: use globally; optionally override filter
        usbSerialDeviceMonitor.start();
        Main main = new Main(config);
        new MainWindow(main, main::terminate);
        main.connect();
    }

    private void terminate() {
        // TODO: AFAIR logger may have been terminated at this point except it isn't (maybe due to threading?)

        if (simulatorClient != null) {
            try {
                LOGGER.info("disposing simulator client");
                panelEventProxy.detachListener(simulatorClient.getPanelEventListener());
                simulatorClient.disposeSimulatorClient();
            } catch (Exception ex) {
                LOGGER.warn("failed to dispose simulator client", ex);
            }
        }

        for (Panel panel : activePanels) {
            try {
                LOGGER.info("disconnecting from panel {}", panel);
                panel.getSimulatorEventListener().ifPresent(simulatorEventProxy::detachListener);
                panel.disconnect();
            } catch (Exception ex) {
                LOGGER.warn("failed to disconnect panel {}", panel);
            }
        }
    }

    private Set<String> getSerials(ConnectorConfiguration config) {
        Set<String> out = new HashSet<>();
        collectSerials(out, config.getItems());
        return out;
    }

    private void collectSerials(Collection<String> collector, Collection<?> objects) {
        for (Object obj : objects) {
            if (obj instanceof ModuleBindable) {
                ((ModuleBindable) obj).getSerial()
                                      .ifPresent(collector::add);
            }

            if (obj instanceof ConfigNode) {
                collectSerials(collector, ((ConfigNode) obj).getChildren());
            }
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
