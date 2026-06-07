import { Card } from 'antd'
import {
  ArrowUpOutlined,
  ArrowDownOutlined,
  MinusOutlined,
} from '@ant-design/icons'
import { useMarketStore } from '../stores'

interface IndexBarProps {
  onSelectSymbol?: (symbol: string) => void
}

// Mock index data (in real app, would come from WebSocket)
const DEFAULT_INDICES = [
  { symbol: '000001', name: '上证指数', type: 'INDEX' },
  { symbol: '399001', name: '深证成指', type: 'INDEX' },
  { symbol: '399006', name: '创业板指', type: 'INDEX' },
  { symbol: '000688', name: '科创50', type: 'INDEX' },
  { symbol: '000300', name: '沪深300', type: 'INDEX' },
  { symbol: 'IF0', name: 'IF主力', type: 'FUTURE' },
]

interface IndexData {
  symbol: string
  name: string
  price: string
  change: string
  changePercent: string
  type: string
}

export function IndexBar({ onSelectSymbol }: IndexBarProps) {
  const { marketSnapshots } = useMarketStore()

  // Calculate index data from snapshots
  const indices: IndexData[] = DEFAULT_INDICES.map((idx) => {
    const snapshot = marketSnapshots[idx.symbol]
    const currentPrice = snapshot?.bidPrices[0] || '0'

    // Mock change calculation (would need prev close in real app)
    const change = '0'
    const changePercent = '0.00%'

    return {
      symbol: idx.symbol,
      name: idx.name,
      price: currentPrice !== '0' ? currentPrice : '--',
      change,
      changePercent,
      type: idx.type,
    }
  })

  return (
    <div
      style={{
        display: 'flex',
        gap: 12,
        overflowX: 'auto',
        padding: '8px 0',
      }}
    >
      {indices.map((idx) => {
        const isUp = parseFloat(idx.changePercent) > 0
        const isDown = parseFloat(idx.changePercent) < 0
        const color = isUp ? '#34d399' : isDown ? '#fb7185' : '#fbbf24'

        return (
          <Card
            key={idx.symbol}
            size="small"
            style={{
              minWidth: 180,
              background: 'rgba(15, 23, 42, 0.8)',
              borderColor: isUp ? '#34d399' : isDown ? '#fb7185' : '#fbbf24',
              cursor: 'pointer',
            }}
            hoverable
            onClick={() => onSelectSymbol?.(idx.symbol)}
          >
            <div style={{ color: '#94a3b8', fontSize: 12 }}>{idx.name}</div>
            <div style={{ color: '#fff', fontSize: 16, fontWeight: 600, marginTop: 4 }}>
              {idx.price}
            </div>
            <div style={{ color, fontSize: 12, marginTop: 4 }}>
              {isUp && <ArrowUpOutlined />}{' '}
              {isDown && <ArrowDownOutlined />}{' '}
              {!isUp && !isDown && <MinusOutlined />}{' '}
              {idx.changePercent}
            </div>
          </Card>
        )
      })}
    </div>
  )
}

export default IndexBar