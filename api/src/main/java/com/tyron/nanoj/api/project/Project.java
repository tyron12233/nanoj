package com.tyron.nanoj.api.project;

import com.tyron.nanoj.api.service.Disposable;
import com.tyron.nanoj.api.vfs.FileObject;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Represents a loaded project in the IDE.
 * <p>
 * This interface acts as the "World View" for the compiler and editor.
 * It defines where source files live, where libraries are found, and where output goes.
 * </p>
 */
public interface Project extends Disposable {

    /**
     * @return The display name of the project (e.g., "MyApplication").
     */
    String getName();

    /**
     * @return The root folder containing the project configuration (e.g., build.gradle or .project).
     */
    FileObject getRootDirectory();

    /**
     * @return A list of folders containing Java source files (e.g., "src/main/java").
     * Used by the Indexer and Compiler to resolve "package-info.java" and source roots.
     */
    List<FileObject> getSourceRoots();

    /**
     * @return A list of folders containing resources (e.g., "src/main/res", "src/main/resources").
     */
    List<FileObject> getResourceRoots();

    /**
     * @return The list of external libraries (JARs/AARs) required to compile the project.
     * This forms the user classpath.
     */
    List<FileObject> getClassPath();

    /**
     * @return The Platform Classpath (e.g., android.jar or rt.jar).
     * Critical for Javac to resolve java.lang.*.
     */
    List<FileObject> getBootClassPath();

    /**
     * @return The VFS folder where compilation output (.class, .dex) should be written.
     * (e.g., "build/classes", "bin").
     */
    FileObject getBuildDirectory();

    /**
     * @return A dedicated directory on the local disk for internal IDE caches
     * (Indexes, AST caches, file history).
     * Note: Returns {@link java.io.File} because internal stores (like LevelDB or BucketedStore)
     * typically require direct disk access for performance, bypassing VFS overhead.
     */
    File getCacheDir();

    /**
     * @return The configuration provider for this project.
     */
    ProjectConfiguration getConfiguration();

    /**
     * @return true if the project is currently open and valid.
     * Background tasks should check this before running expensive operations.
     */
    boolean isOpen();

    /**
     * Generic configuration wrapper (Key-Value store).
     */
    interface ProjectConfiguration {
        String getProperty(String key);
        String getProperty(String key, String defaultValue);

        void setProperty(String key, String value);

        /**
         * @return Standard Java version (e.g., 8, 11, 17).
         */
        int getJavaVersion();
    }
}