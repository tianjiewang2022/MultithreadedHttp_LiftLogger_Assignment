package clients;

import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

public class MultithreadedHttpClient2 extends MultithreadedHttpClient {
    private static final String CSV_FILE = "request_metrics.csv";
    private static List<RequestMetrics> metrics = Collections.synchronizedList(new ArrayList<>());
    
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        System.out.println("Starting Multithreaded HTTP Client with Metrics...");
        
        // Start event producer thread
        Thread producerThread = new Thread(() -> {
            System.out.println("Producer thread started. Generating events...");
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                eventQueue.add(new LiftRideEvent());
                if (i % 10000 == 0) {
                    System.out.println("Generated " + i + " events.");
                }
            }
            System.out.println("Producer thread finished. Total events generated: " + TOTAL_REQUESTS);
        });
        producerThread.start();
        
        // Create initial threads
        ExecutorService executor = Executors.newFixedThreadPool(INITIAL_THREADS);
        System.out.println("Initializing " + INITIAL_THREADS + " threads...");
        for (int i = 0; i < INITIAL_THREADS; i++) {
            executor.submit(new RequestSenderWithMetrics(i + 1));
        }
        
        // Wait for all threads to complete
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        executor.shutdown();
        long endTime = System.currentTimeMillis();
        
        // Write metrics to CSV
        writeMetricsToCsv();
        
        // Calculate and display performance metrics
        calculateAndDisplayMetrics(startTime, endTime);
        
        System.out.println("\nAll requests completed.");
        System.out.println("Successful requests: " + successfulRequests.get());
        System.out.println("Failed requests: " + failedRequests.get());
        System.out.println("Total runtime: " + (endTime - startTime) + " ms");
        System.out.println("Throughput: " + (TOTAL_REQUESTS / ((endTime - startTime) / 1000.0)) + " requests/second");
    }
    
    private static class RequestSenderWithMetrics extends RequestSender {
        public RequestSenderWithMetrics(int threadId) {
            super(threadId);
        }
        
        @Override
        protected void sendPostRequest(LiftRideEvent event) {
            int retries = 0;
            while (retries < 5) {
                long start = System.currentTimeMillis();
                try {
                    URL url = new URL(SERVER_URL);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true);
                    
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = event.toJson().getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }
                    
                    int responseCode = connection.getResponseCode();
                    long end = System.currentTimeMillis();
                    long latency = end - start;
                    
                    synchronized (metrics) {
                        metrics.add(new RequestMetrics(start, "POST", latency, responseCode));
                    }
                    
                    if (responseCode == HttpURLConnection.HTTP_CREATED) {
                        successfulRequests.incrementAndGet();
                        break;
                    } else if (responseCode >= 400 && responseCode < 600) {
                        retries++;
                        if (retries == 5) {
                            failedRequests.incrementAndGet();
                            System.out.println("Thread " + threadId + ": Request failed after 5 retries.");
                        }
                    }
                } catch (IOException e) {
                    retries++;
                    if (retries == 5) {
                        failedRequests.incrementAndGet();
                        System.out.println("Thread " + threadId + ": Request failed after 5 retries.");
                    }
                }
            }
            if (successfulRequests.get() % 1000 == 0) {
                System.out.println("Total successful requests so far: " + successfulRequests.get());
            }
        }
    }
    
    private static void writeMetricsToCsv() {
        try (FileWriter writer = new FileWriter(CSV_FILE)) {
            writer.write("StartTime,RequestType,Latency,ResponseCode\n");
            for (RequestMetrics metric : metrics) {
                writer.write(metric.toCsvString() + "\n");
            }
            System.out.println("Metrics written to " + CSV_FILE);
        } catch (IOException e) {
            System.err.println("Error writing metrics to CSV: " + e.getMessage());
        }
    }
    
    private static void calculateAndDisplayMetrics(long startTime, long endTime) {
        List<Long> latencies = new ArrayList<>();
        for (RequestMetrics metric : metrics) {
            latencies.add(metric.getLatency());
        }
        Collections.sort(latencies);
        
        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        long mean = latencies.stream().mapToLong(Long::longValue).sum() / latencies.size();
        long median = latencies.get(latencies.size() / 2);
        long p99 = latencies.get((int) (latencies.size() * 0.99));
        double throughput = TOTAL_REQUESTS / ((endTime - startTime) / 1000.0);
        
        System.out.println("\nPerformance Metrics:");
        System.out.println("Mean response time: " + mean + " ms");
        System.out.println("Median response time: " + median + " ms");
        System.out.println("Throughput: " + throughput + " requests/second");
        System.out.println("p99 response time: " + p99 + " ms");
        System.out.println("Min response time: " + min + " ms");
        System.out.println("Max response time: " + max + " ms");
    }
    
    private static class RequestMetrics {
        private long startTime;
        private String requestType;
        private long latency;
        private int responseCode;
        
        public RequestMetrics(long startTime, String requestType, long latency, int responseCode) {
            this.startTime = startTime;
            this.requestType = requestType;
            this.latency = latency;
            this.responseCode = responseCode;
        }
        
        public long getLatency() {
            return latency;
        }
        
        public String toCsvString() {
            return startTime + "," + requestType + "," + latency + "," + responseCode;
        }
    }
}