package com.tyron.nanoj.lang.java.indexing;

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Index that maps a direct supertype to its direct subtypes.
 *
 * Key/value use JVM internal names (e.g. {@code java/util/List}, {@code java/util/ArrayList}).
 *
 * This index powers "expected type" class-name completion such as:
 *
 * {@code List<String> list = new <caret>}
 */
public final class JavaSuperTypeIndex implements IndexDefinition<String, String> {

    public static final String ID = "java_supertypes";
    private static final int VERSION = 1;

    public static JavaSuperTypeIndex getInstance(Project project) {
        return ProjectServiceManager.getService(project, JavaSuperTypeIndex.class);
    }

    @SuppressWarnings("unused")
    public JavaSuperTypeIndex(Project project) {
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public boolean supports(FileObject fileObject) {
        return fileObject != null && "class".equalsIgnoreCase(fileObject.getExtension());
    }

    @Override
    public Map<String, String> map(FileObject file, Object helper) {
        try {
            ClassFile cf = SharedClassFile.get(file, helper);

            String self = cf.getName();

            Map<String, String> out = new HashMap<>();

            String superName = cf.getSuperclassName();
            if (superName != null && !superName.isBlank()) {
                out.put(superName + "#" + self, self);
            }

            for (int i = 0; i < cf.interfaces.length; i++) {
                String iface = cf.getInterfaceName(i);
                if (iface != null && !iface.isBlank()) {
                    out.put(iface + "#" + self, self);
                }
            }

            return out;
        } catch (IOException | ConstantPoolException e) {
            return Map.of();
        }
    }

    @Override
    public boolean isValueForFile(String value, int fileId) {
        return true;
    }

    @Override
    public byte[] serializeKey(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] serializeValue(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String deserializeKey(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public String deserializeValue(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }
}
