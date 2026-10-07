package com.labconnect.desktop;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.config.AppPaths;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Console entry point. Kept as the Main-Class in the jar manifest; all it does
 * is print the banner and hand control to the JavaFX application.
 */
public class Launcher {
    static {
        System.setProperty("labconnect.log.dir", AppPaths.dataDirectory().resolve("logs").toString());
    }

    private static final Logger log = LoggerFactory.getLogger(Launcher.class);

    public static void main(String[] args) {
        // NOTE: do not set java.util.logging.manager to Log4j here - this project
        // logs through SLF4J/Logback and Log4j is not a dependency, so pointing
        // JUL at it just throws ClassNotFoundException on every startup.

        log.info("╔═══════════════════════════════════════════════╗");
        log.info("║           LabConnect v1.0.0-SNAPSHOT          ║");
        log.info("║   Decentralized LAN Communication & File Transfer   ║");
        log.info("╚═══════════════════════════════════════════════╝");

        AppConfig config = AppConfig.load();
        log.info("Configuration loaded:");
        log.info("  Device: {} ({})", config.getDevice().getName(), config.getDevice().getType());
        log.info("  TCP Port: {}", config.getNetwork().getTcpPort());
        log.info("  Discovery Port: {}", config.getNetwork().getDiscoveryPort());
        log.info("  Multicast Group: {}", config.getNetwork().getMulticastGroup());
        log.info("  Chunk Size: {} bytes", config.getTransfer().getChunkSize());
        log.info("  Max Concurrent Transfers: {}", config.getTransfer().getMaxConcurrentTransfers());
        log.info("  Pairing Required: {}", config.getSecurity().isRequirePairing());

        config.save();
        log.info("Configuration saved to {}", AppPaths.configPath());

        log.info("Status: Starting JavaFX application...");
        Application.launch(LabConnectApp.class, args);
    }
}
