import config.AppConfig;
import mqtt.MqttPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import payload.PayloadBuilder;
import sensor.SensorData;
import sensor.SensorSim;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Componentes
    private final AppConfig config;
    private final SensorSim sensorSim;
    private final PayloadBuilder payloadBuilder;
    private final MqttPublisher mqttPublisher;

    // Scheduler para publicação periódica
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "publish-thread");
                t.setDaemon(false);  // mantém a JVM viva enquanto o scheduler rodar
                return t;
            });

    // Contadores de diagnóstico
    private final AtomicLong cycleCount       = new AtomicLong(0);
    private final AtomicLong validationErrors = new AtomicLong(0);
    private final AtomicLong publishErrors    = new AtomicLong(0);
    private final long       startTime        = System.currentTimeMillis();

    public Main() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║    Estação IoT Estacionária — Iniciando  ║");
        log.info("╚══════════════════════════════════════════╝");

        config         = new AppConfig();
        sensorSim      = new SensorSim(config);
        payloadBuilder = new PayloadBuilder();
        mqttPublisher  = new MqttPublisher(config);
    }

    /**
     * Inicializa a conexão MQTT e inicia o loop de publicação.
     */
    public void start() throws Exception {

        // ── 1. Conecta ao broker ──
        log.info("[Main] Conectando ao broker MQTT...");
        mqttPublisher.connect();

        // ── 2. Registra shutdown hook ──
        registerShutdownHook();

        // ── 3. Agenda ciclo de publicação ──
        log.info("[Main] Agendando publicações a cada {} ms...",
                config.getPublishIntervalMs());

        scheduler.scheduleAtFixedRate(
                this::publishCycle,
                0,                               // sem delay inicial
                config.getPublishIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        log.info("[Main] Sistema operacional. Ctrl+C para encerrar.");

    }

    /**
     * Executa um ciclo de leitura → validação → publicação.
     * Chamado pelo scheduler a cada intervalo configurado.
     */
    private void publishCycle() {
        long cycle = cycleCount.incrementAndGet();

        try {
            // 1. Lê dados do sensor
            SensorData data = sensorSim.read();

            // 2. Monta e valida o payload
            Optional<String> payload = payloadBuilder.build(data);

            if (payload.isEmpty()) {
                validationErrors.incrementAndGet();
                log.warn("[Main] Ciclo {} — payload inválido, pulando publicação.", cycle);
                return;
            }

            // 3. Publica via MQTT
            mqttPublisher.publish(payload.get());

            // Log de status a cada 10 ciclos (não polui o log)
            if (cycle % 10 == 0) {
                log.info("[Main] Ciclo {} | publicadas={} | entregues={} | " +
                                "erros_valid={} | erros_pub={} | uptime={}s",
                        cycle,
                        mqttPublisher.getTotalPublished(),
                        mqttPublisher.getTotalDelivered(),
                        validationErrors.get(),
                        publishErrors.get(),
                        (System.currentTimeMillis() - startTime) / 1000);
            }

        } catch (Exception e) {
            publishErrors.incrementAndGet();
            log.error("[Main] Ciclo {} — erro na publicação: {}", cycle, e.getMessage());
        }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    log.info("[Main] Sinal de encerramento recebido. Desligando...");

                    // Para novos agendamentos, aguarda ciclo atual terminar
                    scheduler.shutdown();

                    try {
                        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                            scheduler.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        scheduler.shutdownNow();
                        Thread.currentThread().interrupt();
                    }

                    // Publica status offline e desconecta do broker
                    mqttPublisher.disconnect();

                    // Relatório final
                    printFinalReport();
                }, "shutdown-hook")
        );
    }

    private void printFinalReport() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;

        log.info("╔══════════════════════════════════════════╗");
        log.info("║         Relatório Final da Sessão        ║");
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ Uptime          : {} s",    uptime);
        log.info("║ Ciclos totais   : {}",       cycleCount.get());
        log.info("║ Erros validação : {}",       validationErrors.get());
        log.info("║ Erros publicação: {}",       publishErrors.get());
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ MQTT Publicadas : {}",       mqttPublisher.getTotalPublished());
        log.info("║ MQTT Entregues  : {}",       mqttPublisher.getTotalDelivered());
        log.info("║ Taxa entrega    : {}",  mqttPublisher.getDeliveryRate());
        log.info("║ Latência avg    : {} ms",mqttPublisher.getAverageLatency());
        log.info("║ Desconexões     : {}",       mqttPublisher.getTotalDisconnects());
        log.info("╚══════════════════════════════════════════╝");
    }

    static void main(String[] args) {
        try {
            new Main().start();
        } catch (Exception e) {
            LoggerFactory.getLogger(Main.class)
                    .error("[Main] Erro fatal na inicialização: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
