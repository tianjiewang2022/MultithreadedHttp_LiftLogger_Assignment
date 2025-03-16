package assignment2.server;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.annotation.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.json.JSONObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;

@WebServlet(name = "SkierServlet2", value = "/skiers2/*")
public class SkierServlet2 extends HttpServlet {
    private static final Logger LOGGER = Logger.getLogger(SkierServlet2.class.getName());
    private ConnectionFactory factory;
    private Connection connection;
    private BlockingQueue<Channel> channelPool;
    private final String QUEUE_NAME = "skier_events";
    private int poolSize;
    
    // URL path patterns - using more specific regex patterns for validation
    private static final Pattern SKI_DAY_PATTERN =
            Pattern.compile("/(\\d+)/seasons/(\\d+)/days/(\\d+)/skiers/(\\d+)");
    private static final Pattern VERTICAL_PATTERN =
            Pattern.compile("/(\\d+)/vertical");
    
    @Override
    public void init() throws ServletException {
        try {
            // Load configuration from properties file
            Properties props = loadProperties();
            
            // Initialize RabbitMQ connection factory with proper error handling
            factory = new ConnectionFactory();
            
            // Update these values to match your EC2 RabbitMQ instance
            String rabbitmqHost = System.getenv("RABBITMQ_HOST");
            if (rabbitmqHost == null || rabbitmqHost.isEmpty()) {
                rabbitmqHost = props.getProperty("rabbitmq.host", "localhost");
            }
            factory.setHost(rabbitmqHost);
            factory.setPort(Integer.parseInt(props.getProperty("rabbitmq.port", "5672")));
            factory.setUsername(props.getProperty("rabbitmq.username", "admin"));
            factory.setPassword(props.getProperty("rabbitmq.password", "SecurePassword123"));
            
            // Configure connection recovery for reliability
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(5000); // 5 seconds between recovery attempts
            factory.setConnectionTimeout(30000); // 30 seconds connection timeout
            
            // Establish a single connection - this is thread-safe in RabbitMQ
            LOGGER.info("Establishing connection to RabbitMQ server at " + factory.getHost());
            connection = factory.newConnection();
            LOGGER.info("RabbitMQ connection established successfully");
            
            // Initialize channel pool with appropriate size based on expected load
            poolSize = Integer.parseInt(props.getProperty("channel.pool.size", "50"));
            channelPool = new LinkedBlockingQueue<>(poolSize);
            
            // Pre-create channels and add to pool
            for (int i = 0; i < poolSize; i++) {
                Channel channel = connection.createChannel();
                
                // Ensure queue exists with proper durability settings
                // durable=true: queue survives broker restart
                // exclusive=false: allow multiple connections
                // autoDelete=false: don't delete when no consumers
                channel.queueDeclare(QUEUE_NAME, true, false, false, null);
                
                // Configure channel for performance
                channel.confirmSelect(); // Enable publisher confirms for reliability
                
                // Fixed: Check return value of offer method
                boolean added = channelPool.offer(channel);
                if (!added) {
                    LOGGER.warning("Failed to add channel to pool during initialization - closing channel");
                    try {
                        channel.close();
                    } catch (Exception closeEx) {
                        LOGGER.log(Level.FINE, "Error closing channel", closeEx);
                    }
                }
            }
            
            LOGGER.info("RabbitMQ channel pool initialized with " + poolSize + " channels");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize RabbitMQ connection", e);
            throw new ServletException("Failed to initialize RabbitMQ connection", e);
        }
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
    
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        // Fixed: Removed ServletException from the method signature since it's never thrown
        res.setContentType("application/json");
        Channel channel = null;
        long startTime = System.currentTimeMillis();
        
        try {
            // Step 1: Validate URL path
            String pathInfo = req.getPathInfo();
            if (pathInfo == null || !validateUrlPath(pathInfo)) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid URL path format\"}");
                LOGGER.warning("Invalid URL path: " + pathInfo);
                return;
            }
            
