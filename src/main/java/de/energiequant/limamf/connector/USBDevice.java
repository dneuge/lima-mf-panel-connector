package de.energiequant.limamf.connector;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

public class USBDevice {
    private File deviceNode;
    private String name;
    private int vendorId = -1;
    private int productId = -1;
    private String serialId;

    public USBDevice() {
        // nothing to do
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

    public Optional<Integer> getVendorId() {
        if (vendorId < 0) {
            return Optional.empty();
        }

        return Optional.of(vendorId);
    }

    public USBDevice setVendorId(String vendorId) {
        return setVendorId(Integer.parseUnsignedInt(vendorId, 16));
    }

    public USBDevice setVendorId(int vendorId) {
        this.vendorId = vendorId;
        return this;
    }

    public Optional<Integer> getProductId() {
        if (productId < 0) {
            return Optional.empty();
        }

        return Optional.of(productId);
    }

    public USBDevice setProductId(String productId) {
        return setProductId(Integer.parseUnsignedInt(productId, 16));
    }

    public USBDevice setProductId(int productId) {
        this.productId = productId;
        return this;
    }

    public Optional<String> getSerialId() {
        return Optional.ofNullable(serialId);
    }

    public USBDevice setSerialId(String serialId) {
        this.serialId = serialId;
        return this;
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
            isFirst = false;
        }

        if (vendorId >= 0) {
            if (!isFirst) {
                sb.append(", ");
            }

            sb.append("vendorId=0x");
            sb.append(String.format("%04X", vendorId));
            isFirst = false;
        }

        if (productId >= 0) {
            if (!isFirst) {
                sb.append(", ");
            }

            sb.append("productId=0x");
            sb.append(String.format("%04X", productId));
            isFirst = false;
        }

        if (serialId != null) {
            if (!isFirst) {
                sb.append(", ");
            }

            sb.append("serialId=\"");
            sb.append(serialId);
            sb.append("\"");
        }

        sb.append(")");

        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof USBDevice)) {
            return false;
        }

        USBDevice other = (USBDevice) obj;

        return this.vendorId == other.vendorId
            && this.productId == other.productId
            && Objects.equals(this.deviceNode, other.deviceNode)
            && Objects.equals(this.serialId, other.serialId)
            && Objects.equals(this.name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vendorId, productId, serialId, deviceNode, name);
    }
}
