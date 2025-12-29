package com.tyron.nanoj.core.completion;

import com.tyron.nanoj.api.completion.CompletionParameters;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementWeigher;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.core.service.ProjectServiceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sorts completion items using registered {@link LookupElementWeigher}s.
 */
public final class LookupElementSorting {

    private LookupElementSorting() {
    }

    public static List<LookupElement> sort(Project project, CompletionParameters parameters, List<LookupElement> items) {
        if (items == null || items.size() <= 1) {
            return items;
        }

        List<LookupElementWeigher> weighers = ProjectServiceManager.getExtensions(project, LookupElementWeigher.class);
        if (weighers.isEmpty()) {
            return sortByPriorityThenName(items);
        }

        int exactPrefixIdx = -1;
        for (int i = 0; i < weighers.size(); i++) {
            try {
                if ("exactPrefix".equals(weighers.get(i).id())) {
                    exactPrefixIdx = i;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        Map<LookupElement, int[]> cache = new IdentityHashMap<>();
        int finalExactPrefixIdx = exactPrefixIdx;
        Comparator<LookupElement> comparator = (a, b) -> {
            int[] wa = cache.computeIfAbsent(a, el -> weighAll(weighers, parameters, el));
            int[] wb = cache.computeIfAbsent(b, el -> weighAll(weighers, parameters, el));

            // Exact match should win even against higher-priority items.
            if (finalExactPrefixIdx >= 0) {
                int cmp = Integer.compare(wb[finalExactPrefixIdx], wa[finalExactPrefixIdx]);
                if (cmp != 0) return cmp;
            }

            int prio = Integer.compare(b.getPriority(), a.getPriority());
            if (prio != 0) return prio;

            for (int i = 0; i < wa.length; i++) {
                if (i == finalExactPrefixIdx) continue;
                int cmp = Integer.compare(wb[i], wa[i]);
                if (cmp != 0) return cmp;
            }

            return a.getLookupString().compareToIgnoreCase(b.getLookupString());
        };

        ArrayList<LookupElement> out = new ArrayList<>(items);
        out.sort(comparator);
        return out;
    }

    private static int[] weighAll(List<LookupElementWeigher> weighers, CompletionParameters parameters, LookupElement el) {
        int[] weights = new int[weighers.size()];
        for (int i = 0; i < weighers.size(); i++) {
            weights[i] = weighers.get(i).weigh(parameters, el);
        }
        return weights;
    }

    private static List<LookupElement> sortByPriorityThenName(List<LookupElement> items) {
        ArrayList<LookupElement> out = new ArrayList<>(items);
        out.sort(
                Comparator.<LookupElement>comparingInt(LookupElement::getPriority).reversed()
                        .thenComparing(e -> e.getLookupString().toLowerCase())
        );
        return out;
    }
}
