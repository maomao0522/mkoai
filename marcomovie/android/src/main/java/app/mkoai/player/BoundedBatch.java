package app.mkoai.player;

/** Bounded background metadata work: one slow provider cannot hold all shelves indefinitely. */
final class BoundedBatch {
    static <T> java.util.List<T> run(java.util.List<java.util.concurrent.Callable<T>> jobs, int workers, long budgetMs) throws InterruptedException {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(1, Math.min(4, workers)), runnable -> {
                    Thread thread = new Thread(runnable, "mkoai-metadata");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
        try {
            java.util.List<java.util.concurrent.Future<T>> futures = pool.invokeAll(jobs, budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            java.util.List<T> result = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<T> future : futures) {
                try { result.add(future.isCancelled() ? null : future.get()); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failed) { result.add(null); }
            }
            return result;
        } finally { pool.shutdownNow(); }
    }
    private BoundedBatch() {}
}
