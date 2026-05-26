import config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Teste 1 — AppConfig")
public class ConfigTest {

    private AppConfig config;

    @BeforeEach
    void setup() {
        config = new AppConfig();
    }

    // ── Carregamento básico ───────────────────────────────────────────────────
    @Test
    @DisplayName("Deve carregar sem lançar exceção")
    void deveCarregarSemExcecao() {
        assertDoesNotThrow(() -> new AppConfig());
    }

    // ── MQTT ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Broker host não deve ser vazio")
    void brokerHostNaoDeveSerVazio() {
        assertFalse(config.getBrokerHost().isBlank(),
                "mqtt.broker.host não pode ser vazio");
    }

    @Test
    @DisplayName("Broker port deve estar entre 1 e 65535")
    void brokerPortDeveEstarNoRange() {
        int port = config.getBrokerPort();
        assertTrue(port >= 1 && port <= 65535,
                "Porta inválida: " + port);
    }

    @Test
    @DisplayName("QoS deve ser 0, 1 ou 2")
    void qosDeveSerValido() {
        int qos = config.getPublishQos();
        assertTrue(qos == 0 || qos == 1 || qos == 2,
                "QoS inválido: " + qos);
    }

    @Test
    @DisplayName("Intervalo de publicação deve ser >= 1000ms")
    void intervaloDeveSerPositivo() {
        assertTrue(config.getPublishIntervalMs() >= 1000,
                "Intervalo muito curto: " + config.getPublishIntervalMs() + "ms");
    }

    // ── Identidade ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Station ID não deve ser vazio")
    void stationIdNaoDeveSerVazio() {
        assertFalse(config.getStationId().isBlank(),
                "station.id não pode ser vazio");
    }

    @Test
    @DisplayName("Latitude deve estar entre -90 e 90")
    void latitudeDeveEstarNoRange() {
        double lat = config.getStationLat();
        assertTrue(lat >= -90.0 && lat <= 90.0,
                "Latitude inválida: " + lat);
    }

    @Test
    @DisplayName("Longitude deve estar entre -180 e 180")
    void longitudeDeveEstarNoRange() {
        double lon = config.getStationLon();
        assertTrue(lon >= -180.0 && lon <= 180.0,
                "Longitude inválida: " + lon);
    }

    // ── Tópicos ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tópico de telemetria deve conter o station ID")
    void topicoDeveConterStationId() {
        String topic = config.getTopicTelemetry();
        assertTrue(topic.contains(config.getStationId()),
                "Tópico '" + topic + "' não contém o station ID '" + config.getStationId() + "'");
    }

    @Test
    @DisplayName("Tópico de telemetria deve terminar com /telemetria")
    void topicoDeveTerminarComTelemetria() {
        assertTrue(config.getTopicTelemetry().endsWith("/telemetria"),
                "Tópico deve terminar com /telemetria: " + config.getTopicTelemetry());
    }

    // ── Sumário ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sumário completo — imprime todas as configurações carregadas")
    void sumarioCompleto() {
        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  SUMÁRIO — AppConfig");
        System.out.println("══════════════════════════════════════════");
        System.out.printf("  Broker      : %s:%d%n",
                config.getBrokerHost(), config.getBrokerPort());
        System.out.printf("  Tópico      : %s%n",
                config.getTopicTelemetry());
        System.out.printf("  Status      : %s%n",
                config.getTopicStatus());
        System.out.printf("  Intervalo   : %d ms%n",
                config.getPublishIntervalMs());
        System.out.printf("  QoS         : %d%n",
                config.getPublishQos());
        System.out.printf("  Station ID  : %s%n",
                config.getStationId());
        System.out.printf("  Lat/Lon     : %.6f, %.6f%n",
                config.getStationLat(), config.getStationLon());
        System.out.printf("  Temp base   : %.1f°C (σ=%.2f)%n",
                config.getTempBase(), config.getTempSigma());
        System.out.printf("  Hum base    : %.1f%% (σ=%.2f)%n",
                config.getHumBase(), config.getHumSigma());
        System.out.printf("  Pressão base: %.2f hPa (σ=%.2f)%n",
                config.getPressureBase(), config.getPressureSigma());
        System.out.printf("  CO2 base    : %.1f ppm (σ=%.2f)%n",
                config.getCo2Base(), config.getCo2Sigma());
        System.out.printf("  UV base     : %.1f (σ=%.2f)%n",
                config.getUvBase(), config.getUvSigma());
        System.out.printf("  Random seed : %s%n",
                config.getRandomSeed() == 0 ? "aleatória" : config.getRandomSeed());
        System.out.println("══════════════════════════════════════════\n");

        // Se chegou aqui sem exceção, o carregamento está correto
        assertNotNull(config);
    }
}
