package com.labconnect.core.discovery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.labconnect.core.models.DeviceInfo;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Set;

public record DiscoveredDevice(
        @JsonProperty("deviceId") String deviceId,
        @JsonProperty("deviceName") String deviceName,
        @JsonProperty("deviceType") DeviceInfo.DeviceType deviceType,
        @JsonProperty("protocolVersion") String protocolVersion,
        @JsonProperty("ipAddress") String ipAddress,
        @JsonProperty("tcpPort") int tcpPort,
        @JsonProperty("capabilities") Set<String> capabilities,
        @JsonProperty("publicKey") String publicKey,
        @JsonProperty("timestamp") Instant timestamp
) {
    public DeviceInfo toDeviceInfo() {
        return new DeviceInfo(
                deviceId, deviceName, deviceType, protocolVersion,
                ipAddress, tcpPort, capabilities, publicKey,
                timestamp, DeviceInfo.DeviceStatus.ONLINE
        );
    }

    public static DiscoveredDevice fromDeviceInfo(DeviceInfo info, Instant timestamp) {
        return new DiscoveredDevice(
                info.deviceId(), info.deviceName(), info.deviceType(), info.protocolVersion(),
                info.ipAddress(), info.tcpPort(), info.capabilities(), info.publicKey(),
                timestamp
        );
    }
}