import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.shared.config.ConfigLoader;

public class ProducerMain {
    private static final int MAX_RETRIES = 10;
    private static final int RETRY_DELAY_MS = 5000;

    public static void main(String[] args) {
        try {
            System.out.println("⏳ Waiting for consumer to start...");
            waitForConsumer();
        } catch (Exception e) {
            System.out.println("❌ Failed to connect to consumer: " + e.getMessage());
            System.exit(1);
        }

        int threadCount = ConfigLoader.getInt("producer.threads", 1);
        String basePath = ConfigLoader.get("producer.rootvideopath");

        System.out.println("🔼 Starting producer with " + threadCount + " threads");
        System.out.println("📁 Base video folder: " + basePath);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 1; i <= threadCount; i++) {
            File folder = new File(basePath + "/folder" + i);

            System.out.println("🧭 Looking for video folders inside: " + new File(basePath).getAbsolutePath());

            if (!folder.exists() || !folder.isDirectory()) {
                System.out.println("⚠️ Skipping missing folder: " + folder.getAbsolutePath());
                continue;
            }

            executor.submit(new ProducerWorker(folder));
        }

        executor.shutdown();
    }

    private static void waitForConsumer() throws Exception {
        String environment = ConfigLoader.get("environment");
        String consumerUrl = (environment != null && environment.equals("docker")) 
            ? "http://consumer:8080/list" 
            : "http://localhost:8080/list";

        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                URL url = new URL(consumerUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    System.out.println("✅ Consumer is ready!");
                    return;
                }
            } catch (Exception e) {
                System.out.println("⏳ Waiting for consumer... (attempt " + (i + 1) + "/" + MAX_RETRIES + ")");
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
        throw new Exception("Consumer failed to start after " + MAX_RETRIES + " attempts");
    }
}