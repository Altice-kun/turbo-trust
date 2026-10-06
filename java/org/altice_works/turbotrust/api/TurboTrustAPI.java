package org.altice_works.turbotrust.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * TurboTrust の公開API。他プラグインからは ServicesManager 経由で取得できる。
 *
 * <pre>{@code
 * TurboTrustAPI turbo = Bukkit.getServicesManager().load(TurboTrustAPI.class);
 * }</pre>
 *
 * <h2>スレッドセーフに関する重要なルール</h2>
 * ワーカースレッド(並列処理の本体)の中では <b>Bukkit / Paper / Towny のAPIを呼ばないこと</b>。
 * ワールド・エンティティ・ブロック等の状態はスレッドセーフではない。
 * 「メインスレッドでスナップショット(不変データ)を取る → ワーカーで計算 → メインスレッドで結果を適用」
 * の形で使うこと。
 */
public interface TurboTrustAPI {

    /** ワーカースレッド数。 */
    int parallelism();

    /** 範囲 [from, to) を受け取って処理する本体。 */
    @FunctionalInterface
    interface RangeBody {
        void accept(int fromInclusive, int toExclusive);
    }

    /**
     * [startInclusive, endExclusive) の各インデックスに対して body を全コアで並列実行する。
     * 完了するまで呼び出し元スレッドはブロックされる(メインスレッドから呼ぶとその間tickが止まる)。
     * 要素数が少ない場合は並列化せず呼び出し元スレッドで実行する。
     */
    void parallelFor(int startInclusive, int endExclusive, IntConsumer body);

    /**
     * {@link #parallelFor} の区間版。grain は1タスクあたりの最大要素数(0以下なら自動)。
     * 1要素あたりの処理が軽い場合はこちらの方がオーバーヘッドが小さい。
     */
    void parallelForRange(int startInclusive, int endExclusive, int grain, RangeBody body);

    /** input の各要素に fn を並列適用し、順序を保った結果リストを返す(ブロッキング)。 */
    <T, R> List<R> parallelMap(List<? extends T> input, Function<? super T, ? extends R> fn);

    /** ワーカースレッドでタスクを非同期実行する。結果は Future で受け取る。 */
    <T> CompletableFuture<T> submit(Supplier<? extends T> task);

    /**
     * compute をワーカーで実行し、結果を次tick以降にメインスレッドで applyOnMain に渡す。
     * 例外が発生した場合はログに出力される。
     */
    <T> void computeThenApply(Supplier<? extends T> compute, Consumer<? super T> applyOnMain);

    /** {@link #computeThenApply(Supplier, Consumer)} の例外ハンドラ指定版(onError もメインスレッドで実行される)。 */
    <T> void computeThenApply(Supplier<? extends T> compute,
                              Consumer<? super T> applyOnMain,
                              Consumer<Throwable> onError);

    /** 任意のスレッドから、メインスレッドでの実行を予約する(次tick以降、時間予算内で実行)。 */
    void runOnMain(Runnable task);
}
