package com.tyron.nanoj.api.indexing;

import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;

import java.util.List;

public class Scopes {

    /**
     * Includes only files inside the project's source roots (src/main/java).
     */
    public static SearchScope projectSource(Project project) {
        IndexManager manager = IndexManager.getInstance();
        List<FileObject> roots = project.getSourceRoots();
        
        return fileId -> {
            var file =  VirtualFileManager.getInstance().findById(fileId);
            if (file == null) return false;
            String path = file.getPath();
            if (path == null) return false;
            
            // Check if path starts with any source root
            for (FileObject root : roots) {
                if (path.startsWith(root.getPath())) return true;
            }
            return false;
        };
    }

    /**
     * Includes only files in external libraries (jars/dependencies).
     */
    public static SearchScope libraries(Project project) {
        IndexManager manager = IndexManager.getInstance();
        List<FileObject> libs = project.getClassPath();
        List<FileObject> boot = project.getBootClassPath();

        return fileId -> {
            var file = VirtualFileManager.getInstance().findById(fileId);
            if (file == null) return false;
            String path = file.getPath();
            if (path == null) return false;

            for (FileObject lib : libs) {
                if (path.startsWith(lib.getPath())) return true;
            }
            for (FileObject b : boot) {
                if (path.startsWith(b.getPath())) return true;
            }
            return false;
        };
    }

    public static SearchScope all(Project project) {
        return fileId -> true;
    }
}