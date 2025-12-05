package pl.training.e008_completable_future;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TaskCoordination {

    private static final ExecutorService executor = Executors.newFixedThreadPool(6);

    static CompletableFuture<String> fetchUserData(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("[" + Thread.currentThread().getName() + "] Pobieram dane użytkownika...");
            sleep(1000);
            return "User: Jan Kowalski (id=" + userId + ")";
        }, executor);
    }

    static CompletableFuture<String> fetchOrders(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("[" + Thread.currentThread().getName() + "] Pobieram zamówienia...");
            sleep(1500);
            return "Zamówienia: [Laptop, Myszka, Klawiatura]";
        }, executor);
    }

    static CompletableFuture<String> fetchRecommendations(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("[" + Thread.currentThread().getName() + "] Pobieram rekomendacje...");
            sleep(800);
            return "Rekomendacje: [Monitor 4K, Słuchawki]";
        }, executor);
    }

    static CompletableFuture<String> fetchEmailNotifications(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("[" + Thread.currentThread().getName() + "] Pobieram powiadomienia EMAIL...");
            sleep(600);
            return "Email: 5 nowych wiadomości";
        }, executor);
    }

    static CompletableFuture<String> fetchSmsNotifications(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("[" + Thread.currentThread().getName() + "] Pobieram powiadomienia SMS...");
            sleep(400);
            return "SMS: 2 nowe wiadomości";
        }, executor);
    }

    static CompletableFuture<String> fetchAllNotifications(int userId) {
        CompletableFuture<String> emailFuture = fetchEmailNotifications(userId);
        CompletableFuture<String> smsFuture = fetchSmsNotifications(userId);

        return emailFuture.thenCombine(smsFuture, (email, sms) -> {
            System.out.println("[" + Thread.currentThread().getName() + "] Łączę powiadomienia...");
            return email + "\n" + sms;
        });
    }

    static void printReport(String userData, String orders, String recommendations, String notifications) {
        System.out.println("\n=== RAPORT ===");
        System.out.println(userData);
        System.out.println(orders);
        System.out.println(recommendations);
        System.out.println(notifications);
        System.out.println("==============\n");
    }

    void main() {
        System.out.println("=== Koordynacja zadań z CompletableFuture ===\n");
        long start = System.currentTimeMillis();

        int userId = 42;

        CompletableFuture<String> userFuture = fetchUserData(userId);

        CompletableFuture<String> ordersFuture = userFuture
                .thenCompose(user -> fetchOrders(userId));

        CompletableFuture<String> recommendationsFuture = userFuture
                .thenCompose(user -> fetchRecommendations(userId));

        CompletableFuture<String> notificationsFuture = userFuture
                .thenCompose(user -> fetchAllNotifications(userId));

        CompletableFuture<Void> reportFuture = userFuture
                .thenCombine(ordersFuture, (user, orders) -> new String[]{user, orders})
                .thenCombine(recommendationsFuture, (arr, recs) ->
                        new String[]{arr[0], arr[1], recs})
                .thenCombine(notificationsFuture, (arr, notif) ->
                        new String[]{arr[0], arr[1], arr[2], notif})
                .thenAccept(arr -> printReport(arr[0], arr[1], arr[2], arr[3]));

        reportFuture.join();

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Całkowity czas: %d ms%n", elapsed);

        executor.shutdown();
    }

    private static void sleep(int millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}