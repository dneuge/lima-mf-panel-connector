package de.energiequant.limamf.connector;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class USBDeviceId {
    private static final Logger LOGGER = LoggerFactory.getLogger(USBDeviceId.class);

    private final int vendor;
    private final int product;
    private final String serial;

    private static final int MAX_ID = 65535; // all IDs are 16-bit integers

    private USBDeviceId(int vendor, int product, String serial) {
        this.vendor = vendor;
        this.product = product;
        this.serial = serial;
    }

    public int getVendor() {
        return vendor;
    }

    public int getProduct() {
        return product;
    }

    public Optional<String> getSerial() {
        return Optional.ofNullable(serial);
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int vendor = -1;
        private int product = -1;
        private String serial;

        public Builder setVendor(String vendor) {
            return setVendor(Integer.parseUnsignedInt(vendor, 16));
        }

        public Builder setVendor(int vendorId) {
            if (vendorId < 0 || vendorId > MAX_ID) {
                throw new IllegalArgumentException("Invalid vendor ID: " + vendorId);
            }

            this.vendor = vendorId;

            return this;
        }

        public Builder setProduct(String product) {
            return setProduct(Integer.parseUnsignedInt(product, 16));
        }

        public Builder setProduct(int productId) {
            if (productId < 0 || productId > MAX_ID) {
                throw new IllegalArgumentException("Invalid product ID: " + productId);
            }

            this.product = productId;

            return this;
        }

        public Builder setSerial(String serial) {
            if (serial.trim().isEmpty()) {
                LOGGER.debug("blank serial cannot be used; resetting to non-existent");
                serial = null;
            }
            
            this.serial = serial;

            return this;
        }

        public USBDeviceId build() {
            if (vendor < 0) {
                throw new IllegalArgumentException("missing vendor ID");
            }

            if (product < 0) {
                throw new IllegalArgumentException("missing product ID");
            }

            return new USBDeviceId(vendor, product, serial);
        }
    }
}
