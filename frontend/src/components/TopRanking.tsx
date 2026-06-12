import { useEffect, useState, useCallback } from 'react'
import { Card, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import { marketApi } from '../api/client'
import type { SinaQuote } from '../api/types'

interface RankingItem {
  key: string
  rank: number
  name: string
  price: string
  changePercent: string
  volume?: string
}

interface TopRankingProps {
  type: 'gainers' | 'losers' | 'volume'
  limit?: number
  onSymbolClick?: (symbol: string) => void
}

const TYPE_CONFIG = {
  gainers: {
    title: '上涨Top5',
    borderColor: '#34d399',
    bgColor: 'rgba(6, 78, 59, 0.3)',
  },
  losers: {
    title: '下跌Top5',
    borderColor: '#fb7185',
    bgColor: 'rgba(120, 53, 15, 0.3)',
  },
  volume: {
    title: '成交量Top5',
    borderColor: '#a78bfa',
    bgColor: 'rgba(8, 51, 68, 0.4)',
  },
}

export function TopRanking({ type, limit = 5, onSymbolClick: _onSymbolClick }: TopRankingProps) {
  const [data, setData] = useState<RankingItem[]>([])
  const [loading, setLoading] = useState(true)
  const config = TYPE_CONFIG[type]

  const fetchRankings = useCallback(async () => {
    try {
      const res = await marketApi.getRankings()
      if (res.code === '0' && res.data) {
        const source = type === 'gainers' ? res.data.gainers
          : type === 'losers' ? res.data.losers
          : res.data.topVolume

        setData(source.slice(0, limit).map((item: SinaQuote, idx: number) => ({
          key: String(idx),
          rank: idx + 1,
          name: item.name || '--',
          price: item.price || '--',
          changePercent: item.changePercent || '0.00%',
          volume: item.volume ? formatVolume(item.volume) : undefined,
        })))
      }
    } catch (e) {
      console.warn('Failed to fetch rankings:', e)
    } finally {
      setLoading(false)
    }
  }, [type, limit])

  useEffect(() => {
    fetchRankings()
    const timer = setInterval(fetchRankings, 15000)
    return () => clearInterval(timer)
  }, [fetchRankings])

  const columns: ColumnsType<RankingItem> = [
    {
      title: '排名',
      dataIndex: 'rank',
      width: 40,
      render: (rank: number) => (
        <span style={{
          color: rank === 1 ? '#fbbf24' : '#94a3b8',
          fontWeight: rank === 1 ? 600 : 400,
        }}>
          {rank}
        </span>
      ),
    },
    {
      title: '名称',
      dataIndex: 'name',
      width: 90,
      render: (name: string) => <span style={{ color: '#94a3b8' }}>{name}</span>,
    },
    {
      title: '最新价',
      dataIndex: 'price',
      width: 80,
      render: (price: string) => <span style={{ color: '#fff' }}>{price}</span>,
    },
    ...(type === 'volume'
      ? [{
          title: '成交额',
          dataIndex: 'volume',
          width: 80,
          render: (volume: string) => (
            <span style={{ color: '#a78bfa' }}>{volume}</span>
          ),
        }]
      : []),
    {
      title: '涨跌幅',
      dataIndex: 'changePercent',
      width: 80,
      render: (change: string) => {
        const isUp = change.startsWith('+')
        const color = isUp ? '#34d399' : '#fb7185'
        return (
          <span style={{ color, fontWeight: 600 }}>
            {isUp && <ArrowUpOutlined style={{ marginRight: 4 }} />}
            {!isUp && <ArrowDownOutlined style={{ marginRight: 4 }} />}
            {change}
          </span>
        )
      },
    },
  ]

  return (
    <Card
      title={
        <span style={{ color: '#fff', fontWeight: 600 }}>
          {config.title}
        </span>
      }
      size="small"
      loading={loading}
      style={{
        background: config.bgColor,
        borderColor: config.borderColor,
      }}
      styles={{
        header: {
          borderBottom: `1px solid ${config.borderColor}`,
        },
      }}
    >
      <Table
        columns={columns}
        dataSource={data}
        pagination={false}
        size="small"
        showHeader
      />
    </Card>
  )
}

function formatVolume(v: string): string {
  const n = parseFloat(v)
  if (isNaN(n)) return v
  if (n >= 1e8) return (n / 1e8).toFixed(1) + '亿'
  if (n >= 1e4) return (n / 1e4).toFixed(1) + '万'
  return v
}

export function TopGainers(props: Omit<TopRankingProps, 'type'>) {
  return <TopRanking type="gainers" {...props} />
}

export function TopLosers(props: Omit<TopRankingProps, 'type'>) {
  return <TopRanking type="losers" {...props} />
}

export function TopVolume(props: Omit<TopRankingProps, 'type'>) {
  return <TopRanking type="volume" {...props} />
}

export default TopRanking
