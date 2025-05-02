package de.energiequant.limamf.connector.utils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class TimeUtils {
    private TimeUtils() {
        // utility class; hide constructor
    }

    public static Optional<Long> millisRemaining(Instant end) {
        Duration remaining = Duration.between(Instant.now(), end);
        if (remaining.isNegative() || remaining.isZero()) {
            return Optional.empty();
        }

        return Optional.of(remaining.toMillis());
    }

    public static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
