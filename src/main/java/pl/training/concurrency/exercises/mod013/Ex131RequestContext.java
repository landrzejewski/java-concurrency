package pl.training.concurrency.exercises.mod013;

import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * Exercise 13.1 — Request context without parameter passing.
 *
 * <p>Related: Mod013 §2, §3
 */
public final class Ex131RequestContext {

    private Ex131RequestContext() {}

    // Typed keys, declared once for the whole program.
    static final ScopedValue<String> CURRENT_USER = ScopedValue.newInstance();
    static final ScopedValue<String> TENANT_ID    = ScopedValue.newInstance();
    static final ScopedValue<String> TRACE_ID     = ScopedValue.newInstance();

    static final class UnauthenticatedException extends RuntimeException {
        UnauthenticatedException() { super("no user bound to this request"); }
    }

    // main declares IOException because call() lets the body's CHECKED exception through unwrapped —
    // that propagation is the point of [3], and the compiler enforces it right here.
    public static void main(String[] args) throws IOException {

        System.out.println("[1] bind three keys at once and read them three call levels down");
        ScopedValue.where(CURRENT_USER, "alice")
                .where(TENANT_ID, "acme")
                .where(TRACE_ID, "req-42")
                .run(Ex131RequestContext::controller);
        System.out.println("  → controller, service and repository take ZERO context parameters");

        System.out.println("[2] reading outside a binding");
        try {
            CURRENT_USER.get();
            throw new AssertionError("should not reach here");
        } catch (NoSuchElementException e) {
            System.out.println("  get()                        -> " + e.getClass().getSimpleName());
        }
        System.out.println("  isBound()                    -> " + CURRENT_USER.isBound());
        System.out.println("  orElse(\"anonymous\")          -> " + CURRENT_USER.orElse("anonymous"));
        try {
            CURRENT_USER.orElseThrow(UnauthenticatedException::new);
            throw new AssertionError("should not reach here");
        } catch (UnauthenticatedException e) {
            System.out.println("  orElseThrow(Unauthenticated) -> " + e.getMessage());
        }
        System.out.println("  → pick per call site: orElse for an optional slot, orElseThrow for a required one,");
        System.out.println("    isBound when the same code runs both inside and outside a request");

        System.out.println("[3] the value-returning call() form propagates checked exceptions unwrapped");
        String receipt = ScopedValue.where(CURRENT_USER, "alice")
                .where(TENANT_ID, "acme")
                .call(Ex131RequestContext::renderReceipt);
        System.out.println("  " + receipt);
        try {
            ScopedValue.where(CURRENT_USER, "mallory")
                    .where(TENANT_ID, "acme")
                    .call(Ex131RequestContext::renderReceipt);
            throw new AssertionError("should not reach here");
        } catch (IOException e) {
            // Caught as IOException itself — no RuntimeException wrapper to unwrap, unlike run() would force.
            System.out.println("  checked IOException arrived unwrapped: " + e.getMessage());
        }

        System.out.println("[4] bindings nest naturally across independent requests");
        for (String user : new String[] { "bob", "carol" }) {
            ScopedValue.where(CURRENT_USER, user).where(TENANT_ID, "globex").where(TRACE_ID, "req-" + user)
                    .run(Ex131RequestContext::controller);
        }

        // Why this is easier to audit than a ThreadLocal holding the same data:
        //  - A ThreadLocal has a public set(): ANY code with a reference to the key can rewrite the value at any
        //    point, so "who set the current user?" is a whole-codebase search. A ScopedValue has no setter — the
        //    only places a value can come from are the where(...) call sites, which are grep-able and few.
        //  - The lifetime is lexical. A binding starts at run()/call() and ends when that block exits, unwound by
        //    the runtime even on an exception. A ThreadLocal value lives until someone remembers remove().
        //  - "Is a value bound here?" is answerable by reading the call stack, not by reasoning about which
        //    thread ran what earlier.
        System.out.println("Ex131RequestContext finished");
    }

    private static void controller() { service(); }

    private static void service() { repository(); }

    private static void repository() {
        System.out.println("  repository sees user=" + CURRENT_USER.get()
                + ", tenant=" + TENANT_ID.get()
                + ", trace=" + TRACE_ID.get()
                + " (trace bound? " + TRACE_ID.isBound() + ")");
    }

    /** Throws a CHECKED exception, to show that call() lets it through as-is. */
    private static String renderReceipt() throws IOException {
        String user = CURRENT_USER.get();
        if ("mallory".equals(user)) throw new IOException("receipt store unreachable for " + user);
        return "receipt for " + user + "@" + TENANT_ID.get();
    }
}
