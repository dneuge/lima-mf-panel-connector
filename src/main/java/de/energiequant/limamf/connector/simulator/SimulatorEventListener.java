package de.energiequant.limamf.connector.simulator;

import de.energiequant.limamf.connector.SimulatorStatus;

public interface SimulatorEventListener {
    void onSimStatusChanged(SimulatorStatus status, String msg);

    void onSimPanelBrightnessChanged(double fraction);

    class Adapter implements SimulatorEventListener {
        @Override
        public void onSimStatusChanged(SimulatorStatus status, String msg) {
            // ignored by default
        }

        @Override
        public void onSimPanelBrightnessChanged(double fraction) {
            // ignored by default
        }
    }
}
