package de.energiequant.limamf.connector.panels;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

import de.energiequant.limamf.compat.config.connector.ConfigItem;
import de.energiequant.limamf.compat.config.connector.ConnectorConfiguration;
import de.energiequant.limamf.compat.config.connector.Display;
import de.energiequant.limamf.compat.config.connector.EncoderSettings;
import de.energiequant.limamf.compat.config.connector.InputMultiplexerSettings;
import de.energiequant.limamf.compat.config.connector.OutputDisplay;
import de.energiequant.limamf.compat.config.connector.Settings;
import de.energiequant.limamf.compat.config.devices.DeviceConfiguration;
import de.energiequant.limamf.compat.config.devices.DeviceType;
import de.energiequant.limamf.compat.config.devices.InterfaceConfiguration;
import de.energiequant.limamf.compat.config.devices.OutputConfiguration;
import de.energiequant.limamf.compat.protocol.CommandMessage;
import de.energiequant.limamf.compat.protocol.ConfigurationInfoMessage;
import de.energiequant.limamf.compat.protocol.DigitalInputMultiplexerChangeMessage;
import de.energiequant.limamf.compat.protocol.EncoderChangeMessage;
import de.energiequant.limamf.compat.protocol.GetConfigMessage;
import de.energiequant.limamf.compat.protocol.SetPinMessage;
import de.energiequant.limamf.compat.utils.Maps;
import de.energiequant.limamf.compat.utils.Numbers;
import de.energiequant.limamf.connector.DeviceCommunicator;
import de.energiequant.limamf.connector.ModuleDiscovery;
import de.energiequant.limamf.connector.ModuleId;
import de.energiequant.limamf.connector.USBDevice;
import de.energiequant.limamf.connector.simulator.SimulatorEventListener;

public class DCPCCPPanel implements Panel {
    private static Logger LOGGER = LoggerFactory.getLogger(DCPCCPPanel.class);

    private final PanelEventListener recipient;
    private final SimulatorEventListener simulatorEventListener;

    private final String protocolVersion;

    private final DeviceCommunicator communicator;

    private final Map<String, Map<Integer, Set<Usage>>> digInMuxUsagesByNameAndPin = new HashMap<>();
    private final Map<String, Set<Usage>> encoderUsagesByName = new HashMap<>();
    private final Map<OutputUsage, Collection<OutputDisplay>> displayConfigurations = new HashMap<>();
    private final Map<OutputUsage, OutputPin> outputPins = new HashMap<>();

    private final AtomicReference<Side> selectedSide = new AtomicReference<>(Side.LEFT);

    private final AtomicReference<Instant> leftButtonPushed = new AtomicReference<>();
    private final AtomicReference<Instant> rightButtonPushed = new AtomicReference<>();

    private static final Duration LONG_PRESS_DURATION = Duration.ofMillis(250);

    private boolean useSimulatorBrightness = true;
    private int simulatorBrightness = 0;
    private int brightness = 0;
    private static final int MIN_BRIGHTNESS = 0;
    private static final int MAX_BRIGHTNESS = 250;

    private static final Source MANUAL_BRIGHTNESS_ENCODER = Source.CCP5_ROTARY_RADIO;
    private static final int MANUAL_BRIGHTNESS_STEP_SIZE = 5;

