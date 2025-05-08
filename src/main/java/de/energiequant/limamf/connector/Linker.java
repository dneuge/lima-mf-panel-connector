package de.energiequant.limamf.connector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.compat.config.connector.ConnectorConfiguration;
import de.energiequant.limamf.connector.panels.DCPCCPPanel;
import de.energiequant.limamf.connector.panels.Panel;
import de.energiequant.limamf.connector.panels.PanelEventListener;
import de.energiequant.limamf.connector.simulator.SimulatorClient;
import de.energiequant.limamf.connector.simulator.SimulatorEventListener;

public class Linker {
    private static final Logger LOGGER = LoggerFactory.getLogger(Linker.class);

    private final Map<String, Panel.Factory> panelFactories;
    private final Map<ModuleId, Configuration.Module> configuredModules = new HashMap<>();
    private final ObservableCollectionProxy<ModuleDiscovery.ConnectedModule, ?> connectedModules;
    private final ObservableCollectionProxy.Listener<ModuleDiscovery.ConnectedModule> connectedModulesListener;
    private final Map<ModuleId, Panel> activePanels = new HashMap<>();

    private SimulatorClient simulatorClient;

    private final SimulatorEventProxy simulatorEventProxy = new SimulatorEventProxy();
    private final PanelEventProxy panelEventProxy = new PanelEventProxy();

    private final AtomicBoolean running = new AtomicBoolean();

    public Linker(Map<String, Panel.Factory> panelFactories, ObservableCollectionProxy<ModuleDiscovery.ConnectedModule, ?> connectedModules) {
        this.panelFactories = panelFactories;
        this.connectedModules = connectedModules;

        connectedModulesListener = new ObservableCollectionProxy.Listener<ModuleDiscovery.ConnectedModule>() {
            @Override
            public void onAdded(ModuleDiscovery.ConnectedModule obj) {
                onModuleConnected(obj);
            }

            @Override
            public void onRemoved(ModuleDiscovery.ConnectedModule obj) {
                onModuleDisconnected(obj);
            }
        };
    }

    private void onModuleConnected(ModuleDiscovery.ConnectedModule module) {
        ModuleId moduleId = module.getModuleId();
        Configuration.Module moduleConfig = configuredModules.get(moduleId);
        if (moduleConfig == null) {
            LOGGER.info("Module is not configured, ignoring: {}", module);
            return;
        }

        LOGGER.info("Module connected, starting: {}", module);

        // get Panel factory
        String panelFactoryId = moduleConfig.getPanelFactoryId();
        Panel.Factory panelFactory = panelFactories.get(panelFactoryId);
        if (panelFactory == null) {
            LOGGER.warn("Implementation \"{}\" not found. Check configuration; ignoring: {}", panelFactoryId, module);
            return;
        }

        // load Connector config file
        File connectorConfigurationFile = moduleConfig.getConnectorConfig();
        ConnectorConfiguration connectorConfiguration = null;
        try {
            connectorConfiguration = ConnectorConfiguration.fromXML(connectorConfigurationFile);
        } catch (Exception ex) {
            LOGGER.warn("Failed to load Connector configuration file {}, ignoring: {}", connectorConfigurationFile, module, ex);
            return;
        }

        // check that serial number to be used matches Connector configuration
        String wantedConnectorSerial = moduleConfig.getConnectorConfigSerial().orElse(null);
        Set<String> connectorSerials = connectorConfiguration.getSerials();
        LOGGER.debug("Serials in connector configuration: {}", connectorSerials);
        if (wantedConnectorSerial == null) {
            if (connectorSerials.size() == 1) {
                wantedConnectorSerial = connectorSerials.iterator().next();
                LOGGER.debug("auto-selected unique serial \"{}\"", wantedConnectorSerial);
            } else if (!connectorSerials.isEmpty()) {
                LOGGER.warn("Multiple serials found in Connector configuration file but none has been selected. Check configuration; ignoring: {}", module);
                return;
            } else {
                LOGGER.debug("no serial selected, but also no serial in Connector config => OK");
            }
        } else {
            if (!connectorSerials.contains(wantedConnectorSerial)) {
                LOGGER.warn("Wanted serial \"{}\" not found in Connector configuration. Check configuration; ignoring: {}", wantedConnectorSerial, module);
                return;
            } else {
                LOGGER.debug("wanted serial found in Connector config => OK");
            }
        }

        synchronized (this) {
            if (!running.get()) {
                LOGGER.warn("aborting startup due to concurrent shutdown");
                return;
            }

            if (activePanels.containsKey(moduleId)) {
                LOGGER.warn("Module already appears to have an active implementation, aborting: {}", module);
                return;
            }

            Panel panel;
            try {
                panel = panelFactory.create(panelEventProxy, module, connectorConfiguration, wantedConnectorSerial);
            } catch (Exception ex) {
                LOGGER.warn("failed to start \"{}\" (\"{}\") for {}", panelFactory.getName(), panelFactoryId, module, ex);
                return;
            }

            Panel previous = activePanels.put(moduleId, panel);
            if (previous != null) {
                LOGGER.error("Module has been started multiple times; bailing out: {}; previous: {}; new: {}", module, previous, panel);
                System.exit(1);
                return;
            }

            panel.getSimulatorEventListener().ifPresent(simulatorEventProxy::attachListener);

            LOGGER.info("started \"{}\" for {}", panelFactory.getName(), module);
        }
    }

