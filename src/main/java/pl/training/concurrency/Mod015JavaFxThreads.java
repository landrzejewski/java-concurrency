package pl.training.concurrency;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/*
Threads and JavaFX

Why GUI toolkits are single-threaded

- Every mainstream GUI toolkit (Swing, SWT, Android, WinUI, Cocoa, JavaFX) confines the UI to ONE thread. Attempts to
  build multithreaded toolkits failed: input events travel bottom-up (OS → window → control) while model changes
  travel top-down (model → control → window), so the two directions acquire locks in opposite order — the classic
  lock-ordering deadlock from Mod014 §2, baked into the architecture.
- The consequence is thread confinement (Mod003): the scene graph is not thread-safe and needs no locks, because only
  one thread ever touches it.

The JavaFX threads

  Thread                        Role
  ----------------------------- -------------------------------------------------------------------------------
  JavaFX-Launcher               created by Application.launch(); runs Application.init()
  JavaFX Application Thread     "the FX thread": event handlers, start(), stop(), animations, runLater runnables
  QuantumRenderer-0             renders the scene graph snapshot (Prism) — you never touch it directly
  main                          blocked inside Application.launch() until the application exits

The two rules

  1. NEVER BLOCK THE FX THREAD. Anything slower than a few milliseconds (I/O, sleeping, joining, heavy computation)
     belongs on another thread. A blocked FX thread = frozen window, no repaints, "application not responding".
  2. TOUCH THE LIVE SCENE GRAPH ONLY ON THE FX THREAD. Nodes that are not yet attached to a showing Scene may be
     built on any thread; once attached, every read and write goes through the FX thread.

  Rule 1 pushes work OFF the FX thread; rule 2 pulls the results back ON. Everything in this module — runLater, Task,
  Service, CompletableFuture with an FX executor — is a way of doing that round trip correctly.

Running this module

- The project has no module-info.java, so JavaFX is loaded from the classpath. The JavaFX launcher refuses to start
  when the MAIN class itself extends Application ("JavaFX runtime components are missing"). That is why this class
  does not extend Application — main() delegates to the nested DemoApp. The warning "Unsupported JavaFX
  configuration: classes were loaded from 'unnamed module'" is expected and harmless for a demo. The JDK 24+
  "restricted method System::load" warnings disappear with the JVM option --enable-native-access=ALL-UNNAMED.
- Each section is a tab in one window. Every demo logs the current thread to the console so you can see which
  thread ran which piece of code.
*/

public final class Mod015JavaFxThreads {

