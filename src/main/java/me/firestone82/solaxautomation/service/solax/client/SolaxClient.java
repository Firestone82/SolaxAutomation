package me.firestone82.solaxautomation.service.solax.client;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.firestone82.solaxautomation.service.solax.queue.ModbusRequest;
import me.firestone82.solaxautomation.service.solax.queue.ModbusRequestQueue;
import me.firestone82.solaxautomation.service.solax.register.ReadRegister;
import me.firestone82.solaxautomation.service.solax.register.WriteRegister;
import net.solarnetwork.io.modbus.ModbusClient;
import net.solarnetwork.io.modbus.ModbusException;
import net.solarnetwork.io.modbus.ModbusMessage;
import net.solarnetwork.io.modbus.netty.msg.RegistersModbusMessage;
import net.solarnetwork.io.modbus.tcp.netty.NettyTcpModbusClientConfig;
import net.solarnetwork.io.modbus.tcp.netty.TcpNettyModbusClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static net.solarnetwork.io.modbus.netty.msg.RegistersModbusMessage.*;

@Slf4j
@Getter
@Component
public class SolaxClient {

    private final ModbusClient modbusClient;
    private final ModbusRequestQueue requestQueue;
    private long lastActivityTime;

    public SolaxClient(
            @Value("${solax.modbus.host}") String hostName,
            @Value("${solax.modbus.port}") int hostPort,
            ModbusRequestQueue requestQueue
    ) {
        log.info("Initializing Solax client with host: {} and port: {}", hostName, hostPort);

        NettyTcpModbusClientConfig config = new NettyTcpModbusClientConfig(hostName, hostPort);
        config.setAutoReconnect(false);
        modbusClient = new TcpNettyModbusClient(config);

        this.requestQueue = requestQueue;

        log.info("Solax client initialized successfully");
    }

    @PreDestroy
    private void destroy() {
        if (!isConnected()) {
            log.info("Solax client is not connected, no need to disconnect");
            return;
        }

        if (disconnect()) {
            log.info("Successfully disconnected from Solax inverter");
        } else {
            log.error("Failed to disconnect from Solax inverter");
        }
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    private void timeout() {
        if (!isConnected()) {
            return;
        }

        if (lastActivityTime + 60 * 1000 < System.currentTimeMillis()) {
            if (disconnect()) {
                log.info("Disconnected from Solax inverter due to inactivity");
            } else {
                log.error("Failed to disconnect from Solax inverter due to inactivity");
            }
        }
    }

    public boolean isConnected() {
        if (modbusClient != null) {
            return modbusClient.isConnected();
        }

        return false;
    }

    public boolean connect() {
        log.trace("Attempting to connect to Solax inverter");

        if (modbusClient == null) {
            log.error("Cannot connect to modbus server, client is null!");
            return false;
        }

        if (modbusClient.isConnected()) {
            return true;
        }

        try {
            modbusClient.start().get();
            lastActivityTime = System.currentTimeMillis();
            return true;
        } catch (ExecutionException e) {
            log.error("Failed to connect to Solax inverter", e);
        } catch (InterruptedException e) {
            log.error("Thread interrupted while connecting to Solax inverter", e);
            Thread.currentThread().interrupt();
        }

        return false;
    }

    public boolean disconnect() {
        log.trace("Attempting to disconnect from Solax inverter");

        if (modbusClient == null) {
            log.error("Cannot disconnect from modbus server, client is null!");
            return false;
        }

        if (!modbusClient.isConnected()) {
            return true;
        }

        try {
            modbusClient.stop().get();
            return true;
        } catch (ExecutionException e) {
            log.error("Failed to disconnect from Solax inverter", e);
        } catch (InterruptedException e) {
            log.error("Thread interrupted while disconnecting from Solax inverter", e);
            Thread.currentThread().interrupt();
        }

        return false;
    }

    public <T> Optional<T> read(ReadRegister<T> register, int unitId) {
        String registerAddress = String.format("%4s", Integer.toHexString(register.getAddress())).replace(' ', '0');
        log.trace("Reading from register '{}' at 0x{} with length {}", register.getName(), registerAddress, register.getCount());

        ModbusMessage req = switch (register.getType()) {
            case INPUT -> readInputsRequest(unitId, register.getAddress(), register.getCount());
            case HOLDING -> readHoldingsRequest(unitId, register.getAddress(), register.getCount());
        };

        Callable<Optional<T>> task = () -> {
            try {
                return ensureConnected(modbus -> {
                    RegistersModbusMessage res = modbusClient.send(req).unwrap(RegistersModbusMessage.class);
                    return Optional.of(ModbusConvertUtil.convertResponse(res.dataCopy(), register.getTClass(), register.getCount()));
                });
            } catch (ModbusException e) {
                log.error("Failed reading {} @{} count {}: {}", register.getName(), register.getAddress(), register.getCount(), e.getMessage());
                return Optional.empty();
            }
        };

        try {
            return requestQueue.submitAndWait(new ModbusRequest<>(task));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Read interrupted for {}", register.getName());
        } catch (ExecutionException ee) {
            log.error("Read error for {}: {}", register.getName(), ee.getCause().getMessage());
        }

        return Optional.empty();
    }

    public <T> boolean write(WriteRegister<T> register, int unitId, T value) {
        String registerAddress = String.format("%4s", Integer.toHexString(register.getAddress())).replace(' ', '0');
        log.trace("Writing to register '{}' at 0x{} with length {}", register.getName(), registerAddress, register.getCount());

        ModbusMessage req = switch (value) {
            case Integer v -> writeHoldingRequest(unitId, register.getAddress(), v);
            case Boolean v -> writeHoldingRequest(unitId, register.getAddress(), v ? 1 : 0);
            case Enum<?> v -> writeHoldingRequest(unitId, register.getAddress(), v.ordinal());
            default -> {
                short[] values = ModbusConvertUtil.convertRequest(value, register.getCount());
                yield writeHoldingsRequest(unitId, register.getAddress(), values);
            }
        };

        Callable<Boolean> task = () -> {
            try {
                return ensureConnected(modbus -> {
                    RegistersModbusMessage res = modbusClient.send(req).unwrap(RegistersModbusMessage.class);
                    return !res.isException();
                });
            } catch (ModbusException e) {
                log.error("Failed to write {} (addr {}): {}", register.getName(), register.getAddress(), e.getMessage());
                return false;
            }
        };

        try {
            return requestQueue.submitAndWait(new ModbusRequest<>(task));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Write interrupted for {}", register.getName());
        } catch (ExecutionException ee) {
            log.error("Error executing write for {}: {}", register.getName(), ee.getCause().getMessage());
        }

        return false;
    }

    private <V> V ensureConnected(SolaxCallable<V> callable) {
        if (!isConnected() && !connect()) {
            log.error("Unable to provide connection to Solax inverter, can't connect to modbus server");
            System.exit(1);
        }

        lastActivityTime = System.currentTimeMillis();
        return callable.call(modbusClient);
    }
}
