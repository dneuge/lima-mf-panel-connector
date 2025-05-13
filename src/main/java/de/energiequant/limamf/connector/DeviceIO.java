package de.energiequant.limamf.connector;

import static de.energiequant.limamf.connector.utils.Locks.withLock;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeviceIO implements Closeable, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceIO.class);

    private static final Charset DEFAULT_CHARACTER_SET = StandardCharsets.ISO_8859_1;

    private final String logPrefix;

    private final AtomicBoolean closing = new AtomicBoolean(false);

    private final FileInputStream fis;
    private final InputStreamReader isr;
    private final ReentrantLock inputLock = new ReentrantLock();

    private final FileOutputStream fos;
    private final OutputStreamWriter osw;
    private final BufferedWriter bw;
    private final ReentrantLock outputLock = new ReentrantLock();

    private final long POLLING_INTERVAL_MILLIS = 10;

    private DeviceIO(File deviceNode, Charset characterSet) {
        logPrefix = "[" + deviceNode.getAbsolutePath() + "] ";

        /*
        RandomAccessFile raf;
        FileChannel channel;
        try {
            raf = new RandomAccessFile(deviceNode, "rw");
            channel = raf.getChannel();
        } catch (IOException ex) {
            LOGGER.warn("{}failed to open device node", logPrefix, ex);
            throw new UncheckedIOException(ex);
        }

        try {
            channel.lock();
        } catch (IOException ex) {
            LOGGER.warn("{}failed to gain file lock", logPrefix, ex);
            throw new UncheckedIOException(ex);
        }
        */

        try {
            fis = new FileInputStream(deviceNode);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        try {
            fos = new FileOutputStream(deviceNode);
        } catch (IOException ex) {
            try {
                fis.close();
            } catch (IOException ex2) {
                LOGGER.warn("{}failed to close FileInputStream while handling FileOutputStream error", logPrefix, ex2);
            }

            throw new UncheckedIOException(ex);
        }

        isr = new InputStreamReader(fis, characterSet);
        osw = new OutputStreamWriter(fos, characterSet);

        bw = new BufferedWriter(osw);
    }

    public static DeviceIO openDeviceNode(File deviceNode) {
        return new DeviceIO(deviceNode, DEFAULT_CHARACTER_SET);
    }

    @Override
    public void close() throws IOException {
        if (!tryClose()) {
            throw new IOException("failed to close");
        }
    }

    public boolean tryClose() {
        AtomicBoolean success = new AtomicBoolean(true);

        closing.set(true);

        //withLocks(
        //    inputLock, outputLock,
        //    () -> {
        try {
            bw.close();
        } catch (IOException ex) {
            LOGGER.warn("{}failed to close BufferedWriter", logPrefix, ex);
            success.set(false);
        }

        try {
            osw.close();
        } catch (IOException ex) {
            LOGGER.warn("{}failed to close OutputStreamWriter", logPrefix, ex);
            success.set(false);
        }

        try {
            isr.close();
        } catch (IOException ex) {
            LOGGER.warn("{}failed to close InputStreamReader", logPrefix, ex);
            success.set(false);
        }

        try {
            fos.close();
        } catch (IOException ex) {
            LOGGER.warn("{}failed to close FileOutputStream", logPrefix, ex);
            success.set(false);
        }

        try {
            fis.close();
        } catch (IOException ex) {
            LOGGER.warn("{}failed to close FileInputStream", logPrefix, ex);
            success.set(false);
        }
        //    }
        //);

        return success.get();
    }

    public void write(String s) {
        withLock(
            outputLock,
            () -> {
                try {
                    bw.write(s);
                } catch (IOException ex) {
                    LOGGER.warn("{}failed writing to device, closing", logPrefix, ex);
                    tryClose();
                    throw new UncheckedIOException(ex);
                }
            }
        );
    }

    public void flush() {
        withLock(
            outputLock,
            () -> {
                try {
                    bw.flush();
                } catch (IOException ex) {
                    if (closing.get()) {
                        LOGGER.debug("{}failed flushing buffer to device, closing (expected due to marked as closing already)", logPrefix, ex);
                    } else {
                        LOGGER.warn("{}unexpectedly failed flushing buffer to device, closing", logPrefix, ex);
                    }
                    tryClose();
                    throw new UncheckedIOException(ex);
                }
            }
        );
    }

    /**
     * Attempts to read characters into the given buffer, non-blocking.
     * Returns 0 if nothing is ready to be read, -1 on EOF.
     *
     * @param buffer buffer to read into
     * @return number of characters read
     */
    public int readAvailableInto(char[] buffer) {
        if (buffer.length == 0) {
            throw new IllegalArgumentException("attempted to read into zero buffer");
        }

        AtomicInteger res = new AtomicInteger(0);

        withLock(
            inputLock,
            () -> {
                try {
                    if (!isr.ready()) {
                        return;
                    }

                    res.set(isr.read(buffer));
                } catch (IOException ex) {
                    LOGGER.warn("{}failed to read from device, closing", logPrefix, ex);
                    tryClose();
                    throw new UncheckedIOException(ex);
                }
            }
        );

        return res.get();
    }

    /**
     * Reads at least one character into the given buffer, blocking.
     * Returns -1 on EOF.
     *
     * @param buffer buffer to read into
     * @return number of characters read
     */
    public int readAtLeastOneInto(char[] buffer) {
        if (buffer.length == 0) {
            throw new IllegalArgumentException("attempted to read into zero buffer");
        }

        AtomicInteger res = new AtomicInteger(0);

        withLock(
            inputLock,
            () -> {
                try {
                    // We are polling as a workaround for not being able to close the reader on shutdown while we are
                    // waiting for blocking read to complete which in turn means we can't unblock on shutdown.
                    // TODO: try with NIO (also has no timed read but is supposed to be closable while waiting)
                    while (!isr.ready()) {
                        Thread.sleep(POLLING_INTERVAL_MILLIS);
                    }
                    res.set(isr.read(buffer));
                } catch (IOException ex) {
                    if (closing.get()) {
                        res.set(-1);
                    } else {
                        LOGGER.warn("{}failed to read from device, closing", logPrefix, ex);
                        tryClose();
                        throw new UncheckedIOException(ex);
                    }
                }
            }
        );

        return res.get();
    }
}
