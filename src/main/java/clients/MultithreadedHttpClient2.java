package clients;

import com.google.gson.Gson;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class MultithreadedHttpClient2 {
    private static final int TOTAL_REQUESTS = 200000;
    private static final int INITIAL_THREADS = 32;
    private static final int REQUESTS_PER_THREAD = 1000;
    private static final int MAX_RETRIES = 5;
    private static final String SERVER_URL = "http://34.219.0.248:8080/JavaServlets_war/skiers";
    private static final String CSV_FILE = "request_metrics.csv";
    
    private static final AtomicInteger successfulRequests = new AtomicInteger(0);
    private static final AtomicInteger failedRequests = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue<RequestMetric> metrics = new ConcurrentLinkedQueue<>();
    
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
        
        // Write metrics to CSV file
        writeMetricsToCSV();
        
        // Calculate and display statistics
        calculateAndDisplayStatistics();
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
            RequestMetric metric = sendRequest(liftRide);
            if (metric.getResponseCode() == 201) {
                metrics.add(metric);
                return true;
            }
            retries++;
        }
        // Add the last failed attempt metric
        metrics.add(new RequestMetric(System.currentTimeMillis(), "POST", 0, -1));
        return false;
    }
    
    private static RequestMetric sendRequest(LiftRideEvent liftRide) {
        long startTime = System.currentTimeMillis();
        int statusCode = -1;
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(SERVER_URL);
            httpPost.setHeader("Content-Type", "application/json");
            
            Gson gson = new Gson();
            String json = gson.toJson(liftRide);
            httpPost.setEntity(new StringEntity(json));
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                statusCode = response.getCode();
            }
        } catch (Exception e) {
            System.err.println("Request failed: " + e.getMessage());
        }
        
        long endTime = System.currentTimeMillis();
        long latency = endTime - startTime;
        
        return new RequestMetric(startTime, "POST", latency, statusCode);
    }
    
    private static void writeMetricsToCSV() {
        System.out.println("Writing metrics to CSV file...");
        try (FileWriter csvWriter = new FileWriter(CSV_FILE)) {
            // Write header
            csvWriter.append("StartTime,RequestType,Latency,ResponseCode\n");
            
            // Write data
            for (RequestMetric metric : metrics) {
                csvWriter.append(String.valueOf(metric.getStartTime())).append(",");
                csvWriter.append(metric.getRequestType()).append(",");
                csvWriter.append(String.valueOf(metric.getLatency())).append(",");
                csvWriter.append(String.valueOf(metric.getResponseCode())).append("\n");
            }
            
            csvWriter.flush();
            System.out.println("Metrics written to " + CSV_FILE);
        } catch (IOException e) {
            System.err.println("Failed to write metrics to CSV: " + e.getMessage());
        }
    }
    
    private static void calculateAndDisplayStatistics() {
        System.out.println("Calculating statistics...");
        
        // Filter out failed requests for latency calculations
        List<Long> latencies = new ArrayList<>();
        for (RequestMetric metric : metrics) {
            if (metric.getResponseCode() == 201) {
                latencies.add(metric.getLatency());
            }
        }
        
        // Sort latencies for percentile calculations
        Collections.sort(latencies);
        
        if (latencies.isEmpty()) {
            System.out.println("No successful requests to calculate statistics.");
            return;
        }
        
        // Calculate statistics
        double mean = calculateMean(latencies);
        double median = calculateMedian(latencies);
        double p99 = calculatePercentile(latencies, 99);
        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        
        // Display statistics
        System.out.println("\nRequest Latency Statistics:");
        System.out.println("Mean response time: " + String.format("%.2f", mean) + " ms");
        System.out.println("Median response time: " + String.format("%.2f", median) + " ms");
        System.out.println("p99 (99th percentile) response time: " + String.format("%.2f", p99) + " ms");
        System.out.println("Min response time: " + min + " ms");
        System.out.println("Max response time: " + max + " ms");
    }
    
    private static double calculateMean(List<Long> values) {
        long sum = 0;
        for (Long value : values) {
            sum += value;
        }
        return (double) sum / values.size();
    }
    
    private static double calculateMedian(List<Long> values) {
        int size = values.size();
        if (size % 2 == 0) {
            return (values.get(size / 2 - 1) + values.get(size / 2)) / 2.0;
        } else {
            return values.get(size / 2);
        }
    }
    
    private static double calculatePercentile(List<Long> values, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * values.size()) - 1;
        return values.get(index);
    }
}

class RequestMetric {
    private final long startTime;
    private final String requestType;
    private final long latency;
    private final int responseCode;
    
    public RequestMetric(long startTime, String requestType, long latency, int responseCode) {
        this.startTime = startTime;
        this.requestType = requestType;
        this.latency = latency;
        this.responseCode = responseCode;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public String getRequestType() {
        return requestType;
    }
    
    public long getLatency() {
        return latency;
    }
    
    public int getResponseCode() {
        return responseCode;
    }
}