package demo;

import org.openstack4j.event.config.EventConfig;
import org.openstack4j.event.config.EventConfig.*;
import org.openstack4j.event.listener.OpenStackEventManager;

/**
 * 当 OpenStack 配置了 kafka 作为 oslo.messaging transport driver 时的用法。
 *
 * OpenStack 那边的配置:
 *   [oslo_messaging_kafka]
 *   transport_url = kafka://10.225.20.6:9092
 *
 * 消息会发到 Kafka topic，命名为 "<service>.notifications"，如:
 *   nova.notifications
 *   cinder.notifications
 *   neutron.notifications
 */
public class KafkaExample {

    public static void main(String[] args) throws InterruptedException {

        var config = new EventConfig();
        config.setTopic("notifications");

        // Kafka cluster
        var cluster = new ClusterConfig();
        cluster.setTransport(TransportType.kafka);
        cluster.getKafka().setBootstrapServers("10.225.20.6:9092");
        cluster.getKafka().setGroupId("openstack-event-consumer");
        cluster.getKafka().setAutoOffsetReset("latest"); // 只消费新消息

        // 订阅 nova 和 cinder 的通知
        // Kafka 不需要 username/password（除非配了 SASL）
        var nova = new ServiceConfig();
        cluster.getServices().put("nova", nova);

        var cinder = new ServiceConfig();
        cluster.getServices().put("cinder", cinder);

        config.getClusters().put("cluster-b", cluster);

        // 使用方式和 RabbitMQ 完全相同
        var manager = new OpenStackEventManager(config);

        manager.addListener(event -> {
            System.out.printf("[%s] %s %s: %s → %s%n",
                    event.getClusterId(),
                    event.getResourceType(),
                    event.getResourceId(),
                    event.getEventType(),
                    event.getStatus());
        });

        manager.start();

        Runtime.getRuntime().addShutdownHook(new Thread(manager::close));
        System.out.println("Listening on Kafka topics... (Ctrl+C to stop)");
        Thread.currentThread().join();
    }
}
