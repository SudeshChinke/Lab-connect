package com.labconnect.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the config save/load round trip.
 *
 * Regression test for a real bug: NetworkConfig had fields/setters named
 * "announceIntervalSec" but getters named "getAnnounceInterval()" returning a
 * Duration. The derived keys were serialised alongside the real ones, and were
 * then rejected on the next load, so every user setting was silently discarded
 * and the file was overwritten with defaults on each run.
 */
class ConfigTest {

    @Test
    @DisplayName("save/load round trip preserves all network settings")
    void roundTripPreservesNetworkSettings(@TempDir Path dir) throws IOException {
        Path cfg = dir.resolve("config.yaml");

        AppConfig original = new AppConfig();
        original.getNetwork().setTcpPort(6000);
        original.getNetwork().setDiscoveryPort(60001);
        original.getNetwork().setMulticastGroup("239.1.2.3");
        original.getNetwork().setAnnounceIntervalSec(11);
        original.getNetwork().setHeartbeatIntervalSec(12);
        original.getNetwork().setConnectionTimeoutSec(13);
        original.save(cfg);

        AppConfig loaded = AppConfig.load(cfg);

        assertThat(loaded.getNetwork().getTcpPort()).isEqualTo(6000);
        assertThat(loaded.getNetwork().getDiscoveryPort()).isEqualTo(60001);
        assertThat(loaded.getNetwork().getMulticastGroup()).isEqualTo("239.1.2.3");
        assertThat(loaded.getNetwork().getAnnounceInterval()).isEqualTo(Duration.ofSeconds(11));
        assertThat(loaded.getNetwork().getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(12));
        assertThat(loaded.getNetwork().getConnectionTimeout()).isEqualTo(Duration.ofSeconds(13));
    }

    @Test
    @DisplayName("config.yaml is real YAML, not JSON")
    void fileIsYaml(@TempDir Path dir) throws IOException {
        Path cfg = dir.resolve("config.yaml");

        new AppConfig().save(cfg);
        String text = Files.readString(cfg, StandardCharsets.UTF_8);

        // Block-style YAML: one key per line, no braces, no quoted JSON keys.
        assertThat(text).contains("---");
        assertThat(text).contains("tcpPort: 5000");
        assertThat(text).doesNotContain("\"tcpPort\"");
        assertThat(text).doesNotContain("{");

        // Must actually parse as YAML.
        Map<String, Object> parsed = new Yaml().load(text);
        assertThat(parsed).containsKey("network");
    }

    @Test
    @DisplayName("saved file does not contain derived Duration properties")
    void savedFileHasNoDerivedProperties(@TempDir Path dir) throws IOException {
        Path cfg = dir.resolve("config.yaml");

        new AppConfig().save(cfg);
        String text = Files.readString(cfg, StandardCharsets.UTF_8);

        // These were the keys that broke deserialisation.
        assertThat(text).doesNotContain("announceInterval:");
        assertThat(text).doesNotContain("heartbeatInterval:");
        assertThat(text).doesNotContain("connectionTimeout:");
        assertThat(text).contains("announceIntervalSec:");
    }

    @Test
    @DisplayName("unknown keys are ignored instead of discarding the whole config")
    void unknownKeysAreIgnored(@TempDir Path dir) throws IOException {
        Path cfg = dir.resolve("config.yaml");
        Files.writeString(cfg, "network:\n  tcpPort: 7000\n  someFutureKey: 123\n",
                StandardCharsets.UTF_8);

        AppConfig loaded = AppConfig.load(cfg);

        assertThat(loaded.getNetwork().getTcpPort()).isEqualTo(7000);
    }

    @Test
    @DisplayName("missing file yields defaults")
    void missingFileYieldsDefaults(@TempDir Path dir) {
        AppConfig loaded = AppConfig.load(dir.resolve("does-not-exist.yaml"));

        assertThat(loaded.getNetwork().getTcpPort()).isEqualTo(5000);
        assertThat(loaded.getNetwork().getDiscoveryPort()).isEqualTo(50001);
        assertThat(loaded.getTransfer().getChunkSize()).isEqualTo(65536);
        assertThat(loaded.getSecurity().isRequirePairing()).isTrue();
    }

    @Test
    @DisplayName("malformed file falls back to defaults instead of throwing")
    void malformedFileYieldsDefaults(@TempDir Path dir) throws IOException {
        Path cfg = dir.resolve("config.yaml");
        Files.writeString(cfg, "network:\n  tcpPort: [unclosed\n", StandardCharsets.UTF_8);

        AppConfig loaded = AppConfig.load(cfg);

        assertThat(loaded.getNetwork().getTcpPort()).isEqualTo(5000);
    }

    @Test
    @DisplayName("device, transfer, security and logging round trip")
    void otherSectionsRoundTrip(@TempDir Path dir) {
        Path cfg = dir.resolve("config.yaml");

        AppConfig original = new AppConfig();
        original.getDevice().setName("Lab-PC-01");
        original.getDevice().setType(AppConfig.DeviceType.MOBILE);
        original.getTransfer().setChunkSize(32768);
        original.getTransfer().setMaxConcurrentTransfers(8);
        original.getTransfer().setResumeEnabled(false);
        original.getSecurity().setRequirePairing(false);
        original.getSecurity().setTlsVersion("TLSv1.2");
        original.getLogging().setLevel("DEBUG");
        original.save(cfg);

        AppConfig loaded = AppConfig.load(cfg);

        assertThat(loaded.getDevice().getName()).isEqualTo("Lab-PC-01");
        assertThat(loaded.getDevice().getType()).isEqualTo(AppConfig.DeviceType.MOBILE);
        assertThat(loaded.getTransfer().getChunkSize()).isEqualTo(32768);
        assertThat(loaded.getTransfer().getMaxConcurrentTransfers()).isEqualTo(8);
        assertThat(loaded.getTransfer().isResumeEnabled()).isFalse();
        assertThat(loaded.getSecurity().isRequirePairing()).isFalse();
        assertThat(loaded.getSecurity().getTlsVersion()).isEqualTo("TLSv1.2");
        assertThat(loaded.getLogging().getLevel()).isEqualTo("DEBUG");
    }
}
