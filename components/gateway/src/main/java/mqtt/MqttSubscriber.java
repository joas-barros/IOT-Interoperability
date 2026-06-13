package mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.AppConfig;
import model.StationData;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pipeline.DataPipeline;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

public class MqttSubscriber implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscriber.class);

    private final AppConfig config;
    private final DataPipeline pipeline;
    private final ObjectMapper mapper;
    private MqttClient client;

    // Timestamps para calcular tempo de indisponibilidade da estação
    private volatile long stationOfflineSince = 0;

    // ── Métricas ──────────────────────────────────────────────────────────
    private final AtomicLong totalReceived     = new AtomicLong(0);
    private final AtomicLong totalDesErrors    = new AtomicLong(0);
    private final AtomicLong stationOfflines   = new AtomicLong(0);
    private final AtomicLong totalOfflineMs    = new AtomicLong(0);

    // ─────────────────────────────────────────────────────────────────────

    public MqttSubscriber(AppConfig config, DataPipeline pipeline) {
        this.config   = config;
        this.pipeline = pipeline;
        this.mapper   = new ObjectMapper();
    }

    /**
     * Conecta ao broker e assina os tópicos configurados.
     *
     * @throws MqttException se a conexão falhar
     */
    public void connect() throws MqttException {
        String brokerUrl = String.format("tcp://%s:%d",
                config.getBrokerHost(), config.getBrokerPort());

        String clientId = config.getGatewayId() + "_sub_" + System.currentTimeMillis();

        client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        client.setCallback(this);

        MqttConnectOptions opts = buildConnectOptions();

        log.info("[MQTT] Conectando ao broker: {} | clientId: {}", brokerUrl, clientId);
        client.connect(opts);

        // Assina os dois tópicos após conexão bem-sucedida
        subscribeTopics();

        log.info("[MQTT] Subscriber conectado e assinando tópicos.");
    }

    /**
     * Desconecta graciosamente do broker.
     */
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                log.info("[MQTT] Subscriber desconectado.");
            }
        } catch (MqttException e) {
            log.warn("[MQTT] Erro ao desconectar: {}", e.getMessage());
        }
        printMetrics();
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    // ── MqttCallbackExtended ──────────────────────────────────────────────

    /**
     * Chamado quando a conexão é estabelecida ou reestabelecida.
     * Re-assina os tópicos após reconexão — necessário porque
     * {@code cleanSession=false} pode não restaurar subscrições
     * dependendo da versão do broker.
     */
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (reconnect) {
            log.info("[MQTT] Reconectado ao broker: {}", serverURI);
            try {
                subscribeTopics();
            } catch (MqttException e) {
                log.error("[MQTT] Falha ao re-assinar após reconexão: {}", e.getMessage());
            }
        }
    }

    /**
     * Chamado quando uma mensagem chega em qualquer tópico assinado.
     * Processa rapidamente (só deserializa e enfileira) — não bloqueia o Paho.
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String gatewayTs = Instant.now()
                .truncatedTo(ChronoUnit.MILLIS)
                .toString();

        byte[] payload = message.getPayload();

        log.debug("[MQTT] Mensagem | tópico={} | {} bytes", topic, payload.length);

        // ── Tópico de telemetria ──
        if (topic.equals(config.getTopicTelemetry())) {
            handleTelemetry(payload, gatewayTs);

            // ── Tópico de status (inclui LWT) ──
        } else if (topic.equals(config.getTopicStatus())) {
            handleStatus(payload, gatewayTs);

        } else {
            log.debug("[MQTT] Tópico desconhecido: {}", topic);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // O subscriber não publica — callback obrigatório mas não utilizado.
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("[MQTT] Conexão perdida: {} | Reconexão automática em andamento...",
                cause != null ? cause.getMessage() : "causa desconhecida");
        // setAutomaticReconnect(true) cuida da reconexão
    }

    // ── Handlers por tópico ───────────────────────────────────────────────

    private void handleTelemetry(byte[] payload, String gatewayTs) {
        totalReceived.incrementAndGet();
        try {
            StationData station = mapper.readValue(payload, StationData.class);
            station.gatewayTs  = gatewayTs;

            log.debug("[MQTT] Telemetria | seq={} | id={} | tmp={} | pres={}",
                    station.seq, station.id, station.tempC, station.pressureHpa);

            // Enfileira no pipeline — retorna imediatamente
            pipeline.submitStation(station);

        } catch (Exception e) {
            totalDesErrors.incrementAndGet();
            log.error("[MQTT] Erro ao desserializar telemetria: {}", e.getMessage());
        }
    }

    private void handleStatus(byte[] payload, String gatewayTs) {
        try {
            JsonNode node   = mapper.readTree(payload);
            boolean  online = node.path("online").asBoolean(true);
            String   id     = node.path("id").asText("?");

            if (!online) {
                // Estação ficou offline (desconexão normal ou LWT)
                stationOfflines.incrementAndGet();
                stationOfflineSince = System.currentTimeMillis();
                log.warn("[MQTT] Estação OFFLINE | id={} | ts={}", id, gatewayTs);

            } else {
                // Estação voltou online
                if (stationOfflineSince > 0) {
                    long downtime = System.currentTimeMillis() - stationOfflineSince;
                    totalOfflineMs.addAndGet(downtime);
                    log.info("[MQTT] Estação ONLINE | id={} | downtime={}ms", id, downtime);
                    stationOfflineSince = 0;
                } else {
                    log.info("[MQTT] Estação ONLINE | id={}", id);
                }
            }

        } catch (Exception e) {
            log.warn("[MQTT] Erro ao processar status: {}", e.getMessage());
        }
    }

    // ── Configuração ──────────────────────────────────────────────────────

    private void subscribeTopics() throws MqttException {
        // Assina telemetria com QoS 1 — mesmo QoS da publicação da estação
        client.subscribe(config.getTopicTelemetry(), 1);
        log.info("[MQTT] Assinado: {} (QoS 1)", config.getTopicTelemetry());

        // Assina status com QoS 1 — inclui LWT e mensagens de presença
        client.subscribe(config.getTopicStatus(), 1);
        log.info("[MQTT] Assinado: {} (QoS 1)", config.getTopicStatus());
    }

    private MqttConnectOptions buildConnectOptions() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setConnectionTimeout(config.getConnectionTimeout());
        opts.setKeepAliveInterval(config.getKeepAlive());
        opts.setCleanSession(false);         // mantém sessão para QoS 1
        opts.setAutomaticReconnect(true);
        opts.setMaxReconnectDelay(30_000);

        if (!config.getBrokerUser().isEmpty()) {
            opts.setUserName(config.getBrokerUser());
            opts.setPassword(config.getBrokerPassword().toCharArray());
        }

        return opts;
    }

    // ── Métricas ──────────────────────────────────────────────────────────

    public long getTotalReceived()   { return totalReceived.get(); }
    public long getStationOfflines() { return stationOfflines.get(); }
    public long getTotalOfflineMs()  { return totalOfflineMs.get(); }

    public void printMetrics() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║       Métricas MQTT Subscriber           ║");
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ Mensagens recebidas: {}", totalReceived.get());
        log.info("║ Erros deserial.    : {}", totalDesErrors.get());
        log.info("║ Offline detectados : {}", stationOfflines.get());
        log.info("║ Tempo offline total: {} ms", totalOfflineMs.get());
        log.info("╚══════════════════════════════════════════╝");
    }
}
