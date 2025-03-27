import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.shared.config.ConfigLoader;

public class ProducerMain {
    public static void main(String[] args) {
        int threadCount = ConfigLoader.getInt("producer.threads", 1);
        String basePath = ConfigLoader.get("producer.rootvideopath");

        System.out.println("🔼 Starting producer with " + threadCount + " threads");
        System.out.println("📁 Base video folder: " + basePath);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 1; i <= threadCount; i++) {
            File folder = new File(basePath + "/folder" + i);
            if (!folder.exists() || !folder.isDirectory()) {
                System.out.println("⚠️ Skipping missing folder: " + folder.getAbsolutePath());
                continue;
            }

            executor.submit(new ProducerWorker(folder));
        }

        executor.shutdown();
    }
}