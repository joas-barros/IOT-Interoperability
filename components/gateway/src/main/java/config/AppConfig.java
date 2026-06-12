package config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    // ── Identidade ────────────────────────────────────────────────────────────
    private final String gatewayId;

    // ── CoAP ──────────────────────────────────────────────────────────────────
    private final int    coapPort;
    private final String coapResourceDrone;

    // ── MQTT ──────────────────────────────────────────────────────────────────
    private final String brokerHost;
    private final int    brokerPort;
    private final String brokerUser;
    private final String brokerPassword;
    private final int    connectionTimeout;
    private final int    keepAlive;
    private final String topicTelemetry;
    private final String topicStatus;

    // ── HTTP / Datacenter ─────────────────────────────────────────────────────
    private final String datacenterUrl;
    private final String endpointIngest;
    private final String endpointMetrics;
    private final int    connectTimeoutMs;
    private final int    readTimeoutMs;
    private final int    retryMax;
    private final long   retryBackoffMs;
    private final int    retryQueueSize;

    // ── Pipeline ──────────────────────────────────────────────────────────────
    private final long metricsIntervalMs;

    // ─────────────────────────────────────────────────────────────────────────

    public AppConfig() {
        Properties props = loadProperties();

        gatewayId          = requireString(props, "gateway.id");

        coapPort           = requireInt(props, "coap.port", 1, 65535);
        coapResourceDrone  = requireString(props, "coap.resource.drone");

        // Variáveis de ambiente sobrescrevem .properties (para Docker)
        brokerHost         = envOrProp("MQTT_BROKER_HOST",
                props.getProperty("mqtt.broker.host", "localhost").trim());
        brokerPort         = Integer.parseInt(
                envOrProp("MQTT_BROKER_PORT",
                        props.getProperty("mqtt.broker.port", "1883").trim()));
        brokerUser         = props.getProperty("mqtt.broker.user", "").trim();
        brokerPassword     = props.getProperty("mqtt.broker.password", "").trim();
        connectionTimeout  = requireInt(props, "mqtt.connection.timeout", 1, 300);
        keepAlive          = requireInt(props, "mqtt.keepalive", 10, 3600);
        topicTelemetry     = requireString(props, "mqtt.topic.telemetry");
        topicStatus        = requireString(props, "mqtt.topic.status");

        datacenterUrl      = envOrProp("DATACENTER_URL",
                props.getProperty("datacenter.url", "").trim());
        if (datacenterUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "[Config] datacenter.url obrigatório (ou env DATACENTER_URL)");
        }
        endpointIngest     = requireString(props, "datacenter.endpoint.ingest");
        endpointMetrics    = requireString(props, "datacenter.endpoint.metrics");
        connectTimeoutMs   = requireInt(props, "datacenter.connect.timeout.ms", 100, 30000);
        readTimeoutMs      = requireInt(props, "datacenter.read.timeout.ms",    100, 60000);
        retryMax           = requireInt(props, "datacenter.retry.max",          0,  10);
        retryBackoffMs     = requireLong(props, "datacenter.retry.backoff.ms",  100, 60000);
        retryQueueSize     = requireInt(props, "datacenter.retry.queue.size",   1,  10000);

        metricsIntervalMs  = requireLong(props, "pipeline.metrics.interval.ms", 5000, 3_600_000);

        logSummary();
    }

    // ── Carregamento ─────────────────────────────────────────────────────────

    private Properties loadProperties() {
        Properties base  = load("gateway.properties", true);
        Properties local = load("gateway-local.properties", false);
        if (local != null) {
            base.putAll(local);
            log.info("[Config] gateway-local.properties aplicado.");
        }
        return base;
    }

    private Properties load(String filename, boolean required) {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(filename)) {
            if (is == null) {
                if (required) throw new IllegalStateException(
                        "Arquivo não encontrado no classpath: " + filename);
                return null;
            }
            Properties p = new Properties();
            p.load(is);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao ler " + filename, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String envOrProp(String envKey, String propValue) {
        String env = System.getenv(envKey);
        return (env != null && !env.isBlank()) ? env.trim() : propValue;
    }

    private String requireString(Properties p, String key) {
        String v = p.getProperty(key, "").trim();
        if (v.isEmpty()) throw new IllegalArgumentException(
                "[Config] Propriedade obrigatória ausente: " + key);
        return v;
    }

    private int requireInt(Properties p, String key, int min, int max) {
        String raw = requireString(p, key);
        try {
            int v = Integer.parseInt(raw);
            if (v < min || v > max) throw new IllegalArgumentException(
                    String.format("[Config] %s=%d fora de [%d, %d]", key, v, min, max));
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("[Config] " + key + " não é inteiro: " + raw);
        }
    }

    private long requireLong(Properties p, String key, long min, long max) {
        String raw = requireString(p, key);
        try {
            long v = Long.parseLong(raw);
            if (v < min || v > max) throw new IllegalArgumentException(
                    String.format("[Config] %s=%d fora de [%d, %d]", key, v, min, max));
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("[Config] " + key + " não é long: " + raw);
        }
    }

    private void logSummary() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║     Gateway IoT — Configuração           ║");
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ ID          : {}", gatewayId);
        log.info("║ CoAP porta  : udp/{}", coapPort);
        log.info("║ CoAP recurso: /{}", coapResourceDrone);
        log.info("║ MQTT broker : {}:{}", brokerHost, brokerPort);
        log.info("║ MQTT telemetria: {}", topicTelemetry);
        log.info("║ Datacenter  : {}{}", datacenterUrl, endpointIngest);
        log.info("╚══════════════════════════════════════════╝");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getGatewayId()          { return gatewayId; }
    public int    getCoapPort()           { return coapPort; }
    public String getCoapResourceDrone()  { return coapResourceDrone; }
    public String getBrokerHost()         { return brokerHost; }
    public int    getBrokerPort()         { return brokerPort; }
    public String getBrokerUser()         { return brokerUser; }
    public String getBrokerPassword()     { return brokerPassword; }
    public int    getConnectionTimeout()  { return connectionTimeout; }
    public int    getKeepAlive()          { return keepAlive; }
    public String getTopicTelemetry()     { return topicTelemetry; }
    public String getTopicStatus()        { return topicStatus; }
    public String getDatacenterUrl()      { return datacenterUrl; }
    public String getEndpointIngest()     { return endpointIngest; }
    public String getEndpointMetrics()    { return endpointMetrics; }
    public int    getConnectTimeoutMs()   { return connectTimeoutMs; }
    public int    getReadTimeoutMs()      { return readTimeoutMs; }
    public int    getRetryMax()           { return retryMax; }
    public long   getRetryBackoffMs()     { return retryBackoffMs; }
    public int    getRetryQueueSize()     { return retryQueueSize; }
    public long   getMetricsIntervalMs()  { return metricsIntervalMs; }

    public String getIngestUrl() {
        return datacenterUrl + endpointIngest;
    }
    public String getMetricsUrl() {
        return datacenterUrl + endpointMetrics;
    }
}
