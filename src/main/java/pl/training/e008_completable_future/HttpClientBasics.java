package pl.training.e008_completable_future;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class HttpClientBasics {
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    static void main() {
        System.out.println("=== Przykład 1: Synchroniczne GET ===");
        synchronousGet();
        
        System.out.println("\n=== Przykład 2: Asynchroniczne GET ===");
        asynchronousGet();
        
        System.out.println("\n=== Przykład 3: POST z JSON ===");
        postJson();
        
        System.out.println("\n=== Przykład 4: Równoległe zapytania ===");
        parallelRequests();
    }
    
    // Przykład 1: Klasyczne synchroniczne zapytanie
    static void synchronousGet() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/users/octocat"))
                .header("Accept", "application/json")
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(
                request, 
                HttpResponse.BodyHandlers.ofString()
            );
            
            System.out.println("Status: " + response.statusCode());
            System.out.println("Body preview: " + response.body().substring(0, Math.min(100, response.body().length())));
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Przykład 2: Asynchroniczne zapytanie z CompletableFuture
    static void asynchronousGet() {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/users/torvalds"))
            .header("Accept", "application/json")
            .build();
        
        CompletableFuture<HttpResponse<String>> responseFuture = 
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("Zapytanie wysłane asynchronicznie, mogę robić inne rzeczy...");
        
        // Przetwarzanie odpowiedzi
        responseFuture
            .thenApply(HttpResponse::body)
            .thenAccept(body -> {
                System.out.println("Otrzymano odpowiedź asynchronicznie!");
                System.out.println("Body preview: " + 
                                 body.substring(0, Math.min(100, body.length())));
            })
            .join(); // Czekamy na zakończenie (tylko dla demo)
    }
    
    // Przykład 3: POST request z JSON
    static void postJson() {
        String json = """
            {
                "title": "Test Issue",
                "body": "Issue created by HttpClient demo"
            }
            """;
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://httpbin.org/post"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                System.out.println("POST Status: " + response.statusCode());
                return response.body();
            })
            .thenAccept(body -> System.out.println("Response preview: " + 
                                                  body.substring(0, Math.min(150, body.length()))))
            .join();
    }
    
    // Przykład 4: Równoległe zapytania do wielu API
    static void parallelRequests() {
        String[] users = {"octocat", "torvalds", "gvanrossum"};
        
        CompletableFuture<?>[] futures = new CompletableFuture[users.length];
        
        for (int i = 0; i < users.length; i++) {
            final String user = users[i];
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/users/" + user))
                .build();
            
            futures[i] = httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    System.out.println("Odpowiedź dla " + user + 
                                     ": status " + response.statusCode());
                    return response.body();
                });
        }
        
        // Czekamy na wszystkie
        CompletableFuture.allOf(futures).join();
        System.out.println("Wszystkie równoległe zapytania zakończone!");
    }
}