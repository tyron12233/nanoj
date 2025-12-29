package com.tyron.nanoj.lang.java.compiler;

import com.tyron.nanoj.lang.java.indexing.stub.ClassStub;
import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import java.io.*;

public class StubJavaFileObject extends SimpleJavaFileObject {

    private final ClassStub stub;

    public StubJavaFileObject(ClassStub stub) {
        super(URI.create("stub:///" + stub.name), Kind.CLASS);
        this.stub = stub;
    }

    public ClassStub getStub() {
        return stub;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        throw new UnsupportedOperationException("StubJavaFileObject does not have bytes! intercept logic failed.");
    }
}