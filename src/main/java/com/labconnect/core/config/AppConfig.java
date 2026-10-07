package com.labconnect.core.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application configuration, persisted as real YAML in {@code config.yaml}.
 *
 * <p>The file is built from an explicit map rather than reflected off the
 * bean, so only the plain settings are ever written. The convenience
 * {@code Duration} accessors ({@link NetworkConfig#getAnnounceInterval()} and
 * friends) are derived values and are deliberately never serialised - writing
 * them previously produced keys that could not be read back, which silently
 * reset every setting to its default.
 */
public class AppConfig {

    private static final Path CONFIG_PATH = AppPaths.configPath();

    private static final DumperOptions DUMPER_OPTIONS = createDumperOptions();

    private DeviceConfig device = new DeviceConfig();

    private NetworkConfig network = new NetworkConfig();

    private TransferConfig transfer = new TransferConfig();

    private SecurityConfig security = new SecurityConfig();

    private LoggingConfig logging = new LoggingConfig();

    // === Persistence ===

    private static DumperOptions createDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setExplicitStart(true);
        options.setExplicitEnd(false);
        return options;
    }

    private static Yaml newYaml() {
        return new Yaml(DUMPER_OPTIONS);
    }

    private static Yaml newLoader() {
        return new Yaml(new LoaderOptions());
    }

    public static AppConfig load() {
        ensureConfigExists();
        return load(CONFIG_PATH);
    }

    /**
     * Copies {@code config.example.yaml} to {@code config.yaml} on first launch so
     * a fresh clone runs without manual setup. Any existing config is left alone.
     */
    private static void ensureConfigExists() {
        if (Files.exists(CONFIG_PATH)) {
            return;
        }
        Path example = CONFIG_PATH.resolveSibling("config.example.yaml");
        try {
            Path parent = CONFIG_PATH.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            if (Files.exists(example)) {
                Files.copy(example, CONFIG_PATH);
                System.out.println("Created " + CONFIG_PATH + " from config.example.yaml");
            } else {
                try (var input = AppConfig.class.getResourceAsStream("/config.example.yaml")) {
                    if (input != null) Files.copy(input, CONFIG_PATH);
                    else new AppConfig().save(CONFIG_PATH);
                }
                System.out.println("Created " + CONFIG_PATH + " with application defaults");
            }
        } catch (IOException e) {
            System.err.println("Could not create " + CONFIG_PATH + ": " + e.getMessage()
                    + " - continuing with defaults");
        }
    }

    /**
     * Loads configuration from an explicit path, falling back to defaults when the
     * file is missing or unreadable. Unknown keys are ignored.
     * Exposed for testability.
     */
    public static AppConfig load(Path path) {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Object raw = newLoader().load(reader);
                if (raw instanceof Map) {
                    return fromMap(asStringKeyedMap(raw));
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Failed to load config, using defaults: " + e.getMessage());
            }
        }
        return new AppConfig();
    }

    public void save() {
        save(CONFIG_PATH);
    }

    /**
     * Writes configuration to an explicit path. Exposed for testability.
     */
    public void save(Path path) {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            newYaml().dump(toMap(), writer);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    // === Mapping ===

    Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> deviceMap = new LinkedHashMap<>();
        deviceMap.put("name", device.getName());
        deviceMap.put("type", device.getType().name());
        root.put("device", deviceMap);

        Map<String, Object> networkMap = new LinkedHashMap<>();
        networkMap.put("tcpPort", network.getTcpPort());
        networkMap.put("discoveryPort", network.getDiscoveryPort());
        networkMap.put("multicastGroup", network.getMulticastGroup());
        networkMap.put("announceIntervalSec", network.getAnnounceIntervalSec());
        networkMap.put("heartbeatIntervalSec", network.getHeartbeatIntervalSec());
        networkMap.put("connectionTimeoutSec", network.getConnectionTimeoutSec());
        root.put("network", networkMap);

        Map<String, Object> transferMap = new LinkedHashMap<>();
        transferMap.put("chunkSize", transfer.getChunkSize());
        transferMap.put("maxConcurrentTransfers", transfer.getMaxConcurrentTransfers());
        transferMap.put("resumeEnabled", transfer.isResumeEnabled());
        root.put("transfer", transferMap);

        Map<String, Object> securityMap = new LinkedHashMap<>();
        securityMap.put("requirePairing", security.isRequirePairing());
        securityMap.put("tlsVersion", security.getTlsVersion());
        root.put("security", securityMap);

        Map<String, Object> loggingMap = new LinkedHashMap<>();
        loggingMap.put("level", logging.getLevel());
        loggingMap.put("fileEnabled", logging.isFileEnabled());
        loggingMap.put("consoleEnabled", logging.isConsoleEnabled());
        root.put("logging", loggingMap);

        return root;
    }

    static AppConfig fromMap(Map<String, Object> root) {
        AppConfig config = new AppConfig();

        Map<String, Object> deviceMap = section(root, "device");
        if (deviceMap != null) {
            config.device.setName(string(deviceMap, "name", config.device.getName()));
            config.device.setType(deviceType(string(deviceMap, "type", null)));
        }

        Map<String, Object> networkMap = section(root, "network");
        if (networkMap != null) {
            config.network.setTcpPort(integer(networkMap, "tcpPort", config.network.getTcpPort()));
            config.network.setDiscoveryPort(integer(networkMap, "discoveryPort", config.network.getDiscoveryPort()));
            config.network.setMulticastGroup(string(networkMap, "multicastGroup", config.network.getMulticastGroup()));
            config.network.setAnnounceIntervalSec(integer(networkMap, "announceIntervalSec", config.network.getAnnounceIntervalSec()));
            config.network.setHeartbeatIntervalSec(integer(networkMap, "heartbeatIntervalSec", config.network.getHeartbeatIntervalSec()));
            config.network.setConnectionTimeoutSec(integer(networkMap, "connectionTimeoutSec", config.network.getConnectionTimeoutSec()));
        }

        Map<String, Object> transferMap = section(root, "transfer");
        if (transferMap != null) {
            config.transfer.setChunkSize(integer(transferMap, "chunkSize", config.transfer.getChunkSize()));
            config.transfer.setMaxConcurrentTransfers(integer(transferMap, "maxConcurrentTransfers", config.transfer.getMaxConcurrentTransfers()));
            config.transfer.setResumeEnabled(bool(transferMap, "resumeEnabled", config.transfer.isResumeEnabled()));
        }

        Map<String, Object> securityMap = section(root, "security");
        if (securityMap != null) {
            config.security.setRequirePairing(bool(securityMap, "requirePairing", config.security.isRequirePairing()));
            config.security.setTlsVersion(string(securityMap, "tlsVersion", config.security.getTlsVersion()));
        }

        Map<String, Object> loggingMap = section(root, "logging");
        if (loggingMap != null) {
            config.logging.setLevel(string(loggingMap, "level", config.logging.getLevel()));
            config.logging.setFileEnabled(bool(loggingMap, "fileEnabled", config.logging.isFileEnabled()));
            config.logging.setConsoleEnabled(bool(loggingMap, "consoleEnabled", config.logging.isConsoleEnabled()));
        }

        return config;
    }

    // === Small typed readers, tolerant of missing/odd values ===

    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value instanceof Map) {
            return asStringKeyedMap(value);
        }
        return null;
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    private static DeviceType deviceType(String value) {
        if (value != null) {
            for (DeviceType type : DeviceType.values()) {
                if (type.name().equalsIgnoreCase(value.trim())) {
                    return type;
                }
            }
        }
        return DeviceType.DESKTOP;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyedMap(Object value) {
        Map<Object, Object> source = (Map<Object, Object>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

    public DeviceConfig getDevice() { return device; }
    public NetworkConfig getNetwork() { return network; }
    public TransferConfig getTransfer() { return transfer; }
    public SecurityConfig getSecurity() { return security; }
    public LoggingConfig getLogging() { return logging; }

    public static class DeviceConfig {
        private String name = "LabConnect-" + System.getProperty("user.name");

        private DeviceType type = DeviceType.DESKTOP;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public DeviceType getType() { return type; }
        public void setType(DeviceType type) { this.type = type; }
    }

    public enum DeviceType {
        DESKTOP, MOBILE
    }

    public static class NetworkConfig {
        private int tcpPort = 5000;

        private int discoveryPort = 50001;

        private String multicastGroup = "239.255.255.250";

        private int announceIntervalSec = 5;

        private int heartbeatIntervalSec = 5;

        private int connectionTimeoutSec = 10;

        public int getTcpPort() { return tcpPort; }
        public void setTcpPort(int tcpPort) { this.tcpPort = tcpPort; }
        public int getDiscoveryPort() { return discoveryPort; }
        public void setDiscoveryPort(int discoveryPort) { this.discoveryPort = discoveryPort; }
        public String getMulticastGroup() { return multicastGroup; }
        public void setMulticastGroup(String multicastGroup) { this.multicastGroup = multicastGroup; }
        public int getAnnounceIntervalSec() { return announceIntervalSec; }
        public void setAnnounceIntervalSec(int announceIntervalSec) { this.announceIntervalSec = announceIntervalSec; }
        public int getHeartbeatIntervalSec() { return heartbeatIntervalSec; }
        public void setHeartbeatIntervalSec(int heartbeatIntervalSec) { this.heartbeatIntervalSec = heartbeatIntervalSec; }
        public int getConnectionTimeoutSec() { return connectionTimeoutSec; }
        public void setConnectionTimeoutSec(int connectionTimeoutSec) { this.connectionTimeoutSec = connectionTimeoutSec; }

        // --- derived conveniences, never serialised ---

        public Duration getAnnounceInterval() { return Duration.ofSeconds(announceIntervalSec); }
        public Duration getHeartbeatInterval() { return Duration.ofSeconds(heartbeatIntervalSec); }
        public Duration getConnectionTimeout() { return Duration.ofSeconds(connectionTimeoutSec); }
    }

    public static class TransferConfig {
        private int chunkSize = 65536;

        private int maxConcurrentTransfers = 4;

        private boolean resumeEnabled = true;

        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getMaxConcurrentTransfers() { return maxConcurrentTransfers; }
        public void setMaxConcurrentTransfers(int maxConcurrentTransfers) { this.maxConcurrentTransfers = maxConcurrentTransfers; }
        public boolean isResumeEnabled() { return resumeEnabled; }
        public void setResumeEnabled(boolean resumeEnabled) { this.resumeEnabled = resumeEnabled; }
    }

    public static class SecurityConfig {
        private boolean requirePairing = true;

        private String tlsVersion = "TLSv1.3";

        public boolean isRequirePairing() { return requirePairing; }
        public void setRequirePairing(boolean requirePairing) { this.requirePairing = requirePairing; }
        public String getTlsVersion() { return tlsVersion; }
        public void setTlsVersion(String tlsVersion) { this.tlsVersion = tlsVersion; }
    }

    public static class LoggingConfig {
        private String level = "INFO";

        private boolean fileEnabled = true;

        private boolean consoleEnabled = true;

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public boolean isFileEnabled() { return fileEnabled; }
        public void setFileEnabled(boolean fileEnabled) { this.fileEnabled = fileEnabled; }
        public boolean isConsoleEnabled() { return consoleEnabled; }
        public void setConsoleEnabled(boolean consoleEnabled) { this.consoleEnabled = consoleEnabled; }
    }
}
