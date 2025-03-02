# 6650_LiftLogger_Project
It is a repo for 6650 Scalable Distributed Systems assignment1 

# CS6650 Assignment 1 - Ski Resort Lift Ride System

## Client Setup and Execution Instructions

### 1. Modify Server URL

Before running the client, update the `SERVER_URL` variable in both `LiftRideClientPart1.java` and `LiftRideClientPart2.java` to match your deployed server on EC2:

```java
private static final String SERVER_URL = "http://your-ec2-ip:8080/Assignment1-1.0-SNAPSHOT/skiers";
```

Replace `your-ec2-ip` with the actual public IP of your AWS EC2 instance.

### 2. Expected Output

The client will output:
- Number of successful and failed requests
- Total execution time
- Throughput in requests per second
- (For Part 2) Response time statistics (mean, median, p99, min, max)

### 3. Log File (Part 2)

`LiftRideClientPart2.java` logs request details into `latency.csv`:
- Format: `StartTime, RequestType, Latency, ResponseCode`
- Use this file to analyze request performance.

### 4. Additional Notes

Ensure that Tomcat is running on EC2 before executing the client.

Test the API using Postman or `curl` before running the client:

```bash
curl -X POST "http://your-ec2-ip:8080/Assignment1-1.0-SNAPSHOT/skiers" \
-H "Content-Type: application/json" \
-d '{"skierID":1,"resortID":1,"liftID":10,"seasonID":2025,"dayID":1,"time":100}'
```

Expected response: `Lift ride recorded`
