package org.altice_works.turbotrust.command;

import org.altice_works.turbotrust.core.Benchmark;
import org.altice_works.turbotrust.core.CpuProfiler;
import org.altice_works.turbotrust.core.TurboExecutor;
import org.altice_works.turbotrust.core.TurboRuntime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** /turbotrust status | bench [size] */
public final class TurboTrustCommand implements TabExecutor {

    private static final int DEFAULT_BENCH_SIZE = 1_000_000;
    private static final int MIN_BENCH_SIZE = 10_000;
    private static final int MAX_BENCH_SIZE = 10_000_000; // 配列80MB程度

    private final JavaPlugin plugin;
    private final TurboRuntime runtime;
    private final AtomicBoolean benchRunning = new AtomicBoolean();
    private final AtomicBoolean loadRunning = new AtomicBoolean();

    public TurboTrustCommand(JavaPlugin plugin, TurboRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "bench" -> bench(sender, args);
            case "load" -> load(sender, args);
            default -> send(sender, "使い方: /" + label + " <status | bench [要素数] | load [秒]>", NamedTextColor.YELLOW);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "bench", "load").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private void status(CommandSender sender) {
        TurboRuntime.Stats s = runtime.stats();
        TurboExecutor.Stats e = s.executor();
        int cores = Runtime.getRuntime().availableProcessors();
        send(sender, "=== TurboTrust ステータス ===", NamedTextColor.GOLD);
        send(sender, "ワーカースレッド: " + e.parallelism() + " 本 (JVM認識コア数: " + cores + ")", NamedTextColor.WHITE);
        send(sender, "稼働中: " + e.activeThreads() + " / プール: " + e.poolSize()
                + " / 待機タスク: " + (e.queuedTasks() + e.queuedSubmissions())
                + " / steal: " + e.steals(), NamedTextColor.WHITE);
        send(sender, "submit: " + e.submitted() + " 件 (完了 " + e.completed() + ")", NamedTextColor.WHITE);
        send(sender, "並列実行: " + e.parallelCalls() + " 回 / 並列化せず直接実行: " + e.inlineCalls() + " 回", NamedTextColor.WHITE);
        send(sender, "メインスレッド適用: 待ち " + s.bridgePending() + " 件 / 累計 " + s.bridgeExecuted()
                + " 件 / 直近tick " + s.lastDrainMicros() + "µs", NamedTextColor.WHITE);
    }

    private void bench(CommandSender sender, String[] args) {
        int size = DEFAULT_BENCH_SIZE;
        if (args.length >= 2) {
            try {
                size = Integer.parseInt(args[1].replace("_", ""));
            } catch (NumberFormatException ex) {
                send(sender, "要素数は整数で指定してください: " + args[1], NamedTextColor.RED);
                return;
            }
        }
        size = Math.max(MIN_BENCH_SIZE, Math.min(MAX_BENCH_SIZE, size));

        if (!benchRunning.compareAndSet(false, true)) {
            send(sender, "ベンチマークは実行中です。完了までお待ちください。", NamedTextColor.YELLOW);
            return;
        }
        final int benchSize = size;
        send(sender, "ベンチマーク開始 (要素数 " + benchSize + ")。数秒間CPUを使い切ります...", NamedTextColor.YELLOW);

        // メインスレッドを止めないよう専用スレッドで実行し、結果だけメインスレッドで表示する
        Thread thread = new Thread(() -> {
            try {
                Benchmark.Result r = Benchmark.run(runtime.executor(), benchSize);
                runtime.runOnMain(() -> report(sender, r));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "ベンチマーク中に例外が発生しました", t);
                runtime.runOnMain(() -> send(sender, "ベンチマークに失敗しました(詳細はログ)", NamedTextColor.RED));
            } finally {
                benchRunning.set(false);
            }
        }, "TurboTrust-Bench");
        thread.setDaemon(true);
        thread.start();
    }

    private void load(CommandSender sender, String[] args) {
        int seconds = 10;
        if (args.length >= 2) {
            try {
                seconds = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                send(sender, "秒数は整数で指定してください: " + args[1], NamedTextColor.RED);
                return;
            }
        }
        final int sampleSeconds = Math.max(3, Math.min(60, seconds));

        if (!loadRunning.compareAndSet(false, true)) {
            send(sender, "CPU計測は実行中です。完了までお待ちください。", NamedTextColor.YELLOW);
            return;
        }
        send(sender, "CPU使用率の内訳を " + sampleSeconds + " 秒間計測します(普段の状態のまま待ってください)...", NamedTextColor.YELLOW);

        Thread thread = new Thread(() -> {
            try {
                CpuProfiler.Result r = CpuProfiler.sample(sampleSeconds * 1000L);
                runtime.runOnMain(() -> reportLoad(sender, r));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "CPU計測中に例外が発生しました", t);
                runtime.runOnMain(() -> send(sender, "CPU計測に失敗しました(詳細はログ)", NamedTextColor.RED));
            } finally {
                loadRunning.set(false);
            }
        }, "TurboTrust-LoadSampler");
        thread.setDaemon(true);
        thread.start();
    }

    private void reportLoad(CommandSender sender, CpuProfiler.Result r) {
        send(sender, "=== CPU使用率の内訳(マシン全体比 / " + String.format(Locale.ROOT, "%.0f", r.seconds())
                + "秒 / " + r.cores() + "コア) ===", NamedTextColor.GOLD);
        send(sender, String.format(Locale.ROOT, "サーバープロセス合計: %.2f%%", r.processPercent()), NamedTextColor.GREEN);
        send(sender, String.format(Locale.ROOT, "  メインスレッド(Server thread): %.2f%%", r.mainThreadPercent()), NamedTextColor.WHITE);
        send(sender, String.format(Locale.ROOT, "  TurboTrust(ワーカー等): %.2f%%", r.turboTrustPercent()), NamedTextColor.WHITE);
        send(sender, String.format(Locale.ROOT, "  その他のJavaスレッド: %.2f%%", r.otherJavaPercent()), NamedTextColor.WHITE);
        for (CpuProfiler.ThreadShare t : r.topOthers()) {
            send(sender, String.format(Locale.ROOT, "      %s: %.2f%%", t.name(), t.percent()), NamedTextColor.WHITE);
        }
        send(sender, String.format(Locale.ROOT, "  JVM内部・計測外(GC/JIT等): %.2f%%", r.jvmInternalPercent()), NamedTextColor.WHITE);
    }

    private void report(CommandSender sender, Benchmark.Result r) {
        send(sender, "=== ベンチマーク結果 ===", NamedTextColor.GOLD);
        send(sender, String.format(Locale.ROOT, "要素数 %d / スレッド %d 本", r.size(), r.threads()), NamedTextColor.WHITE);
        send(sender, String.format(Locale.ROOT, "逐次(1コア): %.1f ms", r.sequentialMs()), NamedTextColor.WHITE);
        send(sender, String.format(Locale.ROOT, "並列(全コア): %.1f ms  → %.2f 倍", r.parallelMs(), r.speedup()), NamedTextColor.GREEN);
        send(sender, r.identical() ? "結果の一致: OK" : "結果の一致: NG (不一致)", r.identical() ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private static void send(CommandSender sender, String message, NamedTextColor color) {
        sender.sendMessage(Component.text(message, color));
    }
}
