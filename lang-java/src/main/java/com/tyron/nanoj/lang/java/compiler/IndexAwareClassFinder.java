package com.tyron.nanoj.lang.java.compiler;

import com.sun.tools.javac.code.ClassFinder;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.tyron.nanoj.api.indexing.IndexManager;
import com.tyron.nanoj.api.project.Project;
import com.tyron.nanoj.lang.java.indexing.JavaBinaryStubIndexer;
import com.tyron.nanoj.lang.java.indexing.stub.ClassStub;

import javax.tools.JavaFileObject;
import java.util.List;

/**
 * Custom Javac ClassFinder that checks the NanoJ Index before hitting the file system.
 */
public class IndexAwareClassFinder extends ClassFinder {

    private final IndexManager indexManager;
    private final Symtab symtab;
    private final Context context;

    /**
     * Factory method to register this finder in the Context.
     */
    public static void preRegister(Context context, Project project) {
        context.put(ClassFinder.classFinderKey, (Context.Factory<ClassFinder>) c -> new IndexAwareClassFinder(c, project));
    }

    protected IndexAwareClassFinder(Context context, Project project) {
        super(context);
        this.context = context;
        this.symtab = Symtab.instance(context);
        this.indexManager = IndexManager.getInstance();
    }

    /**
     * This is the main entry point Javac uses to find a class.
     */
    @Override
    public ClassSymbol loadClass(Symbol.ModuleSymbol msym, Name name) {
        ClassSymbol c = symtab.getClass(msym, name);
        if (c != null) {
            // if it exists but isn't completed, Javac will handle calling the completer
            return c;
        }

        String fqn = name.toString();
        List<ClassStub> stubs = indexManager.search(JavaBinaryStubIndexer.ID, fqn);
        
        if (!stubs.isEmpty()) {
            ClassStub stub = stubs.get(0);

            c = symtab.enterClass(msym, name);
            c.completer = new StubCompleter(context, stub);
            c.classfile = new StubJavaFileObject(stub);
            
            return c;
        }

        return super.loadClass(msym, name);
    }
}