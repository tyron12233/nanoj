package com.tyron.nanoj.lang.java.indexing;

import com.sun.tools.classfile.*;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.lang.java.indexing.stub.ClassStub;

import java.io.*;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Indexes compiled .class files to produce lightweight Stubs.
 * <p>
 * This allows the IDE to know about methods/fields in libraries (android.jar, libs/*.jar)
 * without loading them into the runtime memory.
 * </p>
 */
public class JavaBinaryStubIndexer implements IndexDefinition<String, ClassStub> {

    public static final String ID = "java_stubs";
    private static final int VERSION = 5;

    public static JavaBinaryStubIndexer getInstance(Project project) {
        return ProjectServiceManager.getService(project, JavaBinaryStubIndexer.class);
    }

    @SuppressWarnings("unused")
    public JavaBinaryStubIndexer(Project project) {
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
    public boolean supports(FileObject file) {
        return "class".equalsIgnoreCase(file.getExtension());
    }

    @Override
    public Map<String, ClassStub> map(FileObject file, Object helper) {
        try {
            ClassFile cf = SharedClassFile.get(file, helper);

            if (!cf.access_flags.is(Modifier.PUBLIC)) {
                return Map.of();
            }
            
            ClassStub stub = new ClassStub();

            stub.name = cf.getName();
            stub.superName = cf.getSuperclassName();
            stub.accessFlags = cf.access_flags.flags;
            stub.sourceFile = file.getPath();

            int ifaceCount = cf.interfaces.length;
            stub.interfaces = new String[ifaceCount];
            for (int i = 0; i < ifaceCount; i++) {
                stub.interfaces[i] = cf.getInterfaceName(i);
            }

            Signature_attribute clsSig = (Signature_attribute) cf.getAttribute(Attribute.Signature);
            if (clsSig != null) {
                stub.signature = clsSig.getSignature(cf.constant_pool);
            }

            for (Field f : cf.fields) {
                ClassStub.FieldStub fs = new ClassStub.FieldStub();
                fs.name = f.getName(cf.constant_pool);
                fs.descriptor = f.descriptor.getValue(cf.constant_pool);
                fs.accessFlags = f.access_flags.flags;
                
                Signature_attribute sig = (Signature_attribute) f.attributes.get(Attribute.Signature);
                if (sig != null) {
                    fs.signature = sig.getSignature(cf.constant_pool);
                }
                stub.fields.add(fs);
            }

            for (Method m : cf.methods) {
                ClassStub.MethodStub ms = new ClassStub.MethodStub();
                ms.name = m.getName(cf.constant_pool);
                ms.descriptor = m.descriptor.getValue(cf.constant_pool);
                ms.accessFlags = m.access_flags.flags;
                
                Signature_attribute sig = (Signature_attribute) m.attributes.get(Attribute.Signature);
                if (sig != null) {
                    ms.signature = sig.getSignature(cf.constant_pool);
                }
                stub.methods.add(ms);
            }

            // map key: FQN (e.g., "java/util/List") -> Stub
            Map<String, ClassStub> result = new HashMap<>();
            result.put(stub.name, stub);
            return result;

        } catch (ConstantPoolException | IOException e) {
            // if the class file is malformed or locked, skip it.
            System.err.println("Failed to index class file: " + file.getName() + " - " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public boolean isValueForFile(ClassStub value, int fileId) {
        // While we store the source path in the stub, strictly checking fileId ownership
        // in the IndexManager requires resolving the fileId to a path.
        // Assuming the IndexManager does strict cleanup based on ForwardIndex keys,
        // returning true here is usually safe. 
        // 
        // However, if multiple jars contain the SAME class (Duplicate Classpath Hell),
        // we might want to differentiate. For now, strict replacement is preferred.
        return true;
    }

    @Override
    public byte[] serializeKey(String key) {
        return key.getBytes(); // UTF-8 default
    }

    @Override
    public byte[] serializeValue(ClassStub value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            value.write(dos);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // Should not happen with ByteArrayOutputStream
        }
        return baos.toByteArray();
    }

    @Override
    public String deserializeKey(byte[] data) {
        return new String(data);
    }

    @Override
    public ClassStub deserializeValue(byte[] data) {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        try (DataInputStream dis = new DataInputStream(bais)) {
            return ClassStub.read(dis);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}