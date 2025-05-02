package de.energiequant.limamf.connector;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.cliftonlabs.json_simple.JsonException;
import com.github.cliftonlabs.json_simple.JsonKey;
import com.github.cliftonlabs.json_simple.JsonObject;
import com.github.cliftonlabs.json_simple.Jsoner;

import de.energiequant.limamf.connector.utils.ExternalCommand;
import de.energiequant.limamf.connector.utils.OperatingSystem;

public class MacOSLogWrapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(MacOSLogWrapper.class);

    private final ExternalCommand log;

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^(\\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01]) ([01]\\d|2[0-3]):([0-5]\\d):([0-5]\\d|60)\\.(\\d{6})([-+])([01]\\d|2[0-4])([0-5]\\d)$");
    private static final int TIMESTAMP_PATTERN_YEAR = 1;
    private static final int TIMESTAMP_PATTERN_MONTH = 2;
    private static final int TIMESTAMP_PATTERN_DAY = 3;
    private static final int TIMESTAMP_PATTERN_HOUR = 4;
    private static final int TIMESTAMP_PATTERN_MINUTE = 5;
    private static final int TIMESTAMP_PATTERN_SECOND = 6;
    private static final int TIMESTAMP_PATTERN_MICRO = 7;
    private static final int TIMESTAMP_PATTERN_OFFSET_DIRECTION = 8;
    private static final int TIMESTAMP_PATTERN_OFFSET_HOURS = 9;
    private static final int TIMESTAMP_PATTERN_OFFSET_MINUTES = 10;

    public MacOSLogWrapper() {
        OperatingSystem.requireMacOS();

        log = ExternalCommand.locateFromPaths("log")
                             .orElseThrow(() -> new MissingTool("log command could not be found"));
    }

    public static class LogEntry {
        private final Instant timestampParsed;
        private final Instant timestampLogged;
        private final int uid;
        private final int pid;
        private final String eventMessage;
        private final String processImagePath;
        private final String senderImagePath;
        private final String category;
        private final String subsystem;

        LogEntry(JsonObject json) {
            timestampParsed = Instant.now();
            timestampLogged = getNonBlankString(json, LogStreamKey.TIMESTAMP).flatMap(MacOSLogWrapper::parseTimestamp)
                                                                             .orElse(null);

            uid = getInt(json, LogStreamKey.UID).orElse(-1);
            pid = getInt(json, LogStreamKey.PID).orElse(-1);

            eventMessage = getNonBlankString(json, LogStreamKey.EVENT_MESSAGE).orElse(null);
            processImagePath = getNonBlankString(json, LogStreamKey.PROCESS_IMAGE_PATH).orElse(null);
            senderImagePath = getNonBlankString(json, LogStreamKey.SENDER_IMAGE_PATH).orElse(null);
            category = getNonBlankString(json, LogStreamKey.CATEGORY).orElse(null);
            subsystem = getNonBlankString(json, LogStreamKey.SUBSYSTEM).orElse(null);
        }

        public Instant getTimestampParsed() {
            return timestampParsed;
        }

        public Optional<Instant> getTimestampLogged() {
            return Optional.ofNullable(timestampLogged);
        }

        public Optional<Integer> getUid() {
            return (uid >= 0) ? Optional.of(uid) : Optional.empty();
        }

        public Optional<Integer> getPid() {
            return (pid >= 0) ? Optional.of(pid) : Optional.empty();
        }

        public Optional<String> getEventMessage() {
            return Optional.ofNullable(eventMessage);
        }

        public Optional<String> getProcessImagePath() {
            return Optional.ofNullable(processImagePath);
        }

        public Optional<String> getSenderImagePath() {
            return Optional.ofNullable(senderImagePath);
        }

        public Optional<String> getCategory() {
            return Optional.ofNullable(category);
        }

        public Optional<String> getSubsystem() {
            return Optional.ofNullable(subsystem);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("LogEntry(parsed=");

            sb.append(timestampParsed);

            if (timestampLogged != null) {
                sb.append(", logged=");
                sb.append(timestampLogged);
            }

            if (uid >= 0) {
                sb.append(", uid=");
                sb.append(uid);
            }

            if (pid >= 0) {
                sb.append(", pid=");
                sb.append(pid);
            }

            if (subsystem != null) {
                sb.append(", subsystem=");
                sb.append(subsystem);
            }

            if (category != null) {
                sb.append(", category=");
                sb.append(category);
            }

            if (eventMessage != null) {
                sb.append(", eventMessage=\"");
                sb.append(eventMessage);
                sb.append("\"");
            }

            if (processImagePath != null) {
                sb.append(", processImage=");
                sb.append(processImagePath);
            }

            if (senderImagePath != null) {
                sb.append(", senderImage=");
                sb.append(senderImagePath);
            }

            sb.append(")");

            return sb.toString();
        }
    }

    private enum LogStreamKey implements JsonKey {
        CATEGORY("category"),
        EVENT_MESSAGE("eventMessage"),
        PID("processID"),
        PROCESS_IMAGE_PATH("processImagePath"),
        SENDER_IMAGE_PATH("senderImagePath"),
        SUBSYSTEM("subsystem"),
        TIMESTAMP("timestamp"),
        UID("userID");

        private final String key;

        LogStreamKey(String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return null;
        }
    }

    public class LogStreamMonitor {
        private final Consumer<LogEntry> callback;
        private final Process process;

        private LogStreamMonitor(int pid, Consumer<LogEntry> callback) {
            this.callback = callback;

            List<String> parameters = new ArrayList<>();
            parameters.add("stream");

            parameters.add("--style");
            parameters.add("ndjson");

            if (pid >= 0) {
                parameters.add("--process");
                parameters.add(Integer.toString(pid));
            }

            // NDJSON is specified to always be encoded as UTF-8: https://github.com/ndjson/ndjson-spec
            process = log.monitorLines(parameters, StandardCharsets.UTF_8, this::onLineReceived);
        }

        private void onLineReceived(String line) {
            LOGGER.trace("received log stream line: {}", line);

            JsonObject logEntryJson;
            try {
                logEntryJson = (JsonObject) Jsoner.deserialize(line);
            } catch (JsonException | ClassCastException ex) {
                LOGGER.debug("Ignoring unparseable log stream line: {}", line, ex);
                return;
            }

            LogEntry logEntry = new LogEntry(logEntryJson);
            try {
                callback.accept(logEntry);
            } catch (Exception ex) {
                LOGGER.warn("callback failed to process {}", logEntry, ex);
            }
        }

        public void terminate() {
            process.destroyForcibly();
        }
    }

    private static Optional<Instant> parseTimestamp(String s) {
        Matcher matcher = TIMESTAMP_PATTERN.matcher(s);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        int year = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_YEAR));
        int month = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_MONTH));
        int day = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_DAY));
        int hour = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_HOUR));
        int minute = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_MINUTE));
        int second = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_SECOND));
        int micros = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_MICRO));

        String offsetDirection = matcher.group(TIMESTAMP_PATTERN_OFFSET_DIRECTION);
        int offsetHours = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_OFFSET_HOURS));
        int offsetMinutes = Integer.parseInt(matcher.group(TIMESTAMP_PATTERN_OFFSET_MINUTES));

        int offsetSeconds = (offsetHours * 60 + offsetMinutes) * 60;
        if ("-".equals(offsetDirection)) {
            offsetSeconds = -offsetSeconds;
        } else if (!"+".equals(offsetDirection)) {
            return Optional.empty();
        }

        return Optional.of(OffsetDateTime.of(
            year,
            month,
            day,
            hour,
            minute,
            second,
            micros * 1000,
            ZoneOffset.ofTotalSeconds(offsetSeconds)
        ).toInstant());
    }

    public LogStreamMonitor stream(Consumer<LogEntry> callback) {
        return stream(-1, callback);
    }

    public LogStreamMonitor stream(int pid, Consumer<LogEntry> callback) {
        return new LogStreamMonitor(pid, callback);
    }

    private static Optional<String> getNonBlankString(JsonObject json, JsonKey key) {
        Optional<String> res = getString(json, key);

        if (!res.isPresent() || res.get().trim().isEmpty()) {
            return Optional.empty();
        }

        return res;
    }

    private static Optional<String> getString(JsonObject json, JsonKey key) {
        if (!json.containsKey(key.getKey())) {
            return Optional.empty();
        }

        return Optional.ofNullable(json.getStringOrDefault(key));
    }

    private static OptionalInt getInt(JsonObject json, JsonKey key) {
        if (!json.containsKey(key.getKey())) {
            return OptionalInt.empty();
        }

        return OptionalInt.of(json.getInteger(key));
    }
}
