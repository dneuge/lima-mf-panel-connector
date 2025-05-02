package de.energiequant.limamf.connector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.connector.utils.OperatingSystem;

public class LinuxDeviceDiscovery extends DeviceDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxDeviceDiscovery.class);

    private static final Pattern DEFAULT_SERIAL_DEVICE_PATTERN = Pattern.compile("^tty(S|ACM).*");
    private static final Predicate<String> DEFAULT_SERIAL_DEVICE_FILTER = s -> DEFAULT_SERIAL_DEVICE_PATTERN.matcher(s).matches();

    @Override
    public Collection<USBDevice> findUSBSerialDevices() {
        return findUSBSerialDevices(DEFAULT_SERIAL_DEVICE_FILTER);
    }

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

            toUSBDevice(udevadm.info(sysClassTtyNode), true, sysClassTtyNode)
                .ifPresent(out::add);
        }

        return out;
    }

    private static Optional<USBDevice> toUSBDevice(UDevAdmWrapper.DeviceInformation udevInfo, boolean checkPermissions, Object source) {
        if (!udevInfo.getKernelDeviceNodeName().isPresent()) {
            LOGGER.debug("Skipping USB serial device with unreported kernel device node name: {}", source);
            return Optional.empty();
        }

        Map<String, String> properties = udevInfo.getProperties();
        String devicePath = properties.get("DEVNAME");
        if (devicePath == null) {
            LOGGER.debug("Skipping USB serial device without device path: {}", source);
            return Optional.empty();
        }

        String vendorId = properties.get("ID_USB_VENDOR_ID");
        String productId = properties.get("ID_USB_MODEL_ID");

        if (vendorId == null || productId == null) {
            LOGGER.debug("vendor/product ID is missing, not a USB device: {}", udevInfo);
            return Optional.empty();
        }

        File deviceNode = new File(devicePath);
        if (checkPermissions && !(deviceNode.canRead() && deviceNode.canWrite())) {
            LOGGER.debug("Missing permissions, skipping: {} => {}", source, deviceNode);
            return Optional.empty();
        }

        USBDevice description = new USBDevice();
        description.setDeviceNode(deviceNode);

        description.setVendorId(vendorId);
        description.setProductId(productId);

        udevInfo.getKernelDeviceNodeName().ifPresent(description::setName);

        applyIfPresent(properties, "ID_SERIAL", description::setSerialId);

        return Optional.of(description);
    }

    @Override
    public AsyncMonitor<USBDevice, Set<USBDevice>> monitorUSBSerialDevices() {
        return monitorUSBSerialDevices(DEFAULT_SERIAL_DEVICE_FILTER);
    }

    @Override
    public AsyncMonitor<USBDevice, Set<USBDevice>> monitorUSBSerialDevices(Predicate<String> ttyNameFilter) {
        return new USBSerialMonitor(this, ttyNameFilter);
    }

    private static class USBSerialMonitor extends AsyncMonitor<USBDevice, Set<USBDevice>> {
        private final LinuxDeviceDiscovery deviceDiscovery;
        private final Predicate<String> ttyNameFilter;
        private final ObservableCollectionProxy<USBDevice, Set<USBDevice>> collectionProxy;

        private UDevAdmWrapper.Monitor udevMonitor;

        private USBSerialMonitor(LinuxDeviceDiscovery deviceDiscovery, Predicate<String> ttyNameFilter) {
            super(HashSet::new);

            this.deviceDiscovery = deviceDiscovery;
            this.ttyNameFilter = ttyNameFilter;

            collectionProxy = getCollectionProxy();
        }

        @Override
        protected void doStart() {
            udevMonitor = new UDevAdmWrapper().monitor(UDevAdmWrapper.DeviceEventSource.UDEV, "tty", this::onDeviceEvent);

            deviceDiscovery.findUSBSerialDevices(ttyNameFilter)
                           .forEach(collectionProxy::add);
        }

        private void onDeviceEvent(UDevAdmWrapper.DeviceEvent event) {
            UDevAdmWrapper.DeviceInformation info = event.getInfo();
            String nodeName = info.getKernelDeviceNodeName().orElse(null);
            if (nodeName == null) {
                LOGGER.warn("Received device information without node name, ignoring: {}", event);
                return;
            }

            if (!ttyNameFilter.test(nodeName)) {
                LOGGER.debug("Ignoring event for unwanted device node: {}", event);
                return;
            }

            UDevAdmWrapper.DeviceEventType eventType = event.getType();
            Consumer<USBDevice> action;
            boolean checkPermissions = true;
            if (eventType == UDevAdmWrapper.DeviceEventType.ADD) {
                action = collectionProxy::add;
            } else {
                if (eventType != UDevAdmWrapper.DeviceEventType.REMOVE) {
                    LOGGER.warn("Unhandled event {}, interpreting as device removal: {}", event);
                }

                action = collectionProxy::remove;
                checkPermissions = false; // device is gone, so there is nothing to check
            }

            toUSBDevice(info, checkPermissions, event).ifPresent(action);
        }

        @Override
        protected void doShutdown() {
            if (udevMonitor != null) {
                udevMonitor.terminate();
            }
        }
    }

    private static void applyIfPresent(Map<String, String> properties, String propertiesKey, Consumer<String> setter) {
        String value = properties.get(propertiesKey);
        if (value != null) {
            setter.accept(value);
        }
    }
}
