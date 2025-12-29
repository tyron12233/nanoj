package com.tyron.nanoj.lang.java.compiler;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.tyron.nanoj.lang.java.indexing.stub.ClassStub;

import static com.sun.tools.javac.code.Symbol.*;

public class StubCompleter implements Completer {

    private final Symtab symtab;
    private final Names names;
    private final ClassStub stub;
    private final StubTypeResolver typeResolver;

    public StubCompleter(Context context, ClassStub stub) {
        this.symtab = Symtab.instance(context);
        this.names = Names.instance(context);
        this.stub = stub;
        this.typeResolver = new StubTypeResolver(context, symtab, names);
    }

    @Override
    public void complete(Symbol sym) throws CompletionFailure {
        ClassSymbol c = (ClassSymbol) sym;

        // 1. Basic Flags
        c.flags_field = stub.accessFlags;
        c.sourcefile = null; // or create a SimpleJavaFileObject from stub.sourceFile

        // 2. The Critical Fix: Construct c.type
        // We must do this BEFORE filling members, as members might refer to the class's own type parameters (e.g. List<T>)
        if (stub.signature != null) {
            // Complex case: Class has Generics (e.g. class Map<K,V>)
            // We delegate to typeResolver to parse "<K:Object;V:Object>..."
            // This sets c.type to a ClassType containing TypeVars
            typeResolver.resolveClassSignature(c, stub.signature);
        } else {
            // Simple case: No Generics
            c.type = new Type.ClassType(Type.noType, List.nil(), c);
        }

        // 3. Super Class & Interfaces
        // If signature was present, resolveClassSignature already handled super/interfaces from the signature string.
        // If NO signature (legacy or simple class), we read from the stub fields.
        if (stub.signature == null) {
            if (stub.superName != null) {
                ((Type.ClassType) c.type).supertype_field = typeResolver.resolveType(stub.superName);
            } else {
                ((Type.ClassType) c.type).supertype_field = symtab.objectType;
            }

            List<Type> interfaces = List.nil();
            if (stub.interfaces != null) {
                for (String iface : stub.interfaces) {
                    interfaces = interfaces.prepend(typeResolver.resolveType(iface));
                }
            }
            // Update the Type's interfaces
            ((Type.ClassType) c.type).interfaces_field = interfaces;
        }

        // 4. Fill Members (Fields/Methods)
        fillMembers(c);

        // 5. Done
        c.completer = null;
    }

    private void fillMembers(ClassSymbol c) {
        WriteableScope scope = WriteableScope.create(c);
        c.members_field = scope;

        for (ClassStub.FieldStub fs : stub.fields) {
            Name name = names.fromString(fs.name);
            // Use signature if available (for generics), else descriptor
            String typeStr = (fs.signature != null) ? fs.signature : fs.descriptor;
            Type type = typeResolver.resolveDescriptor(typeStr);

            VarSymbol v = new VarSymbol(fs.accessFlags, name, type, c);
            scope.enter(v);
        }

        for (ClassStub.MethodStub ms : stub.methods) {
            Name name = names.fromString(ms.name);
            String typeStr = (ms.signature != null) ? ms.signature : ms.descriptor;
            Type type = typeResolver.resolveMethodDescriptor(typeStr);

            var m = new MethodSymbol(ms.accessFlags, name, type, c);
            scope.enter(m);
        }
    }
}