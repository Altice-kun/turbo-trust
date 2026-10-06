package org.altice_works.turbotrust.core;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「サーバーのCPU使用率が、どのスレッドに使われているか」を内訳で出す計測ツール。
 * Bukkit に依存しない。指定時間スリープしながら、その間のCPU時間の増分を集計する
 * (メインスレッドから呼ばないこと)。
 *
 * 数値はすべて「マシン全体のCPU能力(コア数 × 経過時間)に対する割合(%)」で、
 * タスクマネージャーの全体CPU使用率と同じ基準。
 */
public final class CpuProfiler {

    public static final String MAIN_THREAD_NAME = "Server thread";
    public static final String TURBOTRUST_PREFIX = "TurboTrust-";

    public record ThreadShare(String name, double percent) {
    }

    public record Result(double seconds, int cores,
                         double processPercent,
                         double mainThreadPercent,
                         double turboTrustPercent,
                         double otherJavaPercent,
                         double jvmInternalPercent,
                         List<ThreadShare> topOthers) {
    }

    private CpuProfiler() {
    }

    public static Result sample(long durationMillis) throws InterruptedException {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (!threads.isThreadCpuTimeSupported()) {
            throw new UnsupportedOperationException("このJVMはスレッドごとのCPU時間の計測に対応していません");
        }
        if (!threads.isThreadCpuTimeEnabled()) {
            threads.setThreadCpuTimeEnabled(true);
        }
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

        Map<Long, Long> before = threadCpu(threads);
        long processBefore = os.getProcessCpuTime();
        long startNanos = System.nanoTime();

        Thread.sleep(Math.max(1L, durationMillis));

        Map<Long, Long> after = threadCpu(threads);
        long processAfter = os.getProcessCpuTime();
        long elapsed = Math.max(1L, System.nanoTime() - startNanos);

        long[] ids = after.keySet().stream().mapToLong(Long::longValue).toArray();
        ThreadInfo[] infos = threads.getThreadInfo(ids);

        long mainNs = 0;
        long turboNs = 0;
        Map<String, Long> otherByName = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (infos[i] == null) {
                continue; // 計測中に終了したスレッド(JVM内部/計測外に含まれる)
            }
            long delta = Math.max(0L, after.get(ids[i]) - before.getOrDefault(ids[i], 0L));
            String name = infos[i].getThreadName();
            switch (classify(name)) {
                case MAIN -> mainNs += delta;
                case TURBOTRUST -> turboNs += delta;
                case OTHER -> otherByName.merge(normalizeName(name), delta, Long::sum);
            }
        }
        long otherNs = otherByName.values().stream().mapToLong(Long::longValue).sum();
        long javaNs = mainNs + turboNs + otherNs;

        long processNs = processBefore >= 0 && processAfter >= processBefore
                ? processAfter - processBefore
                : javaNs; // プロセスCPU時間が取れない環境では Javaスレッド合計で代用
        long internalNs = Math.max(0L, processNs - javaNs);
        double capacity = (double) elapsed * cores;

        List<ThreadShare> top = new ArrayList<>();
        for (Map.Entry<String, Long> e : otherByName.entrySet()) {
            top.add(new ThreadShare(e.getKey(), e.getValue() / capacity * 100.0));
        }
        top.sort(Comparator.comparingDouble(ThreadShare::percent).reversed());
        if (top.size() > 5) {
            top = new ArrayList<>(top.subList(0, 5));
        }

        return new Result(
                elapsed / 1e9, cores,
                Math.max(processNs, javaNs) / capacity * 100.0,
                mainNs / capacity * 100.0,
                turboNs / capacity * 100.0,
                otherNs / capacity * 100.0,
                internalNs / capacity * 100.0,
                List.copyOf(top));
    }

    enum Group {MAIN, TURBOTRUST, OTHER}

    static Group classify(String threadName) {
        if (MAIN_THREAD_NAME.equals(threadName)) {
            return Group.MAIN;
        }
        if (threadName.startsWith(TURBOTRUST_PREFIX)) {
            return Group.TURBOTRUST;
        }
        return Group.OTHER;
    }

    /** "Netty Server IO #3" や "Worker-12" のような連番を落として、同種スレッドを1行にまとめる。 */
    static String normalizeName(String threadName) {
        String n = threadName.replaceAll("[\\s#\\-_]*\\d+$", "");
        return n.isEmpty() ? threadName : n;
    }

    private static Map<Long, Long> threadCpu(ThreadMXBean threads) {
        Map<Long, Long> map = new HashMap<>();
        for (long id : threads.getAllThreadIds()) {
            long cpu = threads.getThreadCpuTime(id); // 終了済み・非対応は -1
            if (cpu >= 0) {
                map.put(id, cpu);
            }
        }
        return map;
    }
}
