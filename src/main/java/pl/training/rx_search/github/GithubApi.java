package pl.training.rx_search.github;

import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

import java.util.List;

public interface GithubApi {

    @GET("users/{username}/repos")
    Observable<List<Repository>> getUserRepositories(@Path("username") String username);

    @GET("/search/repositories")
    Observable<QueryResult> getRepositories(@Query("q") String query);

}
