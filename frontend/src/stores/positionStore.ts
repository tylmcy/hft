import { create } from 'zustand'

// Position types matching V12.md §3.1.2
interface Position {
  accountId: string
  symbol: string
  symbolCode: number
  quantity: string // positive = long, negative = short
  available: string
  frozen: string
  t1Locked: string
  avgPrice: string
  unrealizedPnl: string
  updateTime: number
}

interface PositionState {
  // Position list
  positions: Position[]
  loading: boolean

  // Current prices for unrealized PnL calculation
  currentPrices: Record<string, string>

  // Actions
  setPositions: (positions: Position[]) => void
  updatePosition: (symbol: string, updates: Partial<Position>) => void
  addPosition: (position: Position) => void
  removePosition: (symbol: string) => void
  setLoading: (loading: boolean) => void

  // Real-time price update for PnL calculation
  updateCurrentPrice: (symbol: string, price: string) => void

  // Calculate unrealized PnL based on current price
  updateOnPriceChange: (symbol: string, currentPrice: string) => void
}

export const usePositionStore = create<PositionState>((set, get) => ({
  positions: [],
  loading: false,
  currentPrices: {},

  setPositions: (positions) => set({ positions }),

  updatePosition: (symbol, updates) =>
    set((state) => ({
      positions: state.positions.map((p) =>
        p.symbol === symbol ? { ...p, ...updates } : p
      ),
    })),

  addPosition: (position) =>
    set((state) => ({
      positions: [...state.positions, position],
    })),

  removePosition: (symbol) =>
    set((state) => ({
      positions: state.positions.filter((p) => p.symbol !== symbol),
    })),

  setLoading: (loading) => set({ loading }),

  updateCurrentPrice: (symbol, price) =>
    set((state) => ({
      currentPrices: {
        ...state.currentPrices,
        [symbol]: price,
      },
    })),

  updateOnPriceChange: (symbol, currentPrice) => {
    set((state) => ({
      currentPrices: {
        ...state.currentPrices,
        [symbol]: currentPrice,
      },
    }))

    const position = get().positions.find((p) => p.symbol === symbol)
    if (position && position.quantity !== '0') {
      // unrealizedPnl = (currentPrice - avgPrice) × quantity
      const currentPriceNum = BigInt(currentPrice)
      const avgPriceNum = BigInt(position.avgPrice)
      const quantityNum = BigInt(position.quantity)

      const pnl = (currentPriceNum - avgPriceNum) * quantityNum

      set((state) => ({
        positions: state.positions.map((p) =>
          p.symbol === symbol ? { ...p, unrealizedPnl: pnl.toString() } : p
        ),
      }))
    }
  },
}))