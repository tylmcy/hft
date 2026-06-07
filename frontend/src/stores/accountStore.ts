import { create } from 'zustand'

// Account types matching V12.md §3.1.1
type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'LIQUIDATING' | 'NEGATIVE_BALANCE' | 'CLOSED'

interface Account {
  accountId: string
  userId: string
  balance: string
  available: string
  frozen: string
  marginFrozen: string
  orderFrozen: string
  unrealizedPnl: string
  status: AccountStatus
  updateTime: number
}

interface AccountState {
  // Account info
  account: Account | null
  loading: boolean

  // Actions
  setAccount: (account: Account | null) => void
  updateBalance: (balance: string, available: string, frozen: string) => void
  setLoading: (loading: boolean) => void

  // Helper getters
  getTotalAssets: () => string
}

export const useAccountStore = create<AccountState>((set, get) => ({
  account: null,
  loading: false,

  setAccount: (account) => set({ account }),

  updateBalance: (balance, available, frozen) =>
    set((state) => ({
      account: state.account
        ? {
            ...state.account,
            balance,
            available,
            frozen,
            updateTime: Date.now(),
          }
        : null,
    })),

  setLoading: (loading) => set({ loading }),

  getTotalAssets: () => {
    const { account } = get()
    if (!account) return '0'
    // Total assets = balance + unrealizedPnl
    const balance = BigInt(account.balance)
    const pnl = BigInt(account.unrealizedPnl || '0')
    return (balance + pnl).toString()
  },
}))