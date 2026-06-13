package forwarder;

import com.fasterxml.jackson.databind.ObjectMapper;
import config.AppConfig;
import model.NormalizedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class HttpForwarder {

    private static final Logger log = LoggerFactory.getLogger(HttpForwarder.class);

    private final AppConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    // Fila principal: dados aguardando envio
    private final LinkedBlockingQueue<NormalizedData> sendQueue;

    // Fila de retry: dados que falharam e aguardam reenvio
    private final LinkedBlockingQueue<RetryEntry> retryQueue;

    // Worker thread do sender
    private final ExecutorService senderExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "http-sender"));

    // Scheduler para processar a fila de retry periodicamente
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "http-retry"));

    // ── Métricas ──────────────────────────────────────────────────────────
    private final AtomicLong totalForwarded  = new AtomicLong(0);
    private final AtomicLong totalSuccess    = new AtomicLong(0);
    private final AtomicLong totalError      = new AtomicLong(0);
    private final AtomicLong totalRetried    = new AtomicLong(0);
    private final AtomicLong totalDiscarded  = new AtomicLong(0);
    private final AtomicLong latencySum      = new AtomicLong(0);
    private final AtomicLong latencyCount    = new AtomicLong(0);

    public HttpForwarder(AppConfig config) {
        this.config   = config;
        this.mapper   = new ObjectMapper();
        this.sendQueue  = new LinkedBlockingQueue<>(config.getRetryQueueSize());
        this.retryQueue = new LinkedBlockingQueue<>(config.getRetryQueueSize());

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
                .build();
    }

    /**
     * Inicia o worker de envio e o scheduler de retry.
     */
    public void start() {
        senderExecutor.submit(this::senderLoop);

        // Processa a fila de retry a cada (retryBackoffMs * 2)
        long retryInterval = config.getRetryBackoffMs() * 2;
        retryScheduler.scheduleAtFixedRate(
                this::processRetryQueue,
                retryInterval, retryInterval,
                TimeUnit.MILLISECONDS);

        log.info("[HTTP] Forwarder iniciado | destino: {}", config.getIngestUrl());
    }

    /**
     * Enfileira um dado para envio assíncrono.
     * Retorna imediatamente — não bloqueia o pipeline.
     */
    public void forward(NormalizedData data) {
        totalForwarded.incrementAndGet();
        if (!sendQueue.offer(data)) {
            // Fila cheia — descarta dado mais antigo para abrir espaço
            sendQueue.poll();
            totalDiscarded.incrementAndGet();
            log.warn("[HTTP] Fila cheia — dado descartado (seq={})", data.seq);
            sendQueue.offer(data);
        }
    }

    /**
     * Encerra o forwarder graciosamente.
     * Aguarda até 5s para o worker processar dados pendentes.
     */
    public void shutdown() {
        senderExecutor.shutdown();
        retryScheduler.shutdown();
        try {
            senderExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[HTTP] Forwarder encerrado.");
        printMetrics();
    }

    // ── Loop de envio ─────────────────────────────────────────────────────

    private void senderLoop() {
        log.debug("[HTTP] Worker de envio iniciado.");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                NormalizedData data = sendQueue.take();  // bloqueia até ter dado
                sendWithRetry(data, 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void sendWithRetry(NormalizedData data, int attempt) {
        try {
            String json = mapper.writeValueAsString(data);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getIngestUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-Gateway-Id", config.getGatewayId())
                    .header("X-Source-Type", data.sourceType)
                    .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            long sendTime = System.currentTimeMillis();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            long latency = System.currentTimeMillis() - sendTime;
            latencySum.addAndGet(latency);
            latencyCount.incrementAndGet();

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                totalSuccess.incrementAndGet();
                log.debug("[HTTP] OK | src={} seq={} | {}ms | status={}",
                        data.sourceId, data.seq, latency, response.statusCode());
            } else {
                handleHttpError(data, attempt, response.statusCode(),
                        response.body());
            }
        } catch (Exception e) {
            handleException(data, attempt, e);
        }
    }

    private void handleHttpError(NormalizedData data, int attempt,
                                 int status, String body) {
        totalError.incrementAndGet();
        log.warn("[HTTP] Erro {} | src={} seq={} | body={}",
                status, data.sourceId, data.seq,
                body.length() > 100 ? body.substring(0, 100) + "..." : body);

        if (attempt < config.getRetryMax()) {
            enqueueRetry(data, attempt + 1);
        } else {
            totalDiscarded.incrementAndGet();
            log.error("[HTTP] Descartado após {} tentativas | seq={}",
                    config.getRetryMax(), data.seq);
        }
    }

    private void handleException(NormalizedData data, int attempt, Exception e) {
        totalError.incrementAndGet();
        log.warn("[HTTP] Exceção | src={} seq={} | {}",
                data.sourceId, data.seq, e.getMessage());

        if (attempt < config.getRetryMax()) {
            enqueueRetry(data, attempt + 1);
        } else {
            totalDiscarded.incrementAndGet();
        }
    }

    private void enqueueRetry(NormalizedData data, int nextAttempt) {
        long retryAfter = System.currentTimeMillis() +
                config.getRetryBackoffMs() * nextAttempt;

        RetryEntry entry = new RetryEntry(data, nextAttempt, retryAfter);

        if(!retryQueue.offer(entry)) {
            retryQueue.poll(); // descarta o mais antigo
            totalDiscarded.incrementAndGet();
            retryQueue.offer(entry);
        }

        log.debug("[HTTP] Retry agendado | seq={} | tentativa={}", data.seq, nextAttempt);
    }

    private void processRetryQueue() {
        long now = System.currentTimeMillis();

        retryQueue.removeIf(entry -> {
            if (entry.retryAfter <= now) {
                totalRetried.incrementAndGet();
                sendWithRetry(entry.data, entry.attempt);
                return true;
            }
            return false;
        });

    }

    // ── Métricas ──────────────────────────────────────────────────────────

    public long   getTotalForwarded()  { return totalForwarded.get(); }
    public long   getTotalSuccess()    { return totalSuccess.get(); }
    public long   getTotalError()      { return totalError.get(); }
    public long   getTotalDiscarded()  { return totalDiscarded.get(); }

    public double getSuccessRate() {
        long fwd = totalForwarded.get();
        return fwd == 0 ? 0.0 : (totalSuccess.get() * 100.0) / fwd;
    }

    public double getAvgLatencyMs() {
        long count = latencyCount.get();
        return count == 0 ? 0.0 : (double) latencySum.get() / count;
    }

    public void printMetrics() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║       Métricas HTTP Forwarder            ║");
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ Enfileirados : {}", totalForwarded.get());
        log.info("║ Sucesso      : {}", totalSuccess.get());
        log.info("║ Erros        : {}", totalError.get());
        log.info("║ Retries      : {}", totalRetried.get());
        log.info("║ Descartados  : {}", totalDiscarded.get());
        log.info("║ Taxa sucesso : {}%",
                String.format("%.1f", getSuccessRate()));
        log.info("║ Lat. HTTP avg: {} ms",
                String.format("%.1f", getAvgLatencyMs()));
        log.info("╚══════════════════════════════════════════╝");
    }

    private record RetryEntry(NormalizedData data, int attempt, long retryAfter) {}
}
