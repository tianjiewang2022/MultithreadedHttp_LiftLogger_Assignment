package clients;

import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

public class MultithreadedHttpClient2 {
    private static final int TOTAL_REQUESTS = 200000;  // Reduced from 200,000 for testing purposes
    private static final int INITIAL_THREADS = 32;    // Reduced from 32
    private static final int REQUESTS_PER_THREAD = 1000;  // Reduced from 1000
    private static final int MAX_RETRIES = 5;        // Retry limit reduced for quicker testing
    private static final BlockingQueue<JSONObject> eventQueue = new ArrayBlockingQueue<>(1000);
    private static int successfulRequests = 0;
    private static int failedRequests = 0;
    
    // Store latencies for each request
    private static List<Long> latencies = new ArrayList<>();
    private static long testStartTime;
    
    // CSV Writer
    private static PrintWriter csvWriter;
    
    public static void main(String[] args) throws InterruptedException, IOException {
        // Set up CSV file for logging request performance
        csvWriter = new PrintWriter(new FileWriter("request_performance.csv"));
        csvWriter.println("start_time,request_type,latency,response_code");
        
        // Start event generation thread
        Thread eventGeneratorThread = new Thread(new EventGenerator());
        eventGeneratorThread.start();
        
        // Initialize test start time
        testStartTime = System.currentTimeMillis();
        
        // CountDownLatch to wait for all threads to complete
        CountDownLatch latch = new CountDownLatch(INITIAL_THREADS);
        
        // Start 4 initial threads (you can increase this as per your requirement)
        for (int i = 0; i < INITIAL_THREADS; i++) {
            new Thread(new PostingThread(latch)).start();
        }
        
        // Wait for threads to complete
        latch.await();
        
        // Record the end time of the test
        long testEndTime = System.currentTimeMillis();
        long wallTime = testEndTime - testStartTime; // Total time taken for all requests
        
        // Calculate throughput
        double throughput = (double) TOTAL_REQUESTS / (wallTime / 1000.0); // requests per second
        
        // Calculate statistics on latencies
        long meanLatency = calculateMean(latencies);
        long medianLatency = calculateMedian(latencies);
        long p99Latency = calculatePercentile(latencies, 99);
        long minLatency = Collections.min(latencies);
        long maxLatency = Collections.max(latencies);
        
        // Print the results
        System.out.println("Mean response time: " + meanLatency + " ms");
        System.out.println("Median response time: " + medianLatency + " ms");
        System.out.println("Throughput: " + throughput + " requests/second");
        System.out.println("p99 response time: " + p99Latency + " ms");
        System.out.println("Min response time: " + minLatency + " ms");
        System.out.println("Max response time: " + maxLatency + " ms");
        System.out.println("Total successful requests: " + successfulRequests);
        System.out.println("Total failed requests: " + failedRequests);
        
        // Close the CSV writer
        csvWriter.close();
    }
    
    // Event generator that generates 200,000 lift ride events
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
    
    // Posting thread that sends requests to the server
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
                    event = eventQueue.take(); // Get event from queue
                    sendPostRequest(event); // Send POST request
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            latch.countDown(); // Signal that this thread has finished
        }
        
        private void sendPostRequest(JSONObject event) {
            int retries = 0;
            long startTime = System.currentTimeMillis();  // Record start time of the request
            while (retries < MAX_RETRIES) {
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL("http://35.95.11.105:8080/JavaServlets_war/skiers/9/seasons/2025/day/1/skier/20").openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.getOutputStream().write(event.toString().getBytes());
                    
                    int responseCode = connection.getResponseCode();
                    long endTime = System.currentTimeMillis();  // Record end time of the request
                    long latency = endTime - startTime;  // Calculate latency
                    
                    // Log the request performance into CSV
                    synchronized (MultithreadedHttpClient.class) {
                        csvWriter.println(startTime + ",POST," + latency + "," + responseCode);  // Log to CSV
                        latencies.add(latency);  // Store latency for future analysis
                    }
                    
                    if (responseCode == 201) {
                        synchronized (MultithreadedHttpClient.class) {
                            successfulRequests++;
                        }
                        break; // Success, break out of retry loop
                    } else {
                        retries++;
                        if (responseCode >= 500) {
                            // Retry for 5XX server errors
                            System.out.println("Server error, retrying... " + retries);
                        } else {
                            // Don't retry for 4XX client errors
                            synchronized (MultithreadedHttpClient.class) {
                                failedRequests++;
                            }
                            break;
                        }
                    }
                } catch (IOException e) {
                    retries++;
                    System.out.println("Request failed, retrying... " + retries);
                }
            }
        }
    }
    
    // Calculate the mean of the latencies
    private static long calculateMean(List<Long> latencies) {
        long sum = 0;
        for (long latency : latencies) {
            sum += latency;
        }
        return sum / latencies.size();
    }
    
    // Calculate the median of the latencies
    private static long calculateMedian(List<Long> latencies) {
        Collections.sort(latencies);
        if (latencies.size() % 2 == 0) {
            return (latencies.get(latencies.size() / 2 - 1) + latencies.get(latencies.size() / 2)) / 2;
        } else {
            return latencies.get(latencies.size() / 2);
        }
    }
    
    // Calculate the nth percentile of latencies
    private static long calculatePercentile(List<Long> latencies, int percentile) {
        Collections.sort(latencies);
        int index = (int) Math.ceil(percentile / 100.0 * latencies.size()) - 1;
        return latencies.get(index);
    }
}
