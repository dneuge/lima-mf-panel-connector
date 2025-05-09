package de.energiequant.limamf.connector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.apputils.misc.DisclaimerState;

public class Configuration {
    private static final Logger LOGGER = LoggerFactory.getLogger(Configuration.class);

    private static final int VERSION = 1;

    private static final String PROPERTY_VERSION = "configVersion";
    private static final String PROPERTY_DISCLAIMER = "disclaimerAccepted";
    private static final String PROPERTY_USB_INTERFACES_PREFIX = "interfaces.usb.";
    private static final String PROPERTY_USB_INTERFACES_VENDOR = "vendorId";
    private static final String PROPERTY_USB_INTERFACES_PRODUCT = "productId";
    private static final String PROPERTY_USB_INTERFACES_SERIAL = "serialId";
    private static final String PROPERTY_MODULES_PREFIX = "modules.";
    private static final String PROPERTY_MODULE_TYPE = "type";
    private static final String PROPERTY_MODULE_NAME = "name";
    private static final String PROPERTY_MODULE_DEVICE_SERIAL = "serial";
    private static final String PROPERTY_MODULE_PANEL_FACTORY_ID = "panelFactoryId";
    private static final String PROPERTY_MODULE_CONNECTOR_CONFIG = "mcc";
    private static final String PROPERTY_MODULE_CONNECTOR_CONFIG_SERIAL = "mccSerial";

    private final DisclaimerState disclaimerState;

    private File saveLocation;

    private String acceptedDisclaimer;
    private final ObservableCollectionProxy<USBDeviceId, Set<USBDeviceId>> usbInterfaceIds = new ObservableCollectionProxy<>(HashSet::new);
    private final Map<ModuleId, Module> modulesById = new HashMap<>();

    public static class Module {
        private final ModuleId id;
        private final String panelFactoryId;
        private final File connectorConfig;
        private final String connectorConfigSerial;

        private Module(Properties properties, String prefix) {
            this.id = ModuleId.builder()
                              .setType(getMandatoryString(properties, prefix + PROPERTY_MODULE_TYPE))
                              .setName(getMandatoryString(properties, prefix + PROPERTY_MODULE_NAME))
                              .setSerial(getMandatoryString(properties, prefix + PROPERTY_MODULE_DEVICE_SERIAL))
                              .build();
            this.panelFactoryId = getMandatoryString(properties, prefix + PROPERTY_MODULE_PANEL_FACTORY_ID);
            this.connectorConfig = new File(getMandatoryString(properties, prefix + PROPERTY_MODULE_CONNECTOR_CONFIG));
            this.connectorConfigSerial = getOptionalString(properties, prefix + PROPERTY_MODULE_CONNECTOR_CONFIG_SERIAL).orElse(null);
        }

        private Module(ModuleId id, String panelFactoryId, File connectorConfig, String connectorConfigSerial) {
            this.id = id;
            this.panelFactoryId = panelFactoryId;
            this.connectorConfig = connectorConfig;
            this.connectorConfigSerial = connectorConfigSerial;
        }

        public ModuleId getId() {
            return id;
        }

        public String getPanelFactoryId() {
            return panelFactoryId;
        }

        public File getConnectorConfig() {
            return connectorConfig;
        }

        public Optional<String> getConnectorConfigSerial() {
            return Optional.ofNullable(connectorConfigSerial);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Module)) {
                return false;
            }

            Module other = (Module) obj;

