package de.energiequant.limamf.connector;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.connector.utils.OperatingSystem;

public class UDevAdmWrapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(UDevAdmWrapper.class);

    private final File udevadm;
    private static final String SYS_PATH = "/sys/";

    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);

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
        udevadm = OperatingSystem.locateFromPaths("udevadm", File::canExecute)
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

        List<String> lines = run("info", "-p", canonicalPath.substring(SYS_PATH.length()));
        return DeviceInformation.fromInfoOutput(lines);
    }

    private List<String> run(String... parameters) {
        return run(Arrays.asList(parameters));
    }

    private List<String> run(Collection<String> parameters) {
        List<String> command = new ArrayList<>();
        command.add(udevadm.getAbsolutePath());
        command.addAll(parameters);

        LOGGER.debug("Running command: {}", command);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to spawn command: " + String.join(" ", command), ex);
        }

        List<String> lines = new ArrayList<>();
        try (
            InputStreamReader isr = new InputStreamReader(process.getInputStream());
            BufferedReader br = new BufferedReader(isr);
        ) {
            String line = br.readLine();
            while (line != null) {
                lines.add(line);
                line = br.readLine();
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read output: " + String.join(" ", command), ex);
        }

        try {
            long seconds = TERMINATION_TIMEOUT.getSeconds();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOGGER.warn("Command timed out after {} seconds: {}", seconds, command);
            }
        } catch (InterruptedException ex) {
            throw new RuntimeException("interrupted during command termination", ex);
        }

        return lines;
    }
}
