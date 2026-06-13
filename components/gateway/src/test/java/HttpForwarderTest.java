import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import config.AppConfig;
import forwarder.HttpForwarder;
import model.NormalizedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste 4 — HttpForwarder
 *
 * Usa um servidor HTTP mock embutido (com.sun.net.httpserver — JDK nativo)
 * para simular o datacenter sem dependência externa.
 *
 * Sem rede externa necessária — o mock roda em localhost.
 *
 * COMO EXECUTAR: mvn test -Dtest=HttpForwarderTest
 */
@DisplayName("Teste 4 — HttpForwarder")
class HttpForwarderTest {

    private static final int MOCK_PORT = 18000;

    private HttpServer mockServer;
    private HttpForwarder forwarder;
    private ObjectMapper mapper = new ObjectMapper();

    // Contadores e capturadores do mock
    private AtomicInteger       requestCount   = new AtomicInteger(0);
    private AtomicReference<String> lastBody   = new AtomicReference<>();
    private AtomicInteger       responseCode   = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws Exception {
        requestCount = new AtomicInteger(0);
        lastBody     = new AtomicReference<>();
        responseCode = new AtomicInteger(200);

        // Inicia servidor HTTP mock no JDK
        mockServer = HttpServer.create(new InetSocketAddress(MOCK_PORT), 0);
        mockServer.createContext("/ingestao", exchange -> {
            requestCount.incrementAndGet();
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            lastBody.set(new String(reqBody));

            int code = responseCode.get();
            exchange.sendResponseHeaders(code, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(code == 200 ? "OK".getBytes() : "ERROR".getBytes());
            }
        });
        mockServer.start();

        // Configura o forwarder apontando para o mock
        // Sobrescreve a URL do datacenter via System property
        System.setProperty("DATACENTER_URL_OVERRIDE",
                "http://localhost:" + MOCK_PORT);

        forwarder = new TestableForwarder(new AppConfig());
        forwarder.start();
    }

    @AfterEach
    void tearDown() {
        forwarder.shutdown();
        mockServer.stop(0);
        System.clearProperty("DATACENTER_URL_OVERRIDE");
    }

    // ── Testes ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deve enviar dado normalizado ao datacenter")
    void deveEnviarDado() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        // Substitui o handler para contar e liberar o latch
        mockServer.removeContext("/ingestao");
        mockServer.createContext("/ingestao", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastBody.set(new String(body));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("OK".getBytes());
            exchange.getResponseBody().close();
            latch.countDown();
        });

        forwarder.forward(validNormalizedData(1L, "DRONE"));

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Requisição HTTP não recebida em 5s");
        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("JSON recebido pelo datacenter deve ter campos corretos")
    void jsonDeveConterCampos() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        mockServer.removeContext("/ingestao");
        mockServer.createContext("/ingestao", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastBody.set(new String(body));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("OK".getBytes());
            exchange.getResponseBody().close();
            latch.countDown();
        });

        NormalizedData nd = validNormalizedData(42L, "DRONE");
        forwarder.forward(nd);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        JsonNode json = mapper.readTree(lastBody.get());

        assertAll("Campos do JSON enviado ao datacenter",
                () -> assertEquals("drone_01",  json.get("sourceId").asText()),
                () -> assertEquals("DRONE",     json.get("sourceType").asText()),
                () -> assertEquals("CoAP",      json.get("sourceProtocol").asText()),
                () -> assertEquals(42L,         json.get("seq").asLong()),
                () -> assertNotNull(json.get("gatewayId"),   "gatewayId ausente"),
                () -> assertNotNull(json.get("sensorTs"),    "sensorTs ausente"),
                () -> assertNotNull(json.get("gatewayTs"),   "gatewayTs ausente"),
                () -> assertNotNull(json.get("tempC"),       "tempC ausente"),
                () -> assertNotNull(json.get("lat"),         "lat ausente"),
                () -> assertNotNull(json.get("lon"),         "lon ausente")
        );
    }

    @Test
    @DisplayName("Deve encaminhar dados de drone e estação com sourceType correto")
    void deveEncaminharAmbosTipos() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        mockServer.removeContext("/ingestao");
        mockServer.createContext("/ingestao", exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("OK".getBytes());
            exchange.getResponseBody().close();
            latch.countDown();
        });

        forwarder.forward(validNormalizedData(1L, "DRONE"));
        forwarder.forward(validNormalizedData(2L, "STATION"));

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Nem todas as requisições chegaram em 5s");
        assertEquals(2, requestCount.get());
    }

    @Test
    @DisplayName("Métricas devem ser registradas após envios")
    void metricasDeveSerRegistradas() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        mockServer.removeContext("/ingestao");
        mockServer.createContext("/ingestao", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("OK".getBytes());
            exchange.getResponseBody().close();
            latch.countDown();
        });

        for (int i = 1; i <= 3; i++) {
            forwarder.forward(validNormalizedData((long)i, "DRONE"));
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        Thread.sleep(500); // aguarda métricas atualizarem

        assertEquals(3, forwarder.getTotalForwarded());
        assertEquals(3, forwarder.getTotalSuccess());
        assertTrue(forwarder.getSuccessRate() >= 99.0,
                "Taxa de sucesso esperada >= 99%: " + forwarder.getSuccessRate());
        assertTrue(forwarder.getAvgLatencyMs() > 0,
                "Latência média deve ser > 0ms");

        System.out.printf("%n  Taxa sucesso: %.1f%% | Latência avg: %.1fms%n",
                forwarder.getSuccessRate(), forwarder.getAvgLatencyMs());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private NormalizedData validNormalizedData(long seq, String sourceType) {
        NormalizedData nd    = new NormalizedData();
        nd.gatewayId         = "gateway_01";
        nd.sourceId          = sourceType.equals("DRONE") ? "drone_01" : "estacao_01";
        nd.sourceType        = sourceType;
        nd.sourceProtocol    = sourceType.equals("DRONE") ? "CoAP" : "MQTT";
        nd.payloadFormat     = sourceType.equals("DRONE") ? "CBOR" : "JSON";
        nd.sensorTs          = "2026-05-21T10:00:00.000Z";
        nd.gatewayTs         = "2026-05-21T10:00:00.150Z";
        nd.lat               = -5.7923;
        nd.lon               = -35.2128;
        nd.tempC             = 29.5;
        nd.humPct            = 68.0;
        nd.rssiDbm           = -61;
        nd.seq               = seq;
        if ("DRONE".equals(sourceType)) {
            nd.altM          = 80.0;
            nd.batteryPct    = 74;
            nd.batteryOk     = true;
            nd.flightPhase   = "MISSION";
        } else {
            nd.pressureHpa   = 1012.4;
            nd.co2Ppm        = 418.2;
            nd.uvIndex       = 6.1;
        }
        return nd;
    }

    /**
     * Subclasse que sobrescreve a URL do datacenter para o mock.
     */
    private static class TestableForwarder extends HttpForwarder {
        TestableForwarder(AppConfig config) {
            super(new MockableConfig(config));
        }
    }

    private static class MockableConfig extends AppConfig {
        MockableConfig(AppConfig base) { super(); }

        @Override
        public String getIngestUrl() {
            String override = System.getProperty("DATACENTER_URL_OVERRIDE");
            return (override != null ? override : super.getDatacenterUrl())
                    + super.getEndpointIngest();
        }
    }
}
