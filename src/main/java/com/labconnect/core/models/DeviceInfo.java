package com.labconnect.core.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DeviceInfo(
        @JsonProperty("deviceId") String deviceId,
        @JsonProperty("deviceName") String deviceName,
        @JsonProperty("deviceType") DeviceType deviceType,
        @JsonProperty("protocolVersion") String protocolVersion,
        @JsonProperty("ipAddress") String ipAddress,
        @JsonProperty("tcpPort") int tcpPort,
        @JsonProperty("capabilities") Set<String> capabilities,
        @JsonProperty("publicKey") String publicKey,
        @JsonProperty("lastSeen") Instant lastSeen,
        @JsonProperty("status") DeviceStatus status
) {
    public enum DeviceType {
        DESKTOP, MOBILE
    }

    public enum DeviceStatus {
        ONLINE, OFFLINE, CONNECTING, CONNECTED, PAIRING_REQUIRED
    }

    @JsonCreator
    public DeviceInfo {
        // Validation
    }

    public static DeviceInfo createLocal(String deviceId, String deviceName, DeviceType type,
                                          String ipAddress, int tcpPort, String publicKey) {
        return new DeviceInfo(
                deviceId, deviceName, type, "1.0",
                ipAddress, tcpPort,
                Set.of("FILE_TRANSFER", "GROUPS", "ENCRYPTION"),
                publicKey, Instant.now(), DeviceStatus.ONLINE
        );
    }

    public DeviceInfo withStatus(DeviceStatus newStatus) {
        return new DeviceInfo(deviceId, deviceName, deviceType, protocolVersion,
                ipAddress, tcpPort, capabilities, publicKey, Instant.now(), newStatus);
    }

    public DeviceInfo withLastSeen(Instant lastSeen) {
        return new DeviceInfo(deviceId, deviceName, deviceType, protocolVersion,
                ipAddress, tcpPort, capabilities, publicKey, lastSeen, status);
    }

    public String getConnectionString() {
        return ipAddress + ":" + tcpPort;
    }
}