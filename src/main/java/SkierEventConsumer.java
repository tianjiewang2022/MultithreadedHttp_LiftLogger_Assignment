import com.rabbitmq.client.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

/**
 * A multi-threaded RabbitMQ consumer that processes skier lift ride events
 * and maintains a thread-safe record of rides per skier.
 */
public class SkierEventConsumer{
    private static final Logger LOGGER = Logger.getLogger(SkierEventConsumer.class.getName());
    private static final String QUEUE_NAME = "skier_events";
    
    // Thread-safe map to store skier ride data: skierID -> ride count
    private final ConcurrentHashMap<Integer, AtomicInteger> skierRides = new ConcurrentHashMap<>();
    
    // Track processing metrics
    private final AtomicInteger totalProcessedMessages = new AtomicInteger(0);
    private final AtomicInteger activeConsumers = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> resortStats = new ConcurrentHashMap<>();
    
    // RabbitMQ resources
    private ConnectionFactory factory;
    private Connection connection;
    
    // Thread management
    private final ExecutorService threadPool;
    private final int numThreads;
    private volatile boolean isRunning = true;
    
    // Periodic stats reporting
    private final ScheduledExecutorService statsReporter = Executors.newSingleThreadScheduledExecutor();
    
    public SkierEventConsumer(int numThreads) {
        this.numThreads = numThreads;
        this.threadPool = Executors.newFixedThreadPool(numThreads);
        LOGGER.info("Initializing SkierEventsConsumer with " + numThreads + " threads");
    }
    
    public void initialize() throws IOException, TimeoutException {
        // Load configuration
        Properties props = loadProperties();
        
        // Initialize connection factory
        factory = new ConnectionFactory();
        
        // Get RabbitMQ connection details from environment or config
        String rabbitmqHost = System.getenv("RABBITMQ_HOST");
        if (rabbitmqHost == null || rabbitmqHost.isEmpty()) {
            rabbitmqHost = props.getProperty("rabbitmq.host", "localhost");
        }
        
        factory.setHost(rabbitmqHost);
        factory.setPort(Integer.parseInt(props.getProperty("rabbitmq.port", "5672")));
        factory.setUsername(props.getProperty("rabbitmq.username", "admin"));
        factory.setPassword(props.getProperty("rabbitmq.password", "SecurePassword123"));
        
        // Configure connection recovery
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);
        factory.setConnectionTimeout(30000);
        
        // Establish connection
        LOGGER.info("Connecting to RabbitMQ server at " + factory.getHost());
        connection = factory.newConnection();
        LOGGER.info("RabbitMQ connection established successfully");
        
