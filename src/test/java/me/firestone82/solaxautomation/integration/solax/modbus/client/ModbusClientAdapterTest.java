package me.firestone82.solaxautomation.integration.solax.modbus.client;

import me.firestone82.solaxautomation.integration.solax.modbus.ModbusProperties;
import me.firestone82.solaxautomation.integration.solax.modbus.queue.ModbusRequestQueue;
import me.firestone82.solaxautomation.integration.solax.modbus.register.ReadRegister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The inverter closes idle Modbus connections on its own. That leaves the client
 * <em>started but not connected</em> - a state in which {@code start()} is a no-op and every
 * further request fails with "connection is closed" until the application is restarted.
 * <p>
 * These tests stand a throwaway Modbus TCP server in front of the client and make it behave
 * the same way, so the recovery cannot silently regress.
 */
class ModbusClientAdapterTest {

    private FakeInverter inverter;
    private ModbusRequestQueue queue;
    private ModbusClientAdapter client;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.disconnect();
        }

        if (queue != null) {
            queue.shutdown();
        }

        if (inverter != null) {
            inverter.close();
        }
    }

    /**
     * Wires a client to a server that serves {@code responsesPerConnection} requests and then
     * hangs up, exactly as an idle-timeout on the inverter's gateway does.
     */
    private void start(int responsesPerConnection, Duration idleTimeout) throws IOException {
        start(responsesPerConnection, idleTimeout, Duration.ZERO);
    }

    /** Same, with the inverter taking {@code responseDelay} to answer each request. */
    private void start(int responsesPerConnection, Duration idleTimeout, Duration responseDelay) throws IOException {
        inverter = new FakeInverter(responsesPerConnection, responseDelay);

        ModbusProperties properties = new ModbusProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(inverter.getPort());
        properties.setUnitId(1);
        properties.setRequestDelay(Duration.ofMillis(50));
        properties.setIdleTimeout(idleTimeout);
        // High enough that a test never trips the shutdown guard.
        properties.setMaxConsecutiveFailures(100);

        queue = new ModbusRequestQueue(properties);
        queue.start();

        client = new ModbusClientAdapter(properties, queue, refusingContext());
    }

    /**
     * An ApplicationContext that fails the test if anything touches it - the adapter only ever
     * uses it to shut the application down, which none of these scenarios should do.
     */
    private static ApplicationContext refusingContext() {
        return (ApplicationContext) Proxy.newProxyInstance(
                ModbusClientAdapterTest.class.getClassLoader(),
                new Class<?>[]{ApplicationContext.class},
                (proxy, method, args) -> {
                    throw new AssertionError("The adapter tried to shut the application down: " + method.getName());
                });
    }

    @Test
    @DisplayName("recycles a connection the inverter closed while it was idle")
    void reconnectsAfterIdleClose() throws Exception {
        start(1, Duration.ofMillis(300));

        assertEquals(Optional.of(42), client.read(ReadRegister.BATTERY_CAPACITY, 1));

        // Long enough that the connection counts as stale and is recycled before the next read.
        Thread.sleep(600);

        assertEquals(Optional.of(42), client.read(ReadRegister.BATTERY_CAPACITY, 1),
                "a read after an idle close must reconnect instead of failing");
        assertEquals(2, inverter.getConnectionCount(), "the client should have opened a second connection");
    }

    @Test
    @DisplayName("retries once when the inverter closed the connection without an idle gap")
    void retriesAfterUnexpectedClose() throws Exception {
        // Idle timeout far longer than the pause, so recycling cannot be what saves this.
        start(1, Duration.ofMinutes(10));

        assertEquals(Optional.of(42), client.read(ReadRegister.BATTERY_CAPACITY, 1));
        assertEquals(Optional.of(42), client.read(ReadRegister.BATTERY_CAPACITY, 1),
                "a closed connection must be retried on a fresh one");
        assertEquals(2, inverter.getConnectionCount());
    }

    @Test
    @DisplayName("keeps serving reads for as long as the inverter keeps hanging up")
    void survivesRepeatedCloses() throws Exception {
        start(1, Duration.ofMinutes(10));

        for (int i = 0; i < 5; i++) {
            assertEquals(Optional.of(42), client.read(ReadRegister.BATTERY_CAPACITY, 1), "read " + (i + 1));
        }

        assertEquals(5, inverter.getConnectionCount());
    }

    @Test
    @DisplayName("reports a failure rather than stopping the application when the inverter is gone")
    void degradesWhenInverterUnreachable() throws Exception {
        start(1, Duration.ofMinutes(10));
        inverter.close();

        // refusingContext() turns any shutdown attempt into a test failure.
        assertTrue(client.read(ReadRegister.BATTERY_CAPACITY, 1).isEmpty());
        assertTrue(client.read(ReadRegister.BATTERY_CAPACITY, 1).isEmpty());
    }

    @Test
    @DisplayName("leaves a connection alone while a request is using it")
    void idleSweepSkipsRequestInFlight() throws Exception {
        // The connection counts as stale almost immediately, so every sweep would recycle it if
        // it were allowed to; the inverter takes long enough to answer to sweep it mid-request.
        start(10, Duration.ofMillis(50), Duration.ofMillis(500));

        AtomicReference<Optional<Integer>> result = new AtomicReference<>();
        AtomicReference<Throwable> escaped = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            try {
                result.set(client.read(ReadRegister.BATTERY_CAPACITY, 1));
            } catch (Throwable t) {
                escaped.set(t);
            }
        }, "reader");

        reader.start();
        inverter.awaitRequestInFlight();

        // Long enough that the sweep considers the connection stale, short enough that the
        // inverter has not answered yet.
        Thread.sleep(150);

        // Exactly what the scheduler does every fifteen seconds, only while the read is waiting
        // for its answer. Stopping the client here cancels the request the module is blocked on.
        for (int sweep = 0; sweep < 5; sweep++) {
            client.closeIdleConnection();
        }

        reader.join(10_000);

        assertNull(escaped.get(), "the idle sweep must not throw anything at the caller");
        assertEquals(Optional.of(42), result.get(), "the read must survive the idle sweep");
        assertEquals(1, inverter.getConnectionCount(),
                "the sweep tore down the connection mid-request and forced a reconnect");
    }

    @Test
    @DisplayName("survives the idle sweep firing throughout a run of reads")
    void idleSweepDoesNotCancelReads() throws Exception {
        // Zero idle timeout: a sweep recycles the connection whenever it manages to get in,
        // including in the window where the client is started but the socket is not up yet -
        // which is where stopping it cancels the connect and throws CancellationException at
        // whoever asked.
        start(10, Duration.ZERO);

        AtomicInteger sweeps = new AtomicInteger();
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread sweeper = new Thread(() -> {
            while (done.getCount() > 0) {
                try {
                    client.closeIdleConnection();
                    sweeps.incrementAndGet();
                } catch (Throwable t) {
                    escaped.set(t);
                    return;
                }
            }
        }, "idle-sweeper");

        sweeper.setDaemon(true);
        sweeper.start();

        try {
            for (int i = 0; i < 20; i++) {
                assertEquals(Optional.of(42), client.read(ReadRegister.BATTERY_CAPACITY, 1),
                        "read " + (i + 1) + " was cancelled by the idle sweep");
            }
        } finally {
            done.countDown();
            sweeper.join(5_000);
        }

        assertNull(escaped.get(), "the idle sweep itself must not throw");
        assertTrue(sweeps.get() > 0, "the sweeper never ran");
    }

    /**
     * Throwaway Modbus TCP server. Answers reads with the constant 42 and then closes the
     * connection after the configured number of responses.
     */
    private static final class FakeInverter implements AutoCloseable {

        private static final int VALUE = 42;

        private final ServerSocket serverSocket;
        private final int responsesPerConnection;
        private final Duration responseDelay;
        private final AtomicInteger connections = new AtomicInteger();
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();
        private final Thread acceptor;

        /** Opened once a request has arrived and is waiting out {@link #responseDelay}. */
        private final CountDownLatch serving = new CountDownLatch(1);

        private volatile boolean running = true;

        private FakeInverter(int responsesPerConnection, Duration responseDelay) throws IOException {
            this.responsesPerConnection = responsesPerConnection;
            this.responseDelay = responseDelay;
            this.serverSocket = new ServerSocket(0);

            this.acceptor = new Thread(this::acceptLoop, "fake-inverter");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        private int getPort() {
            return serverSocket.getLocalPort();
        }

        private int getConnectionCount() {
            return connections.get();
        }

        /** Blocks until a request is in flight, i.e. received but not yet answered. */
        private void awaitRequestInFlight() throws InterruptedException {
            assertTrue(serving.await(5, TimeUnit.SECONDS), "the inverter never received a request");
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    accepted.add(socket);
                    connections.incrementAndGet();

                    Thread worker = new Thread(() -> serve(socket), "fake-inverter-connection");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (socket;
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 OutputStream out = socket.getOutputStream()) {

                for (int served = 0; served < responsesPerConnection; served++) {
                    // MBAP header (7 bytes) plus function code, address and register count.
                    byte[] request = new byte[12];
                    in.readFully(request);

                    int transaction = ((request[0] & 0xFF) << 8) | (request[1] & 0xFF);
                    int unitId = request[6] & 0xFF;
                    int function = request[7] & 0xFF;
                    int count = ((request[10] & 0xFF) << 8) | (request[11] & 0xFF);

                    if (!responseDelay.isZero()) {
                        serving.countDown();

                        try {
                            Thread.sleep(responseDelay.toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    byte[] response = new byte[9 + count * 2];
                    response[0] = (byte) (transaction >> 8);
                    response[1] = (byte) transaction;
                    response[2] = 0;
                    response[3] = 0;

                    int length = 3 + count * 2;
                    response[4] = (byte) (length >> 8);
                    response[5] = (byte) length;
                    response[6] = (byte) unitId;
                    response[7] = (byte) function;
                    response[8] = (byte) (count * 2);

                    for (int register = 0; register < count; register++) {
                        response[9 + register * 2] = (byte) (VALUE >> 8);
                        response[10 + register * 2] = (byte) VALUE;
                    }

                    out.write(response);
                    out.flush();
                }
            } catch (IOException e) {
                // The client hung up, or the test finished; nothing to do.
            }
        }

        @Override
        public void close() throws IOException {
            running = false;
            accepted.forEach(socket -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // already gone
                }
            });

            serverSocket.close();
        }
    }
}
