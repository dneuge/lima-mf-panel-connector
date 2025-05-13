package de.energiequant.limamf.connector.gui;

import static de.energiequant.apputils.misc.gui.SwingHelper.runSynchronouslyInEventDispatchThread;
import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.apputils.misc.gui.SwingHelper;
import de.energiequant.limamf.compat.protocol.IdentificationInfoMessage;
import de.energiequant.limamf.connector.Configuration;
import de.energiequant.limamf.connector.DeviceDiscovery;
import de.energiequant.limamf.connector.ModuleDiscovery;
import de.energiequant.limamf.connector.ObservableCollectionProxy;
import de.energiequant.limamf.connector.USBDevice;
import de.energiequant.limamf.connector.USBDeviceId;

public class SerialDeviceApprovalsWindow extends JDialog {
    private static final Logger LOGGER = LoggerFactory.getLogger(SerialDeviceApprovalsWindow.class);

    private final Configuration config;
    private final ObservableCollectionProxy<USBDevice, ?> connectedUSBDevices;
    private final ObservableCollectionProxy<USBDeviceId, ?> configuredUSBDeviceIds;

    private final DeviceListPanel deviceListPanel;

    private boolean windowClosed = true;

    public SerialDeviceApprovalsWindow(Configuration config, ObservableCollectionProxy<USBDevice, ?> connectedUSBDevices) {
        super();

        this.config = config;
        this.connectedUSBDevices = connectedUSBDevices;
        this.configuredUSBDeviceIds = config.getUSBInterfaceIds();

        // FIXME: relocate to app-wide setup
        UIManager.put("OptionPane.maximumSize", new Dimension(500, 500));

        setTitle("Serial Device Approvals");
        setModal(true);
        setSize(800, 600);
        setMinimumSize(new Dimension(600, 400));
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        Insets defaultInsets = gbc.insets;

        gbc.gridwidth = 3;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(1, 2, 5, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        add(
            SwingHelper.stylePlain(
                new JLabel(
                    "<html>Hardware using MobiFlight shows up as a generic serial interface. MobiFlight can only be detected by actively sending probe "
                        + "commands to the device. To prevent inadvertent actions or disruptions, devices need to be manually identified and approved in this "
                        + "dialog. If you are unsure about a device's identity, you can try plugging it in and out a few times to observe how the entries in "
                        + "this list change. Enabling a serial interface in this list will actively probe the device to confirm firmware compatibility. Only "
                        + "devices which have been enabled here and pass probing can be used.</html>"
                )
            ),
            gbc
        );
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = defaultInsets;

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        deviceListPanel = new DeviceListPanel();
        add(new JScrollPane(deviceListPanel), gbc);
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;

        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy++;
        add(new JPanel(), gbc);
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;

        gbc.gridx++;
        gbc.insets = new Insets(1, 1, 1, 0);
        JButton btnApply = new JButton("Apply & Close");
        btnApply.addActionListener(this::onApplyCloseClicked);
        add(btnApply, gbc);

        gbc.gridx++;
        gbc.insets = new Insets(1, 0, 1, 1);
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(this::onCancelClicked);
        add(btnCancel, gbc);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    }

    private void onApplyCloseClicked(ActionEvent e) {
        applyChanges();
        close();
    }

    private void onCancelClicked(ActionEvent e) {
        close();
    }

    @Override
    protected void processWindowEvent(WindowEvent e) {
        super.processWindowEvent(e);

        int eventId = e.getID();
        if (windowClosed && (eventId == WindowEvent.WINDOW_ACTIVATED)) {
            onWindowOpened();
        } else if (eventId == WindowEvent.WINDOW_CLOSING) {
            if (hasChanges()) {
                int res = JOptionPane.showConfirmDialog(this, "Do you want to apply the changed device approval(s)?", "Pending Changes", JOptionPane.YES_NO_CANCEL_OPTION);
                if (res == JOptionPane.YES_OPTION) {
                    applyChanges();
                } else if (res != JOptionPane.NO_OPTION) {
                    // cancel
                    return;
                }
            }

            close();
        }
    }

    private boolean hasChanges() {
        return !deviceListPanel.getApprovedDeviceIds().equals(configuredUSBDeviceIds.getAllPresent());
    }

    private void applyChanges() {
        Set<USBDeviceId> previous = new HashSet<>(configuredUSBDeviceIds.getAllPresent());
        Set<USBDeviceId> wanted = deviceListPanel.getApprovedDeviceIds();

        Set<USBDeviceId> removed = new HashSet<>(previous);
        removed.removeAll(wanted);

        Set<USBDeviceId> added = new HashSet<>(wanted);
        added.removeAll(previous);

        if (removed.isEmpty() && added.isEmpty()) {
            LOGGER.debug("no changes to be applied");
            return;
        }

        LOGGER.debug("removing: {}", removed);
        LOGGER.debug("adding: {}", added);

        removed.forEach(configuredUSBDeviceIds::remove);
        added.forEach(configuredUSBDeviceIds::add);

        LOGGER.debug("changes have been applied");

        config.trySave();
    }

    private void close() {
        onWindowClosing();
        setVisible(false);
    }

    private void onWindowOpened() {
        LOGGER.debug("window opened");
        windowClosed = false;
        deviceListPanel.clear();
        connectedUSBDevices.attach(true, deviceListPanel);
    }

    private void onWindowClosing() {
        LOGGER.debug("window closing");
        windowClosed = true;
        connectedUSBDevices.detach(deviceListPanel);
        deviceListPanel.clear();
    }

    private class DeviceListPanel extends JPanel implements ObservableCollectionProxy.Listener<USBDevice> {
        private LinkedHashSet<USBDeviceId> knownDeviceIds = new LinkedHashSet<>();
        private List<JCheckBox> checkBoxes = new ArrayList<>();
        private Map<USBDeviceId, USBDevice> connectedDevicesById = new HashMap<>();
        private Set<USBDeviceId> approvedDeviceIds = new HashSet<>();

        private DeviceListPanel() {
            super();

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(Color.WHITE);
        }

        public Set<USBDeviceId> getApprovedDeviceIds() {
            synchronized (this) {
                return new HashSet<>(approvedDeviceIds);
            }
        }

        private void clear() {
            synchronized (this) {
                LOGGER.debug("clearing device list");
                knownDeviceIds.clear();
                approvedDeviceIds.clear();
                connectedDevicesById.clear();

                for (USBDeviceId configuredDeviceId : configuredUSBDeviceIds.getAllPresent()) {
                    knownDeviceIds.add(configuredDeviceId);
                    approvedDeviceIds.add(configuredDeviceId);
                }
            }

            syncEventDispatch(() -> {
                synchronized (this) {
                    checkBoxes.clear();
                    removeAll();

                    updateUIList();

                    if (checkBoxes.isEmpty()) {
                        add(new JLabel("No devices have been found yet; check if your module is connected."));
                    }
                }
            });
        }

        @Override
        public void onAdded(USBDevice obj) {
            synchronized (this) {
                USBDeviceId id = obj.getId();
                if (!id.getSerial().isPresent()) {
                    LOGGER.debug("missing serial ID, ignoring: {}", obj);
                    return;
                }

                LOGGER.debug("recording known device (monitor): {}", obj);
                knownDeviceIds.add(id);
                USBDevice previous = connectedDevicesById.put(id, obj);
                if (previous != null) {
                    connectedDevicesById.remove(id);
                    throw new IllegalArgumentException("Device with same ID seen twice; sharing the same ID currently is not supported: " + previous + ", " + obj);
                }
            }

            updateUIList();
        }

        @Override
        public void onRemoved(USBDevice obj) {
            synchronized (this) {
                LOGGER.debug("recording disconnected device (monitor): {}", obj);
                connectedDevicesById.remove(obj.getId());
            }

            updateUIList();
        }

        private void updateUIList() {
            syncEventDispatch(() -> {
                synchronized (this) {
                    LOGGER.debug(
                        "updating UI; {} known, {} connected, already have {} checkboxes",
                        knownDeviceIds.size(), connectedDevicesById.size(), checkBoxes.size()
                    );

                    // remove placeholder text before adding first device
                    if (checkBoxes.isEmpty() && !knownDeviceIds.isEmpty()) {
                        removeAll();
                        revalidate();
                    }

                    int i = 0;
                    for (USBDeviceId id : knownDeviceIds) {
                        while (i >= checkBoxes.size()) {
                            JCheckBox tmp = new JCheckBox();
                            tmp.setBackground(Color.WHITE);
                            final int deviceIndex = checkBoxes.size();
                            tmp.addActionListener(event -> onCheckBoxChange(deviceIndex, event));
                            add(tmp);
                            checkBoxes.add(tmp);
                        }

                        JCheckBox checkBox = checkBoxes.get(i);

                        StringBuilder sb = new StringBuilder();

                        sb.append(String.format("%04X %04X ", id.getVendor(), id.getProduct()));

                        String deviceSerial = id.getSerial().orElseThrow(() -> new IllegalArgumentException("missing serial ID: " + id));
                        sb.append(deviceSerial);
                        sb.append(" ");

                        boolean isApproved = approvedDeviceIds.contains(id);
                        checkBox.setSelected(isApproved);

                        if (connectedDevicesById.containsKey(id)) {
                            sb.append("[connected]");
                            checkBox.setEnabled(true);
                        } else {
                            sb.append("[not found]");
                            checkBox.setEnabled(isApproved);
                        }

                        // TODO: mark devices usually associated with MobiFlight

                        checkBox.setText(escapeHtml4(sb.toString()));

                        checkBox.revalidate();

                        i++;
                    }
                }
            });

            SwingUtilities.invokeLater(() -> {
                // removal & invalidating the panel is insufficient
                // - revalidate must be called to actually get the components removed (done on the changed components above)
                // - repaint is needed even if the window gets closed/reopened, otherwise remains of previous components may still get drawn
                SerialDeviceApprovalsWindow.this.repaint();
            });
        }

        private void onCheckBoxChange(int index, ActionEvent event) {
            boolean selected = ((JCheckBox) event.getSource()).isSelected();

            USBDeviceId deviceId = null;
            synchronized (this) {
                Iterator<USBDeviceId> it = knownDeviceIds.iterator();
                int i = 0;
                while (it.hasNext()) {
                    USBDeviceId tmp = it.next();
                    if (i == index) {
                        deviceId = tmp;
                        break;
                    }
                    i++;
                }
            }

            if (deviceId == null) {
                throw new IllegalArgumentException("no device ID for checkbox index " + index);
            }

            if (!selected) {
                synchronized (this) {
                    LOGGER.info("Revoked serial device approval: {}", deviceId);
                    approvedDeviceIds.remove(deviceId);
                }
            } else {
                USBDevice device;
                synchronized (this) {
                    device = connectedDevicesById.get(deviceId);
                }
                if (device == null) {
                    LOGGER.debug("prevented selection of unconnected device {}", deviceId);
                    updateUIList(); // update UI to reset checkbox state
                    return;
                }

                File deviceNode = device.getDeviceNode().orElse(null);
                if (deviceNode == null) {
                    throw new IllegalArgumentException("missing node for known device index " + index + ": " + device);
                }

                if (!DeviceDiscovery.getInstance().isKnownUSBProduct(deviceId)) {
                    int res = JOptionPane.showConfirmDialog(this, "The selected serial device is not on the list of hardware commonly known to be used for MobiFlight.\n\nAre you sure you want to send probe commands?", "Uncommon device selected", JOptionPane.YES_NO_OPTION);
                    if (res != JOptionPane.YES_OPTION) {
                        LOGGER.debug("Cancelled approval of uncommon serial device: {}", device);
                        updateUIList(); // update UI to reset checkbox state
                        return;
                    }

                    LOGGER.warn("Requested probe to uncommon serial device {}/{}", asHexUSBId(deviceId.getProduct()), asHexUSBId(deviceId.getVendor()));
                }

                // TODO: decouple probing from UI thread using SwingWorker and a modal wait dialog (see SwingWorker.get JavaDoc)
                SerialDeviceApprovalsWindow.this.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                SerialDeviceApprovalsWindow.this.repaint(); // workaround for blocking UI thread; only works sometimes, needs decoupling

                LOGGER.info("Probing {} for approval ({}/{} {})", device.getDeviceNode().orElse(null), asHexUSBId(deviceId.getProduct()), asHexUSBId(deviceId.getVendor()), deviceId.getSerial().orElse("no serial"));
                IdentificationInfoMessage identification = ModuleDiscovery.probe(deviceNode).orElse(null);

                SerialDeviceApprovalsWindow.this.setCursor(Cursor.getDefaultCursor());

                if (identification == null) {
                    LOGGER.warn("Device on {} does not appear to run MobiFlight and cannot be used. The device may have fallen into an undefined state as a result of sending invalid commands; check physical state and reset if necessary: {}/{} {}", device.getDeviceNode().orElse(null), asHexUSBId(deviceId.getProduct()), asHexUSBId(deviceId.getVendor()), deviceId.getSerial().orElse("no serial"));

                    JOptionPane.showMessageDialog(this, "Failed to probe serial device " + deviceNode.getAbsolutePath() + ", MobiFlight was not detected.\n\nThe device may have fallen into an undefined state.", "Probe failed", JOptionPane.ERROR_MESSAGE);
                    updateUIList(); // update UI to reset checkbox state
                    return;
                }

                synchronized (this) {
                    LOGGER.info("Probe on {} found {} {} {} (version {})", device.getDeviceNode().orElse(null), identification.getMobiflightType(), identification.getName(), identification.getSerial(), identification.getVersion());
                    approvedDeviceIds.add(device.getId());
                }

                JOptionPane.showMessageDialog(this, "Serial device " + deviceNode.getAbsolutePath() + " has successfully been probed.", "Probe successful", JOptionPane.INFORMATION_MESSAGE);
            }

            updateUIList();
        }
    }

    private static String asHexUSBId(int id) {
        return String.format("%04X", id);
    }

    private static void syncEventDispatch(Runnable action) {
        try {
            runSynchronouslyInEventDispatchThread(action);
        } catch (InterruptedException ex) {
            LOGGER.error("interrupted while waiting for event dispatch thread, exiting", ex);
            System.exit(1);
        }
    }
}
