package me.firestone82.solaxautomation.integration.solax.modbus.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.solax.modbus.ModbusProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Serialises every Modbus request onto a single worker thread and spaces requests out by
 * {@code solax.modbus.request-delay}.
 * <p>
 * The inverter silently drops requests that arrive too quickly after each other, so this
 * queue is what makes concurrent callers (four modules plus the dashboard) safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "solax.modbus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ModbusRequestQueue {

    private final ModbusProperties properties;

    private final BlockingQueue<ModbusRequest<?>> queue = new LinkedBlockingQueue<>();
    private ScheduledExecutorService executor;

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ModbusRequestQueue-Worker");
            t.setDaemon(true);
            return t;
        });

        long delayMillis = properties.getRequestDelay().toMillis();
        executor.scheduleWithFixedDelay(this::processNext, 0, delayMillis, TimeUnit.MILLISECONDS);
        log.info("Modbus request queue started, {} ms between requests", delayMillis);
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();

            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Modbus request queue did not stop within the timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while stopping the Modbus request queue");
            }
        }

        log.info("Modbus request queue stopped");
    }

    private void processNext() {
        try {
            ModbusRequest<?> request = queue.poll();

            if (request != null) {
                request.execute();
            }
        } catch (Exception e) {
            log.error("Error processing Modbus request", e);
        }
    }

    /**
     * Submit a request and block until completion.
     *
     * @param request the Modbus request
     * @param <T>     response type
     * @return the result of the request
     */
    public <T> T submitAndWait(ModbusRequest<T> request) throws InterruptedException, ExecutionException {
        queue.put(request);
        return request.getFuture().get();
    }
}
