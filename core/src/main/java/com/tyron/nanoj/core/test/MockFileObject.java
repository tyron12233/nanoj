package com.tyron.nanoj.core.test;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.vfs.FileObjectWithId;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MockFileObject implements FileObject {
    private String name;
    private String content;
    private final String path;
    private byte[] byteContent;

    private boolean isFolder;


    public MockFileObject(String path, byte[] content) {
        this.path = path;
        this.byteContent = content;
        this.name = new File(path).getName();
    }


    public MockFileObject(String path, String content) {
        this.path = path;
        this.content = content;
        this.name = new File(path).getName();
    }

    public void setContent(String content) { this.content = content; }

    @Override public String getName() { return name; }
    @Override public String getExtension() { return name.contains(".") ? name.substring(name.lastIndexOf(".") + 1) : ""; }
    @Override public String getPath() { return path; }
    @Override public URI toUri() { return new File(path).toURI(); }
    @Override public FileObject getParent() { return null; }
    @Override public List<FileObject> getChildren() { return Collections.emptyList(); }
    @Override public FileObject getChild(String name) { return null; }
    @Override public boolean exists() { return true; }
    @Override public boolean isFolder() { return isFolder; }

    public void setFolder(boolean folder) {
        isFolder = folder;
    }

    @Override public boolean isReadOnly() { return false; }
    @Override public long lastModified() { return System.currentTimeMillis(); }

    public void setByteContent(byte[] content) {
        this.byteContent = content;
    }

    @Override
    public InputStream getInputStream() {
        if (byteContent != null) return new ByteArrayInputStream(byteContent);
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public long getLength() {
        if (byteContent != null) return byteContent.length;
        return content != null ? content.length() : 0;
    }

    @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
    @Override public FileObject createFile(String name) { throw new UnsupportedOperationException(); }
    @Override public FileObject createFolder(String name) { throw new UnsupportedOperationException(); }
    @Override public void delete() {}
    @Override public void rename(String newName) {}
    @Override public void refresh() {}
}