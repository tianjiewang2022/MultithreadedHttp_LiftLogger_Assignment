package clients;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;

public class MultithreadedHttpClient2 {
    
    /** Constant definitions for the number of threads, requests, server URL, and output file **/
    private static final int NUM_THREADS = 100;
    private static final int NUM_REQUESTS = 200000;
    private static final String SERVER_URL = "http://34.219.0.248:8080/JavaServlets_war/skiers";
    private static final String OUTPUT_FILE = "request_metrics.csv";
    
    /** Thread-safe list to store latencies and a concurrent queue to log request metrics **/
    private static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    private static final Queue<String> logQueue = new ConcurrentLinkedQueue<>();
    private static final Random random = new Random();
    
    public static void main(String[] args) throws InterruptedException, IOException {
        // Create an executor service with a fixed thread pool
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        
        // Create a scheduled executor service to periodically flush logs
        ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor();
        logExecutor.scheduleAtFixedRate(MultithreadedHttpClient2::flushLogs, 1, 1, TimeUnit.SECONDS);
        
        // Record start time to calculate the total wall time
        long startWallTime = System.currentTimeMillis();
        
        // Create a list of futures to track the completion of all requests
        List<Future<Void>> futures = new ArrayList<>();
        
        // Submit tasks to send requests to the server
        for (int i = 0; i < NUM_REQUESTS; i++) {
            futures.add(executor.submit(() -> {
                sendPostRequest();
                return null;
            }));
        }
        
        // Ensure all tasks complete by waiting for each future to finish
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                e.getCause().printStackTrace();
            }
        }
        
        // Shutdown executor and await termination
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.MINUTES);
        logExecutor.shutdown();
        
        // Record end time to calculate total wall time
        long endWallTime = System.currentTimeMillis();
        long wallTime = endWallTime - startWallTime;
        
        // Compute and display the performance metrics
        computeAndDisplayMetrics(wallTime);
    }
    
    /** Send a POST request to the server and measure latency **/
    private static void sendPostRequest() {
        long startTime = System.currentTimeMillis();  // Record request start time
        
        int responseCode = -1;
        int retries = 3;
        
        // Retry logic for handling failed requests
        while (retries-- > 0) {
            try {
                // Create a connection to the server and configure it
                URL url = new URL(SERVER_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                
                // Generate a random lift ride event in JSON format
                String jsonInputString = generateRandomLiftRideEvent();
                
                // Write the JSON data to the output stream
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
                
                // Get the response code and disconnect
                responseCode = connection.getResponseCode();
                connection.disconnect();
                break; // Exit retry loop if request was successful
            } catch (IOException e) {
                if (retries == 0) {
                    e.printStackTrace(); // Print stack trace if all retries fail
                }
            }
        }
        
        long endTime = System.currentTimeMillis(); // Record request end time
        long latency = endTime - startTime;  // Calculate latency
        latencies.add(latency); // Store latency for later processing
        
        // Log the request metrics (start time, request type, latency, response code)
        logQueue.add(startTime + ",POST," + latency + "," + responseCode);
    }
    
    /** Generate a random JSON lift ride event **/
    private static String generateRandomLiftRideEvent() {
        return String.format("{\"skierID\":%d,\"resortID\":%d,\"liftID\":%d,\"seasonID\":2025,\"dayID\":1,\"time\":%d}",
                random.nextInt(100000) + 1,
                random.nextInt(10) + 1,
                random.nextInt(40) + 1,
                random.nextInt(360) + 1);
    }
    
    /** Periodically flush logs from the logQueue to the output file **/
    private static void flushLogs() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, true))) {
            while (!logQueue.isEmpty()) {
                writer.write(logQueue.poll() + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace(); // Print stack trace if log flushing fails
        }
    }
    
    /** Compute and display various performance metrics based on collected latencies **/
    private static void computeAndDisplayMetrics(long wallTime) {
        if (latencies.isEmpty()) {
            System.out.println("No latency data recorded.");
            return;
        }
        
        // Sort latencies for statistical analysis
        Collections.sort(latencies);
        
        long totalRequests = latencies.size();
        LongSummaryStatistics stats = latencies.stream().mapToLong(Long::longValue).summaryStatistics();
        long medianResponseTime = latencies.get(latencies.size() / 2);
        double throughput = (double) totalRequests / (wallTime / 1000.0);
        long p99ResponseTime = latencies.get((int) (latencies.size() * 0.99));
        
        // Display the computed metrics
        System.out.println("Total Requests: " + totalRequests);
        System.out.println("Mean Response Time: " + stats.getAverage() + " ms");
        System.out.println("Median Response Time: " + medianResponseTime + " ms");
        System.out.println("Throughput: " + throughput + " requests/sec");
        System.out.println("P99 Response Time: " + p99ResponseTime + " ms");
        System.out.println("Min Response Time: " + stats.getMin() + " ms");
        System.out.println("Max Response Time: " + stats.getMax() + " ms");
    }
}