    private Mod015JavaFxThreads() {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Virtual threads for all background work in this module (Mod011). Closed in DemoApp.stop(). */
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();

    /*
    The FX Application Thread and the application life cycle

    - Application.launch() starts the toolkit, calls init() on the JavaFX-Launcher thread, then start(Stage) on the
      FX thread, and finally stop() on the FX thread when the application exits.
    - init() must not create Stages or Scenes (it is not the FX thread) — use it for loading configuration only.
    - Platform.isFxApplicationThread() tells you where you are; use it in assertions inside code that must be called
      on (or off) the FX thread.
    - The application exits when the last window closes (Platform.setImplicitExit(true), the default) or when
      Platform.exit() is called. Platform.exit() runs stop() and ends the FX thread; background user threads still
      keep the JVM alive, which is why background executors are closed in stop() (or use daemon/virtual threads).
    */
    static Node lifecycle() {
        System.out.println("[Section 1] FX Application Thread and life cycle");
        log("building section 1, isFxApplicationThread = " + Platform.isFxApplicationThread());

        var threads = new Label();
        var refresh = new Button("List JVM threads");
        refresh.setOnAction(_ -> threads.setText(listThreads()));
        refresh.fire();

        var check = new Label();
        var fromFx = new Button("isFxApplicationThread() in a handler");
        fromFx.setOnAction(_ -> check.setText("handler: " + Platform.isFxApplicationThread()));
        var fromWorker = new Button("isFxApplicationThread() in a worker");
        fromWorker.setOnAction(_ -> WORKERS.submit(() -> {
            boolean onFx = Platform.isFxApplicationThread();
            log("worker checked isFxApplicationThread = " + onFx);
            Platform.runLater(() -> check.setText("worker: " + onFx));
        }));

        var exit = new Button("Platform.exit()");
        exit.setOnAction(_ -> Platform.exit());

        return section("Rule: the scene graph belongs to one thread — the JavaFX Application Thread.",
                new HBox(8, refresh, fromFx, fromWorker, exit), check, threads);
    }

    /*
    Blocking the FX thread

    - The FX thread runs a loop: take the next event (input, runLater runnable, animation pulse), handle it, repeat.
      Rendering is driven by "pulses" scheduled on the same thread, so while a handler runs NOTHING repaints.
    - The spinning ProgressIndicator below is animated by pulses. Press the first button: the spinner freezes,
      the other buttons do not react, the window cannot be moved on some platforms — for three seconds.
    - The second button runs the same sleep on a virtual thread and only hands the RESULT back with runLater.
      The spinner keeps spinning.
    - The same applies to anything that blocks: Future.get(), CompletableFuture.join(), Thread.join(), a
      synchronized block contended by a worker, blocking I/O, a JDBC call.
    */
    static Node blocking() {
        System.out.println("[Section 2] blocking the FX thread");

        var spinner = new ProgressIndicator();
        var result = new Label("idle");

        var blockFx = new Button("Sleep 3 s ON the FX thread");
        blockFx.setOnAction(_ -> {
            log("sleeping on the FX thread — the window is frozen");
            result.setText("working…");      // never shown: no pulse happens before the handler returns
            sleep(3_000);
            result.setText("done (blocked the FX thread) at " + now());
        });

        var offFx = new Button("Sleep 3 s on a virtual thread");
        offFx.setOnAction(_ -> {
            result.setText("working…");
            offFx.setDisable(true);
            WORKERS.submit(() -> {
                log("sleeping off the FX thread — the window stays responsive");
                sleep(3_000);
                String text = "done (off the FX thread) at " + now();
                Platform.runLater(() -> {
                    result.setText(text);
                    offFx.setDisable(false);
                });
            });
        });

        return section("Watch the spinner while each button works.",
                spinner, new HBox(8, blockFx, offFx), result);
    }

    /*
    Wrong-thread access

    - Some operations are CHECKED: modifying the children of a Parent that is part of a showing Scene, creating or
      showing a Stage, and a few others call Toolkit.checkFxUserThread() and throw
      IllegalStateException: Not on FX application thread; currentThread = ...
    - Most property writes are NOT checked. label.setText(...) from a worker returns normally — it is a data race
      on the scene graph (Mod002): the renderer may see a half-updated node and a layout pass can run concurrently
      with the write. Sometimes a listener inside the control's skin reaches a checked operation and an
      IllegalStateException is printed to stderr — but it is swallowed by the listener machinery, setText() still
      returns, and the property and its on-screen representation are now out of sync. Other times you get an
      occasional NullPointerException / ArrayIndexOutOfBounds deep inside JavaFX, or nothing at all. Unchecked is
      worse than checked: it fails rarely, and far from the cause.
    - Bindings do not help: if a worker writes a property that a node's property is bound to, the listener runs
      on the WORKER thread and updates the node there.
    - Fix: marshal the update with Platform.runLater(...). Build detached nodes off-thread if you like, but attach
      them on the FX thread.
    */
    static Node wrongThread() {
        System.out.println("[Section 3] wrong-thread access");

        var items = new VBox(4);
        var status = new Label();

        var checked = new Button("Add child from a worker (checked → exception)");
        checked.setOnAction(_ -> WORKERS.submit(() -> {
            try {
                items.getChildren().add(new Label("added off the FX thread"));
            } catch (IllegalStateException e) {
                log("caught: " + e.getMessage());
                Platform.runLater(() -> status.setText("IllegalStateException: " + e.getMessage()));
            }
        }));

        var unchecked = new Button("setText from a worker (unchecked → silent race)");
        unchecked.setOnAction(_ -> WORKERS.submit(() -> {
            status.setText("set off the FX thread at " + now() + " — no exception, still a bug");
            log("setText off the FX thread returned normally (check stderr for a swallowed exception)");
        }));

        var fixed = new Button("Add child via Platform.runLater (correct)");
        fixed.setOnAction(_ -> WORKERS.submit(() -> {
            var label = new Label("built on a worker, attached on the FX thread at " + now()); // detached: fine
            Platform.runLater(() -> {
                items.getChildren().add(label);
                status.setText("added via runLater");
            });
        }));

        return section("Only the FX thread may touch nodes that are part of a showing Scene.",
                new HBox(8, checked, unchecked, fixed), status, items);
    }

    /*
    Platform.runLater and flooding

    - Platform.runLater(r) enqueues r on the FX thread's event queue and returns immediately. Runnables execute in
      FIFO order — useful: a runnable posted after N others is guaranteed to run after them.
    - The queue is unbounded. A worker that posts one runnable per data item can outpace the FX thread by orders of
      magnitude: the queue grows, memory grows, input events wait behind thousands of stale label updates, and the
      UI lags seconds behind reality. Nobody needs to see 100 000 intermediate values on a 60 Hz screen.
    - Cure: COALESCE. Keep the latest value in an AtomicReference and allow at most one pending runnable at a time
      (an AtomicBoolean). The runnable clears the flag FIRST and then reads the latest value, so a value published
      in between is either read now or triggers a new runnable — never lost.
    - javafx.concurrent.Task does exactly this internally for updateProgress/updateMessage/updateValue (§5).
    */
    static Node runLaterFlood() {
        System.out.println("[Section 4] Platform.runLater and flooding");

        final int updates = 200_000;
        var value = new Label("-");
        var report = new Label();

        var flood = new Button("One runLater per update");
        flood.setOnAction(_ -> {
            var executed = new AtomicInteger();
            long start = System.nanoTime();
            WORKERS.submit(() -> {
                for (int i = 1; i <= updates; i++) {
                    String text = Integer.toString(i);
                    Platform.runLater(() -> {
                        value.setText(text);
                        executed.incrementAndGet();
                    });
                }
                long posted = System.nanoTime();
                // FIFO: this runs after every runnable posted above
                Platform.runLater(() -> report.setText(summary("flood", updates, executed.get(), start, posted)));
            });
        });

        var coalesce = new Button("Coalesced updates");
        coalesce.setOnAction(_ -> {
            var executed = new AtomicInteger();
            var latest = new AtomicReference<String>();
            var pending = new AtomicBoolean();
            long start = System.nanoTime();
            WORKERS.submit(() -> {
                for (int i = 1; i <= updates; i++) {
                    latest.set(Integer.toString(i));
                    if (pending.compareAndSet(false, true)) {
                        Platform.runLater(() -> {
                            pending.set(false);          // clear first, then read — no update can be lost
                            value.setText(latest.get());
                            executed.incrementAndGet();
                        });
                    }
                }
                long posted = System.nanoTime();
                Platform.runLater(() -> report.setText(summary("coalesced", updates, executed.get(), start, posted)));
            });
        });

        return section("Both buttons publish " + String.format(Locale.ROOT, "%,d", updates)
                        + " values from a worker. Compare how many runnables the FX thread had to execute.",
                new HBox(8, flood, coalesce), value, report);
    }

    private static String summary(String label, int published, int executed, long start, long posted) {
        String text = String.format(Locale.ROOT,
                "%s: published %,d, executed %,d runnables on the FX thread; worker done after %d ms, UI caught up after %d ms",
                label, published, executed, (posted - start) / 1_000_000, (System.nanoTime() - start) / 1_000_000);
        log(text);
        return text;
    }

    /*
    javafx.concurrent.Task

    - Task<V> is a FutureTask (Mod007) that knows about the FX thread. call() runs on a background thread; everything
      observable about it lives on the FX thread:
        - updateProgress / updateMessage / updateTitle / updateValue may be called from call(); the task coalesces
          them (§4) and applies them on the FX thread, so the matching properties can be BOUND to controls.
        - state, value, exception, progress, message are FX-thread properties — read them only on the FX thread.
        - setOnSucceeded / setOnFailed / setOnCancelled handlers run on the FX thread.
    - Worker.State: READY → SCHEDULED → RUNNING → SUCCEEDED | FAILED | CANCELLED.
    - A Task is single-use (like a Thread). Run it with executor.submit(task) or new Thread(task).start().
    - cancel() sets the state to CANCELLED immediately and interrupts the thread (cancel(true)). Cancellation is
      still cooperative (Mod001): call() must check isCancelled() or react to InterruptedException. Anything the
      task returns after cancellation is ignored.
    - Never touch the scene graph from call(); return a value or use updateValue instead.
    */
    static Node task() {
        System.out.println("[Section 5] javafx.concurrent.Task");

        var progress = new ProgressBar(0);
        progress.setPrefWidth(300);
        var message = new Label();
        var state = new Label();
        var partial = new Label();
        var outcome = new Label();
        var failHalfway = new CheckBox("fail at 50%");
        var start = new Button("Start");
        var cancel = new Button("Cancel");
        cancel.setDisable(true);

        start.setOnAction(_ -> {
            boolean fail = failHalfway.isSelected();
            Task<Long> task = new Task<>() {
                @Override
                protected Long call() throws Exception {
                    log("Task.call() started");
                    long sum = 0;
                    for (int i = 1; i <= 100; i++) {
                        if (isCancelled()) {
                            break;
                        }
                        try {
                            Thread.sleep(40);                 // simulated work — interrupted by cancel()
                        } catch (InterruptedException e) {
                            if (isCancelled()) break;
                            throw e;
                        }
                        if (fail && i == 50) {
                            throw new IllegalStateException("simulated failure at step 50");
                        }
                        sum += i;
                        updateProgress(i, 100);
                        updateMessage("step " + i + "/100");
                        updateValue(sum);
                    }
                    log("Task.call() finished, cancelled = " + isCancelled());
                    return sum;
                }
            };

            progress.progressProperty().bind(task.progressProperty());
            message.textProperty().bind(task.messageProperty());
            state.textProperty().bind(task.stateProperty().asString("state: %s"));
            partial.textProperty().bind(task.valueProperty().asString("partial value: %s"));

            task.setOnSucceeded(_ -> finish(outcome, "succeeded: sum = " + task.getValue(), start, cancel));
            task.setOnFailed(_ -> finish(outcome, "failed: " + task.getException().getMessage(), start, cancel));
            task.setOnCancelled(_ -> finish(outcome, "cancelled", start, cancel));

            cancel.setOnAction(_ -> task.cancel());
            start.setDisable(true);
            cancel.setDisable(false);
            outcome.setText("");
            WORKERS.submit(task);
        });

        return section("Progress, message, state and value are bound to Task properties updated from call().",
                new HBox(8, start, cancel, failHalfway), progress, message, state, partial, outcome);
    }

    private static void finish(Label outcome, String text, Button start, Button cancel) {
        log("Task handler: " + text);
        outcome.setText(text);
        start.setDisable(false);
        cancel.setDisable(true);
    }

    /*
    Service and ScheduledService

    - Service<V> is a REUSABLE wrapper around Task: createTask() builds a fresh Task for every run. It exposes the
      same observable properties (state, value, progress, …) as a Task, but they survive across runs, so the UI
      binds once.
    - start() only from READY; restart() cancels the running task (if any) and starts a new one — perfect for
      "search as you type" where only the latest query matters. reset() returns to READY.
    - setExecutor(...) chooses where tasks run; the default is an internal thread pool of daemon threads.
    - Service itself must be used from the FX thread (start, restart, cancel, reading properties).
    - ScheduledService<V> re-runs its task periodically:
        - setDelay (first run), setPeriod (between successful runs)
        - setRestartOnFailure(true) + setBackoffStrategy(EXPONENTIAL_BACKOFF_STRATEGY) — retry failures with growing
          delay; setMaximumFailureCount stops after N consecutive failures (state FAILED)
        - lastValue holds the last successful value (value is reset to null at the start of each run)
    - For periodic UI work that is NOT background work (a clock, a blinking cursor), use Timeline / AnimationTimer
      instead (§8).
    */
    static Node services() {
        System.out.println("[Section 6] Service and ScheduledService");

        // Service: reusable, restartable
        var search = new Service<String>() {
            private int runs;
            @Override
            protected Task<String> createTask() {
                int run = ++runs;
                return new Task<>() {
                    @Override
                    protected String call() throws Exception {
                        log("Service task #" + run + " started");
                        Thread.sleep(1_500);
                        return "result of run #" + run + " at " + now();
                    }
                };
            }
        };
        search.setExecutor(WORKERS);
        var searchState = new Label();
        searchState.textProperty().bind(search.stateProperty().asString("service state: %s"));
        var searchValue = new Label();
        searchValue.textProperty().bind(search.valueProperty());
        var restart = new Button("restart()");
        restart.setOnAction(_ -> search.restart());

        // ScheduledService: periodic polling with backoff
        var poller = new ScheduledService<String>() {
            @Override
            protected Task<String> createTask() {
                return new Task<>() {
                    @Override
                    protected String call() {
                        if (ThreadLocalRandom.current().nextInt(100) < 30) {
                            log("poll failed");
                            throw new IllegalStateException("server unavailable");
                        }
                        log("poll succeeded");
                        return "server time " + now();
                    }
                };
            }
        };
        poller.setExecutor(WORKERS);
        poller.setPeriod(Duration.seconds(1));
        poller.setRestartOnFailure(true);
        poller.setBackoffStrategy(ScheduledService.EXPONENTIAL_BACKOFF_STRATEGY);
        poller.setMaximumFailureCount(5);
        poller.setMaximumCumulativePeriod(Duration.seconds(10));

        var pollState = new Label();
        pollState.textProperty().bind(poller.stateProperty().asString("poller state: %s"));
        var pollValue = new Label();
        pollValue.textProperty().bind(poller.lastValueProperty().map(v -> "last value: " + v));
        var failures = new Label();
        failures.textProperty().bind(poller.currentFailureCountProperty().asString("consecutive failures: %d"));
        var period = new Label();
        period.textProperty().bind(poller.cumulativePeriodProperty().map(d -> "current period: " + d));

        var startPolling = new Button("Start polling");
        startPolling.setOnAction(_ -> poller.restart());
        var stopPolling = new Button("Stop polling");
        stopPolling.setOnAction(_ -> poller.cancel());

        return section("Click restart() repeatedly — each click cancels the running task and starts a new one.",
                restart, searchState, searchValue,
                new Label(" "),
                new Label("ScheduledService: polls every second, 30% of polls fail, exponential backoff, max 5 failures."),
                new HBox(8, startPolling, stopPolling), pollState, pollValue, failures, period);
    }

    /*
    CompletableFuture and the FX thread

    - Platform::runLater has the shape of an Executor (void execute(Runnable)), so it can be passed to every *Async
      stage method of CompletableFuture (Mod009). That is the idiomatic way to hop back onto the FX thread:

        supplyAsync(this::load, workers)                     // worker thread
            .thenApplyAsync(this::parse, workers)            // worker thread
            .thenAcceptAsync(this::render, Platform::runLater) // FX thread
            .exceptionallyAsync(this::showError, Platform::runLater);

    - The NON-async thenAccept(...) runs on whichever thread completes the previous stage — usually the worker, but
      the FX thread itself if the stage was already complete when thenAccept was called. Never rely on that for UI
      code; always name the executor explicitly.
    - Never call join()/get() on the FX thread (§2). If code must wait for a result "in the middle" of a handler,
      use a nested event loop (§8) — or better, restructure it into a continuation.
    - Compared with Task: CompletableFuture composes (thenCombine, allOf, timeouts); Task gives progress properties,
      state for binding and cooperative cancellation. They can be combined: run a Task, or complete a
      CompletableFuture from a Task's onSucceeded handler.
    */
    static Node completableFuture() {
        System.out.println("[Section 7] CompletableFuture and the FX thread");

        var steps = new VBox(2);
        var fail = new CheckBox("fail while loading");

        var run = new Button("Load → parse → render");
        run.setOnAction(_ -> {
            steps.getChildren().setAll(new Label("handler on " + threadName()));
            boolean shouldFail = fail.isSelected();
            CompletableFuture
                    .supplyAsync(() -> {
                        String step = "load  on " + threadName();
                        log(step);
                        sleep(800);
                        if (shouldFail) throw new IllegalStateException("connection refused");
                        return step;
                    }, WORKERS)
                    .thenApplyAsync(loaded -> {
                        String step = "parse on " + threadName();
                        log(step);
                        sleep(400);
                        return loaded + "\n" + step;
                    }, WORKERS)
                    .thenAcceptAsync(parsed -> {
                        log("render on " + threadName());
                        for (String line : parsed.split("\n")) steps.getChildren().add(new Label(line));
                        steps.getChildren().add(new Label("render on " + threadName()));
                    }, Platform::runLater)
                    .exceptionallyAsync(e -> {
                        var cause = e.getCause() != null ? e.getCause() : e;   // stages wrap in CompletionException
                        steps.getChildren().add(new Label("error shown on " + threadName() + ": " + cause.getMessage()));
                        return null;
                    }, Platform::runLater);
        });

        var nonAsync = new Button("Non-async thenAccept — where does it run?");
        nonAsync.setOnAction(_ -> {
            var future = CompletableFuture.supplyAsync(() -> {
                sleep(300);
                return "value";
            }, WORKERS);
            future.thenAccept(_ -> {
                String where = threadName();
                log("thenAccept ran on " + where);
                Platform.runLater(() -> steps.getChildren().setAll(new Label("thenAccept ran on " + where
                        + " — touching nodes here would be a bug")));
            });
        });

        var join = new Button("join() on the FX thread (freezes 2 s)");
        join.setOnAction(_ -> {
            log("joining on the FX thread");
            String value = CompletableFuture.supplyAsync(() -> { sleep(2_000); return "joined at " + now(); }, WORKERS).join();
            steps.getChildren().setAll(new Label(value + " — the window was frozen meanwhile"));
        });

        return section("Hop between threads with *Async stages and Platform::runLater as the executor.",
                new HBox(8, run, fail), new HBox(8, nonAsync, join), steps);
    }

    /*
    Pitfalls and the remaining tools

    - Periodic UI work: do not start a thread that sleeps and calls runLater in a loop. Timeline (fixed intervals)
      and AnimationTimer (called once per frame, ~60 Hz, with a nanosecond timestamp) both run ON the FX thread,
      are paused/stopped with the animation API and create no extra threads.
    - Nested event loops: Platform.enterNestedEventLoop(key) blocks the CALLER but keeps processing events, so the
      UI stays responsive; Platform.exitNestedEventLoop(key, value) returns value to that caller. Stage.showAndWait()
      and Alert.showAndWait() are built on it. The demo below "waits" for a worker this way without freezing. Nesting
      is powerful and easy to abuse — handlers can re-enter code that is still waiting further down the stack.
    - Platform.startup(Runnable) starts the toolkit without an Application subclass (tests, embedding, a JavaFX
      window opened from a console program). It may be called only once per JVM.
    - Swing interop: SwingNode / JFXPanel connect two toolkits, each with its own UI thread. Use
      SwingUtilities.invokeLater for Swing components and Platform.runLater for FX nodes; never block one UI thread
      waiting for the other.
    - Shared model objects read by the UI and written by workers need the usual rules from Mod002–Mod005: publish
      immutable snapshots, or confine the model to the FX thread and send it messages with runLater.
    */
    static Node pitfalls() {
        System.out.println("[Section 8] pitfalls and remaining tools");

        var frameClock = new Label();
        var frames = new AtomicInteger();
        var timer = new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                frameClock.setText("AnimationTimer: frame " + frames.incrementAndGet() + " at " + now());
            }
        };
        timer.start();

