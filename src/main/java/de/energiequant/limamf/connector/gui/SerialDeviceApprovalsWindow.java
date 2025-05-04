package de.energiequant.limamf.connector.gui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.compat.protocol.IdentificationInfoMessage;
import de.energiequant.limamf.connector.Configuration;
import de.energiequant.limamf.connector.DeviceCommunicator;
import de.energiequant.limamf.connector.DeviceDiscovery;
import de.energiequant.limamf.connector.ObservableCollectionProxy;
import de.energiequant.limamf.connector.USBDevice;

public class SerialDeviceApprovalsWindow extends JDialog {
    private static final Logger LOGGER = LoggerFactory.getLogger(SerialDeviceApprovalsWindow.class);

    private final Configuration config;
    private final ObservableCollectionProxy<USBDevice, ?> connectedUSBDevices;

    private final DeviceListPanel deviceListPanel;

    public SerialDeviceApprovalsWindow(Configuration config, ObservableCollectionProxy<USBDevice, ?> connectedUSBDevices) {
        this.config = config;
        this.connectedUSBDevices = connectedUSBDevices;

        // FIXME: relocate to app-wide setup
        UIManager.put("OptionPane.maximumSize", new Dimension(500, 500));

        setTitle("Serial Device Approvals");
        setModal(true);
        setSize(800, 600);
        setMinimumSize(new Dimension(600, 400));
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        add(
            new JLabel(
                "<html>Hardware using MobiFlight shows up as a generic serial interface. MobiFlight can only be detected by actively sending probe "
                    + "commands to the device. To prevent inadvertent actions or disruptions, devices need to be manually identified and approved in this "
                    + "dialog. If you are unsure about a device's identity, you can try plugging it in and out a few times to observe how the entries in "
                    + "this list change. Enabling a serial interface in this list will actively probe the device to confirm firmware compatibility. Only "
                    + "devices which have been enabled here and pass probing can be used.</html>"
            ),
            gbc
        );
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;

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
    }

    @Override
    protected void processWindowEvent(WindowEvent e) {
        int eventId = e.getID();
        if (eventId == WindowEvent.WINDOW_OPENED) {
            onWindowOpened();
        } else if (eventId == WindowEvent.WINDOW_CLOSING) {
            onWindowClosing();
        }

        super.processWindowEvent(e);
    }

    private void onWindowOpened() {
        LOGGER.debug("window opened");
        deviceListPanel.clear();
        connectedUSBDevices.attach(true, deviceListPanel);
    }

    private void onWindowClosing() {
        LOGGER.debug("window closing");
        connectedUSBDevices.detach(deviceListPanel);
    }

    private class DeviceListPanel extends JPanel implements ObservableCollectionProxy.Listener<USBDevice> {
        private LinkedHashSet<USBDevice> knownDevices = new LinkedHashSet<>();
        private List<JCheckBox> checkBoxes = new ArrayList<>();
        private Set<USBDevice> connectedDevices = new HashSet<>();
        private Set<USBDevice> approvedDeviceIds = new HashSet<>();

        private DeviceListPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        private void clear() {
            LOGGER.debug("clearing device list");
            knownDevices.clear();
            connectedDevices.clear();
            checkBoxes.clear();
            removeAll();

            // TODO: add all devices from config

            updateUIList();
        }

        @Override
        public void onAdded(USBDevice obj) {
            if (!(obj.getVendorId().isPresent() && obj.getProductId().isPresent() && obj.getSerialId().isPresent())) {
                LOGGER.debug("missing essential information, ignoring: {}", obj);
                return;
            }

            LOGGER.debug("recording known device (monitor): {}", obj);
            knownDevices.add(obj);
            connectedDevices.add(obj);
            updateUIList();
        }

        @Override
        public void onRemoved(USBDevice obj) {
            LOGGER.debug("recording disconnected device (monitor): {}", obj);
            connectedDevices.remove(obj);
            updateUIList();
        }

