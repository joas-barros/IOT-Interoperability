package mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import config.AppConfig;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MqttPublisher implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final AppConfig config;
    private final ObjectMapper mapper;
    private MqttAsyncClient client;

    // ── Métricas thread-safe ──────────────────────────────────────────────────
    private final AtomicLong totalPublished   = new AtomicLong(0);
    private final AtomicLong totalDelivered   = new AtomicLong(0);
    private final AtomicLong totalDisconnects = new AtomicLong(0);
    private final AtomicLong latencySum       = new AtomicLong(0);
    private final AtomicLong latencyCount     = new AtomicLong(0);

    private final Map<Integer, Long> sendTimes = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────

    public MqttPublisher(AppConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
    }

    public void connect() throws MqttException {
        String brokerUrl = String.format("tcp://%s:%d",
                config.getBrokerHost(), config.getBrokerPort());

        // ClientId único: stationId + timestamp para evitar conflito
        String clientId = config.getStationId() + "_" + System.currentTimeMillis();

        // MemoryPersistence: mensagens QoS 1/2 persistidas em memória
        client = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());
        client.setCallback(this);

        MqttConnectOptions options = buildConnectOptions();

        log.info("[MQTT] Conectando ao broker: {} | clientId: {}", brokerUrl, clientId);

        IMqttToken connectToken = client.connect(options);
        connectToken.waitForCompletion(config.getConnectionTimeout() * 1000L);

        log.info("[MQTT] Conectado com sucesso!");

        // Publica mensagem de presença online
        publishStatus(true);
    }

    public void publish(String json) throws MqttException {
        if (!client.isConnected()) {
            log.warn("[MQTT] Tentativa de publicação sem conexão ativa. Ignorado.");
            return;
        }

        MqttMessage message = new MqttMessage(
                json.getBytes(StandardCharsets.UTF_8));
        message.setQos(config.getPublishQos());
        message.setRetained(config.isPublishRetained());

        long sendTime = System.currentTimeMillis();
        totalPublished.incrementAndGet();

        IMqttDeliveryToken token = client.publish(config.getTopicTelemetry(), message);

        // Registra tempo de envio para calcular latência no callback
        sendTimes.put(token.getMessageId(), sendTime);

        log.debug("[MQTT] Publicado | msgId={} | topic={} | {} bytes | QoS={}",
                token.getMessageId(),
                config.getTopicTelemetry(),
                json.length(),
                config.getPublishQos());
    }

    public void publishStatus(boolean online) {
        try {
            Map<String, Object> statusMap = Map.of(
                    "id",     config.getStationId(),
                    "online", online,
                    "ts",     Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
            );

            String statusJson = mapper.writeValueAsString(statusMap);

            MqttMessage msg = new MqttMessage(statusJson.getBytes(StandardCharsets.UTF_8));
            msg.setQos(1);
            msg.setRetained(true);

            IMqttDeliveryToken token = client.publish(config.getTopicStatus(), msg);
            token.waitForCompletion(3000);

            log.info("[MQTT] Status publicado: online={}", online);

        } catch (Exception e) {
            log.warn("[MQTT] Falha ao publicar status: {}", e.getMessage());
        }
    }

    /**
     * Desconecta graciosamente do broker.
     * Aguarda até 3s para mensagens pendentes serem entregues antes de desconectar.
     */
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                publishStatus(false);

                // Aguarda entregas pendentes antes de desconectar
                IMqttToken disconnectToken = client.disconnect(3000);
                disconnectToken.waitForCompletion(5000);

                log.info("[MQTT] Desconectado graciosamente.");
            }
        } catch (MqttException e) {
            log.warn("[MQTT] Erro ao desconectar: {}", e.getMessage());
            try { client.disconnectForcibly(); } catch (Exception ignored) {}
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    @Override
    public void connectionLost(Throwable cause) {
        totalDisconnects.incrementAndGet();
        log.warn("[MQTT] Conexão perdida! Causa: {} | Reconexão automática em andamento...",
                cause != null ? cause.getMessage() : "desconhecida");
    }

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        // A estação só publica — não assina tópicos.
        log.debug("[MQTT] Mensagem recebida inesperada no tópico: {}", topic);
    }

    /**
     * Chamado pelo thread interno do Paho quando o PUBACK é confirmado (QoS 1).
     * Calcula e acumula a latência de publicação.
     */
    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        Long sendTime = sendTimes.remove(token.getMessageId());

        if (sendTime != null) {
            totalDelivered.incrementAndGet();

            long latency = System.currentTimeMillis() - sendTime;
            latencySum.addAndGet(latency);
            latencyCount.incrementAndGet();

            log.debug("[MQTT] PUBACK | msgId={} | latência={} ms",
                    token.getMessageId(), latency);
        }
    }

    /**
     * Chamado quando a conexão é estabelecida (initial) ou reestabelecida
     * após queda (reconnect).
     */
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (reconnect) {
            log.info("[MQTT] Reconectado ao broker: {}", serverURI);
        } else {
            log.debug("[MQTT] connectComplete (conexão inicial): {}", serverURI);
        }
    }

    // ── Configuração da conexão ───────────────────────────────────────────────
    private MqttConnectOptions buildConnectOptions() throws MqttException {
        MqttConnectOptions opts = new MqttConnectOptions();

        opts.setConnectionTimeout(config.getConnectionTimeout());
        opts.setKeepAliveInterval(config.getKeepAlive());
        opts.setCleanSession(false);
        opts.setAutomaticReconnect(true);
        opts.setMaxReconnectDelay(30_000);

        if (!config.getBrokerUser().isEmpty()) {
            opts.setUserName(config.getBrokerUser());
            opts.setPassword(config.getBrokerPassword().toCharArray());
        }

        // Last Will Testament — broker publica isto se a conexão cair
        try {
            String willJson = mapper.writeValueAsString(Map.of(
                    "id",     config.getStationId(),
                    "online", false,
                    "ts",     Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
            ));

            opts.setWill(
                    config.getTopicStatus(),
                    willJson.getBytes(StandardCharsets.UTF_8),
                    1,
                    true
            );
            log.debug("[MQTT] LWT configurado: {}", config.getTopicStatus());
        } catch (Exception e) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
        }

        return opts;
    }

    // ── Métricas ──────────────────────────────────────────────────────────────

    public long getTotalPublished()   { return totalPublished.get(); }
    public long getTotalDelivered()   { return totalDelivered.get(); }
    public long getTotalDisconnects() { return totalDisconnects.get(); }

    public double getDeliveryRate() {
        long pub = totalPublished.get();
        return pub == 0 ? 0.0 : (totalDelivered.get() * 100.0) / pub;
    }

    public double getAverageLatency() {
        long count = latencyCount.get();
        return count == 0 ? 0.0 : (latencySum.get() * 1.0) / count;
    }

    public void printMetrics() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║       Métricas MQTT da Sessão            ║");
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ Publicadas   : {}", totalPublished.get());
        log.info("║ Entregues    : {}", totalDelivered.get());
        log.info("║ Taxa entrega : {}%",
                String.format("%.1f", getDeliveryRate()));
        log.info("║ Latência avg : {} ms",
                String.format("%.1f", getAverageLatency()));
        log.info("║ Desconexões  : {}", totalDisconnects.get());
        log.info("╚══════════════════════════════════════════╝");
    }
}
