package assignment2.client;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multithreaded client for load testing the Skier API
 */
public class SkierClient {
    private static final Logger LOGGER = Logger.getLogger(SkierClient.class.getName());
    
    // Configuration constants
    private static final int TOTAL_REQUESTS = 200000;
    private static final int INITIAL_THREADS = 32;
    private static final int REQUESTS_PER_INITIAL_THREAD = 1000;
    private static final int SEASON_ID = 2025;  // Season ID restricted to 2025
    private static final int DAY_ID = 1;  // Day ID restricted to 1
    private static final int EVENT_QUEUE_SIZE = 1000;  // Size of the event generation queue
    
    // Metrics tracking
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final List<Long> responseTimes = Collections.synchronizedList(new LinkedList<>());
    
    // Event generation queue
    private final BlockingQueue<String> eventQueue = new ArrayBlockingQueue<>(EVENT_QUEUE_SIZE);
    
    // HTTP connection pool
    private final PoolingHttpClientConnectionManager connectionManager;
    private final CloseableHttpClient httpClient;
    private final String baseUrl;
    
    // Counter for threads to track total requests sent
    private final AtomicInteger requestsSent = new AtomicInteger(0);
    
    // File for detailed response time data
    private PrintWriter csvWriter;
    
    public SkierClient(String baseUrl, int maxConnections) {
        this.baseUrl = baseUrl;
        this.connectionManager = new PoolingHttpClientConnectionManager();
        this.connectionManager.setMaxTotal(maxConnections);
        this.connectionManager.setDefaultMaxPerRoute(maxConnections);
        
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();
        
        try {
            this.csvWriter = new PrintWriter(new FileWriter("response_times.csv"));
            this.csvWriter.println("StartTime,RequestType,Latency,ResponseCode");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to create CSV writer", e);
        }
    }
    
    /**
     * Thread to generate lift ride events and add them to the queue
     */
    private class EventGenerator implements Runnable {
        private final Random random = new Random();
        private volatile boolean running = true;
        
