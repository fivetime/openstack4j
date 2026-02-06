package demo;

import org.openstack4j.event.listener.OpenStackEventManager;
import org.openstack4j.event.model.OpenStackEvent;
import org.openstack4j.event.model.ResourceType;

import java.nio.file.Path;

/**
 * 最简单的用法：YAML 配置 + main 方法。
 *
 * 运行: java -cp "lib/*" demo.BasicExample config.yml
 */
public class BasicExample {

    public static void main(String[] args) {
        // 1. 从 YAML 加载配置并创建 manager
        Path configPath = Path.of(args.length > 0 ? args[0] : "config.yml");
        var manager = OpenStackEventManager.fromYaml(configPath);

        // 2. 注册监听器
        manager.addListener(BasicExample::onEvent);

        // 3. 启动（连接 RabbitMQ/Kafka，开始消费）
        manager.start();

        // 4. 保持运行，Ctrl+C 退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            manager.close();
        }));

        System.out.println("Listening for OpenStack notifications... (Ctrl+C to stop)");

        // 阻塞主线程
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void onEvent(OpenStackEvent event) {
        System.out.printf("[%s] %-8s %-10s %s  %s → %s  (terminal=%s)%n",
                event.getClusterId(),
                event.getService(),
                event.getResourceType(),
                event.getResourceId(),
                event.getOldStatus(),
                event.getStatus(),
                event.isTerminal());
    }
}
