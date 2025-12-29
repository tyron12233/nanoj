package com.tyron.nanoj.api.completion;

import com.tyron.nanoj.api.model.ElementHandle;
import java.util.Objects;

/**
 * A standard, generic implementation of {@link LookupElement} that uses a builder pattern.
 * <p>
 * This is the preferred way to create completion items in the API.
 * </p>
 */
public final class LookupElementBuilder extends LookupElement {

    private final String lookupString;
    private String itemText;        // The main text shown (defaults to lookupString)
    private String typeText;        // The right-aligned gray text (e.g. "String")
    private String tailText;        // Text right after the main text (e.g. "(params)")
    private String iconKey;         // Icon resource ID

    private boolean isBold;
    private boolean isStrikeout;

    private InsertHandler<LookupElement> insertHandler;
    private boolean tailGrayed;


    public static LookupElementBuilder create(String lookupString) {
        return new LookupElementBuilder(lookupString, lookupString);
    }

    public static LookupElementBuilder create(Object object, String lookupString) {
        return new LookupElementBuilder(object, lookupString);
    }

    /**
     * specialized factory for ElementHandle to auto-populate basic info.
     */
    public static LookupElementBuilder create(ElementHandle handle) {
        return new LookupElementBuilder(handle, handle.getSimpleName())
                .withIcon(handle.getKind().name().toLowerCase()) // naive icon mapping
                .withStrikeout(handle.getModifiers().contains(ElementHandle.Modifier.DEPRECATED));
    }

    private LookupElementBuilder(Object object, String lookupString) {
        super(object);
        this.lookupString = Objects.requireNonNull(lookupString);
        this.itemText = lookupString;
    }

    /**
     * Sets the text displayed in the list. Defaults to the lookup string.
     */
    public LookupElementBuilder withPresentableText(String text) {
        this.itemText = text;
        return this;
    }

    /**
     * Sets the type text shown on the right (e.g. "String", "void").
     */
    public LookupElementBuilder withTypeText(String typeText) {
        this.typeText = typeText;
        return this;
    }

    /**
     * Sets text shown immediately after the item text (e.g. method parameters).
     */
    public LookupElementBuilder withTailText(String tailText) {
        this.tailText = tailText;
        return this;
    }

    public LookupElementBuilder withIcon(String iconKey) {
        this.iconKey = iconKey;
        return this;
    }

    public LookupElementBuilder withBoldness(boolean bold) {
        this.isBold = bold;
        return this;
    }

    public LookupElementBuilder withStrikeout(boolean strikeout) {
        this.isStrikeout = strikeout;
        return this;
    }

    /**
     * Sets sorting priority for this item.
     * Higher values appear earlier.
     */
    public LookupElementBuilder withPriority(int priority) {
        setPriority(priority);
        return this;
    }

    /**
     * Sets a custom handler for insertion events.
     */
    public LookupElementBuilder withInsertHandler(InsertHandler<LookupElement> handler) {
        this.insertHandler = handler;
        return this;
    }

    public LookupElementBuilder withTailText(String text, boolean grayed) {
        this.tailText = text;
        this.tailGrayed = grayed;
        return this;
    }

    // --- LookupElement Implementation ---

    @Override
    public String getLookupString() {
        return lookupString;
    }

    @Override
    public void renderElement(LookupElementPresentation p) {
        p.setItemText(itemText);
        p.setStrikeout(isStrikeout);
        p.setItemTextBold(isBold);
        p.setIconKey(iconKey);

        // Extensive fields
        p.setTailText(tailText, tailGrayed);
        p.setTypeText(typeText, true);
    }

    @Override
    public void handleInsert(InsertionContext context) {
        context.getDocument().replace(context.getStartOffset(), context.getTailOffset(), lookupString);

        int endOffset = context.getStartOffset() + lookupString.length();
        context.setSelectionEndOffset(endOffset);
        context.setTailOffset(endOffset);

        if (insertHandler != null) {
            insertHandler.handleInsert(context, this);
        }
    }

    @Override
    public String toString() {
        return itemText;
    }
}