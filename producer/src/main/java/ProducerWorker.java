import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import com.shared.config.ConfigLoader;

/**
 * Worker class responsible for processing and uploading videos from a specific folder.
 * This class implements the Runnable interface to allow concurrent processing of videos
 * from different folders. It handles file uploads with retry logic and exponential backoff.
 */
public class ProducerWorker implements Runnable {
    // Constants for upload handling and retry logic
    private static final int MAX_RETRIES = 10;           // Maximum number of upload retry attempts
    private static final int INITIAL_BACKOFF_MS = 1000;  // Initial delay between retries
    private static final int MAX_BACKOFF_MS = 30000;     // Maximum delay between retries
    private static final int BUFFER_SIZE = 4096;         // Size of buffer for file transfers
    private static final String LINE_FEED = "\r\n";      // Line feed for HTTP multipart
    private static final String BOUNDARY_PREFIX = "==="; // Prefix for multipart boundary
    private static final String BOUNDARY_SUFFIX = "==="; // Suffix for multipart boundary

    private final File folder;  // The folder containing videos to process

    /**
     * Constructs a new ProducerWorker for the specified folder.
     * @param folder The folder containing videos to process
     */
    public ProducerWorker(File folder) {
        this.folder = folder;
        System.out.println("🚀 Producer worker initialized for folder: " + folder.getAbsolutePath());
    }

    /**
     * Main execution method for the worker thread.
     * Processes all video files in the assigned folder.
     */
    @Override
    public void run() {
        // Get all files in the folder
        File[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("📂 [" + folder.getName() + "] No files to process.");
            return;
        }

        System.out.println("📂 Found " + files.length + " files in folder: " + folder.getName());
        processFiles(files);
    }

    /**
     * Processes all files in the folder, attempting to upload each one.
     * @param files Array of files to process
     */
    private void processFiles(File[] files) {
        for (File videoFile : files) {
            if (!videoFile.isFile()) continue;

            try {
                System.out.println("\n📤 Attempting to upload: " + videoFile.getName());
                uploadFile(videoFile);
            } catch (IOException e) {
                System.out.println("❌ Failed to upload " + videoFile.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Uploads a single file to the consumer service with retry logic.
     * @param file The file to upload
     * @throws IOException if the upload fails after all retries
     */
    private void uploadFile(File file) throws IOException {
        String boundary = generateBoundary();
        int retryCount = 0;
        int backoffMs = INITIAL_BACKOFF_MS;

        String consumerUrl = getConsumerUrl();
        System.out.println("🌐 Uploading to: " + consumerUrl);
        
        while (retryCount < MAX_RETRIES) {
            HttpURLConnection conn = createConnection(consumerUrl, boundary);
            if (performUpload(conn, file, boundary)) {
                return; // Success
            }
            
            backoffMs = handleUploadFailure(conn, backoffMs, retryCount, file.getName());
            retryCount++;
        }
    }

    /**
     * Generates a unique boundary string for multipart form data.
     * @return A unique boundary string
     */
    private String generateBoundary() {
        return BOUNDARY_PREFIX + System.currentTimeMillis() + BOUNDARY_SUFFIX;
    }

    /**
     * Determines the appropriate consumer URL based on the environment configuration.
     * @return The URL to connect to the consumer service
     */
    private String getConsumerUrl() {
        String environment = ConfigLoader.get("environment");
        return (environment != null && environment.equals("docker")) 
            ? "http://consumer:8080/upload" 
            : "http://localhost:8080/upload";
    }

    /**
     * Creates an HTTP connection configured for file upload.
     * @param url The URL to connect to
     * @param boundary The multipart boundary string
     * @return A configured HttpURLConnection object
     * @throws IOException if the connection cannot be established
     */
    private HttpURLConnection createConnection(String url, String boundary) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setUseCaches(false);
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        return conn;
    }

    /**
     * Performs the actual file upload operation.
     * @param conn The HTTP connection to use
     * @param file The file to upload
     * @param boundary The multipart boundary string
     * @return true if the upload was successful, false otherwise
     * @throws IOException if the upload fails
     */
    private boolean performUpload(HttpURLConnection conn, File file, String boundary) throws IOException {
        try (
            OutputStream outputStream = conn.getOutputStream();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"), true);
            FileInputStream inputStream = new FileInputStream(file)
        ) {
            writeMultipartContent(writer, outputStream, inputStream, file, boundary);
            
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("✅ Uploaded: " + file.getName());
                return true;
            }
            return false;
        }
    }

    /**
     * Writes the multipart form data for the file upload.
     * @param writer The PrintWriter for writing headers
     * @param outputStream The OutputStream for writing file content
     * @param inputStream The FileInputStream for reading the file
     * @param file The file being uploaded
     * @param boundary The multipart boundary string
     * @throws IOException if writing fails
     */
    private void writeMultipartContent(PrintWriter writer, OutputStream outputStream, 
                                     FileInputStream inputStream, File file, String boundary) throws IOException {
        // Write header
        writer.append("--").append(boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
              .append(file.getName()).append("\"").append(LINE_FEED);
        writer.append("Content-Type: application/octet-stream").append(LINE_FEED);
        writer.append(LINE_FEED).flush();

        // Write file content
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.flush();

        // Write footer
        writer.append(LINE_FEED).flush();
        writer.append("--").append(boundary).append("--").append(LINE_FEED).flush();
    }

    /**
     * Handles upload failures with exponential backoff and retry logic.
     * @param conn The HTTP connection that failed
     * @param backoffMs The current backoff delay
     * @param retryCount The current retry attempt number
     * @param filename The name of the file being uploaded
     * @return The updated backoff delay for the next retry
     * @throws IOException if the connection cannot be read
     */
    private int handleUploadFailure(HttpURLConnection conn, int backoffMs, int retryCount, String filename) 
            throws IOException {
        int responseCode = conn.getResponseCode();
        
        if (responseCode == HttpURLConnection.HTTP_UNAVAILABLE) {
            System.out.println("⏳ Queue full for " + filename + ", waiting " + backoffMs + "ms before retry...");
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Upload interrupted", e);
            }
            
            // Exponential backoff with jitter
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            backoffMs += (int)(Math.random() * 1000);
            
            if (retryCount >= MAX_RETRIES - 1) {
                System.out.println("❌ Max retries reached for " + filename + ". Skipping file.");
            }
        } else {
            System.out.println("⚠️ Server responded with code: " + responseCode);
        }
        
        return backoffMs;
    }
}
