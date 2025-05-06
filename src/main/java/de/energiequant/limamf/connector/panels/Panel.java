package de.energiequant.limamf.connector.panels;

import java.util.Optional;

import de.energiequant.limamf.compat.config.connector.ConnectorConfiguration;
import de.energiequant.limamf.connector.ModuleDiscovery;
import de.energiequant.limamf.connector.simulator.SimulatorEventListener;

public interface Panel {
    Optional<SimulatorEventListener> getSimulatorEventListener();

    void disconnect();

    interface Factory {
        String getId();

        String getName();

        Panel create(PanelEventListener eventListener, ModuleDiscovery.ConnectedModule module, ConnectorConfiguration connectorConfiguration, String connectorConfigurationSerial);
    }
}
