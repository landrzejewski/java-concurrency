package pl.training.e005_util_concurrent;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Phaser;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;

public class Search implements Runnable {

    private final Path path;
    private final String query;
    private final Predicate<File> predicate;
    private final Phaser phaser;

    public Search(Path path, String query, Predicate<File> predicate, Phaser phaser) {
        this.path = path;
        this.query = query;
        this.predicate = predicate;
        this.phaser = phaser;
    }

    @Override
    public void run() {
        Set<File> files;

        newPhase();
        files = process(path.toFile());

        if (files.isEmpty()) {
            endPhaseWithNoResult();
            return;
        }
        endPhaseWithResult();

        newPhase();
        files = files.stream().filter(predicate).collect(toSet());
        if (files.isEmpty()) {
            endPhaseWithNoResult();
            return;
        }
        endPhaseWithResult();

        newPhase();
        files.forEach(file -> System.out.println(file.getAbsolutePath()));
    }

    private void newPhase() {
        phaser.arriveAndAwaitAdvance();
        System.out.printf("%s: Starting phase %d\n", Thread.currentThread().getName(), phaser.getPhase());
    }

    private void endPhaseWithResult() {
        System.out.printf("%s: Finished with results\n", Thread.currentThread().getName());
    }

    private void endPhaseWithNoResult() {
        phaser.arriveAndDeregister();
        System.out.printf("%s: Finished with no results\n", Thread.currentThread().getName());
    }

    private Set<File> process(File file) {
        Set<File> files = new HashSet<>();
        if (file.isDirectory()) {
            files.addAll(processDirectory(file));
        } else {
            processFile(file).ifPresent(files::add);
        }
        return files;
    }

    private Set<File> processDirectory(File file) {
        File[] files = file.listFiles();
        if (files != null) {
            return Arrays.stream(files)
                    .map(this::process)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());

        }
        return new HashSet<>();
    }

    private Optional<File> processFile(File file) {
        if (file.getName().contains(query)) {
            return Optional.of(file);
        }
        return Optional.empty();
    }

}
