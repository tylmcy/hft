import { useEffect, useState } from 'react'
import { Card, Row, Col, Typography, Table, Tag, Button, Empty, Space, message } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAuthStore, useMarketStore } from '../stores'
import { StarFilled, DeleteOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'

const { Title, Text } = Typography

interface WatchlistItem {
  symbol: string
  name: string
  type: 'STOCK' | 'FUTURE' | 'OPTION'
  price: number
  changeRatio: number
  volume: number
  turnover: number
  addedAt: string
}

export default function Watchlist() {
  const navigate = useNavigate()
  const { isLoggedIn } = useAuthStore()
  const { setSelectedSymbol, watchlist, removeFromWatchlist } = useMarketStore()
  const [watchlistData, setWatchlistData] = useState<WatchlistItem[]>([])

  useEffect(() => {
    if (!isLoggedIn) {
      navigate('/login')
    }
  }, [isLoggedIn, navigate])

  useEffect(() => {
    // Mock watchlist data
    const mockData: WatchlistItem[] = [
      { symbol: '600000', name: '浦发银行', type: 'STOCK', price: 10.50, changeRatio: 2.50, volume: 1234567, turnover: 12345678, addedAt: '2026-05-20' },
      { symbol: '600519', name: '贵州茅台', type: 'STOCK', price: 1687.00, changeRatio: 1.23, volume: 456789, turnover: 76543210, addedAt: '2026-05-19' },
      { symbol: 'IF2406', name: '沪深300期货', type: 'FUTURE', price: 3850.0, changeRatio: 0.45, volume: 123456, turnover: 476000000, addedAt: '2026-05-18' },
      { symbol: '10003720', name: '50ETF购6月2500', type: 'OPTION', price: 0.0856, changeRatio: 12.34, volume: 12345, turnover: 856000, addedAt: '2026-05-21' },
    ]
    setWatchlistData(mockData)
  }, [watchlist])

  const handleSymbolClick = (symbol: string) => {
    setSelectedSymbol(symbol)
    navigate(`/trading?symbol=${symbol}`)
  }

  const handleRemove = (symbol: string) => {
    removeFromWatchlist(symbol)
    message.success(`已移除 ${symbol} 从自选`)
    setWatchlistData(watchlistData.filter(item => item.symbol !== symbol))
  }

  const columns: ColumnsType<WatchlistItem> = [
    {
      title: '代码',
      dataIndex: 'symbol',
      width: 100,
      render: (s: string) => (
        <a onClick={() => handleSymbolClick(s)} style={{ color: '#22d3ee' }}>{s}</a>
      ),
    },
    {
      title: '名称',
      dataIndex: 'name',
      width: 150,
      render: (n: string) => <span style={{ color: '#fff' }}>{n}</span>,
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 80,
      render: (t: string) => {
        const colors: Record<string, string> = { STOCK: '#22d3ee', FUTURE: '#a78bfa', OPTION: '#fbbf24' }
        const labels: Record<string, string> = { STOCK: '股票', FUTURE: '期货', OPTION: '期权' }
        return <Tag color={colors[t]}>{labels[t]}</Tag>
      },
    },
    {
      title: '最新价',
      dataIndex: 'price',
      width: 100,
      render: (p: number, record) => (
        <span style={{ color: record.changeRatio >= 0 ? '#34d399' : '#fb7185', fontWeight: 600 }}>
          {record.type === 'OPTION' ? p.toFixed(4) : p.toFixed(2)}
        </span>
      ),
    },
    {
      title: '涨跌幅',
      dataIndex: 'changeRatio',
      width: 100,
      render: (r: number) => (
        <Tag color={r >= 0 ? '#34d399' : '#fb7185'}>
          {r >= 0 ? '+' : ''}{r.toFixed(2)}%
        </Tag>
      ),
      sorter: (a, b) => a.changeRatio - b.changeRatio,
    },
    {
      title: '成交量',
      dataIndex: 'volume',
      width: 100,
      render: (v: number) => <span style={{ color: '#a78bfa' }}>{v.toLocaleString()}</span>,
    },
    {
      title: '添加时间',
      dataIndex: 'addedAt',
      width: 100,
      render: (d: string) => <span style={{ color: '#64748b' }}>{d}</span>,
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, record) => (
        <Button
          size="small"
          danger
          icon={<DeleteOutlined />}
          onClick={() => handleRemove(record.symbol)}
        >
          移除
        </Button>
      ),
    },
  ]

  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ color: '#fbbf24', marginBottom: 16 }}>
        自选股 (Watchlist.tsx)
      </Title>

      <Card style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#fbbf24' }}>
        {/* Header Actions */}
        <Space style={{ marginBottom: 16 }}>
          <Button type="primary" icon={<StarFilled />}>
            共 {watchlistData.length} 个
          </Button>
          <Button onClick={() => message.info('批量管理功能开发中')}>
            批量管理
          </Button>
        </Space>

        {watchlistData.length === 0 ? (
          <Empty
            description={<span style={{ color: '#94a3b8' }}>暂无自选股，请在行情页面添加</span>}
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          />
        ) : (
          <Table
            columns={columns}
            dataSource={watchlistData}
            size="small"
            pagination={false}
            rowKey="symbol"
            scroll={{ y: 400 }}
          />
        )}
      </Card>

      {/* Quick Stats */}
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={6}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#34d399' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>上涨数量</Text>
            <br />
            <Text style={{ color: '#34d399', fontSize: 18, fontWeight: 600 }}>
              {watchlistData.filter(w => w.changeRatio > 0).length}
            </Text>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fb7185' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>下跌数量</Text>
            <br />
            <Text style={{ color: '#fb7185', fontSize: 18, fontWeight: 600 }}>
              {watchlistData.filter(w => w.changeRatio < 0).length}
            </Text>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#22d3ee' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>股票</Text>
            <br />
            <Text style={{ color: '#22d3ee', fontSize: 18, fontWeight: 600 }}>
              {watchlistData.filter(w => w.type === 'STOCK').length}
            </Text>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#a78bfa' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>期货/期权</Text>
            <br />
            <Text style={{ color: '#a78bfa', fontSize: 18, fontWeight: 600 }}>
              {watchlistData.filter(w => w.type !== 'STOCK').length}
            </Text>
          </Card>
        </Col>
      </Row>
    </div>
  )
}