    private static final Map<String, Source> SOURCES_BY_BASE_NAME = Maps.<String, Source>createUnmodifiableHashMap(
        Maps.entry("DCP_MENU", Source.DCP1_MENU),
        Maps.entry("DCP_NAV_SRC", Source.DCP1_NAV_SRC),
        Maps.entry("DCP_FRMT", Source.DCP1_FRMT),
        Maps.entry("DCP_ESC", Source.DCP1_ESC),
        Maps.entry("CCP_LWR_FRMT", Source.CCP1_LWR_FRMT),
        Maps.entry("DCP_MENU-DATA_PUSH", Source.DCP1_MENU_SELECT),
        Maps.entry("DCP_TFC", Source.DCP1_TFC),
        Maps.entry("DCP_TR/WX", Source.DCP1_TR_WX),
        Maps.entry("DCP_BRG_SRC", Source.DCP1_BRG_SRC),
        Maps.entry("DCP_REFS", Source.DCP1_REFS),
        Maps.entry("DCP_RDR_MENU", Source.DCP1_RDR_MENU),
        Maps.entry("DCP_TILT-RANGE_PUSH", Source.DCP2_AUTO_TILT),
        Maps.entry("DCP_RADAR", Source.DCP2_RADAR),
        Maps.entry("CCP_UPR_MENU", Source.CCP1_UPR_MENU),
        Maps.entry("CCP_LWR_MENU", Source.CCP1_LWR_MENU),
        Maps.entry("CCP_SUMRY", Source.CCP4_SUMRY),
        Maps.entry("CCP_CAS", Source.CCP4_CAS),
        Maps.entry("CCP_ESC", Source.CCP1_ESC),
        Maps.entry("CCP_MENU-DATA_PUSH", Source.CCP1_MENU_SELECT),
        Maps.entry("CCP_TFC", Source.CCP1_TFC),
        Maps.entry("CCP_AC_ELEC", Source.CCP4_AC_ELEC),
        Maps.entry("CCP_DC_ELEC", Source.CCP4_DC_ELEC),
        Maps.entry("CCP_TR-WX", Source.CCP1_TR_WX),
        Maps.entry("CCP_HYD", Source.CCP4_HYD),
        Maps.entry("CCP_FLT", Source.CCP4_FLT),
        Maps.entry("CCP_MEM1", Source.CCP2_MEM1),
        Maps.entry("CCP_MEM2", Source.CCP2_MEM2),
        Maps.entry("CCP_MEM3", Source.CCP2_MEM3),
        Maps.entry("CCP_RADIO", Source.CCP5_RADIO),
        Maps.entry("CCP_CHART", Source.CCP3_CHART),
        Maps.entry("CCP_ROTATE", Source.CCP3_ROTATE),
        Maps.entry("CCP_ZOOM-", Source.CCP3_ZOOM),
        Maps.entry("CCP_ZOOM+", Source.CCP3_ZOOM),
        Maps.entry("CCP_FREQ", Source.CCP5_FREQ),
        Maps.entry("CCP_RADIO-DATA_PUSH", Source.CCP5_RADIO_SELECT),
        Maps.entry("CCP_JSTK", Source.CCP3_JSTK),
        Maps.entry("CCP_JSTK_UP", Source.CCP3_CURSOR),
        Maps.entry("CCP_JSTK_LEFT", Source.CCP3_CURSOR),
        Maps.entry("CCP_JSTK_DOWN", Source.CCP3_CURSOR),
        Maps.entry("CCP_JSTK_RIGHT", Source.CCP3_CURSOR),
        // unmapped: CCP_JSTK_PUSH
        Maps.entry("CCP_1-2", Source.CCP5_1_2),
        Maps.entry("CCP_DME-H", Source.CCP5_DME_H),
        Maps.entry("CCP_IDENT", Source.CCP5_IDENT),
        Maps.entry("CCP_ATC", Source.CCP5_ATC),
        Maps.entry("DCP_MENU-DATA_OUTER", Source.DCP1_ROTARY_MENU),
        Maps.entry("DCP_MENU-DATA_INNER", Source.DCP1_ROTARY_DATA),
        Maps.entry("DCP_TILT-RANGE_OUTER", Source.DCP2_ROTARY_TILT),
        Maps.entry("DCP_TILT-RANGE_INNER", Source.DCP2_ROTARY_RANGE),
        Maps.entry("CCP_MENU-DATA_OUTER", Source.CCP1_ROTARY_MENU),
        Maps.entry("CCP_MENU-DATA_INNER", Source.CCP1_ROTARY_DATA),
        Maps.entry("CCP_RADIO-DATA_OUTER", Source.CCP5_ROTARY_RADIO),
        Maps.entry("CCP_RADIO-DATA_INNER", Source.CCP5_ROTARY_DATA)
    );

    public enum Side {
        LEFT,
        RIGHT;
    }

    public enum Source {
        // numbers refer to sub-panels
        DCP1_MENU,
        DCP1_ESC,
        DCP1_NAV_SRC,
        DCP1_FRMT,
        DCP1_TFC,
        DCP1_TR_WX,
        DCP1_BRG_SRC,
        DCP1_REFS,
        DCP1_RDR_MENU,
        DCP1_MENU_SELECT, // encoder push
        DCP1_ROTARY_MENU, // outer
        DCP1_ROTARY_DATA, // inner

