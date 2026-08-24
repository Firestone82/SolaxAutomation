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
import java.util.concurrent.atomic.AtomicInteger;

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
        inverter = new FakeInverter(responsesPerConnection);

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

    /**
     * Throwaway Modbus TCP server. Answers reads with the constant 42 and then closes the
     * connection after the configured number of responses.
     */
    private static final class FakeInverter implements AutoCloseable {

        private static final int VALUE = 42;

        private final ServerSocket serverSocket;
        private final int responsesPerConnection;
        private final AtomicInteger connections = new AtomicInteger();
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();
        private final Thread acceptor;

        private volatile boolean running = true;

        private FakeInverter(int responsesPerConnection) throws IOException {
            this.responsesPerConnection = responsesPerConnection;
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
