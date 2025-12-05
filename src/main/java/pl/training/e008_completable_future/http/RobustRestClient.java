package pl.training.e008_completable_future.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class RobustRestClient {

    private final HttpClient httpClient;
    private final int maxRetries;
    private final Duration retryDelay;

    public RobustRestClient(int maxRetries, Duration retryDelay) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
    }

    public CompletableFuture<HttpResponse<String>> sendWithRetry(
            HttpRequest request,
            Predicate<HttpResponse<String>> shouldRetry) {

        return sendWithRetryInternal(request, shouldRetry, 0);
    }

    private CompletableFuture<HttpResponse<String>> sendWithRetryInternal(
            HttpRequest request,
            Predicate<HttpResponse<String>> shouldRetry,
            int attemptNumber) {

        System.out.println("Próba " + (attemptNumber + 1) + "/" + (maxRetries + 1) +
                " dla: " + request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {

                    if (shouldRetry.test(response) && attemptNumber < maxRetries) {

                        System.out.println("Status " + response.statusCode() +
                                " - czekam " + retryDelay.toMillis() + "ms przed retry...");

                        // ❗ FIXED DELAYED RETRY
                        return CompletableFuture.runAsync(
                                () -> {}, 
                                CompletableFuture.delayedExecutor(
                                        retryDelay.toMillis(),
                                        TimeUnit.MILLISECONDS
                                )
                        ).thenCompose(v ->
                                sendWithRetryInternal(request, shouldRetry, attemptNumber + 1)
                        );
                    }

                    return CompletableFuture.completedFuture(response);
                })
                .exceptionally(ex -> {
                    System.err.println("Błąd podczas zapytania: " + ex.getMessage());

                    if (attemptNumber < maxRetries) {
                        System.out.println("Wyjątek - próbuję ponownie...");

                        try {
                            Thread.sleep(retryDelay.toMillis());
                            return sendWithRetryInternal(request, shouldRetry, attemptNumber + 1).join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }

                    throw new RuntimeException("Wszystkie próby nie powiodły się", ex);
                });
    }

    public CompletableFuture<String> get(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        Predicate<HttpResponse<String>> shouldRetry = response -> {
            int status = response.statusCode();
            return status >= 500 || status == 429;
        };

        return sendWithRetry(request, shouldRetry)
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new RuntimeException("HTTP Error " + response.statusCode() +
                                ": " + response.body());
                    }
                    return response.body();
                });
    }

    public CompletableFuture<String> postJson(String url, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        Predicate<HttpResponse<String>> shouldRetry =
                response -> response.statusCode() >= 500;

        return sendWithRetry(request, shouldRetry)
                .thenApply(HttpResponse::body);
    }

    public static void main(String[] args) {

        RobustRestClient client = new RobustRestClient(3, Duration.ofSeconds(2));

        System.out.println("=== Test 1: GET z automatycznym retry ===");

        client.get("https://httpbin.org/status/500")
                .exceptionally(ex -> {
                    System.err.println("Ostateczny błąd: " + ex.getMessage());
                    return null;
                })
                .join();

        System.out.println("\n=== Test 2: Poprawny GET ===");

        client.get("https://api.github.com/users/octocat")
                .thenAccept(body ->
                        System.out.println("Sukces! Długość odpowiedzi: " + body.length() + " znaków"))
                .join();

        System.out.println("\n=== Test 3: POST JSON ===");

        String json = "{\"name\": \"Test\", \"job\": \"Developer\"}";
        client.postJson("https://reqres.in/api/users", json)
                .thenAccept(response -> {
                    System.out.println("POST zakończony sukcesem!");
                    System.out.println("Response: " + response);
                })
                .join();
    }
}
