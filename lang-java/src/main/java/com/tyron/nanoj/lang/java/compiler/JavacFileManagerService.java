package com.tyron.nanoj.lang.java.compiler;

import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.api.vfs.FileObject;

import javax.tools.JavaFileManager;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * A Project Service that holds the heavy JavacFileManager.
 * This ensures we only scan the bootclasspath (android.jar) once per project.
 */
public class JavacFileManagerService implements Disposable {

    private final Project project;
    private final JavaFileManager fileManager;

    public JavacFileManagerService(Project project) {
        this.project = project;
        // true = register with context, though we manage lifecycle manually
        var fm = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null);
        this.fileManager = new IndexedJavaFileManager(fm, project);
    }

    public JavaFileManager getFileManager() {
        return fileManager;
    }

    @Override
    public void dispose() {
        try {
            fileManager.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Iterable<File> toFiles(List<FileObject> fos) {
        List<File> files = new ArrayList<>();
        if (fos == null) return files;
        for (FileObject fo : fos) {
            files.add(new File(fo.getPath()));
        }
        return files;
    }
}