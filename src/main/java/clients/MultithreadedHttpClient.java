package clients;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MultithreadedHttpClient {
    protected static final int TOTAL_REQUESTS = 200000;
    protected static final int INITIAL_THREADS = 32;
    protected static final int REQUESTS_PER_THREAD = 1000;
    protected static final String SERVER_URL = "http://34.217.60.24:8080/JavaServlets_war/skiers";
    
    protected static BlockingQueue<LiftRideEvent> eventQueue = new LinkedBlockingQueue<>();
    protected static AtomicInteger successfulRequests = new AtomicInteger(0);
    protected static AtomicInteger failedRequests = new AtomicInteger(0);
    protected static CountDownLatch latch = new CountDownLatch(INITIAL_THREADS);
    
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        System.out.println("Starting Multithreaded HTTP Client...");
        
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
            executor.submit(new RequestSender(i + 1));
        }
        
        // Wait for all threads to complete
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        executor.shutdown();
        long endTime = System.currentTimeMillis();
        
        // Print results
        System.out.println("\nAll requests completed.");
        System.out.println("Successful requests: " + successfulRequests.get());
        System.out.println("Failed requests: " + failedRequests.get());
        System.out.println("Total runtime: " + (endTime - startTime) + " ms");
        System.out.println("Throughput: " + (TOTAL_REQUESTS / ((endTime - startTime) / 1000.0)) + " requests/second");
    }
    
    protected static class RequestSender implements Runnable {
        protected int threadId;
        
        public RequestSender(int threadId) {
            this.threadId = threadId;
        }
        
        @Override
        public void run() {
            System.out.println("Thread " + threadId + " started.");
            for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                try {
                    LiftRideEvent event = eventQueue.take();
                    sendPostRequest(event);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println("Thread " + threadId + " finished.");
            latch.countDown(); // Notify that this thread has finished
        }
        
        protected void sendPostRequest(LiftRideEvent event) {
            int retries = 0;
            while (retries < 5) {
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
}