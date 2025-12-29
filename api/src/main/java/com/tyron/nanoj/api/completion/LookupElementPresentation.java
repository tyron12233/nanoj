package com.tyron.nanoj.api.completion;

import org.jetbrains.annotations.Nullable;

/**
 * A Data Transfer Object (DTO) responsible for rendering a code completion item.
 * <p>
 * This class isolates the "Logic" (LookupElement) from the "UI" (Android View/Swing Component).
 * It supports the standard IntelliJ-like 3-column layout:
 * </p>
 * <pre>
 * [Icon]  Name(TailText)       Type
 * [M]     substring(int, int)  String
 * </pre>
 */
public class LookupElementPresentation {

    // --- 1. Main Item (The Identifier) ---
    private String itemText;
    private boolean isItemTextBold;
    private boolean isItemTextUnderlined;
    private boolean isItemTextStrikeout; // For deprecated items
    private int itemTextColor = 0;

    // --- 2. Tail Text (Parameters, Container info) ---
    private String tailText;
    private boolean isTailTextGrayed = true; // Defaults to true (standard IDE look)
    private boolean isTailTextSmall = false;

    // --- 3. Type Text (Return Type, aligned right) ---
    private String typeText;
    private boolean isTypeTextGrayed = true;
    private String typeIconKey; // Optional icon on the right (rare, but useful)

    // --- 4. Graphics ---
    private String iconKey; // Resource ID/Name for the main icon
    private boolean isIconGrayed; // If the symbol is inaccessible

    // --- 5. State (Helper for UI) ---
    private boolean isFreezed = false; // Prevent modification after rendering

    /**
     * Resets the state of this presentation.
     * Call this before passing the instance to {@link LookupElement#renderElement(LookupElementPresentation)}.
     */
    public void clear() {
        itemText = null;
        isItemTextBold = false;
        isItemTextUnderlined = false;
        isItemTextStrikeout = false;
        itemTextColor = 0;

        tailText = null;
        isTailTextGrayed = true;
        isTailTextSmall = false;

        typeText = null;
        isTypeTextGrayed = true;
        typeIconKey = null;

        iconKey = null;
        isIconGrayed = false;

        isFreezed = false;
    }

    // ===========================
    //         Getters
    // ===========================

    @Nullable
    public String getItemText() { return itemText; }
    public boolean isItemTextBold() { return isItemTextBold; }
    public boolean isItemTextUnderlined() { return isItemTextUnderlined; }
    public boolean isItemTextStrikeout() { return isItemTextStrikeout; }
    public int getItemTextColor() { return itemTextColor; }

    @Nullable
    public String getTailText() { return tailText; }
    public boolean isTailTextGrayed() { return isTailTextGrayed; }
    public boolean isTailTextSmall() { return isTailTextSmall; }

    @Nullable
    public String getTypeText() { return typeText; }
    public boolean isTypeTextGrayed() { return isTypeTextGrayed; }
    @Nullable
    public String getTypeIconKey() { return typeIconKey; }

    @Nullable
    public String getIconKey() { return iconKey; }
    public boolean isIconGrayed() { return isIconGrayed; }

    // ===========================
    //         Setters
    // ===========================

    public void setItemText(@Nullable String text) {
        checkMutable();
        this.itemText = text;
    }

    public void setItemTextBold(boolean bold) {
        checkMutable();
        this.isItemTextBold = bold;
    }

    public void setStrikeout(boolean strikeout) {
        checkMutable();
        this.isItemTextStrikeout = strikeout;
    }

    public void setUnderlined(boolean underlined) {
        checkMutable();
        this.isItemTextUnderlined = underlined;
    }

    /**
     * Set a specific ARGB color. If 0, the UI uses the default theme color.
     */
    public void setItemTextColor(int color) {
        checkMutable();
        this.itemTextColor = color;
    }

    public void setTailText(@Nullable String text) {
        checkMutable();
        this.tailText = text;
    }

    /**
     * Sets the tail text and optionally applies standard gray coloring.
     * @param text The text (e.g. parameters)
     * @param grayed If true, renders in a secondary color.
     */
    public void setTailText(@Nullable String text, boolean grayed) {
        checkMutable();
        this.tailText = text;
        this.isTailTextGrayed = grayed;
    }

    public void setTypeText(@Nullable String text) {
        checkMutable();
        this.typeText = text;
    }

    public void setTypeText(@Nullable String text, boolean grayed) {
        checkMutable();
        this.typeText = text;
        this.isTypeTextGrayed = grayed;
    }

    public void setIconKey(@Nullable String iconKey) {
        checkMutable();
        this.iconKey = iconKey;
    }

    public void setTypeIconKey(@Nullable String iconKey) {
        checkMutable();
        this.typeIconKey = iconKey;
    }

    public void setIconGrayed(boolean grayed) {
        checkMutable();
        this.isIconGrayed = grayed;
    }

    // ===========================
    //       Utility Methods
    // ===========================

    public void copyFrom(LookupElementPresentation other) {
        checkMutable();
        this.itemText = other.itemText;
        this.isItemTextBold = other.isItemTextBold;
        this.isItemTextStrikeout = other.isItemTextStrikeout;
        this.isItemTextUnderlined = other.isItemTextUnderlined;
        this.itemTextColor = other.itemTextColor;

        this.tailText = other.tailText;
        this.isTailTextGrayed = other.isTailTextGrayed;
        this.isTailTextSmall = other.isTailTextSmall;

        this.typeText = other.typeText;
        this.isTypeTextGrayed = other.isTypeTextGrayed;
        this.typeIconKey = other.typeIconKey;

        this.iconKey = other.iconKey;
        this.isIconGrayed = other.isIconGrayed;
    }

    /**
     * Called by the UI after the render pass is complete to prevent tampering.
     */
    public void freeze() {
        this.isFreezed = true;
    }

    private void checkMutable() {
        if (isFreezed) {
            throw new IllegalStateException("LookupElementPresentation is frozen and cannot be modified.");
        }
    }
}