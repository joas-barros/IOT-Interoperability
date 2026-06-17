package pipeline;

import config.AppConfig;
import exceptions.ValidationException;
import forwarder.HttpForwarder;
import model.DroneData;
import model.NormalizedData;
import model.StationData;
import normalizer.DroneNormalizer;
import normalizer.StationNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DataPipeline — Orquestra o fluxo: dado bruto → normalização → encaminhamento.
 *
 * <h3>Arquitetura de threads</h3>
 * <pre>
 *   CoapServer thread(s)  ──┐
 *                           ├──► LinkedBlockingQueue ──► pipeline thread ──► HttpForwarder
 *   MqttSubscriber thread ──┘
 * </pre>
 *
 * <p>A {@link LinkedBlockingQueue} desacopla produtores (CoAP/MQTT) do
 * consumidor (pipeline). Isso é fundamental para o experimento: o servidor
 * CoAP responde ao ESP32 com ACK imediatamente após enfileirar o dado,
 * sem esperar o HTTP POST ao datacenter completar. Assim a latência CoAP
 * medida no ESP32 reflete apenas o transporte de rede, não o processamento.</p>
 *
 * <p>Um único thread de pipeline elimina race conditions nos normalizadores
 * e no forwarder — eles sempre são chamados sequencialmente.</p>
 */
public class DataPipeline {

    private static final Logger log = LoggerFactory.getLogger(DataPipeline.class);

    // Capacidade da fila interna
    private static final int QUEUE_CAPACITY = 256;

    private final AppConfig config;
    private final DroneNormalizer droneNormalizer;
    private final StationNormalizer stationNormalizer;
    private final HttpForwarder forwarder;

    // Fila de dados brutos (DroneData ou StationData)
    private final LinkedBlockingQueue<Object> queue =
            new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    // Thread única de processamento
    private final ExecutorService pipelineExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "pipeline-thread"));

    // ── Métricas ──────────────────────────────────────────────────────────
    private final AtomicLong receivedDrone    = new AtomicLong(0);
    private final AtomicLong receivedStation  = new AtomicLong(0);
    private final AtomicLong normalized       = new AtomicLong(0);
    private final AtomicLong validationErrors = new AtomicLong(0);
    private final AtomicLong processingErrors = new AtomicLong(0);
    private final AtomicLong queueDropped     = new AtomicLong(0);

    // ─────────────────────────────────────────────────────────────────────

    public DataPipeline(AppConfig config,
                        DroneNormalizer droneNormalizer,
                        StationNormalizer stationNormalizer,
                        HttpForwarder forwarder) {
        this.config            = config;
        this.droneNormalizer   = droneNormalizer;
        this.stationNormalizer = stationNormalizer;
        this.forwarder         = forwarder;
    }

    /**
     * Inicia o loop de processamento em thread dedicada.
     */
    public void start() {
        forwarder.start();
        pipelineExecutor.submit(this::processingLoop);
        log.info("[Pipeline] Iniciado | fila cap={}", QUEUE_CAPACITY);
    }

    /**
     * Encerra o pipeline graciosamente.
     */
    public void shutdown() {
        pipelineExecutor.shutdown();
        try {
            pipelineExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        forwarder.shutdown();
        log.info("[Pipeline] Encerrado.");
        printMetrics();
    }

    /**
     * Enfileira dados do drone para processamento.
     * Chamado pelo CoapServer — retorna imediatamente.
     */
    public void submitDrone(DroneData data) {
        receivedDrone.incrementAndGet();
        if (!queue.offer(data)) {
            queueDropped.incrementAndGet();
            log.warn("[Pipeline] Fila cheia — DroneData seq={} descartado", data.seq);
        }
    }

    /**
     * Enfileira dados da estação para processamento.
     * Chamado pelo MqttSubscriber — retorna imediatamente.
     */
    public void submitStation(StationData data) {
        receivedStation.incrementAndGet();
        if (!queue.offer(data)) {
            queueDropped.incrementAndGet();
            log.warn("[Pipeline] Fila cheia — StationData seq={} descartado", data.seq);
        }
    }

    // ── Loop de processamento ─────────────────────────────────────────────

    private void processingLoop() {
        log.debug("[Pipeline] Loop de processamento iniciado.");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Object raw = queue.take();  // bloqueia até ter dado
                process(raw);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                processingErrors.incrementAndGet();
                log.error("[Pipeline] Erro inesperado no processamento: {}",
                        e.getMessage(), e);
            }
        }
    }

    private void process(Object raw) {
        try {
            NormalizedData nd;

            if (raw instanceof DroneData drone) {
                nd = droneNormalizer.normalize(drone);
                log.debug("[Pipeline] Drone normalizado | seq={} phase={} fmt={}",
                        nd.seq, nd.flightPhase, nd.payloadFormat);

            } else if (raw instanceof StationData station) {
                nd = stationNormalizer.normalize(station);
                log.debug("[Pipeline] Estação normalizada | seq={}",
                        nd.seq);

            } else {
                log.warn("[Pipeline] Tipo desconhecido na fila: {}",
                        raw.getClass().getSimpleName());
                return;
            }

            normalized.incrementAndGet();
            forwarder.forward(nd);

        } catch (ValidationException e) {
            validationErrors.incrementAndGet();
            log.warn("[Pipeline] Validação falhou: {}", e.getMessage());
        }
    }

    // ── Métricas ──────────────────────────────────────────────────────────

    public long getReceivedDrone()    { return receivedDrone.get(); }
    public long getReceivedStation()  { return receivedStation.get(); }
    public long getNormalized()       { return normalized.get(); }
    public long getValidationErrors() { return validationErrors.get(); }
    public int  getQueueSize()        { return queue.size(); }

    public void printMetrics() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║       Métricas DataPipeline              ║");
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ Drone recebidos   : {}", receivedDrone.get());
        log.info("║ Estação recebidos : {}", receivedStation.get());
        log.info("║ Normalizados      : {}", normalized.get());
        log.info("║ Erros validação   : {}", validationErrors.get());
        log.info("║ Erros proc.       : {}", processingErrors.get());
        log.info("║ Descartados fila  : {}", queueDropped.get());
        log.info("║ Fila atual        : {}", queue.size());
        log.info("╚══════════════════════════════════════════╝");
    }
}
