# 部署运维 Skill

当用户需要设计和实现交易系统的部署运维方案时使用此skill。

## 触发场景
- 部署方案设计
- 容器化配置
- 运维脚本开发
- 灾备方案设计

## 核心原则

### 1. 部署架构

```
┌─────────────────────────────────────────────────────────┐
│                      负载均衡层                          │
│                   Nginx / HAProxy                       │
└─────────────────────────────────────────────────────────┘
                          │
            ┌─────────────┼─────────────┐
            ▼             ▼             ▼
    ┌───────────┐  ┌───────────┐  ┌───────────┐
    │  Node 1   │  │  Node 2   │  │  Node 3   │
    │  (Master) │  │  (Slave)  │  │  (Slave)  │
    └───────────┘  └───────────┘  └───────────┘
            │             │             │
            └─────────────┼─────────────┘
                          ▼
    ┌─────────────────────────────────────────────────┐
    │              共享存储层                          │
    │  Chronicle Queue (NFS) | H2 DB (Replication)   │
    └─────────────────────────────────────────────────┘
```

### 2. Docker 容器化

#### Dockerfile
```dockerfile
FROM openjdk:17-jdk-alpine

# 安装依赖
RUN apk add --no-cache curl

# 创建应用目录
WORKDIR /app

# 复制应用文件
COPY target/trading-system.jar /app/
COPY config/ /app/config/

# JVM 参数优化
ENV JAVA_OPTS="-Xms4g -Xmx4g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=10 \
    -XX:+UseStringDeduplication \
    -XX:+AlwaysPreTouch \
    -XX:+UseCompressedOops"

# 健康检查
HEALTHCHECK --interval=10s --timeout=3s \
    CMD curl -f http://localhost:8080/health || exit 1

# 启动应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar trading-system.jar"]
```

#### docker-compose.yml
```yaml
version: '3.8'

services:
  trading-engine:
    build: .
    container_name: trading-engine
    ports:
      - "8080:8080"
      - "9090:9090"  # metrics
    volumes:
      - ./data/chronicle:/data/chronicle
      - ./data/h2:/data/h2
      - ./config:/app/config
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - JAVA_OPTS=-Xms4g -Xmx4g
    networks:
      - trading-network
    depends_on:
      - redis
      - prometheus

  redis:
    image: redis:7-alpine
    container_name: redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    networks:
      - trading-network

  prometheus:
    image: prom/prometheus:latest
    container_name: prometheus
    ports:
      - "9091:9090"
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
    networks:
      - trading-network

  grafana:
    image: grafana/grafana:latest
    container_name: grafana
    ports:
      - "3000:3000"
    volumes:
      - grafana-data:/var/lib/grafana
      - ./monitoring/dashboards:/etc/grafana/provisioning/dashboards
    networks:
      - trading-network

volumes:
  redis-data:
  grafana-data:

networks:
  trading-network:
    driver: bridge
```

### 3. Kubernetes 部署

#### deployment.yaml
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: trading-engine
spec:
  replicas: 3
  selector:
    matchLabels:
      app: trading-engine
  template:
    metadata:
      labels:
        app: trading-engine
    spec:
      containers:
      - name: trading-engine
        image: trading-system:latest
        ports:
        - containerPort: 8080
        - containerPort: 9090
        resources:
          requests:
            memory: "4Gi"
            cpu: "2"
          limits:
            memory: "8Gi"
            cpu: "4"
        livenessProbe:
          httpGet:
            path: /live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /ready
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 5
        volumeMounts:
        - name: chronicle-data
          mountPath: /data/chronicle
        - name: h2-data
          mountPath: /data/h2
      volumes:
      - name: chronicle-data
        persistentVolumeClaim:
          claimName: chronicle-pvc
      - name: h2-data
        persistentVolumeClaim:
          claimName: h2-pvc
```

### 4. 运维脚本

#### 启动脚本
```bash
#!/bin/bash
# start.sh

set -e

echo "Starting Trading System..."

# 检查环境
check_environment() {
    echo "Checking environment..."
    java -version
    docker --version
}

# 启动服务
start_services() {
    echo "Starting services..."
    docker-compose up -d

    # 等待服务就绪
    sleep 10

    # 健康检查
    curl -f http://localhost:8080/health || exit 1
    echo "Trading System started successfully!"
}

check_environment
start_services
```

#### 停止脚本
```bash
#!/bin/bash
# stop.sh

set -e

echo "Stopping Trading System..."

# 优雅关闭
curl -X POST http://localhost:8080/shutdown

# 等待处理完成
sleep 30

# 强制停止
docker-compose down

echo "Trading System stopped."
```

#### 备份脚本
```bash
#!/bin/bash
# backup.sh

BACKUP_DIR="/backup/$(date +%Y%m%d_%H%M%S)"
mkdir -p $BACKUP_DIR

# 备份 Chronicle Queue
echo "Backing up Chronicle Queue..."
cp -r /data/chronicle $BACKUP_DIR/

# 备份 H2 数据库
echo "Backing up H2 database..."
java -cp h2.jar org.h2.tools.Backup -url jdbc:h2:/data/h2/trading -user sa -password "" -backup $BACKUP_DIR/h2_backup.zip

# 备份配置文件
echo "Backing up config files..."
cp -r /app/config $BACKUP_DIR/

echo "Backup completed: $BACKUP_DIR"
```

### 5. 监控告警配置

#### Prometheus 配置
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'trading-engine'
    static_configs:
      - targets: ['trading-engine:9090']

rule_files:
  - '/etc/prometheus/rules/*.yml'

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']
```

#### AlertManager 配置
```yaml
global:
  resolve_timeout: 5m
  smtp_smarthost: 'smtp.example.com:587'
  smtp_from: 'alert@trading.com'

route:
  group_by: ['alertname']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 1h
  receiver: 'trading-team'

receivers:
  - name: 'trading-team'
    email_configs:
      - to: 'trading-team@example.com'
    webhook_configs:
      - url: 'http://webhook:5000/alert'
```

### 6. 日志管理

#### Logback 配置
```xml
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/logs/trading.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/logs/trading.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>

    <root level="INFO">
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### 7. 灾备方案

#### 主备切换
```java
public class FailoverManager {
    // 主节点故障检测
    public void monitorMaster() {
        // 心跳检测
        // 故障判定
        // 自动切换
    }

    // 数据同步
    public void syncData() {
        // Chronicle Queue 复制
        // H2 数据库同步
    }
}
```

#### 数据恢复
```bash
#!/bin/bash
# restore.sh

BACKUP_DIR=$1

echo "Restoring from backup: $BACKUP_DIR"

# 停止服务
./stop.sh

# 恢复数据
cp -r $BACKUP_DIR/chronicle/* /data/chronicle/
java -cp h2.jar org.h2.tools.Restore -url jdbc:h2:/data/h2/trading -user sa -password "" -backup $BACKUP_DIR/h2_backup.zip

# 启动服务
./start.sh

echo "Restore completed."
```

## 输出要求
- 完整的 Docker/K8s 配置
- 运维脚本集合
- 监控告警配置
- 灾备恢复方案