        var tick = new Label();
        var timeline = new Timeline(new KeyFrame(Duration.seconds(1), _ -> tick.setText("Timeline tick on " + threadName() + " at " + now())));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();

        var nested = new Label();
        var waitNested = new Button("Wait for a worker in a nested event loop");
        waitNested.setOnAction(_ -> {
            var key = new Object();
            WORKERS.submit(() -> {
                sleep(2_000);
                String result = "computed on " + threadName();
                Platform.runLater(() -> Platform.exitNestedEventLoop(key, result));
            });
            nested.setText("waiting… (the window keeps repainting)");
            log("entering nested event loop");
            Object result = Platform.enterNestedEventLoop(key);   // "blocks" here, but events keep flowing
            log("nested event loop returned: " + result);
            nested.setText("handler resumed with: " + result);
        });

        return section("Both clocks run on the FX thread without any background thread.",
                frameClock, tick, waitNested, nested);
    }

    /** The JavaFX application. Nested so that the main class does not extend Application (see header comment). */
    public static final class DemoApp extends Application {

        @Override
        public void init() {
            log("Application.init()");
        }

        @Override
        public void start(Stage stage) {
            log("Application.start()");
            var tabs = new TabPane(
                    tab("1 FX thread", lifecycle()),
                    tab("2 Blocking", blocking()),
                    tab("3 Wrong thread", wrongThread()),
                    tab("4 runLater", runLaterFlood()),
                    tab("5 Task", task()),
                    tab("6 Service", services()),
                    tab("7 CompletableFuture", completableFuture()),
                    tab("8 Pitfalls", pitfalls()));
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            stage.setTitle("Mod015 — Threads and JavaFX");
            stage.setScene(new Scene(tabs, 900, 520));
            stage.show();
        }

        @Override
        public void stop() {
            log("Application.stop()");
            WORKERS.shutdownNow();
        }
    }

    private static Tab tab(String title, Node content) {
        return new Tab(title, content);
    }

    private static VBox section(String description, Node... content) {
        var box = new VBox(10);
        box.setPadding(new Insets(16));
        var header = new Label(description);
        header.setWrapText(true);
        box.getChildren().add(header);
        box.getChildren().addAll(content);
        return box;
    }

    private static String listThreads() {
        var sb = new StringBuilder("Live platform threads:\n");
        Thread.getAllStackTraces().keySet().stream()
                .map(t -> String.format(Locale.ROOT, "  %-32s daemon=%-5s state=%s", t.getName(), t.isDaemon(), t.getState()))
                .sorted()
                .forEach(line -> sb.append(line).append('\n'));
        return sb.toString();
    }

    private static String threadName() {
        var t = Thread.currentThread();
        return t.isVirtual() ? "virtual#" + t.threadId() : t.getName();
    }

    private static void log(String message) {
        System.out.println("  [" + threadName() + "] " + message);
    }

    private static String now() {
        return LocalTime.now().format(TIME);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) {
        log("main() calls Application.launch — it returns only when the application exits");
        Application.launch(DemoApp.class, args);
        System.out.println("Mod015JavaFxThreads finished");
    }
}
