package com.tyron.nanoj.core.completion;

import com.tyron.nanoj.api.completion.CompletionParameters;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementBuilder;
import com.tyron.nanoj.testFramework.BaseCompletionTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LookupElementSortingExactMatchTest extends BaseCompletionTest {

    @Test
    void exactMatchBeatsHigherPriority() {
        // typed prefix: "foo"
        CompletionParameters p = new CompletionParameters(project, null, "foo", 3);

        LookupElement exact = LookupElementBuilder.create("foo").withPriority(0);
        LookupElement highPriorityNonExact = LookupElementBuilder.create("foobar").withPriority(10_000);

        List<LookupElement> sorted = LookupElementSorting.sort(project, p, List.of(highPriorityNonExact, exact));

        assertEquals("foo", sorted.get(0).getLookupString());
        assertEquals("foobar", sorted.get(1).getLookupString());
    }

    @Test
    void priorityStillAppliesWhenNoExact() {
        // typed prefix: "fo"
        CompletionParameters p = new CompletionParameters(project, null, "fo", 2);

        LookupElement a = LookupElementBuilder.create("foo").withPriority(0);
        LookupElement b = LookupElementBuilder.create("fork").withPriority(100);

        List<LookupElement> sorted = LookupElementSorting.sort(project, p, List.of(a, b));

        assertEquals("fork", sorted.get(0).getLookupString());
        assertEquals("foo", sorted.get(1).getLookupString());
    }
}
