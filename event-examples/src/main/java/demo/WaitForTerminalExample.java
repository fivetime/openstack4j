package demo;

import org.openstack4j.event.config.EventConfig;
import org.openstack4j.event.config.EventConfig.*;
import org.openstack4j.event.listener.OpenStackEventManager;
import org.openstack4j.event.model.OpenStackEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 实用场景：等待某个资源到达终态。
 *
 * 比如调用 OpenStack API 创建了一个 VM，然后等待它变成 active 或 error。
 * 替代传统的轮询（poll）方式。
 */
public class WaitForTerminalExample {

    public static void main(String[] args) throws Exception {

        // 假设你刚通过 OpenStack API 创建了一个 VM，拿到了 instance ID
        String instanceId = args.length > 0 ? args[0] : "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

        var config = new EventConfig();
        var cluster = new ClusterConfig();
        cluster.getRabbitmq().setHost("10.224.18.6");

        var nova = new ServiceConfig();
        nova.setUsername("nova");
        nova.setPassword("password");
        cluster.getServices().put("nova", nova);
        config.getClusters().put("cluster-a", cluster);

        var manager = new OpenStackEventManager(config);

        // 用 CompletableFuture 等待终态
        var future = new CompletableFuture<OpenStackEvent>();

        manager.addListener(event -> {
            if (instanceId.equals(event.getResourceId()) && event.isTerminal()) {
                future.complete(event);
            }
        });

        manager.start();

        System.out.printf("Waiting for instance %s to reach terminal state...%n", instanceId);

        try {
            // 最多等 5 分钟
            OpenStackEvent result = future.get(5, TimeUnit.MINUTES);
            System.out.printf("Done! Instance %s is now: %s%n",
                    result.getResourceId(), result.getStatus());
        } catch (Exception e) {
            System.err.println("Timeout or error: " + e.getMessage());
        } finally {
            manager.close();
        }
    }
}
