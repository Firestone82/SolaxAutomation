package me.firestone82.solaxautomation.integration.solax.modbus.client;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.integration.solax.modbus.ModbusProperties;
import me.firestone82.solaxautomation.integration.solax.modbus.queue.ModbusRequest;
import me.firestone82.solaxautomation.integration.solax.modbus.queue.ModbusRequestQueue;
import me.firestone82.solaxautomation.integration.solax.modbus.register.ReadRegister;
import me.firestone82.solaxautomation.integration.solax.modbus.register.WriteRegister;
import net.solarnetwork.io.modbus.ModbusClient;
import net.solarnetwork.io.modbus.ModbusException;
import net.solarnetwork.io.modbus.netty.msg.RegistersModbusMessage;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.solarnetwork.io.modbus.tcp.netty.NettyTcpModbusClientConfig;
import net.solarnetwork.io.modbus.tcp.netty.TcpNettyModbusClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static net.solarnetwork.io.modbus.netty.msg.RegistersModbusMessage.*;
import java.util.Locale;

/**
 * Thin, guarded wrapper around the Netty Modbus TCP client.
 * <p>
 * Three protections wrap every request, all of them unchanged from the original
 * implementation because they exist to protect real hardware:
 * <ul>
 *   <li>requests are serialised and spaced out through {@link ModbusRequestQueue};</li>
 *   <li>the connection is dropped after an idle period and re-opened on demand;</li>
 *   <li>a run of failures, or an implausible number of writes, stops the application
 *       rather than letting it hammer the inverter.</li>
 * </ul>
 */
