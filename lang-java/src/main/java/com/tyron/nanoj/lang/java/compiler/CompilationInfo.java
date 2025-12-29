package com.tyron.nanoj.lang.java.compiler;

import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import java.net.URI;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

/**
 * Represents a compiled state of a specific text snapshot.
 */
public class CompilationInfo implements AutoCloseable {

    public enum Phase { PARSED, RESOLVED }

    private final JavacTaskImpl task;
    private final Context context;
    private final DiagnosticCollector<JavaFileObject> diagnostics;
    private final StringWriter taskOutput;
    private JCTree.JCCompilationUnit root;
    private Phase currentPhase = null;

    public CompilationInfo(Project project, FileObject file, String content) {
        this.context = new Context();

        JavaFileObject source = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean b) { return content; }
        };

        List<String> options = List.of(
            "-proc:none", "-g", 
            "-source", "1.8", "-target", "1.8"
        );

        JavacTool tool = (JavacTool) ToolProvider.getSystemJavaCompiler();
        JavacFileManagerService fileManagerService = ProjectServiceManager.getService(project, JavacFileManagerService.class);

        // Avoid polluting stdout/stderr with compiler diagnostics.
        this.taskOutput = new StringWriter();
        this.diagnostics = new DiagnosticCollector<>();
        this.task = (JavacTaskImpl) tool.getTask(taskOutput, fileManagerService.getFileManager(), diagnostics, options, null, Collections.singletonList(source), context);
    }

    public void toPhase(Phase target) throws java.io.IOException {
        if (currentPhase == target) return;
        if (target == Phase.RESOLVED && currentPhase == Phase.PARSED) {
            task.analyze();
            currentPhase = Phase.RESOLVED;
            return;
        }

        Iterable<? extends com.sun.source.tree.CompilationUnitTree> units = task.parse();
        this.root = (JCTree.JCCompilationUnit) units.iterator().next();
        currentPhase = Phase.PARSED;

        if (target == Phase.RESOLVED) {
            task.analyze();
            currentPhase = Phase.RESOLVED;
        }
    }

    public JCTree.JCCompilationUnit getCompilationUnit() { return root; }
    public Context getContext() { return context; }
    public JavacTaskImpl getTask() { return task; }

    public List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
        return diagnostics.getDiagnostics();
    }

    public String getTaskOutput() {
        return taskOutput.toString();
    }

    @Override
    public void close() {
        // We do not close the file manager here because it is shared!
        // We only clear our task specific data.
    }
}