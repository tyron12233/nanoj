package com.tyron.nanoj.lang.java.completion;

import com.tyron.nanoj.core.editor.FileDocumentManagerImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaCompletionTypingSimulationTest extends BaseJavaTypingCompletionTest {

    @Test
    void completionsTrackInMemoryEditsWhileTyping() throws Exception {
        String withCaret = """
                package p;
                public class Foo {
                  void test() {
                    Str<caret> s;
                  }
                }
                """;

        try (TypingSession s = typingSession("p.Foo", withCaret)) {
            assertHasLookup(s.complete().items, "String");

            s.type("i");
            assertTrue(FileDocumentManagerImpl.getInstance(project).isModified(s.document), "Typing should mark the document modified");
            assertHasLookup(s.complete().items, "String");

            s.type("x");
            assertNoLookup(s.complete().items, "String");

            s.backspace();
            assertHasLookup(s.complete().items, "String");
        }
    }
}
