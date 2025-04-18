package de.energiequant.limamf.connector.panels;

import java.util.Optional;

import de.energiequant.limamf.connector.simulator.SimulatorEventListener;

public interface Panel {
    Optional<SimulatorEventListener> getSimulatorEventListener();

    void disconnect();
}
