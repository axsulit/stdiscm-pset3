import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import com.shared.config.ConfigLoader;

public class ProducerWorker implements Runnable {
    private final File folder;
    private static final int MAX_RETRIES = 10;
    private static final int INITIAL_BACKOFF_MS = 1000; // Start with 1 second
    private static final int MAX_BACKOFF_MS = 30000;    // Max 30 seconds

    public ProducerWorker(File folder) {
        this.folder = folder;
        System.out.println("🚀 Producer worker initialized for folder: " + folder.getAbsolutePath());
    }

    @Override
    public void run() {
        File[] files = folder.listFiles();

        if (files == null || files.length == 0) {
            System.out.println("📂 [" + folder.getName() + "] No files to process.");
            return;
        }

        System.out.println("📂 Found " + files.length + " files in folder: " + folder.getName());

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

    private void uploadFile(File file) throws IOException {
        String boundary = "===" + System.currentTimeMillis() + "===";
        String LINE_FEED = "\r\n";
        int retryCount = 0;
        int backoffMs = INITIAL_BACKOFF_MS;

        String environment = ConfigLoader.get("environment");
        String consumerUrl = (environment != null && environment.equals("docker")) 
            ? "http://consumer:8080/upload" 
            : "http://localhost:8080/upload";
        
        System.out.println("🌐 Uploading to: " + consumerUrl);
        
        while (retryCount < MAX_RETRIES) {
            URL url = new URL(consumerUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setDoOutput(true); // sends POST
            conn.setDoInput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (
                OutputStream outputStream = conn.getOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"), true);
                FileInputStream inputStream = new FileInputStream(file)
            ) {
                // Start boundary
                writer.append("--").append(boundary).append(LINE_FEED);
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                      .append(file.getName()).append("\"").append(LINE_FEED);
                writer.append("Content-Type: application/octet-stream").append(LINE_FEED);
                writer.append(LINE_FEED).flush();

                // File data
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();

                // End of part
                writer.append(LINE_FEED).flush();
                writer.append("--").append(boundary).append("--").append(LINE_FEED).flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("✅ Uploaded: " + file.getName());
                return; // Success, exit the retry loop
            } else if (responseCode == HttpURLConnection.HTTP_UNAVAILABLE) {
                // Queue is full, implement exponential backoff
                System.out.println("⏳ Queue full, waiting " + backoffMs + "ms before retry...");
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Upload interrupted", e);
                }
                
                // Exponential backoff with jitter
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                backoffMs += (int)(Math.random() * 1000); // Add random jitter
                
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    System.out.println("❌ Max retries reached for " + file.getName() + ". Skipping file.");
                    return;
                }
            } else {
                System.out.println("⚠️ Server responded with code: " + responseCode);
                return; // Exit on other error codes
            }
        }
    }
}
