# 专业交易前端设计 Skill

当用户需要设计符合传统金融专业性 + 加密货币交易所友好性的前端界面时使用此skill。

## 触发场景
- 交易界面布局设计
- K线图和深度图实现
- 订单簿可视化
- 账户和持仓展示
- 键盘快捷键支持

## 核心原则

### 1. 整体布局设计 (v3)

四栏式布局 + 账户信息集成导航栏：

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  Logo  │ 股票 │ 期货 │ 期权 │ 自选 │  总资产  │  持仓市值  │  可用  │  风险度  │  保证金  │  浮盈  │ 设置 │
├─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌────────────┐  ┌────────────────────────────────┐  ┌──────────────────┐  ┌────────────┐        │
│  │ 市场列表    │  │   [分时] [K线] [深度图]        │  │    订单簿        │  │ 账户详情    │        │
│  │            │  │        K线图 (TradingView)     │  │  卖五~卖一       │  │ 资金明细    │        │
│  │ 全部股票   │  │                                │  │  ─────────────  │  │ 持仓明细    │        │
│  │ 涨幅榜     │  ├────────────────────────────────┤  │  买一~买五       │  │ 保证金详情  │        │
│  │ 跌幅榜     │  │  成交明细 (Time & Sales)       │  │                  │  │            │        │
│  │ 成交额榜   │  │  10:30:05  10.01  100  B       │  │  ┌────────────┐ │  │            │        │
│  │ 自选股     │  ├────────────────────────────────┤  │  │ 快捷下单    │ │  │            │        │
│  │            │  │        下单面板                 │  │  │ 买 [10.00] │ │  │            │        │
│  │            │  │  [限价] [市价] [FAK] [FOK]     │  │  │ 卖 [10.01] │ │  │            │        │
│  │            │  │  价格: [__10.00__] [↑] [↓]     │  │  └────────────┘ │  │            │        │
│  │            │  │  数量: [__100___] [1/2][全部]  │  │                  │  │            │        │
│  │            │  │  [买入 F1]       [卖出 F2]      │  │                  │  │            │        │
│  └────────────┘  └────────────────────────────────┘  └──────────────────┘  └────────────┘        │
├─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  [当前委托]  [历史成交]  [资金流水]  [持仓管理]  [交易统计]  [预警设置]                              │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. 导航栏账户信息

**位置**: 菜单和设置按钮之间，节省垂直空间

| 指标 | 说明 | 颜色规则 |
|------|------|----------|
| 总资产 | 现金 + 持仓市值 | 白色 |
| 持仓市值 | 股票持仓总市值 | 白色 |
| 可用资金 | 可用于开新仓 | 青色高亮 |
| 风险度 | 保证金/总资产 | <50%绿 / 50-80%黄 / >80%红 |
| 占用保证金 | 期货/期权占用 | 橙色 |
| 浮动盈亏 | 未实现盈亏 | 盈利绿 / 亏损红 |

**交互**: 悬停Tooltip、点击展开详情弹窗

### 3. 核心组件设计

#### TradingViewChart (K线图)
```typescript
interface ChartConfig {
    symbol: string;
    interval: '1m' | '5m' | '15m' | '30m' | '60m' | '1d' | '1w' | '1M';
    indicators: ('MACD' | 'KDJ' | 'RSI' | 'BOLL')[];
    theme: 'dark'; // 专业交易深色主题
    showDepth: boolean;
}
```

#### OrderBook (订单簿)
```typescript
interface OrderBookProps {
    bids: PriceLevel[];  // 买一~买五
    asks: PriceLevel[];  // 卖一~卖五
    onPriceClick: (price: number, side: 'buy' | 'sell') => void; // 点击价格快捷下单
}
```

#### OrderPanel (下单面板)
```typescript
interface OrderPanelProps {
    symbol: string;
    orderTypes: ('limit' | 'market' | 'FAK' | 'FOK')[];
    priceAdjust: 'tick'; // ↑↓按钮按tick调整
    quantityShortcuts: ['1/2', '1/3', '全部']; // 数量快捷按钮
    hotkeys: { buy: 'F1', sell: 'F2' };
}
```

