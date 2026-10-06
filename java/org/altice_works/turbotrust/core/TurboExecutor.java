package org.altice_works.turbotrust.core;

import org.altice_works.turbotrust.api.TurboTrustAPI.RangeBody;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * 全コアを使うワークスティーリング型のワーカープール。
 * Bukkit に依存しないので単体テストできる。
 */
public final class TurboExecutor implements AutoCloseable {

    /** 自動grain時、1スレッドあたりこの個数程度のタスクに分割する(負荷の偏り対策)。 */
    private static final int TASKS_PER_THREAD = 8;

    private final ForkJoinPool pool;
    private final int parallelism;
    private final int minParallelSize;

    private final LongAdder submitted = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder parallelCalls = new LongAdder();
    private final LongAdder inlineCalls = new LongAdder();

    public record Stats(int parallelism, int poolSize, int activeThreads, int queuedSubmissions,
                        long queuedTasks, long steals, long submitted, long completed,
                        long parallelCalls, long inlineCalls) {
    }

    public TurboExecutor(int parallelism, int minParallelSize, Consumer<Throwable> errorHandler) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1: " + parallelism);
        }
        Objects.requireNonNull(errorHandler, "errorHandler");
        this.parallelism = parallelism;
        this.minParallelSize = Math.max(1, minParallelSize);
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ForkJoinPool(
                parallelism,
                p -> new Worker(p, "TurboTrust-Worker-" + seq.incrementAndGet()),
                (thread, error) -> errorHandler.accept(error),
                false);
    }

    public int parallelism() {
        return parallelism;
    }

    /** 各インデックスに対して body を並列実行する(ブロッキング)。 */
    public void parallelFor(int startInclusive, int endExclusive, IntConsumer body) {
        Objects.requireNonNull(body, "body");
        parallelForRange(startInclusive, endExclusive, 0, (lo, hi) -> {
            for (int i = lo; i < hi; i++) {
                body.accept(i);
            }
        });
    }

    /**
     * 範囲を分割して並列実行する(ブロッキング)。
     * <ul>
     *   <li>grain &gt; 0: 1タスクの最大要素数。範囲が grain 以下なら並列化しない。</li>
     *   <li>grain &lt;= 0: 自動。範囲が min-parallel-size 未満なら並列化しない。</li>
     * </ul>
     * body 内の例外は呼び出し元に再スローされる。
     */
    public void parallelForRange(int startInclusive, int endExclusive, int grain, RangeBody body) {
        Objects.requireNonNull(body, "body");
        int n = endExclusive - startInclusive;
        if (n <= 0) {
            return;
        }
        int g;
        boolean inline;
        if (grain > 0) {
            g = grain;
            inline = parallelism == 1 || n <= g;
        } else {
            g = Math.max(1, n / (parallelism * TASKS_PER_THREAD));
            inline = parallelism == 1 || n < minParallelSize;
        }
        if (inline) {
            inlineCalls.increment();
            body.accept(startInclusive, endExclusive);
            return;
        }
        parallelCalls.increment();
        RangeAction action = new RangeAction(startInclusive, endExclusive, g, body);
        if (Thread.currentThread() instanceof Worker w && w.getPool() == pool) {
            action.invoke(); // ワーカー内からの入れ子呼び出し: そのまま分割して協調実行(デッドロックしない)
        } else {
            pool.invoke(action);
        }
    }

    /** 各要素に fn を並列適用し、順序を保った結果を返す(ブロッキング)。 */
    @SuppressWarnings("unchecked")
    public <T, R> List<R> parallelMap(List<? extends T> input, Function<? super T, ? extends R> fn) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(fn, "fn");
        Object[] src = input.toArray();
        Object[] out = new Object[src.length];
        parallelForRange(0, src.length, 0, (lo, hi) -> {
            for (int i = lo; i < hi; i++) {
                out[i] = fn.apply((T) src[i]);
            }
        });
        return (List<R>) Collections.unmodifiableList(Arrays.asList(out));
    }

    /** 非同期実行。例外は返した Future に格納される。 */
    public <T> CompletableFuture<T> submit(Supplier<? extends T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        pool.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                completed.increment();
            }
        });
        submitted.increment();
        return future;
    }

    public Stats stats() {
        return new Stats(
                parallelism,
                pool.getPoolSize(),
                pool.getActiveThreadCount(),
                pool.getQueuedSubmissionCount(),
                pool.getQueuedTaskCount(),
                pool.getStealCount(),
                submitted.sum(),
                completed.sum(),
                parallelCalls.sum(),
                inlineCalls.sum());
    }

    /** 新規受付を止め、実行中のタスクの完了を待つ(最大5秒、その後強制終了)。 */
    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                pool.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class Worker extends ForkJoinWorkerThread {
        Worker(ForkJoinPool pool, String name) {
            super(pool);
            setName(name);
            setContextClassLoader(TurboExecutor.class.getClassLoader());
        }
    }

    @SuppressWarnings("serial")
    private static final class RangeAction extends RecursiveAction {
        private final int lo;
        private final int hi;
        private final int grain;
        private final RangeBody body;

        RangeAction(int lo, int hi, int grain, RangeBody body) {
            this.lo = lo;
            this.hi = hi;
            this.grain = grain;
            this.body = body;
        }

        @Override
        protected void compute() {
            if (hi - lo <= grain) {
                body.accept(lo, hi);
                return;
            }
            int mid = lo + (hi - lo) / 2;
            invokeAll(new RangeAction(lo, mid, grain, body), new RangeAction(mid, hi, grain, body));
        }
    }
}
