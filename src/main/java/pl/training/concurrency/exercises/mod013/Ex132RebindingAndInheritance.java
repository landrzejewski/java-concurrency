package pl.training.concurrency.exercises.mod013;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

/**
 * Exercise 13.2 — Rebinding and inheritance into a scope.
 *
 * <p>Related: Mod013 §4, §5
 *
 * <p>Compile and run with {@code --enable-preview} — StructuredTaskScope is still preview in Java 26.
 */
public final class Ex132RebindingAndInheritance {

    private Ex132RebindingAndInheritance() {}

    static final ScopedValue<String> CURRENT_USER = ScopedValue.newInstance();
    static final ScopedValue<String> TRACE_ID     = ScopedValue.newInstance();

    public static void main(String[] args) throws InterruptedException {

        System.out.println("[1] a nested binding shadows the outer one, then the outer one comes back");
        ScopedValue.where(CURRENT_USER, "alice").run(() -> {
            System.out.println("  outer                : " + CURRENT_USER.get());
            ScopedValue.where(CURRENT_USER, "system").run(() ->
                    System.out.println("  inner (audit-log call): " + CURRENT_USER.get()));
            System.out.println("  outer restored       : " + CURRENT_USER.get());
        });
        System.out.println("  → lexical shadowing: the name is the same, the value differs inside the inner block,");
        System.out.println("    and the runtime unwinds the inner binding even if the block throws");

        System.out.println("[2] sub-tasks inherit the binding — no setter, no parameter passing");
        ScopedValue.where(TRACE_ID, "req-42").run(() -> {
            try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
                for (int i = 0; i < 3; i++) {
                    final int branch = i;
                    scope.fork(() -> "branch " + branch + " sees TRACE_ID=" + TRACE_ID.get()
                            + " on " + Thread.currentThread());
                }
                scope.join().forEach(line -> System.out.println("  " + line));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        System.out.println("[3] a sub-task rebinding is invisible to its siblings and to the parent");
        ScopedValue.where(TRACE_ID, "req-42").run(() -> {
            try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
                var rebound = new CountDownLatch(1);
                scope.fork(() -> ScopedValue.where(TRACE_ID, "req-HIJACKED").call(() -> {
                    rebound.countDown();
                    Thread.sleep(50);                      // hold the rebinding open
                    return "rebinding branch sees " + TRACE_ID.get();
                }));
                scope.fork(() -> {
                    rebound.await();                       // read WHILE the sibling's rebinding is active
                    return "sibling branch  sees " + TRACE_ID.get();
                });
                scope.join().forEach(line -> System.out.println("  " + line));
                System.out.println("  parent          sees " + TRACE_ID.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        System.out.println("  → a binding only ever extends DOWN the call tree from its own run()/call()");

        System.out.println("[4] the same propagation with InheritableThreadLocal — and its stale copy");
        var inheritable = new InheritableThreadLocal<String>();
        inheritable.set("req-42");
        var childrenStarted = new CountDownLatch(3);
        var parentChanged = new CountDownLatch(1);
        var children = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 3; i++) {
            final int branch = i;
            children.add(Thread.ofPlatform().start(() -> {
                childrenStarted.countDown();
                try { parentChanged.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                System.out.println("  child " + branch + " still sees " + inheritable.get());
            }));
        }
        childrenStarted.await();
        inheritable.set("req-99");                         // the parent moves on AFTER forking
        parentChanged.countDown();
        for (Thread child : children) child.join();
        System.out.println("  parent now sees " + inheritable.get() + " — the children kept the stale copy");

        // The structural difference, which is NOT "one inherits and the other does not":
        // InheritableThreadLocal IS inherited by StructuredTaskScope sub-tasks too (the default thread factory
        // inherits inheritable thread locals). The difference is HOW.
        //  - InheritableThreadLocal COPIES the parent's whole inheritable-thread-local map into every child at
        //    creation time. A fan-out of N children pays N copies, and — as [4] shows — every copy is frozen at
        //    fork time, so a later change by the parent never reaches a running child. Nothing is unwound either:
        //    the value lives as long as the child thread does.
        //  - A ScopedValue binding is ONE immutable node that parent and children SHARE. Adding a sub-task is
        //    O(1) and copies nothing, the view is always consistent because nobody can mutate the node, and the
        //    binding is unwound automatically when the enclosing block exits.
        System.out.println("Ex132RebindingAndInheritance finished");
    }
}
