import coap.GatewayCoapServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.AppConfig;
import forwarder.HttpForwarder;
import mqtt.MqttSubscriber;
import normalizer.DroneNormalizer;
import normalizer.StationNormalizer;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.elements.config.UdpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pipeline.DataPipeline;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private final AppConfig config;
    private final HttpForwarder forwarder;
    private final DataPipeline pipeline;
    private final GatewayCoapServer coapServer;
    private final MqttSubscriber mqttSubscriber;

    private final ScheduledExecutorService metricsScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "metrics-scheduler"));

    private final HttpClient httpMetricsClient;

    // ─────────────────────────────────────────────────────────────────────

    public Main() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║       Gateway IoT — Inicializando        ║");
        log.info("╚══════════════════════════════════════════╝");

        // ── 1. Configurações ──
        config = new AppConfig();

        // ── 2. Normalizadores ──
        DroneNormalizer   droneNorm   = new DroneNormalizer(config);
        StationNormalizer stationNorm = new StationNormalizer(config);

        // ── 3. Forwarder HTTP ──
        forwarder = new HttpForwarder(config);

        // ── 4. Pipeline ──
        pipeline = new DataPipeline(config, droneNorm, stationNorm, forwarder);

        // ── 5. Servidor CoAP ──
        coapServer = new GatewayCoapServer(config, pipeline);

        // ── 6. Subscriber MQTT ──
        mqttSubscriber = new MqttSubscriber(config, pipeline);

        // ── 7. HTTP Client para métricas ──
        httpMetricsClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public void start() throws Exception {

        // ── Pipeline e forwarder ──
        log.info("[Main] Iniciando pipeline...");
        pipeline.start();

        // ── CoAP ──
        log.info("[Main] Iniciando servidor CoAP...");
        coapServer.start();

        // ── MQTT ──
        log.info("[Main] Conectando ao broker MQTT...");
        mqttSubscriber.connect();

        // ── ShutdownHook ──
        registerShutdownHook();

        // ── Métricas periódicas ──
        metricsScheduler.scheduleAtFixedRate(
                () -> {
                    logMetrics();
                    publishMetrics();
                },
                config.getMetricsIntervalMs(),
                config.getMetricsIntervalMs(),
                TimeUnit.MILLISECONDS);

        log.info("╔══════════════════════════════════════════╗");
        log.info("║       Gateway IoT — Operacional          ║");
        log.info("║  CoAP: udp/{}                        ║",
                config.getCoapPort());
        log.info("║  MQTT: {}:{}                   ║",
                config.getBrokerHost(), config.getBrokerPort());
        log.info("║  HTTP: {}{}  ║",
                config.getDatacenterUrl(), config.getEndpointIngest());
        log.info("╚══════════════════════════════════════════╝");
    }

    private void publishMetrics() {
        try{
            // Agrega métricas de todos os componentes
            Map<String, Object> metrics = new LinkedHashMap<>();

            // Coap
            metrics.put("coap_received", coapServer.getTotalReceived());
            metrics.put("coap_received_cbor", coapServer.getReceivedCbor());
            metrics.put("coap_received_json", coapServer.getReceivedJson());
            metrics.put("coap_errors", coapServer.getDeserialErrors());

            // MQTT
            metrics.put("mqtt_received", mqttSubscriber.getTotalReceived());
            metrics.put("mqtt_offlines", mqttSubscriber.getStationOfflines());
            metrics.put("mqtt_offline_ms",     mqttSubscriber.getTotalOfflineMs());

            // Pipeline
            metrics.put("pipeline_received_drone",   pipeline.getReceivedDrone());
            metrics.put("pipeline_received_station", pipeline.getReceivedStation());
            metrics.put("pipeline_normalized",       pipeline.getNormalized());
            metrics.put("pipeline_validation_errors",pipeline.getValidationErrors());
            metrics.put("pipeline_queue_size",       pipeline.getQueueSize());

            // HTTP Forwarder
            metrics.put("http_forwarded",   forwarder.getTotalForwarded());
            metrics.put("http_success",     forwarder.getTotalSuccess());
            metrics.put("http_errors",      forwarder.getTotalError());
            metrics.put("http_discarded",   forwarder.getTotalDiscarded());
            metrics.put("http_avg_latency_ms",
                    String.format("%.1f", forwarder.getAvgLatencyMs()));
            metrics.put("http_success_rate",
                    String.format("%.1f", forwarder.getSuccessRate()));

            // Timestamp
            metrics.put("gateway_id", config.getGatewayId());
            metrics.put("collected_at",
                    java.time.Instant.now().toString());

            // Serializa e envia
            String json = new ObjectMapper()
                    .writeValueAsString(metrics);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(config.getMetricsUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

            httpMetricsClient.sendAsync(request,
                            HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() != 200) {
                            log.warn("[Metrics] Falha ao publicar métricas: status={}",
                                    resp.statusCode());
                        } else {
                            log.debug("[Metrics] Métricas publicadas ao datacenter.");
                        }
                    })
                    .exceptionally(e -> {
                        log.warn("[Metrics] Erro ao publicar métricas: {}", e.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            log.error("[Metrics] Erro ao agregar métricas: {}", e.getMessage());
        }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Main] Sinal de encerramento recebido. Desligando...");

            // Para métricas periódicas
            metricsScheduler.shutdown();

            // Encerra em ordem inversa da inicialização
            log.info("[Main] Encerrando MQTT subscriber...");
            mqttSubscriber.disconnect();

            log.info("[Main] Encerrando CoAP server...");
            coapServer.stop();

            log.info("[Main] Encerrando pipeline...");
            pipeline.shutdown();  // inclui forwarder.shutdown()

            // Relatório final
            logMetrics();
            log.info("[Main] Gateway encerrado.");

        }, "shutdown-hook"));
    }

    private void logMetrics() {
        log.info("── Métricas periódicas ─────────────────────");
        log.info("[Main] CoAP recebidos : {} (CBOR={} JSON={})",
                coapServer.getTotalReceived(),
                coapServer.getReceivedCbor(),
                coapServer.getReceivedJson());
        log.info("[Main] MQTT recebidos : {} | offlines={}",
                mqttSubscriber.getTotalReceived(),
                mqttSubscriber.getStationOfflines());
        log.info("[Main] Pipeline fila  : {} itens",
                pipeline.getQueueSize());
        log.info("[Main] HTTP sucesso   : {} | erros={}",
                forwarder.getTotalSuccess(),
                forwarder.getTotalError());
        log.info("────────────────────────────────────────────");
    }

    // ── Entry point ───────────────────────────────────────────────────────

    public static void main(String[] args) {
        try {
            CoapConfig.register();
            UdpConfig.register();

            new Main().start();
        } catch (Exception e) {
            LoggerFactory.getLogger(Main.class)
                    .error("[Main] Erro fatal na inicialização: {}",
                            e.getMessage(), e);
            System.exit(1);
        }
    }

}
