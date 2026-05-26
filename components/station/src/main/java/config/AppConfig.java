package config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    // ── MQTT ──────────────────────────────────────────────────────────────────
    private final String brokerHost;
    private final int brokerPort;
    private final String brokerUser;
    private final String brokerPassword;
    private final int connectionTimeout;
    private final int keepAlive;

    // ── Tópicos ───────────────────────────────────────────────────────────────
    private final String topicBase;
    private final String topicStatus;
    private final String topicTelemetry;

    // ── Publicação ────────────────────────────────────────────────────────────
    private final long publishIntervalMs;
    private final int publishQos;
    private final boolean publishRetained;

    // ── Identidade ────────────────────────────────────────────────────────────
    private final String stationId;
    private final double stationLat;
    private final double stationLon;

    // ── Sensores ──────────────────────────────────────────────────────────────
    private final double tempBase;
    private final double tempSigma;
    private final double humBase;
    private final double humSigma;
    private final double pressureBase;
    private final double pressureSigma;
    private final double co2Base;
    private final double co2Sigma;
    private final double uvBase;
    private final double uvSigma;
    private final long randomSeed;

    // ─────────────────────────────────────────────────────────────────────────


    public AppConfig() {
        Properties props = loadProperties();

        // ── MQTT ──
        brokerHost        = requireString(props, "mqtt.broker.host");
        brokerPort        = requireInt(props,    "mqtt.broker.port", 1, 65535);
        brokerUser        = props.getProperty("mqtt.broker.user", "").trim();
        brokerPassword    = props.getProperty("mqtt.broker.password", "").trim();
        connectionTimeout = requireInt(props,    "mqtt.connection.timeout", 1, 300);
        keepAlive         = requireInt(props,    "mqtt.keepalive", 10, 3600);

        // ── Tópicos ──
        topicBase     = requireString(props, "mqtt.topic.base");
        topicStatus   = requireString(props, "mqtt.topic.status");
        stationId     = requireString(props, "station.id");
        topicTelemetry = topicBase + "/" + stationId + "/telemetria";

        // ── Publicação ──
        publishIntervalMs = requireLong(props, "mqtt.publish.interval.ms", 1000, 3_600_000);
        publishQos        = requireInt(props,  "mqtt.publish.qos", 0, 2);
        publishRetained   = Boolean.parseBoolean(
                props.getProperty("mqtt.publish.retained", "false").trim());

        // ── Identidade ──
        stationLat = requireDouble(props, "station.lat", -90.0, 90.0);
        stationLon = requireDouble(props, "station.lon", -180.0, 180.0);

        // ── Sensores ──
        tempBase     = requireDouble(props, "sensor.temp.base",     -20.0, 60.0);
        tempSigma    = requireDouble(props, "sensor.temp.sigma",      0.0, 10.0);
        humBase      = requireDouble(props, "sensor.hum.base",        0.0, 100.0);
        humSigma     = requireDouble(props, "sensor.hum.sigma",       0.0, 20.0);
        pressureBase = requireDouble(props, "sensor.pressure.base", 800.0, 1100.0);
        pressureSigma= requireDouble(props, "sensor.pressure.sigma",  0.0, 10.0);
        co2Base      = requireDouble(props, "sensor.co2.base",       300.0, 5000.0);
        co2Sigma     = requireDouble(props, "sensor.co2.sigma",        0.0, 100.0);
        uvBase       = requireDouble(props, "sensor.uv.base",          0.0, 11.0);
        uvSigma      = requireDouble(props, "sensor.uv.sigma",         0.0, 3.0);
        randomSeed   = Long.parseLong(
                props.getProperty("sensor.random.seed", "0").trim());

        log();

    }

    // ── Carregamento ─────────────────────────────────────────────────────────

    private Properties loadProperties() {
        Properties base  = load("station.properties", true);
        Properties local = load("station-local.properties", false);

        // local sobrescreve base (sem alterar o arquivo versionado)
        if (local != null) {
            base.putAll(local);
            log.info("[Config] station-local.properties carregado — sobrescrevendo valores base.");
        }

        return base;
    }

    private Properties load(String filename, boolean required) {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                if (required) {
                    throw new IllegalStateException(
                            "Arquivo de configuração não encontrado no classpath: " + filename +
                                    "\nVerifique se está em src/main/resources/");
                }
                return null;
            }
            props.load(is);
            log.debug("[Config] Carregado: {}", filename);
            return props;
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao ler " + filename + ": " + e.getMessage(), e);
        }
    }

    // ── Helpers de validação ──────────────────────────────────────────────────
    private String requireString(Properties p, String key) {
        String val = p.getProperty(key, "").trim();
        if (val.isEmpty()) {
            throw new IllegalArgumentException(
                    "[Config] Propriedade obrigatória ausente ou vazia: " + key);
        }
        return val;
    }

    private int requireInt(Properties p, String key, int min, int max) {
        String raw = requireString(p, key);
        try {
            int val = Integer.parseInt(raw);
            if (val < min || val > max) {
                throw new IllegalArgumentException(
                        String.format("[Config] %s=%d fora do range [%d, %d]", key, val, min, max));
            }
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "[Config] " + key + " não é um inteiro válido: " + raw);
        }
    }

    private long requireLong(Properties p, String key, long min, long max) {
        String raw = requireString(p, key);
        try {
            long val = Long.parseLong(raw);
            if (val < min || val > max) {
                throw new IllegalArgumentException(
                        String.format("[Config] %s=%d fora do range [%d, %d]", key, val, min, max));
            }
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "[Config] " + key + " não é um long válido: " + raw);
        }
    }

    private double requireDouble(Properties p, String key, double min, double max) {
        String raw = requireString(p, key);
        try {
            double val = Double.parseDouble(raw);
            if (val < min || val > max) {
                throw new IllegalArgumentException(
                        String.format("[Config] %s=%.4f fora do range [%.2f, %.2f]", key, val, min, max));
            }
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "[Config] " + key + " não é um double válido: " + raw);
        }
    }

    private void log() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║       Configuração Carregada             ║");
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ Estação ID : {}", stationId);
        log.info("║ Broker     : {}:{}", brokerHost, brokerPort);
        log.info("║ Tópico     : {}", topicTelemetry);
        log.info("║ Status     : {}", topicStatus);
        log.info("║ Intervalo  : {} ms", publishIntervalMs);
        log.info("║ QoS        : {}", publishQos);
        log.info("║ Lat/Lon    : {}, {}", stationLat, stationLon);
        log.info("║ Seed       : {}", randomSeed == 0 ? "aleatória" : randomSeed);
        log.info("╚══════════════════════════════════════════╝");

    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String  getBrokerHost()        { return brokerHost; }
    public int     getBrokerPort()        { return brokerPort; }
    public String  getBrokerUser()        { return brokerUser; }
    public String  getBrokerPassword()    { return brokerPassword; }
    public int     getConnectionTimeout() { return connectionTimeout; }
    public int     getKeepAlive()         { return keepAlive; }

    public String  getTopicTelemetry()    { return topicTelemetry; }
    public String  getTopicStatus()       { return topicStatus; }

    public long    getPublishIntervalMs() { return publishIntervalMs; }
    public int     getPublishQos()        { return publishQos; }
    public boolean isPublishRetained()    { return publishRetained; }

    public String  getStationId()         { return stationId; }
    public double  getStationLat()        { return stationLat; }
    public double  getStationLon()        { return stationLon; }

    public double  getTempBase()          { return tempBase; }
    public double  getTempSigma()         { return tempSigma; }
    public double  getHumBase()           { return humBase; }
    public double  getHumSigma()          { return humSigma; }
    public double  getPressureBase()      { return pressureBase; }
    public double  getPressureSigma()     { return pressureSigma; }
    public double  getCo2Base()           { return co2Base; }
    public double  getCo2Sigma()          { return co2Sigma; }
    public double  getUvBase()            { return uvBase; }
    public double  getUvSigma()           { return uvSigma; }
    public long    getRandomSeed()        { return randomSeed; }
}
