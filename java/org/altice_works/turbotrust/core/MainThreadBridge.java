package org.altice_works.turbotrust.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * ワーカー(任意のスレッド)からメインスレッドへ処理を渡すキュー。
 * メインスレッドが毎tick {@link #drain(long)} を呼び、時間予算内で実行する。
 */
public final class MainThreadBridge {

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final LongAdder executed = new LongAdder();
    private final Consumer<Throwable> errorHandler;
    private volatile long lastDrainNanos;

    public MainThreadBridge(Consumer<Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    /** どのスレッドからでも呼べる。 */
    public void post(Runnable task) {
        Objects.requireNonNull(task, "task");
        pending.incrementAndGet();
        queue.add(task);
    }

    /**
     * 時間予算(ナノ秒)を超えるまでキューのタスクを実行する。メインスレッド専用。
     * 予算を超えても最低1件は実行する(1件が長いと予算超過しうる)。残りは次回に持ち越す。
     *
     * @return 実行した件数
     */
    public int drain(long budgetNanos) {
        long start = System.nanoTime();
        int count = 0;
        Runnable task;
        while ((task = queue.poll()) != null) {
            pending.decrementAndGet();
            try {
                task.run();
            } catch (Throwable t) {
                errorHandler.accept(t);
            }
            executed.increment();
            count++;
            if (System.nanoTime() - start >= budgetNanos) {
                break;
            }
        }
        lastDrainNanos = System.nanoTime() - start;
        return count;
    }

    public int pending() {
        return pending.get();
    }

    public long executedTotal() {
        return executed.sum();
    }

    public long lastDrainMicros() {
        return lastDrainNanos / 1_000;
    }
}
