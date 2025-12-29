package com.tyron.nanoj.lang.java.completions;


public enum JavaSortCategory {

    /**
     * A symbol within the local scope, e.g inside a method
     */
    LOCAL_VARIABLE,

    /**
     * A symbol directly defined in this class, e.g a field or a method from the class
     */
    DIRECT_MEMBER,

    /**
     * A symbol accessible from the current scope but is not a direct member, e.g a field or method
     * from a super class
     */
    ACCESSIBLE_SYMBOL,

    /**
     * Any other symbols that are not categorized above
     */
    UNKNOWN,

    /**
     * Java keywords
     */
    KEYWORD,

    /**
     * Symbols that are not yet accessible but will be accessible once imported
     */
    TO_IMPORT;

    @Override
    public String toString() {
        return String.valueOf(ordinal());
    }
}