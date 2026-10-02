package com.labconnect.desktop.ui;

import com.labconnect.core.transfer.Transfer;
import com.labconnect.core.transfer.TransferManager;
import com.labconnect.core.transfer.TransferState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class TransferView extends VBox {
    private final TransferManager transferManager;
    private final String localDeviceId;
    private final ListView<TransferItem> transferListView;
    private final Button sendFileButton;
    private final Label statusLabel;
    private final Runnable onRefreshDevices;

    public TransferView(TransferManager transferManager, String localDeviceId, Runnable onRefreshDevices) {
        this.transferManager = transferManager;
        this.localDeviceId = localDeviceId;
        this.onRefreshDevices = onRefreshDevices;
        
        setSpacing(10);
        setPadding(new Insets(15));
        setFillWidth(true);
        VBox.setVgrow(this, Priority.ALWAYS);
        
        // Header
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("File Transfers");
        title.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 16));
        
        sendFileButton = new Button("Send File");
        sendFileButton.setOnAction(e -> showSendFileDialog());
        
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> refreshTransfers());
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(title, spacer, sendFileButton, refreshButton);
        
        // Transfer list
        transferListView = new ListView<>();
        transferListView.setCellFactory(lv -> new TransferCell());
        transferListView.setPlaceholder(new Label("No transfers"));
        VBox.setVgrow(transferListView, Priority.ALWAYS);
        
        // Status
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        
        getChildren().addAll(header, transferListView, statusLabel);
        
        // Start refresh timer
        startRefreshTimer();
    }
    
    private void showSendFileDialog() {
        Stage stage = (Stage) getScene().getWindow();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");
        
        // Add extension filters
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Files", "*.*"),
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
            new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt", "*.md"),
            new FileChooser.ExtensionFilter("Archives", "*.zip", "*.rar", "*.7z", "*.tar", "*.gz"),
            new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.mkv", "*.avi", "*.mov")
        );
        
        List<File> files = fileChooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            for (File file : files) {
                sendFile(file.toPath());
            }
        }
    }
    
    private void sendFile(java.nio.file.Path filePath) {
        // Get available devices
        List<String> deviceIds = getAvailableDeviceIds();
        if (deviceIds.isEmpty()) {
            showAlert("No Devices", "No other devices are currently connected.");
            return;
        }
        
        // Show device selection dialog
        ChoiceDialog<String> dialog = new ChoiceDialog<>(deviceIds.get(0), deviceIds);
        dialog.setTitle("Select Target Device");
        dialog.setHeaderText("Send " + filePath.getFileName() + " to:");
        dialog.setContentText("Device:");
        
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(deviceId -> {
            try {
                transferManager.sendFile(deviceId, filePath);
                statusLabel.setText("Sending " + filePath.getFileName() + " to " + deviceId);
            } catch (Exception e) {
                showAlert("Send Failed", "Failed to send file: " + e.getMessage());
            }
        });
    }
    
    private List<String> getAvailableDeviceIds() {
        // This would come from the connection manager or discovery
        // For now, return empty - in real implementation, query connection manager
        return List.of();
    }
    
    private void refreshTransfers() {
        List<Transfer> transfers = transferManager.getActiveTransfers().stream().collect(Collectors.toList());
        Platform.runLater(() -> {
            transferListView.getItems().clear();
            transferListView.getItems().addAll(transfers.stream()
                    .map(TransferItem::new)
                    .collect(Collectors.toList()));
        });
    }
    
    private void startRefreshTimer() {
        Thread timer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    refreshTransfers();
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        timer.setDaemon(true);
        timer.start();
    }
    
    private void showAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    // Inner classes
    private static class TransferItem {
        final Transfer transfer;
        
        TransferItem(Transfer transfer) {
            this.transfer = transfer;
        }
    }
    
    private class TransferCell extends ListCell<TransferItem> {
        private final TransferProgressView progressView;
        
        TransferCell() {
            this.progressView = null; // Created in updateItem
        }
        
        @Override
        protected void updateItem(TransferItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
            } else {
                if (progressView == null || progressView.getTransfer() != item.transfer) {
                    // Create new view for this transfer
                    TransferProgressView view = new TransferProgressView(
                        item.transfer,
                        () -> transferManager.cancelTransfer(item.transfer.getMetadata().transferId()),
                        () -> transferManager.pauseTransfer(item.transfer.getMetadata().transferId()),
                        () -> transferManager.resumeTransfer(item.transfer.getMetadata().transferId())
                    );
                    setGraphic(view);
                } else {
                    setGraphic(progressView);
                }
            }
        }
    }
}