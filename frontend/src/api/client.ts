import api from './index'
import {
  ApiResponse,
  AccountInfo,
  Position,
  SubmitOrderRequest,
  SubmitOrderResponse,
  Order,
  CancelOrderResponse,
  Trade,
  Symbol,
  Kline,
  WatchlistSymbol,
  Cashflow,
  PaginationParams,
  PaginatedResponse,
  SystemStatus,
  CalendarResponse,
  DepositRecord,
  ApproveDepositRequest,
  RejectDepositRequest,
  AccountAdminInfo,
  IndexQuote,
  SinaQuote,
  RankingResponse,
} from './types'

// ==================== Account API ====================

export const accountApi = {
  // GET /api/account/info
  getInfo: () =>
    api.get<unknown, ApiResponse<AccountInfo>>('/account/info'),

  // GET /api/account/positions
  getPositions: () =>
    api.get<unknown, ApiResponse<Position[]>>('/account/positions'),

  // GET /api/account/cashflow
  getCashflow: (params?: PaginationParams) =>
    api.get<unknown, ApiResponse<PaginatedResponse<Cashflow>>>('/account/cashflow', { params }),
}

// ==================== Order API ====================

export const orderApi = {
  // POST /api/orders
  submit: (data: SubmitOrderRequest) =>
    api.post<unknown, ApiResponse<SubmitOrderResponse>>('/orders', data),

  // DELETE /api/orders/{orderId}
  cancel: (orderId: string) =>
    api.delete<unknown, ApiResponse<CancelOrderResponse>>(`/orders/${orderId}`),

  // GET /api/orders/active
  getActive: () =>
    api.get<unknown, ApiResponse<Order[]>>('/orders/active'),

  // GET /api/orders/{orderId}
  getById: (orderId: string) =>
    api.get<unknown, ApiResponse<Order>>(`/orders/${orderId}`),

  // GET /api/orders/history
  getHistory: (params?: PaginationParams) =>
    api.get<unknown, ApiResponse<PaginatedResponse<Order>>>('/orders/history', { params }),
}

// ==================== Trade API ====================

export const tradeApi = {
  // GET /api/trades
  getHistory: (params?: PaginationParams) =>
    api.get<unknown, ApiResponse<PaginatedResponse<Trade>>>('/trades', { params }),
}

// ==================== Market API ====================

export const marketApi = {
  // GET /api/market/indices (指数行情，从新浪拉取)
  getIndices: () =>
    api.get<unknown, ApiResponse<IndexQuote[]>>('/market/indices'),

  // GET /api/market/sina?codes=sh600000 (通用新浪行情代理)
  getSinaQuotes: (codes: string) =>
    api.get<unknown, ApiResponse<SinaQuote[]>>('/market/sina', { params: { codes } }),

  // GET /api/market/symbols
  getSymbols: () =>
    api.get<unknown, ApiResponse<Symbol[]>>('/market/symbols'),

  // GET /api/market/watchlist
  getWatchlist: () =>
    api.get<unknown, ApiResponse<WatchlistSymbol[]>>('/market/watchlist'),

  // POST /api/market/watchlist
  addToWatchlist: (symbol: string) =>
    api.post<unknown, ApiResponse<void>>('/market/watchlist', { symbol }),

  // DELETE /api/market/watchlist
  removeFromWatchlist: (symbol: string) =>
    api.delete<unknown, ApiResponse<void>>('/market/watchlist', { data: { symbol } }),

  // GET /api/market/kline
  getKline: (params: {
    symbol: string
    interval: '1m' | '5m' | '15m' | '30m' | '1h' | '1d'
    startTime?: number
    endTime?: number
    limit?: number
  }) =>
    api.get<unknown, ApiResponse<Kline[]>>('/market/kline', { params }),

  // GET /api/market/rankings (涨跌排行榜)
  getRankings: () =>
    api.get<unknown, ApiResponse<RankingResponse>>('/market/rankings'),
}

// ==================== Admin API ====================

export const adminApi = {
  // Deposit
  deposit: (data: { userId: string; amount: string; reason: string }) =>
    api.post<unknown, ApiResponse<{
      depositId: string
      accountId: string
      newBalance: string
      newAvailable: string
    }>>('/admin/deposit', data),

  depositCorrection: (data: { depositId: string; amount: string; reason: string }) =>
    api.post<unknown, ApiResponse<{
      correctionId: string
      accountId: string
      newBalance: string
      newAvailable: string
    }>>('/admin/deposit/correction', data),

  getDeposits: (params?: { userId?: string; startDate?: string; endDate?: string }) =>
    api.get<unknown, ApiResponse<PaginatedResponse<DepositRecord>>>('/admin/deposits', { params }),

  getPendingDeposits: () =>
    api.get<unknown, ApiResponse<DepositRecord[]>>('/admin/deposits/pending'),

  getDepositHistory: (params?: PaginationParams) =>
    api.get<unknown, ApiResponse<PaginatedResponse<DepositRecord>>>('/admin/deposits/history', { params }),

  approveDeposit: (data: ApproveDepositRequest) =>
    api.post<unknown, ApiResponse<{
      depositId: string
      accountId: string
      newBalance: string
      newAvailable: string
    }>>('/admin/deposit/approve', data),

  rejectDeposit: (data: RejectDepositRequest) =>
    api.post<unknown, ApiResponse<void>>('/admin/deposit/reject', data),

  // Instruments
  addInstrument: (data: {
    symbol: string
    name: string
    type: 'STOCK' | 'FUTURE' | 'OPTION'
    prevClose: string
    limitUpRatio: number
    limitDownRatio: number
    lotSize: string
    minPriceIncrement: string
    commissionRate: number
  }) =>
    api.post<unknown, ApiResponse<void>>('/admin/instruments', data),

  // System
  getSystemStatus: () =>
    api.get<unknown, ApiResponse<SystemStatus>>('/admin/system/status'),

  recover: () =>
    api.post<unknown, ApiResponse<void>>('/admin/system/recover'),

  // Calendar
  getCalendar: (yearMonth?: string) =>
    api.get<unknown, ApiResponse<CalendarResponse>>('/admin/calendar', { params: { yearMonth } }),

  updateCalendar: (data: { date: string; isTradingDay: boolean }) =>
    api.post<unknown, ApiResponse<void>>('/admin/calendar', data),

  setHolidays: (data: { dates: string[]; reason: string }) =>
    api.post<unknown, ApiResponse<void>>('/admin/calendar/holidays', data),

  // Accounts
  getAccounts: (params?: { page?: number; pageSize?: number; status?: string }) =>
    api.get<unknown, ApiResponse<PaginatedResponse<AccountAdminInfo>>>('/admin/accounts', { params }),

  // API Keys
  getApiKeys: (userId?: string) =>
    api.get<unknown, ApiResponse<any[]>>('/admin/api-keys', { params: { userId } }),

  createApiKey: (data: { name: string; keyType: 'READ_ONLY' | 'TRADING'; allowedInstruments?: string }) =>
    api.post<unknown, ApiResponse<{ apiKeyId: string; apiKey: string; secretKey: string }>>('/admin/api-keys', data),

  deleteApiKey: (apiKeyId: string) =>
    api.delete<unknown, ApiResponse<void>>(`/admin/api-keys/${apiKeyId}`),

  getApiKeySecret: (apiKeyId: string) =>
    api.get<unknown, ApiResponse<{ secretKey: string }>>(`/admin/api-keys/${apiKeyId}/secret`),
}

export default api