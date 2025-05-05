package de.energiequant.limamf.connector;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

public class USBDevice {
    private File deviceNode;
    private String name;
    private final USBDeviceId id;

    public USBDevice(USBDeviceId id) {
        this.id = id;
    }

    public Optional<File> getDeviceNode() {
        return Optional.ofNullable(deviceNode);
    }

    public USBDevice setDeviceNode(File deviceNode) {
        this.deviceNode = deviceNode;
        return this;
    }

    public Optional<String> getName() {
        return Optional.ofNullable(name);
    }

    public USBDevice setName(String name) {
        this.name = name;
        return this;
    }

    public USBDeviceId getId() {
        return id;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("USBDevice(");

        boolean isFirst = true;

        if (deviceNode != null) {
            sb.append("deviceNode=\"");
            sb.append(deviceNode.getAbsolutePath());
            sb.append("\"");
            isFirst = false;
        }

        if (name != null) {
            if (!isFirst) {
                sb.append(", ");
            }

            sb.append("name=\"");
            sb.append(name);
            sb.append("\"");
        }

        sb.append(", ");
        sb.append(id);

        sb.append(")");

        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof USBDevice)) {
            return false;
        }

        USBDevice other = (USBDevice) obj;

        return Objects.equals(this.deviceNode, other.deviceNode)
            && Objects.equals(this.id, other.id)
            && Objects.equals(this.name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, deviceNode, name);
    }
}
