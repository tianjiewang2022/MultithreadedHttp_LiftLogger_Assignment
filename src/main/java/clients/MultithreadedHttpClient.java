package clients;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class MultithreadedHttpClient {
    private static final int TOTAL_REQUESTS = 200000; // Reduced to 2000 for local testing
    private static final int INITIAL_THREADS = 32; // Reduced from 32 for local testing
    private static final int REQUESTS_PER_THREAD = 1000; // Reduced for quicker testing
    private static final int MAX_RETRIES = 5; // Lower retries for local testing
    private static final BlockingQueue<JSONObject> eventQueue = new ArrayBlockingQueue<>(1000); // Adjust size if necessary
    private static final AtomicInteger successfulRequests = new AtomicInteger(0);
    private static final AtomicInteger failedRequests = new AtomicInteger(0);
    
    public static void main(String[] args) throws InterruptedException {
        long startTime = System.currentTimeMillis(); // Track wall time
        
        // Start the event generation thread
        Thread eventGeneratorThread = new Thread(new EventGenerator());
        eventGeneratorThread.start();
        
        // CountDownLatch to track completion of the initial batch of threads
        CountDownLatch latch = new CountDownLatch(INITIAL_THREADS);
        
        // Start initial threads for posting requests
        for (int i = 0; i < INITIAL_THREADS; i++) {
            new Thread(new PostingThread(latch)).start();
        }
        
        // Wait for all initial threads to finish
        latch.await();
        
        // Dynamically spawn additional threads if necessary until all requests are sent
        while (successfulRequests.get() < TOTAL_REQUESTS) {
            new Thread(new PostingThread(latch)).start();
        }
        
        // Wait for all threads to finish
        latch.await();
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        
        // Print the results
        System.out.println("Total successful requests: " + successfulRequests.get());
        System.out.println("Total failed requests: " + failedRequests.get());
        System.out.println("Total run time: " + totalTime + " ms");
        System.out.println("Throughput (requests per second): " + (TOTAL_REQUESTS / (totalTime / 1000.0)));
    
//        /** Print results */
//        System.out.println("Successful requests: " + 200000);
//        System.out.println("Failed requests: " + 0);
//        System.out.println("Total run time: " + 522579 + " ms");
//        System.out.println("Total throughput: " + 398.4137287881564 + " requests/sec");
    }
    
    // Event generator that generates lift ride events
    static class EventGenerator implements Runnable {
        @Override
        public void run() {
            try {
                for (int i = 0; i < TOTAL_REQUESTS; i++) {
                    JSONObject event = new JSONObject();
                    event.put("time", (int) (Math.random() * 100)); // Random time
                    event.put("liftID", (int) (Math.random() * 10)); // Random lift ID
                    event.put("skierID", (int) (Math.random() * 100)); // Random skier ID
                    event.put("resortID", 12); // Example resort ID
                    event.put("seasonID", 2025); // Example season ID
                    event.put("dayID", 1); // Example day ID
                    
                    eventQueue.put(event); // Add event to the queue
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    // Posting thread to send requests to the server
    static class PostingThread implements Runnable {
        private final CountDownLatch latch;
        
        PostingThread(CountDownLatch latch) {
            this.latch = latch;
        }
        
        @Override
        public void run() {
            for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                JSONObject event;
                try {
                    event = eventQueue.take(); // Take event from the queue
                    sendPostRequest(event); // Send POST request
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            latch.countDown(); // Signal that this thread has finished
        }
        
        private void sendPostRequest(JSONObject event) {
            int retries = 0;
            while (retries < MAX_RETRIES) {
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL("http://35.95.11.105:8080/JavaServlets_war/skiers/9/seasons/2025/day/1/skier/20").openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.getOutputStream().write(event.toString().getBytes());
                    
                    int responseCode = connection.getResponseCode();
                    
                    if (responseCode == 201) {
                        successfulRequests.incrementAndGet(); // Successful request
                        break; // Exit retry loop on success
                    } else {
                        retries++;
                        if (responseCode >= 500) {
                            // Retry for 5XX errors
                            System.out.println("Server error, retrying... " + retries);
                        } else {
                            failedRequests.incrementAndGet(); // Increment failed request count
                            break; // Exit retry loop on client error (4XX)
                        }
                    }
                } catch (IOException e) {
                    retries++;
                    System.out.println("Request failed, retrying... " + retries);
                }
            }
        }
    }
}
