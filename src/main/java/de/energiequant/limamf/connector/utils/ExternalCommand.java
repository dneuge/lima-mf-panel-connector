package de.energiequant.limamf.connector.utils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalCommand.class);

    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);

    private final File executable;

    public static class Result {
        private final byte[] stdout;

        private Result(byte[] stdout) {
            this.stdout = stdout;
        }

        public String getStandardOutputText() {
            return new String(stdout);
        }

        public List<String> getStandardOutputLines() {
            return Arrays.asList(getStandardOutputText().split("\\R"));
        }

        public Reader getStandardOutputReader() {
            return new InputStreamReader(new ByteArrayInputStream(stdout));
        }
    }

    public ExternalCommand(File executable) {
        this.executable = executable;
    }

    public Result run(String... parameters) {
        return run(Arrays.asList(parameters));
    }

    public Result run(Collection<String> parameters) {
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

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        byte[] buffer = new byte[4096];
        try (
            InputStream is = process.getInputStream();
        ) {
            while (true) {
                int read = is.read(buffer);
                if (read > 0) {
                    baos.write(buffer, 0, read);
                } else if (read == -1) {
                    break;
                } else {
                    throw new IllegalArgumentException("Got invalid result while reading: " + read);
                }
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

        return new Result(baos.toByteArray());
    }

    public Process monitorLines(Collection<String> parameters, Consumer<String> callback) {
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(parameters);

        LOGGER.debug("Monitoring command: {}", command);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to spawn command: " + String.join(" ", command), ex);
        }

        new Thread(
            () -> {
                Throwable innerException = null;
                try (
                    InputStream is = process.getInputStream();
                    InputStreamReader isr = new InputStreamReader(is);
                    BufferedReader br = new BufferedReader(isr);
                ) {
                    String line = br.readLine();
                    while (line != null) {
                        try {
                            callback.accept(line);
                        } catch (Exception ex) {
                            LOGGER.warn("monitor callback failed for {}", command, ex);
                            innerException = ex;
                            break;
                        }
                        line = br.readLine();
                    }
                } catch (IOException ex) {
                    throw new IllegalArgumentException("Failed to read output: " + String.join(" ", command), ex);
                }

                if (innerException != null) {
                    process.destroyForcibly();
                }
            },
            "ExternalCommand " + String.join(" ", command)
        ).start();

        return process;
    }

    public static Optional<ExternalCommand> locateFromPaths(String commandName) {
        return OperatingSystem.locateFromPaths(commandName, File::canExecute)
                              .map(ExternalCommand::new);
    }
}
