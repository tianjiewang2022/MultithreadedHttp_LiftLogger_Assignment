import com.rabbitmq.client.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;
    
    /**
     * A multi-threaded RabbitMQ consumer that processes skier lift ride events
     * and persists the data to Redis for efficient querying.
     */
    public class SkierEventRedisConsumer {
        private static final Logger LOGGER = Logger.getLogger(SkierEventRedisConsumer.class.getName());
        private static final String QUEUE_NAME = "skier_events";
        
        // Redis connection pool
        private JedisPool jedisPool;
        private final String REDIS_HOST;
        private final int REDIS_PORT;
        
        // Track processing metrics
        private final AtomicInteger totalProcessedMessages = new AtomicInteger(0);
        private final AtomicInteger activeConsumers = new AtomicInteger(0);
        
        // RabbitMQ resources
        private ConnectionFactory factory;
        private Connection connection;
        
        // Thread management
        private final ExecutorService threadPool;
        private final int numThreads;
        private volatile boolean isRunning = true;
        
        // Periodic stats reporting
        private final ScheduledExecutorService statsReporter = Executors.newSingleThreadScheduledExecutor();
        
        public SkierEventRedisConsumer(int numThreads) {
            this.numThreads = numThreads;
            this.threadPool = Executors.newFixedThreadPool(numThreads);
            
            // Get Redis configuration from environment or defaults
            this.REDIS_HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
            this.REDIS_PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
            
            LOGGER.info("Initializing SkierEventsConsumer with " + numThreads + " threads and Redis at "
                    + REDIS_HOST + ":" + REDIS_PORT);
        }
        
        public void initialize() throws IOException, TimeoutException {
            // Initialize Redis connection pool
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(numThreads * 2); // Allow 2 connections per thread
            poolConfig.setMaxIdle(numThreads);
            poolConfig.setMinIdle(2);
            this.jedisPool = new JedisPool(poolConfig, REDIS_HOST, REDIS_PORT);
            
            // Test Redis connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                LOGGER.info("Redis connection established successfully");
            } catch (Exception e) {
                throw new IOException("Failed to connect to Redis", e);
            }
            
            // Initialize RabbitMQ
            Properties props = loadProperties();
            factory = new ConnectionFactory();
            String rabbitmqHost = System.getenv("RABBITMQ_HOST");
            if (rabbitmqHost == null || rabbitmqHost.isEmpty()) {
                rabbitmqHost = props.getProperty("rabbitmq.host", "localhost");
            }
            
            factory.setHost(rabbitmqHost);
            factory.setPort(Integer.parseInt(props.getProperty("rabbitmq.port", "5672")));
            factory.setUsername(props.getProperty("rabbitmq.username", "admin"));
            factory.setPassword(props.getProperty("rabbitmq.password", "SecurePassword123"));
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(5000);
            factory.setConnectionTimeout(30000);
            
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
                    try (Jedis jedis = jedisPool.getResource()) {  // Use try-with-resources
                        channel = connection.createChannel();
                    
                        // Ensure queue exists
                        channel.queueDeclare(QUEUE_NAME, true, false, false, null);
                    
                        // Limit prefetch
                        int prefetchCount = 100;
                        channel.basicQos(prefetchCount);
                    
                        LOGGER.info("Consumer thread " + threadId + " started with prefetch count " + prefetchCount);
                        activeConsumers.incrementAndGet();
                    
                        // Create final reference for use in lambda
                        final Channel finalChannel = channel;
                    
                        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                            try {
                                String message = new String(delivery.getBody(), "UTF-8");
                                processMessage(message, jedis);  // Now jedis is effectively final
                            
                                finalChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                                totalProcessedMessages.incrementAndGet();
                            } catch (Exception e) {
                                LOGGER.log(Level.WARNING, "Error processing message", e);
                                finalChannel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                            }
                        };
                    
                        channel.basicConsume(QUEUE_NAME, false, deliverCallback, consumerTag -> {
                            LOGGER.info("Consumer " + threadId + " canceled");
                            activeConsumers.decrementAndGet();
                        });
                    
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
                            } catch (Exception e) {
                                LOGGER.log(Level.WARNING, "Error closing channel", e);
                            }
                        }
                    }
                });
            }
        }
        
        public void processMessage(String message, Jedis jedis) {
            try {
                JSONObject json = new JSONObject(message);
                
                int skierID = json.getInt("skierID");
                int resortID = json.getInt("resortID");
                int liftID = json.getInt("liftID");
                int seasonID = json.getInt("seasonID");
                int dayID = json.getInt("dayID");
                int time = json.getInt("time");
                
                // Calculate vertical feet (assuming each lift ride gives 10 vertical feet)
                int vertical = liftID * 10;
                
                // Generate composite keys for Redis
                String skierKey = "skier:" + skierID;
                String resortKey = "resort:" + resortID;
                String dayKey = "day:" + dayID;
                String seasonKey = "season:" + seasonID;
                
                // Use Redis pipeline for batch operations
                Pipeline pipeline = jedis.pipelined();
                
                // 1. Track skier's daily lifts (sorted set by time)
                String dailyLiftsKey = String.join(":", skierKey, seasonKey, dayKey, "lifts");
                pipeline.zadd(dailyLiftsKey, time, String.valueOf(liftID));
                
                // 2. Track skier's daily vertical (hash)
                String dailyVerticalKey = String.join(":", skierKey, seasonKey, dayKey, "vertical");
                pipeline.hincrBy(dailyVerticalKey, "total", vertical);
                
                // 3. Track skier's ski days (set)
                String skiDaysKey = String.join(":", skierKey, seasonKey, "days");
                pipeline.sadd(skiDaysKey, dayKey);
                
                // 4. Track resort visitors for the day (set)
                String resortVisitorsKey = String.join(":", resortKey, seasonKey, dayKey, "visitors");
                pipeline.sadd(resortVisitorsKey, String.valueOf(skierID));
                
                // 5. Global counters
                pipeline.incr("total_rides");
                
                // Execute all commands in pipeline
                pipeline.sync();
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error parsing or processing message: " + message, e);
                throw e; // Rethrow to trigger nack
            }
        }
        
        private void reportStats() {
            try (Jedis jedis = jedisPool.getResource()) {
                LOGGER.info("===== CONSUMER STATS =====");
                LOGGER.info("Active consumer threads: " + activeConsumers.get());
                LOGGER.info("Total processed messages: " + totalProcessedMessages.get());
                
                String totalRides = jedis.get("total_rides");
                LOGGER.info("Total recorded rides in Redis: " + (totalRides != null ? totalRides : "0"));
                
                LOGGER.info("===========================");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error reporting stats from Redis", e);
            }
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
            
            // Close Redis connection pool
            if (jedisPool != null) {
                jedisPool.close();
                LOGGER.info("Redis connection pool closed");
            }
            
            // Close RabbitMQ connection
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
            
            final SkierEventRedisConsumer consumer = new SkierEventRedisConsumer(numThreads);
            
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