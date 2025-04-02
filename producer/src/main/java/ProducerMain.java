import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.shared.config.ConfigLoader;

/**
 * Main class for the producer component of the video processing system.
 * This class manages the initialization of producer threads and their communication
 * with the consumer service. It handles the coordination of multiple producer workers
 * that upload videos from different folders.
 */
public class ProducerMain {
    // Constants for connection and retry handling
    private static final int MAX_RETRIES = 10;           // Maximum number of connection retry attempts
    private static final int RETRY_DELAY_MS = 5000;     // Delay between retry attempts in milliseconds
    private static final int CONNECTION_TIMEOUT_MS = 5000; // Timeout for HTTP connections
    private static final String FOLDER_PREFIX = "folder"; // Prefix for video source folders

    /**
     * Main entry point for the producer application.
     * Initializes the system and starts producer threads.
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            waitForConsumer();
            startProducerThreads();
        } catch (Exception e) {
            System.out.println("❌ Failed to start producer: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Waits for the consumer service to become available.
     * Implements a retry mechanism with exponential backoff.
     * @throws Exception if the consumer fails to start after maximum retries
     */
    private static void waitForConsumer() throws Exception {
        System.out.println("⏳ Waiting for consumer to start...");
        
        String consumerUrl = getConsumerUrl();
        for (int i = 0; i < MAX_RETRIES; i++) {
            if (isConsumerReady(consumerUrl)) {
                System.out.println("✅ Consumer is ready!");
                return;
            }
            logRetryAttempt(i);
            Thread.sleep(RETRY_DELAY_MS);
        }
        
        throw new Exception("Consumer failed to start after " + MAX_RETRIES + " attempts");
    }

    /**
     * Determines the appropriate consumer URL based on the environment configuration.
     * @return The URL to connect to the consumer service
     */
    private static String getConsumerUrl() {
        String environment = ConfigLoader.get("environment");
        return (environment != null && environment.equals("docker")) 
            ? "http://consumer:8080/list" 
            : "http://localhost:8080/list";
    }

    /**
     * Checks if the consumer service is ready to accept connections.
     * @param url The URL of the consumer service
     * @return true if the consumer is ready, false otherwise
     */
    private static boolean isConsumerReady(String url) {
        try {
            HttpURLConnection conn = createConnection(url);
            return conn.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates an HTTP connection with appropriate timeout settings.
     * @param url The URL to connect to
     * @return An HttpURLConnection object configured for the request
     * @throws Exception if the connection cannot be established
     */
    private static HttpURLConnection createConnection(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        conn.setReadTimeout(CONNECTION_TIMEOUT_MS);
        return conn;
    }

    /**
     * Logs a retry attempt while waiting for the consumer.
     * @param attempt The current attempt number
     */
    private static void logRetryAttempt(int attempt) {
        System.out.println("⏳ Waiting for consumer... (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
    }

    /**
     * Starts the producer threads based on configuration.
     * Creates a thread pool and submits producer workers for each valid video folder.
     */
    private static void startProducerThreads() {
        int threadCount = ConfigLoader.getInt("producer.threads", 1);
        String basePath = ConfigLoader.get("producer.rootvideopath");

        System.out.println("🔼 Starting producer with " + threadCount + " threads");
        System.out.println("📁 Base video folder: " + basePath);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        submitProducerWorkers(executor, basePath, threadCount);
        executor.shutdown();
    }

    /**
     * Submits producer workers to the executor service for each valid video folder.
     * @param executor The executor service to submit workers to
     * @param basePath The base path containing video folders
     * @param threadCount The number of threads to create
     */
    private static void submitProducerWorkers(ExecutorService executor, String basePath, int threadCount) {
        System.out.println("🧭 Looking for video folders inside: " + new File(basePath).getAbsolutePath());

        for (int i = 1; i <= threadCount; i++) {
            File folder = new File(basePath + "/" + FOLDER_PREFIX + i);
            if (isValidFolder(folder)) {
                executor.submit(new ProducerWorker(folder));
            }
        }
    }

    /**
     * Validates that a folder exists and is a directory.
     * @param folder The folder to validate
     * @return true if the folder is valid, false otherwise
     */
    private static boolean isValidFolder(File folder) {
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("⚠️ Skipping missing folder: " + folder.getAbsolutePath());
            return false;
        }
        return true;
    }
}