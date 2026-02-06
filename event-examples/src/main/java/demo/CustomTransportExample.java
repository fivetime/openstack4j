package demo;

import org.openstack4j.event.config.EventConfig;
import org.openstack4j.event.config.EventConfig.*;
import org.openstack4j.event.listener.OpenStackEventManager;
import org.openstack4j.event.transport.*;

/**
 * 自定义 Transport 实现示例。
 *
 * 如果将来要接 AMQP 1.0 (如 ActiveMQ, Qpid) 或其他消息中间件，
 * 只需实现 MessageTransport 接口，然后注入到 manager。
 */
public class CustomTransportExample {

    /**
     * 最简单的自定义 transport 骨架。
     */
    static class MyCustomTransport implements MessageTransport {

        private boolean running = false;

        @Override
        public void subscribe(String serviceName, ServiceEndpoint endpoint, MessageCallback callback) {
            System.out.printf("Custom: subscribing to %s%n", serviceName);
            // TODO: 建立连接，注册消费者，收到消息时调用 callback.onMessage(serviceName, rawBytes)
        }

        @Override
        public void unsubscribe(String serviceName) {
            System.out.printf("Custom: unsubscribing from %s%n", serviceName);
        }

        @Override
        public void start() {
            running = true;
            System.out.println("Custom transport started");
        }

        @Override
        public void close() {
            running = false;
            System.out.println("Custom transport stopped");
        }

        @Override
        public int getActiveCount() { return running ? 1 : 0; }

        @Override
        public boolean isRunning() { return running; }
    }

    public static void main(String[] args) throws InterruptedException {

        var config = new EventConfig();

        // 配置一个空的 cluster（transport 会被手动覆盖）
        var cluster = new ClusterConfig();
        var nova = new ServiceConfig();
        nova.setUsername("nova");
        nova.setPassword("password");
        cluster.getServices().put("nova", nova);
        config.getClusters().put("cluster-c", cluster);

        var manager = new OpenStackEventManager(config);

        // 注入自定义 transport（必须在 start() 之前）
        manager.setTransport("cluster-c", new MyCustomTransport());

        manager.addListener(event -> System.out.println("Got event: " + event));

        manager.start();

        Runtime.getRuntime().addShutdownHook(new Thread(manager::close));
        Thread.currentThread().join();
    }
}
