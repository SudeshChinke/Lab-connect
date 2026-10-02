package com.labconnect.core.discovery;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Set;

public record Announcement(
        @JsonProperty("type") String type,
        @JsonProperty("deviceId") String deviceId,
        @JsonProperty("deviceName") String deviceName,
        @JsonProperty("deviceType") String deviceType,
        @JsonProperty("protocolVersion") String protocolVersion,
        @JsonProperty("tcpPort") int tcpPort,
        @JsonProperty("capabilities") Set<String> capabilities,
        @JsonProperty("publicKey") String publicKey,
        @JsonProperty("timestamp") Instant timestamp
) {}