package de.energiequant.limamf.connector.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalCommand.class);

    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);

    private final File executable;

    public ExternalCommand(File executable) {
        this.executable = executable;
    }

    public List<String> run(String... parameters) {
        return run(Arrays.asList(parameters));
    }

    public List<String> run(Collection<String> parameters) {
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(parameters);

        LOGGER.debug("Running command: {}", command);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to spawn command: " + String.join(" ", command), ex);
        }

        List<String> lines = new ArrayList<>();
        try (
            InputStreamReader isr = new InputStreamReader(process.getInputStream());
            BufferedReader br = new BufferedReader(isr);
        ) {
            String line = br.readLine();
            while (line != null) {
                lines.add(line);
                line = br.readLine();
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read output: " + String.join(" ", command), ex);
        }

        try {
            long seconds = TERMINATION_TIMEOUT.getSeconds();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOGGER.warn("Command timed out after {} seconds: {}", seconds, command);
            }
        } catch (InterruptedException ex) {
            throw new RuntimeException("interrupted during command termination", ex);
        }

        return lines;
    }

    public static Optional<ExternalCommand> locateFromPaths(String commandName) {
        return OperatingSystem.locateFromPaths(commandName, File::canExecute)
                              .map(ExternalCommand::new);
    }
}