#### QuickOrder (快捷下单)
```typescript
interface QuickOrderProps {
    buyPrice: number;  // 买一价
    sellPrice: number; // 卖一价
    onQuickBuy: () => void;
    onQuickSell: () => void;
}
```

### 4. 键盘快捷键

| 快捷键 | 功能 | 适用场景 |
|--------|------|----------|
| F1 | 快速买入 | 下单面板聚焦 |
| F2 | 快速卖出 | 下单面板聚焦 |
| ↑ | 价格上调tick | 价格输入聚焦 |
| ↓ | 价格下调tick | 价格输入聚焦 |
| Ctrl+↑ | 数量+100 | 数量输入聚焦 |
| Ctrl+↓ | 数量-100 | 数量输入聚焦 |
| Delete | 撤销选中订单 | 委托列表聚焦 |
| Esc | 清空/关闭 | 全局 |

### 5. 技术栈

```json
{
  "framework": "React 18 + TypeScript",
  "charts": "TradingView",
  "ui": "Ant Design 5.x",
  "state": "Zustand",
  "websocket": "native WebSocket + reconnect",
  "build": "Vite",
  "performance": "virtual scroll, Canvas渲染"
}
```

### 6. 组件结构

```
src/components/
├── Layout/ (Header, Sidebar, MainContent, RightPanel, BottomPanel)
├── Chart/ (TradingViewChart, TimeShareChart, DepthChart)
├── OrderBook/ (OrderBook, QuickOrder, ContractInfo)
├── Trade/ (OrderPanel, PriceInput, QuantityInput)
├── Market/ (MarketList, TradeDetails)
├── Orders/ (ActiveOrders, TradeHistory, FundFlow, PositionList)
├── Stats/ (TradeStats, StatsChart)
├── Alert/ (AlertList, AlertForm, AlertNotify)
└── Account/ (AccountBar, AccountPanel)
```

### 7. 性能优化
- 虚拟滚动处理大量数据 (委托列表、成交明细)
- Canvas 渲染 K 线图
- Web Worker 处理数据计算
- 防抖/节流高频更新
- Zustand 轻量状态管理

### 8. 时间戳转换规范

**重要**: 后端返回的所有时间字段均为毫秒级时间戳 (BIGINT)，前端需转换为本地时区显示

```typescript
// utils/time.ts
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';

dayjs.extend(utc);
dayjs.extend(timezone);

// 时间戳转本地时间字符串
export function formatTime(timestamp: number, format = 'YYYY-MM-DD HH:mm:ss'): string {
  return dayjs(timestamp).format(format);
}

// 时间戳转时分秒 (用于成交明细)
export function formatTimeOnly(timestamp: number): string {
  return dayjs(timestamp).format('HH:mm:ss');
}

// 时间戳转日期 (用于K线图)
export function formatDate(timestamp: number): string {
  return dayjs(timestamp).format('YYYY-MM-DD');
}

// 相对时间 (用于订单列表)
export function formatRelativeTime(timestamp: number): string {
  const now = Date.now();
  const diff = now - timestamp;

  if (diff < 60000) return '刚刚';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`;
  return dayjs(timestamp).format('MM-DD HH:mm');
}

// 示例使用
const orderTime = 1716220805000;  // 后端返回的时间戳
const displayTime = formatTime(orderTime);  // "2024-05-20 16:00:05"
```

**组件中使用示例**:
```tsx
// 成交明细组件
function TradeDetails({ trades }: { trades: Trade[] }) {
  return (
    <table>
      <thead>
        <tr>
          <th>时间</th>
          <th>价格</th>
          <th>数量</th>
        </tr>
      </thead>
      <tbody>
        {trades.map(trade => (
          <tr key={trade.tradeId}>
            <td>{formatTimeOnly(trade.tradeTime)}</td>
            <td>{formatPrice(trade.price)}</td>
            <td>{trade.quantity}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```

### 9. 交互优化
- 点击订单簿价格 → 自动填入下单面板
- 价格↑↓按钮 → 按tick调整
- 数量1/2/全部快捷按钮
- 风险度进度条可视化 (>80%闪烁警告)

## 输出要求
- 完整的组件代码实现
- 布局配置文件
- WebSocket 数据订阅方案
- 键盘快捷键绑定
- 性能优化清单
- 时间戳转换工具函数