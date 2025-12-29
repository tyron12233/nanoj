package com.tyron.nanoj.lang.java.model;

import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Flags;
import com.tyron.nanoj.api.model.ElementHandle;

import javax.lang.model.element.ElementKind;
import java.util.HashSet;
import java.util.Set;

public class JavacElementHandle implements ElementHandle {

    private final String simpleName;
    private final String qualifiedName;
    private final ElementKind kind;
    private final Set<Modifier> modifiers;
    
    private final String signature;

    public static JavacElementHandle create(Symbol symbol) {
        return new JavacElementHandle(symbol);
    }

    private JavacElementHandle(Symbol symbol) {
        this.simpleName = symbol.name.toString();
        this.qualifiedName = symbol.getQualifiedName().toString();
        this.kind = convertKind(symbol);
        this.modifiers = convertModifiers(symbol.flags());
        this.signature = symbol.toString();
    }

    @Override public String getSimpleName() { return simpleName; }
    @Override public String getQualifiedName() { return qualifiedName; }
    @Override public ElementKind getKind() { return kind; }
    @Override public Set<Modifier> getModifiers() { return modifiers; }

    private ElementKind convertKind(Symbol s) {
        if (s.kind == Kinds.Kind.MTH) return ElementKind.METHOD;
        if (s.kind == Kinds.Kind.PCK) return ElementKind.PACKAGE;

        if (s.kind == Kinds.Kind.VAR) {
            var javacKind = s.getKind();
            if (javacKind == javax.lang.model.element.ElementKind.PARAMETER) return ElementKind.PARAMETER;
            if (javacKind == javax.lang.model.element.ElementKind.LOCAL_VARIABLE
                    || javacKind == javax.lang.model.element.ElementKind.EXCEPTION_PARAMETER
                    || javacKind == javax.lang.model.element.ElementKind.RESOURCE_VARIABLE) {
                return ElementKind.LOCAL_VARIABLE;
            }
            return ElementKind.FIELD;
        }

        if (s.isInterface()) return ElementKind.INTERFACE;
        if (s.isEnum()) return ElementKind.ENUM;
        return ElementKind.CLASS;
    }

    private Set<Modifier> convertModifiers(long flags) {
        Set<Modifier> mods = new HashSet<>();
        if ((flags & Flags.PUBLIC) != 0) mods.add(Modifier.PUBLIC);
        if ((flags & Flags.PRIVATE) != 0) mods.add(Modifier.PRIVATE);
        if ((flags & Flags.STATIC) != 0) mods.add(Modifier.STATIC);
        if ((flags & Flags.DEPRECATED) != 0) mods.add(Modifier.DEPRECATED);
        if ((flags & Flags.ABSTRACT) != 0) mods.add(Modifier.ABSTRACT);
        if ((flags & Flags.PROTECTED) != 0) mods.add(Modifier.PROTECTED);
        if ((flags & Flags.FINAL) != 0) mods.add(Modifier.FINAL);
        return mods;
    }
}