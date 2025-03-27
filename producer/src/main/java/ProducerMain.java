import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.shared.config.ConfigLoader;

public class ProducerMain {
    public static void main(String[] args) {
        try {
            System.out.println("⏳ Waiting for consumer to start...");
            Thread.sleep(5000); // wait 5 seconds
        } catch (InterruptedException e) {
            // ignore
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
}