        // Setup stats reporting every minute
        statsReporter.scheduleAtFixedRate(this::reportStats, 60, 60, TimeUnit.SECONDS);
    }
    
    public void startConsumers() {
        LOGGER.info("Starting " + numThreads + " consumer threads");
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threadPool.submit(() -> {
                Channel channel = null;
                try {
                    // Each consumer gets its own channel
                    channel = connection.createChannel();
                    
                    // Ensure queue exists
                    channel.queueDeclare(QUEUE_NAME, true, false, false, null);
                    
                    // Limit prefetch to avoid overwhelming consumers
                    // This is an important tuning parameter - adjust based on testing
                    int prefetchCount = 100;
                    channel.basicQos(prefetchCount);
                    
                    LOGGER.info("Consumer thread " + threadId + " started with prefetch count " + prefetchCount);
                    activeConsumers.incrementAndGet();
                    
                    // Create a consumer that processes messages
                    Channel finalChannel = channel;
                    DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                        try {
                            String message = new String(delivery.getBody(), "UTF-8");
                            processMessage(message);
                            
                            // Acknowledge successful processing
                            finalChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            
                            // Update metrics
                            totalProcessedMessages.incrementAndGet();
                            
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error processing message", e);
                            // Negatively acknowledge with requeue=true for retry
                            finalChannel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                        }
                    };
                    
                    // Setup message consumption
                    channel.basicConsume(QUEUE_NAME, false, deliverCallback, consumerTag -> {
                        LOGGER.info("Consumer " + threadId + " canceled");
                        activeConsumers.decrementAndGet();
                    });
                    
                    // Keep thread running until shutdown is requested
                    while (isRunning) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Consumer thread " + threadId + " encountered an error", e);
                    activeConsumers.decrementAndGet();
                } finally {
                    if (channel != null && channel.isOpen()) {
                        try {
                            channel.close();
                            LOGGER.info("Channel closed for consumer " + threadId);
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error closing channel", e);
                        }
                    }
                }
            });
        }
    }
    
    private void processMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            
            int skierID = json.getInt("skierID");
            int resortID = json.getInt("resortID");
            
            // Record the lift ride in our thread-safe map
            skierRides.computeIfAbsent(skierID, k -> new AtomicInteger(0)).incrementAndGet();
            
            // Track stats by resort
            String resortKey = "resort_" + resortID;
            resortStats.computeIfAbsent(resortKey, k -> new AtomicInteger(0)).incrementAndGet();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error parsing or processing message: " + message, e);
            throw e; // Rethrow to trigger nack
        }
    }
    
    private void reportStats() {
        int totalRides = 0;
        for (AtomicInteger rides : skierRides.values()) {
            totalRides += rides.get();
        }
        
        LOGGER.info("===== CONSUMER STATS =====");
        LOGGER.info("Active consumer threads: " + activeConsumers.get());
        LOGGER.info("Total processed messages: " + totalProcessedMessages.get());
        LOGGER.info("Unique skiers: " + skierRides.size());
        LOGGER.info("Total recorded rides: " + totalRides);
        
        // Log top 5 skiers by ride count
        LOGGER.info("Top skiers by ride count:");
        skierRides.entrySet().stream()
                .sorted(Comparator.comparing(entry -> -entry.getValue().get()))  // Negative for descending order
                .limit(5)
                .forEach(entry -> LOGGER.info("Skier " + entry.getKey() + ": " + entry.getValue().get() + " rides"));
        
        // Resort statistics
        LOGGER.info("Resort statistics:");
        resortStats.forEach((resort, count) ->
                LOGGER.info(resort + ": " + count.get() + " rides"));
        
        LOGGER.info("===========================");
    }
    
    public void shutdown() {
        LOGGER.info("Shutting down consumer...");
        isRunning = false;
        
        // Shutdown stats reporter
        statsReporter.shutdown();
        
        // Shutdown thread pool with a grace period
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close connection
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
                LOGGER.info("RabbitMQ connection closed");
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error closing RabbitMQ connection", e);
        }
        
        LOGGER.info("Consumer shutdown complete");
    }
    
    private Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (input != null) {
                props.load(input);
                LOGGER.info("Configuration loaded successfully from config.properties");
            } else {
                LOGGER.warning("Unable to find config.properties, using default values");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load configuration file, using default values", e);
        }
        return props;
    }
    
    public static void main(String[] args) {
        // Parse command line arguments for thread count
        int numThreads = 16; // Default value
        if (args.length > 0) {
            try {
                numThreads = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid thread count specified, using default: " + numThreads);
            }
        }
        
        final SkierEventConsumer consumer = new SkierEventConsumer(numThreads);
        
        try {
            consumer.initialize();
            consumer.startConsumers();
            
            // Register shutdown hook for graceful termination
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("Shutdown signal received");
                consumer.shutdown();
            }));
            
            LOGGER.info("Consumer started successfully with " + numThreads + " threads. Press Ctrl+C to stop.");
            
            // Keep main thread alive
            while (true) {
                Thread.sleep(10000);
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error starting consumer", e);
            consumer.shutdown();
            System.exit(1);
        }
    }
}