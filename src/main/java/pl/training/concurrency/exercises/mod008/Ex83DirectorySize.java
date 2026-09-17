package pl.training.concurrency.exercises.mod008;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Exercise 8.3 — Directory size on a dedicated ForkJoinPool.
 *
 * <p>Related: Mod008 §2, §4–§5
 */
public final class Ex83DirectorySize {

    private Ex83DirectorySize() {}

    static final AtomicInteger UNREADABLE = new AtomicInteger();

    static final class DirSizeTask extends RecursiveTask<Long> {
        private final Path dir;

        DirSizeTask(Path dir) { this.dir = dir; }

        @Override protected Long compute() {
            long size = 0;
            List<DirSizeTask> subtasks = new ArrayList<>();
            try (var entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    try {
                        var attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attrs.isDirectory()) {
                            var subtask = new DirSizeTask(entry);
                            subtask.fork();               // one subtask per subdirectory
                            subtasks.add(subtask);
                        } else if (attrs.isRegularFile()) {
                            size += attrs.size();         // files of this directory are summed right here
                        }
                    } catch (IOException e) {
                        UNREADABLE.incrementAndGet();     // a single unreadable entry must not fail the tree
                    }
                }
            } catch (AccessDeniedException e) {
                UNREADABLE.incrementAndGet();
                return 0L;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (var subtask : subtasks) {
                size += subtask.join();
            }
            return size;
        }
    }

    public static void main(String[] args) throws IOException {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        System.out.println("scanning " + root.toAbsolutePath().normalize());

        long t0 = System.nanoTime();
        long forkJoinSize;
        // A dedicated pool: directory listing is blocking I/O. Blocking a common-pool worker stalls every other
        // user of the common pool (parallel streams, CompletableFuture async stages), and its parallelism is
        // sized for CPU-bound work — for I/O we want more threads than cores, not fewer.
        try (var pool = new ForkJoinPool(8)) {
            forkJoinSize = pool.invoke(new DirSizeTask(root));
        }
        long forkJoinMillis = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        long walkSize;
        try (Stream<Path> paths = Files.walk(root)) {
            walkSize = paths.filter(Files::isRegularFile).mapToLong(Ex83DirectorySize::sizeOrZero).sum();
        } catch (UncheckedIOException e) {
            walkSize = -1; // Files.walk gives up on the first unreadable directory
        }
        long walkMillis = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf(Locale.ROOT, "  fork/join:  %,d bytes in %d ms (unreadable entries: %d)%n",
                forkJoinSize, forkJoinMillis, UNREADABLE.get());
        System.out.printf(Locale.ROOT, "  Files.walk: %,d bytes in %d ms%n", walkSize, walkMillis);
        System.out.println(forkJoinSize == walkSize ? "  sizes match" : "  sizes differ (files changed or unreadable entries)");
        System.out.println("Ex83DirectorySize finished");
    }

    private static long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }
}
