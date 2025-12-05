package pl.training.e008_completable_future;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CompletableFutureBasics {
    
    void main() {
        System.out.println("=== Przykład 1: Proste użycie ===");
        simpleExample();
        
        System.out.println("\n=== Przykład 2: Łańcuchowanie ===");
        chainingExample();
        
        System.out.println("\n=== Przykład 3: Obsługa błędów ===");
        errorHandlingExample();
        
        System.out.println("\n=== Przykład 4: Kombinowanie Future ===");
        combiningFutures();
    }
    
    // Przykład 1: Podstawowe tworzenie i wykonywanie
    static void simpleExample() {
        // supplyAsync - asynchroniczne obliczenie z wynikiem
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            sleep(1000);
            return "Wynik obliczeń asynchronicznych";
        });
        
        System.out.println("Zadanie uruchomione, kontynuuję pracę...");
        
        // join() - czeka na wynik (blokująca)
        String result = future.join();
        System.out.println("Otrzymany wynik: " + result);
    }
    
    // Przykład 2: Łańcuchowanie operacji
    static void chainingExample() {
        CompletableFuture<String> future = CompletableFuture
            .supplyAsync(() -> {
                System.out.println("Krok 1: Pobieram dane z bazy (wątek: " + Thread.currentThread().getName() + ")");
                sleep(1000);
                return "Dane użytkownika";
            })
            .thenApply(data -> {
                System.out.println("Krok 2: Przetwarzam dane (wątek: " + Thread.currentThread().getName() + ")");
                return data.toUpperCase();
            })
            .thenApply(processed -> {
                System.out.println("Krok 3: Dodaję prefix (wątek: " + Thread.currentThread().getName() + ")");
                return "PROCESSED: " + processed;
            });
        
        System.out.println("Końcowy wynik: " + future.join());
    }
    
    // Przykład 3: Obsługa błędów
    static void errorHandlingExample() {
        CompletableFuture<String> future = CompletableFuture
            .supplyAsync(() -> {
                sleep(500);
                if (Math.random() > 0.5) {
                    throw new RuntimeException("Symulowany błąd!");
                }
                return "Sukces";
            })
            .exceptionally(ex -> {
                System.out.println("Przechwycono błąd: " + ex.getMessage());
                return "Wartość domyślna po błędzie";
            })
            .thenApply(result -> "Finalne przetworzenie: " + result);
        
        System.out.println("Wynik: " + future.join());
    }
    
    // Przykład 4: Kombinowanie wielu Future
    static void combiningFutures() {
        CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> {
            sleep(1000);
            return "Dane z API 1";
        });
        
        CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> {
            sleep(1500);
            return "Dane z API 2";
        });
        
        CompletableFuture<String> future3 = CompletableFuture.supplyAsync(() -> {
            sleep(800);
            return "Dane z API 3";
        });
        
        // Czekamy na wszystkie
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(future1, future2, future3);
        
        // Gdy wszystkie się zakończą, zbieramy wyniki
        CompletableFuture<String> combined = allFutures.thenApply(v -> {
            String result1 = future1.join();
            String result2 = future2.join();
            String result3 = future3.join();
            return String.format("Połączone: [%s, %s, %s]", result1, result2, result3);
        });
        
        System.out.println(combined.join());
    }
    
    static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}