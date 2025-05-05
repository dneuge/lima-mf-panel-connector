package de.energiequant.limamf.connector;

import java.util.Objects;
import java.util.Optional;

public class USBDeviceId {
    private int vendorId = -1;
    private int productId = -1;
    private String serialId;

    public Optional<Integer> getVendorId() {
        if (vendorId < 0) {
            return Optional.empty();
        }

        return Optional.of(vendorId);
    }

    public USBDeviceId setVendorId(String vendorId) {
        return setVendorId(Integer.parseUnsignedInt(vendorId, 16));
    }

    public USBDeviceId setVendorId(int vendorId) {
        this.vendorId = vendorId;
        return this;
    }

    public Optional<Integer> getProductId() {
        if (productId < 0) {
            return Optional.empty();
        }

        return Optional.of(productId);
    }

    public USBDeviceId setProductId(String productId) {
        return setProductId(Integer.parseUnsignedInt(productId, 16));
    }

    public USBDeviceId setProductId(int productId) {
        this.productId = productId;
        return this;
    }

    public Optional<String> getSerialId() {
        return Optional.ofNullable(serialId);
    }

    public USBDeviceId setSerialId(String serialId) {
        this.serialId = serialId;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("USBDeviceId(");

        sb.append(String.format("%04X", vendorId));
        sb.append("/");
        sb.append(String.format("%04X", productId));

        if (serialId != null) {
            sb.append(", \"");
            sb.append(serialId);
            sb.append("\"");
        }

        sb.append(")");

        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof USBDeviceId)) {
            return false;
        }

        USBDeviceId other = (USBDeviceId) obj;

        return this.vendorId == other.vendorId
            && this.productId == other.productId
            && Objects.equals(this.serialId, other.serialId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vendorId, productId, serialId);
    }
}
