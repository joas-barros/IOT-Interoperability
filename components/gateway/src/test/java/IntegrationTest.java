import coap.GatewayCoapServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.sun.net.httpserver.HttpServer;
import config.AppConfig;
import forwarder.HttpForwarder;
import model.DroneData;
import model.StationData;
import mqtt.MqttSubscriber;
import normalizer.DroneNormalizer;
import normalizer.StationNormalizer;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.elements.exception.ConnectorException;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.*;
import pipeline.DataPipeline;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Teste 5 — Integração Completa do Gateway")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegrationTest {

    private static final boolean SKIP_MQTT = Boolean.getBoolean("mqtt.skip");

    // Portas usadas nos testes — diferentes das de produção para não conflitar
    private static final int COAP_TEST_PORT = 5684;
    private static final int HTTP_MOCK_PORT = 18001;
    private static final int MQTT_TEST_PORT = 1883;

    private static final int N_MESSAGES = 5;

    // ── Infraestrutura do teste ───────────────────────────────────────────
    private AppConfig config;
    private DataPipeline pipeline;
    private GatewayCoapServer coapServer;
    private MqttSubscriber mqttSubscriber;
    private HttpForwarder forwarder;
    private HttpServer mockDatacenter;

    // Captura de dados recebidos pelo datacenter mock
    private final List<String> receivedBodies = new ArrayList<>();
    private final AtomicInteger receivedCount  = new AtomicInteger(0);
    private CountDownLatch latch;

    // Mappers
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    // ─────────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        receivedBodies.clear();
        receivedCount.set(0);

        // ── 1. Servidor HTTP mock (datacenter) ──
        mockDatacenter = HttpServer.create(
                new InetSocketAddress(HTTP_MOCK_PORT), 0);
        mockDatacenter.createContext("/ingestao", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (receivedBodies) {
                receivedBodies.add(new String(body, StandardCharsets.UTF_8));
            }
            receivedCount.incrementAndGet();
            if (latch != null) latch.countDown();

            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("OK".getBytes());
            }
        });
        mockDatacenter.start();

        // ── 2. Config apontando para mock ──
        config = new IntegrationAppConfig();

        // ── 3. Componentes ──
        DroneNormalizer   droneNorm   = new DroneNormalizer(config);
        StationNormalizer stationNorm = new StationNormalizer(config);
        forwarder  = new HttpForwarder(config);
        pipeline   = new DataPipeline(config, droneNorm, stationNorm, forwarder);
        coapServer = new IntegrationCoapServer(config, pipeline);

        // ── 4. Sobe componentes ──
        pipeline.start();
        coapServer.start();

        if (!SKIP_MQTT) {
            mqttSubscriber = new MqttSubscriber(config, pipeline);
            mqttSubscriber.connect();
        }

        // Aguarda estabilização
        Thread.sleep(500);
    }

    @AfterEach
    void tearDown() {
        if (mqttSubscriber != null) mqttSubscriber.disconnect();
        coapServer.stop();
        pipeline.shutdown();
        mockDatacenter.stop(0);
    }

    // ── Teste 1: CoAP com CBOR ────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("CoAP CBOR → normalização → datacenter")
    @Timeout(30)
    void coapCborParaDatacenter() throws Exception {
        latch = new CountDownLatch(1);

        byte[] cborPayload = buildCborPayload(1L);
        CoapResponse response = sendCoap(cborPayload,
                MediaTypeRegistry.APPLICATION_CBOR);

        // Verifica ACK imediato do gateway
        assertNotNull(response, "Gateway não respondeu ao CoAP POST");
        assertEquals(CoAP.ResponseCode.CHANGED, response.getCode(),
                "Código de resposta CoAP esperado: 2.04 CHANGED");

        // Verifica que dado chegou ao datacenter
        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Dado CBOR não chegou ao datacenter em 5s");

        String body = receivedBodies.get(0);
        JsonNode json = jsonMapper.readTree(body);

        assertAll("Dado CBOR normalizado no datacenter",
                () -> assertEquals("DRONE",   json.get("sourceType").asText()),
                () -> assertEquals("CoAP",    json.get("sourceProtocol").asText()),
                () -> assertEquals("CBOR",    json.get("payloadFormat").asText()),
                () -> assertEquals("drone_01",json.get("sourceId").asText()),
                () -> assertEquals(1L,        json.get("seq").asLong()),
                () -> assertNotNull(json.get("gatewayTs"), "gatewayTs ausente"),
                () -> assertNotNull(json.get("sensorTs"),  "sensorTs ausente"),
                () -> assertNotNull(json.get("tempC"),     "tempC ausente"),
                () -> assertNotNull(json.get("lat"),       "lat ausente"),
                () -> assertNull(json.get("pressureHpa"), "pressureHpa deve ser null para drone")
        );

        System.out.printf("%n  [CBOR→Datacenter] Payload recebido: %s%n", body);
    }

    // ── Teste 2: CoAP com JSON ────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("CoAP JSON → normalização → datacenter")
    @Timeout(30)
    void coapJsonParaDatacenter() throws Exception {
        latch = new CountDownLatch(1);

        byte[] jsonPayload = buildJsonPayload(2L);
        CoapResponse response = sendCoap(jsonPayload,
                MediaTypeRegistry.APPLICATION_JSON);

        assertNotNull(response, "Gateway não respondeu ao CoAP POST JSON");
        assertEquals(CoAP.ResponseCode.CHANGED, response.getCode());

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Dado JSON não chegou ao datacenter em 5s");

        JsonNode json = jsonMapper.readTree(receivedBodies.get(0));
        assertAll("Dado JSON normalizado no datacenter",
                () -> assertEquals("DRONE", json.get("sourceType").asText()),
                () -> assertEquals("JSON",  json.get("payloadFormat").asText()),
                () -> assertEquals(2L,      json.get("seq").asLong())
        );

        System.out.printf("%n  [JSON→Datacenter] Payload recebido: %s%n",
                receivedBodies.get(0));
    }

    // ── Teste 3: MQTT ─────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("MQTT → normalização → datacenter")
    @Timeout(30)
    void mqttParaDatacenter() throws Exception {
        if (SKIP_MQTT) {
            System.out.println("[SKIP] Broker MQTT não disponível.");
            return;
        }

        latch = new CountDownLatch(1);

        // Publica via Paho diretamente no broker
        String stationJson = buildStationJson(3L);
        publishMqtt(config.getTopicTelemetry(), stationJson);

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Dado MQTT não chegou ao datacenter em 5s");

        JsonNode json = jsonMapper.readTree(receivedBodies.get(0));
        assertAll("Dado MQTT normalizado no datacenter",
                () -> assertEquals("STATION",    json.get("sourceType").asText()),
                () -> assertEquals("MQTT",       json.get("sourceProtocol").asText()),
                () -> assertEquals("estacao_01", json.get("sourceId").asText()),
                () -> assertEquals(3L,           json.get("seq").asLong()),
                () -> assertNotNull(json.get("pressureHpa"), "pressureHpa ausente"),
                () -> assertNotNull(json.get("co2Ppm"),      "co2Ppm ausente"),
                () -> assertNull(json.get("altM"),           "altM deve ser null para estação")
        );

        System.out.printf("%n  [MQTT→Datacenter] Payload recebido: %s%n",
                receivedBodies.get(0));
    }

    // ── Teste 4: N mensagens CoAP ─────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("N mensagens CoAP chegam ao datacenter com seq incrementando")
    @Timeout(60)
    void nMensagensCoapComSeqIncrementando() throws Exception {
        latch = new CountDownLatch(N_MESSAGES);

        for (long seq = 1; seq <= N_MESSAGES; seq++) {
            byte[] payload = buildCborPayload(seq);
            CoapResponse resp = sendCoap(payload, MediaTypeRegistry.APPLICATION_CBOR);
            assertNotNull(resp, "Gateway não respondeu para seq=" + seq);
            assertEquals(CoAP.ResponseCode.CHANGED, resp.getCode());
            Thread.sleep(200);
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS),
                "Nem todas as mensagens chegaram ao datacenter: "
                        + receivedCount.get() + "/" + N_MESSAGES);

        assertEquals(N_MESSAGES, receivedCount.get(),
                "Número de mensagens recebidas incorreto");

        // Verifica que os seq estão todos presentes
        List<Long> seqs = new ArrayList<>();
        synchronized (receivedBodies) {
            for (String body : receivedBodies) {
                JsonNode node = jsonMapper.readTree(body);
                seqs.add(node.get("seq").asLong());
            }
        }

        System.out.printf("%n  Sequências recebidas: %s%n", seqs);

        for (long expected = 1; expected <= N_MESSAGES; expected++) {
            final long e = expected;
            assertTrue(seqs.contains(e),
                    "seq=" + e + " não encontrado nas mensagens recebidas");
        }
    }

    // ── Teste 5: Latência CoAP ────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Latência CoAP deve ser < 500ms em rede local")
    @Timeout(30)
    void latenciaCoapDeveSerAceitavel() throws Exception {
        int SAMPLES = 10;
        long totalRtt = 0;
        long maxRtt   = 0;

        for (int i = 1; i <= SAMPLES; i++) {
            byte[] payload = buildCborPayload((long) i);
            long   start   = System.currentTimeMillis();

            CoapResponse resp = sendCoap(payload, MediaTypeRegistry.APPLICATION_CBOR);
            long rtt = System.currentTimeMillis() - start;

            assertNotNull(resp, "Sem resposta CoAP para amostra " + i);
            assertEquals(CoAP.ResponseCode.CHANGED, resp.getCode());

            totalRtt += rtt;
            if (rtt > maxRtt) maxRtt = rtt;

            Thread.sleep(200);
        }

        long avgRtt = totalRtt / SAMPLES;

        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  Latência CoAP (loopback, " + SAMPLES + " amostras)");
        System.out.println("══════════════════════════════════════════");
        System.out.printf("  RTT médio : %d ms%n", avgRtt);
        System.out.printf("  RTT máximo: %d ms%n", maxRtt);
        System.out.println("══════════════════════════════════════════\n");

        // Em loopback, latência deve ser < 500ms
        assertTrue(avgRtt < 500,
                "RTT médio muito alto: " + avgRtt + "ms (esperado < 500ms em loopback)");
    }

    // ── Teste 6: CoAP + MQTT simultâneos ─────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Drone e estação enviando simultaneamente — ambos chegam ao datacenter")
    @Timeout(30)
    void fontesSimultaneas() throws Exception {
        if (SKIP_MQTT) {
            System.out.println("[SKIP] Broker MQTT não disponível.");
            return;
        }

        int totalEsperado = 4; // 2 CoAP + 2 MQTT
        latch = new CountDownLatch(totalEsperado);

        // Envia 2 mensagens CoAP e 2 MQTT intercaladas
        sendCoap(buildCborPayload(1L), MediaTypeRegistry.APPLICATION_CBOR);
        publishMqtt(config.getTopicTelemetry(), buildStationJson(1L));
        Thread.sleep(100);
        sendCoap(buildCborPayload(2L), MediaTypeRegistry.APPLICATION_CBOR);
        publishMqtt(config.getTopicTelemetry(), buildStationJson(2L));

        assertTrue(latch.await(10, TimeUnit.SECONDS),
                "Nem todas as mensagens chegaram: "
                        + receivedCount.get() + "/" + totalEsperado);

        assertEquals(totalEsperado, receivedCount.get());

        // Conta por tipo
        long drones   = receivedBodies.stream()
                .filter(b -> b.contains("\"sourceType\":\"DRONE\"")).count();
        long stations = receivedBodies.stream()
                .filter(b -> b.contains("\"sourceType\":\"STATION\"")).count();

        System.out.printf("%n  Drones recebidos: %d | Estações recebidas: %d%n",
                drones, stations);

        assertEquals(2, drones,   "Esperadas 2 mensagens de drone");
        assertEquals(2, stations, "Esperadas 2 mensagens de estação");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Envia requisição CoAP POST e aguarda resposta (síncrono). */
    private CoapResponse sendCoap(byte[] payload, int contentFormat) throws ConnectorException, IOException {
        CoapClient client = new CoapClient(
                "coap://localhost:" + COAP_TEST_PORT + "/"
                        + config.getCoapResourceDrone());
        client.setTimeout(5000L);

        Request request = new Request(CoAP.Code.POST);
        request.setPayload(payload);
        request.getOptions().setContentFormat(contentFormat);

        return client.advanced(request);
    }

    /** Publica mensagem MQTT via Paho (simula a estação). */
    private void publishMqtt(String topic, String payload) throws MqttException {
        String url      = String.format("tcp://%s:%d",
                config.getBrokerHost(), MQTT_TEST_PORT);
        MqttClient pub  = new MqttClient(url,
                "test_pub_" + System.currentTimeMillis(),
                new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setConnectionTimeout(10);
        pub.connect(opts);
        pub.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
        pub.disconnect();
        pub.close();
    }

    /** Constrói payload CBOR simulando o ESP32. */
    private byte[] buildCborPayload(long seq) throws Exception {
        DroneData d     = buildDroneData(seq);
        return cborMapper.writeValueAsBytes(d);
    }

    /** Constrói payload JSON simulando o ESP32 em modo debug. */
    private byte[] buildJsonPayload(long seq) throws Exception {
        DroneData d = buildDroneData(seq);
        return jsonMapper.writeValueAsBytes(d);
    }

    /** Constrói payload JSON simulando a estação. */
    private String buildStationJson(long seq) throws Exception {
        StationData s   = new StationData();
        s.id            = "estacao_01";
        s.sensorTs      = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        s.lat           = -5.7901;
        s.lon           = -35.2098;
        s.tempC         = 29.8;
        s.humPct        = 71.3;
        s.pressureHpa   = 1012.4;
        s.co2Ppm        = 418.2;
        s.uvIndex       = 6.1;
        s.rssiDbm       = -48;
        s.seq           = seq;
        return jsonMapper.writeValueAsString(s);
    }

    private DroneData buildDroneData(long seq) {
        DroneData d     = new DroneData();
        d.id            = "drone_01";
        d.sensorTs      = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        d.flightPhase   = "MISSION";
        d.waypointIndex = 1;
        d.lat           = -5.7923;
        d.lon           = -35.2128;
        d.altM          = 80.0;
        d.headingDeg    = 127.0;
        d.velocityMs    = 11.2;
        d.tempC         = 29.5;
        d.humPct        = 68.0;
        d.batteryPct    = 74;
        d.batteryOk     = true;
        d.rssiDbm       = -61;
        d.seq           = seq;
        return d;
    }

    // ── Config sobrescrita para testes ────────────────────────────────────

    /**
     * AppConfig com porta CoAP e URL do datacenter sobrescritas
     * para os valores de teste, sem alterar o gateway.properties.
     */
    private static class IntegrationAppConfig extends AppConfig {
        @Override public int    getCoapPort()      { return COAP_TEST_PORT; }
        @Override public String getIngestUrl()     {
            return "http://localhost:" + HTTP_MOCK_PORT + getEndpointIngest();
        }
        @Override public String getMetricsUrl()    {
            return "http://localhost:" + HTTP_MOCK_PORT + getEndpointMetrics();
        }
        @Override public String getBrokerHost()    { return "localhost"; }
        @Override public int    getBrokerPort()    { return MQTT_TEST_PORT; }
    }

    /**
     * GatewayCoapServer que usa a porta de teste em vez da de produção.
     * Necessário porque o Californium lê a porta no construtor.
     */
    private static class IntegrationCoapServer extends GatewayCoapServer {
        IntegrationCoapServer(AppConfig config, DataPipeline pipeline) {
            super(config, pipeline);
        }
    }
}