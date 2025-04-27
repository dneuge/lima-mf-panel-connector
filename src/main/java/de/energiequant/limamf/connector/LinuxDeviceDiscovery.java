package de.energiequant.limamf.connector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.connector.utils.OperatingSystem;

public class LinuxDeviceDiscovery extends DeviceDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxDeviceDiscovery.class);

    @Override
    public Collection<USBDevice> findUSBSerialDevices(Predicate<String> sysClassTtyNameFilter) {
        Collection<USBDevice> out = new ArrayList<>();

        OperatingSystem.requireLinux();

        UDevAdmWrapper udevadm = new UDevAdmWrapper();

        File sysClassTty = new File("/sys/class/tty");
        File[] sysClassTtyNodes = sysClassTty.listFiles();
        if (sysClassTtyNodes == null) {
            return Collections.emptyList();
        }

        for (File sysClassTtyNode : sysClassTtyNodes) {
            if (!sysClassTtyNameFilter.test(sysClassTtyNode.getName())) {
                LOGGER.trace("Skipping unwanted /sys/class/tty node: {}", sysClassTtyNode);
                continue;
            }

            UDevAdmWrapper.DeviceInformation udevInfo = udevadm.info(sysClassTtyNode);
            if (!udevInfo.getKernelDeviceNodeName().isPresent()) {
                LOGGER.debug("Skipping USB serial device with unreported kernel device node name: {}", sysClassTtyNode);
                continue;
            }

            Map<String, String> properties = udevInfo.getProperties();
            String devicePath = properties.get("DEVNAME");
            if (devicePath == null) {
                LOGGER.debug("Skipping USB serial device without device path: {}", sysClassTtyNode);
                continue;
            }

            File deviceNode = new File(devicePath);
            if (!(deviceNode.canRead() && deviceNode.canWrite())) {
                LOGGER.debug("Missing permissions, skipping: {} => {}", sysClassTtyNode, deviceNode);
                continue;
            }

            USBDevice description = new USBDevice();
            description.setDeviceNode(deviceNode);

            udevInfo.getKernelDeviceNodeName().ifPresent(description::setName);

            applyIfPresent(properties, "ID_SERIAL", description::setSerialId);
            applyIfPresent(properties, "ID_USB_VENDOR_ID", description::setVendorId);
            applyIfPresent(properties, "ID_USB_MODEL_ID", description::setProductId);

            out.add(description);
        }

        return out;
    }

    private static void applyIfPresent(Map<String, String> properties, String propertiesKey, Consumer<String> setter) {
        String value = properties.get(propertiesKey);
        if (value != null) {
            setter.accept(value);
        }
    }
}
