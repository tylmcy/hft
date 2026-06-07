import { create } from 'zustand'
import { MarketSnapshotData, KlineData, KlineInterval } from '../websocket/types'

interface Symbol {
  symbol: string
  name: string
  type: 'STOCK' | 'FUTURE' | 'OPTION'
  prevClose: string
  limitUpRatio: number
  limitDownRatio: number
  lotSize: string
  minPriceIncrement: string
}

interface MarketState {
  // Symbol list
  symbols: Symbol[]
  currentSymbol: string | null
  selectedSymbol: string | null
  loading: boolean

  // Market snapshots (real-time quotes)
  marketSnapshots: Record<string, MarketSnapshotData>

  // K-line data cache
  klines: Record<string, Record<KlineInterval, KlineData[]>>

  // Subscriptions tracking
  subscribedSymbols: Set<string>

  // Watchlist
  watchlist: string[]

  // Actions
  setSymbols: (symbols: Symbol[]) => void
  setCurrentSymbol: (symbol: string | null) => void
  setSelectedSymbol: (symbol: string | null) => void
  setLoading: (loading: boolean) => void
  updateMarketSnapshot: (symbol: string, data: MarketSnapshotData) => void
  appendKline: (symbol: string, interval: KlineInterval, data: KlineData) => void
  setKlines: (symbol: string, interval: KlineInterval, data: KlineData[]) => void
  addSubscription: (symbol: string) => void
  removeSubscription: (symbol: string) => void
  clearSubscriptions: () => void
  addToWatchlist: (symbol: string) => void
  removeFromWatchlist: (symbol: string) => void
}

export const useMarketStore = create<MarketState>((set) => ({
  symbols: [],
  currentSymbol: null,
  selectedSymbol: null,
  loading: false,
  marketSnapshots: {},
  klines: {},
  subscribedSymbols: new Set<string>(),
  watchlist: [],

  setSymbols: (symbols) => set({ symbols }),

  setCurrentSymbol: (symbol) => set({ currentSymbol: symbol }),

  setSelectedSymbol: (symbol) => set({ selectedSymbol: symbol }),

  setLoading: (loading) => set({ loading }),

  updateMarketSnapshot: (symbol, data) =>
    set((state) => ({
      marketSnapshots: {
        ...state.marketSnapshots,
        [symbol]: data,
      },
    })),

  appendKline: (symbol, interval, data) =>
    set((state) => {
      const symbolKlines = state.klines[symbol] || {}
      const intervalKlines = symbolKlines[interval] || []
      return {
        klines: {
          ...state.klines,
          [symbol]: {
            ...symbolKlines,
            [interval]: [...intervalKlines, data],
          },
        },
      }
    }),

  setKlines: (symbol, interval, data) =>
    set((state) => {
      const symbolKlines = state.klines[symbol] || {}
      return {
        klines: {
          ...state.klines,
          [symbol]: {
            ...symbolKlines,
            [interval]: data,
          },
        },
      }
    }),

  addSubscription: (symbol) =>
    set((state) => ({
      subscribedSymbols: new Set([...state.subscribedSymbols, symbol]),
    })),

  removeSubscription: (symbol) =>
    set((state) => {
      const newSet = new Set(state.subscribedSymbols)
      newSet.delete(symbol)
      return { subscribedSymbols: newSet }
    }),

  clearSubscriptions: () => set({ subscribedSymbols: new Set() }),

  addToWatchlist: (symbol) =>
    set((state) => ({
      watchlist: state.watchlist.includes(symbol) ? state.watchlist : [...state.watchlist, symbol],
    })),

  removeFromWatchlist: (symbol) =>
    set((state) => ({
      watchlist: state.watchlist.filter((s) => s !== symbol),
    })),
}))
