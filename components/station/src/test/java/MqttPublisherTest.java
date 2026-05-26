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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Teste 4 — MqttPublisher")
public class MqttPublisherTest {

    private static final boolean SKIP = Boolean.getBoolean("mqtt.skip");

    private AppConfig      config;
    private MqttPublisher publisher;
    private SensorSim sensor;
    private PayloadBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        config    = new AppConfig();
        publisher = new MqttPublisher(config);
        sensor    = new SensorSim(config);
        builder   = new PayloadBuilder();

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

    // ── Conexão ───────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Deve conectar ao broker com sucesso")
    void deveConectarAoBroker() {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }
        assertTrue(publisher.isConnected(), "isConnected() deve ser true após connect()");
    }

    // ── Publicação simples ────────────────────────────────────────────────────
    @Test
    @DisplayName("Deve publicar payload sem lançar exceção")
    void devePublicarPayload() {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        SensorData data = sensor.read();
        String     json = builder.build(data).orElseThrow();

        assertDoesNotThrow(() -> publisher.publish(json));
    }

    // ── Confirmação de entrega (PUBACK) ───────────────────────────────────────
    @Test
    @DisplayName("Deve receber PUBACK para QoS 1 em até 3 segundos")
    void deveReceberPuback() throws Exception {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        // Publica 5 mensagens e aguarda todas serem entregues
        int N = 5;
        for (int i = 0; i < N; i++) {
            String json = builder.build(sensor.read()).orElseThrow();
            publisher.publish(json);
        }

        // Aguarda o Paho processar os PUBACKs
        Thread.sleep(3000);

        assertEquals(N, publisher.getTotalPublished(), "Total publicadas incorreto");
        assertEquals(N, publisher.getTotalDelivered(),
                "PUBACK não recebido para todas as mensagens. " +
                        "Entregues: " + publisher.getTotalDelivered() + "/" + N);
    }

    // ── Subscriber de controle ────────────────────────────────────────────────
    @Test
    @DisplayName("Mensagem publicada deve chegar ao broker e ser recebida por subscriber")
    @Timeout(10)
    void mensagemDeveChegarAoBroker() throws Exception {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        CountDownLatch        latch       = new CountDownLatch(1);
        AtomicReference<String> received  = new AtomicReference<>();

        // Cria um subscriber separado para confirmar que a mensagem chegou
        String subscriberUrl = String.format("tcp://%s:%d",
                config.getBrokerHost(), config.getBrokerPort());
        MqttClient subscriber = new MqttClient(
                subscriberUrl, "test_subscriber_" + System.currentTimeMillis(),
                new MemoryPersistence());

        subscriber.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable c) {}
            @Override public void deliveryComplete(IMqttDeliveryToken t) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.set(new String(message.getPayload()));
                latch.countDown();
            }
        });

        subscriber.connect();
        subscriber.subscribe(config.getTopicTelemetry(), 1);

        // Publica via publisher
        SensorData data = sensor.read();
        String     json = builder.build(data).orElseThrow();
        publisher.publish(json);

        // Aguarda até 5s pelo recebimento
        boolean arrived = latch.await(5, TimeUnit.SECONDS);

        subscriber.disconnect();
        subscriber.close();

        assertTrue(arrived, "Mensagem não recebida pelo subscriber em 5s");
        assertNotNull(received.get(), "Payload recebido é null");
        assertTrue(received.get().contains("\"id\""),  "Campo 'id' ausente no payload recebido");
        assertTrue(received.get().contains("\"seq\""), "Campo 'seq' ausente no payload recebido");

        System.out.printf("%n  Payload recebido: %s%n", received.get());
    }

    // ── Status LWT ────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Deve publicar status online ao conectar")
    void devePublicarStatusOnline() throws Exception {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        CountDownLatch        latch    = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>();

        String url = String.format("tcp://%s:%d",
                config.getBrokerHost(), config.getBrokerPort());
        MqttClient sub = new MqttClient(url,
                "status_sub_" + System.currentTimeMillis(),
                new MemoryPersistence());

        sub.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable c) {}
            @Override public void deliveryComplete(IMqttDeliveryToken t) {}
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload());
                // O tópico de status é retained — chega imediatamente ao subscribe
                if (payload.contains("\"online\":true")) {
                    status.set(payload);
                    latch.countDown();
                }
            }
        });

        sub.connect();
        sub.subscribe(config.getTopicStatus(), 1);

        boolean arrived = latch.await(3, TimeUnit.SECONDS);
        sub.disconnect();
        sub.close();

        assertTrue(arrived, "Status online não recebido em 3s");
        System.out.printf("%n  Status recebido: %s%n", status.get());
    }

    // ── Métricas ──────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Métricas devem ser registradas após 10 publicações")
    void metricasDevemSerRegistradas() throws Exception {
        if (SKIP) { System.out.println("[SKIP] Broker não disponível."); return; }

        int N = 10;
        for (int i = 0; i < N; i++) {
            publisher.publish(builder.build(sensor.read()).orElseThrow());
            Thread.sleep(200);
        }

        // Aguarda PUBACKs
        Thread.sleep(2000);

        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  MÉTRICAS — MqttPublisher (10 msgs)");
        System.out.println("══════════════════════════════════════════");
        System.out.printf("  Publicadas   : %d%n",   publisher.getTotalPublished());
        System.out.printf("  Entregues    : %d%n",   publisher.getTotalDelivered());
        System.out.printf("  Taxa entrega : %.1f%%%n", publisher.getDeliveryRate());
        System.out.printf("  Latência avg : %.1f ms%n", publisher.getAverageLatency());
        System.out.println("══════════════════════════════════════════\n");

        assertEquals(N, publisher.getTotalPublished());
        assertTrue(publisher.getDeliveryRate() >= 90.0,
                "Taxa de entrega baixa: " + publisher.getDeliveryRate());
    }
}
