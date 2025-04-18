package de.energiequant.limamf.connector;

import static de.energiequant.limamf.connector.utils.TimeUtils.millisRemaining;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.energiequant.limamf.compat.protocol.CommandMessage;
import de.energiequant.limamf.compat.protocol.CommandMessageDecoder;
import de.energiequant.limamf.compat.protocol.GetInfoMessage;
import de.energiequant.limamf.connector.utils.OperatingSystem;

public class DeviceCommunicator {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceCommunicator.class);

    private final String logPrefix;
    private final String protocolVersion;

    private final DeviceIO io;
    private final BiConsumer<DeviceCommunicator, CommandMessage> receiveCallback;

    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final Deque<String> sendQueue = new LinkedList<>();
    private final AtomicInteger ioUsers = new AtomicInteger();

    private final Thread sendThread;
    private final Thread receiveThread;

    private static final long CHECK_INTERVAL = 5000;

    public DeviceCommunicator(File deviceNode, BiConsumer<DeviceCommunicator, CommandMessage> receiveCallback) {
        this(deviceNode, null, receiveCallback);
    }

    public DeviceCommunicator(File deviceNode, String protocolVersion, BiConsumer<DeviceCommunicator, CommandMessage> receiveCallback) {
        this.logPrefix = "[" + deviceNode.getAbsolutePath() + "] ";

        this.protocolVersion = protocolVersion;
        this.receiveCallback = receiveCallback;

        // serial echo needs to be disabled, otherwise Linux sends everything it received back to the device
        OperatingSystem.disableSerialEcho(deviceNode);

        this.io = DeviceIO.openDeviceNode(deviceNode);

        ioUsers.set(2);

        receiveThread = new Thread(this::receiveLoop);
        sendThread = new Thread(this::sendLoop);

        receiveThread.start();
        sendThread.start();
    }

    private void receiveLoop() {
        LOGGER.debug("{}[recv] thread starting", logPrefix);

        CommandMessageDecoder decoder = new CommandMessageDecoder();

        char[] tmp = new char[4096];
        StringBuilder collector = new StringBuilder();
        boolean escaped = false;

        while (!shutdown.get()) {
            int res = io.readAtLeastOneInto(tmp);
            if (res == -1) {
                LOGGER.info("{}[recv] stream has been closed", logPrefix);
                break;
            }
            if (res < 1) {
                LOGGER.warn("{}[recv] unexpected result on read attempt: {}", logPrefix, res);
                break;
            }

            for (int i = 0; i < res; i++) {
                char ch = tmp[i];

                if (escaped) {
                    escaped = false;
                } else {
                    if (ch == CommandMessage.ESCAPE_CHARACTER) {
                        escaped = true;
                    } else if (ch == CommandMessage.COMMAND_SEPARATOR) {
                        String s = trimLeadingLineBreaks(collector.toString());
                        collector = new StringBuilder();

                        LOGGER.debug("{}[recv] received: \"{}\"", logPrefix, s);

                        CommandMessage msg;
                        try {
                            msg = decoder.deserialize(s);
                        } catch (Exception ex) {
                            LOGGER.warn("{}[recv] failed to parse message \"{}\"", logPrefix, s, ex);
                            break;
                        }

                        try {
                            receiveCallback.accept(this, msg);
                        } catch (Exception ex) {
                            LOGGER.warn("{}[recv] failed to handle message {}", logPrefix, msg, ex);
                            break;
                        }

                        continue;
                    }
                }

                collector.append(ch);
            }
        }

        LOGGER.debug("{}[recv] shutdown", logPrefix);
        shutdown.set(true);
        io.tryClose();

        LOGGER.debug("{}[recv] thread terminated", logPrefix);
    }

    private void sendLoop() {
        LOGGER.debug("{}[send] thread starting", logPrefix);

        Deque<String> processing = new LinkedList<>();
        while (!shutdown.get()) {
            synchronized (sendQueue) {
                if (!sendQueue.isEmpty()) {
                    processing.addAll(sendQueue);
                    sendQueue.clear();
                }
            }

            if (!processing.isEmpty()) {
                try {
                    while (!shutdown.get() && !processing.isEmpty()) {
                        String s = processing.removeFirst();

                        LOGGER.debug("{}[send] sending: \"{}\"", logPrefix, s);
                        io.write(s);
                    }

                    io.flush();
                } catch (Exception ex) {
                    LOGGER.warn("{}[send] failed to send queue to device", logPrefix, ex);
                    break;
                }
            }

            synchronized (sendQueue) {
                if (!shutdown.get() && sendQueue.isEmpty()) {
                    try {
                        sendQueue.wait(CHECK_INTERVAL);
                    } catch (InterruptedException ex) {
                        LOGGER.warn("{}[send] interrupted while waiting on queue", logPrefix, ex);
                        break;
                    }
                }
            }
        }

        LOGGER.debug("{}[send] shutdown", logPrefix);
        shutdown.set(true);
        io.tryClose();

        LOGGER.debug("{}[send] thread terminated", logPrefix);
    }

    public void send(CommandMessage msg) {
        if (protocolVersion == null) {
            if (!(msg instanceof GetInfoMessage)) {
                LOGGER.warn("Only device identification must be requested from device while version is unknown, got: {}", msg);
                throw new IllegalArgumentException("Only device identification must be requested from device while version is unknown, got: " + msg);
            }
        } else if (!msg.isTestedVersion(protocolVersion)) {
            if (msg.isCriticalOperation()) {
                LOGGER.warn("Refusing to send critical message to device running untested version {}: {}", protocolVersion, msg);
                throw new IllegalArgumentException("Critical messages must have been tested; interfaced with version " + protocolVersion + ": " + msg);
            }

            LOGGER.debug("Non-critical message has not been tested for {}: {}", protocolVersion, msg);
        }

        String serialized = msg.serialize() + CommandMessage.COMMAND_SEPARATOR;

        synchronized (sendQueue) {
            sendQueue.add(serialized);
            sendQueue.notifyAll();
        }
    }

    public void shutdownAsync() {
        LOGGER.debug("{}requesting shutdown", logPrefix);
        shutdown.set(true);

        // wake up send thread which will close streams when terminating which unblocks receive thread
        synchronized (sendQueue) {
            sendQueue.notifyAll();
        }
    }

    public boolean waitForShutdown(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);

        shutdownAsync();

        boolean success = true;
        success &= joinUntil(receiveThread, deadline);
        success &= joinUntil(sendThread, deadline);

        return success;
    }

    private boolean joinUntil(Thread thread, Instant deadline) throws InterruptedException {
        if (!thread.isAlive()) {
            return true;
        }

        Optional<Long> remaining = millisRemaining(deadline);
        if (!remaining.isPresent()) {
            return false;
        }

        thread.join(remaining.get());

        return true;
    }

    private void releaseIO() {
        int remainingUsers = ioUsers.decrementAndGet();
        if (remainingUsers > 0) {
            return;
        }

        // this was the last user, close IO streams
        io.tryClose();
    }

    private String trimLeadingLineBreaks(String s) {
        int start = 0;

        for (char ch : s.toCharArray()) {
            if (ch != '\r' && ch != '\n') {
                break;
            }

            start++;
        }

        return (start == 0) ? s : s.substring(start);
    }
}
