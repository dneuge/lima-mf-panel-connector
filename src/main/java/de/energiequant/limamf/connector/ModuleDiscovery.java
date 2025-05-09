package de.energiequant.limamf.connector;

import java.io.File;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.apputils.misc.DisclaimerState;
import de.energiequant.limamf.compat.protocol.IdentificationInfoMessage;

public class ModuleDiscovery extends AsyncMonitor<ModuleDiscovery.ConnectedModule, Set<ModuleDiscovery.ConnectedModule>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleDiscovery.class);

    private final DisclaimerState disclaimerState;
    private final ObservableCollectionProxy<USBDeviceId, Set<USBDeviceId>> wantedUSBInterfaceIds;
    private final ObservableCollectionProxy.Listener<USBDeviceId> wantedUSBInterfaceIdListener;

    private final ObservableCollectionProxy<USBDevice, ?> connectedDevices;
    private final ObservableCollectionProxy.Listener<USBDevice> connectedDevicesListener;

    private final ObservableCollectionProxy<ConnectedModule, ?> connectedModules;

    private final Set<USBDeviceId> blockedUSBInterfaceIds = new HashSet<>();

    private final Thread probeThread;
    private final Set<USBDevice> probeQueue = new LinkedHashSet<>();
    private static final Duration PROBE_CHECK_INTERVAL = Duration.ofSeconds(5);
    private static final Duration PROBE_THREAD_JOIN_TIMEOUT = Duration.ofMinutes(1);

    public static class ConnectedModule {
        private final ModuleId moduleId;
        private final USBDevice usbDevice;
        private final String version;

        private ConnectedModule(ModuleId moduleId, USBDevice usbDevice, String version) {
            this.moduleId = moduleId;
            this.usbDevice = usbDevice;
            this.version = version;
        }

        public ModuleId getModuleId() {
            return moduleId;
        }

        public USBDevice getUSBDevice() {
            return usbDevice;
        }

        public String getVersion() {
            return version;
        }

        @Override
        public String toString() {
            return "ConnectedModule(" + moduleId + ", version=\"" + version + "\", " + usbDevice + ")";
        }
    }

    public ModuleDiscovery(Configuration config, ObservableCollectionProxy<USBDevice, ?> connectedDevices, DisclaimerState disclaimerState) {
        super(HashSet::new);

        this.disclaimerState = disclaimerState;
        this.connectedDevices = connectedDevices;
        this.connectedModules = getCollectionProxy();
        this.wantedUSBInterfaceIds = config.getUSBInterfaceIds();

        wantedUSBInterfaceIdListener = new ObservableCollectionProxy.Listener<USBDeviceId>() {
            @Override
            public void onAdded(USBDeviceId obj) {
                onDeviceWanted(obj);
            }

            @Override
            public void onRemoved(USBDeviceId obj) {
                onDeviceUnwanted(obj);
            }
        };

        connectedDevicesListener = new ObservableCollectionProxy.Listener<USBDevice>() {
            @Override
            public void onAdded(USBDevice obj) {
                onDeviceAvailable(obj);
            }

            @Override
            public void onRemoved(USBDevice obj) {
                onDeviceUnavailable(obj);
            }
        };

        probeThread = new Thread(this::probeLoop);

        disclaimerState.addListener(this::onDisclaimerStateChanged);
    }

    private void onDisclaimerStateChanged() {
        synchronized (this) {
            notifyAll();
        }
    }

    @Override
    protected void doStart() {
        wantedUSBInterfaceIds.attach(true, wantedUSBInterfaceIdListener);
        connectedDevices.attach(true, connectedDevicesListener);
        probeThread.start();
    }

    @Override
    protected void doShutdown() {
        connectedDevices.detach(connectedDevicesListener);
        wantedUSBInterfaceIds.detach(wantedUSBInterfaceIdListener);

        synchronized (this) {
            probeQueue.clear();
            notifyAll();
        }

        try {
            LOGGER.debug("joining probe thread");
            probeThread.join(PROBE_THREAD_JOIN_TIMEOUT.toMillis());
        } catch (InterruptedException ex) {
            LOGGER.error("interrupted while joining probe thread", ex);
            System.exit(1);
        }

        if (probeThread.isAlive()) {
            LOGGER.warn("probe thread did not shutdown within timeout");
        }
    }

    private void onDeviceWanted(USBDeviceId deviceId) {
        synchronized (this) {
            LOGGER.debug("Interface wanted: {}", deviceId);

            for (USBDevice connectedDevice : connectedDevices.getAllPresent()) {
                if (deviceId.equals(connectedDevice.getId())) {
                    queueProbe(connectedDevice);
                }
            }
        }
    }

    private void onDeviceUnwanted(USBDeviceId deviceId) {
        synchronized (this) {
            LOGGER.debug("Interface is no longer wanted: {}", deviceId);
            removeModules(deviceId);
        }
    }

    private void onDeviceAvailable(USBDevice device) {
        synchronized (this) {
            LOGGER.debug("Interface available: {}", device);

            boolean wanted = wantedUSBInterfaceIds.getAllPresent().contains(device.getId());
            if (wanted) {
                queueProbe(device);
            }
        }
    }

    private void onDeviceUnavailable(USBDevice device) {
        USBDeviceId deviceId = device.getId();

        synchronized (this) {
            LOGGER.debug("Interface unavailable: {}", deviceId);
            removeModules(deviceId);
        }
    }

    private void queueProbe(USBDevice device) {
        synchronized (this) {
            LOGGER.debug("queueing probe for {}", device);
            probeQueue.add(device);
            notifyAll();
        }
    }

    private void probeLoop() {
        LOGGER.debug("probe thread started");
        long probeCheckMillis = PROBE_CHECK_INTERVAL.toMillis();

        while (true) {
            USBDevice device = null;
            synchronized (this) {
                if (shouldShutdown()) {
                    break;
                }

                if (probeQueue.isEmpty() || !disclaimerState.isAccepted()) {
                    try {
                        wait(probeCheckMillis);
                    } catch (InterruptedException ex) {
                        LOGGER.error("interrupted while waiting in probe loop; exiting", ex);
                        System.exit(1);
                    }
                    continue;
                }

                device = removeFirst(probeQueue).orElse(null);
                if (device == null) {
                    LOGGER.warn("non-empty queue contained probe request for null device");
                    continue;
                }

                if (blockedUSBInterfaceIds.contains(device.getId())) {
                    LOGGER.warn("device is blocked, not probing: {}", device);
                    continue;
                }
            }

            // probe queue may hold requests for devices that have already been probed; only probe once while connected
            boolean alreadyConnected = false;
            for (ConnectedModule connectedModule : connectedModules.getAllPresent()) {
                if (device.equals(connectedModule.getUSBDevice())) {
                    alreadyConnected = true;
                    break;
                }
            }
            if (alreadyConnected) {
                LOGGER.debug("module is already connected; not probing again: {}", device);
                continue;
            }

            // probe requests may be outdated if devices get disconnected or ignored concurrently
            USBDeviceId deviceId = device.getId();
            boolean wanted = wantedUSBInterfaceIds.contains(deviceId);
            boolean connected = connectedDevices.contains(device);
            if (!(wanted && connected)) {
                LOGGER.debug("ignoring outdated probe request, wanted={}, connected={}: {}", wanted, connected, device);
                continue;
            }

            File deviceNode = device.getDeviceNode().orElse(null);
            if (deviceNode == null) {
                LOGGER.warn("probe requested for device without node; ignoring: {}", device);
                continue;
            }

            LOGGER.info("Probing: {}", device);
            IdentificationInfoMessage identification = probe(deviceNode).orElse(null);
            if (identification == null) {
                LOGGER.warn("Module cannot be added (probe failed): {}", device);
                block(device);
                continue;
            }

            synchronized (this) {
                // probe queue may hold duplicates; remove them
                probeQueue.removeIf(x -> deviceId.equals(x.getId()));

                // device may be no longer wanted or could have been disconnected since we started processing the probe
                // request; only connect modules that are still relevant
                boolean blocked = blockedUSBInterfaceIds.contains(deviceId);
                wanted = wantedUSBInterfaceIds.contains(deviceId);
                connected = connectedDevices.contains(device);
                boolean disclaimerAccepted = disclaimerState.isAccepted();
                if (shouldShutdown() || blocked || !(wanted && connected) || !disclaimerAccepted) {
                    LOGGER.debug("probe is no longer valid, shutdown={}, wanted={}, connected={}, blocked={}, disclaimerAccepted={}: {}", shouldShutdown(), wanted, connected, blocked, disclaimerAccepted, device);
                    continue;
                }

                ConnectedModule module;
                try {
                    module = new ConnectedModule(
                        ModuleId.builder()
                                .setType(identification.getMobiflightType())
                                .setName(identification.getName())
                                .setSerial(identification.getSerial())
                                .build(),
                        device,
                        identification.getVersion()
                    );
                } catch (IllegalArgumentException ex) {
                    LOGGER.warn("Module cannot be added (probed data is invalid): {}, {}", device, identification, ex);
                    block(device);
                    continue;
                }

                LOGGER.info("Module is available: {}", module);
                connectedModules.add(module);
            }
        }

        LOGGER.debug("probe thread terminates");
    }

    public static Optional<IdentificationInfoMessage> probe(File deviceNode) {
        LOGGER.debug("probing {}", deviceNode);
        IdentificationInfoMessage identification;
        try {
            identification = DeviceCommunicator.probe(deviceNode).orElse(null);
        } catch (InterruptedException ex) {
            LOGGER.error("interrupted while probing; exiting: {}", deviceNode, ex);
            System.exit(1);
            return Optional.empty();
        }

        if (identification == null) {
            LOGGER.warn("Probe failed: {}", deviceNode);
            return Optional.empty();
        }

        if (!identification.getMobiflightType().toLowerCase().contains("mobiflight")) {
            LOGGER.warn("Interface does not indicate to be running MobiFlight firmware, probe failed: {}, {}", deviceNode, identification);
            return Optional.empty();
        }

        if (!identification.getVersion().equals(identification.getCoreVersion())) {
            LOGGER.warn("Interface reports inconsistent versions, probe failed: {}, {}", deviceNode, identification);
            return Optional.empty();
        }

        if (identification.getName().trim().isEmpty()) {
            LOGGER.warn("Interface reports no module name, probe failed: {}, {}", deviceNode, identification);
            return Optional.empty();
        }

        if (identification.getSerial().trim().isEmpty()) {
            LOGGER.warn("Interface reports no serial, probe failed: {}, {}", deviceNode, identification);
            return Optional.empty();
        }

        LOGGER.debug("probe successful for {}", deviceNode);
        return Optional.of(identification);
    }

    private void removeModules(USBDeviceId deviceId) {
        synchronized (this) {
            LOGGER.debug("removing modules matching {}", deviceId);

            probeQueue.removeIf(x -> deviceId.equals(x.getId()));

            for (ConnectedModule module : connectedModules.getAllPresent()) {
                if (deviceId.equals(module.getUSBDevice().getId())) {
                    LOGGER.warn("Module has become unavailable: {}", module);
                    connectedModules.remove(module);
                }
            }
        }
    }

    public void block(USBDevice device) {
        block(device.getId());
    }

    public void block(USBDeviceId deviceId) {
        synchronized (this) {
            LOGGER.warn("Blocking: {}", deviceId);
            blockedUSBInterfaceIds.add(deviceId);

            removeModules(deviceId);
        }
    }

    private static <T> Optional<T> removeFirst(Collection<T> collection) {
        Iterator<T> it = collection.iterator();
        if (!it.hasNext()) {
            return Optional.empty();
        }

        T item = it.next();
        it.remove();

        return Optional.ofNullable(item);
    }
}
