package com.tyron.nanoj.api.editor;

/**
 * A data object representing a range of text to style.
 */
public record TokenSpan(int start, int length, TokenType type) {

    public enum TokenType {
        KEYWORD, TYPE, STRING, COMMENT, FIELD, METHOD, ERROR
    }
}
