package de.energiequant.limamf.connector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final String PROPERTY_MODULE_CONNECTOR_CONFIG = "mcc";
    private static final String PROPERTY_MODULE_CONNECTOR_CONFIG_SERIAL = "mccSerial";

    private String acceptedDisclaimer;
    private final ObservableCollectionProxy<USBDeviceId, Set<USBDeviceId>> usbInterfaceIds = new ObservableCollectionProxy<>(HashSet::new);
    private final Collection<Module> modules = new ArrayList<>();

    public static class Module {
        private final ModuleId id;
        private final File connectorConfig;
        private final String connectorConfigSerial;

        private Module(Properties properties, String prefix) {
            this.id = ModuleId.builder()
                              .setType(getMandatoryString(properties, prefix + PROPERTY_MODULE_TYPE))
                              .setName(getMandatoryString(properties, prefix + PROPERTY_MODULE_NAME))
                              .setSerial(getMandatoryString(properties, prefix + PROPERTY_MODULE_DEVICE_SERIAL))
                              .build();
            this.connectorConfig = new File(getMandatoryString(properties, prefix + PROPERTY_MODULE_CONNECTOR_CONFIG));
            this.connectorConfigSerial = getOptionalString(properties, prefix + PROPERTY_MODULE_CONNECTOR_CONFIG_SERIAL).orElse(null);
        }

        public ModuleId getId() {
            return id;
        }

        public File getConnectorConfig() {
            return connectorConfig;
        }

        public Optional<String> getConnectorConfigSerial() {
            return Optional.ofNullable(connectorConfigSerial);
        }
    }

    private Configuration(Properties properties) {
        int version = Integer.parseUnsignedInt(getMandatoryString(properties, PROPERTY_VERSION));
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported config file version " + version);
        }

        acceptedDisclaimer = getOptionalString(properties, PROPERTY_DISCLAIMER).orElse(null);

        streamKeys(properties).filter(x -> x.startsWith(PROPERTY_USB_INTERFACES_PREFIX))
                              .map(x -> x.substring(0, x.indexOf(".", PROPERTY_USB_INTERFACES_PREFIX.length()) + 1))
                              .distinct()
                              .map(x -> parseUSBInterface(properties, x))
                              .forEach(usbInterfaceIds::add);

        streamKeys(properties).filter(x -> x.startsWith(PROPERTY_MODULES_PREFIX))
                              .map(x -> x.substring(0, x.indexOf(".", PROPERTY_MODULES_PREFIX.length()) + 1))
                              .distinct()
                              .map(x -> new Module(properties, x))
                              .forEach(modules::add);
    }

    public Optional<String> getAcceptedDisclaimer() {
        return Optional.ofNullable(acceptedDisclaimer);
    }

    public Collection<Module> getModules() {
        return new ArrayList<>(modules);
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

    public static Configuration loadProperties(File file) {
        Properties properties = new Properties();

        try (FileInputStream fis = new FileInputStream(file)) {
            properties.load(fis);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to load configuration from " + file.getAbsolutePath(), ex);
        }

        return new Configuration(properties);
    }
}
