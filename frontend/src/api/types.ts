// API Response types based on V12.md §8
// All field values are STRING type to prevent JS int64 precision loss

export interface ApiResponse<T = any> {
  code: string
  message: string
  data: T
  timestamp: string
  traceId: string
}

// Auth types
export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  accessExpireTime: string
  refreshExpireTime: string
  userId: string
  accountId: string
  username: string
  role: 'ADMIN' | 'TRADER'
}

export interface RegisterRequest {
  username: string
  password: string
}

export interface RegisterResponse {
  userId: string
  accountId: string
}

export interface RefreshTokenRequest {
  refreshToken: string
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

// Account types
export interface AccountInfo {
  accountId: string
  userId: string
  balance: string
  available: string
  frozen: string
  marginFrozen: string
  orderFrozen: string
  unrealizedPnl: string
  status: 'ACTIVE' | 'SUSPENDED' | 'LIQUIDATING' | 'NEGATIVE_BALANCE' | 'CLOSED'
}

export interface Position {
  accountId: string
  symbol: string
  symbolCode: number
  quantity: string
  available: string
  frozen: string
  t1Locked: string
  avgPrice: string
  unrealizedPnl: string
}

// Order types
export interface SubmitOrderRequest {
  symbol: string
  side: 'BUY' | 'SELL'
  orderType: 'LIMIT' | 'MARKET' | 'FAK' | 'FOK'
  price: string
  quantity: string
  maxPrice?: string
  minPrice?: string
  idempotencyKey: string
}

export interface SubmitOrderResponse {
  orderId: string
  status: 'PENDING'
}

export interface Order {
  orderId: string
  symbol: string
  side: 'BUY' | 'SELL'
  orderType: 'LIMIT' | 'MARKET' | 'FAK' | 'FOK'
  price: string
  quantity: string
  filledQuantity: string
  remainingQty: string
  status: 'PENDING' | 'NEW' | 'PARTIAL_FILLED' | 'FILLED' | 'CANCELLED' | 'REJECTED'
  accountId: string
  userId: string
  createTime: number
  updateTime: number
}

export interface CancelOrderResponse {
  orderId: string
  status: 'PENDING_CANCEL'
}

// Trade types
export interface Trade {
  tradeId: string
  orderId: string
  symbol: string
  side: 'BUY' | 'SELL'
  price: string
  quantity: string
  amount: string
  commission: string
  tradeTime: number
}

// Market types
export interface Symbol {
  symbol: string
  symbolCode: number
  name: string
  type: 'STOCK' | 'FUTURE' | 'OPTION'
  prevClose: string
  limitUpRatio: number
  limitDownRatio: number
  lotSize: string
  minPriceIncrement: string
}

export interface Kline {
  symbol: string
  interval: string
  open: string
  high: string
  low: string
  close: string
  volume: string
  openTime: number
  closeTime: number
}

export interface WatchlistSymbol {
  symbol: string
  createdAt: number
}

// Cashflow types
export interface Cashflow {
  id: string
  accountId: string
  amount: string
  type: 'DEPOSIT' | 'TRADE' | 'COMMISSION' | 'FREEZE' | 'UNFREEZE' | 'T1_UNLOCK'
  reason: string
  tradeId?: string
  orderId?: string
  createdAt: number
}

// Pagination
export interface PaginationParams {
  page?: number
  pageSize?: number
  symbol?: string
  side?: 'BUY' | 'SELL'
  status?: string
  startDate?: string
  endDate?: string
}

export interface PaginatedResponse<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

// Deposit types (Admin)
export interface DepositRequest {
  userId: string
  amount: string
  reason: string
}

export interface DepositResponse {
  depositId: string
  accountId: string
  newBalance: string
  newAvailable: string
}

export interface DepositCorrectionRequest {
  depositId: string
  amount: string
  reason: string
}

// API Key types
export interface ApiKey {
  apiKeyId: string
  userId: string
  apiKey: string
  keyType: 'READ_ONLY' | 'TRADING'
  allowedInstruments: string
  status: 'ACTIVE' | 'DISABLED'
  createdAt: number
  lastUsedAt: number
}

export interface CreateApiKeyRequest {
  name: string
  keyType: 'READ_ONLY' | 'TRADING'
  allowedInstruments?: string
}

export interface CreateApiKeyResponse {
  apiKeyId: string
  apiKey: string
  secretKey: string // Only returned once on creation
}

// System Status types
export interface SystemStatus {
  heapUsed: number
  heapMax: number
  heapUsagePercent: number
  threadCount: number
  status: string
  phase: string
  activeUsers: number
  todayOrders: number
  todayTrades: number
  todayTurnover: string
  tps: number
  p99Latency: number
  queueWriteRate: number
  mapSize: number
  diskUsage: number
}

// Trading Calendar types
export interface TradingDay {
  date: string
  isTradingDay: boolean
  description: string
}

export interface CalendarResponse {
  yearMonth: string
  days: TradingDay[]
}

// Deposit types
export interface DepositRecord {
  depositId: string
  userId: string
  accountId: string
  amount: string
  status: 'APPROVED' | 'REJECTED' | 'PENDING'
  reason: string
  processedAt: number
  processedBy: string
}

export interface ApproveDepositRequest {
  depositId: string
  actualAmount?: string
  reason?: string
}

export interface RejectDepositRequest {
  depositId: string
  reason?: string
}

// Account Admin types
export interface AccountAdminInfo {
  accountId: string
  userId: string
  balance: string
  available: string
  frozen: string
  marginFrozen: string
  orderFrozen: string
  unrealizedPnl: string
  riskRatio: number
  status: 'NORMAL' | 'WARNING' | 'FROZEN'
}

// Sina index quote types
export interface IndexQuote {
  symbol: string
  name: string
  open: string
  prevClose: string
  price: string
  high: string
  low: string
  volume: string
  amount: string
  date: string
  time: string
}

export interface SinaQuote {
  name: string
  open: string
  prevClose: string
  price: string
  high: string
  low: string
  volume: string
  amount: string
  date: string
  time: string
  change?: string
  changePercent?: string
}

// Ranking types
export interface RankingResponse {
  updateTime: string
  gainers: SinaQuote[]
  losers: SinaQuote[]
  topVolume: SinaQuote[]
}
