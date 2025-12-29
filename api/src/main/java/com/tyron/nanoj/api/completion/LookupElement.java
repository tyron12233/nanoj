package com.tyron.nanoj.api.completion;

/**
 * Represents a single item in the completion popup.
 * Modeled after IntelliJ's LookupElement.
 */
public abstract class LookupElement {

    private final Object object; // Usually an ElementHandle

    /**
     * Optional priority used by completion sorting.
     * Higher values appear earlier.
     */
    private int priority;

    protected LookupElement(Object object) {
        this.object = object;
    }

    public final int getPriority() {
        return priority;
    }

    public final void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * The string used for filtering/matching what the user typed.
     */
    public abstract String getLookupString();

    /**
     * Called by the UI to render the list item.
     */
    public abstract void renderElement(LookupElementPresentation presentation);

    /**
     * Called when the user selects this item.
     */
    public abstract void handleInsert(InsertionContext context);

    public Object getObject() {
        return object;
    }
}