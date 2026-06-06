# 优雅启动实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Worker 完成恢复后 Tomcat 才绑定端口，确保系统完全就绪后才接受请求。

**Architecture:** 使用 Spring SmartLifecycle 接口控制启动顺序，LifecycleManager 在 phase=0 时启动，等待所有 Worker 就绪后才返回，之后 Tomcat 绑定端口。

**Tech Stack:** Spring Boot 3.2, SmartLifecycle, CompletableFuture

---

## Task 1: Worker 增加 isReady() 方法

**Files:**
- Modify: `src/main/java/com/hft/trading/worker/AccountWorker.java:59`
- Modify: `src/main/java/com/hft/trading/worker/MatchingEngineWorker.java:54`
- Modify: `src/main/java/com/hft/trading/worker/ClearingWorker.java:53`
- Modify: `src/main/java/com/hft/trading/worker/AsyncPersistenceWorker.java:48`

- [ ] **Step 1: AccountWorker 增加 isReady() 方法**

在 `running` 字段下方添加 `ready` 字段和 getter：

```java
// 状态
private volatile boolean running = false;
private volatile boolean ready = false;  // 新增

public boolean isReady() {
    return ready;
}
```

在 `run()` 方法开头，线程启动后立即标记就绪。找到 `run()` 方法，在 `while (running)` 循环之前添加：

```java
@Override
public void run() {
    logger.info("AccountWorker thread started");
    this.ready = true;  // 新增：标记就绪

    while (running) {
        // ... 原有代码
    }
}
```

- [ ] **Step 2: MatchingEngineWorker 增加 isReady() 方法**

同上，在 `running` 字段下方添加：

```java
// 状态
private volatile boolean running = false;
private volatile boolean ready = false;

public boolean isReady() {
    return ready;
}
```

在 `run()` 方法开头添加：

```java
@Override
public void run() {
    logger.info("MatchingEngineWorker thread started");
    this.ready = true;

    while (running) {
        // ... 原有代码
    }
}
```

- [ ] **Step 3: ClearingWorker 增加 isReady() 方法**

同上：

```java
// 状态
private volatile boolean running = false;
private volatile boolean ready = false;

public boolean isReady() {
    return ready;
}
```

在 `run()` 方法开头添加：

```java
@Override
public void run() {
    logger.info("ClearingWorker thread started");
    this.ready = true;

    while (running) {
        // ... 原有代码
    }
}
```

- [ ] **Step 4: AsyncPersistenceWorker 增加 isReady() 方法**

同上：

```java
// 状态
private volatile boolean running = false;
private volatile boolean ready = false;

public boolean isReady() {
    return ready;
}
```

在 `run()` 方法开头添加：

```java
@Override
public void run() {
    logger.info("AsyncPersistenceWorker thread started");
    this.ready = true;

    while (running) {
        // ... 原有代码
    }
}
```

- [ ] **Step 5: 构建验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

## Task 2: LifecycleManager 实现 SmartLifecycle

**Files:**
- Modify: `src/main/java/com/hft/trading/lifecycle/LifecycleManager.java`

- [ ] **Step 1: 添加 SmartLifecycle 接口导入和实现声明**

修改类声明：

```java
import org.springframework.context.SmartLifecycle;

@Component
public class LifecycleManager implements SmartLifecycle {
```

- [ ] **Step 2: 添加 running 标志和 SmartLifecycle 方法**

在 `systemStatus` 字段下方添加：

```java
private volatile boolean running = false;
```

在类末尾 `}` 之前添加 SmartLifecycle 接口方法：

```java
// ==================== SmartLifecycle 接口实现 ====================

@Override
public int getPhase() {
    return 0; // 最先启动
}

@Override
public boolean isAutoStartup() {
    return true;
}

@Override
public boolean isRunning() {
    return running;
}

@Override
public void stop(Runnable callback) {
    shutdown();
    running = false;
    callback.run();
}

@Override
public void stop() {
    shutdown();
    running = false;
}
```

- [ ] **Step 3: 移除 @PostConstruct，重命名 startup() 为 start()**

移除 `@PostConstruct` 注解，将 `startup()` 方法重命名为 `start()` 并添加 `@Override`：

```java
/**
 * 系统启动入口 - SmartLifecycle.start()
 */
@Override
public void start() {
    if (running) {
        logger.warn("LifecycleManager already running");
        return;
    }

    logger.info("=== HFT Trading System Starting ===");
    logger.info("System status: STARTING");

    try {
        // 1. 初始化 Workers
        initializeWorkers();

        // 2. 检查是否需要恢复
        if (needsRecovery()) {
            systemStatus = SystemStatus.RECOVERING;
            logger.info("System status: RECOVERING");
            performRecoveryWithTimeout();
        }

        // 3. 启动所有 Workers
        startAllWorkers();

        // 4. 等待所有 Workers 就绪
        waitForWorkersReady();

        // 5. 系统就绪
        systemStatus = SystemStatus.RUNNING;
        running = true;
        logger.info("System status: RUNNING");
        logger.info("=== HFT Trading System Started Successfully ===");

    } catch (Exception e) {
        logger.error("System startup failed: {}", e.getMessage(), e);
        systemStatus = SystemStatus.DEGRADED;
        running = true; // 仍标记为 running，让容器启动
    }
}
```

