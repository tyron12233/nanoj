package com.tyron.nanoj.core.indexing;

import com.tyron.nanoj.api.vfs.FileObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort filter for JRT (JDK module image) indexing.
 *
 * Skips indexing classes from packages that are not exported from their module.
 * Those packages are not accessible from normal source code (unnamed module).
 */
final class JrtExportFilter {

    private static volatile Map<String, Set<String>> SYSTEM_MODULE_EXPORTED_PACKAGES;

    private JrtExportFilter() {
    }

    static boolean isIndexable(FileObject file, boolean skipNonExportedJrt) {
        if (!skipNonExportedJrt) {
            return true;
        }

        if (file == null) {
            return true;
        }

        String p;
        try {
            p = file.getPath();
        } catch (Throwable t) {
            return true;
        }
        if (p == null) {
            return true;
        }

        if (!p.startsWith("jrt:/")) {
            return true;
        }

        int modulesIdx = p.indexOf("/modules/");
        if (modulesIdx < 0) {
            return true;
        }

        int moduleStart = modulesIdx + "/modules/".length();
        int moduleEnd = p.indexOf('/', moduleStart);
        if (moduleEnd <= moduleStart) {
            return true;
        }
        String module = p.substring(moduleStart, moduleEnd);

        int lastSlash = p.lastIndexOf('/');
        if (lastSlash <= moduleEnd) {
            // Something like jrt:/modules/java.base (folder) – let traversal handle folders.
            return true;
        }

        String fileName = p.substring(lastSlash + 1);
        if ("module-info.class".equals(fileName) || "package-info.class".equals(fileName)) {
            return false;
        }

        if (!fileName.endsWith(".class")) {
            return true;
        }

        String pkgPath = p.substring(moduleEnd + 1, lastSlash);
        if (pkgPath.isEmpty()) {
            return false;
        }
        String pkg = pkgPath.replace('/', '.');

        Set<String> exported = getSystemModuleExports().get(module);
        if (exported == null || exported.isEmpty()) {
            // If we can't determine exports, don't risk hiding symbols.
            return true;
        }

        return exported.contains(pkg);
    }

    private static Map<String, Set<String>> getSystemModuleExports() {
        Map<String, Set<String>> cached = SYSTEM_MODULE_EXPORTED_PACKAGES;
        if (cached != null) {
            return cached;
        }
        synchronized (JrtExportFilter.class) {
            cached = SYSTEM_MODULE_EXPORTED_PACKAGES;
            if (cached != null) {
                return cached;
            }
            cached = computeSystemModuleExportsReflective();
            SYSTEM_MODULE_EXPORTED_PACKAGES = cached;
            return cached;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> computeSystemModuleExportsReflective() {
        try {
            // Use reflection so this code can still load on runtimes without the Java 9 module APIs (e.g., Android).
            Class<?> moduleFinderCls = Class.forName("java.lang.module.ModuleFinder");
            Object finder = moduleFinderCls.getMethod("ofSystem").invoke(null);
            Set<?> refs = (Set<?>) moduleFinderCls.getMethod("findAll").invoke(finder);

            if (refs == null || refs.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, Set<String>> byModule = new HashMap<>();
            for (Object ref : refs) {
                if (ref == null) continue;
                Object descriptor = ref.getClass().getMethod("descriptor").invoke(ref);
                if (descriptor == null) continue;

                String moduleName = (String) descriptor.getClass().getMethod("name").invoke(descriptor);
                if (moduleName == null || moduleName.isBlank()) continue;

                Set<String> exports = new HashSet<>();
                Set<?> exportsSet = (Set<?>) descriptor.getClass().getMethod("exports").invoke(descriptor);
                if (exportsSet != null) {
                    for (Object exp : exportsSet) {
                        if (exp == null) continue;
                        boolean qualified = (boolean) exp.getClass().getMethod("isQualified").invoke(exp);
                        if (qualified) {
                            continue;
                        }
                        String pkg = (String) exp.getClass().getMethod("source").invoke(exp);
                        if (pkg != null && !pkg.isBlank()) {
                            exports.add(pkg);
                        }
                    }
                }

                if (!exports.isEmpty()) {
                    byModule.put(moduleName, Collections.unmodifiableSet(exports));
                }
            }

            return byModule.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(byModule);
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }
}
