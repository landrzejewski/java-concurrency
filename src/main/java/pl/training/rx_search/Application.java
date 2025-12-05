package pl.training.rx_search;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import pl.training.rx_search.github.GithubService;
import pl.training.rx_search.github.Repository;
import pl.training.rx_search.wikipedia.Article;
import pl.training.rx_search.wikipedia.WikipediaService;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static io.reactivex.Observable.zip;

public class Application {

    private final GithubService githubService = new GithubService(retrofitBuilder("https://api.github.com/"));
    private final WikipediaService wikipediaService = new WikipediaService(retrofitBuilder("https://en.wikipedia.org/w/"));
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    private Retrofit retrofitBuilder(String url) {
        return new Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(JacksonConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .client(new OkHttpClient.Builder()
                        .addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                        .build())
                .build();
    }

    private List<String> combine(List<String> result, String value) {
        List<String> newResult = new ArrayList<>(result);
        newResult.add(value);
        return newResult;
    }

    private List<String> combineResults(List<String> results, List<String> otherResults) {
        List<String> newResults = new ArrayList<>();
        newResults.addAll(results);
        newResults.addAll(otherResults);
        return results;
    }

    private Observable<List<String>> sendWikipediaQuery(String query) {
        return wikipediaService.getArticles(query)
                .flatMap(Observable::fromIterable)
                .map(Article::getTitle)
                .reduce(new ArrayList<>(), this::combine)
                .toObservable()
                .subscribeOn(Schedulers.io());
    }

    private Observable<List<String>> sendGithubQuery(String query) {
        return githubService.getRepositories(query)
                .flatMap(Observable::fromIterable)
                .map(Repository::getName)
                .reduce(new ArrayList<>(), this::combine)
                .toObservable()
                .subscribeOn(Schedulers.io());
    }

    private Predicate<String> hasLength(int length) {
        return text -> text.length() > length;
    }

    private Observable<List<String>> sendQueries(String text) {
        return zip(sendGithubQuery(text), sendWikipediaQuery(text), this::combineResults);
    }

    private void start() {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(compositeDisposable::dispose));

        ObservableReader.from(System.in)
                .debounce(5, TimeUnit.SECONDS)
                .flatMap(text -> Observable.zip(sendGithubQuery(text), sendWikipediaQuery(text), this::combineResults))
                .subscribe(System.out::println, System.out::println, () -> System.out.println("Completed"));

       /* var stream = githubService.getRepositories("java")
                .flatMap(Observable::fromIterable)
                .map(Repository::getName)
                .map(String::toLowerCase)
                .filter(hasLength(16))
                .sorted()
                .distinct();*/

//
//        Integer a = null;
//        var test = Optional.ofNullable(a)
//                .flatMap(integer -> Optional.of(integer * 2));
//
//        System.out.println(test);
//
//
//        var stream = ObservableReader.from(System.in)
//                .debounce(5, TimeUnit.SECONDS)
//                .flatMap(this::sendQueries)
//                .flatMap(Observable::fromIterable)
//                .map(String::toLowerCase)
//                .filter(hasLength(16));

    //    var disposable = stream.subscribe(System.out::println, System.out::println, () -> System.out.println("Completed"));
      //  compositeDisposable.add(disposable);
    }

    private Predicate<String> isShort() {
        return text -> text.length() <= 4;
    }


    public static void main(String[] args) throws InterruptedException {
        new Application().start();
        Thread.sleep(10_000);
    }
}

/*

https://proandroiddev.com/understanding-rxjava-subscribeon-and-observeon-744b0c6a41ea

We can specify a thread to execute any operator by using subscribeOn and/or observeOn.

subscribeOn affects upstream operators (operators above the subscribeOn)

observeOn affects downstream operators (operators below the observeOn)

If only subscribeOn is specified, all operators will be be executed on that thread

If only observeOn is specified, all operators will be executed on the current thread and only operators
below the observeOn will be switched to thread specified by the observeOn

 */