- [ ] **Step 4: 移除 @PreDestroy 注解**

移除 `shutdown()` 方法上的 `@PreDestroy` 注解（SmartLifecycle.stop() 会调用它）。

- [ ] **Step 5: 添加恢复超时方法**

在 `performRecovery()` 方法后添加：

```java
/**
 * 带超时的恢复执行
 */
private void performRecoveryWithTimeout() {
    long timeoutMs = 60_000; // 60 秒超时
    logger.info("Starting recovery with {}ms timeout", timeoutMs);

    CompletableFuture<Void> recoveryFuture = CompletableFuture.runAsync(() -> {
        recoveryManager.performRecovery();
    });

    try {
        recoveryFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        logger.info("Recovery completed successfully");
    } catch (TimeoutException e) {
        logger.error("Recovery timeout after {}ms", timeoutMs);
        systemStatus = SystemStatus.DEGRADED;
        throw new RuntimeException("Recovery timeout", e);
    } catch (Exception e) {
        logger.error("Recovery failed: {}", e.getMessage(), e);
        systemStatus = SystemStatus.DEGRADED;
        throw new RuntimeException("Recovery failed", e);
    }
}
```

添加必要的 import：

```java
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
```

- [ ] **Step 6: 添加 Worker 就绪等待方法**

在 `startAllWorkers()` 方法后添加：

```java
/**
 * 等待所有 Workers 就绪
 */
private void waitForWorkersReady() {
    long timeoutMs = 60_000; // 60 秒超时
    long startTime = System.currentTimeMillis();
    long checkIntervalMs = 100;

    logger.info("Waiting for all workers to be ready...");

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        boolean accountReady = accountWorker != null && accountWorker.isReady();
        boolean matchingReady = matchingEngineWorker != null && matchingEngineWorker.isReady();
        boolean clearingReady = clearingWorker != null && clearingWorker.isReady();
        boolean persistenceReady = asyncPersistenceWorker == null || asyncPersistenceWorker.isReady();

        if (accountReady && matchingReady && clearingReady && persistenceReady) {
            logger.info("All workers ready after {}ms", System.currentTimeMillis() - startTime);
            return;
        }

        sleep(checkIntervalMs);
    }

    // 超时处理
    logger.error("Workers not ready after {}ms", timeoutMs);
    logger.error("Ready status: AccountWorker={}, MatchingEngineWorker={}, ClearingWorker={}, AsyncPersistenceWorker={}",
        accountWorker != null && accountWorker.isReady(),
        matchingEngineWorker != null && matchingEngineWorker.isReady(),
        clearingWorker != null && clearingWorker.isReady(),
        asyncPersistenceWorker != null && asyncPersistenceWorker.isReady());

    systemStatus = SystemStatus.DEGRADED;
    throw new RuntimeException("Workers startup timeout");
}
```

- [ ] **Step 7: 构建验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

---

## Task 3: 增加启动超时配置

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 添加启动配置项**

在 `trading:` 下添加 `startup:` 配置：

```yaml
# 交易系统配置
trading:
  chronicle:
    base-path: ./data/chronicle/
  startup:
    recovery-timeout-ms: 60000
    worker-ready-timeout-ms: 60000
```

- [ ] **Step 2: LifecycleManager 读取配置**

在 `LifecycleManager` 构造函数中添加配置读取（可选，使用 `@Value`）：

由于当前 LifecycleManager 没有使用 `@Value`，我们暂时硬编码默认值。如果需要动态配置，可以在后续优化时添加。

---

## Task 4: 构建并测试

**Files:**
- 无新增文件

- [ ] **Step 1: 完整构建**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: 启动应用测试**

Run: `./start.sh`
Expected:
- 日志显示 "Waiting for all workers to be ready..."
- 日志显示 "All workers ready after Xms"
- 日志显示 "System status: RUNNING"
- 应用成功绑定端口 8080

- [ ] **Step 3: 验证 API 可访问**

Run: `curl http://localhost:8080/actuator/health`
Expected: HTTP 200，返回健康状态

- [ ] **Step 4: 停止应用**

Run: `./stop.sh`
Expected: 应用优雅关闭

---

## Task 5: 提交代码

- [ ] **Step 1: Git 提交**

```bash
git add src/main/java/com/hft/trading/worker/*.java \
        src/main/java/com/hft/trading/lifecycle/LifecycleManager.java \
        src/main/resources/application.yml \
        docs/superpowers/specs/2026-06-06-graceful-startup-design.md \
        docs/superpowers/plans/2026-06-06-graceful-startup.md

git commit -m "feat: implement graceful startup with SmartLifecycle

- Add isReady() method to all Workers
- LifecycleManager implements SmartLifecycle with phase=0
- Wait for all workers ready before Tomcat binds port
- Add 60s timeout for recovery and worker startup
- System enters DEGRADED mode on startup failure"
```
