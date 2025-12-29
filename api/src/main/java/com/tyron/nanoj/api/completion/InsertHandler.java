package com.tyron.nanoj.api.completion;

/**
 * Interface for custom logic during code completion insertion.
 * <p>
 * Use this to handle side effects like adding imports, formatting arguments,
 * or moving the caret.
 * </p>
 */
@FunctionalInterface
public interface InsertHandler<T extends LookupElement> {
    /**
     * @param context The context containing the editor, document, and offsets.
     * @param item    The item that is being inserted.
     */
    void handleInsert(InsertionContext context, T item);
}