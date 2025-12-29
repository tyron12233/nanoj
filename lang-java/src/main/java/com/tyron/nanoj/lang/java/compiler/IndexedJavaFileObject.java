package com.tyron.nanoj.lang.java.compiler;

import com.tyron.nanoj.api.vfs.FileObject;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.JavaFileObject;
import java.io.*;
import java.net.URI;

public class IndexedJavaFileObject implements JavaFileObject {

    private final FileObject fileObject;
    private final Kind kind;

    private final String binaryName;

    public IndexedJavaFileObject(FileObject fileObject, Kind kind, String binaryName) {
        this.fileObject = fileObject;
        this.kind = kind;
        this.binaryName = binaryName;
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        String baseName = simpleName + kind.extension;
        return fileObject.getName().equals(baseName);
    }

    @Override
    public NestingKind getNestingKind() {
        return null;
    }

    @Override
    public Modifier getAccessLevel() {
        return null;
    }

    @Override
    public URI toUri() {
        return fileObject.toUri();
    }

    @Override
    public String getName() {
        return fileObject.getPath();
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return fileObject.getInputStream();
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        throw new UnsupportedOperationException("Indexed files are read-only for Javac");
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
        return new InputStreamReader(openInputStream());
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
        return fileObject.getText();
    }

    @Override
    public Writer openWriter() throws IOException {
        throw new UnsupportedOperationException("Indexed files are read-only for Javac");
    }

    @Override
    public long getLastModified() {
        return fileObject.lastModified();
    }

    @Override
    public boolean delete() {
        return false;
    }
    
    public String inferBinaryName() {
        return binaryName;
    }
}