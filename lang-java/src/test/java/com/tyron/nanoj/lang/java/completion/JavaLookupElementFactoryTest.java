package com.tyron.nanoj.lang.java.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.tyron.nanoj.api.completion.LookupElement;
import com.tyron.nanoj.api.completion.LookupElementPresentation;
import com.tyron.nanoj.api.model.ElementHandle;
import com.tyron.nanoj.lang.java.model.JavacElementHandle;
import com.tyron.nanoj.lang.java.source.JavaSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import java.io.IOException;
import java.util.List;

public class JavaLookupElementFactoryTest extends BaseJavaCompletionTest {

    @Test
    public void keywordRendering_hasKeywordIconAndBoldText() {
        var kw = JavaLookupElementFactory.keyword("class");
        var p = render(kw);

        Assertions.assertEquals("class", p.getItemText());
        Assertions.assertEquals("keyword", p.getIconKey());
        Assertions.assertTrue(p.isItemTextBold(), "Keywords should be bold");
    }

    @Test
    public void methodRendering_includesParamsOwnerAndReturnType() throws IOException {
        var file = java("com.example.A",
                "package com.example;\n" +
                "import java.util.List;\n" +
                "public class A {\n" +
                "  public List<String> foo(int x, String y) { return null; }\n" +
                "}\n");

        JavaSource source = JavaSource.forFile(project, file);

        LookupElement element = source.runUserActionTask(file.getText(), info -> {
            var method = findEnclosedSymbol(info.getTask().getElements().getTypeElement("com.example.A"), "foo", ElementKind.METHOD);
            return JavaLookupElementFactory.forSymbol((Symbol) method);
        }).join();

        Assertions.assertNotNull(element);
        LookupElementPresentation p = render(element);

        Assertions.assertEquals("foo", p.getItemText());
        Assertions.assertEquals("method", p.getIconKey());
        Assertions.assertTrue(p.getTailText().startsWith("("), "Tail should start with params");
        Assertions.assertTrue(p.getTailText().contains("int x"), "Should include parameter types/names");
        Assertions.assertTrue(p.getTailText().contains("String y"), "Should include parameter types/names");
        Assertions.assertTrue(p.getTailText().contains("A"), "Tail should contain owner hint");
        Assertions.assertEquals("List<String>", p.getTypeText());
    }

    @Test
    public void fieldRendering_includesTypeAndOwner() throws IOException {
        var file = java("com.example.A",
                "package com.example;\n" +
                "public class A {\n" +
                "  public String field;\n" +
                "}\n");

        JavaSource source = JavaSource.forFile(project, file);

        LookupElement element = source.runUserActionTask(file.getText(), info -> {
            var field = findEnclosedSymbol(info.getTask().getElements().getTypeElement("com.example.A"), "field", ElementKind.FIELD);
            return JavaLookupElementFactory.forSymbol((Symbol) field);
        }).join();

        LookupElementPresentation p = render(element);
        Assertions.assertEquals("field", p.getItemText());
        Assertions.assertEquals("field", p.getIconKey());
        Assertions.assertEquals("String", p.getTypeText());
        Assertions.assertTrue(p.getTailText().contains("A"), "Tail should contain owner hint");
    }

    @Test
    public void constructorRendering_usesClassNameAndConstructorIcon() throws IOException {
        var file = java("com.example.A",
                "package com.example;\n" +
                "public class A {\n" +
                "  public A(int x) {}\n" +
                "}\n");

        JavaSource source = JavaSource.forFile(project, file);

        LookupElement element = source.runUserActionTask(file.getText(), info -> {
            var ctor = findFirstConstructor(info.getTask().getElements().getTypeElement("com.example.A"));
            return JavaLookupElementFactory.forSymbol((Symbol) ctor);
        }).join();

        LookupElementPresentation p = render(element);
        Assertions.assertEquals("A", p.getItemText());
        Assertions.assertEquals("constructor", p.getIconKey());
        Assertions.assertTrue(p.getTailText().contains("int x"));
        Assertions.assertNull(p.getTypeText(), "Constructors should not show a return type");
    }

    @Test
    public void elementHandle_kindMapping_parameterAndLocalVariable() throws IOException {
        var file = java("com.example.A",
                "package com.example;\n" +
                "public class A {\n" +
                "  void m(String p) { int local = 0; }\n" +
                "}\n");

        JavaSource source = JavaSource.forFile(project, file);

        var kinds = source.runUserActionTask(file.getText(), info -> {
            Trees trees = Trees.instance(info.getTask());
            CompilationUnitTree unit = info.getCompilationUnit();

            // Parameter symbol via method parameters.
            Element method = findEnclosedSymbol(info.getTask().getElements().getTypeElement("com.example.A"), "m", ElementKind.METHOD);
            Symbol.MethodSymbol ms = (Symbol.MethodSymbol) method;
            Symbol.VarSymbol param = ms.getParameters().get(0);

            // Local variable symbol via AST scan.
            VariableTree localVarTree = (VariableTree) ((MethodTree) ((ClassTree) unit.getTypeDecls().get(0)).getMembers().get(1)).getBody().getStatements().get(0);
            Element localEl = trees.getElement(TreePath.getPath(unit, localVarTree));

            JavacElementHandle paramHandle = JavacElementHandle.create(param);
            JavacElementHandle localHandle = JavacElementHandle.create((Symbol) localEl);

            return List.of(paramHandle.getKind(), localHandle.getKind());
        }).join();

        Assertions.assertEquals(ElementHandle.ElementKind.PARAMETER, kinds.get(0));
        Assertions.assertEquals(ElementHandle.ElementKind.LOCAL_VARIABLE, kinds.get(1));
    }

    private static Element findEnclosedSymbol(Element type, String name, ElementKind kind) {
        Assertions.assertNotNull(type, "Type element must resolve");
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == kind && e.getSimpleName() != null && name.equals(e.getSimpleName().toString())) {
                return e;
            }
        }
        throw new AssertionError("Missing enclosed element: " + kind + " " + name);
    }

    private static Element findFirstConstructor(Element type) {
        Assertions.assertNotNull(type, "Type element must resolve");
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.CONSTRUCTOR) {
                return e;
            }
        }
        throw new AssertionError("Missing constructor");
    }

    private static final class FindLocalVarTree extends TreePathScanner<VariableTree, Void> {
        private final String name;

        private FindLocalVarTree(String name) {
            this.name = name;
        }

        @Override
        public VariableTree visitVariable(VariableTree node, Void unused) {
            if (node.getName() != null && name.equals(node.getName().toString())) {
                return node;
            }
            return super.visitVariable(node, unused);
        }
    }
}
