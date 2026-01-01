package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.api.indexing.IndexDefinition;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.api.vfs.VirtualFileManager;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class IndexManagerJarTraversalTest extends BaseIdeTest {

    @Test
    public void updateFileAsyncOnJarTraversesAndIndexesChildren() throws Exception {
        IndexManagerImpl manager = (IndexManagerImpl) IndexManager.getInstance();
        manager.register(new ClassNameIndex(manager));

        byte[] jarBytes = makeJar(
                "p/A.class", new byte[]{1, 2, 3},
                "p/q/B.class", new byte[]{4, 5}
        );

        String jarPath = project.getRootDirectory().getPath() + "/libs/test.jar";
        MockFileObject jarFo = new MockFileObject(jarPath, jarBytes);
        jarFo.setFolder(true);

        testVfs.registerFile(jarFo);
        project.addLibrary(jarFo);

        Future<?> f = CompletableFuture.runAsync(() -> manager.processRoots(List.of(jarFo)));
        f.get();
        manager.flush();

        // The traversal + batch indexing should have indexed jar entries.
        List<String> a = manager.search("class_name_index", "A.class");
        List<String> b = manager.search("class_name_index", "B.class");

        Assertions.assertEquals(1, a.size());
        Assertions.assertEquals(1, b.size());
        Assertions.assertTrue(a.get(0).contains("!/p/A.class"), a.toString());
        Assertions.assertTrue(b.get(0).contains("!/p/q/B.class"), b.toString());

        // Sanity: jar root itself should also be resolvable.
        URI jarRootUri = URI.create("jar:" + jarFo.toUri() + "!/");
        FileObject jarRoot = VirtualFileManager.getInstance().find(jarRootUri);
        Assertions.assertTrue(jarRoot.isFolder());
    }

    private static final class ClassNameIndex implements IndexDefinition<String, String> {
        private final IndexManager manager;

        private ClassNameIndex(IndexManager manager) {
            this.manager = manager;
        }

        @Override
        public String id() {
            return "class_name_index";
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public boolean supports(FileObject fileObject) {
            return fileObject != null && "class".equalsIgnoreCase(fileObject.getExtension());
        }

        @Override
        public Map<String, String> map(FileObject file, Object helper) {
            Map<String, String> out = new HashMap<>();
            out.put(file.getName(), file.getPath());
            return out;
        }

        @Override
        public boolean isValueForFile(String value, int fileId) {
            String pathForId = VirtualFileManager.getInstance().findById(fileId).getPath();
            return value != null && value.equals(pathForId);
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

    private static byte[] makeJar(Object... pathAndBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < pathAndBytes.length; i += 2) {
                String path = (String) pathAndBytes[i];
                byte[] bytes = (byte[]) pathAndBytes[i + 1];

                ZipEntry e = new ZipEntry(path);
                zout.putNextEntry(e);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
