package pl.training.solution4;

public class TokenBucket {

    private final int capacity;
    private final long frequencyInMilliseconds;

    private long lastRequestTime = System.currentTimeMillis();
    private long tokensCount = 0;

    public TokenBucket(int capacity, long frequencyInMilliseconds) {
        this.capacity = capacity;
        this.frequencyInMilliseconds = frequencyInMilliseconds;
    }

    public synchronized void getToken() throws InterruptedException {
        tokensCount += (System.currentTimeMillis() - lastRequestTime) / frequencyInMilliseconds;
        if (tokensCount > capacity) {
            tokensCount = capacity;
        }
        if (tokensCount == 0) {
            Thread.sleep(frequencyInMilliseconds);
        } else {
            tokensCount--;
        }
        lastRequestTime = System.currentTimeMillis();
    }

}
