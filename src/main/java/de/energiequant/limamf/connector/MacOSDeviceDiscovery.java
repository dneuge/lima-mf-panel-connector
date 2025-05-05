package de.energiequant.limamf.connector;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.connector.MacOSLogWrapper.LogStreamMonitor;
import de.energiequant.limamf.connector.utils.TimeUtils;

public class MacOSDeviceDiscovery extends DeviceDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(MacOSDeviceDiscovery.class);

    private static final Pattern DEFAULT_SERIAL_DEVICE_PATTERN = Pattern.compile("^(cu|tty)\\.usb(modem|serial).*");
    private static final Predicate<String> DEFAULT_SERIAL_DEVICE_FILTER = s -> DEFAULT_SERIAL_DEVICE_PATTERN.matcher(s).matches();

    private static final Set<String> MAC_HANDLED_IO_OBJECT_CLASSES = new HashSet<>(Arrays.asList("AppleUSBACMData", "IOUserSerial"));
    private static final Set<String> MAC_USB_SERIAL_NODE_KEYS = new HashSet<>(Arrays.asList("IOCalloutDevice", "IODialinDevice"));
    private static final Pattern MAC_PREFERRED_SERIAL_NODE = Pattern.compile("^cu\\..*");

    @Override
    public Collection<USBDevice> findUSBSerialDevices() {
        return findUSBSerialDevices(DEFAULT_SERIAL_DEVICE_FILTER);
    }

    @Override
    public Collection<USBDevice> findUSBSerialDevices(Predicate<String> ttyNameFilter) {
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

                    USBDevice description = new USBDevice(
                        new USBDeviceId()
                            .setVendorId(vendorId)
                            .setProductId(productId)
                            .setSerialId(serialId)
                    );
                    description.setDeviceNode(deviceNode);

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

    @Override
    public AsyncMonitor<USBDevice, Set<USBDevice>> monitorUSBSerialDevices() {
        return monitorUSBSerialDevices(DEFAULT_SERIAL_DEVICE_FILTER);
    }

    @Override
    public AsyncMonitor<USBDevice, Set<USBDevice>> monitorUSBSerialDevices(Predicate<String> ttyNameFilter) {
        return new USBSerialMonitor(this, ttyNameFilter);
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

    private static class USBSerialMonitor extends AsyncMonitor<USBDevice, Set<USBDevice>> {
        private final MacOSDeviceDiscovery discovery;
        private final Predicate<String> ttyNameFilter;
        private final ObservableCollectionProxy<USBDevice, Set<USBDevice>> collectionProxy;

        private LogStreamMonitor logStreamMonitor;

        private Instant scheduledRescan = Instant.now();

        private static final Duration REGULAR_RESCAN_INTERVAL = Duration.ofMinutes(1);
        private static final Duration SETTLE_DELAY = Duration.ofSeconds(3);
        private static final Duration THREAD_CHECK_INTERVAL = Duration.ofSeconds(5);

        private final Thread scanThread;
        private static final Duration MAX_JOIN_TIME = Duration.ofSeconds(5);

        private static final Collection<String> LOG_KEYWORDS = Arrays.asList(
            "USBHost",
            "enumerateDevice",
            "terminateDevice"
        );

        private static final int KERNEL_PID = 0;

        private USBSerialMonitor(MacOSDeviceDiscovery discovery, Predicate<String> ttyNameFilter) {
            super(HashSet::new);

            this.discovery = discovery;
            this.ttyNameFilter = ttyNameFilter;

            collectionProxy = getCollectionProxy();
            scanThread = new Thread(this::scanLoop);
        }

        @Override
        protected void doStart() {
            logStreamMonitor = new MacOSLogWrapper().stream(KERNEL_PID, this::onLogEntry);
            scanThread.start();
        }

        private void onLogEntry(MacOSLogWrapper.LogEntry logEntry) {
            String msg = logEntry.getEventMessage().orElse(null);
            if (msg == null) {
                return;
            }

            boolean relevant = false;
            for (String keyword : LOG_KEYWORDS) {
                if (msg.contains(keyword)) {
                    LOGGER.debug("triggered by log keyword \"{}\", scheduling rescan: {}", keyword, logEntry);
                    relevant = true;
                    break;
                }
            }

            if (!relevant) {
                return;
            }

            Instant intendedRescan = logEntry.getTimestampLogged()
                                             .orElseGet(logEntry::getTimestampParsed)
                                             .plus(SETTLE_DELAY);

            synchronized (this) {
                scheduledRescan = TimeUtils.min(intendedRescan, scheduledRescan);
                notifyAll();
            }
        }

        private void scanLoop() {
            long maxWaitMillis = THREAD_CHECK_INTERVAL.toMillis();

            while (!shouldShutdown()) {
                synchronized (this) {
                    Instant now = Instant.now();
                    long millisUntilRescan = Duration.between(now, scheduledRescan).toMillis();

                    LOGGER.trace("scheduled scan is due in {}ms", millisUntilRescan);

                    boolean isDue = (millisUntilRescan < 1);
                    if (isDue) {
                        scheduledRescan = now.plus(REGULAR_RESCAN_INTERVAL);
                    } else {
                        try {
                            wait(Math.min(millisUntilRescan, maxWaitMillis));
                        } catch (InterruptedException ex) {
                            LOGGER.warn("interrupted while waiting for scheduled scan", ex);
                            break;
                        }

                        continue;
                    }
                }

                LOGGER.debug("scanning devices");

                Set<USBDevice> currentDevices = new HashSet<>(discovery.findUSBSerialDevices(ttyNameFilter));
                Set<USBDevice> previousDevices = collectionProxy.getAllPresent();

                Set<USBDevice> addedDevices = new HashSet<>(currentDevices);
                addedDevices.removeAll(previousDevices);
                addedDevices.forEach(collectionProxy::add);

                Set<USBDevice> removedDevices = new HashSet<>(previousDevices);
                removedDevices.removeAll(currentDevices);
                removedDevices.forEach(collectionProxy::remove);

                LOGGER.debug("device scan complete");
            }

            LOGGER.debug("scan thread terminates, shutting down monitor");
            shutdown();
        }

        @Override
        protected void doShutdown() {
            if (logStreamMonitor != null) {
                logStreamMonitor.terminate();
            }

            if (!scanThread.isAlive()) {
                return;
            }

            synchronized (this) {
                // wake up scan thread
                notifyAll();
            }

            try {
                scanThread.join(MAX_JOIN_TIME.toMillis());
            } catch (InterruptedException ex) {
                LOGGER.warn("interrupted while waiting to join scan thread", ex);
                throw new RuntimeException("interrupted while waiting to join scan thread", ex);
            }

            if (scanThread.isAlive()) {
                LOGGER.warn("scan thread did not terminate within expected timeout");
            }
        }
    }
}
