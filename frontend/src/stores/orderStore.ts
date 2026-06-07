import { create } from 'zustand'

// Order types matching V12.md §3.1.3
type OrderSide = 'BUY' | 'SELL'
type OrderType = 'LIMIT' | 'MARKET' | 'FAK' | 'FOK'
type OrderStatus = 'PENDING' | 'NEW' | 'PARTIAL_FILLED' | 'FILLED' | 'CANCELLED' | 'REJECTED'

interface Order {
  orderId: string
  symbol: string
  side: OrderSide
  orderType: OrderType
  price: string
  quantity: string
  filledQuantity: string
  remainingQty: string
  status: OrderStatus
  accountId: string
  userId: string
  createTime: number
  updateTime: number
}

interface OrderState {
  // Active orders (PENDING, NEW, PARTIAL_FILLED)
  activeOrders: Order[]

  // Order history (FILLED, CANCELLED, REJECTED)
  orderHistory: Order[]

  // Current submitting order (for loading state)
  submitting: boolean

  // Selected order for detail view
  selectedOrder: Order | null

  // Actions
  setActiveOrders: (orders: Order[]) => void
  addActiveOrder: (order: Order) => void
  updateOrder: (orderId: string, updates: Partial<Order>) => void
  removeActiveOrder: (orderId: string) => void
  addToHistory: (order: Order) => void
  setOrderHistory: (orders: Order[]) => void
  setSubmitting: (submitting: boolean) => void
  setSelectedOrder: (order: Order | null) => void
  clearActiveOrders: () => void

  // Helper getters
  getOrderById: (orderId: string) => Order | undefined
}

export const useOrderStore = create<OrderState>((set, get) => ({
  activeOrders: [],
  orderHistory: [],
  submitting: false,
  selectedOrder: null,

  setActiveOrders: (orders) => set({ activeOrders: orders }),

  addActiveOrder: (order) =>
    set((state) => ({
      activeOrders: [...state.activeOrders, order],
    })),

  updateOrder: (orderId, updates) =>
    set((state) => ({
      activeOrders: state.activeOrders.map((o) =>
        o.orderId === orderId ? { ...o, ...updates } : o
      ),
      selectedOrder:
        state.selectedOrder?.orderId === orderId
          ? { ...state.selectedOrder, ...updates }
          : state.selectedOrder,
    })),

  removeActiveOrder: (orderId) =>
    set((state) => ({
      activeOrders: state.activeOrders.filter((o) => o.orderId !== orderId),
      selectedOrder:
        state.selectedOrder?.orderId === orderId ? null : state.selectedOrder,
    })),

  addToHistory: (order) =>
    set((state) => ({
      orderHistory: [order, ...state.orderHistory],
    })),

  setOrderHistory: (orders) => set({ orderHistory: orders }),

  setSubmitting: (submitting) => set({ submitting }),

  setSelectedOrder: (order) => set({ selectedOrder: order }),

  clearActiveOrders: () => set({ activeOrders: [] }),

  getOrderById: (orderId) => {
    const state = get()
    return (
      state.activeOrders.find((o) => o.orderId === orderId) ||
      state.orderHistory.find((o) => o.orderId === orderId)
    )
  },
}))