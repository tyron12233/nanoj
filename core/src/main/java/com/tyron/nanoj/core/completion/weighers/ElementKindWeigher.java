package com.tyron.nanoj.core.completion.weighers;

import com.tyron.nanoj.api.completion.CompletionParameters;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementWeigher;
import com.tyron.nanoj.api.model.ElementHandle;
import com.tyron.nanoj.api.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Prefers items that are "more local" to the user.
 *
 * Typical ordering (high -> low):
 * local variable, parameter, field, method, type, package.
 */
public final class ElementKindWeigher implements LookupElementWeigher {

    public ElementKindWeigher(Project project) {
    }

    @Override
    public @NotNull String id() {
        return "elementKind";
    }

    @Override
    public int weigh(@NotNull CompletionParameters parameters, @NotNull LookupElement element) {
        Object obj = element.getObject();
        if (!(obj instanceof ElementHandle handle)) {
            return 0;
        }

        return switch (handle.getKind()) {
            case LOCAL_VARIABLE -> 1000;
            case PARAMETER -> 950;
            case FIELD -> 800;
            case METHOD -> 700;
            case CLASS, INTERFACE, ENUM -> 600;
            case PACKAGE -> 400;
            case MODULE -> 300;
        };
    }
}
