package demo;

import org.openstack4j.event.config.EventConfig;
import org.openstack4j.event.config.EventConfig.*;
import org.openstack4j.event.listener.OpenStackEventManager;
import org.openstack4j.event.model.ResourceType;

/**
 * 纯代码配置，不需要 YAML 文件。
 * 展示动态添加/移除 vhost。
 */
public class ProgrammaticExample {

    public static void main(String[] args) throws InterruptedException {

        // ========== 1. 纯代码构建配置 ==========

        var config = new EventConfig();
        config.setTopic("notifications");
        config.setPrefetchCount(10);

        // 创建一个 cluster
        var cluster = new ClusterConfig();
        cluster.getRabbitmq().setHost("10.224.18.6");
        cluster.getRabbitmq().setPort(5672);

        // 添加 nova vhost
        var nova = new ServiceConfig();
        nova.setUsername("nova");
        nova.setPassword("password");
        cluster.getServices().put("nova", nova);

        // 添加 cinder vhost
        var cinder = new ServiceConfig();
        cinder.setUsername("cinder");
        cinder.setPassword("password");
        cluster.getServices().put("cinder", cinder);

        config.getClusters().put("cluster-a", cluster);

        // ========== 2. 创建 manager，注册监听器，启动 ==========

        var manager = new OpenStackEventManager(config);

        // 只关心 server 和 volume 事件
        manager.addListener(event -> {
            switch (event.getResourceType()) {
                case SERVER -> System.out.printf("🖥 Server %s: %s → %s (%s.%s)%n",
                        event.getResourceId(), event.getOldStatus(),
                        event.getStatus(), event.getAction(), event.getPhase());

                case VOLUME -> System.out.printf("💾 Volume %s: %s (%s)%n",
                        event.getResourceId(), event.getStatus(), event.getEventType());

                default -> {} // 忽略其他资源类型
            }
        });

        // 错误监听器（独立注册，职责分离）
        manager.addListener(event -> {
            if ("ERROR".equalsIgnoreCase(event.getPriority())) {
                System.err.printf("⚠ ERROR: %s %s %s - %s%n",
                        event.getClusterId(), event.getService(),
                        event.getEventType(), event.getResourceId());
            }
        });

        manager.start();
        System.out.println("Active consumers: " + manager.getActiveConsumerCount());

        // ========== 3. 运行时动态添加 heat vhost ==========

        Thread.sleep(5_000); // 模拟运行一段时间后

        System.out.println("Adding heat service dynamically...");
        var heat = new ServiceConfig();
        heat.setUsername("heat");
        heat.setPassword("password");
        manager.addService("cluster-a", "heat", heat);
        System.out.println("Active consumers: " + manager.getActiveConsumerCount());

        // ========== 4. 运行时移除 cinder vhost ==========

        Thread.sleep(5_000);

        System.out.println("Removing cinder service...");
        manager.removeService("cluster-a", "cinder");
        System.out.println("Active consumers: " + manager.getActiveConsumerCount());

        // ========== 5. 等待退出 ==========

        Runtime.getRuntime().addShutdownHook(new Thread(manager::close));
        Thread.currentThread().join();
    }
}
