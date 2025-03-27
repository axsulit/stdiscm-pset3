import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.shared.config.ConfigLoader;


public class ProducerMain {
    public static void main(String[] args) {
        int threads = ConfigLoader.getInt("producer.threads", 1);
        System.out.println("🔼 Starting producer with " + threads + " threads");
    }
}
