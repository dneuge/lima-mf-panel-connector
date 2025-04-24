package de.energiequant.limamf.connector;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.connector.utils.ExternalCommand;

public class IORegWrapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(IORegWrapper.class);

    private final ExternalCommand ioreg;

    public static class USBElement {
        private String ioObjectClass;
        private Integer vendorId;
        private String vendorName;
        private Integer productId;
        private String productName;
        private String serialId;

        private USBElement parent;
        private List<USBElement> children = Collections.emptyList();

        private XMLPropertyListConfiguration rawData;

        private USBElement() {
            // restrict access
        }

        public Optional<String> getIOObjectClass() {
            return Optional.ofNullable(ioObjectClass);
        }

        public Optional<Integer> getVendorId() {
            return Optional.ofNullable(vendorId);
        }

        public Optional<String> getVendorName() {
            return Optional.ofNullable(vendorName);
        }

        public Optional<Integer> getProductId() {
            return Optional.ofNullable(productId);
        }

        public Optional<String> getProductName() {
            return Optional.ofNullable(productName);
        }

        public Optional<String> getSerialId() {
            return Optional.ofNullable(serialId);
        }

        public USBElement getParent() {
            return parent;
        }

        public List<USBElement> getChildren() {
            return children;
        }

        public XMLPropertyListConfiguration getRawData() {
            return rawData;
        }

        public List<USBElement> flatten() {
            List<USBElement> out = new ArrayList<>();

            out.add(this);
            out.addAll(children);

            return out;
        }

        public List<USBElement> flattenRecursively() {
            return flattenRecursively(Arrays.asList(this));
        }

        public static List<USBElement> flattenRecursively(Collection<USBElement> elems) {
            List<USBElement> out = new ArrayList<>();
            flattenRecursively(elems, out);
            return out;
        }

        private static void flattenRecursively(Collection<USBElement> in, Collection<USBElement> out) {
            for (USBElement elem : in) {
                out.add(elem);
                flattenRecursively(elem.children, out);
            }
        }

        @Override
        public String toString() {
            boolean isFirst = true;
            StringBuilder sb = new StringBuilder("IORegUSB(");

            if (ioObjectClass != null) {
                sb.append("ioObjectClass=\"");
                sb.append(ioObjectClass);
                sb.append("\"");
                isFirst = false;
            }

            if (vendorId != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("vendorId=");
                sb.append(String.format("%04x", vendorId));
                isFirst = false;
            }

            if (productId != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("productId=");
                sb.append(String.format("%04x", productId));
                isFirst = false;
            }

            if (vendorName != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("vendorName=\"");
                sb.append(vendorName);
                sb.append("\"");
                isFirst = false;
            }

            if (productName != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("productName=\"");
                sb.append(productName);
                sb.append("\"");
                isFirst = false;
            }

            if (serialId != null) {
                if (!isFirst) {
                    sb.append(", ");
                }
                sb.append("serialId=\"");
                sb.append(serialId);
                sb.append("\"");
                isFirst = false;
            }

            if (!children.isEmpty()) {
                if (serialId != null) {
                    if (!isFirst) {
                        sb.append(", ");
                    }
                    sb.append("children=[");
                    boolean isFirstChild = true;
                    for (USBElement child : children) {
                        if (!isFirstChild) {
                            sb.append(", ");
                        } else {
                            isFirstChild = false;
                        }
                        sb.append(child);
                    }
                    sb.append("]");
                    isFirst = false;
                }
            }

            sb.append(")");

            return sb.toString();
        }
    }

    public IORegWrapper() {
        ioreg = ExternalCommand.locateFromPaths("ioreg")
                               .orElseThrow(() -> new MissingTool("ioreg is required for device discovery"));
    }

    public List<USBElement> listUSB() {
        ExternalCommand.Result res = ioreg.run("-r", "-c", "IOUSBHostDevice", "-l", "-a");

        XMLPropertyListConfiguration plist = new XMLPropertyListConfiguration();
        try (
            Reader reader = res.getStandardOutputReader();
        ) {
            plist.read(reader);
        } catch (IOException | ConfigurationException ex) {
            throw new IllegalArgumentException("failed to read/parse command output", ex);
        }

        return traverseUSB(plist.getList(XMLPropertyListConfiguration.class, ""), null);
    }

    private List<USBElement> traverseUSB(Collection<XMLPropertyListConfiguration> elems, USBElement parent) {
        List<USBElement> out = new ArrayList<>();

        for (XMLPropertyListConfiguration elem : elems) {
            Integer vendorId = elem.getInteger("idVendor", null);
            Integer productId = elem.getInteger("idProduct", null);

            List<XMLPropertyListConfiguration> children = elem.getList(XMLPropertyListConfiguration.class, "IORegistryEntryChildren");

            if (vendorId != null && productId != null) {
                USBElement usbElement = new USBElement();
                out.add(usbElement);

                usbElement.parent = parent;
                usbElement.vendorId = vendorId;
                usbElement.productId = productId;
                usbElement.rawData = elem;

                usbElement.vendorName = elem.getString("USB Vendor Name", null);
                usbElement.productName = elem.getString("USB Product Name", null);
                usbElement.serialId = elem.getString("USB Serial Number", null);

                usbElement.ioObjectClass = elem.getString("IOObjectClass", null);

                if (children != null) {
                    usbElement.children = traverseUSB(children, usbElement);
                }
            } else if (children != null) {
                out.addAll(traverseUSB(children, parent));
            }
        }

        return out;
    }
}
