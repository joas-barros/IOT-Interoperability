
import config.AppConfig;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste 1 — AppConfig
 * Sem rede necessária.
 *
 * COMO EXECUTAR: mvn test -Dtest=ConfigTest
 */
@DisplayName("Teste 1 — AppConfig")
class ConfigTest {

    private AppConfig config;

    @BeforeEach
    void setUp() { config = new AppConfig(); }

    @Test @DisplayName("Deve carregar sem exceção")
    void deveCarregarSemExcecao() {
        assertDoesNotThrow(() -> new AppConfig());
    }

    @Test @DisplayName("CoAP port deve estar entre 1 e 65535")
    void coapPortValido() {
        assertTrue(config.getCoapPort() >= 1 && config.getCoapPort() <= 65535);
    }

    @Test @DisplayName("CoAP resource não deve ser vazio")
    void coapResourceNaoVazio() {
        assertFalse(config.getCoapResourceDrone().isBlank());
    }

    @Test @DisplayName("Broker host não deve ser vazio")
    void brokerHostNaoVazio() {
        assertFalse(config.getBrokerHost().isBlank());
    }

    @Test @DisplayName("Broker port deve estar entre 1 e 65535")
    void brokerPortValido() {
        assertTrue(config.getBrokerPort() >= 1 && config.getBrokerPort() <= 65535);
    }

    @Test @DisplayName("Tópico de telemetria não deve ser vazio")
    void topicTelemetriaNaoVazio() {
        assertFalse(config.getTopicTelemetry().isBlank());
    }

    @Test @DisplayName("Datacenter URL não deve ser vazia")
    void datacenterUrlNaoVazia() {
        assertFalse(config.getDatacenterUrl().isBlank());
    }

    @Test @DisplayName("getIngestUrl deve combinar URL base e endpoint")
    void ingestUrlCombinado() {
        String url = config.getIngestUrl();
        assertTrue(url.startsWith(config.getDatacenterUrl()),
                "URL de ingestão deve iniciar com a URL base");
        assertTrue(url.contains(config.getEndpointIngest()),
                "URL de ingestão deve conter o endpoint");
    }

    @Test @DisplayName("Retry max deve ser não-negativo")
    void retryMaxNaoNegativo() {
        assertTrue(config.getRetryMax() >= 0);
    }

    @Test @DisplayName("Sumário completo das configurações")
    void sumarioCompleto() {
        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  SUMÁRIO — AppConfig Gateway");
        System.out.println("══════════════════════════════════════════");
        System.out.printf("  Gateway ID   : %s%n", config.getGatewayId());
        System.out.printf("  CoAP         : udp/%d /%s%n",
                config.getCoapPort(), config.getCoapResourceDrone());
        System.out.printf("  MQTT broker  : %s:%d%n",
                config.getBrokerHost(), config.getBrokerPort());
        System.out.printf("  Telemetria   : %s%n", config.getTopicTelemetry());
        System.out.printf("  Status       : %s%n", config.getTopicStatus());
        System.out.printf("  Datacenter   : %s%n", config.getIngestUrl());
        System.out.printf("  Retry max    : %d%n", config.getRetryMax());
        System.out.println("══════════════════════════════════════════\n");
        assertNotNull(config);
    }
}