@Slf4j
@Getter
@Component
@ConditionalOnProperty(prefix = "solax.modbus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ModbusClientAdapter {

    private final ApplicationContext applicationContext;
    private final ModbusProperties properties;
    private final ModbusRequestQueue requestQueue;
    private final ModbusClient modbusClient;

    /**
     * Owned by this adapter rather than by the client.
     * <p>
     * Left to create its own, the client shuts the group down inside {@code stop()}, and
     * Netty's graceful shutdown sits out a two second quiet period every single time. Since
     * connections are recycled routinely - the inverter closes idle ones - that would put two
     * seconds in front of most reads. Owning the group makes {@code stop()} close the channel
     * and nothing else, which takes milliseconds.
     */
    private final EventLoopGroup eventLoopGroup;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final Deque<Long> writeTimestamps = new ConcurrentLinkedDeque<>();

    private volatile long lastActivityTime = System.currentTimeMillis();

    public ModbusClientAdapter(
            ModbusProperties properties,
            ModbusRequestQueue requestQueue,
            ApplicationContext applicationContext
    ) {
        this.properties = properties;
        this.requestQueue = requestQueue;
        this.applicationContext = applicationContext;

        NettyTcpModbusClientConfig config = new NettyTcpModbusClientConfig(properties.getHost(), properties.getPort());
        config.setAutoReconnect(false);

        // One thread is plenty: every request is serialised through ModbusRequestQueue anyway.
        this.eventLoopGroup = new NioEventLoopGroup(1);
        this.modbusClient = new TcpNettyModbusClient(config, eventLoopGroup, NioSocketChannel.class);
    }

    @PostConstruct
    void logConfiguration() {
        log.info("Modbus client targeting {}:{}", properties.getHost(), properties.getPort());
    }

    @PreDestroy
    void shutdown() {
        if (modbusClient != null && modbusClient.isStarted()) {
            if (disconnect()) {
                log.info("Disconnected from the inverter");
            } else {
                log.error("Failed to disconnect from the inverter");
            }
        }

        // The client never owned the group, so this is the only place it gets shut down.
        eventLoopGroup.shutdownGracefully();
    }

    /**
     * Closes an idle connection so the inverter does not keep a socket open for nothing.
     * <p>
     * This only tidies up between requests; {@link #ensureConnected} performs the same check
     * immediately before a request, which is what actually prevents sending into a socket the
     * inverter has already closed.
     */
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.SECONDS)
    void closeIdleConnection() {
        recycleIfStale();
    }

    // ------------------------------------------------------------------ connection

    public boolean isConnected() {
        return modbusClient != null && modbusClient.isConnected();
    }

    /**
     * Opens a connection, or confirms there already is one.
     * <p>
     * The subtlety is the state in between: when the inverter closes the socket the client is
     * <em>started but not connected</em>, and {@code start()} on an already-started client is a
     * no-op - it would hand back the dead channel and the next request would fail with
     * "connection is closed". Such a client has to be stopped before it can be started again.
     */
    public boolean connect() {
        if (modbusClient == null) {
            log.error("Modbus client is not initialized");
            return false;
        }

        if (modbusClient.isConnected()) {
            return true;
        }

        if (modbusClient.isStarted()) {
            log.debug("Client is started but the connection is gone; stopping it before reconnecting");
            disconnect();
        }

        log.debug("Connecting to {}:{}", properties.getHost(), properties.getPort());

        try {
            modbusClient.start().get();
            lastActivityTime = System.currentTimeMillis();
            consecutiveFailures.set(0);
            return true;
        } catch (ExecutionException e) {
            log.error("Failed to connect to the inverter: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.error("Interrupted while connecting to the inverter");
            Thread.currentThread().interrupt();
        }

        return false;
    }

    /**
     * Stops the client.
     * <p>
     * Keyed on {@code isStarted()} rather than {@code isConnected()} for the same reason as
     * {@link #connect()}: a client whose socket the inverter closed is still started, and
     * leaving it that way blocks the next {@code start()}.
     */
    public boolean disconnect() {
        if (modbusClient == null || !modbusClient.isStarted()) {
            return true;
        }

        try {
            modbusClient.stop().get();
            return true;
        } catch (ExecutionException e) {
            log.error("Failed to disconnect from the inverter: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.error("Interrupted while disconnecting from the inverter");
            Thread.currentThread().interrupt();
        }

        return false;
    }

    // ------------------------------------------------------------------ register access

    /** Reads a register, returning empty when the inverter did not answer. */
    public <T> Optional<T> read(ReadRegister<T> register, int unitId) {
        log.trace("Reading {} register '{}' at 0x{} ({} words)",
                register.getType(), register.getName(), hex(register.getAddress()), register.getCount());

        Callable<Optional<T>> task = () -> {
            try {
                return ensureConnected(() -> {
                    RegistersModbusMessage response = modbusClient.send(switch (register.getType()) {
                        case INPUT -> readInputsRequest(unitId, register.getAddress(), register.getCount());
                        case HOLDING -> readHoldingsRequest(unitId, register.getAddress(), register.getCount());
                    }).unwrap(RegistersModbusMessage.class);

                    return Optional.of(ModbusConvertUtil.convertResponse(response.dataCopy(), register.getTClass(), register.getCount()));
                });
            } catch (ModbusException e) {
                log.error("Failed to read '{}' (0x{}): {}", register.getName(), hex(register.getAddress()), e.getMessage());
                recordFailure();
                return Optional.empty();
            }
        };

        try {
            return requestQueue.submitAndWait(new ModbusRequest<>(task));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Read of '{}' was interrupted", register.getName());
        } catch (ExecutionException e) {
            log.error("Read of '{}' failed: {}", register.getName(), e.getCause().getMessage());
            recordFailure();
        }

        return Optional.empty();
    }

    /** Writes a register, returning false when the inverter rejected or ignored the write. */
    public <T> boolean write(WriteRegister<T> register, int unitId, T value) {
        recordWrite();

        log.debug("Writing '{}' at 0x{} = {}", register.getName(), hex(register.getAddress()), value);

        Callable<Boolean> task = () -> {
            try {
                return ensureConnected(() -> {
                    RegistersModbusMessage response = modbusClient.send(switch (value) {
                        case Integer v -> writeHoldingRequest(unitId, register.getAddress(), v);
                        case Boolean v -> writeHoldingRequest(unitId, register.getAddress(), v ? 1 : 0);
                        case Enum<?> v -> writeHoldingRequest(unitId, register.getAddress(), v.ordinal());
                        default -> writeHoldingsRequest(unitId, register.getAddress(),
                                ModbusConvertUtil.convertRequest(value, register.getCount()));
                    }).unwrap(RegistersModbusMessage.class);

                    return !response.isException();
                });
            } catch (ModbusException e) {
                log.error("Failed to write '{}' (0x{}): {}", register.getName(), hex(register.getAddress()), e.getMessage());
                recordFailure();
                return false;
            }
        };

        try {
            return requestQueue.submitAndWait(new ModbusRequest<>(task));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Write of '{}' was interrupted", register.getName());
        } catch (ExecutionException e) {
            log.error("Write of '{}' failed: {}", register.getName(), e.getCause().getMessage());
            recordFailure();
        }

        return false;
    }

    // ------------------------------------------------------------------ guards

    /**
     * Runs a request with a guaranteed live connection.
     * <p>
     * The inverter closes idle Modbus connections on its own, well before any timeout on this
     * side, and Netty does not always notice before the next write - so a request after a
     * pause can fail with "connection is closed" even though {@link #isConnected()} said yes.
     * Two things prevent that:
     * <ul>
     *   <li>a connection idle for longer than {@code solax.modbus.idle-timeout} is recycled
     *       before the request rather than after it fails;</li>
     *   <li>a request that still fails is retried once on a freshly opened connection.</li>
     * </ul>
     * A retry that also fails, or a connection that cannot be opened at all, throws - the
     * caller logs it and counts it towards {@code max-consecutive-failures}, which is what
     * decides whether the application gives up.
     */
    private <V> V ensureConnected(ModbusCallable<V> callable) {
        recycleIfStale();

        for (int attempt = 1; attempt <= 2; attempt++) {
            if (!isConnected() && !connect()) {
                throw new ModbusException("Cannot reach the inverter at "
                        + properties.getHost() + ":" + properties.getPort());
            }

            lastActivityTime = System.currentTimeMillis();

            try {
                V result = callable.call();
                consecutiveFailures.set(0);
                return result;
            } catch (ModbusException e) {
                if (attempt == 2) {
                    throw e;
                }

                log.debug("Modbus request failed ({}), reconnecting and retrying once", e.getMessage());
                disconnect();
            }
        }

        // Unreachable: the loop either returns or throws.
        throw new ModbusException("Modbus request did not complete");
    }

    /**
     * Drops a connection that has been sitting idle, so the next request opens a fresh one
     * instead of discovering that the inverter closed this one.
     */
    private void recycleIfStale() {
        if (modbusClient == null || !modbusClient.isStarted()) {
            return;
        }

        long idleFor = System.currentTimeMillis() - lastActivityTime;
        if (idleFor < properties.getIdleTimeout().toMillis()) {
            return;
        }

        log.debug("Recycling the Modbus connection after {} s of inactivity", idleFor / 1000);
        disconnect();
    }

    /**
     * Guards against a runaway loop wearing out the inverter's flash: writes are counted in a
     * sliding window and the application stops once the budget is exhausted.
     */
    private void recordWrite() {
        long now = System.currentTimeMillis();
        long cutoff = now - properties.getWriteWindow().toMillis();

        while (true) {
            Long oldest = writeTimestamps.peekFirst();
            if (oldest == null || oldest >= cutoff) {
                break;
            }

            writeTimestamps.removeFirst();
        }

        writeTimestamps.addLast(now);

        if (writeTimestamps.size() >= properties.getMaxWritesPerWindow()) {
            log.error("Write budget exhausted: {} writes within {} h",
                    writeTimestamps.size(), properties.getWriteWindow().toHours());
            shutdownApplication("write budget exhausted");
        }
    }

    /**
     * Counts a failed request. The counter is reset by any successful request, so this only
     * fires when the inverter is genuinely unreachable rather than after occasional blips.
     */
    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();

        if (failures >= properties.getMaxConsecutiveFailures()) {
            log.error("{} consecutive Modbus failures", failures);
            shutdownApplication("inverter is not responding");
        }
    }

    private void shutdownApplication(String reason) {
        log.error("Shutting down: {}", reason);

        try {
            SpringApplication.exit(applicationContext, () -> 1);
            Thread.sleep(5000);
            ((ConfigurableApplicationContext) applicationContext).close();
        } catch (Exception e) {
            System.exit(1);
        }
    }

    private static String hex(int address) {
        return String.format(Locale.ROOT, "%04X", address);
    }
}
