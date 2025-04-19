package me.firestone82.solaxautomation.service.solax.queue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

@Getter
@RequiredArgsConstructor
public class ModbusRequest<T> {
    private final Callable<T> task;
    private final CompletableFuture<T> future = new CompletableFuture<>();

    public void execute() {
        try {
            T result = task.call();
            future.complete(result);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }
}