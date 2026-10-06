package org.altice_works.turbotrust.core;

import org.altice_works.turbotrust.api.TurboTrustAPI;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * TurboExecutor(全コアのワーカー)と MainThreadBridge(メインスレッドへの結果受け渡し)をまとめた実体。
 * Bukkit に依存しない。
 */
public final class TurboRuntime implements TurboTrustAPI, AutoCloseable {

    public record Stats(TurboExecutor.Stats executor, int bridgePending,
                        long bridgeExecuted, long lastDrainMicros) {
    }

    private final TurboExecutor executor;
    private final MainThreadBridge bridge;
    private final long mainThreadBudgetNanos;
    private final Consumer<Throwable> errorHandler;

    public TurboRuntime(int parallelism, int minParallelSize, long mainThreadBudgetNanos,
                        Consumer<Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.mainThreadBudgetNanos = Math.max(0L, mainThreadBudgetNanos);
        this.executor = new TurboExecutor(parallelism, minParallelSize, errorHandler);
        this.bridge = new MainThreadBridge(errorHandler);
    }

    public TurboExecutor executor() {
        return executor;
    }

    /** メインスレッドから毎tick呼ぶ。時間予算内で、ワーカーから渡された結果を適用する。 */
    public int drainMainThread() {
        return bridge.drain(mainThreadBudgetNanos);
    }

    public Stats stats() {
        return new Stats(executor.stats(), bridge.pending(), bridge.executedTotal(), bridge.lastDrainMicros());
    }

    @Override
    public int parallelism() {
        return executor.parallelism();
    }

    @Override
    public void parallelFor(int startInclusive, int endExclusive, IntConsumer body) {
        executor.parallelFor(startInclusive, endExclusive, body);
    }

    @Override
    public void parallelForRange(int startInclusive, int endExclusive, int grain, RangeBody body) {
        executor.parallelForRange(startInclusive, endExclusive, grain, body);
    }

    @Override
    public <T, R> List<R> parallelMap(List<? extends T> input, Function<? super T, ? extends R> fn) {
        return executor.parallelMap(input, fn);
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<? extends T> task) {
        return executor.submit(task);
    }

    @Override
    public <T> void computeThenApply(Supplier<? extends T> compute, Consumer<? super T> applyOnMain) {
        computeThenApply(compute, applyOnMain, errorHandler);
    }

    @Override
    public <T> void computeThenApply(Supplier<? extends T> compute,
                                     Consumer<? super T> applyOnMain,
                                     Consumer<Throwable> onError) {
        Objects.requireNonNull(applyOnMain, "applyOnMain");
        Objects.requireNonNull(onError, "onError");
        executor.<T>submit(compute).whenComplete((value, error) -> {
            if (error != null) {
                bridge.post(() -> onError.accept(error));
            } else {
                bridge.post(() -> applyOnMain.accept(value));
            }
        });
    }

    @Override
    public void runOnMain(Runnable task) {
        bridge.post(task);
    }

    /** ワーカーを停止し、未適用の結果をメインスレッド(呼び出し元)で最後に実行する。 */
    @Override
    public void close() {
        executor.close();
        bridge.drain(Long.MAX_VALUE);
    }
}