        DCP2_RADAR,
        DCP2_AUTO_TILT, // encoder push
        DCP2_ROTARY_TILT, // outer
        DCP2_ROTARY_RANGE, // inner

        CCP1_UPR_MENU,
        CCP1_LWR_MENU,
        CCP1_ESC,
        CCP1_LWR_FRMT,
        CCP1_TFC,
        CCP1_TR_WX,
        CCP1_MENU_SELECT, // encoder push
        CCP1_ROTARY_MENU, // outer
        CCP1_ROTARY_DATA, // inner

        CCP2_MEM1,
        CCP2_MEM2,
        CCP2_MEM3,

        CCP3_CHART,
        CCP3_JSTK, // button
        CCP3_ROTATE,
        CCP3_ZOOM, // wipe
        CCP3_CURSOR, // joystick

        CCP4_SUMRY,
        CCP4_AC_ELEC,
        CCP4_DC_ELEC,
        CCP4_HYD,
        CCP4_FLT,
        CCP4_CAS,

        CCP5_RADIO,
        CCP5_FREQ,
        CCP5_1_2,
        CCP5_DME_H,
        CCP5_IDENT,
        CCP5_ATC,
        CCP5_RADIO_SELECT, // encoder push
        CCP5_ROTARY_RADIO, // outer
        CCP5_ROTARY_DATA; // inner
    }

    private enum InternalSource {
        SELECT_LEFT,
        SELECT_RIGHT;
    }

    public enum Action {
        NEUTRAL,
        PUSH,
        INCREMENT,
        DECREMENT,
        LEFT,
        RIGHT,
        UP,
        DOWN;
    }

    private enum HoldDuration {
        NOT_PRESSED,
        SHORT_PRESS,
        LONG_PRESS;
    }

    public static class Event {
        private final Instant timestamp;
        private final Side side;
        private final Source source;
        private final Action action;

        public Event(Source source, Action action) {
            this(null, null, source, action);
        }

        public Event(Instant timestamp, Source source, Action action) {
            this(timestamp, null, source, action);
        }

        public Event(Side side, Source source, Action action) {
            this(null, side, source, action);
        }

