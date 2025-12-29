package com.tyron.nanoj.lang.java.indexing;

import com.tyron.nanoj.api.vfs.FileObject;
import com.tyron.nanoj.core.indexing.IndexManager;
import com.tyron.nanoj.core.service.ProjectServiceManager;
import com.tyron.nanoj.core.test.MockFileObject;
import com.tyron.nanoj.core.test.MockProject;
import com.tyron.nanoj.core.vfs.JrtFileSystem;
import com.tyron.nanoj.core.vfs.VirtualFileSystem;
import com.tyron.nanoj.core.indexing.spi.IndexDefinition;
import com.tyron.nanoj.testFramework.BaseIdeTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Indexing benchmark for {@code jrt:} (JDK modules runtime image).
 *
 * This benchmark measures two things per indexer:
 * 1) "Pure map" cost: time spent in {@link IndexDefinition#map(FileObject, Object)} per file.
 * 2) "End-to-end" cost: time spent indexing the same files through {@link IndexManager#updateFilesAsync(Iterable)}
 *    with a MapDB commit.
 *
 * Notes:
 * - This is intentionally opt-in. Enable with: -Dnanoj.bench.run=true
 * - Control the dataset with: -Dnanoj.bench.jrt.maxClasses=2000 (default)
 * - Control the roots with: -Dnanoj.bench.jrt.roots=jrt:/modules/java.base/java/lang/,jrt:/modules/java.base/java/util/
 */
@Tag("benchmark")
public class JrtIndexBenchmarkTest extends BaseIdeTest {

    private static final String RUN_PROP = "nanoj.bench.run";
    private static final String JRT_MAX_CLASSES_PROP = "nanoj.bench.jrt.maxClasses";
    private static final String JRT_ROOTS_PROP = "nanoj.bench.jrt.roots";
    private static final String WARMUP_PROP = "nanoj.bench.warmup";

    private static final String[] DEFAULT_JRT_ROOTS = {
            "jrt:/modules/java.base/java/lang/",
            "jrt:/modules/java.base/java/util/",
            "jrt:/modules/java.base/java/io/",
            "jrt:/modules/java.base/java/time/"
    };

    @Test
    public void benchmarkIndexersOnJrt() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty(RUN_PROP, "false")),
                "Benchmark is opt-in. Rerun with -D" + RUN_PROP + "=true");

        Assumptions.assumeTrue(isJrtAvailable(), "jrt: filesystem is not available in this runtime");

        int maxClasses = readInt(JRT_MAX_CLASSES_PROP, 2000);
        int warmup = Math.max(0, readInt(WARMUP_PROP, 1));

        List<String> roots = readCsvOrDefault(JRT_ROOTS_PROP, DEFAULT_JRT_ROOTS);

        printEnvironment(maxClasses, warmup, roots);

        List<FileObject> classFiles = collectJrtClassFiles(roots, maxClasses);
        System.out.println("Collected class files: " + classFiles.size());
        if (classFiles.isEmpty()) {
            return;
        }

        // Indexers to benchmark.
        // Keep the list explicit so results are stable/readable.
        List<IndexDefinition<?, ?>> indexers = Arrays.asList(
                new JavaBinaryStubIndexer(project),
                new ShortClassNameIndex(project),
                new JavaPackageIndex(project)
        );

        for (IndexDefinition<?, ?> def : indexers) {
            System.out.println("\n============================================================");
            System.out.println("Indexer: " + def.getClass().getName() + "  (id=" + def.getId() + ", v=" + def.getVersion() + ")");

            // Warmup: run map() without recording.
            for (int i = 0; i < warmup; i++) {
                runPureMap(def, classFiles, false);
            }

            PureMapResult pure = runPureMap(def, classFiles, true);
            printPureMapResult(pure);

            EndToEndResult e2e = runEndToEnd(def, classFiles);
            printEndToEndResult(e2e);
        }
    }

    private static boolean isJrtAvailable() {
        try {
            JrtFileSystem.getOrCreateJrtNioFs();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<FileObject> collectJrtClassFiles(List<String> roots, int maxClasses) {
        if (maxClasses <= 0) {
            return Collections.emptyList();
        }

        List<FileObject> out = new ArrayList<>(Math.min(maxClasses, 8192));
        int remaining = maxClasses;

        for (String root : roots) {
            if (remaining <= 0) break;
            if (root == null || root.trim().isEmpty()) continue;

            try {
                FileObject pkgRoot = VirtualFileSystem.getInstance().find(URI.create(root.trim()));
                remaining = collectClassFilesBfs(pkgRoot, remaining, out);
            } catch (Throwable t) {
                System.out.println("Skipping root (unavailable): " + root + " -> " + t.getClass().getSimpleName());
            }
        }

        return out;
    }

    private static int collectClassFilesBfs(FileObject root, int remaining, List<FileObject> out) {
        if (root == null || remaining <= 0) return remaining;
        if (!root.isFolder()) return remaining;

        Deque<FileObject> q = new ArrayDeque<>();
        q.add(root);

        while (!q.isEmpty() && remaining > 0) {
            FileObject dir = q.removeFirst();
            List<FileObject> children = dir.getChildren();
            if (children == null || children.isEmpty()) continue;

            for (FileObject c : children) {
                if (c == null) continue;
                if (remaining <= 0) break;

                if (c.isFolder()) {
                    q.addLast(c);
                } else if ("class".equalsIgnoreCase(c.getExtension())) {
                    out.add(c);
                    remaining--;
                }
            }
        }

        return remaining;
    }

    private PureMapResult runPureMap(IndexDefinition<?, ?> def, List<FileObject> classFiles, boolean record) {
        long t0 = System.nanoTime();

        long totalEntries = 0;
        int visited = 0;
        int indexed = 0;
        int failures = 0;

        long[] perFileNanos = record ? new long[classFiles.size()] : null;
        int perFileCount = 0;

        for (FileObject fo : classFiles) {
            visited++;
            if (!def.supports(fo)) {
                continue;
            }

            indexed++;
            long s = record ? System.nanoTime() : 0L;
            try {
                Map<?, ?> m = def.map(fo, null);
                if (m != null) totalEntries += m.size();
            } catch (Throwable t) {
                failures++;
            } finally {
                if (record) {
                    perFileNanos[perFileCount++] = System.nanoTime() - s;
                }
            }
        }

        long t1 = System.nanoTime();

        PureMapResult r = new PureMapResult();
        r.indexId = def.getId();
        r.indexerClass = def.getClass().getName();
        r.totalNanos = t1 - t0;
        r.visitedFiles = visited;
        r.indexedFiles = indexed;
        r.failures = failures;
        r.totalEntries = totalEntries;
        r.perFileNanos = record ? Arrays.copyOf(perFileNanos, perFileCount) : new long[0];
        return r;
    }

    private EndToEndResult runEndToEnd(IndexDefinition<?, ?> def, List<FileObject> classFiles) {
        // Create a fresh project+IndexManager so results reflect only this indexer.
        File cacheDir = new File(temporaryFolder, "bench-cache-" + sanitize(def.getId()));
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();

        MockProject benchProject = new MockProject(cacheDir, new MockFileObject("/bench_root", ""));

        try {
            IndexManager manager = ProjectServiceManager.getService(benchProject, IndexManager.class);
            manager.register(def);

            long t0 = System.nanoTime();
            manager.updateFilesAsync(classFiles);
            manager.flush();
            long t1 = System.nanoTime();

            EndToEndResult r = new EndToEndResult();
            r.indexId = def.getId();
            r.indexerClass = def.getClass().getName();
            r.fileCount = classFiles.size();
            r.totalNanos = t1 - t0;
            r.cacheDir = cacheDir.getAbsolutePath();
            r.dbFileBytes = new File(cacheDir, "nanoj_index.db").length();
            return r;
        } finally {
            ProjectServiceManager.disposeProject(benchProject);
            benchProject.dispose();
        }
    }

    private static void printPureMapResult(PureMapResult r) {
        double totalMs = r.totalNanos / 1_000_000.0;
        double filesPerSec = r.indexedFiles <= 0 ? 0.0 : (r.indexedFiles / (r.totalNanos / 1_000_000_000.0));
        double entriesPerSec = r.totalEntries <= 0 ? 0.0 : (r.totalEntries / (r.totalNanos / 1_000_000_000.0));

        System.out.println("\n[PURE MAP]");
        System.out.println("Visited files:   " + r.visitedFiles);
        System.out.println("Indexed files:   " + r.indexedFiles);
        System.out.println("Failures:        " + r.failures);
        System.out.println("Total entries:   " + r.totalEntries);
        System.out.println("Total time:      " + formatMs(totalMs));
        System.out.println("Throughput:      " + format1(filesPerSec) + " files/s, " + format1(entriesPerSec) + " entries/s");

        if (r.perFileNanos != null && r.perFileNanos.length > 0) {
            Stats s = Stats.ofNanos(r.perFileNanos);
            System.out.println("Per-file nanos:  count=" + s.count +
                    ", min=" + s.min +
                    ", p50=" + s.p50 +
                    ", p90=" + s.p90 +
                    ", p99=" + s.p99 +
                    ", max=" + s.max);
        }
    }

    private static void printEndToEndResult(EndToEndResult r) {
        double totalMs = r.totalNanos / 1_000_000.0;
        double filesPerSec = r.fileCount <= 0 ? 0.0 : (r.fileCount / (r.totalNanos / 1_000_000_000.0));

        System.out.println("\n[END-TO-END IndexManager.updateFilesAsync + MapDB commit]");
        System.out.println("Files indexed:   " + r.fileCount);
        System.out.println("Total time:      " + formatMs(totalMs));
        System.out.println("Throughput:      " + format1(filesPerSec) + " files/s");
        System.out.println("Index DB bytes:  " + r.dbFileBytes);
        System.out.println("Cache dir:       " + r.cacheDir);
    }

    private static void printEnvironment(int maxClasses, int warmup, List<String> roots) {
        System.out.println("\n=== JRT Index Benchmark ===");
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("java.vendor=" + System.getProperty("java.vendor"));
        System.out.println("os.name=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("os.arch=" + System.getProperty("os.arch"));
        System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors());
        System.out.println("maxMemoryMB=" + (Runtime.getRuntime().maxMemory() / (1024L * 1024L)));
        System.out.println("\nConfig:");
        System.out.println("- maxClasses=" + maxClasses);
        System.out.println("- warmup=" + warmup);
        System.out.println("- roots=" + roots);
        System.out.println("\nTiming:");
        System.out.println("- Uses System.nanoTime(); reports ms and per-file percentiles");
        System.out.println();
    }

    private static int readInt(String key, int def) {
        try {
            String v = System.getProperty(key);
            if (v == null || v.trim().isEmpty()) return def;
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static List<String> readCsvOrDefault(String key, String[] def) {
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            return Arrays.asList(def);
        }
        String[] parts = v.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.trim().isEmpty()) {
                out.add(p.trim());
            }
        }
        return out.isEmpty() ? Arrays.asList(def) : out;
    }

    private static String sanitize(String s) {
        if (s == null) return "null";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String formatMs(double ms) {
        return String.format(Locale.US, "%.2f ms", ms);
    }

    private static String format1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static final class PureMapResult {
        String indexId;
        String indexerClass;
        long totalNanos;
        int visitedFiles;
        int indexedFiles;
        int failures;
        long totalEntries;
        long[] perFileNanos;
    }

    private static final class EndToEndResult {
        String indexId;
        String indexerClass;
        int fileCount;
        long totalNanos;
        long dbFileBytes;
        String cacheDir;
    }

    private static final class Stats {
        int count;
        long min;
        long p50;
        long p90;
        long p99;
        long max;

        static Stats ofNanos(long[] nanos) {
            long[] a = Arrays.copyOf(nanos, nanos.length);
            Arrays.sort(a);
            Stats s = new Stats();
            s.count = a.length;
            s.min = a[0];
            s.p50 = percentile(a, 50);
            s.p90 = percentile(a, 90);
            s.p99 = percentile(a, 99);
            s.max = a[a.length - 1];
            return s;
        }

        private static long percentile(long[] sorted, int p) {
            if (sorted.length == 0) return 0L;
            if (p <= 0) return sorted[0];
            if (p >= 100) return sorted[sorted.length - 1];
            double rank = (p / 100.0) * (sorted.length - 1);
            int lo = (int) Math.floor(rank);
            int hi = (int) Math.ceil(rank);
            if (lo == hi) return sorted[lo];
            double frac = rank - lo;
            return (long) (sorted[lo] + frac * (sorted[hi] - sorted[lo]));
        }

        @Override
        public String toString() {
            return "Stats{" +
                    "count=" + count +
                    ", min=" + min +
                    ", p50=" + p50 +
                    ", p90=" + p90 +
                    ", p99=" + p99 +
                    ", max=" + max +
                    '}';
        }
    }
}
