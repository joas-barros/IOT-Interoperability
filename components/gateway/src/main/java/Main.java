import coap.GatewayCoapServer;
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
                this::logMetrics,
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
