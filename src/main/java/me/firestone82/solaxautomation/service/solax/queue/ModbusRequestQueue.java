package me.firestone82.solaxautomation.service.solax.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * A Spring-managed queue for Modbus requests with built-in throttling.
 */
@Slf4j
@Component
public class ModbusRequestQueue {

    @Value("${solax.modbus.time.delay:1000}")
    private long delayMillis;

    private final BlockingQueue<ModbusRequest<?>> queue = new LinkedBlockingQueue<>();
    private ScheduledExecutorService executor;

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ModbusRequestQueue-Worker");
            t.setDaemon(true);
            return t;
        });

        executor.scheduleWithFixedDelay(this::processNext, 0, delayMillis, TimeUnit.MILLISECONDS);
        log.info("ModbusRequestQueue started with {}ms delay", delayMillis);
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();

            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("ModbusRequestQueue executor did not terminate within timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during ModbusRequestQueue shutdown", e);
            }
        }

        log.info("ModbusRequestQueue stopped");
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
