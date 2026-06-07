import { useEffect, useRef } from 'react'
import { Card, Typography } from 'antd'
import { useMarketStore } from '../stores'

const { Text } = Typography

interface Trade {
  time: string
  price: number
  quantity: number
  side: 'BUY' | 'SELL'
}

export default function TradeDetails() {
  const { selectedSymbol } = useMarketStore()
  const containerRef = useRef<HTMLDivElement>(null)
  const tradesRef = useRef<Trade[]>([])

  // Generate mock trades
  useEffect(() => {
    const interval = setInterval(() => {
      const now = new Date()
      const time = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`
      const basePrice = 10.0 + Math.random() * 0.1 - 0.05
      const trade: Trade = {
        time,
        price: Math.round(basePrice * 100) / 100,
        quantity: Math.floor(Math.random() * 1000) + 100,
        side: Math.random() > 0.5 ? 'BUY' : 'SELL',
      }
      tradesRef.current = [trade, ...tradesRef.current].slice(0, 50)

      // Force re-render
      if (containerRef.current) {
        renderTrades()
      }
    }, 500)

    return () => clearInterval(interval)
  }, [selectedSymbol])

  const renderTrades = () => {
    if (!containerRef.current) return

    containerRef.current.innerHTML = tradesRef.current.map(trade => `
      <div style="display: flex; justify-content: space-between; padding: 4px 8px; font-size: 11px; font-family: 'JetBrains Mono', monospace;">
        <span style="color: #94a3b8;">${trade.time}</span>
        <span style="color: ${trade.side === 'BUY' ? '#34d399' : '#fb7185'}; font-weight: 600;">${trade.price.toFixed(2)}</span>
        <span style="color: #fff;">${trade.quantity}</span>
        <span style="color: ${trade.side === 'BUY' ? '#34d399' : '#fb7185'};">${trade.side === 'BUY' ? 'B' : 'S'}</span>
      </div>
    `).join('')
  }

  return (
    <Card
      size="small"
      style={{
        background: 'rgba(15, 23, 42, 0.8)',
        borderColor: '#1e293b',
      }}
      bodyStyle={{ padding: 8 }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
        <Text style={{ color: '#fff', fontSize: 12, fontWeight: 600 }}>成交明细 (Time & Sales)</Text>
        <Text style={{ color: '#22d3ee', fontSize: 10 }}>TradeDetails.tsx</Text>
      </div>

      {/* Header */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        padding: '4px 8px',
        fontSize: 10,
        color: '#64748b',
        background: 'rgba(30, 41, 59, 0.5)',
        borderRadius: 4,
        marginBottom: 4,
      }}>
        <span>时间</span>
        <span>价格</span>
        <span>数量</span>
        <span>方向</span>
      </div>

      {/* Trades container */}
      <div
        ref={containerRef}
        style={{
          height: 120,
          overflowY: 'auto',
          overflowX: 'hidden',
        }}
      />
    </Card>
  )
}