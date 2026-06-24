// WebSocket Types based on V12.md §8.3

export type ChannelType =
  | 'market_snapshot'
  | 'orderbook'
  | 'kline'
  | 'order_update'
  | 'trade_update'
  | 'account_update'
  | 'position_update'
  | 'agent_alert';

export type MessageType =
  | 'subscribe'
  | 'unsubscribe'
  | 'subscribe_confirm'
  | 'unsubscribe_confirm'
  | 'ping'
  | 'pong'
  | 'error'
  | 'market_snapshot'
  | 'orderbook'
  | 'kline'
  | 'order_update'
  | 'trade_update'
  | 'account_update'
  | 'position_update'
  | 'agent_alert';

export type KlineInterval = '1m' | '5m' | '15m' | '30m' | '1h' | '1d';

export interface WebSocketMessage {
  type: MessageType;
  messageId?: string;
  channel?: ChannelType;
  symbols?: string[];
  interval?: KlineInterval;
  data?: any;
  errorCode?: string;
  message?: string;
}

export interface SubscribeMessage {
  type: 'subscribe';
  messageId: string;
  channel: ChannelType;
  symbols: string[];
  interval?: KlineInterval;
}

export interface MarketSnapshotData {
  symbol: string;
  updateTime: number;
  bidPrices: string[];
  bidVolumes: string[];
  askPrices: string[];
  askVolumes: string[];
}

export interface OrderUpdateData {
  orderId: string;
  symbol: string;
  status: 'PENDING' | 'NEW' | 'PARTIAL_FILLED' | 'FILLED' | 'CANCELLED' | 'REJECTED';
  filledQuantity: string;
  remainingQty: string;
  updateTime: number;
}

export interface TradeUpdateData {
  tradeId: string;
  orderId: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  price: string;
  quantity: string;
  amount: string;
  commission: string;
  timestamp: number;
}

export interface AccountUpdateData {
  accountId: string;
  balance: string;
  available: string;
  frozen: string;
  reason: 'ORDER_FREEZE' | 'ORDER_UNFREEZE' | 'TRADE_BUY' | 'TRADE_SELL' | 'DEPOSIT' | 'COMMISSION';
  timestamp: number;
}

export interface PositionUpdateData {
  accountId: string;
  symbol: string;
  quantity: string;
  available: string;
  frozen: string;
  t1Locked: string;
  reason: 'ORDER_FREEZE' | 'ORDER_UNFREEZE' | 'TRADE_BUY' | 'TRADE_SELL' | 'T1_UNLOCK';
  timestamp: number;
}

export interface KlineData {
  symbol: string;
  interval: KlineInterval;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
  openTime: number;
  closeTime: number;
}

export interface AgentAlertData {
  agentName: string;
  alertLevel: 'P0' | 'P1' | 'P2';
  checkType: string;
  accountId?: string;
  symbol?: string;
  detail: string;
  suggestion?: string;
  timestamp: number;
}

// WebSocket configuration constants (V12.md §8.4.3)
export const WS_CONFIG = {
  MAX_SUBSCRIPTIONS: 50,
  MAX_SUBSCRIBE_RATE: 10, // per second
  HEARTBEAT_INTERVAL: 30000, // 30 seconds
  IDLE_TIMEOUT: 300000, // 5 minutes
  RECONNECT_INTERVAL: 3000, // 3 seconds
  MAX_RECONNECT_ATTEMPTS: 5,
};