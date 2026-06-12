import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useAuthStore } from './stores/authStore'
import Login from './pages/Login'
import Overview from './pages/Overview'
import Dashboard from './pages/Dashboard'
import Futures from './pages/Futures'
import Options from './pages/Options'
import Watchlist from './pages/Watchlist'
import Orders from './pages/Orders'
import Positions from './pages/Positions'
import History from './pages/History'
import Profile from './pages/Profile'
import Layout from './components/Layout'
import AdminLayout from './components/AdminLayout'
import { AdminDashboard, InstrumentManager, CashflowManager, AdminAccountList, AdminDepositManagement, AdminOrderMonitor, AdminTradeQuery } from './pages/admin'
import TradingCalendar from './pages/admin/TradingCalendar'

function App() {
  const { isLoggedIn, user } = useAuthStore()

  if (!isLoggedIn) {
    return (
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </BrowserRouter>
    )
  }

  // Admin routes
  if (user?.role === 'ADMIN') {
    return (
      <BrowserRouter>
        <Routes>
          <Route path="/admin" element={<AdminLayout />}>
            <Route index element={<AdminDashboard />} />
            <Route path="accounts" element={<AdminAccountList />} />
            <Route path="deposits" element={<AdminDepositManagement />} />
            <Route path="orders" element={<AdminOrderMonitor />} />
            <Route path="trades" element={<AdminTradeQuery />} />
            <Route path="instruments" element={<InstrumentManager />} />
            <Route path="cashflow" element={<CashflowManager />} />
            <Route path="calendar" element={<TradingCalendar />} />
          </Route>
          {/* User routes for admin */}
          <Route path="/" element={<Layout />}>
            <Route index element={<Overview />} />
            <Route path="trading" element={<Dashboard />} />
            <Route path="futures" element={<Futures />} />
            <Route path="options" element={<Options />} />
            <Route path="watchlist" element={<Watchlist />} />
            <Route path="orders" element={<Orders />} />
            <Route path="positions" element={<Positions />} />
            <Route path="history" element={<History />} />
            <Route path="profile" element={<Profile />} />
          </Route>
          <Route path="*" element={<Navigate to="/admin" replace />} />
        </Routes>
      </BrowserRouter>
    )
  }

  // User routes
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Overview />} />
          <Route path="trading" element={<Dashboard />} />
          <Route path="futures" element={<Futures />} />
          <Route path="options" element={<Options />} />
          <Route path="watchlist" element={<Watchlist />} />
          <Route path="orders" element={<Orders />} />
          <Route path="positions" element={<Positions />} />
          <Route path="history" element={<History />} />
          <Route path="profile" element={<Profile />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App