package de.energiequant.limamf.connector;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.compat.utils.Maps;
import de.energiequant.limamf.connector.UDevAdmWrapper.DeviceInformation;
import de.energiequant.limamf.connector.utils.OperatingSystem;
import de.energiequant.limamf.connector.utils.OperatingSystem.UnsupportedOperatingSystem;

public class DeviceDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceDiscovery.class);

    private static final Predicate<String> ACCEPT_ALL = x -> true;

    private static final Map<Integer, Set<Integer>> SUPPORTED_USB_PRODUCTS_BY_VENDOR = Maps.createHashMap(
        Maps.entry(
            // Raspberry Pi
            0x2E8A, new HashSet<>(Arrays.asList(
                // Pico
                0x000A
            ))
        )
    );

    private static final Set<String> MAC_HANDLED_IO_OBJECT_CLASSES = new HashSet<>(Arrays.asList("AppleUSBACMData", "IOUserSerial"));
    private static final Set<String> MAC_USB_SERIAL_NODE_KEYS = new HashSet<>(Arrays.asList("IOCalloutDevice", "IODialinDevice"));
    private static final Pattern MAC_PREFERRED_SERIAL_NODE = Pattern.compile("^cu\\..*");

    private boolean isSupportedUsbProduct(USBDevice device) {
        int vendorId = device.getVendorId().orElse(-1);
        if (vendorId < 0) {
            return false;
        }

        int productId = device.getProductId().orElse(-1);
        if (productId < 0) {
            return false;
        }

        Set<Integer> supportedProductIds = SUPPORTED_USB_PRODUCTS_BY_VENDOR.get(vendorId);
        if (supportedProductIds == null) {
            return false;
        }

        return supportedProductIds.contains(productId);
    }

    public Collection<USBDevice> findSupportedDevices() {
        return findSupportedDevices(ACCEPT_ALL);
    }

    public Collection<USBDevice> findSupportedDevices(Predicate<String> ttyNameFilter) {
        return findUSBSerialDevices(ttyNameFilter).stream()
                                                  .filter(this::isSupportedUsbProduct)
                                                  .collect(Collectors.toList());
    }

    public Collection<USBDevice> findUSBSerialDevices() {
        return findUSBSerialDevices(ACCEPT_ALL);
    }

    public Collection<USBDevice> findUSBSerialDevices(Predicate<String> ttyNameFilter) {
        if (OperatingSystem.isLinux()) {
            return findUSBSerialDevicesLinux(ttyNameFilter);
        } else if (OperatingSystem.isMacOS()) {
            return findUSBSerialDevicesMacOS(ttyNameFilter);
        } else {
            throw new UnsupportedOperatingSystem("Device discovery is only supported on Linux and macOS");
        }
    }

    private Collection<USBDevice> findUSBSerialDevicesLinux(Predicate<String> sysClassTtyNameFilter) {
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

            DeviceInformation udevInfo = udevadm.info(sysClassTtyNode);
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

    private Collection<USBDevice> findUSBSerialDevicesMacOS(Predicate<String> ttyNameFilter) {
        Map<String, Collection<USBDevice>> bySerialId = new HashMap<>();

        List<IORegWrapper.USBElement> usbElements = IORegWrapper.USBElement.flattenRecursively(new IORegWrapper().listUSB());
        for (IORegWrapper.USBElement usbElement : usbElements) {
            if (!MAC_HANDLED_IO_OBJECT_CLASSES.contains(usbElement.getIOObjectClass().orElse(null))) {
                continue;
            }

            //LOGGER.debug("{}", usbElement);
            //LOGGER.debug("{}", toList(usbElement.getRawData().getKeys()));

            List<XMLPropertyListConfiguration> configChildren = usbElement.getRawData().getList(XMLPropertyListConfiguration.class, "IORegistryEntryChildren");
            for (XMLPropertyListConfiguration configChild : configChildren) {
                //LOGGER.debug("{}", toList(configChild.getKeys()));

                for (String serialNodeKey : MAC_USB_SERIAL_NODE_KEYS) {
                    String devicePath = configChild.getString(serialNodeKey, null);
                    if (devicePath == null) {
                        continue;
                    }

                    File deviceNode = new File(devicePath);
                    if (!ttyNameFilter.test(deviceNode.getName())) {
                        continue;
                    }

                    if (!(deviceNode.canRead() && deviceNode.canWrite())) {
                        LOGGER.debug("Missing permissions, skipping: {} {}", deviceNode, usbElement);
                        continue;
                    }

                    LOGGER.trace("Found serial device node: {} {}", deviceNode, usbElement);

                    // the node we just found is nested and may only hold partial information
                    // search parents, use first (deepest) available USB vendor/product ID
                    int vendorId = -1;
                    int productId = -1;

                    IORegWrapper.USBElement provider = usbElement;
                    while (provider != null) {
                        vendorId = provider.getVendorId().orElse(-1);
                        productId = provider.getProductId().orElse(-1);

                        if (vendorId >= 0 && productId >= 0) {
                            break;
                        }

                        provider = provider.getParent();
                    }

                    if (vendorId < 0 || productId < 0) {
                        LOGGER.warn("Could not find vendor and product ID, skipping: {} {}", devicePath, usbElement);
                        continue;
                    }

                    // textual USB identification may also only be available from parents
                    // search up until we
                    //   - either have complete information (read from a single node) or
                    //   - until the numeric IDs no longer match
                    String productName = null;
                    String vendorName = null;
                    String serialId = null;
                    while (provider != null) {
                        if (vendorId != provider.getVendorId().orElse(-1) || productId != provider.getProductId().orElse(-1)) {
                            LOGGER.trace("IDs changed, stopping search: {}", provider);
                            break;
                        }

                        productName = provider.getProductName().orElse(productName);
                        vendorName = provider.getVendorName().orElse(vendorName);
                        serialId = provider.getSerialId().orElse(serialId);

                        LOGGER.trace("Iteration: {} {} {} <= {}", productName, vendorName, serialId, provider);

                        if (productName != null && vendorName != null && serialId != null) {
                            LOGGER.trace("Information complete, stopping search: {}", provider);
                            break;
                        }

                        provider = provider.getParent();
                    }

                    if (serialId == null) {
                        LOGGER.warn("Found USB device without serial ID, unable to process: {} {}", devicePath, usbElement);
                        continue;
                    }

                    USBDevice description = new USBDevice();
                    description.setDeviceNode(deviceNode);
                    description.setProductId(productId);
                    description.setVendorId(vendorId);
                    description.setSerialId(serialId);

                    buildName(vendorName, productName, serialId).ifPresent(description::setName);

                    bySerialId.computeIfAbsent(serialId, x -> new ArrayList<>())
                              .add(description);
                }
            }
        }

        Collection<USBDevice> out = new ArrayList<>();

        for (Map.Entry<String, Collection<USBDevice>> entry : bySerialId.entrySet()) {
            String serialId = entry.getKey();
            Collection<USBDevice> devices = entry.getValue();

            if (devices.size() <= 1) {
                out.addAll(devices);
                continue;
            }

            Collection<USBDevice> preferredDevices = new ArrayList<>();
            for (USBDevice device : devices) {
                File node = device.getDeviceNode().orElseThrow(() -> new IllegalArgumentException("recorded device without node: " + device));
                String name = node.getName();

                if (MAC_PREFERRED_SERIAL_NODE.matcher(name).matches()) {
                    preferredDevices.add(device);
                }
            }

            if (preferredDevices.isEmpty()) {
                LOGGER.warn("Multiple devices share serial ID {}, no node is preferred; ignoring: {}", serialId, devices);
            } else if (preferredDevices.size() > 1) {
                LOGGER.warn("Multiple devices share serial ID {}, unable to decide for preferred device; ignoring: {}", serialId, devices);
            } else {
                USBDevice preferredDevice = preferredDevices.iterator().next();
                LOGGER.debug("Multiple devices share serial ID {}, resolved by preferring {} out of {}", serialId, preferredDevice, devices);
                out.add(preferredDevice);
            }
        }

        return out;
    }

    private Optional<String> buildName(String vendorName, String productName, String serialId) {
        StringBuilder sb = new StringBuilder();

        if (vendorName != null) {
            sb.append(vendorName.trim());
        }

        if (productName != null) {
            sb.append(" ");
            sb.append(productName.trim());
        }

        if (serialId != null) {
            sb.append(" ");
            sb.append(serialId.trim());
        }

        String out = sb.toString().trim();
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    private static void applyIfPresent(Map<String, String> properties, String propertiesKey, Consumer<String> setter) {
        String value = properties.get(propertiesKey);
        if (value != null) {
            setter.accept(value);
        }
    }
}
