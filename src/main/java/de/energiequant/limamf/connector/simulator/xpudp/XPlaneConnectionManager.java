package de.energiequant.limamf.connector.simulator.xpudp;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.painer.xplane.XPlane;
import de.painer.xplane.XPlaneDiscoveryListener;
import de.painer.xplane.XPlaneInstance;

public class XPlaneConnectionManager implements XPlaneDiscoveryListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(XPlaneConnectionManager.class);

    private final XPlaneUDPClient client;

    private DataReceiver receiver = null;

    public XPlaneConnectionManager(XPlaneUDPClient client) {
        this.client = client;
    }

    @Override
    public void lostInstance(XPlaneInstance instance) {
        synchronized (this) {
            if ((receiver != null) && receiver.isConnectedTo(instance)) {
                LOGGER.warn("lost connected instance {}", instance.getAddress());
                receiver = null;
                client.onXPlaneDisconnected();
            } else {
                LOGGER.info("lost unconnected instance {}", instance.getAddress());
            }
        }
    }

    @Override
    public void foundInstance(XPlaneInstance instance) {
        synchronized (this) {
            if (receiver != null) {
                LOGGER.info("found new instance but old one is still connected; ignoring: {}", instance.getAddress());
                return;
            }

            LOGGER.info("found instance, connecting: {}", instance.getAddress());
            XPlane xplane = null;
            try {
                xplane = instance.connect();
                receiver = new DataReceiver(instance, client::onDataSnapshot);
            } catch (IOException e) {
                LOGGER.error("failed to connect", e);
                return;
            }

            client.onXPlaneConnected(xplane);
        }

        LOGGER.info("connected");
    }

    public DataReceiver getReceiver() {
        synchronized (this) {
            return receiver;
        }
    }

    public void shutdown() {
        synchronized (this) {
            if (receiver != null) {
                receiver.unsubscribe();
            }
            receiver = null;
        }
    }
}
