package com.tyron.nanoj.core.dumb;

import com.tyron.nanoj.api.dumb.DumbService;
import com.tyron.nanoj.api.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Project-scoped dumb mode implementation.
 */
public final class DumbServiceImpl implements DumbService {

    private final Project project;

    private final AtomicInteger dumbCounter = new AtomicInteger(0);

    private final Object queueLock = new Object();
    private final List<Runnable> runWhenSmartQueue = new ArrayList<>();

    public DumbServiceImpl(Project project) {
        this.project = Objects.requireNonNull(project, "project");
    }

    @Override
    public boolean isDumb() {
        return dumbCounter.get() > 0;
    }

    @Override
    public void runWhenSmart(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        if (!isDumb()) {
            runnable.run();
            return;
        }

        synchronized (queueLock) {
            if (!isDumb()) {
                runnable.run();
                return;
            }
            runWhenSmartQueue.add(runnable);
        }
    }

    @Override
    public DumbModeToken startDumbTask(String reason) {
        dumbCounter.incrementAndGet();

        AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            int remaining = dumbCounter.decrementAndGet();
            if (remaining == 0) {
                drainRunWhenSmartQueue();
            }
        };
    }

    private void drainRunWhenSmartQueue() {
        List<Runnable> toRun;
        synchronized (queueLock) {
            if (runWhenSmartQueue.isEmpty()) {
                return;
            }
            toRun = new ArrayList<>(runWhenSmartQueue);
            runWhenSmartQueue.clear();
        }

        for (Runnable r : toRun) {
            try {
                r.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}
