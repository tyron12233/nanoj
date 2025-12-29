package com.tyron.nanoj.lang.java.source;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.lang.java.compiler.CompilationInfo;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The entry point for clients (Editor, Code Completion).
 * Does not hold state. Delegates to ParsingManager.
 */
public class JavaSource {

    public enum Phase {
        MODIFIED,         // File touched, nothing parsed
        PARSED,           // AST generated
        ELEMENTS_RESOLVED,// Symbols entered
        RESOLVED,         // Attribution complete (Slow)
        UP_TO_DATE        // Everything done
    }

    private final Project project;
    private final FileObject file;

    public static JavaSource forFile(Project project, FileObject file) {
        return new JavaSource(project, file);
    }

    private JavaSource(Project project, FileObject file) {
        this.project = project;
        this.file = file;
    }

    /**
     * Run a task that requires the code to be fully resolved (with types).
     * Used for: Code Completion, Go to Definition.
     * Priority: HIGH (Cancels other tasks).
     */
    public <T> CompletableFuture<T> runUserActionTask(String text, Function<CompilationInfo, T> action) {
        ParsingManager manager = ProjectServiceManager.getService(project, ParsingManager.class);
        return manager.post(file, text, CompilationInfo.Phase.RESOLVED, true, action);
    }

    /**
     * Run a task that only requires syntax trees (no types).
     * Used for: Syntax Highlighting, Folding, Basic Error checking.
     * Priority: LOW (Does not cancel running tasks).
     */
    public <T> CompletableFuture<T> runModificationTask(String text, Function<CompilationInfo, T> action) {
        ParsingManager manager = ProjectServiceManager.getService(project, ParsingManager.class);
        return manager.post(file, text, CompilationInfo.Phase.PARSED, false, action);
    }

    /**
     * Run a task intended for error highlighting (diagnostics).
     *
     * Uses a RESOLVED compilation phase so type-related errors are reported,
     * but runs at LOW priority (does not cancel running tasks).
     */
    public <T> CompletableFuture<T> runDiagnosticsTask(String text, Function<CompilationInfo, T> action) {
        ParsingManager manager = ProjectServiceManager.getService(project, ParsingManager.class);
        return manager.post(file, text, CompilationInfo.Phase.RESOLVED, false, action);
    }
}