        private void updateUIList() {
            LOGGER.debug("updating UI; {} known, {} connected, already have {} checkboxes", knownDevices.size(), connectedDevices.size(), checkBoxes.size());

            int i = 0;
            for (USBDevice device : knownDevices) {
                while (i >= checkBoxes.size()) {
                    JCheckBox tmp = new JCheckBox();
                    final int deviceIndex = checkBoxes.size();
                    tmp.addActionListener(event -> onCheckBoxChange(deviceIndex, event));
                    add(tmp);
                    checkBoxes.add(tmp);
                }

                JCheckBox checkBox = checkBoxes.get(i);

                StringBuilder sb = new StringBuilder();

                int vendorId = device.getVendorId().orElseThrow(() -> new IllegalArgumentException("missing vendor ID: " + device));
                int productId = device.getProductId().orElseThrow(() -> new IllegalArgumentException("missing product ID: " + device));
                sb.append(String.format("%04X %04X ", vendorId, productId));

                String deviceSerial = device.getSerialId().orElseThrow(() -> new IllegalArgumentException("missing serial ID: " + device));
                sb.append(deviceSerial);
                sb.append(" ");

                boolean isApproved = approvedDeviceIds.contains(device.copyOnlyIDs());
                checkBox.setSelected(isApproved);

                if (connectedDevices.contains(device)) {
                    sb.append("[connected]");
                    checkBox.setEnabled(true);
                } else {
                    sb.append("[not found]");
                    checkBox.setEnabled(isApproved);
                }

                // TODO: mark devices usually associated with MobiFlight

                checkBox.setText(sb.toString());

                i++;
            }

            invalidate();
        }

        private void onCheckBoxChange(int index, ActionEvent event) {
            boolean selected = ((JCheckBox) event.getSource()).isSelected();

            Iterator<USBDevice> it = knownDevices.iterator();
            USBDevice device = null;
            int i = 0;
            while (it.hasNext()) {
                USBDevice tmp = it.next();
                if (i == index) {
                    device = tmp;
                    break;
                }
                i++;
            }

            if (device == null) {
                throw new IllegalArgumentException("no device for checkbox index " + index);
            }

            if (!selected) {
                LOGGER.info("Revoked serial device approval: {}", device);
                approvedDeviceIds.remove(device.copyOnlyIDs());
            } else {
                File deviceNode = device.getDeviceNode().orElse(null);
                if (deviceNode == null || !connectedDevices.contains(device)) {
                    LOGGER.debug("prevented selection of unconnected device {}", device);
                    updateUIList(); // update UI to reset checkbox state
                    return;
                }

                if (!DeviceDiscovery.getInstance().isKnownUSBProduct(device)) {
                    int res = JOptionPane.showConfirmDialog(this, "The selected serial device is not on the list of hardware commonly known to be used for MobiFlight.\n\nAre you sure you want to send probe commands?", "Uncommon device selected", JOptionPane.YES_NO_OPTION);
                    if (res != JOptionPane.YES_OPTION) {
                        LOGGER.debug("Cancelled approval of uncommon serial device: {}", device);
                        updateUIList(); // update UI to reset checkbox state
                        return;
                    }

                    LOGGER.warn("Probing uncommon serial device for approval: {}", device);
                }

                // TODO: decouple probing from UI thread using SwingWorker and a modal wait dialog (see SwingWorker.get JavaDoc)
                SerialDeviceApprovalsWindow.this.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                SerialDeviceApprovalsWindow.this.repaint(); // workaround for blocking UI thread; only works sometimes, needs decoupling

                LOGGER.info("Probing for approval: {}", device);
                IdentificationInfoMessage identification;
                try {
                    identification = DeviceCommunicator.probe(deviceNode).orElse(null);
                } catch (InterruptedException ex) {
                    LOGGER.error("interrupted while probing, exiting", ex);
                    System.exit(1);
                    return;
                }

                SerialDeviceApprovalsWindow.this.setCursor(Cursor.getDefaultCursor());

                if (identification == null) {
                    LOGGER.warn("Probe failed: {}", device);

                    JOptionPane.showMessageDialog(this, "Failed to probe serial device " + deviceNode.getAbsolutePath() + ", MobiFlight was not detected.\n\nThe device may have fallen into an undefined state.", "Probe failed", JOptionPane.ERROR_MESSAGE);
                    updateUIList(); // update UI to reset checkbox state
                    return;
                }

                LOGGER.info("Serial device approved: {} => {}", device, identification);
                approvedDeviceIds.add(device.copyOnlyIDs());

                JOptionPane.showMessageDialog(this, "Serial device " + deviceNode.getAbsolutePath() + " has successfully been probed.", "Probe successful", JOptionPane.INFORMATION_MESSAGE);
            }

            updateUIList();
        }
    }
}
