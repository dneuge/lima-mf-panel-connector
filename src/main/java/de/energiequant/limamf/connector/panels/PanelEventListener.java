package de.energiequant.limamf.connector.panels;

public interface PanelEventListener {
    void onPanelEvent(DCPCCPPanel.Event event);

    class Adapter implements PanelEventListener {
        @Override
        public void onPanelEvent(DCPCCPPanel.Event event) {
            // ignored by default
        }
    }
}
