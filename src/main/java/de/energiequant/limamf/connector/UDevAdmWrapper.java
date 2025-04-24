package de.energiequant.limamf.connector;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.connector.utils.ExternalCommand;

public class UDevAdmWrapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(UDevAdmWrapper.class);

    private final ExternalCommand udevadm;
    private static final String SYS_PATH = "/sys/";

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
}
