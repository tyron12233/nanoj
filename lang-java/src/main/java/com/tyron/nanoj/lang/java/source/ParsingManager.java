package com.tyron.nanoj.lang.java.source;

import com.tyron.nanoj.api.concurrent.TaskContext;
import com.tyron.nanoj.api.concurrent.TaskPriority;
import com.tyron.nanoj.api.concurrent.TaskScheduler;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.lang.java.compiler.CompilationInfo;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Singleton Service per Project.
 * Serializes all Javac access to ensure thread safety and low memory profile.
 */
public class ParsingManager implements com.tyron.nanoj.api.service.Disposable {

    private static final String LANE = "javac";

    private final Project project;

    private final TaskScheduler scheduler;
    
    // The current active compilation
    private CompilationInfo cachedInfo;
    private String cachedContentFingerprint;
    private FileObject cachedFile;

    public ParsingManager(Project project) {
        this.project = Objects.requireNonNull(project, "project");
        this.scheduler = ProjectServiceManager.getService(project, TaskScheduler.class);
    }

    /**
     * Enqueues a task.
     * @param priority If true, cancels the currently running task (if any) to run this one immediately.
     */
    public <T> CompletableFuture<T> post(
            FileObject file, 
            String currentText, 
            CompilationInfo.Phase targetPhase, 
            boolean priority, 
            Function<CompilationInfo, T> action) {

        TaskPriority prio = priority ? TaskPriority.USER : TaskPriority.BACKGROUND;

        // Serialize all javac access on a single lane to avoid thread-safety issues.
        // Latest-only semantics prevent unbounded queue growth while typing.
        return scheduler.submitLatest(LANE, this, prio, ctx -> {
            ctx.cancellation().throwIfCancelled();
            CompilationInfo info = getOrCreate(file, currentText, ctx);
            ctx.cancellation().throwIfCancelled();
            info.toPhase(targetPhase);
            ctx.cancellation().throwIfCancelled();
            return action.apply(info);
        });
    }

    /**
     * Called strictly inside the Javac Thread.
     */
    private CompilationInfo getOrCreate(FileObject file, String text, TaskContext ctx) {
        ctx.cancellation().throwIfCancelled();

        // Check Cache
        if (cachedInfo != null 
            && cachedFile.equals(file) 
            && cachedContentFingerprint.equals(text)) {
            return cachedInfo;
        }

        // Cache Miss - cleanup old
        if (cachedInfo != null) {
            cachedInfo.close();
        }

        ctx.cancellation().throwIfCancelled();

        cachedInfo = new CompilationInfo(project, file, text);
        cachedFile = file;
        cachedContentFingerprint = text;
        
        return cachedInfo;
    }

    @Override
    public void dispose() {
        scheduler.cancel(LANE, this);

        // Best-effort: close cached compilation on the javac lane to avoid races.
        try {
            scheduler.submitLatest(LANE, this, TaskPriority.USER, ctx -> {
                ctx.cancellation().throwIfCancelled();
                if (cachedInfo != null) {
                    cachedInfo.close();
                    cachedInfo = null;
                    cachedFile = null;
                    cachedContentFingerprint = null;
                }
                return null;
            });
        } catch (Throwable ignored) {
            if (cachedInfo != null) {
                cachedInfo.close();
                cachedInfo = null;
                cachedFile = null;
                cachedContentFingerprint = null;
            }
        }
    }
}