        public Event(Instant timestamp, Side side, Source source, Action action) {
            this.timestamp = timestamp;
            this.side = side;
            this.source = source;
            this.action = action;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public Optional<Side> getSide() {
            return Optional.ofNullable(side);
        }

        public Source getSource() {
            return source;
        }

        public Action getAction() {
            return action;
        }

        public Event withoutTimestamp() {
            return new Event(null, side, source, action);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Event(");

            sb.append(timestamp);

            if (side != null) {
                sb.append(", ");
                sb.append(side);
            }

            sb.append(", ");
            sb.append(source);

            sb.append(", ");
            sb.append(action);

            sb.append(")");

            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Event)) {
                return false;
            }

            Event other = (Event) obj;

            return this.source == other.source
                && this.side == other.side
                && this.action == other.action
                && Objects.equals(this.timestamp, other.timestamp);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timestamp, side, source, action);
        }
    }

    private static class Usage {
        private final String name;
        private final Side side;
        private final Action action;
        private final Source source;
        private final InternalSource internalSource;

        private Usage(String name, Side side, Action action, Source source, InternalSource internalSource) {
            this.name = name;
            this.side = side;
            this.action = action;
            this.source = source;
            this.internalSource = internalSource;

            if ((source == null) == (internalSource == null)) {
                throw new IllegalArgumentException("usage must be either internally or externally visible source");
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Usage)) {
                return false;
            }

            Usage other = (Usage) obj;

            return Objects.equals(this.name, other.name)
                && Objects.equals(this.side, other.side)
                && Objects.equals(this.source, other.source)
                && Objects.equals(this.internalSource, other.internalSource);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, side, source, internalSource);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Usage(");

            boolean isFirst = true;

            if (name != null) {
                sb.append("name=\"");
                sb.append(name);
                sb.append("\"");
                isFirst = false;
            }

            if (side != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("side=");
                sb.append(side);
                isFirst = false;
            }

            if (action != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("action=");
                sb.append(action);
                isFirst = false;
            }

            if (source != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("source=");
                sb.append(source);
                isFirst = false;
            }

            if (internalSource != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("internalSource=");
                sb.append(internalSource);
            }

            sb.append(")");

            return sb.toString();
        }
    }

    private enum OutputUsage {
        BACKLIGHT(true, "BACKLIGHT_0_MANUAL", "BACKLIGHT_1_IN-SIM"),
        LEFT_INDICATOR(false, "LED_LEFT_YELLOW", "LED_LEFT_BLINK"),
        RIGHT_INDICATOR(false, "LED_RIGHT_GREEN", "LED_RIGHT_BLINK");

        private final boolean pwm;
        private final Set<String> configItemNames;

        private static final Map<String, OutputUsage> BY_CONFIG_ITEM_NAME;

        static {
            Map<String, OutputUsage> byConfigItemName = new HashMap<>();
            for (OutputUsage usage : values()) {
                for (String name : usage.configItemNames) {
                    OutputUsage previous = byConfigItemName.put(name, usage);
                    if (previous != null) {
                        throw new IllegalArgumentException("collision: name \"" + name + "\" has multiple usages: " + previous + ", " + usage);
                    }
                }
            }
            BY_CONFIG_ITEM_NAME = Collections.unmodifiableMap(byConfigItemName);
        }

        OutputUsage(boolean pwm, String... configItemNames) {
            this.pwm = pwm;
            this.configItemNames = new HashSet<>(Arrays.asList(configItemNames));
        }

        private static Optional<OutputUsage> resolve(String configItemName) {
            return Optional.ofNullable(BY_CONFIG_ITEM_NAME.get(configItemName));
        }
    }

    private static class OutputPin {
        private final int pin;
        private final boolean pwm;
        private final int maxValue;

        OutputPin(int pin, boolean pwm, int maxValue) {
            this.pin = pin;
            this.pwm = pwm;
            this.maxValue = maxValue;

            if (!pwm && SetPinMessage.isDigitalState(maxValue)) {
                throw new IllegalArgumentException("max value has to represent a digital state unless PWM is allowed, got " + maxValue);
            }
        }

        @Override
        public String toString() {
            return "OutputPin(" + pin + ", " + (pwm ? "PWM" : "digital") + ", max=" + maxValue + ")";
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof OutputPin)) {
                return false;
            }

            OutputPin other = (OutputPin) obj;

            return this.pin == other.pin
                && this.pwm == other.pwm
                && this.maxValue == other.maxValue;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pin, pwm, maxValue);
        }
    }

    private DCPCCPPanel(PanelEventListener recipient, ModuleDiscovery.ConnectedModule module, ConnectorConfiguration connectorConfiguration, String connectorConfigurationSerial) {
        this.recipient = recipient;
        this.protocolVersion = module.getVersion();

        USBDevice usbDevice = module.getUSBDevice();

        simulatorEventListener = new SimulatorEventListener.Adapter() {
            @Override
            public void onSimPanelBrightnessChanged(double fraction) {
                setSimulatorBrightness(fraction);
            }
        };

        for (ConfigItem input : connectorConfiguration.getItems(ConfigItem.Direction.INPUT)) {
            if (!input.isActive()) {
                continue;
            }

            Settings settings = input.getSettings();
            if (settings instanceof InputMultiplexerSettings) {
                indexConfigItem(input, connectorConfigurationSerial, (InputMultiplexerSettings) settings);
            } else if (settings instanceof EncoderSettings) {
                indexConfigItem(input, connectorConfigurationSerial, (EncoderSettings) settings);
            }
        }
        for (ConfigItem output : connectorConfiguration.getItems(ConfigItem.Direction.OUTPUT)) {
            if (!output.isActive()) {
                continue;
            }

            Settings settings = output.getSettings();
            Display display = settings.getDisplay().orElse(null);
            if (display instanceof OutputDisplay) {
                indexConfigItem(output, connectorConfigurationSerial, (OutputDisplay) display);
            }
        }
        if (!checkConsistentDisplayConfigurations()) {
            throw new IllegalArgumentException("invalid display configuration");
        }

        ModuleId moduleId = module.getModuleId();
        LOGGER.info("Connecting to {} ({}, serial {}, protocol {})", usbDevice, moduleId.getName(), moduleId.getSerial(), protocolVersion);

        File deviceNode = usbDevice.getDeviceNode().orElseThrow(() -> new IllegalArgumentException("no device node"));
        communicator = new DeviceCommunicator(deviceNode, protocolVersion, this::onCommandMessage);

        communicator.send(new GetConfigMessage());
    }

    private boolean checkConsistentDisplayConfigurations() {
        for (Map.Entry<OutputUsage, Collection<OutputDisplay>> entry : displayConfigurations.entrySet()) {
            OutputUsage usage = entry.getKey();

            Iterator<OutputDisplay> it = entry.getValue().iterator();
            OutputDisplay first = it.next();
            while (it.hasNext()) {
                OutputDisplay config = it.next();
                if (!config.getPin().equals(first.getPin())) {
                    LOGGER.warn("Pin does not match for {}: {}, {}", usage, first, config);
                    return false;
                }
                if (config.getPinBrightness() != first.getPinBrightness()) {
                    LOGGER.warn("Brightness does not match for {}: {}, {}", usage, first, config);
                    return false;
                }
                if (config.usePWM() != first.usePWM()) {
                    LOGGER.warn("PWM does not match for {}: {}, {}", usage, first, config);
                    return false;
                }
            }

            LOGGER.debug("consistency checked OK for host-side configuration of display output {} ({} config items)", usage, entry.getValue().size());
        }

        return true;
    }

    private void indexConfigItem(ConfigItem output, String interfaceSerialId, OutputDisplay display) {
        if (!interfaceSerialId.equals(display.getSerial().orElseThrow(() -> new IllegalArgumentException("display without serial")))) {
            return;
        }

        String description = output.getDescription();
        OutputUsage usage = OutputUsage.resolve(description).orElse(null);
        if (usage == null) {
            LOGGER.debug("ignoring unhandled config item description: \"{}\"", description);
            return;
        }

        displayConfigurations.computeIfAbsent(usage, x -> new ArrayList<>())
                             .add(display);
    }

    @Override
    public Optional<SimulatorEventListener> getSimulatorEventListener() {
        return Optional.of(simulatorEventListener);
    }

    @Override
    public void disconnect() {
        try {
            synchronized (this) {
                brightness = MIN_BRIGHTNESS;
                simulatorBrightness = MIN_BRIGHTNESS;
            }
            submitBrightness();

            selectedSide.set(null);
            submitIndication();

            // wait a short moment for messages to get sent
            // TODO: add capability to flush queue with a timeout instead of just waiting here
            Thread.sleep(20);
        } catch (Exception ex) {
            LOGGER.warn("failed to turn off LEDs on panel", ex);
        }

        communicator.shutdownAsync();
    }

    public void selectSide(Side side) {
        selectedSide.set(side);
        submitIndication();
    }

    private Usage resolveUsage(String description) {
        if ("RIGHT_BUTTON".equals(description)) {
            return new Usage(description, null, null, null, InternalSource.SELECT_RIGHT);
        } else if ("LEFT_BUTTON".equals(description)) {
            return new Usage(description, null, null, null, InternalSource.SELECT_LEFT);
        }

        Side side = null;
        Action action = null;

        String baseName = description;
        if (description.startsWith("L_")) {
            side = Side.LEFT;
            baseName = description.substring(2);
        } else if (description.startsWith("R_")) {
            side = Side.RIGHT;
            baseName = description.substring(2);
        }

        Source source = SOURCES_BY_BASE_NAME.get(baseName);

        if (source == Source.CCP3_CURSOR) {
            if (baseName.endsWith("_LEFT")) {
                action = Action.LEFT;
            } else if (baseName.endsWith("_RIGHT")) {
                action = Action.RIGHT;
            } else if (baseName.endsWith("_UP")) {
                action = Action.UP;
            } else if (baseName.endsWith("_DOWN")) {
                action = Action.DOWN;
            } else {
                LOGGER.debug("unknown cursor direction in \"{}\"", baseName);
                source = null;
            }
        } else if (source == Source.CCP3_ZOOM) {
            if (baseName.endsWith("-")) {
                action = Action.DECREMENT;
            } else if (baseName.endsWith("+")) {
                action = Action.INCREMENT;
            } else {
                LOGGER.debug("unknown zoom action in \"{}\"", baseName);
                source = null;
            }
        }

        if (source == null) {
            return null;
        }

        return new Usage(description, side, action, source, null);
    }

    private void indexConfigItem(ConfigItem input, String interfaceSerialId, InputMultiplexerSettings settings) {
        if (!interfaceSerialId.equals(settings.getSerial().orElseThrow(() -> new IllegalArgumentException("input multiplexer without serial")))) {
            return;
        }

        String description = input.getDescription();
        Usage usage = resolveUsage(description);
        if (usage == null) {
            LOGGER.debug("ignoring unhandled config item description: \"{}\"", description);
            return;
        }

        digInMuxUsagesByNameAndPin.computeIfAbsent(settings.getMultiplexerName(), x -> new HashMap<>())
                                  .computeIfAbsent(settings.getDataPin(), x -> new HashSet<>())
                                  .add(usage);
    }

    private void indexConfigItem(ConfigItem input, String interfaceSerialId, EncoderSettings settings) {
        if (!interfaceSerialId.equals(settings.getSerial().orElseThrow(() -> new IllegalArgumentException("encoder without serial")))) {
            return;
        }

        String description = input.getDescription();
        Usage usage = resolveUsage(description);
        if (usage == null) {
            LOGGER.debug("ignoring unhandled config item description: \"{}\"", description);
            return;
        }

        encoderUsagesByName.computeIfAbsent(settings.getEncoderName(), x -> new HashSet<>())
                           .add(usage);
    }

    private void onCommandMessage(DeviceCommunicator communicator, CommandMessage msg) {
        LOGGER.debug("onCommandMessage {}", msg);

        if (msg instanceof ConfigurationInfoMessage) {
            onCommandMessage((ConfigurationInfoMessage) msg);
        } else if (msg instanceof DigitalInputMultiplexerChangeMessage) {
            onCommandMessage((DigitalInputMultiplexerChangeMessage) msg);
        } else if (msg instanceof EncoderChangeMessage) {
            onCommandMessage((EncoderChangeMessage) msg);
        } else {
            LOGGER.error("unhandled message; panicking: {}", msg);
            communicator.shutdownAsync();
            System.exit(1);
        }
    }

    private void onCommandMessage(ConfigurationInfoMessage msg) {
        Map<String, Integer> outputPinsByName = new HashMap<>();

        InterfaceConfiguration configuration = msg.getConfiguration();
        for (DeviceConfiguration device : configuration.getDevices()) {
            if (device.getType() != DeviceType.OUTPUT) {
                continue;
            }

            String name = device.getName();
            Integer previous = outputPinsByName.put(name, ((OutputConfiguration) device).getPin());
            if (previous != null) {
                LOGGER.error("duplicate output pin configuration for {}; panicking: {}", name, msg);
                communicator.shutdownAsync();
                System.exit(1);
            }
        }

        Map<OutputUsage, OutputPin> newOutputPins = new HashMap<>();

        for (Map.Entry<OutputUsage, Collection<OutputDisplay>> entry : displayConfigurations.entrySet()) {
            OutputUsage usage = entry.getKey();
            OutputDisplay hostSideConfig = entry.getValue().iterator().next();
            Integer deviceSidePin = outputPinsByName.get(hostSideConfig.getPin());
            if (deviceSidePin == null) {
                LOGGER.error("Host-side configured pin \"{}\" does not exist on device, config has to be consistent; panicking: {}", hostSideConfig.getPin(), msg);
                communicator.shutdownAsync();
                System.exit(1);
            }

            OutputPin outputPin = new OutputPin(deviceSidePin, hostSideConfig.usePWM(), hostSideConfig.getPinBrightness());
            LOGGER.debug("Assigning output {}: {}", usage, outputPin);
            OutputPin previous = newOutputPins.put(usage, outputPin);
            if (previous != null) {
                LOGGER.error("Output pin for {} was recorded multiple times; {}, {}; panicking: {}", usage, previous, outputPin, msg);
                communicator.shutdownAsync();
                System.exit(1);
            }
        }

        synchronized (outputPins) {
            if (outputPins.isEmpty()) {
                LOGGER.debug("Activating new output pin configuration.");
                outputPins.putAll(newOutputPins);
            } else if (outputPins.equals(newOutputPins)) {
                LOGGER.debug("Output pins have already been recorded, no change.");
            } else {
                LOGGER.error("Output pins have been recorded previously and differ; panicking: {}", msg);
                communicator.shutdownAsync();
                System.exit(1);
            }
        }

        submitIndication();
        submitBrightness();
    }

    private void onCommandMessage(DigitalInputMultiplexerChangeMessage msg) {
        DigitalInputMultiplexerChangeMessage.Event muxEvent = msg.getEvent();

        LOGGER.debug("onCommandMessage received DigInMux {} {}", muxEvent, msg);

        Side side = selectedSide.get();

        Collection<Usage> usages = digInMuxUsagesByNameAndPin.getOrDefault(msg.getName(), Collections.emptyMap())
                                                             .getOrDefault(msg.getChannel(), Collections.emptySet())
                                                             .stream()
                                                             .filter(x -> (x.side == null) || (x.side == side))
                                                             .collect(Collectors.toList());

        if (usages.isEmpty()) {
            LOGGER.debug("ignoring unmapped input: {} {}", muxEvent, msg);
            return;
        }

        if (usages.size() > 1) {
            LOGGER.debug("ignoring ambiguous input: {} {}", muxEvent, usages, msg);
            return;
        }

        Usage usage = usages.iterator().next();

        Action action = null;
        if (!muxEvent.isActive()) {
            action = Action.NEUTRAL;
        } else {
            action = (usage.action != null) ? usage.action : Action.PUSH;
        }

        if (usage.internalSource != null) {
            handleInternalEvent(usage.internalSource, action);
            return;
        }

        Event event = null;
        if (usage.side == null) {
            event = new Event(Instant.now(), usage.source, action);
        } else {
            event = new Event(Instant.now(), side, usage.source, action);
        }

        LOGGER.debug("event: {}", event);
        recipient.onPanelEvent(event);
    }

    private void onCommandMessage(EncoderChangeMessage msg) {
        LOGGER.debug("onCommandMessage received Encoder {}", msg);

        Side side = selectedSide.get();

        Collection<Usage> usages = encoderUsagesByName.getOrDefault(msg.getName(), Collections.emptySet())
                                                      .stream()
                                                      .filter(x -> (x.side == null) || (x.side == side))
                                                      .collect(Collectors.toList());

        if (usages.isEmpty()) {
            LOGGER.debug("ignoring unmapped input: {}", msg);
            return;
        }

        if (usages.size() > 1) {
            LOGGER.debug("ignoring ambiguous input: {} {}", usages, msg);
            return;
        }

        Usage usage = usages.iterator().next();

        if (usage.action != null) {
            LOGGER.warn("encoder usage cannot have predefined action, ignoring: {}", usage, msg);
            return;
        }

        Action action = msg.getEvent().isClockwise() ? Action.INCREMENT : Action.DECREMENT;

        if (usage.internalSource != null) {
            handleInternalEvent(usage.internalSource, action);
            return;
        }

        // while left button is held divert encoder used for manual brightness control to internal handling
        if (usage.source == MANUAL_BRIGHTNESS_ENCODER && leftButtonPushed.get() != null) {
            handleInternalEvent(usage.source, action);
            return;
        }

        Event event = null;
        if (usage.side == null) {
            event = new Event(Instant.now(), usage.source, action);
        } else {
            event = new Event(Instant.now(), side, usage.source, action);
        }

        LOGGER.debug("event: {}", event);
        recipient.onPanelEvent(event);
    }

    private HoldDuration getHoldDuration(AtomicReference<Instant> pushed) {
        Instant heldSince = pushed.get();
        if (heldSince == null) {
            return HoldDuration.NOT_PRESSED;
        }

        Duration duration = Duration.between(heldSince, Instant.now());
        return duration.compareTo(LONG_PRESS_DURATION) > 0 ? HoldDuration.LONG_PRESS : HoldDuration.SHORT_PRESS;
    }

    private void handleInternalEvent(InternalSource internalSource, Action action) {
        LOGGER.debug("internal event: {} {}", action, internalSource);

        if (internalSource == InternalSource.SELECT_LEFT) {
            if (action == Action.PUSH) {
                leftButtonPushed.set(Instant.now());
            } else if (action == Action.NEUTRAL) {
                HoldDuration holdDuration = getHoldDuration(leftButtonPushed);
                leftButtonPushed.set(null);

                if (holdDuration == HoldDuration.SHORT_PRESS) {
                    LOGGER.debug("emitting events for left-side panels");
                    selectSide(Side.LEFT);
                }
            }
        } else if (internalSource == InternalSource.SELECT_RIGHT) {
            if (action == Action.PUSH) {
                rightButtonPushed.set(Instant.now());
            } else if (action == Action.NEUTRAL) {
                HoldDuration holdDuration = getHoldDuration(rightButtonPushed);
                rightButtonPushed.set(null);

                if (holdDuration == HoldDuration.SHORT_PRESS) {
                    LOGGER.debug("emitting events for right-side panels");
                    selectSide(Side.RIGHT);
                } else if (holdDuration == HoldDuration.LONG_PRESS) {
                    // return to simulator brightness control
                    synchronized (this) {
                        LOGGER.debug("restore simulator brightness control: {}", simulatorBrightness);
                        useSimulatorBrightness = true;
                        brightness = simulatorBrightness;
                        submitBrightness();
                    }
                }
            }
        }
    }

    private void handleInternalEvent(Source source, Action action) {
        LOGGER.debug("internal event: {} {}", action, source);

        if (source == MANUAL_BRIGHTNESS_ENCODER && leftButtonPushed.get() != null) {
            synchronized (this) {
                // take control from simulator
                useSimulatorBrightness = false;

                int change = (action == Action.INCREMENT) ? MANUAL_BRIGHTNESS_STEP_SIZE : -MANUAL_BRIGHTNESS_STEP_SIZE;
                brightness = Numbers.limit(brightness + change, MIN_BRIGHTNESS, MAX_BRIGHTNESS);

                LOGGER.debug("manual brightness: {} => {}", action, brightness);
                submitBrightness();
            }
        }
    }

    private void submitIndication() {
        synchronized (this) {
            Side side = selectedSide.get(); // may be null (disconnect => off)

            setPin(OutputUsage.LEFT_INDICATOR, side == Side.LEFT);
            setPin(OutputUsage.RIGHT_INDICATOR, side == Side.RIGHT);
        }
    }

    private void setPin(OutputUsage usage, boolean state) {
        OutputPin pin;
        synchronized (outputPins) {
            pin = outputPins.get(usage);
        }

        if (pin == null) {
            LOGGER.debug("tried to manipulate unknown pin for {} => {}", usage, state);
            return;
        }

        SetPinMessage msg = SetPinMessage.builder()
                                         .manipulatingPin(pin.pin)
                                         .setDigitalState(state)
                                         .build();

        LOGGER.debug("Manipulating output {} => {}: {}", usage, state, msg);
        communicator.send(msg);
    }

    private void setPin(OutputUsage usage, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be positive; got " + value);
        }

        OutputPin pin;
        synchronized (outputPins) {
            pin = outputPins.get(usage);
        }

        if (pin == null) {
            LOGGER.debug("tried to manipulate unknown pin for {} => {}", usage, value);
            return;
        }

        if (value > pin.maxValue) {
            throw new IllegalArgumentException("value " + value + " exceeds maximum: " + pin);
        }

        SetPinMessage msg = SetPinMessage.builder()
                                         .manipulatingPin(pin.pin)
                                         .setPwmDutyCycleValue(value)
                                         .build();

        LOGGER.debug("Manipulating output {} => {}: {}", usage, value, msg);
        communicator.send(msg);
    }

    private void submitBrightness() {
        synchronized (this) {
            int value = Numbers.limit(brightness, MIN_BRIGHTNESS, MAX_BRIGHTNESS);
            setPin(OutputUsage.BACKLIGHT, value);
        }
    }

    public void setSimulatorBrightness(double fraction) {
        int value = Numbers.limit(Math.round((fraction * (MAX_BRIGHTNESS - MIN_BRIGHTNESS)) + MIN_BRIGHTNESS), MIN_BRIGHTNESS, MAX_BRIGHTNESS);

        synchronized (this) {
            LOGGER.debug("simulator brightness: {} => {}", fraction, value);
            simulatorBrightness = value;

            if (useSimulatorBrightness) {
                brightness = simulatorBrightness;
                submitBrightness();
            }
        }
    }

    @AutoService(Panel.Factory.class)
    public static class Factory implements Panel.Factory {
        @Override
        public String getId() {
            return "limamf.AvioniqueSimulationCL650DCPCCP";
        }

        @Override
        public String getName() {
            return "Avionique Simulation CL650 DCP/CCP";
        }

        @Override
        public Panel create(PanelEventListener eventListener, ModuleDiscovery.ConnectedModule module, ConnectorConfiguration connectorConfiguration, String connectorConfigurationSerial) {
            return new DCPCCPPanel(eventListener, module, connectorConfiguration, connectorConfigurationSerial);
        }
    }
}
