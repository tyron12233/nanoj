package com.tyron.nanoj.lang.java.indexing.stub;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A lightweight representation of a Java class structure.
 * Used to provide symbol tables to Javac without loading the actual Class file.
 */
public class ClassStub {

    public String name;          // FQN: java/util/List
    public String superName;     // java/lang/Object
    public String[] interfaces;
    public int accessFlags;
    public String signature;     // Generic Signature
    public String sourceFile;    // Originating file path (for incremental updates)

    public final List<FieldStub> fields = new ArrayList<>();
    public final List<MethodStub> methods = new ArrayList<>();

    public ClassStub() {}

    public void write(DataOutput out) throws IOException {
        out.writeUTF(name != null ? name : "");
        out.writeUTF(superName != null ? superName : "");
        
        out.writeInt(interfaces.length);
        for (String iface : interfaces) out.writeUTF(iface);
        
        out.writeInt(accessFlags);
        out.writeUTF(signature != null ? signature : "");
        out.writeUTF(sourceFile != null ? sourceFile : "");

        out.writeInt(fields.size());
        for (FieldStub f : fields) f.write(out);

        out.writeInt(methods.size());
        for (MethodStub m : methods) m.write(out);
    }

    public static ClassStub read(DataInput in) throws IOException {
        ClassStub stub = new ClassStub();
        stub.name = in.readUTF();
        stub.superName = in.readUTF();
        
        int ifaceCount = in.readInt();
        stub.interfaces = new String[ifaceCount];
        for (int i = 0; i < ifaceCount; i++) stub.interfaces[i] = in.readUTF();
        
        stub.accessFlags = in.readInt();
        stub.signature = in.readUTF();
        if (stub.signature.isEmpty()) stub.signature = null;
        
        stub.sourceFile = in.readUTF();

        int fieldCount = in.readInt();
        for (int i = 0; i < fieldCount; i++) stub.fields.add(FieldStub.read(in));

        int methodCount = in.readInt();
        for (int i = 0; i < methodCount; i++) stub.methods.add(MethodStub.read(in));

        return stub;
    }

    // --- Nested Stubs ---

    public static class FieldStub {
        public String name;
        public String descriptor; // e.g., Ljava/lang/String;
        public String signature;  // Generic signature
        public int accessFlags;

        public void write(DataOutput out) throws IOException {
            out.writeUTF(name);
            out.writeUTF(descriptor);
            out.writeUTF(signature != null ? signature : "");
            out.writeInt(accessFlags);
        }

        public static FieldStub read(DataInput in) throws IOException {
            FieldStub s = new FieldStub();
            s.name = in.readUTF();
            s.descriptor = in.readUTF();
            s.signature = in.readUTF();
            if (s.signature.isEmpty()) s.signature = null;
            s.accessFlags = in.readInt();
            return s;
        }
    }

    public static class MethodStub {
        public String name;
        public String descriptor; // (Ljava/lang/Object;)V
        public String signature;  // Generic signature
        public int accessFlags;

        public void write(DataOutput out) throws IOException {
            out.writeUTF(name);
            out.writeUTF(descriptor);
            out.writeUTF(signature != null ? signature : "");
            out.writeInt(accessFlags);
        }

        public static MethodStub read(DataInput in) throws IOException {
            MethodStub s = new MethodStub();
            s.name = in.readUTF();
            s.descriptor = in.readUTF();
            s.signature = in.readUTF();
            if (s.signature.isEmpty()) s.signature = null;
            s.accessFlags = in.readInt();
            return s;
        }
    }
}