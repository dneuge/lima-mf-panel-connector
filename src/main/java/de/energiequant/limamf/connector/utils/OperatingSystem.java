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
    private static final String HOME_DIRECTORY = System.getProperty("user.home");

    private OperatingSystem() {
        // utility class; hide constructor
    }

    public static boolean isLinux() {
        String os = System.getProperty("os.name");
        return os.toLowerCase().contains("linux");
    }

    public static void requireLinux() {
        if (!isLinux()) {
            throw new UnsupportedOperatingSystem("Linux is required; found: \"" + System.getProperty("os.name") + "\"");
        }
    }

    public static boolean isMacOS() {
        String os = System.getProperty("os.name");
        return os.toLowerCase().contains("mac os");
    }

    public static void requireMacOS() {
        if (!isMacOS()) {
            throw new UnsupportedOperatingSystem("macOS is required; found: \"" + System.getProperty("os.name") + "\"");
        }
    }

    public static File getUserConfigDirectory() {
        if (isMacOS()) {
            return new File(HOME_DIRECTORY + FILE_SEPARATOR + "Library" + FILE_SEPARATOR + "Application Support");
        } else {
            return new File(HOME_DIRECTORY + FILE_SEPARATOR + ".config");
        }
    }

    public static File resolveInUserConfigDirectory(String path) {
        return new File(getUserConfigDirectory().getAbsolutePath() + FILE_SEPARATOR + path);
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
        File stty = locateFromPaths("stty", x -> x.canExecute()).orElseThrow(() -> new MissingTool("stty could not be found"));

        int exitCode;
        try {
            exitCode = Runtime.getRuntime()
                              .exec(new String[]{stty.getAbsolutePath(), isMacOS() ? "-f" : "-F", deviceNode.getAbsolutePath(), "-echo"})
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

    public static class UnsupportedOperatingSystem extends RuntimeException {
        public UnsupportedOperatingSystem(String msg) {
            super(msg);
        }
    }
}
