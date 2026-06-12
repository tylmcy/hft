import { useState, useEffect } from 'react'
import { Row, Col, message } from 'antd'
import { useMarketStore, useAuthStore } from '../stores'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { orderApi } from '../api/client'
import MarketList from '../components/MarketList'
import TradingViewChart from '../components/TradingViewChart'
import OrderBook from '../components/OrderBook'
import OrderPanel from '../components/OrderPanel'
import TradeDetails from '../components/TradeDetails'
import AccountPanel from '../components/AccountPanel'
import OrderTable from '../components/OrderTable'

export default function Dashboard() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { isLoggedIn } = useAuthStore()

  const { selectedSymbol, setSelectedSymbol } = useMarketStore()
  const [chartType, setChartType] = useState<'kline' | 'depth'>('kline')

  // Get symbol from URL
  useEffect(() => {
    const symbol = searchParams.get('symbol')
    if (symbol) {
      setSelectedSymbol(symbol)
    }
  }, [searchParams, setSelectedSymbol])

  // Redirect to login if not authenticated
  useEffect(() => {
    if (!isLoggedIn) {
      navigate('/login')
    }
  }, [isLoggedIn, navigate])

  // Keyboard shortcuts
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // F1 = Quick Buy
      if (e.key === 'F1') {
        e.preventDefault()
        const symbol = selectedSymbol || '600000'
        const idempotencyKey = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
        orderApi.submit({ symbol, side: 'BUY', orderType: 'MARKET', price: '0', quantity: '100', idempotencyKey })
          .then(res => {
            if (res.code === '0') message.success(`买入成功: ${res.data.orderId}`)
          })
      }
      // F2 = Quick Sell
      if (e.key === 'F2') {
        e.preventDefault()
        const symbol = selectedSymbol || '600000'
        const idempotencyKey = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
        orderApi.submit({ symbol, side: 'SELL', orderType: 'MARKET', price: '0', quantity: '100', idempotencyKey })
          .then(res => {
            if (res.code === '0') message.success(`卖出成功: ${res.data.orderId}`)
          })
      }
      // Escape = clear/close
      if (e.key === 'Escape') {
        // handled by antd modals
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [selectedSymbol])

  return (
    <div style={{ padding: 0, height: 'calc(100vh - 56px)', overflow: 'hidden' }}>
      <Row gutter={16} style={{ height: '100%' }}>
        {/* Left: Market List */}
        <Col xs={24} sm={24} md={4} style={{ height: '100%', overflow: 'auto' }}>
          <MarketList />
        </Col>

        {/* Center: Chart + Order Panel */}
        <Col xs={24} sm={24} md={12} style={{ height: '100%', overflow: 'auto', paddingRight: 8 }}>
          {/* Chart Type Tabs */}
          <div style={{
            display: 'flex',
            gap: 8,
            marginBottom: 8,
          }}>
            <div
              onClick={() => setChartType('kline')}
              style={{
                padding: '4px 12px',
                background: chartType === 'kline' ? 'rgba(34, 211, 238, 0.2)' : 'transparent',
                border: `1px solid ${chartType === 'kline' ? '#22d3ee' : '#334155'}`,
                borderRadius: 4,
                color: chartType === 'kline' ? '#22d3ee' : '#94a3b8',
                cursor: 'pointer',
              }}
            >
              K线
            </div>
            <div
              onClick={() => setChartType('depth')}
              style={{
                padding: '4px 12px',
                background: chartType === 'depth' ? 'rgba(34, 211, 238, 0.2)' : 'transparent',
                border: `1px solid ${chartType === 'depth' ? '#22d3ee' : '#334155'}`,
                borderRadius: 4,
                color: chartType === 'depth' ? '#22d3ee' : '#94a3b8',
                cursor: 'pointer',
              }}
            >
              深度图
            </div>
          </div>

          {/* K线图 */}
          <div style={{ marginBottom: 8 }}>
            <TradingViewChart symbol={selectedSymbol || '600000'} />
          </div>

          {/* Trade Details */}
          <div style={{ marginBottom: 8 }}>
            <TradeDetails />
          </div>

          {/* Order Panel */}
          <OrderPanel symbol={selectedSymbol || '600000'} />
        </Col>

        {/* Right: Order Book + Account Panel */}
        <Col xs={24} sm={24} md={6} style={{ height: '100%', overflow: 'auto' }}>
          {/* Order Book */}
          <div style={{ marginBottom: 8 }}>
            <OrderBook
              symbol={selectedSymbol || '600000'}
              onPriceClick={(price) => {
                // Pass price to OrderPanel by dispatching to the global state
                // For now we just set it in the URL params or a shared store
                console.log('Price clicked:', price)
              }}
            />
          </div>

          {/* Account Panel */}
          <AccountPanel />
        </Col>
      </Row>

      {/* Bottom: Order Table (below the main layout) */}
      <div style={{
        position: 'absolute',
        bottom: 0,
        left: 0,
        right: 0,
        height: 250,
        background: '#020617',
        padding: 16,
        borderTop: '1px solid #22d3ee',
      }}>
        <OrderTable />
      </div>
    </div>
  )
}