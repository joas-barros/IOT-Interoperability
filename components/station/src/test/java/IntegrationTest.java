import config.AppConfig;
import mqtt.MqttPublisher;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.*;
import payload.PayloadBuilder;
import sensor.SensorData;
import sensor.SensorSim;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Teste 5 — Integração Completa")
public class IntegrationTest {
    private static final boolean SKIP = Boolean.getBoolean("mqtt.skip");
    private static final int     N    = 12;   // mensagens a publicar

    private AppConfig config;
    private SensorSim sensor;
    private PayloadBuilder builder;
    private MqttPublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        config    = new AppConfig();
        sensor    = new SensorSim(config);
        builder   = new PayloadBuilder();
        publisher = new MqttPublisher(config);

        if (!SKIP) {
            publisher.connect();
        }
    }

    @AfterEach
    void tearDown() {
        if (!SKIP && publisher.isConnected()) {
            publisher.disconnect();
        }
    }

    // ── Teste principal ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Pipeline completo: " + N + " mensagens publicadas e recebidas")
    @Timeout(30)
    void pipelineCompleto() throws Exception {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        CountDownLatch      latch    = new CountDownLatch(N);
        List<String> received = new ArrayList<>();

        // Subscriber de controle — recebe e valida as mensagens
        String url = String.format("tcp://%s:%d",
                config.getBrokerHost(), config.getBrokerPort());
        MqttClient sub = new MqttClient(
                url, "integration_sub_" + System.currentTimeMillis(),
                new MemoryPersistence());

        sub.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable c) {}
            @Override public void deliveryComplete(IMqttDeliveryToken t) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.add(new String(message.getPayload()));
                latch.countDown();
            }
        });
        sub.connect();
        sub.subscribe(config.getTopicTelemetry(), 1);

        // Publica N mensagens com intervalo de 500ms
        for (int i = 0; i < N; i++) {
            SensorData data    = sensor.read();
            Optional<String> json  = builder.build(data);
            assertTrue(json.isPresent(), "Payload inválido no ciclo " + i);
            publisher.publish(json.get());
            Thread.sleep(500);
        }

        // Aguarda todas chegarem (timeout: 15s)
        boolean allArrived = latch.await(15, TimeUnit.SECONDS);

        sub.disconnect();
        sub.close();

        // ── Asserções ──
        assertTrue(allArrived,
                "Nem todas as mensagens chegaram: " + received.size() + "/" + N);

        assertEquals(N, received.size(),
                "Número de mensagens recebidas incorreto");

        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  INTEGRAÇÃO — Mensagens Recebidas");
        System.out.println("══════════════════════════════════════════");
        received.forEach(msg ->
                System.out.printf("  [%d bytes] %s%n", msg.length(), msg));
        System.out.println("══════════════════════════════════════════\n");
    }

    @Test
    @DisplayName("JSON recebido deve conter todos os campos obrigatórios")
    @Timeout(20)
    void jsonDeveConterCamposObrigatorios() throws Exception {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        CountDownLatch      latch    = new CountDownLatch(1);
        List<String>        received = new ArrayList<>();

        String url = String.format("tcp://%s:%d",
                config.getBrokerHost(), config.getBrokerPort());
        MqttClient sub = new MqttClient(
                url, "fields_sub_" + System.currentTimeMillis(),
                new MemoryPersistence());

        sub.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable c) {}
            @Override public void deliveryComplete(IMqttDeliveryToken t) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.add(new String(message.getPayload()));
                latch.countDown();
            }
        });
        sub.connect();
        sub.subscribe(config.getTopicTelemetry(), 1);

        publisher.publish(builder.build(sensor.read()).orElseThrow());

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Mensagem não recebida em 5s");

        sub.disconnect();
        sub.close();

        String json = received.get(0);
        System.out.printf("%n  JSON recebido: %s%n%n", json);

        assertAll("Campos obrigatórios no JSON recebido",
                () -> assertTrue(json.contains("\"id\""),   "'id' ausente"),
                () -> assertTrue(json.contains("\"ts\""),   "'ts' ausente"),
                () -> assertTrue(json.contains("\"lat\""),  "'lat' ausente"),
                () -> assertTrue(json.contains("\"lon\""),  "'lon' ausente"),
                () -> assertTrue(json.contains("\"tmp\""),  "'tmp' ausente"),
                () -> assertTrue(json.contains("\"hum\""),  "'hum' ausente"),
                () -> assertTrue(json.contains("\"pres\""), "'pres' ausente"),
                () -> assertTrue(json.contains("\"co2\""),  "'co2' ausente"),
                () -> assertTrue(json.contains("\"uv\""),   "'uv' ausente"),
                () -> assertTrue(json.contains("\"rssi\""), "'rssi' ausente"),
                () -> assertTrue(json.contains("\"seq\""),  "'seq' ausente")
        );
    }

    @Test
    @DisplayName("Campo seq deve incrementar entre mensagens consecutivas")
    @Timeout(20)
    void seqDeveIncrementar() throws Exception {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        int MSGS = 3;
        CountDownLatch latch    = new CountDownLatch(MSGS);
        List<String>   received = new ArrayList<>();

        String url = String.format("tcp://%s:%d",
                config.getBrokerHost(), config.getBrokerPort());
        MqttClient sub = new MqttClient(
                url, "seq_sub_" + System.currentTimeMillis(),
                new MemoryPersistence());

        sub.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable c) {}
            @Override public void deliveryComplete(IMqttDeliveryToken t) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.add(new String(message.getPayload()));
                latch.countDown();
            }
        });
        sub.connect();
        sub.subscribe(config.getTopicTelemetry(), 1);

        for (int i = 0; i < MSGS; i++) {
            publisher.publish(builder.build(sensor.read()).orElseThrow());
            Thread.sleep(300);
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        sub.disconnect();
        sub.close();

        // Extrai e verifica os valores de seq
        long prevSeq = -1;
        for (String json : received) {
            int seqIdx = json.indexOf("\"seq\":") + 6;
            int seqEnd = json.indexOf("}", seqIdx);
            if (seqEnd < 0) seqEnd = json.indexOf(",", seqIdx);
            long seq = Long.parseLong(json.substring(seqIdx, seqEnd).trim());

            System.out.printf("  seq=%d%n", seq);

            if (prevSeq >= 0) {
                assertEquals(prevSeq + 1, seq,
                        "seq não incrementou: esperado " + (prevSeq + 1) + " mas foi " + seq);
            }
            prevSeq = seq;
        }
    }

    @Test
    @DisplayName("Métricas finais: taxa de entrega >= 95%")
    void metricasFinais() throws Exception {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        int MSGS = 10;
        for (int i = 0; i < MSGS; i++) {
            publisher.publish(builder.build(sensor.read()).orElseThrow());
            Thread.sleep(300);
        }
        Thread.sleep(2000); // aguarda PUBACKs

        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  MÉTRICAS FINAIS — Integração");
        System.out.println("══════════════════════════════════════════");
        System.out.printf("  Publicadas   : %d%n",   publisher.getTotalPublished());
        System.out.printf("  Entregues    : %d%n",   publisher.getTotalDelivered());
        System.out.printf("  Taxa entrega : %.1f%%%n", publisher.getDeliveryRate());
        System.out.printf("  Latência avg : %.1f ms%n", publisher.getAverageLatency());
        System.out.println("══════════════════════════════════════════\n");

        assertTrue(publisher.getDeliveryRate() >= 95.0,
                "Taxa de entrega abaixo de 95%: " + publisher.getDeliveryRate());
    }
}
