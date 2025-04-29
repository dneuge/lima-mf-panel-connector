package de.energiequant.limamf.connector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.connector.utils.ExternalCommand;

public class UDevAdmWrapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(UDevAdmWrapper.class);

    private final ExternalCommand udevadm;
    private static final String DEV_PATH = "/dev/";
    private static final String SYS_PATH = "/sys/";

    private static final Pattern PATTERN_MONITOR = Pattern.compile("^(UDEV|KERNEL)\\s*\\[\\d+\\.\\d+]\\s+(\\S+)\\s+(/.*?)\\s+\\(([^)]+)\\)$");
    private static final int PATTERN_MONITOR_SOURCE = 1;
    private static final int PATTERN_MONITOR_EVENT_TYPE = 2;
    private static final int PATTERN_MONITOR_PATH = 3;
    private static final int PATTERN_MONITOR_SUBSYSTEM = 4;

    private static final String DEVICE_NAME_PROPERTY = "DEVNAME";

    public enum DeviceEventSource {
        KERNEL("--kernel"),
        UDEV("--udev");

        private final String monitorParameter;

        private static final Map<String, DeviceEventSource> INDEX = new HashMap<>();

        static {
            for (DeviceEventSource type : DeviceEventSource.values()) {
                INDEX.put(type.name().toLowerCase(), type);
            }
        }

        DeviceEventSource(String monitorParameter) {
            this.monitorParameter = monitorParameter;
        }

        private static Optional<DeviceEventSource> resolve(String monitorSourceName) {
            return Optional.ofNullable(INDEX.get(monitorSourceName.toLowerCase()));
        }
    }

    public enum DeviceEventType {
        ADD,
        REMOVE,
        CHANGE,
        MOVE,
        BIND,
        UNBIND,
        ONLINE,
        OFFLINE;

        private static final Map<String, DeviceEventType> INDEX = new HashMap<>();

        static {
            for (DeviceEventType type : DeviceEventType.values()) {
                INDEX.put(type.name().toLowerCase(), type);
            }
        }

        private static Optional<DeviceEventType> resolve(String monitorEventName) {
            return Optional.ofNullable(INDEX.get(monitorEventName.toLowerCase()));
        }
    }

    public static class DeviceEvent {
        private final DeviceEventSource source;
        private final DeviceEventType type;
        private final List<String> path;
        private final String subsystem;
        private final DeviceInformation info;

        private DeviceEvent(DeviceEventSource source, DeviceEventType type, List<String> path, String subsystem, DeviceInformation info) {
            this.source = source;
            this.type = type;
            this.path = path;
            this.subsystem = subsystem;
            this.info = info;
        }

        public DeviceEventSource getSource() {
            return source;
        }

        public DeviceEventType getType() {
            return type;
        }

        public List<String> getPath() {
            return path;
        }

        public String getSubsystem() {
            return subsystem;
        }

        public DeviceInformation getInfo() {
            return info;
        }

        private static DeviceEvent fromMonitorOutput(Collection<String> lines) {
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("no lines provided");
            }

            Deque<String> remainingLines = new LinkedList<>(lines);
            String header = remainingLines.removeFirst();

            Matcher matcher = PATTERN_MONITOR.matcher(header);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("unexpected event header: \"" + header + "\"");
            }

            return new DeviceEvent(
                DeviceEventSource.resolve(matcher.group(PATTERN_MONITOR_SOURCE))
                                 .orElseThrow(() -> new IllegalArgumentException("unsupported event source: \"" + header + "\"")),
                DeviceEventType.resolve(matcher.group(PATTERN_MONITOR_EVENT_TYPE))
                               .orElseThrow(() -> new IllegalArgumentException("unsupported event type: \"" + header + "\"")),
                Arrays.asList(matcher.group(PATTERN_MONITOR_PATH).substring(1).split("/")),
                matcher.group(PATTERN_MONITOR_SUBSYSTEM),
                DeviceInformation.fromMonitorOutput(remainingLines)
            );
        }

        @Override
        public String toString() {
            return "DeviceEvent("
                + source + ", " + type + ", "
                + subsystem + ", " + info + ", "
                + "/" + String.join("/", path)
                + ")";
        }
    }

    public static class DeviceInformation {
        private final String kernelDeviceNodeName;
        private final Map<String, String> properties;

        private DeviceInformation(String kernelDeviceNodeName, Map<String, String> properties) {
            this.kernelDeviceNodeName = kernelDeviceNodeName;
            this.properties = Collections.unmodifiableMap(properties);
        }

        public Optional<String> getKernelDeviceNodeName() {
            return Optional.ofNullable(kernelDeviceNodeName);
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        private static DeviceInformation fromInfoOutput(Collection<String> lines) {
            Map<String, String> properties = new HashMap<>();

            String kernelDeviceNodeName = null;

            for (String line : lines) {
                if (line.startsWith("N: ")) {
                    kernelDeviceNodeName = line.substring(3);
                } else if (line.startsWith("E: ")) {
                    String[] tmp = line.substring(3).split("=", 2);
                    if (tmp.length != 2) {
                        LOGGER.debug("unexpected syntax for device property: \"" + line + "\"");
                        continue;
                    }

                    properties.put(tmp[0], tmp[1]);
                }
            }

            return new DeviceInformation(kernelDeviceNodeName, properties);
        }

        private static DeviceInformation fromMonitorOutput(Collection<String> lines) {
            Map<String, String> properties = new HashMap<>();

            for (String line : lines) {
                String[] tmp = line.split("=", 2);
                if (tmp.length != 2) {
                    LOGGER.debug("unexpected syntax for device property: \"" + line + "\"");
                    continue;
                }

                properties.put(tmp[0], tmp[1]);
            }

            String devName = properties.get(DEVICE_NAME_PROPERTY);
            if (devName == null) {
                throw new IllegalArgumentException("property " + DEVICE_NAME_PROPERTY + " is missing");
            } else if (!devName.startsWith(DEV_PATH)) {
                throw new IllegalArgumentException(
                    "property " + DEVICE_NAME_PROPERTY + " must start with \"" + DEV_PATH
                        + "\" but is \"" + devName + "\""
                );
            }

            String kernelDeviceNodeName = devName.substring(DEV_PATH.length());
            if (kernelDeviceNodeName.isEmpty()) {
                throw new IllegalArgumentException("property " + DEVICE_NAME_PROPERTY + " has unexpected value: \"" + devName + "\"");
            }

            return new DeviceInformation(kernelDeviceNodeName, properties);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("DeviceInformation(");

            sb.append(kernelDeviceNodeName);

            properties.entrySet()
                      .stream()
                      .sorted(Map.Entry.comparingByKey())
                      .forEach(entry -> {
                          sb.append(", ");
                          sb.append(entry.getKey());
                          sb.append("=\"");
                          sb.append(entry.getValue());
                          sb.append("\"");
                      });

            sb.append(")");

            return sb.toString();
        }
    }

    public UDevAdmWrapper() {
        udevadm = ExternalCommand.locateFromPaths("udevadm")
                                 .orElseThrow(() -> new MissingTool("udevadm is required for device discovery"));
    }

    public DeviceInformation info(File sysNode) {
        String canonicalPath;
        try {
            canonicalPath = sysNode.getCanonicalPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("given node does not yield a canonical path: " + sysNode, ex);
        }

        if (!canonicalPath.startsWith(SYS_PATH)) {
            throw new IllegalArgumentException("given node does not yield a canonical path starting with " + SYS_PATH + ":" + sysNode);
        }

        ExternalCommand.Result res = udevadm.run("info", "-p", canonicalPath.substring(SYS_PATH.length()));
        return DeviceInformation.fromInfoOutput(res.getStandardOutputLines());
    }

    public class Monitor {
        private final DeviceEventSource source;
        private final String subsystem;
        private final Consumer<DeviceEvent> callback;
        private final Process process;

        private final List<String> lines = new ArrayList<>();
        private boolean headerReceived;

        private static final String SUBSYSTEM_VALID_CHARS = "abcdefghijklmnopqrstuvwxyz";

        private Monitor(DeviceEventSource source, String subsystem, Consumer<DeviceEvent> callback) {
            this.source = source;
            this.subsystem = subsystem;
            this.callback = callback;

            List<String> parameters = new ArrayList<>();
            parameters.add("monitor");
            parameters.add("-p");

            if (source != null) {
                parameters.add(source.monitorParameter);
            }

            if (subsystem != null) {
                if (subsystem.isEmpty()) {
                    throw new IllegalArgumentException("subsystem parameter must not be empty if present");
                } else if (!StringUtils.containsOnly(subsystem, SUBSYSTEM_VALID_CHARS)) {
                    throw new IllegalArgumentException("Invalid characters used in subsystem parameter: \"" + subsystem + "\"");
                }

                parameters.add("-s");
                parameters.add(subsystem);
            }

            process = udevadm.monitorLines(parameters, this::onMonitorLine);
        }

        private void onMonitorLine(String line) {
            LOGGER.trace("received monitor line: {}", line);

            if (!line.isEmpty()) {
                lines.add(line);
                return;
            }

            if (!headerReceived) {
                LOGGER.trace("monitor header received");
                headerReceived = true;
                lines.clear();
                return;
            }

            DeviceEvent event = null;
            try {
                event = DeviceEvent.fromMonitorOutput(lines);
            } catch (Exception ex) {
                LOGGER.debug("Unparseable information from udevadm monitor, ignoring: {}", lines, ex);
            }

            lines.clear();

            if (event == null) {
                return;
            }

            try {
                callback.accept(event);
            } catch (Exception ex) {
                LOGGER.warn("udevadm monitor callback failed on {}", event, ex);
            }
        }

        public void terminate() {
            process.destroyForcibly();
        }
    }

    public Monitor monitor(Consumer<DeviceEvent> callback) {
        return monitor(null, null, callback);
    }

    public Monitor monitor(DeviceEventSource source, Consumer<DeviceEvent> callback) {
        return monitor(source, null, callback);
    }

    public Monitor monitor(String subsystem, Consumer<DeviceEvent> callback) {
        return monitor(null, subsystem, callback);
    }

    public Monitor monitor(DeviceEventSource source, String subsystem, Consumer<DeviceEvent> callback) {
        return new Monitor(source, subsystem, callback);
    }
}
