package org.altice_works.turbotrust.core;

import java.util.Arrays;

/**
 * 全コア並列化の効果を確かめるための合成ベンチマーク(純粋な数値計算)。
 * 逐次実行と並列実行の結果が一致するかも検証する。
 */
public final class Benchmark {

    private static final int INNER_LOOPS = 64;
    private static final int RUNS = 2;

    public record Result(int size, int threads, double sequentialMs, double parallelMs, boolean identical) {
        public double speedup() {
            return parallelMs <= 0 ? 0 : sequentialMs / parallelMs;
        }
    }

    private Benchmark() {
    }

    /** メインスレッド以外(専用スレッド等)から呼ぶこと。数秒間CPUを使い切る。 */
    public static Result run(TurboExecutor executor, int size) {
        double[] data = new double[size];

        // JITのウォームアップ
        int warm = Math.min(size, 100_000);
        for (int i = 0; i < 2; i++) {
            work(data, 0, warm);
            executor.parallelForRange(0, warm, 0, (lo, hi) -> work(data, lo, hi));
        }

        long bestSeq = Long.MAX_VALUE;
        long bestPar = Long.MAX_VALUE;
        double seqSum = 0;
        double parSum = 0;
        for (int run = 0; run < RUNS; run++) {
            Arrays.fill(data, 0.0);
            long t0 = System.nanoTime();
            work(data, 0, size);
            bestSeq = Math.min(bestSeq, System.nanoTime() - t0);
            seqSum = checksum(data);

            Arrays.fill(data, 0.0);
            long t1 = System.nanoTime();
            executor.parallelForRange(0, size, 0, (lo, hi) -> work(data, lo, hi));
            bestPar = Math.min(bestPar, System.nanoTime() - t1);
            parSum = checksum(data);
        }
        return new Result(size, executor.parallelism(), bestSeq / 1e6, bestPar / 1e6,
                Double.compare(seqSum, parSum) == 0);
    }

    /** 加減乗除と平方根のみ(結果がスレッド数に依存しない決定的な計算)。 */
    private static void work(double[] out, int lo, int hi) {
        for (int i = lo; i < hi; i++) {
            double x = 1.0 + i * 1.0e-6;
            for (int k = 0; k < INNER_LOOPS; k++) {
                x = Math.sqrt(x * x + 1.0) * 0.999 + 0.001;
            }
            out[i] = x;
        }
    }

    private static double checksum(double[] data) {
        double sum = 0;
        for (double v : data) {
            sum += v;
        }
        return sum;
    }
}
