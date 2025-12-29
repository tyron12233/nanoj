package com.tyron.nanoj.core.concurrent;

import com.tyron.nanoj.api.concurrent.CancellationTokenSource;
import com.tyron.nanoj.api.concurrent.TaskContext;
import com.tyron.nanoj.api.concurrent.TaskContexts;
import com.tyron.nanoj.api.concurrent.TaskPriority;
import com.tyron.nanoj.api.concurrent.TaskScheduler;
import com.tyron.nanoj.api.project.Project;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default project-scoped task scheduler.
 */
public final class TaskSchedulerImpl implements TaskScheduler {

    private final Project project;

    private final ConcurrentHashMap<String, ExecutorService> lanes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SlotKey, Slot> slots = new ConcurrentHashMap<>();

    public TaskSchedulerImpl(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    @Override
    public <T> CompletableFuture<T> submitLatest(String lane, Object key, TaskPriority priority, TaskAction<T> task) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(task, "task");

        if (!project.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }

        SlotKey slotKey = new SlotKey(lane, key);
        Slot slot = slots.computeIfAbsent(slotKey, k -> new Slot());

        long seq = slot.seq.incrementAndGet();

        // Cancel any previous request for this slot (queued or running).
        CancellationTokenSource prevToken = slot.token;
        CancellationTokenSource token = new CancellationTokenSource();
        slot.token = token;
        if (prevToken != null) {
            prevToken.cancel();
        }

        Future<?> prev = slot.current;
        if (priority == TaskPriority.USER && prev != null && !prev.isDone()) {
            prev.cancel(true);
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        ExecutorService exec = laneExecutor(lane);

        Future<?> submitted = exec.submit(() -> {
            if (!project.isOpen()) {
                result.complete(null);
                return;
            }

            // If a newer request exists, skip real work.
            if (slot.seq.get() != seq) {
                token.cancel();
                result.cancel(false);
                return;
            }

            try {
                TaskContext ctx = new TaskContextImpl(token, priority, System.nanoTime());
                T value = TaskContexts.with(ctx, () -> {
                    ctx.cancellation().throwIfCancelled();
                    return task.run(ctx);
                });

                // If a newer request arrived while we ran, drop the result.
                if (slot.seq.get() != seq) {
                    token.cancel();
                    result.cancel(false);
                    return;
                }

                result.complete(value);
            } catch (CancellationException e) {
                token.cancel();
                result.cancel(true);
            } catch (InterruptedException e) {
                token.cancel();
                Thread.currentThread().interrupt();
                result.cancel(true);
            } catch (Throwable t) {
                // Treat interrupts surfaced as generic exceptions as cancellation.
                if (Thread.currentThread().isInterrupted()) {
                    token.cancel();
                    result.cancel(true);
                } else {
                    result.completeExceptionally(t);
                }
            }
        });

        slot.current = submitted;
        return result;
    }

    @Override
    public void cancel(String lane, Object key) {
        if (lane == null || key == null) {
            return;
        }

        SlotKey slotKey = new SlotKey(lane, key);
        Slot slot = slots.get(slotKey);
        if (slot == null) {
            return;
        }

        // Invalidate queued work.
        slot.seq.incrementAndGet();

        CancellationTokenSource tok = slot.token;
        if (tok != null) {
            tok.cancel();
        }

        Future<?> cur = slot.current;
        if (cur != null) {
            cur.cancel(true);
        }

        // Best-effort cleanup.
        slots.remove(slotKey, slot);
    }

    private ExecutorService laneExecutor(String lane) {
        return lanes.computeIfAbsent(lane, this::createSingleThreadLane);
    }

    private ExecutorService createSingleThreadLane(String lane) {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "TaskLane-" + lane);
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(tf);
    }

    @Override
    public void dispose() {
        for (ExecutorService exec : lanes.values()) {
            try {
                exec.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
        lanes.clear();
        slots.clear();
    }

    private record SlotKey(String lane, Object key) {
        SlotKey {
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(key, "key");
        }
    }

    private static final class Slot {
        final AtomicLong seq = new AtomicLong();
        volatile Future<?> current;
        volatile CancellationTokenSource token;
    }

    private static final class TaskContextImpl implements TaskContext {
        private final CancellationTokenSource token;
        private final TaskPriority priority;
        private final long submittedAt;

        private TaskContextImpl(CancellationTokenSource token, TaskPriority priority, long submittedAt) {
            this.token = token;
            this.priority = priority;
            this.submittedAt = submittedAt;
        }

        @Override
        public com.tyron.nanoj.api.concurrent.CancellationToken cancellation() {
            return token.token();
        }

        @Override
        public TaskPriority priority() {
            return priority;
        }

        @Override
        public long submittedAtNanos() {
            return submittedAt;
        }
    }
}
