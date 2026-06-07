import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface User {
  userId: string
  username: string
  accountId: string
  role: 'ADMIN' | 'TRADER'
}

interface AuthState {
  token: string | null
  user: User | null
  isLoggedIn: boolean
  login: (token: string, user: User) => void
  logout: () => void
  updateToken: (token: string) => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      isLoggedIn: false,
      login: (token, user) => set({ token, user, isLoggedIn: true }),
      logout: () => set({ token: null, user: null, isLoggedIn: false }),
      updateToken: (token) => set({ token }),
    }),
    {
      name: 'auth-storage',
    }
  )
)