package clients;

import com.google.gson.Gson;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.util.Random;
import java.util.concurrent.*;

public class MultithreadedHttpClient {
    private static final int TOTAL_REQUESTS = 200000;
    private static final int INITIAL_THREADS = 32;
    private static final int REQUESTS_PER_THREAD = 1000;
    private static final int MAX_RETRIES = 5;
    private static final String SERVER_URL = "http://34.219.0.248:8080/JavaServlets_war/skiers";
    
    private static final AtomicInteger successfulRequests = new AtomicInteger(0);
    private static final AtomicInteger failedRequests = new AtomicInteger(0);
    
    public static void main(String[] args) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(100);
        BlockingQueue<LiftRideEvent> queue = new LinkedBlockingQueue<>(TOTAL_REQUESTS);
        
        System.out.println("Starting request generation and processing...");
        long startTime = System.currentTimeMillis();
        
        Thread eventGenerator = new Thread(() -> {
            Random random = new Random();
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                LiftRideEvent liftRide = new LiftRideEvent(
                        random.nextInt(100000) + 1, // skierID
                        random.nextInt(10) + 1,     // resortID
                        random.nextInt(40) + 1,     // liftID
                        2025,                       // seasonID
                        1,                          // dayID
                        random.nextInt(360) + 1     // time
                );
                try {
                    queue.put(liftRide);
                    if (i % 10000 == 0) {
                        System.out.println("Generated " + i + " lift ride events...");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        eventGenerator.start();
        
        for (int i = 0; i < INITIAL_THREADS; i++) {
            executor.submit(() -> sendRequests(queue, REQUESTS_PER_THREAD));
        }
        
        int remainingRequests = TOTAL_REQUESTS - (INITIAL_THREADS * REQUESTS_PER_THREAD);
        int remainingThreads = remainingRequests / REQUESTS_PER_THREAD;
        
        for (int i = 0; i < remainingThreads; i++) {
            executor.submit(() -> sendRequests(queue, REQUESTS_PER_THREAD));
        }
        
        int finalRemainingRequests = remainingRequests % REQUESTS_PER_THREAD;
        if (finalRemainingRequests > 0) {
            executor.submit(() -> sendRequests(queue, finalRemainingRequests));
        }
        
        eventGenerator.join();
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
        
        long endTime = System.currentTimeMillis();
        long totalRunTime = endTime - startTime;
        double throughput = (double) successfulRequests.get() / (totalRunTime / 1000.0);
        
        System.out.println("Successful requests: " + successfulRequests.get());
        System.out.println("Failed requests: " + failedRequests.get());
        System.out.println("Total run time: " + totalRunTime + " ms");
        System.out.println("Total throughput: " + throughput + " requests/sec");
    }
    
    private static void sendRequests(BlockingQueue<LiftRideEvent> queue, int numRequests) {
        for (int i = 0; i < numRequests; i++) {
            try {
                LiftRideEvent liftRide = queue.take();
                boolean success = attemptRequest(liftRide);
                
                if (success) {
                    successfulRequests.incrementAndGet();
                } else {
                    failedRequests.incrementAndGet();
                }
                
                if ((successfulRequests.get() + failedRequests.get()) % 10000 == 0) {
                    System.out.println("Processed " + (successfulRequests.get() + failedRequests.get()) + " requests...");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private static boolean attemptRequest(LiftRideEvent liftRide) {
        int retries = 0;
        while (retries < MAX_RETRIES) {
            int statusCode = sendRequest(liftRide);
            if (statusCode == 201) {
                return true;
            }
            retries++;
        }
        return false;
    }
    
    private static int sendRequest(LiftRideEvent liftRide) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(SERVER_URL);
            httpPost.setHeader("Content-Type", "application/json");
            
            Gson gson = new Gson();
            String json = gson.toJson(liftRide);
            httpPost.setEntity(new StringEntity(json));
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                return response.getCode();
            }
        } catch (Exception e) {
            System.err.println("Request failed: " + e.getMessage());
            return -1;
        }
    }
}
