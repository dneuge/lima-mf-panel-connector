package de.energiequant.limamf.connector.simulator.xpudp;

import java.io.IOException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.painer.xplane.XPlane;
import de.painer.xplane.XPlaneInstance;
import de.painer.xplane.XPlaneListener;
import de.painer.xplane.data.Position;

public class DataReceiver implements XPlaneListener {
    // NOTE: data access is not atomic across fields and timestamp is tracked regardless of fields

    private static final Logger LOGGER = LoggerFactory.getLogger(DataReceiver.class);

    private static final int UPDATES_PER_SECOND = 2;
    private static final String DATAREF_CL650_CCP_BRIGHTNESS = "CL650/lamps/integ/1A4FH_ccp1";
    private static final String[] SUBSCRIBE_DATAREFS = {
        DATAREF_CL650_CCP_BRIGHTNESS
    };

    private final XPlaneInstance xplaneInstance;
    private final XPlane xplane;
    private final Consumer<Snapshot> snapshotListener;

    private float cl650CCPBrightness;
    private long lastUpdate;

    public DataReceiver(XPlaneInstance xplaneInstance, Consumer<Snapshot> snapshotListener) throws IOException {
        this.snapshotListener = snapshotListener;
        this.xplaneInstance = xplaneInstance;
        this.xplane = xplaneInstance.connect();
        subscribe();
    }

    public boolean isConnectedTo(XPlaneInstance xplane) {
        return this.xplaneInstance == xplane;
    }

    private void subscribe() {
        xplane.addXPlaneListener(this);

        for (String dataref : SUBSCRIBE_DATAREFS) {
            xplane.watchDataref(dataref, UPDATES_PER_SECOND);
            // LOGGER.info("subscribed {}", dataref);
        }

        LOGGER.info("all subscribed");
    }

    public void unsubscribe() {
        for (String dataref : SUBSCRIBE_DATAREFS) {
            xplane.unwatchDataref(dataref);
        }
    }

    @Override
    public void receivedPosition(Position position) {
        // not subscribed, ignore
    }

    @Override
    public void receivedDataref(String dataref, float value) {
        synchronized (this) {
            if (DATAREF_CL650_CCP_BRIGHTNESS.equals(dataref)) {
                this.cl650CCPBrightness = value;
            } else {
                return;
            }

            lastUpdate = System.currentTimeMillis();
        }

        snapshotListener.accept(getSnapshot());
    }

    public Snapshot getSnapshot() {
        Snapshot out = new Snapshot();

        synchronized (this) {
            out.cl650CCPBrightness = this.cl650CCPBrightness;
            out.lastUpdate = this.lastUpdate;
        }

        return out;
    }

    public class Snapshot {
        public float cl650CCPBrightness;
        public long lastUpdate;
    }
}
