package com.labconnect.desktop.ui;

import com.labconnect.core.transfer.Transfer;
import com.labconnect.core.transfer.TransferState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class TransferProgressView extends VBox {
    private final Transfer transfer;
    private final Runnable onCancel;
    private final Runnable onPause;
    private final Runnable onResume;
    
    Transfer getTransfer() {
        return transfer;
    }
    
    private final Label fileNameLabel;
    private final ProgressBar progressBar;
    private final Label progressLabel;
    private final Label speedLabel;
    private final Label etaLabel;
    private final Label statusLabel;
    private final Button pauseResumeButton;
    private final Button cancelButton;
    private final HBox buttonBox;
    
    private long lastBytes = 0;
    private long lastTime = 0;

    public TransferProgressView(Transfer transfer, Runnable onCancel, Runnable onPause, Runnable onResume) {
        this.transfer = transfer;
        this.onCancel = onCancel;
        this.onPause = onPause;
        this.onResume = onResume;
        
        setSpacing(8);
        setPadding(new Insets(12));
        setPrefWidth(400);
        getStyleClass().add("transfer-view");
        
        // File name
        fileNameLabel = new Label(transfer.getMetadata().fileName());
        fileNameLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
        fileNameLabel.setWrapText(true);
        fileNameLabel.setMaxWidth(380);
        
        // Progress bar
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(380);
        progressBar.setPrefHeight(20);
        
        // Progress details
        progressLabel = new Label("0% (0 / 0 bytes)");
        progressLabel.setFont(Font.font("System", 11));
        
        // Speed and ETA
        speedLabel = new Label("Speed: calculating...");
        speedLabel.setFont(Font.font("System", 11));
        
        etaLabel = new Label("ETA: calculating...");
        etaLabel.setFont(Font.font("System", 11));
        
        // Status
        statusLabel = new Label("Status: " + transfer.getState());
        statusLabel.setFont(Font.font("System", 11));
        
        // Buttons
        pauseResumeButton = new Button("Pause");
        pauseResumeButton.setOnAction(e -> handlePauseResume());
        
        cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> onCancel.run());
        
        buttonBox = new HBox(8);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getChildren().addAll(pauseResumeButton, cancelButton);
        
        // Layout
        VBox infoBox = new VBox(4, progressLabel, speedLabel, etaLabel, statusLabel);
        infoBox.setPadding(new Insets(4, 0, 0, 0));
        
        getChildren().addAll(fileNameLabel, progressBar, infoBox, buttonBox);
        
        // Start update timer
        startUpdateTimer();
    }
    
    private void startUpdateTimer() {
        Thread updateThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && 
                   transfer.getState() != TransferState.COMPLETED &&
                   transfer.getState() != TransferState.CANCELLED &&
                   transfer.getState() != TransferState.FAILED) {
                try {
                    Platform.runLater(this::updateUI);
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Final update
            Platform.runLater(this::updateUI);
        });
        updateThread.setDaemon(true);
        updateThread.start();
    }
    
    void updateUI() {
        TransferState state = transfer.getState();
        long bytes = transfer.getBytesTransferred();
        long total = transfer.getFileSize();
        double progress = transfer.getProgress();
        double speed = transfer.getSpeedBytesPerSec();
        long eta = transfer.getEtaMillis();
        
        progressBar.setProgress(progress);
        progressLabel.setText(String.format("%.1f%% (%s / %s)", 
                progress * 100, formatBytes(bytes), formatBytes(total)));
        
        if (speed > 0) {
            speedLabel.setText("Speed: " + formatBytes((long) speed) + "/s");
        } else {
            speedLabel.setText("Speed: --");
        }
        
        if (eta > 0 && eta < 86400000) { // Less than 24 hours
            etaLabel.setText("ETA: " + formatDuration(eta));
        } else if (progress >= 1.0) {
            etaLabel.setText("ETA: Complete");
        } else {
            etaLabel.setText("ETA: --");
        }
        
        statusLabel.setText("Status: " + state);
        
        // Update button states
        if (state == TransferState.IN_PROGRESS) {
            pauseResumeButton.setText("Pause");
            pauseResumeButton.setDisable(false);
            cancelButton.setDisable(false);
        } else if (state == TransferState.PAUSED) {
            pauseResumeButton.setText("Resume");
            pauseResumeButton.setDisable(false);
            cancelButton.setDisable(false);
        } else if (state == TransferState.COMPLETED) {
            pauseResumeButton.setDisable(true);
            cancelButton.setDisable(true);
            pauseResumeButton.setText("Done");
        } else if (state == TransferState.CANCELLED) {
            pauseResumeButton.setDisable(true);
            cancelButton.setDisable(true);
            pauseResumeButton.setText("Cancelled");
        } else if (state == TransferState.FAILED) {
            pauseResumeButton.setDisable(true);
            cancelButton.setDisable(true);
            pauseResumeButton.setText("Failed");
        }
        
        // Calculate actual speed from bytes transferred
        long now = System.currentTimeMillis();
        if (lastTime > 0) {
            long elapsed = now - lastTime;
            long delta = bytes - lastBytes;
            if (elapsed > 0) {
                double actualSpeed = (delta * 1000.0) / elapsed;
                if (actualSpeed > 0 && speed <= 0) {
                    speedLabel.setText("Speed: " + formatBytes((long) actualSpeed) + "/s");
                }
            }
        }
        lastBytes = bytes;
        lastTime = now;
    }
    
    private void handlePauseResume() {
        TransferState state = transfer.getState();
        if (state == TransferState.IN_PROGRESS) {
            onPause.run();
        } else if (state == TransferState.PAUSED) {
            onResume.run();
        }
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), unit);
    }
    
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return seconds + "s";
        }
    }
}