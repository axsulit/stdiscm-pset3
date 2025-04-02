import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import com.shared.config.ConfigLoader;

public class ProducerWorker implements Runnable {
    private final File folder;

    public ProducerWorker(File folder) {
        this.folder = folder;
    }

    @Override
    public void run() {
        File[] files = folder.listFiles();

        if (files == null || files.length == 0) {
            System.out.println("📂 [" + folder.getName() + "] No files to process.");
            return;
        }

        for (File videoFile : files) {
            if (!videoFile.isFile()) continue;

            try {
                uploadFile(videoFile);
            } catch (IOException e) {
                System.out.println("❌ Failed to upload " + videoFile.getName() + ": " + e.getMessage());
            }
        }
    }

    private void uploadFile(File file) throws IOException {
        String boundary = "===" + System.currentTimeMillis() + "===";
        String LINE_FEED = "\r\n";

        String environment = ConfigLoader.get("environment");
        String consumerUrl = (environment != null && environment.equals("docker")) 
            ? "http://consumer:8080/upload" 
            : "http://localhost:8080/upload";
        
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
        } else {
            System.out.println("⚠️ Server responded with code: " + responseCode);
        }
    }
}