    private void onModuleDisconnected(ModuleDiscovery.ConnectedModule module) {
        stopPanel(module.getModuleId());
    }

    private void stopPanel(ModuleId moduleId) {
        synchronized (this) {
            Panel panel = activePanels.get(moduleId);
            if (panel == null) {
                LOGGER.debug("Ignoring disconnected module without active implementation: {}", moduleId);
                return;
            }

            LOGGER.info("stopping disconnected module {}", moduleId);
            try {
                panel.getSimulatorEventListener().ifPresent(simulatorEventProxy::detachListener);
                panel.disconnect();
            } catch (Exception ex) {
                LOGGER.warn("failed to stop implementation for {}", moduleId, ex);
                return;
            }

            Panel removedPanel = activePanels.remove(moduleId);
            if (removedPanel != panel) {
                LOGGER.error("Wrong module has been marked as stopped; bailing out: {}; stopped: {}; removed: {}", moduleId, panel, removedPanel);
                System.exit(1);
                return;
            }

            LOGGER.info("stopped disconnected module {}", moduleId);
        }
    }

    public void enable(SimulatorClient.Factory simulatorClientFactory, Collection<Configuration.Module> configuredModules) {
        Map<ModuleId, Configuration.Module> configuredModuleIndex = new HashMap<>();
        for (Configuration.Module configuredModule : configuredModules) {
            Configuration.Module previous = configuredModuleIndex.put(configuredModule.getId(), configuredModule);
            if (previous != null) {
                throw new IllegalArgumentException("Same module ID has multiple configurations: " + previous + ", " + configuredModule);
            }
        }

        if (configuredModuleIndex.isEmpty()) {
            throw new IllegalArgumentException("at least one module must be configured");
        }

        synchronized (this) {
            boolean alreadyRunning = running.getAndSet(true);
            if (alreadyRunning) {
                throw new InvalidState("linker is already running; unable to reconfigure");
            }

            LOGGER.info("Starting modules...");

            // TODO: provide actual configuration from sub-properties when there is anything to configure
            Properties simulatorClientProperties = new Properties();

            LOGGER.info("Using simulator client: {} [{}]", simulatorClientFactory.getClientName(), simulatorClientFactory.getClientId());
            simulatorClient = simulatorClientFactory.createClient(simulatorClientProperties, simulatorEventProxy).orElse(null);
            if (simulatorClient == null) {
                LOGGER.error("Failed to create simulator client: {} [{}]", simulatorClientFactory.getClientName(), simulatorClientFactory.getClientId());
                return;
            }
            panelEventProxy.attachListener(simulatorClient.getPanelEventListener());

            this.configuredModules.clear();
            this.configuredModules.putAll(configuredModuleIndex);

            connectedModules.attach(true, connectedModulesListener);
        }
    }

    public boolean disable() {
        synchronized (this) {
            if (!running.get()) {
                return true;
            }

            LOGGER.info("Stopping...");

            connectedModules.detach(connectedModulesListener);

            if (simulatorClient != null) {
                try {
                    LOGGER.debug("disposing simulator client");
                    panelEventProxy.detachListener(simulatorClient.getPanelEventListener());
                    simulatorClient.disposeSimulatorClient();
                } catch (Exception ex) {
                    LOGGER.warn("failed to dispose simulator client", ex);
                }
            }

            for (ModuleId moduleId : new HashSet<>(activePanels.keySet())) {
                stopPanel(moduleId);
            }

            if (!activePanels.isEmpty()) {
                LOGGER.warn("Some modules could not be stopped: {}", activePanels.keySet());
                return false;
            }

            running.set(false);

            LOGGER.info("Stopped.");
        }

        return true;
    }

    public boolean isRunning() {
        return running.get();
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

    private static class InvalidState extends RuntimeException {
        private InvalidState(String msg) {
            super(msg);
        }
    }
}
