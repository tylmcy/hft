# 优雅启动设计文档

## 概述

实现优雅启动机制，确保所有 Worker 完成初始化和恢复后，Tomcat 才绑定端口对外提供服务。

## 问题背景

当前系统使用 `@PostConstruct` 启动 Workers，但 Tomcat 在 Spring Boot 启动过程中就绑定了端口。这导致：

1. 请求到达时，系统可能处于 `STARTING` 或 `RECOVERING` 状态
2. Workers 未就绪时，请求可能失败或行为异常
3. 无法保证系统完全就绪后才接受流量

## 设计方案

### 核心机制

使用 Spring Boot 的 **SmartLifecycle** 接口控制启动顺序：

```
Spring Boot 启动流程:
1. 创建所有 Bean
2. 调用 SmartLifecycle.start() (按 phase 排序，phase 越小越先执行)
3. 所有 SmartLifecycle 启动完成后，绑定端口、开始接受请求
```

### 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot 启动流程                      │
├─────────────────────────────────────────────────────────────┤
│  1. Bean 初始化                                              │
│     - TradingConfig 创建 QueueManager, Stores 等            │
│     - LifecycleManager 创建 (不在此启动 Workers)             │
│                                                             │
│  2. SmartLifecycle.start() 调用 (phase=0, 最先执行)          │
│     - LifecycleManager.startup() 执行                       │
│       ① 初始化 Workers                                       │
│       ② 检查并执行恢复 (超时 60s)                             │
│       ③ 启动所有 Workers                                     │
│       ④ 等待所有 Worker 就绪 (超时 60s)                       │
│       ⑤ 状态设为 RUNNING                                     │
│                                                             │
│  3. 所有 SmartLifecycle 完成后                               │
│     - Tomcat 绑定端口 8080                                   │
│     - 开始接受 HTTP 请求                                     │
│                                                             │
│  4. 如果启动失败                                             │
│     - 系统状态设为 DEGRADED                                  │
│     - 返回 503 Service Unavailable                           │
└─────────────────────────────────────────────────────────────┘
```

### 关键修改

#### 1. LifecycleManager 实现 SmartLifecycle

```java
@Component
public class LifecycleManager implements SmartLifecycle {

    private volatile boolean running = false;

    @Override
    public int getPhase() {
        return 0; // 最先启动
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void start() {
        // 原来的 @PostConstruct startup() 逻辑移到这里
        // 增加：
        // - 恢复超时检查 (60s)
        // - Worker 就绪等待 (60s)
        running = true;
    }

    @Override
    public void stop() {
        // 原来的 @PreDestroy shutdown() 逻辑移到这里
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
```

#### 2. Worker 就绪检查

每个 Worker 增加就绪标志：

```java
public abstract class BaseWorker {
    protected volatile boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    protected void markReady() {
        this.ready = true;
    }
}
```

Worker 启动流程：
```java
public void run() {
    // 初始化完成
    markReady();

    while (running) {
        // 处理事件
    }
}
```

#### 3. 恢复超时控制

```java
private void performRecoveryWithTimeout() {
    long timeoutMs = 60_000;
    long startTime = System.currentTimeMillis();

    CompletableFuture<Void> recoveryFuture = CompletableFuture.runAsync(() -> {
        recoveryManager.performRecovery();
    });

    try {
        recoveryFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        logger.error("Recovery timeout after {}ms", timeoutMs);
        systemStatus = SystemStatus.DEGRADED;
        throw new RuntimeException("Recovery timeout");
    }
}
```

#### 4. Worker 就绪等待

```java
private void waitForWorkersReady(long timeoutMs) {
    long startTime = System.currentTimeMillis();

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        boolean allReady = accountWorker.isReady()
            && matchingEngineWorker.isReady()
            && clearingWorker.isReady();

        if (allReady) {
            logger.info("All workers ready");
            return;
        }

        sleep(100);
    }

    logger.error("Workers not ready after {}ms", timeoutMs);
    systemStatus = SystemStatus.DEGRADED;
    throw new RuntimeException("Workers startup timeout");
}
```

#### 5. 移除 @PostConstruct 和 @PreDestroy

将 `@PostConstruct startup()` 改为 `SmartLifecycle.start()`
将 `@PreDestroy shutdown()` 改为 `SmartLifecycle.stop()`

### 配置项

```yaml
trading:
  startup:
    recovery-timeout-ms: 60000    # 恢复超时时间
    worker-ready-timeout-ms: 60000 # Worker 就绪等待超时
```

### 错误处理

| 场景 | 处理方式 |
|------|----------|
| 恢复超时 | 系统进入 DEGRADED 模式，HTTP 返回 503 |
| Worker 启动失败 | 系统进入 DEGRADED 模式，HTTP 返回 503 |
| 恢复异常 | 系统进入 DEGRADED 模式，记录错误日志 |

### 成功标准

- [ ] Workers 启动并完成恢复后，Tomcat 才绑定端口
- [ ] 恢复超时 60 秒，超时后系统进入 DEGRADED 模式
- [ ] DEGRADED 模式下 HTTP 请求返回 503 错误
- [ ] 系统正常启动后，所有 API 正常响应
- [ ] 优雅关闭时，先停止接受请求，再关闭 Workers

## 实现文件清单

| 文件 | 修改内容 |
|------|----------|
| `LifecycleManager.java` | 实现 SmartLifecycle 接口，移除 @PostConstruct/@PreDestroy |
| `AccountWorker.java` | 增加 isReady() 方法 |
| `MatchingEngineWorker.java` | 增加 isReady() 方法 |
| `ClearingWorker.java` | 增加 isReady() 方法 |
| `AsyncPersistenceWorker.java` | 增加 isReady() 方法 |
| `application.yml` | 增加启动超时配置 |
