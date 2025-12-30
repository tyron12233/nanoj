package com.tyron.nanoj.core.vfs;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

public class JrtFileSystemTest extends BaseIdeTest {

    @Test
    public void jrtUriResolvesJavaBaseClass() throws Exception {
        // JDK8 doesn't have jrt:.
        Assumptions.assumeTrue(isJrtAvailable());

        FileObject stringClass = VirtualFileManager.getInstance()
                .find(URI.create("jrt:/modules/java.base/java/lang/String.class"));

        assertTrue(stringClass.exists());
        assertFalse(stringClass.isFolder());
        assertEquals("class", stringClass.getExtension());
        assertTrue(stringClass.getLength() > 0);
        assertNotNull(stringClass.getParent());
        assertTrue(stringClass.getParent().isFolder());
    }

    private static boolean isJrtAvailable() {
        try {
            // Will throw on JDK8.
            JrtFileSystem.getOrCreateJrtNioFs();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
