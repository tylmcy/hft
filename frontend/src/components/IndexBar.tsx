import { useEffect, useState } from 'react'
import { Card } from 'antd'
import {
  ArrowUpOutlined,
  ArrowDownOutlined,
  MinusOutlined,
} from '@ant-design/icons'
import { marketApi } from '../api/client'
import type { IndexQuote } from '../api/types'

interface IndexBarProps {
  onSelectSymbol?: (symbol: string) => void
}

// 默认指数列表
const DEFAULT_INDICES = [
  { symbol: '000001', name: '上证指数' },
  { symbol: '399001', name: '深证成指' },
  { symbol: '399006', name: '创业板指' },
  { symbol: '000688', name: '科创50' },
  { symbol: '000300', name: '沪深300' },
]

export function IndexBar({ onSelectSymbol }: IndexBarProps) {
  const [indices, setIndices] = useState<IndexQuote[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const fetchIndices = async () => {
      try {
        const res = await marketApi.getIndices()
        if (res.code === '0' && res.data) {
          setIndices(res.data)
        }
      } catch (e) {
        console.warn('Failed to fetch indices:', e)
      } finally {
        setLoading(false)
      }
    }

    fetchIndices()
    // 每10秒刷新一次
    const timer = setInterval(fetchIndices, 10000)
    return () => clearInterval(timer)
  }, [])

  // 如果没有数据，用默认名称显示占位符
  const displayData = indices.length > 0 ? indices : DEFAULT_INDICES.map(idx => ({
    symbol: idx.symbol,
    name: idx.name,
    price: '--',
    prevClose: '0',
    open: '0',
    high: '0',
    low: '0',
    volume: '0',
    amount: '0',
    date: '',
    time: '',
  } as IndexQuote))

  return (
    <div
      style={{
        display: 'flex',
        gap: 12,
        overflowX: 'auto',
        padding: '8px 0',
      }}
    >
      {displayData.map((idx) => {
        const price = parseFloat(idx.price)
        const prevClose = parseFloat(idx.prevClose)
        const change = prevClose > 0 ? price - prevClose : 0
        const changePercent = prevClose > 0 ? (change / prevClose * 100) : 0
        const isUp = change > 0
        const isDown = change < 0
        const color = isUp ? '#34d399' : isDown ? '#fb7185' : '#fbbf24'

        return (
          <Card
            key={idx.symbol}
            size="small"
            loading={loading}
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
              {idx.price !== '--' ? parseFloat(idx.price).toFixed(2) : '--'}
            </div>
            <div style={{ color, fontSize: 12, marginTop: 4 }}>
              {isUp && <ArrowUpOutlined />}{' '}
              {isDown && <ArrowDownOutlined />}{' '}
              {!isUp && !isDown && <MinusOutlined />}{' '}
              {prevClose > 0 ? `${changePercent >= 0 ? '+' : ''}${changePercent.toFixed(2)}%` : '--'}
            </div>
          </Card>
        )
      })}
    </div>
  )
}

export default IndexBar
