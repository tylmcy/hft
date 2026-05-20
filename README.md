# HFT Trading System

A股模拟交易系统

## 项目结构

```
hft/
├── docs/                    # 文档目录
│   ├── technical-solution-V3.md    # 技术方案V3
│   ├── test-plan.md               # 测试计划
│   ├── frontend-layout.html       # 前端布局设计
│   └── ...
├── CLAUDE.md               # 项目说明
└── README.md               # 本文件
```

## 技术栈

- 后端: Java 21 + Spring Boot 4 + Chronicle Map/Queue
- 前端: React 18 + TypeScript + TradingView + Ant Design 5
- 监控: Prometheus + Grafana + ELK

## 架构图

![alt text](trading-system-architecture.png)

![alt text](trading-frontend-layout-v4.png)


## 文档

- [技术方案 V3](docs/technical-solution-V3.md)
- [测试计划](docs/test-plan.md)
- [前端布局](docs/frontend-layout.html)
