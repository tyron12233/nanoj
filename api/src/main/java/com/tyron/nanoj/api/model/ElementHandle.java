package com.tyron.nanoj.api.model;

import java.util.Set;

/**
 * A thread-safe, lightweight reference to a language element (Class, Method, Field).
 * Unlike Javac Symbols, this can be safely passed to the UI thread.
 */
public interface ElementHandle {
    
    String getSimpleName();
    
    /**
     * @return The fully qualified name or unique signature.
     */
    String getQualifiedName();
    
    ElementKind getKind();
    
    Set<Modifier> getModifiers();

    enum ElementKind {
        CLASS, INTERFACE, ENUM, METHOD, FIELD, PARAMETER, LOCAL_VARIABLE, PACKAGE, MODULE
    }

    enum Modifier {
        PUBLIC, PRIVATE, PROTECTED, STATIC, FINAL, ABSTRACT, DEPRECATED
    }
}