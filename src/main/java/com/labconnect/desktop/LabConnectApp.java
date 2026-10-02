package com.labconnect.desktop;

import com.labconnect.core.config.AppConfig;
import com.labconnect.desktop.services.DesktopService;
import com.labconnect.desktop.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX entry point. Owns the application lifecycle: builds the config, brings
 * up the core managers, and hands a configured Stage to {@link MainWindow}.
 */
public class LabConnectApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(LabConnectApp.class);

    private DesktopService service;
    private MainWindow window;

    @Override
    public void start(Stage stage) {
        log.info("Starting LabConnect UI...");

        AppConfig config = AppConfig.load();
        log.info("Configuration loaded for device '{}'", config.getDevice().getName());

        try {
            // DesktopService binds a TCP socket and starts the UDP discovery loop,
            // so it is constructed off the FX thread to keep startup responsive.
            service = buildService(config);
        } catch (Exception e) {
            log.error("Failed to initialise core services", e);
            showFatalError(stage, e);
            return;
        }

        window = new MainWindow(stage, config, service);
        window.show();

        service.discoverDevices();
        log.info("LabConnect UI is running");
    }

    /**
     * DesktopService blocks on network setup, so build it on a worker thread and
     * wait for the result. The FX thread stays free.
     */
    private DesktopService buildService(AppConfig config) throws Exception {
        final DesktopService[] holder = new DesktopService[1];
        final Exception[] failure = new Exception[1];

        Thread init = new Thread(() -> {
            try {
                holder[0] = new DesktopService(config);
            } catch (Exception e) {
                failure[0] = e;
            }
        }, "labconnect-init");
        init.setDaemon(true);
        init.start();
        init.join();

        if (failure[0] != null) {
            throw failure[0];
        }
        return holder[0];
    }

    @Override
    public void stop() {
        log.info("Shutting down LabConnect");
        if (service != null) {
            service.shutdown();
        }
    }

    private void showFatalError(Stage stage, Exception e) {
        log.error("Startup aborted", e);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("LabConnect - Startup Failed");
        alert.setHeaderText("Could not initialise LabConnect");
        alert.setContentText(String.valueOf(e.getMessage()));
        alert.initOwner(stage);
        alert.showAndWait();
    }
}
