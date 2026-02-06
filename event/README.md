# OpenStack4j Event Library

从 `openstack4j-event-spring-boot-starter` 重构而来的纯 Java 库，**零 Spring 依赖**。

通过可插拔的 transport 后端（RabbitMQ / Kafka）消费 OpenStack oslo.messaging 通知，解析为结构化的 `OpenStackEvent` 对象。

## 依赖

| 依赖 | 用途 | 是否必须 |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | JSON 解析 | 必须 |
| `org.slf4j:slf4j-api` | 日志 | 必须 |
| `org.yaml:snakeyaml` | YAML 配置加载 | 必须 |
| `com.rabbitmq:amqp-client` | RabbitMQ transport | 按需（optional） |
| `org.apache.kafka:kafka-clients` | Kafka transport | 按需（optional） |

## 快速开始

### RabbitMQ (默认)

```yaml
openstack:
  event:
    clusters:
      cluster-a:
        rabbitmq:
          host: 10.224.18.6
          port: 5672
        vhosts:                   # "vhosts" 或 "services" 均可
          nova:
            username: nova
            password: password
          cinder:
            username: cinder
            password: password
```

```java
var manager = OpenStackEventManager.fromYaml(Path.of("config.yml"));
manager.addListener(event -> {
    System.out.printf("[%s] %s %s → %s%n",
        event.getClusterId(), event.getResourceType(),
        event.getResourceId(), event.getStatus());
});
manager.start();
```

### Kafka (oslo.messaging kafka driver)

当 OpenStack 配置了 `transport_url = kafka://...` 时，通知会发到 Kafka topic。
Topic 命名规则：`<exchange>.<topic>`，如 `nova.notifications`。

```yaml
openstack:
  event:
    clusters:
      cluster-b:
        transport: kafka
        kafka:
          bootstrap-servers: 10.225.20.6:9092
          group-id: openstack-event-consumer
        services:
          nova: { username: "", password: "" }
          cinder: { username: "", password: "" }
```

用法完全相同，只是配置不同：

```java
var manager = OpenStackEventManager.fromYaml(Path.of("config.yml"));
manager.addListener(event -> handleEvent(event));
manager.start();
```

### 混合集群

同一个应用可以同时消费 RabbitMQ 和 Kafka 集群：

```yaml
openstack:
  event:
    clusters:
      prod-cluster:
        transport: rabbitmq
        rabbitmq: { host: 10.224.18.6, port: 5672 }
        vhosts:
          nova: { username: nova, password: pass }
      dev-cluster:
        transport: kafka
        kafka: { bootstrap-servers: 10.225.20.6:9092, group-id: dev-consumer }
        services:
          nova: { username: "", password: "" }
```

### 运行时动态添加/移除服务

```java
var svcConfig = new EventConfig.ServiceConfig();
svcConfig.setUsername("heat");
svcConfig.setPassword("password");
manager.addService("cluster-a", "heat", svcConfig);

manager.removeService("cluster-a", "heat");
```

### 自定义 Transport

实现 `MessageTransport` 接口即可支持其他消息中间件（如 AMQP 1.0）：

```java
public class Amqp10Transport implements MessageTransport {
    // ...
}

manager.setTransport("cluster-c", new Amqp10Transport(...));
manager.start();
```

## 架构

```
                    ┌─────────────────────────────┐
                    │    OpenStackEventManager     │
                    │  (lifecycle, dispatch, SPI)  │
                    └──────────┬──────────────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
    ┌─────────▼────────┐ ┌────▼──────────┐ ┌───▼───────────┐
    │ RabbitMQTransport │ │KafkaTransport │ │ CustomTransport│
    │  (amqp-client)   │ │(kafka-clients)│ │  (your impl)  │
    └─────────┬────────┘ └────┬──────────┘ └───┬───────────┘
              │                │                │
              ▼                ▼                ▼
        ┌──────────────────────────────────────────┐
        │          MessageTransport SPI            │
        │  subscribe / unsubscribe / start / close │
        └──────────────────┬───────────────────────┘
                           │ raw bytes
                           ▼
        ┌──────────────────────────────────────────┐
        │        OsloEnvelopeUnwrapper             │
        │     (v1 direct / v2 envelope unwrap)     │
        └──────────────────┬───────────────────────┘
                           │ JsonNode
                           ▼
        ┌──────────────────────────────────────────┐
        │         NotificationParser               │
        │  (event_type → ResourceType, status, ID) │
        └──────────────────┬───────────────────────┘
                           │ OpenStackEvent
                           ▼
        ┌──────────────────────────────────────────┐
        │       OpenStackEventListener(s)          │
        │         (your business logic)            │
        └──────────────────────────────────────────┘
```

## 文件清单

```
src/main/java/org/openstack4j/event/
├── config/
│   ├── EventConfig.java           # 配置 POJO（支持 RabbitMQ + Kafka）
│   └── EventConfigLoader.java     # YAML 配置加载器
├── envelope/
│   ├── OsloEnvelopeUnwrapper.java # oslo.messaging 信封解析（不变）
│   └── NotificationParser.java    # 通知解析为 OpenStackEvent（不变）
├── listener/
│   ├── OpenStackEventListener.java # 事件回调接口
│   └── OpenStackEventManager.java  # 主入口（生命周期 + SPI 编排）
├── model/
│   ├── OpenStackEvent.java         # 事件模型（不变）
│   └── ResourceType.java           # 资源类型枚举（不变）
└── transport/
    ├── MessageTransport.java       # SPI 接口
    ├── MessageCallback.java        # 原始消息回调
    ├── ServiceEndpoint.java        # 服务连接参数
    ├── TransportException.java     # 统一异常
    ├── rabbitmq/
    │   ├── RabbitMQTransport.java  # RabbitMQ 实现（每 vhost 一个 Connection）
    │   └── RabbitMQConfig.java     # RabbitMQ broker 配置
    └── kafka/
        ├── KafkaTransport.java     # Kafka 实现（反射加载，无编译时依赖）
        └── KafkaConfig.java        # Kafka broker 配置
```

## oslo.messaging Kafka Driver 注意事项

oslo.messaging 的 kafka driver 和 rabbit driver 在消息格式上的区别：

- **信封格式相同**：v1（raw）或 v2（oslo.message 包装），由 `OsloEnvelopeUnwrapper` 统一处理
- **无 vhost**：Kafka 没有 vhost 概念，所有服务共享同一个 Kafka 集群
- **Topic 命名**：`<exchange>.<topic>`，如 `nova.notifications`、`cinder.notifications`
- **无 routing key**：RabbitMQ 按 `notifications.info/error/warn` 做 routing，Kafka 不区分 priority，所有 priority 都在同一个 topic
- **消费者组**：Kafka 使用 consumer group 做负载均衡，通过 `group-id` 配置
