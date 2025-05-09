package de.energiequant.limamf.connector;

import java.util.Comparator;
import java.util.Objects;

public class ModuleId implements Comparable<ModuleId> {
    private final String type;
    private final String name;
    private final String serial;

    private static final Comparator<ModuleId> COMPARATOR = Comparator.comparing(ModuleId::getType)
                                                                     .thenComparing(ModuleId::getName)
                                                                     .thenComparing(ModuleId::getSerial);

    private ModuleId(String type, String name, String serial) {
        this.type = type;
        this.name = name;
        this.serial = serial;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getSerial() {
        return serial;
    }

    @Override
    public int compareTo(ModuleId o) {
        return COMPARATOR.compare(this, o);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ModuleId)) {
            return false;
        }

        ModuleId other = (ModuleId) obj;

        return Objects.equals(this.serial, other.serial)
            && Objects.equals(this.name, other.name)
            && Objects.equals(this.type, other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serial, name, type);
    }

    @Override
    public String toString() {
        return "ModuleId(serial=\"" + serial + "\", type=\"" + type + "\", name=\"" + name + "\")";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String type;
        private String name;
        private String serial;

        public Builder setType(String type) {
            if (type.trim().isEmpty()) {
                throw new IllegalArgumentException("type must not be blank");
            }

            this.type = type;

            return this;
        }

        public Builder setName(String name) {
            if (name.trim().isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }

            this.name = name;

            return this;
        }

        public Builder setSerial(String serial) {
            if (serial.trim().isEmpty()) {
                throw new IllegalArgumentException("serial must not be blank");
            }

            this.serial = serial;

            return this;
        }

        public ModuleId build() {
            if (type == null) {
                throw new IllegalArgumentException("missing type");
            }

            if (name == null) {
                throw new IllegalArgumentException("missing name");
            }

            if (serial == null) {
                throw new IllegalArgumentException("missing serial");
            }

            return new ModuleId(type, name, serial);
        }
    }
}
