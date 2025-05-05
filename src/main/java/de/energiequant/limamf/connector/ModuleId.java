package de.energiequant.limamf.connector;

import java.util.Objects;

public class ModuleId {
    private final String type;
    private final String name;
    private final String serial;

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
            this.type = type;
            return this;
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setSerial(String serial) {
            this.serial = serial;
            return this;
        }

        public ModuleId build() {
            return new ModuleId(type, name, serial);
        }
    }
}
