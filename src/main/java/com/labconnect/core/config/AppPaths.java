package com.labconnect.core.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Central paths for editable source runs and installed app builds. */
public final class AppPaths {
    private static final String APP_NAME = "LabConnect";

    private AppPaths() {}

    public static boolean isPackaged() {
        return Boolean.getBoolean("labconnect.packaged");
    }

    public static Path dataDirectory() {
        if (!isPackaged()) return Paths.get(".").toAbsolutePath().normalize();

        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            return Paths.get(localAppData == null || localAppData.isBlank()
                    ? home + "/AppData/Local" : localAppData).resolve(APP_NAME);
        }
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", APP_NAME);
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path base = xdgDataHome == null || xdgDataHome.isBlank()
                ? Paths.get(home, ".local", "share") : Paths.get(xdgDataHome);
        return base.resolve(APP_NAME);
    }

    public static Path configPath() {
        return isPackaged() ? dataDirectory().resolve("config.yaml") : Paths.get("config.yaml");
    }

    public static Path identityPath() {
        return isPackaged() ? dataDirectory().resolve("keystore.dat") : Paths.get("keystore.dat");
    }

    public static Path trustStorePath() {
        return isPackaged() ? dataDirectory().resolve("truststore.properties") : Paths.get("truststore.properties");
    }

    public static Path downloadsDirectory() {
        return isPackaged() ? dataDirectory().resolve("downloads") : Paths.get("downloads");
    }
}
