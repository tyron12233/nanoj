package com.tyron.nanoj.lang.java.indexing;

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Indexes files by their Package Name.
 * <p>
 * <b>Key:</b> Package Name (e.g. "java.util")
 * <b>Value:</b> ClassSimpleName + Kind (e.g. "ArrayList" + CLASS)
 * </p>
 */
public class JavaPackageIndex implements IndexDefinition<String, JavaPackageIndex.Entry> {

    public static final String ID = "java_packages";
    private static final int VERSION = 1;

    private final ParserFactory parserFactory;

    public static JavaPackageIndex getInstance(Project project) {
        return ProjectServiceManager.getService(project, JavaPackageIndex.class);
    }

    public JavaPackageIndex(Project project) {
        Context context = new Context();
        new JavacFileManager(context, true, StandardCharsets.UTF_8);
        this.parserFactory = ParserFactory.instance(context);
    }

    @Override
    public String getId() { return ID; }

    @Override
    public int getVersion() { return VERSION; }

    @Override
    public boolean supports(FileObject file) {
        String ext = file.getExtension();
        return "java".equals(ext) || "class".equals(ext);
    }

    @Override
    public Map<String, Entry> map(FileObject file, Object helper) {
        Map<String, Entry> result = new HashMap<>();
        
        if ("class".equals(file.getExtension())) {
            // Binary Parsing
            try {
                ClassFile cf = SharedClassFile.get(file, helper);
                String binaryName = cf.getName().replace('/', '.'); // java.util.List
                
                int lastDot = binaryName.lastIndexOf('.');
                String packageName = (lastDot == -1) ? "" : binaryName.substring(0, lastDot);
                String simpleName = (lastDot == -1) ? binaryName : binaryName.substring(lastDot + 1);

                // Skip inner classes ($) for package listing (usually top level only needed for listing)
                if (!simpleName.contains("$")) {
                    result.put(packageName, new Entry(simpleName, JavaFileObject.Kind.CLASS));
                }
            } catch (Exception ignored) {}
        } else {
            // Source Parsing
            try {
                String packageName = "";
                try {
                    // Optimization: Don't read whole file if huge,
                    // but for VFS usually getting text is fast enough.
                    // ideally get a Reader and scan char-by-char, but getText() is safe for now.
                    packageName = LightweightPackageScanner.extractPackage(file.getText());
                } catch (IOException e) {
                    // ignore
                }
                
                String simpleName = file.getName().replace(".java", "");
                result.put(packageName, new Entry(simpleName, JavaFileObject.Kind.SOURCE));
            } catch (Exception ignored) {}
        }
        return result;
    }

    @Override
    public boolean isValueForFile(Entry value, int fileId) {
        return true; 
    }

    public static class Entry {
        public String simpleName;
        public JavaFileObject.Kind kind;

        public Entry(String n, JavaFileObject.Kind k) { simpleName = n; kind = k; }
    }

    @Override
    public byte[] serializeKey(String key) { return key.getBytes(StandardCharsets.UTF_8); }
    
    @Override
    public byte[] serializeValue(Entry value) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bo);
            dos.writeUTF(value.simpleName);
            dos.writeByte(value.kind == JavaFileObject.Kind.SOURCE ? 0 : 1);
            return bo.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    @Override
    public String deserializeKey(byte[] data) { return new String(data, StandardCharsets.UTF_8); }

    @Override
    public Entry deserializeValue(byte[] data) {
        try {
            java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            String name = dis.readUTF();
            JavaFileObject.Kind kind = dis.readByte() == 0 ? JavaFileObject.Kind.SOURCE : JavaFileObject.Kind.CLASS;
            return new Entry(name, kind);
        } catch (IOException e) { throw new RuntimeException(e); }
    }
}