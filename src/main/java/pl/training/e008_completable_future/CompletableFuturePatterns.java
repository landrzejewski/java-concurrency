package pl.training.e008_completable_future;

import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

public class CompletableFuturePatterns {
    
    public static void main(String[] args) {
        System.out.println("=== Wzorzec 1: Timeout ===");
        timeoutExample();
        
        System.out.println("\n=== Wzorzec 2: Retry ===");
        retryExample();
        
        System.out.println("\n=== Wzorzec 3: Fallback ===");
        fallbackExample();
        
        System.out.println("\n=== Wzorzec 4: Pipeline z thenCompose ===");
        thenComposeExample();
    }
    
    // Wzorzec 1: Timeout dla operacji asynchronicznych
    static void timeoutExample() {
        CompletableFuture<String> slowOperation = CompletableFuture.supplyAsync(() -> {
            sleep(5000); // Symulacja wolnej operacji
            return "Dane z wolnego API";
        });
        
        CompletableFuture<String> timeout = new CompletableFuture<>();
        
        // Scheduler który ukończy future po 2 sekundach
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.schedule(() -> {
            timeout.completeExceptionally(new TimeoutException("Operacja przekroczyła limit czasu!"));
        }, 2, TimeUnit.SECONDS);
        
        CompletableFuture<String> result = slowOperation
            .applyToEither(timeout, Function.identity())
            .exceptionally(ex -> "Timeout! Używam cache: wartość z pamięci");
        
        System.out.println(result.join());
        scheduler.shutdown();
    }
    
    // Wzorzec 2: Retry z eksponencjalnym backoff
    static void retryExample() {
        final int MAX_RETRIES = 3;
        
        CompletableFuture<String> result = retryWithBackoff(
            () -> unreliableOperation(),
            MAX_RETRIES,
            100 // początkowe opóźnienie w ms
        );
        
        System.out.println("Wynik po retry: " + result.join());
    }
    
    static CompletableFuture<String> retryWithBackoff(
            Supplier<CompletableFuture<String>> operation,
            int maxRetries,
            long initialDelay) {
        
        CompletableFuture<String> result = operation.get();
        
        for (int i = 0; i < maxRetries; i++) {
            final int attempt = i;
            final long delay = initialDelay * (long) Math.pow(2, i);
            
            result = result.exceptionally(ex -> {
                System.out.println("Próba " + (attempt + 1) + " nie powiodła się: " + ex.getMessage());
                System.out.println("Czekam " + delay + "ms przed kolejną próbą...");
                sleep((int) delay);
                return null;
            }).thenCompose(res -> {
                if (res != null) {
                    return CompletableFuture.completedFuture(res);
                }
                return operation.get();
            });
        }
        
        return result;
    }
    
    static CompletableFuture<String> unreliableOperation() {
        return CompletableFuture.supplyAsync(() -> {
            if (Math.random() > 0.7) { // 30% szans na sukces
                return "Sukces!";
            }
            throw new RuntimeException("Tymczasowy błąd sieciowy");
        });
    }
    
    // Wzorzec 3: Fallback chain (łańcuch alternatywnych źródeł)
    static void fallbackExample() {
        CompletableFuture<String> primary = fetchFromPrimarySource();
        CompletableFuture<String> secondary = fetchFromSecondarySource();
        CompletableFuture<String> cache = fetchFromCache();
        
        CompletableFuture<String> result = primary
            .exceptionally(ex -> {
                System.out.println("Primary source failed: " + ex.getMessage());
                return null;
            })
            .thenCompose(data -> {
                if (data != null) {
                    return CompletableFuture.completedFuture(data);
                }
                return secondary;
            })
            .exceptionally(ex -> {
                System.out.println("Secondary source failed: " + ex.getMessage());
                return null;
            })
            .thenCompose(data -> {
                if (data != null) {
                    return CompletableFuture.completedFuture(data);
                }
                return cache;
            });
        
        System.out.println("Ostateczny wynik: " + result.join());
    }
    
    static CompletableFuture<String> fetchFromPrimarySource() {
        return CompletableFuture.supplyAsync(() -> {
            throw new RuntimeException("Primary database is down");
        });
    }
    
    static CompletableFuture<String> fetchFromSecondarySource() {
        return CompletableFuture.supplyAsync(() -> {
            throw new RuntimeException("Secondary database is down");
        });
    }
    
    static CompletableFuture<String> fetchFromCache() {
        return CompletableFuture.supplyAsync(() -> "Dane z cache (stare 5 minut)");
    }
    
    // Wzorzec 4: thenCompose vs thenApply (spłaszczanie zagnieżdżeń)
    static void thenComposeExample() {
        System.out.println("--- Niepoprawne użycie thenApply (zagnieżdżone Future) ---");
        
        // ZŁE: thenApply zwraca CompletableFuture<CompletableFuture<User>>
        CompletableFuture<CompletableFuture<String>> nested = CompletableFuture
            .supplyAsync(() -> 12345L)
            .thenApply(userId -> fetchUserName(userId)); // Zwraca CF<CF<String>>
        
        // Trzeba dwukrotnie wywołać join()
        System.out.println("Zagnieżdżony typ: " + nested.getClass().getSimpleName());
        
        System.out.println("\n--- Poprawne użycie thenCompose (spłaszczenie) ---");
        
        // DOBRE: thenCompose spłaszcza do CompletableFuture<User>
        CompletableFuture<String> flat = CompletableFuture
            .supplyAsync(() -> 12345L)
            .thenCompose(userId -> fetchUserName(userId)); // Zwraca CF<String>
        
        System.out.println("Spłaszczony wynik: " + flat.join());
    }
    
    static CompletableFuture<String> fetchUserName(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            sleep(500);
            return "User_" + userId;
        });
    }
    
    static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}