        @Override
        public void run() {
            try {
                while (running) {
                    String event = generateLiftRideEvent();
                    boolean added = eventQueue.offer(event, 100, TimeUnit.MILLISECONDS);
                    if (!added) {
                        // Queue is full, can log or handle this situation
                        Thread.sleep(10); // Short sleep to avoid busy waiting
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.info("Event generator interrupted");
            }
        }
        
        public void stop() {
            running = false;
        }
        
        private String generateLiftRideEvent() {
            int time = random.nextInt(360) + 1;  // 1-360
            int liftID = random.nextInt(40) + 1;  // 1-40
            int skierID = random.nextInt(100000) + 1;  // 1-100000
            int resortID = random.nextInt(10) + 1;
            
            JSONObject eventJson = new JSONObject();
            eventJson.put("time", time);
            eventJson.put("liftID", liftID);
            eventJson.put("skierID", skierID);
            eventJson.put("resortID", resortID);
            eventJson.put("seasonID", SEASON_ID);
            eventJson.put("dayID", DAY_ID);
            
            return eventJson.toString();
        }
    }
    
    /**
     * Worker thread to send POST requests to the server
     */
    private class RequestSender implements Runnable {
        private final int numRequests;
        private final CountDownLatch latch;
        
        public RequestSender(int numRequests, CountDownLatch latch) {
            this.numRequests = numRequests;
            this.latch = latch;
        }
        
        @Override
        public void run() {
            int sent = 0;
            try {
                while (sent < numRequests && requestsSent.get() < TOTAL_REQUESTS) {
                    // Get an event from the queue with timeout
                    String event = eventQueue.poll(1, TimeUnit.SECONDS);
                    if (event == null) {
                        LOGGER.fine("Timeout waiting for event, retrying...");
                        continue;
                    }
                    
                    // Generate a random skier ID for the URL (1-100000)
                    int urlSkierID = ThreadLocalRandom.current().nextInt(100000) + 1;
                    int urlResortID = ThreadLocalRandom.current().nextInt(10) + 1;
                    String url = String.format("%s/%d/seasons/%d/days/%d/skiers/%d",
                            baseUrl, urlResortID, SEASON_ID, DAY_ID, urlSkierID);
                    
                    long startTime = System.currentTimeMillis();
                    boolean success = sendPostRequest(url, event);
                    long endTime = System.currentTimeMillis();
                    long latency = endTime - startTime;
                    
                    // Record metrics
                    responseTimes.add(latency);
                    csvWriter.println(startTime + ",POST," + latency + "," + (success ? 201 : 400));
                    csvWriter.flush();
                    
                    if (success) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                    
                    sent++;
                    requestsSent.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("Request sender thread interrupted");
            } finally {
                // Count down the latch when this thread completes
                latch.countDown();
            }
        }
        
        private boolean sendPostRequest(String url, String jsonBody) {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type", "application/json");
            
            try {
                httpPost.setEntity(new StringEntity(jsonBody));
                try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    
                    // Consume entity to release connection back to pool
                    HttpEntity entity = response.getEntity();
                    EntityUtils.consume(entity);
                    
                    return statusCode == 201;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error sending POST request", e);
                return false;
            }
        }
    }
    
    /**
     * Start the client test with initial phase followed by completion phase
     */
    public void runTest() {
        long startTime = System.currentTimeMillis();
        LOGGER.info("Starting test with " + INITIAL_THREADS + " initial threads");
        
        // Start the event generator thread
        EventGenerator eventGenerator = new EventGenerator();
        Thread generatorThread = new Thread(eventGenerator);
        generatorThread.start();
        
        // Allow some time for the queue to fill up initially
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Phase 1: Initial threads (32 threads with 1000 requests each)
        CountDownLatch initialLatch = new CountDownLatch(INITIAL_THREADS);
        ExecutorService initialExecutor = Executors.newFixedThreadPool(INITIAL_THREADS);
        
        for (int i = 0; i < INITIAL_THREADS; i++) {
            initialExecutor.submit(new RequestSender(REQUESTS_PER_INITIAL_THREAD, initialLatch));
        }
        
        try {
            // Wait for all initial threads to complete
            initialLatch.await();
            LOGGER.info("Initial phase completed");
            
            // Phase 2: Completion phase with optimized thread count
            int remainingRequests = TOTAL_REQUESTS - (INITIAL_THREADS * REQUESTS_PER_INITIAL_THREAD);
            
            if (remainingRequests > 0) {
                LOGGER.info("Starting completion phase with " + remainingRequests + " remaining requests");
                
                // Determine optimal thread count for phase 2 (can be tuned based on testing)
                int optimalThreads = 2048;  // This value should be tuned based on testing
                int requestsPerThread = Math.max(100, remainingRequests / optimalThreads);
                int actualThreads = (int) Math.ceil((double) remainingRequests / requestsPerThread);
                
                CountDownLatch completionLatch = new CountDownLatch(actualThreads);
                ExecutorService completionExecutor = Executors.newFixedThreadPool(actualThreads);
                
                LOGGER.info("Using " + actualThreads + " threads with " + requestsPerThread + " requests per thread");
                
                for (int i = 0; i < actualThreads; i++) {
                    // Last thread may have fewer requests
                    int requests = (i == actualThreads - 1) ?
                            remainingRequests - (requestsPerThread * (actualThreads - 1)) :
                            requestsPerThread;
                    
                    completionExecutor.submit(new RequestSender(requests, completionLatch));
                }
                
                // Wait for completion phase to finish
                completionLatch.await();
                LOGGER.info("Completion phase finished");
                completionExecutor.shutdown();
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.severe("Test interrupted");
        } finally {
            // Stop the event generator
            eventGenerator.stop();
            initialExecutor.shutdown();
            
            // Calculate and display metrics
            long endTime = System.currentTimeMillis();
            double wallTime = (endTime - startTime) / 1000.0;
            
            LOGGER.info("Test completed in " + wallTime + " seconds");
            LOGGER.info("Successful requests: " + successCount.get());
            LOGGER.info("Failed requests: " + failureCount.get());
            LOGGER.info("Total throughput: " + (successCount.get() / wallTime) + " requests/second");
            
            // Calculate statistics for response times
            if (!responseTimes.isEmpty()) {
                Collections.sort(responseTimes);
                double mean = responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
                long median = responseTimes.get(responseTimes.size() / 2);
                long p99 = responseTimes.get((int) (responseTimes.size() * 0.99));
                long min = responseTimes.get(0);
                long max = responseTimes.get(responseTimes.size() - 1);
                
                LOGGER.info("Mean response time: " + mean + " ms");
                LOGGER.info("Median response time: " + median + " ms");
                LOGGER.info("99th percentile response time: " + p99 + " ms");
                LOGGER.info("Min response time: " + min + " ms");
                LOGGER.info("Max response time: " + max + " ms");
            }
            
            // Close resources
            try {
                csvWriter.close();
                httpClient.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing resources", e);
            }
        }
    }
    
    private static Properties loadProperties() {
        Properties props = new Properties();
        try {
            props.load(SkierClient.class.getClassLoader().getResourceAsStream("client.properties"));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load properties, using defaults", e);
        }
        return props;
    }
    
    public static void main(String[] args) {
        Properties props = loadProperties();
        
        // Get configuration from properties or command line args
        String baseUrl = System.getProperty("baseUrl");
        if (baseUrl == null || baseUrl.isEmpty()) {
            // baseUrl for single instance
            baseUrl = props.getProperty("base.url", "http://35.91.168.253:8080/JavaServlets-1.0-SNAPSHOT/skiers2");
    
            // baseUrl for load balanced four instances
//            baseUrl = props.getProperty("base.url", "http://SkierLoadBalancer-220836080.us-west-2.elb.amazonaws.com:80/JavaServlets-1.0-SNAPSHOT/skiers2");
        }
        
        // Number of max connections in HTTP client pool
        int maxConnections = 20000;
        
        SkierClient client = new SkierClient(baseUrl, maxConnections);
        client.runTest();
    }
}