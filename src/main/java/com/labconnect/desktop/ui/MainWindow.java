package com.labconnect.desktop.ui;

import com.labconnect.core.config.AppConfig;
import com.labconnect.core.diagnostics.DiagnosticsManager;
import com.labconnect.core.discovery.DiscoveryManager;
import com.labconnect.core.error.LabConnectException;
import com.labconnect.core.messaging.ChatManager;
import com.labconnect.core.messaging.TextMessage;
import com.labconnect.core.models.DeviceInfo;
import com.labconnect.core.networking.ConnectionManager;
import com.labconnect.core.transfer.Transfer;
import com.labconnect.core.transfer.TransferManager;
import com.labconnect.desktop.services.DesktopService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MainWindow {
    private final Stage stage;
    private final AppConfig config;
    private final DesktopService service;
    
    private BorderPane root;
    private ListView<DeviceItem> deviceListView;
    private ListView<ChatItem> chatListView;
    private TabPane tabPane;
    private Tab devicesTab;
    private Tab chatsTab;
    private Tab transfersTab;
    private Tab diagnosticsTab;
    
    private Label statusLabel;
    private Label connectionCountLabel;
    
    public MainWindow(Stage stage, AppConfig config, DesktopService service) {
        this.stage = stage;
        this.config = config;
        this.service = service;
        
        initializeUI();
        setupEventHandlers();
        refreshUI();
    }

    /**
     * Displays the window. Must be called on the JavaFX Application Thread.
     */
    public void show() {
        stage.show();
    }
    
    private void initializeUI() {
        root = new BorderPane();
        root.setPadding(new Insets(10));
        
        // Top toolbar
        ToolBar toolBar = createToolBar();
        root.setTop(toolBar);
        
        // Center - tab pane
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        devicesTab = new Tab("Devices", createDevicesView());
        chatsTab = new Tab("Chats", createChatsView());
        transfersTab = new Tab("Transfers", createTransfersView());
        diagnosticsTab = new Tab("Diagnostics", createDiagnosticsView());
        
        tabPane.getTabs().addAll(devicesTab, chatsTab, transfersTab, diagnosticsTab);
        root.setCenter(tabPane);
        
        // Status bar
        StatusBar statusBar = new StatusBar();
        root.setBottom(statusBar);
        
        Scene scene = new Scene(root, 1000, 700);
        stage.setScene(scene);
        stage.setTitle("LabConnect - " + config.getDevice().getName());
        stage.setMinWidth(800);
        stage.setMinHeight(600);
    }
    
    private ToolBar createToolBar() {
        ToolBar toolBar = new ToolBar();
        toolBar.setPadding(new Insets(5, 10, 5, 10));
        
        Button refreshBtn = new Button("Refresh Devices");
        refreshBtn.setOnAction(e -> refreshDevices());
        
        Button sendFileBtn = new Button("Send File");
        sendFileBtn.setOnAction(e -> showFileSender());
        
        Button newChatBtn = new Button("New Chat");
        newChatBtn.setOnAction(e -> showNewChatDialog());
        
        Button settingsBtn = new Button("Settings");
        settingsBtn.setOnAction(e -> showSettingsDialog());
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        statusLabel = new Label("Status: Starting...");
        statusLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
        
        connectionCountLabel = new Label("Connections: 0");
        connectionCountLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
        
        toolBar.getItems().addAll(
            refreshBtn, sendFileBtn, newChatBtn, settingsBtn,
            spacer, statusLabel, connectionCountLabel
        );
        
        return toolBar;
    }
    
    private Node createDevicesView() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setFillWidth(true);
        
        Label title = new Label("Discovered Devices");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        deviceListView = new ListView<>();
        deviceListView.setCellFactory(lv -> new DeviceListCell());
        deviceListView.setPlaceholder(new Label("No devices discovered"));
        VBox.setVgrow(deviceListView, Priority.ALWAYS);
        
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        Button connectBtn = new Button("Connect");
        connectBtn.setOnAction(e -> connectToSelectedDevice());
        Button pairBtn = new Button("Pair");
        pairBtn.setOnAction(e -> pairWithSelectedDevice());
        buttonBox.getChildren().addAll(connectBtn, pairBtn);
        
        vbox.getChildren().addAll(title, deviceListView, buttonBox);
        return vbox;
    }
    
    private Node createChatsView() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setFillWidth(true);
        
        Label title = new Label("Active Chats");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);
        Button newChatBtn = new Button("New Chat");
        newChatBtn.setOnAction(e -> showNewChatDialog());
        Button groupBtn = new Button("Create Group");
        groupBtn.setOnAction(e -> showCreateGroupDialog());
        topBar.getChildren().addAll(newChatBtn, groupBtn);
        
        chatListView = new ListView<>();
        chatListView.setCellFactory(lv -> new ChatListCell());
        chatListView.setPlaceholder(new Label("No active chats"));
        VBox.setVgrow(chatListView, Priority.ALWAYS);
        
        vbox.getChildren().addAll(title, topBar, chatListView);
        return vbox;
    }
    
    private Node createTransfersView() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setFillWidth(true);
        
        Label title = new Label("File Transfers");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        TableView<TransferRow> transferTable = new TableView<>();
        transferTable.setPlaceholder(new Label("No active transfers"));
        VBox.setVgrow(transferTable, Priority.ALWAYS);
        
        TableColumn<TransferRow, String> fileCol = new TableColumn<>("File");
        fileCol.setCellValueFactory(c -> c.getValue().fileNameProperty());
        fileCol.setPrefWidth(250);
        
        TableColumn<TransferRow, String> progressCol = new TableColumn<>("Progress");
        progressCol.setCellValueFactory(c -> c.getValue().progressProperty());
        progressCol.setPrefWidth(200);
        progressCol.setCellFactory(col -> new ProgressTableCell());
        
        TableColumn<TransferRow, String> speedCol = new TableColumn<>("Speed");
        speedCol.setCellValueFactory(c -> c.getValue().speedProperty());
        speedCol.setPrefWidth(100);
        
        TableColumn<TransferRow, String> etaCol = new TableColumn<>("ETA");
        etaCol.setCellValueFactory(c -> c.getValue().etaProperty());
        etaCol.setPrefWidth(80);
        
        TableColumn<TransferRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setPrefWidth(120);
        
        transferTable.getColumns().addAll(fileCol, progressCol, speedCol, etaCol, statusCol);
        
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        Button sendFileBtn = new Button("Send File");
        sendFileBtn.setOnAction(e -> showFileSender());
        Button cancelBtn = new Button("Cancel Selected");
        cancelBtn.setOnAction(e -> cancelSelectedTransfers(transferTable));
        buttonBox.getChildren().addAll(sendFileBtn, cancelBtn);
        
        VBox wrapper = new VBox(10);
        wrapper.getChildren().addAll(title, transferTable, buttonBox);
        
        // Wire up transfer manager callbacks to populate the table
        refreshTransferTable(transferTable);
        service.getTransferManager().getActiveTransfers().forEach(t -> 
            Platform.runLater(() -> addTransferRow(transferTable, t))
        );
        service.setOnTransferProgress(t -> Platform.runLater(() -> updateTransferRow(transferTable, t)));
        service.setOnTransferCompleted(t -> Platform.runLater(() -> updateTransferRow(transferTable, t)));
        service.setOnTransferFailed(t -> Platform.runLater(() -> updateTransferRow(transferTable, t)));
        
        return wrapper;
    }
    
    private Node createDiagnosticsView() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setFillWidth(true);
        
        Label title = new Label("Network Diagnostics");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        TextArea diagnosticsArea = new TextArea();
        diagnosticsArea.setEditable(false);
        diagnosticsArea.setWrapText(true);
        diagnosticsArea.setFont(Font.font("Monospace", 12));
        diagnosticsArea.setPrefHeight(400);
        VBox.setVgrow(diagnosticsArea, Priority.ALWAYS);
        
        Button refreshBtn = new Button("Refresh Diagnostics");
        refreshBtn.setOnAction(e -> refreshDiagnostics(diagnosticsArea));
        
        Button reportBtn = new Button("Generate Report");
        reportBtn.setOnAction(e -> generateDiagnosticReport());
        
        HBox btnBox = new HBox(10);
        btnBox.setAlignment(Pos.CENTER_RIGHT);
        btnBox.getChildren().addAll(refreshBtn, reportBtn);
        
        vbox.getChildren().addAll(title, diagnosticsArea, btnBox);
        return vbox;
    }
    
    private void setupEventHandlers() {
        // Device list selection
        deviceListView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            updateDeviceButtons(sel != null);
        });
        
        // Push inbound messages and connection changes into the UI. These
        // callbacks fire on networking threads, so hop to the FX thread.
        service.setOnMessageReceived(msg ->
            Platform.runLater(() -> upsertChatItem(displayNameOf(msg.senderId()), msg.content())));
        service.setOnMessageDelivered(msg -> Platform.runLater(() -> chatListView.refresh()));
        service.setOnConnectionChange(() -> Platform.runLater(this::updateConnectionCount));
        // B2: the device list updates itself whenever discovery finds,
        // updates, or loses a device - no manual refresh needed.
        service.setOnDeviceListChanged(device -> Platform.runLater(this::updateDeviceList));
        service.getPairingManager().setOnIncomingPairingRequest(request -> Platform.runLater(() -> {
            String fingerprint = fingerprint(request.requesterPublicKey());
            Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "Trust " + request.requesterDeviceName() + " (" + request.requesterDeviceId() + ")?\n\n"
                    + "Verify this fingerprint with the person before accepting:\n" + fingerprint,
                ButtonType.YES, ButtonType.NO);
            prompt.setTitle("Pairing request");
            prompt.setHeaderText("Verify peer identity");
            boolean accepted = prompt.showAndWait().filter(ButtonType.YES::equals).isPresent();
            if (accepted) service.getPairingManager().acceptPairing(request.sessionId());
            else service.getPairingManager().rejectPairing(request.sessionId());
        }));
    }
    
    /** Resolves a deviceId to a friendly name via discovery, falling back to the id. */
    private String displayNameOf(String deviceId) {
        return service.getDiscoveryManager().getDevice(deviceId)
            .map(DeviceInfo::deviceName)
            .orElse(deviceId);
    }
    
    /** Adds or refreshes the preview line of a chat in the Chats tab. */
    private void upsertChatItem(String peerName, String lastMessage) {
        for (ChatItem item : chatListView.getItems()) {
            if (item.name.equals(peerName)) {
                item.lastMessage = lastMessage;
                chatListView.refresh();
                return;
            }
        }
        chatListView.getItems().add(0, new ChatItem(peerName, lastMessage));
    }
    
    private void updateConnectionCount() {
        long count = service.getConnectionManager().getAllConnections().stream()
            .filter(com.labconnect.core.networking.Connection::isConnected)
            .count();
        connectionCountLabel.setText("Connections: " + count);
    }
    
    private void updateDeviceButtons(boolean hasSelection) {
        // TODO: Enable/disable connect/pair buttons
    }
    
    private void refreshUI() {
        refreshDevices();
        refreshDiagnostics(null);
    }
    
    private void refreshDevices() {
        statusLabel.setText("Status: Refreshing devices...");
        service.discoverDevices();
        Platform.runLater(this::updateDeviceList);
    }
    
    /**
     * Rebuilds the device list from the registry, preserving the current
     * selection by deviceId. Discovery announcements arrive every few
     * seconds, so rebuilding without this would yank a selection out from
     * under the user mid-click.
     */
    private void updateDeviceList() {
        DeviceItem selected = deviceListView.getSelectionModel().getSelectedItem();
        String selectedId = selected != null ? selected.getDeviceId() : null;
        
        deviceListView.getItems().setAll(
            service.getDiscoveryManager().getAllDevices().stream()
                .map(DeviceItem::new)
                .toList()
        );
        if (selectedId != null) {
            for (int i = 0; i < deviceListView.getItems().size(); i++) {
                if (deviceListView.getItems().get(i).getDeviceId().equals(selectedId)) {
                    deviceListView.getSelectionModel().select(i);
                    break;
                }
            }
        }
        statusLabel.setText("Status: Ready - " + deviceListView.getItems().size() + " devices");
    }
    
    private void refreshDiagnostics(TextArea area) {
        String report = service.getDiagnosticsManager().generateDiagnosticReport();
        if (area != null) {
            area.setText(report);
        }
    }
    
    private void generateDiagnosticReport() {
        String report = service.getDiagnosticsManager().generateDiagnosticReport();
        
        // Save to file
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("diagnostics-report.txt");
            java.nio.file.Files.writeString(path, report);
            showAlert(Alert.AlertType.INFORMATION, "Report Generated", 
                "Diagnostic report saved to " + path.toAbsolutePath());
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error", "Failed to save report: " + e.getMessage());
        }
    }
    
    private void showNewChatDialog() {
        List<DeviceItem> devices = service.getDiscoveryManager().getAllDevices().stream()
            .map(DeviceItem::new).toList();
        if (devices.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "No Devices",
                "No devices discovered yet. Click Refresh Devices and connect first.");
            return;
        }
        
        ChoiceDialog<DeviceItem> deviceDialog = new ChoiceDialog<>();
        deviceDialog.setTitle("New Chat");
        deviceDialog.setHeaderText("Select a device to start a chat");
        deviceDialog.setContentText("Device:");
        deviceDialog.getItems().addAll(devices);
        
        Optional<DeviceItem> chosen = deviceDialog.showAndWait();
        if (chosen.isEmpty()) {
            return;
        }
        DeviceItem device = chosen.get();
        
        TextInputDialog messageDialog = new TextInputDialog();
        messageDialog.setTitle("Message " + device.getDeviceName());
        messageDialog.setHeaderText("Send a message to " + device.getDeviceName());
        messageDialog.setContentText("Message:");
        
        messageDialog.showAndWait().ifPresent(text -> {
            if (text.isBlank()) {
                return;
            }
            boolean sent = service.getChatManager().sendMessage(device.getDeviceId(), text.trim());
            if (sent) {
                upsertChatItem(device.getDeviceName(), text.trim());
            } else {
                showAlert(Alert.AlertType.WARNING, "Not Connected",
                    "Not connected to " + device.getDeviceName()
                        + ". Select it in the Devices tab and click Connect first.");
            }
        });
    }
    
    private void showCreateGroupDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create Group");
        dialog.setHeaderText("Enter group name");
        dialog.setContentText("Group name:");
        
        dialog.showAndWait().ifPresent(name -> {
            service.getChatManager().createGroup(name);
        });
    }
    
    private void showFileSender() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File(s) to Send");
        
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Files", "*.*"),
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
            new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt", "*.md"),
            new FileChooser.ExtensionFilter("Archives", "*.zip", "*.rar", "*.7z", "*.tar", "*.gz"),
            new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.mkv", "*.avi", "*.mov")
        );
        
        List<File> files = fileChooser.showOpenMultipleDialog(this.stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        
        // Get connected devices
        List<DeviceItem> devices = service.getDiscoveryManager().getAllDevices().stream()
            .filter(d -> d.status() == DeviceInfo.DeviceStatus.ONLINE)
            .map(DeviceItem::new)
            .toList();
        
        if (devices.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Devices", "No online devices available to send files to.");
            return;
        }
        
        for (File file : files) {
            showSendFileDialog(file.toPath(), devices);
        }
    }
    
    private void showSendFileDialog(java.nio.file.Path filePath, List<DeviceItem> devices) {
        ChoiceDialog<DeviceItem> dialog = new ChoiceDialog<>();
        dialog.setTitle("Send File");
        dialog.setHeaderText("Send " + filePath.getFileName() + " to:");
        dialog.setContentText("Device:");
        dialog.getItems().addAll(devices);
        
        dialog.showAndWait().ifPresent(device -> {
            try {
                service.getTransferManager().sendFile(device.getDeviceId(), filePath);
                statusLabel.setText("Sending " + filePath.getFileName() + " to " + device.getDeviceName());
            } catch (IOException e) {
                showAlert(Alert.AlertType.ERROR, "Send Failed", "Failed to send file: " + e.getMessage());
            }
        });
    }
    
    private void refreshTransferTable(TableView<TransferRow> table) {
        table.getItems().clear();
        service.getTransferManager().getActiveTransfers().forEach(t -> 
            addTransferRow(table, t)
        );
    }
    
    private void addTransferRow(TableView<TransferRow> table, Transfer t) {
        String fileName = t.getMetadata().fileName();
        String progress = String.format("%.1f%%", t.getProgress() * 100);
        String speed = formatSpeed(t.getSpeedBytesPerSec());
        String eta = t.getEtaMillis() > 0 ? formatEta(t.getEtaMillis()) : "--";
        String status = t.getState().name();
        
        TransferRow row = new TransferRow(fileName, progress, speed, eta, status);
        row.transfer = t;
        table.getItems().add(row);
    }
    
    private void updateTransferRow(TableView<TransferRow> table, Transfer t) {
        for (TransferRow row : table.getItems()) {
            if (row.transfer != null && row.transfer.getMetadata().transferId().equals(t.getMetadata().transferId())) {
                row.progress.set(String.format("%.1f%%", t.getProgress() * 100));
                row.speed.set(formatSpeed(t.getSpeedBytesPerSec()));
                row.eta.set(t.getEtaMillis() > 0 ? formatEta(t.getEtaMillis()) : "--");
                row.status.set(t.getState().name());
                table.refresh();
                break;
            }
        }
    }
    
    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) return String.format("%.1f B/s", bytesPerSec);
        if (bytesPerSec < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSec / 1024);
        return String.format("%.1f MB/s", bytesPerSec / (1024 * 1024));
    }
    
    private String formatEta(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h";
    }
    
    private void cancelSelectedTransfers(TableView<?> table) {
        if (table instanceof TableView<?>) {
            @SuppressWarnings("unchecked")
            TableView<TransferRow> transferTable = (TableView<TransferRow>) table;
            TransferRow selected = transferTable.getSelectionModel().getSelectedItem();
            if (selected != null && selected.transfer != null) {
                service.getTransferManager().cancelTransfer(selected.transfer.getMetadata().transferId());
            }
        }
    }
    
    private void showSettingsDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("LabConnect Settings");
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        TextField nameField = new TextField(config.getDevice().getName());
        TextField portField = new TextField(String.valueOf(config.getNetwork().getTcpPort()));
        
        grid.add(new Label("Device Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("TCP Port:"), 0, 1);
        grid.add(portField, 1, 1);
        
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                config.getDevice().setName(nameField.getText());
                config.getNetwork().setTcpPort(Integer.parseInt(portField.getText()));
                config.save();
            }
            return null;
        });
        
        dialog.showAndWait();
    }
    
    private void connectToSelectedDevice() {
        DeviceItem item = deviceListView.getSelectionModel().getSelectedItem();
        if (item == null) {
            showAlert(Alert.AlertType.INFORMATION, "No Device Selected",
                "Select a device to connect to.");
            return;
        }
        if (service.connectToDevice(item.getDeviceId())) {
            statusLabel.setText("Status: Connecting to " + item.getDeviceName() + "...");
        } else {
            showAlert(Alert.AlertType.ERROR, "Connection Failed",
                "Could not reach " + item.getDeviceName() + ". Check that it is online.");
        }
    }
    
    private void pairWithSelectedDevice() {
        DeviceItem item = deviceListView.getSelectionModel().getSelectedItem();
        if (item == null) return;
        if (service.getConnectionManager().getConnection(item.getDeviceId())
                .filter(com.labconnect.core.networking.Connection::isConnected).isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Connect first", "Connect to this device, then start pairing.");
            return;
        }
        Alert verify = new Alert(Alert.AlertType.CONFIRMATION,
            "Compare this fingerprint with the peer through a separate trusted channel before continuing:\n\n"
                + fingerprint(item.info.publicKey()), ButtonType.OK, ButtonType.CANCEL);
        verify.setTitle("Verify peer fingerprint");
        verify.setHeaderText("Pair with " + item.info.deviceName() + "?");
        if (verify.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
        service.getPairingManager().initiatePairing(item.getDeviceId(), item.info.deviceName(),
            item.info.publicKey(), result -> Platform.runLater(() -> showAlert(
                result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                result.success() ? "Paired" : "Pairing failed", result.message())));
    }

    private static String fingerprint(String base64PublicKey) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.util.Base64.getDecoder().decode(base64PublicKey));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02X", b));
            return result.toString().replaceAll("(.{4})", "$1 ").trim();
        } catch (Exception e) {
            return "Invalid public key";
        }
    }
    
    private void showAlert(Alert.AlertType type, String title, String msg) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }
    
    // Inner classes for UI components
    private static class DeviceItem {
        private final DeviceInfo info;
        
        DeviceItem(DeviceInfo info) { this.info = info; }
        
        String getDeviceId() { return info.deviceId(); }
        String getDeviceName() { return info.deviceName(); }
        String getDisplayName() { return info.deviceName() + " (" + info.ipAddress() + ":" + info.tcpPort() + ")"; }
        
        @Override
        public String toString() { return getDisplayName(); }
    }
    
    private static class ChatItem {
        final String name;
        String lastMessage;
        
        ChatItem(String name, String lastMessage) {
            this.name = name;
            this.lastMessage = lastMessage;
        }
        
        @Override
        public String toString() { return name + ": " + lastMessage; }
    }
    
    private static class TransferRow {
        private final StringProperty fileName = new SimpleStringProperty();
        private final StringProperty progress = new SimpleStringProperty();
        private final StringProperty speed = new SimpleStringProperty();
        private final StringProperty eta = new SimpleStringProperty();
        private final StringProperty status = new SimpleStringProperty();
        Transfer transfer; // Reference to actual transfer for cancellation
        
        TransferRow(String fileName, String progress, String speed, String eta, String status) {
            this.fileName.set(fileName);
            this.progress.set(progress);
            this.speed.set(speed);
            this.eta.set(eta);
            this.status.set(status);
        }
        
        String getFileName() { return fileName.get(); }
        ObservableValue<String> fileNameProperty() { return fileName; }
        String getProgress() { return progress.get(); }
        ObservableValue<String> progressProperty() { return progress; }
        String getSpeed() { return speed.get(); }
        ObservableValue<String> speedProperty() { return speed; }
        String getEta() { return eta.get(); }
        ObservableValue<String> etaProperty() { return eta; }
        String getStatus() { return status.get(); }
        ObservableValue<String> statusProperty() { return status; }
    }
    
    private static class DeviceListCell extends ListCell<DeviceItem> {
        @Override
        protected void updateItem(DeviceItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                HBox hbox = new HBox(10);
                Label nameLabel = new Label(item.info.deviceName());
                nameLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
                Label addrLabel = new Label(item.info.ipAddress() + ":" + item.info.tcpPort());
                addrLabel.setStyle("-fx-text-fill: gray;");
                Label statusLabel = new Label(item.info.status().name());
                statusLabel.setStyle("-fx-text-fill: " + 
                    (item.info.status() == DeviceInfo.DeviceStatus.ONLINE ? "green" : "red") + ";");
                hbox.getChildren().addAll(nameLabel, addrLabel, statusLabel);
                setGraphic(hbox);
            }
        }
    }
    
    private static class ChatListCell extends ListCell<ChatItem> {
        @Override
        protected void updateItem(ChatItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) setText(null);
            else setText(item.toString());
        }
    }
    
    private static class ProgressTableCell extends TableCell<TransferRow, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
            } else {
                ProgressBar bar = new ProgressBar();
                try {
                    double progress = Double.parseDouble(item.replace("%", "")) / 100.0;
                    bar.setProgress(progress);
                } catch (NumberFormatException e) {
                    bar.setProgress(0);
                }
                setGraphic(bar);
            }
        }
    }
    
    private static class StatusBar extends HBox {
        StatusBar() {
            setSpacing(10);
            setPadding(new Insets(5, 10, 5, 10));
            setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-border-width: 1 0 0 0;");
            
            Label deviceInfo = new Label();
            deviceInfo.setFont(Font.font("System", 11));
            getChildren().add(deviceInfo);
            
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            getChildren().add(spacer);
            
            Label version = new Label("LabConnect v1.0.0");
            version.setFont(Font.font("System", 10));
            getChildren().add(version);
        }
    }
}
