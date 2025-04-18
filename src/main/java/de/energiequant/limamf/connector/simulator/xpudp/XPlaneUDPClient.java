package de.energiequant.limamf.connector.simulator.xpudp;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

import de.energiequant.limamf.connector.panels.DCPCCPPanel;
import de.energiequant.limamf.connector.panels.PanelEventListener;
import de.energiequant.limamf.connector.simulator.SimulatorClient;
import de.energiequant.limamf.connector.simulator.SimulatorEventListener;
import de.painer.xplane.XPlane;
import de.painer.xplane.XPlaneDiscovery;

public class XPlaneUDPClient implements SimulatorClient, PanelEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(XPlaneUDPClient.class);

    private final SimulatorEventListener listener;
    private final XPlaneConnectionManager xplaneConnectionManager;

    private XPlane xplane;

    private static final Map<DCPCCPPanel.Event, Set<String>> COMMAND_NAMES;

    static {
        Map<DCPCCPPanel.Event, Set<String>> out = new HashMap<>();
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_MENU, DCPCCPPanel.Action.PUSH, "menu");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_NAV_SRC, DCPCCPPanel.Action.PUSH, "nav_src");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_FRMT, DCPCCPPanel.Action.PUSH, "frmt");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_ESC, DCPCCPPanel.Action.PUSH, "esc");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_LWR_FRMT, DCPCCPPanel.Action.PUSH, "lwr_frmt");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_ROTARY_MENU, DCPCCPPanel.Action.DECREMENT, "menu_adv_down");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_ROTARY_MENU, DCPCCPPanel.Action.INCREMENT, "menu_adv_up");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_ROTARY_DATA, DCPCCPPanel.Action.DECREMENT, "menu_data_down");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_ROTARY_DATA, DCPCCPPanel.Action.INCREMENT, "menu_data_up");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_MENU_SELECT, DCPCCPPanel.Action.PUSH, "menu_push");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_TFC, DCPCCPPanel.Action.PUSH, "tfc");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_TR_WX, DCPCCPPanel.Action.PUSH, "tr_wx");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_BRG_SRC, DCPCCPPanel.Action.PUSH, "brg_src");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_REFS, DCPCCPPanel.Action.PUSH, "refs");
        defineCL650Command(out, DCPCCPPanel.Source.DCP1_RDR_MENU, DCPCCPPanel.Action.PUSH, "rdr_menu");
        defineCL650Command(out, DCPCCPPanel.Source.DCP2_ROTARY_TILT, DCPCCPPanel.Action.DECREMENT, "tilt_down");
        defineCL650Command(out, DCPCCPPanel.Source.DCP2_ROTARY_TILT, DCPCCPPanel.Action.INCREMENT, "tilt_up");
        defineCL650Command(out, DCPCCPPanel.Source.DCP2_ROTARY_RANGE, DCPCCPPanel.Action.DECREMENT, "range_down");
        defineCL650Command(out, DCPCCPPanel.Source.DCP2_ROTARY_RANGE, DCPCCPPanel.Action.INCREMENT, "range_up");
        defineCL650Command(out, DCPCCPPanel.Source.DCP2_AUTO_TILT, DCPCCPPanel.Action.PUSH, "tilt_auto");
        defineCL650Command(out, DCPCCPPanel.Source.DCP2_RADAR, DCPCCPPanel.Action.PUSH, "radar");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_UPR_MENU, DCPCCPPanel.Action.PUSH, "upr_menu");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_LWR_MENU, DCPCCPPanel.Action.PUSH, "lwr_menu");
        defineCL650Command(out, DCPCCPPanel.Source.CCP4_SUMRY, DCPCCPPanel.Action.PUSH, "sumry");
        defineCL650Command(out, DCPCCPPanel.Source.CCP4_CAS, DCPCCPPanel.Action.PUSH, "cas");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_ESC, DCPCCPPanel.Action.PUSH, "esc");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_ROTARY_MENU, DCPCCPPanel.Action.DECREMENT, "menu_adv_down");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_ROTARY_MENU, DCPCCPPanel.Action.INCREMENT, "menu_adv_up");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_ROTARY_DATA, DCPCCPPanel.Action.DECREMENT, "menu_data_down");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_ROTARY_DATA, DCPCCPPanel.Action.INCREMENT, "menu_data_up");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_MENU_SELECT, DCPCCPPanel.Action.PUSH, "menu_push");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_TFC, DCPCCPPanel.Action.PUSH, "tfc");
        defineCL650Command(out, DCPCCPPanel.Source.CCP4_AC_ELEC, DCPCCPPanel.Action.PUSH, "ac_elec");
        defineCL650Command(out, DCPCCPPanel.Source.CCP4_DC_ELEC, DCPCCPPanel.Action.PUSH, "dc_elec");
        defineCL650Command(out, DCPCCPPanel.Source.CCP1_TR_WX, DCPCCPPanel.Action.PUSH, "tr_wx");
        defineCL650Command(out, DCPCCPPanel.Source.CCP4_HYD, DCPCCPPanel.Action.PUSH, "hyd");
        defineCL650Command(out, DCPCCPPanel.Source.CCP4_FLT, DCPCCPPanel.Action.PUSH, "flt");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP2_MEM1, DCPCCPPanel.Action.PUSH, "custom/ccp1mem1_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP2_MEM1, DCPCCPPanel.Action.NEUTRAL, "custom/ccp1mem1_release");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP2_MEM2, DCPCCPPanel.Action.PUSH, "custom/ccp1mem2_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP2_MEM2, DCPCCPPanel.Action.NEUTRAL, "custom/ccp1mem2_release");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP2_MEM3, DCPCCPPanel.Action.PUSH, "custom/ccp1mem3_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP2_MEM3, DCPCCPPanel.Action.NEUTRAL, "custom/ccp1mem3_release");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP2_MEM1, DCPCCPPanel.Action.PUSH, "custom/ccp2mem1_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP2_MEM1, DCPCCPPanel.Action.NEUTRAL, "custom/ccp2mem1_release");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP2_MEM2, DCPCCPPanel.Action.PUSH, "custom/ccp2mem2_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP2_MEM2, DCPCCPPanel.Action.NEUTRAL, "custom/ccp2mem2_release");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP2_MEM3, DCPCCPPanel.Action.PUSH, "custom/ccp2mem3_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP2_MEM3, DCPCCPPanel.Action.NEUTRAL, "custom/ccp2mem3_release");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_RADIO, DCPCCPPanel.Action.PUSH, "radio");
        defineCL650Command(out, DCPCCPPanel.Source.CCP3_CHART, DCPCCPPanel.Action.PUSH, "chart");
        defineCL650Command(out, DCPCCPPanel.Source.CCP3_ROTATE, DCPCCPPanel.Action.PUSH, "chart_orient");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP3_ZOOM, DCPCCPPanel.Action.DECREMENT, "custom/ccp1zoomdown_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP3_ZOOM, DCPCCPPanel.Action.INCREMENT, "custom/ccp1zoomup_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP3_ZOOM, DCPCCPPanel.Action.NEUTRAL, "custom/ccp1zoomdown_release", "custom/ccp1zoomup_release");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP3_ZOOM, DCPCCPPanel.Action.DECREMENT, "custom/ccp2zoomdown_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP3_ZOOM, DCPCCPPanel.Action.INCREMENT, "custom/ccp2zoomup_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP3_ZOOM, DCPCCPPanel.Action.NEUTRAL, "custom/ccp2zoomdown_release", "custom/ccp2zoomup_release");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_FREQ, DCPCCPPanel.Action.PUSH, "freq");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_ROTARY_RADIO, DCPCCPPanel.Action.DECREMENT, "radio_adv_down");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_ROTARY_RADIO, DCPCCPPanel.Action.INCREMENT, "radio_adv_up");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_ROTARY_DATA, DCPCCPPanel.Action.DECREMENT, "radio_data_down");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_ROTARY_DATA, DCPCCPPanel.Action.INCREMENT, "radio_data_up");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_RADIO_SELECT, DCPCCPPanel.Action.PUSH, "radio_push");
        defineCL650Command(out, DCPCCPPanel.Source.CCP3_JSTK, DCPCCPPanel.Action.PUSH, "jstk");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.UP, "custom/ccp1jup_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.LEFT, "custom/ccp1jleft_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.DOWN, "custom/ccp1jdown_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.RIGHT, "custom/ccp1jright_hold");
        defineCommand(out, DCPCCPPanel.Side.LEFT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.NEUTRAL, "custom/ccp1jup_release", "custom/ccp1jleft_release", "custom/ccp1jdown_release", "custom/ccp1jright_release");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.UP, "custom/ccp2jup_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.LEFT, "custom/ccp2jleft_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.DOWN, "custom/ccp2jdown_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.RIGHT, "custom/ccp2jright_hold");
        defineCommand(out, DCPCCPPanel.Side.RIGHT, DCPCCPPanel.Source.CCP3_CURSOR, DCPCCPPanel.Action.NEUTRAL, "custom/ccp2jup_release", "custom/ccp2jleft_release", "custom/ccp2jdown_release", "custom/ccp2jright_release");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_1_2, DCPCCPPanel.Action.PUSH, "1_2");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_DME_H, DCPCCPPanel.Action.PUSH, "dme_h");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_IDENT, DCPCCPPanel.Action.PUSH, "ident");
        defineCL650Command(out, DCPCCPPanel.Source.CCP5_ATC, DCPCCPPanel.Action.PUSH, "atc");
        COMMAND_NAMES = Collections.unmodifiableMap(out); // TODO: sets should also be unmodifiable
    }

    private static void defineCL650Command(Map<DCPCCPPanel.Event, Set<String>> map, DCPCCPPanel.Source source, DCPCCPPanel.Action action, String partialCommandName) {
        String addonPanelName;
        if (source.name().startsWith("CCP")) {
            addonPanelName = "CCP";
        } else if (source.name().startsWith("DCP")) {
            addonPanelName = "DCP";
        } else {
            throw new IllegalArgumentException("source is not mapped to addon panel name: " + source);
        }

        for (DCPCCPPanel.Side side : DCPCCPPanel.Side.values()) {
            String addonSideName = (side == DCPCCPPanel.Side.LEFT) ? "1" : "2";
            String commandName = "CL650/" + addonPanelName + "/" + addonSideName + "/" + partialCommandName;
            defineCommand(map, side, source, action, commandName);
        }
    }

    private static void defineCommand(Map<DCPCCPPanel.Event, Set<String>> map, DCPCCPPanel.Side side, DCPCCPPanel.Source source, DCPCCPPanel.Action action, String... commandNames) {
        map.computeIfAbsent(new DCPCCPPanel.Event(side, source, action).withoutTimestamp(), x -> new HashSet<>())
           .addAll(Arrays.asList(commandNames));
    }

    private XPlaneUDPClient(Properties config, SimulatorEventListener listener) {
        this.listener = listener;

        xplaneConnectionManager = new XPlaneConnectionManager(this);
        XPlaneDiscovery.getInstance().addListener(xplaneConnectionManager);
    }

    @Override
    public void disposeSimulatorClient() {
        xplaneConnectionManager.shutdown();
    }

    public void onDataSnapshot(DataReceiver.Snapshot data) {
        // TODO: delay updates if received too rapidly
        if (!Float.isNaN(data.cl650CCPBrightness)) {
            listener.onSimPanelBrightnessChanged(data.cl650CCPBrightness);
        }
    }

    @Override
    public void onPanelEvent(DCPCCPPanel.Event event) {
        synchronized (this) {
            if (xplane == null) {
                LOGGER.debug("not connected; ignoring {}", event);
                return;
            }

            Set<String> commands = COMMAND_NAMES.get(event.withoutTimestamp());
            if (commands == null || commands.isEmpty()) {
                LOGGER.debug("ignoring unmapped event {}", event);
                return;
            }

            if (commands.size() > 1) {
                LOGGER.warn("sending multiple commands to X-Plane at once; this may cause issues: {}", commands);
            } else {
                LOGGER.debug("sending command to X-Plane: {}", commands);
            }

            commands.forEach(xplane::sendCommand);
        }
    }

    @Override
    public PanelEventListener getPanelEventListener() {
        return this;
    }

    public void onXPlaneConnected(XPlane xplane) {
        synchronized (this) {
            this.xplane = xplane;
        }
    }

    public void onXPlaneDisconnected() {
        synchronized (this) {
            this.xplane = null;
        }
    }

    @AutoService(SimulatorClient.Factory.class)
    public static class Factory implements SimulatorClient.Factory {

        @Override
        public String getClientId() {
            return "xpudp";
        }

        @Override
        public String getClientName() {
            return "X-Plane via UDP";
        }

        @Override
        public Optional<SimulatorClient> createClient(Properties config, SimulatorEventListener listener) {
            return Optional.of(new XPlaneUDPClient(config, listener));
        }
    }
}
