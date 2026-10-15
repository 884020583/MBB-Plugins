package org.moboxlab.mbb.roleplay;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 角色线程池
 *
 * 负责回合线程池、后台任务线程池、任务提交和关闭。
 */
public class RoleplayExecutorService {
    private final RoleplayService service;
    private volatile ExecutorService turnExecutor;
    private volatile int turnThreads = 0;
    private volatile ExecutorService backgroundExecutor;

    public RoleplayExecutorService(RoleplayService service) {
        this.service = service;
    }

    void ensureTurnExecutor() {
        int threads = service.config().turnThreads < 1 ? 1 : Math.min(service.config().turnThreads,16);
        if (turnExecutor != null && turnThreads == threads) return;
        ExecutorService previous = turnExecutor;
        turnExecutor = Executors.newFixedThreadPool(threads,runnable -> {
            Thread thread = new Thread(runnable,"MoBoxBot-Roleplay-Turn");
            thread.setDaemon(true);
            return thread;
        });
        turnThreads = threads;
        if (previous != null) previous.shutdown();
    }

    void ensureBackgroundExecutor() {
        if (backgroundExecutor != null) return;
        backgroundExecutor = Executors.newFixedThreadPool(2,runnable -> {
            Thread thread = new Thread(runnable,"MoBoxBot-Roleplay-Background");
            thread.setDaemon(true);
            return thread;
        });
    }

    void submitTurn(Runnable task) {
        if (task == null) return;
        ExecutorService executor = turnExecutor;
        if (executor == null) {
            task.run();
            return;
        }
        try {
            executor.submit(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    void submitBackground(Runnable task) {
        if (task == null) return;
        ExecutorService executor = backgroundExecutor;
        if (executor == null) {
            task.run();
            return;
        }
        try {
            executor.submit(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }

    public void shutdown() {
        ExecutorService executor = turnExecutor;
        turnExecutor = null;
        if (executor != null) executor.shutdownNow();
        ExecutorService background = backgroundExecutor;
        backgroundExecutor = null;
        if (background != null) background.shutdownNow();
    }
}