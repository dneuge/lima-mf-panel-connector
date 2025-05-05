package de.energiequant.limamf.connector;

import java.util.Objects;
import java.util.Optional;

public class USBDeviceId {
    private int vendor = -1;
    private int product = -1;
    private String serial;

    public Optional<Integer> getVendor() {
        if (vendor < 0) {
            return Optional.empty();
        }

        return Optional.of(vendor);
    }

    public USBDeviceId setVendor(String vendor) {
        return setVendorId(Integer.parseUnsignedInt(vendor, 16));
    }

    public USBDeviceId setVendorId(int vendorId) {
        this.vendor = vendorId;
        return this;
    }

    public Optional<Integer> getProduct() {
        if (product < 0) {
            return Optional.empty();
        }

        return Optional.of(product);
    }

    public USBDeviceId setProduct(String product) {
        return setProduct(Integer.parseUnsignedInt(product, 16));
    }

    public USBDeviceId setProduct(int productId) {
        this.product = productId;
        return this;
    }

    public Optional<String> getSerial() {
        return Optional.ofNullable(serial);
    }

    public USBDeviceId setSerial(String serial) {
        this.serial = serial;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("USBDeviceId(");

        sb.append(String.format("%04X", vendor));
        sb.append("/");
        sb.append(String.format("%04X", product));

        if (serial != null) {
            sb.append(", \"");
            sb.append(serial);
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

        return this.vendor == other.vendor
            && this.product == other.product
            && Objects.equals(this.serial, other.serial);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vendor, product, serial);
    }
}
