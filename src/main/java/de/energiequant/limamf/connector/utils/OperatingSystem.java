package de.energiequant.limamf.connector.utils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import de.energiequant.limamf.connector.MissingTool;

public class OperatingSystem {
    private static final String FILE_SEPARATOR = System.getProperty("file.separator");
    private static final String PATH_SEPARATOR = System.getProperty("path.separator");

    private OperatingSystem() {
        // utility class; hide constructor
    }

    public static void requireLinux() {
        String os = System.getProperty("os.name");
        if (!os.toLowerCase().contains("linux")) {
            throw new UnsupportedOperatingSystem("Linux is required; found: \"" + os + "\"");
        }
    }

    public static Optional<File> locateFromPaths(String filename, Predicate<File> predicate) {
        return locateFromPaths(filename, Arrays.asList(System.getenv("PATH").split(Pattern.quote(PATH_SEPARATOR))), predicate);
    }

    public static Optional<File> locateFromPaths(String filename, Collection<String> searchPaths, Predicate<File> predicate) {
        for (String searchPath : searchPaths) {
            if (searchPath.trim().isEmpty()) {
                continue;
            }

            File file = new File(searchPath + FILE_SEPARATOR + filename);
            if (file.exists() && predicate.test(file)) {
                return Optional.of(file);
            }
        }

        return Optional.empty();
    }

    public static void disableSerialEcho(File deviceNode) {
        requireLinux();

        File stty = locateFromPaths("stty", x -> x.canExecute()).orElseThrow(() -> new MissingTool("stty could not be found"));

        int exitCode;
        try {
            exitCode = Runtime.getRuntime()
                              .exec(new String[]{stty.getAbsolutePath(), "-F", deviceNode.getAbsolutePath(), "-echo"})
                              .waitFor();
        } catch (InterruptedException | IOException ex) {
            throw new CommandFailed("failed to disable echo on " + deviceNode, ex);
        }

        if (exitCode != 0) {
            throw new CommandFailed("failed to disable echo on " + deviceNode + ", exit code " + exitCode);
        }
    }

    private static class CommandFailed extends RuntimeException {
        CommandFailed(String msg) {
            super(msg);
        }

        CommandFailed(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private static class UnsupportedOperatingSystem extends RuntimeException {
        UnsupportedOperatingSystem(String msg) {
            super(msg);
        }
    }
}
