package de.energiequant.limamf.connector;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.compat.utils.Maps;
import de.energiequant.limamf.connector.utils.OperatingSystem;
import de.energiequant.limamf.connector.utils.OperatingSystem.UnsupportedOperatingSystem;

public abstract class DeviceDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceDiscovery.class);

    private static final DeviceDiscovery INSTANCE;

    public static final Predicate<String> ACCEPT_ALL = x -> true;

    private static final Map<Integer, Set<Integer>> KNOWN_USB_PRODUCTS_BY_VENDOR = Maps.createHashMap(
        Maps.entry(
            // Raspberry Pi
            0x2E8A, new HashSet<>(Arrays.asList(
                // Pico
                0x000A
            ))
        )
    );

    static {
        if (OperatingSystem.isLinux()) {
            INSTANCE = new LinuxDeviceDiscovery();
        } else if (OperatingSystem.isMacOS()) {
            INSTANCE = new MacOSDeviceDiscovery();
        } else {
            throw new UnsupportedOperatingSystem("Device discovery has not been implemented for operating system: " + System.getProperty("os.name"));
        }
    }

    public boolean isKnownUSBProduct(USBDevice device) {
        return isKnownUSBProduct(device.getId());
    }

    public boolean isKnownUSBProduct(USBDeviceId id) {
        int vendorId = id.getVendorId().orElse(-1);
        if (vendorId < 0) {
            return false;
        }

        int productId = id.getProductId().orElse(-1);
        if (productId < 0) {
            return false;
        }

        Set<Integer> knownProductIds = KNOWN_USB_PRODUCTS_BY_VENDOR.get(vendorId);
        if (knownProductIds == null) {
            return false;
        }

        return knownProductIds.contains(productId);
    }

    public abstract Collection<USBDevice> findUSBSerialDevices();

    public abstract Collection<USBDevice> findUSBSerialDevices(Predicate<String> ttyNameFilter);

    public abstract AsyncMonitor<USBDevice, Set<USBDevice>> monitorUSBSerialDevices();

    public abstract AsyncMonitor<USBDevice, Set<USBDevice>> monitorUSBSerialDevices(Predicate<String> ttyNameFilter);

    public static DeviceDiscovery getInstance() {
        return INSTANCE;
    }
}