            return Objects.equals(this.id, other.id)
                && Objects.equals(this.panelFactoryId, other.panelFactoryId)
                && Objects.equals(this.connectorConfigSerial, other.connectorConfigSerial)
                && Objects.equals(this.connectorConfig, other.connectorConfig);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, panelFactoryId, connectorConfig, connectorConfigSerial);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Module(");

            sb.append(id);

            sb.append(", \"");
            sb.append(panelFactoryId);

            sb.append("\", ");
            if (connectorConfigSerial != null) {
                sb.append("\"");
                sb.append(connectorConfigSerial);
                sb.append("\"@");
            }
            sb.append(connectorConfig);

            sb.append(")");

            return sb.toString();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private ModuleId id;
            private String panelFactoryId;
            private File connectorConfig;
            private String connectorConfigSerial;

            public Builder setId(ModuleId id) {
                this.id = id;
                return this;
            }

            public Builder setPanelFactoryId(String panelFactoryId) {
                this.panelFactoryId = panelFactoryId;
                return this;
            }

            public Builder setConnectorConfig(File connectorConfig) {
                this.connectorConfig = connectorConfig;
                return this;
            }

            public Builder setConnectorConfigSerial(String connectorConfigSerial) {
                this.connectorConfigSerial = connectorConfigSerial;
                return this;
            }

            public Module build() {
                if (id == null) {
                    throw new IllegalArgumentException("missing module ID");
                }

                if (panelFactoryId == null) {
                    throw new IllegalArgumentException("missing panel factory ID");
                }

                if (connectorConfig == null) {
                    throw new IllegalArgumentException("missing connector config file");
                }

                return new Module(id, panelFactoryId, connectorConfig, connectorConfigSerial);
            }
        }
    }

    private Configuration(Properties properties, DisclaimerState disclaimerState) {
        this.disclaimerState = disclaimerState;

        int version = Integer.parseUnsignedInt(getMandatoryString(properties, PROPERTY_VERSION));
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported config file version " + version);
        }

        acceptedDisclaimer = getOptionalString(properties, PROPERTY_DISCLAIMER).orElse(null);
        disclaimerState.setAccepted(disclaimerState.getDisclaimerHash().equals(acceptedDisclaimer));

        streamKeys(properties).filter(x -> x.startsWith(PROPERTY_USB_INTERFACES_PREFIX))
                              .map(x -> x.substring(0, x.indexOf(".", PROPERTY_USB_INTERFACES_PREFIX.length()) + 1))
                              .distinct()
                              .map(x -> parseUSBInterface(properties, x))
                              .forEach(usbInterfaceIds::add);

        streamKeys(properties).filter(x -> x.startsWith(PROPERTY_MODULES_PREFIX))
                              .map(x -> x.substring(0, x.indexOf(".", PROPERTY_MODULES_PREFIX.length()) + 1))
                              .distinct()
                              .map(x -> new Module(properties, x))
                              .forEach(this::putModule);
    }

    public Configuration setSaveLocation(File saveLocation) {
        this.saveLocation = saveLocation;
        return this;
    }

    public Optional<File> getSaveLocation() {
        return Optional.ofNullable(saveLocation);
    }

    public Optional<Module> getModule(ModuleId id) {
        return Optional.ofNullable(modulesById.get(id));
    }

    public void putModule(Module module) {
        Module previous = modulesById.put(module.getId(), module);
        if (previous == null) {
            LOGGER.debug("Added module configuration: {}", module);
        } else {
            LOGGER.debug("Replaced module configuration: {} => {}", previous, module);
        }
    }

    public void removeModule(ModuleId id) {
        Module previous = modulesById.remove(id);
        if (previous == null) {
            LOGGER.debug("Tried to remove unconfigured module: {}", id);
        } else {
            LOGGER.debug("Removed module configuration: {}", previous);
        }
    }

    public Optional<String> getAcceptedDisclaimer() {
        return Optional.ofNullable(acceptedDisclaimer);
    }

    public Set<Module> getModules() {
        return new HashSet<>(modulesById.values());
    }

    public void setModules(Collection<Module> modules) {
        Map<ModuleId, Module> tmp = new HashMap<>();

        for (Module module : modules) {
            Module previous = tmp.put(module.getId(), module);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate module ID \"" + module.getId() + "\": " + module + ", " + previous);
            }
        }

        modulesById.clear();
        modulesById.putAll(tmp);
    }

    public ObservableCollectionProxy<USBDeviceId, Set<USBDeviceId>> getUSBInterfaceIds() {
        return usbInterfaceIds;
    }

    public USBDeviceId parseUSBInterface(Properties properties, String prefix) {
        return USBDeviceId.builder()
                          .setVendor(getMandatoryString(properties, prefix + PROPERTY_USB_INTERFACES_VENDOR))
                          .setProduct(getMandatoryString(properties, prefix + PROPERTY_USB_INTERFACES_PRODUCT))
                          .setSerial(getMandatoryString(properties, prefix + PROPERTY_USB_INTERFACES_SERIAL))
                          .build();
    }

    private static Stream<String> streamKeys(Properties properties) {
        return properties.keySet()
                         .stream()
                         .map(String.class::cast);
    }

    private static String getMandatoryString(Properties properties, String key) {
        return getOptionalString(properties, key)
            .orElseThrow(() -> new IllegalArgumentException("Missing key: " + key));
    }

    private static Optional<String> getOptionalString(Properties properties, String key) {
        return Optional.ofNullable((String) properties.get(key));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Stream<Map.Entry<String, String>> streamEntries(Properties properties) {
        return (Stream) properties.entrySet()
                                  .stream();
    }

    public static Configuration createFromDefaults(DisclaimerState disclaimerState) {
        Properties properties = new Properties();

        try (InputStream is = Configuration.class.getResourceAsStream("default-config.properties")) {
            properties.load(is);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to load default configuration", ex);
        }

        return new Configuration(properties, disclaimerState);
    }

    public static Configuration loadProperties(File file, DisclaimerState disclaimerState) {
        Properties properties = new Properties();

        try (FileInputStream fis = new FileInputStream(file)) {
            properties.load(fis);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to load configuration from " + file.getAbsolutePath(), ex);
        }

        return new Configuration(properties, disclaimerState).setSaveLocation(file);
    }
}
