package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class JarFileSystemTest extends BaseIdeTest {

    @Test
    public void jarUriResolvesAndListsEntries() throws Exception {
        byte[] jarBytes = makeJar(
                "p/A.class", new byte[]{1, 2, 3},
                "p/q/B.class", new byte[]{4, 5}
        );

        String jarPath = project.getRootDirectory().getPath() + "/libs/test.jar";
        MockFileObject jarFo = new MockFileObject(jarPath, jarBytes);
        testVfs.registerFile(jarFo);
        project.addLibrary(jarFo);

        URI jarRootUri = URI.create("jar:" + jarFo.toUri() + "!/");
        FileObject jarRoot = VirtualFileManager.getInstance().find(jarRootUri);

        Assertions.assertTrue(jarRoot.exists());
        Assertions.assertTrue(jarRoot.isFolder());

        // Root should contain "p"
        List<FileObject> rootKids = jarRoot.getChildren();
        Assertions.assertFalse(rootKids.isEmpty());
        Assertions.assertEquals("p", rootKids.get(0).getName());

        FileObject pDir = jarRoot.getChild("p");
        Assertions.assertNotNull(pDir);
        Assertions.assertTrue(pDir.isFolder());

        FileObject a = pDir.getChild("A.class");
        Assertions.assertNotNull(a);
        Assertions.assertEquals("class", a.getExtension());
        Assertions.assertArrayEquals(new byte[]{1, 2, 3}, a.getContent());

        FileObject q = pDir.getChild("q");
        Assertions.assertNotNull(q);
        Assertions.assertTrue(q.isFolder());

        FileObject b = q.getChild("B.class");
        Assertions.assertNotNull(b);
        Assertions.assertArrayEquals(new byte[]{4, 5}, b.getContent());
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
