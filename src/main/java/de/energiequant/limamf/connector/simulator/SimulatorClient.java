package de.energiequant.limamf.connector.simulator;

import java.util.Optional;
import java.util.Properties;

import de.energiequant.limamf.connector.panels.PanelEventListener;

public interface SimulatorClient {
    PanelEventListener getPanelEventListener();

    void disposeSimulatorClient();

    interface Factory {
        String getClientId();

        String getClientName();

        Optional<SimulatorClient> createClient(Properties config, SimulatorEventListener listener);
    }
}
