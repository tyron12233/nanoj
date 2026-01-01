package com.tyron.nanoj.lang.java.source;

import com.google.common.truth.Truth;
import com.sun.tools.javac.tree.JCTree;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.core.indexing.IndexManagerImpl;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.lang.java.compiler.CompilationInfo;
import com.tyron.nanoj.lang.java.compiler.JavacFileManagerService;
import com.tyron.nanoj.lang.java.indexing.JavaBinaryStubIndexer;
import com.tyron.nanoj.lang.java.indexing.JavaPackageIndex;
import com.tyron.nanoj.lang.java.indexing.JavaSuperTypeIndex;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class SourceTest extends BaseIdeTest {

    @Override
    protected void beforeEach() {
        var indexManager = IndexManager.getInstance();
        indexManager.register(new JavaBinaryStubIndexer(project));
        indexManager.register(new JavaPackageIndex(project));
        indexManager.register(new JavaSuperTypeIndex(project));

        ProjectServiceManager.registerInstance(
                project,
                JavacFileManagerService.class,
                new JavacFileManagerService(project)
        );

        MockFileObject libsDir = dir("libs");
        project.addLibrary(libsDir);
    }

    @Override
    protected void afterEach() {
        IndexManager.getInstance().dispose();
    }

    @Test
    public void test() throws IOException {
        MockFileObject file = java("Test", "class Test {}");


        JavaSource source = JavaSource.forFile(project, file);
        var firstFuture = source.runUserActionTask(file.getText(), CompilationInfo::getCompilationUnit);
        var secondFuture = source.runUserActionTask(file.getText(), CompilationInfo::getCompilationUnit);

        JCTree.JCCompilationUnit join = secondFuture.join();
        Truth.assertThat(firstFuture.isCancelled());
        Truth.assertThat(join).isNotNull();
    }

    @Test
    @DisplayName("ParsingManager should cache JavacTask for identical content")
    void testCaching() throws Exception {
        MockFileObject file = java("Test", "class Test {}");

        JavaSource source = JavaSource.forFile(project, file);
        String code = "class Cache {}";

        int id1 = source.runModificationTask(code, System::identityHashCode).get();

        // Second run (Same text)
        int id2 = source.runModificationTask(code, System::identityHashCode).get();

        // Third run (Different text)
        int id3 = source.runModificationTask(code + " ", System::identityHashCode).get();

        assertEquals(id1, id2, "Should reuse CompilationInfo object for same text");
        assertNotEquals(id1, id3, "Should create new object for different text");
    }
}