            // Step 2: Read and validate the request body
            StringBuilder jsonString = new StringBuilder();
            try (BufferedReader reader = req.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonString.append(line);
                }
            }
            
            String json = jsonString.toString();
            
            // Check if the request body is empty
            if (json.isEmpty()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Empty JSON request body\"}");
                LOGGER.warning("Empty request body received");
                return;
            }
            
            // Parse and validate JSON
            JSONObject jsonObject;
            try {
                jsonObject = new JSONObject(json);
            } catch (Exception e) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid JSON format: " + e.getMessage() + "\"}");
                LOGGER.warning("Invalid JSON format: " + e.getMessage());
                return;
            }
            
            // Validate required fields exist
            String[] requiredFields = {"time", "liftID", "skierID", "resortID", "seasonID", "dayID"};
            for (String field : requiredFields) {
                if (!jsonObject.has(field)) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    res.getWriter().write("{\"error\":\"Missing required field: '" + field + "'\"}");
                    LOGGER.warning("Missing required field in JSON: " + field);
                    return;
                }
            }
            
            // Validate field values with comprehensive checks
            try {
                // Extracting values first to ensure they are the correct type
                int time = jsonObject.getInt("time");
                int liftID = jsonObject.getInt("liftID");
                int skierID = jsonObject.getInt("skierID");
                int resortID = jsonObject.getInt("resortID");
                int seasonID = jsonObject.getInt("seasonID");
                int dayID = jsonObject.getInt("dayID");
                
                // Validate value ranges with clear error messages
                if (time < 1 || time > 360) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    res.getWriter().write("{\"error\":\"Invalid value for 'time'. Must be between 1 and 360.\"}");
                    return;
                }
                if (liftID < 1 || liftID > 40) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    res.getWriter().write("{\"error\":\"Invalid value for 'liftID'. Must be between 1 and 40.\"}");
                    return;
                }
                if (skierID < 1 || skierID > 100000) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    res.getWriter().write("{\"error\":\"Invalid value for 'skierID'. Must be between 1 and 100000.\"}");
                    return;
                }
                if (resortID < 1 || resortID > 10) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    res.getWriter().write("{\"error\":\"Invalid value for 'resortID'. Must be between 1 and 10.\"}");
                    return;
                }
                if (seasonID != 2025) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    res.getWriter().write("{\"error\":\"Invalid value for 'seasonID'. Must be 2025.\"}");
                    return;
                }
                if (dayID != 1) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    res.getWriter().write("{\"error\":\"Invalid value for 'dayID'. Must be 1.\"}");
                    return;
                }
            } catch (Exception e) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid data type for one or more fields: " + e.getMessage() + "\"}");
                LOGGER.warning("Data type validation failed: " + e.getMessage());
                return;
            }
            
            // Step 3: Send to RabbitMQ
            // Get a channel from the pool with timeout handling
            try {
                // Attempt to get a channel for up to 1 second
                channel = channelPool.poll(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                
                if (channel == null) {
                    LOGGER.warning("Could not obtain channel from pool within timeout - creating new channel");
                    channel = connection.createChannel();
                    channel.queueDeclare(QUEUE_NAME, true, false, false, null);
                }
                
                // Convert validated object to JSON string for message
                String messageBody = jsonObject.toString();
                
                // Publish message to queue with persistent delivery
                channel.basicPublish(
                        "", // Default exchange
                        QUEUE_NAME,
                        MessageProperties.PERSISTENT_TEXT_PLAIN,  // Ensure message persistence
                        messageBody.getBytes());
                
                // Return success response to client
                res.setStatus(HttpServletResponse.SC_CREATED);
                res.getWriter().write("{\"message\":\"Lift ride event recorded successfully.\"}");
                
                long processingTime = System.currentTimeMillis() - startTime;
                LOGGER.fine("Request processed successfully in " + processingTime + "ms");
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Interrupted while waiting for a channel", e);
                res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                res.getWriter().write("{\"error\":\"Service temporarily unavailable\"}");
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error during request processing", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            res.getWriter().write("{\"error\":\"Failed to process request: " + e.getMessage() + "\"}");
        } finally {
            // Return the channel to the pool if it's still valid
            if (channel != null && channel.isOpen()) {
                try {
                    boolean returned = channelPool.offer(channel, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!returned) {
                        LOGGER.warning("Failed to return channel to pool - closing channel");
                        try {
                            channel.close();
                        } catch (Exception closeEx) {
                            LOGGER.log(Level.FINE, "Error closing channel", closeEx);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(Level.WARNING, "Interrupted while returning channel to pool", e);
                    try {
                        channel.close();
                    } catch (Exception closeEx) {
                        LOGGER.log(Level.FINE, "Error closing channel", closeEx);
                    }
                }
            }
        }
    }
    
    private boolean validateUrlPath(String pathInfo) {
        // Validate URL against the defined patterns
        boolean isValid = SKI_DAY_PATTERN.matcher(pathInfo).matches() ||
                VERTICAL_PATTERN.matcher(pathInfo).matches();
        
        if (!isValid) {
            LOGGER.warning("URL validation failed for: " + pathInfo);
        }
        
        return isValid;
    }
    
    @Override
    public void destroy() {
        LOGGER.info("Servlet shutting down - cleaning up RabbitMQ resources");
        try {
            // Close all channels in the pool properly
            int closedChannels = 0;
            while (!channelPool.isEmpty()) {
                Channel channel = channelPool.poll();
                if (channel != null && channel.isOpen()) {
                    try {
                        channel.close();
                        closedChannels++;
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error closing channel during shutdown", e);
                    }
                }
            }
            
            // Close the connection
            if (connection != null && connection.isOpen()) {
                connection.close();
                LOGGER.info("RabbitMQ connection closed successfully");
            }
            
            LOGGER.info("Cleanup completed: " + closedChannels + " channels closed");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error closing RabbitMQ resources", e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error during cleanup", e);
        }
    }
}