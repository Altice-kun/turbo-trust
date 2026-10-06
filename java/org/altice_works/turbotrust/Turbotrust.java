package org.altice_works.turbotrust;

import org.altice_works.turbotrust.api.TurboTrustAPI;
import org.altice_works.turbotrust.command.TurboTrustCommand;
import org.altice_works.turbotrust.core.TurboRuntime;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class Turbotrust extends JavaPlugin {

    private static final int MAX_THREADS = 256;

    private TurboRuntime runtime;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int cores = Runtime.getRuntime().availableProcessors();
        int configured = getConfig().getInt("threads", 0);
        int reserve = Math.max(0, getConfig().getInt("reserve-cores", 0));
        int threads = configured > 0 ? configured : Math.max(1, cores - reserve);
        threads = Math.min(threads, MAX_THREADS);
        int minParallelSize = Math.max(1, getConfig().getInt("min-parallel-size", 512));
        long budgetMs = Math.max(1, getConfig().getLong("main-thread-budget-ms", 5L));

        runtime = new TurboRuntime(
                threads,
                minParallelSize,
                budgetMs * 1_000_000L,
                error -> getLogger().log(Level.SEVERE, "TurboTrust のタスクで例外が発生しました", error));

        // 毎tick、ワーカーから渡された結果をメインスレッドで適用する
        getServer().getScheduler().runTaskTimer(this, runtime::drainMainThread, 1L, 1L);

        // 他プラグインから利用できるように公開
        getServer().getServicesManager().register(TurboTrustAPI.class, runtime, this, ServicePriority.Normal);

        PluginCommand command = getCommand("turbotrust");
        if (command != null) {
            command.setExecutor(new TurboTrustCommand(this, runtime));
        }

        getLogger().info("TurboTrust 有効化: ワーカースレッド " + threads + " 本 (JVM認識コア数: " + cores + ")");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
