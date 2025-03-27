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

    }
}