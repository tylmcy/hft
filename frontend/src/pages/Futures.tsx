import { useEffect } from 'react'
import { Card, Row, Col, Typography, Table, Tag, Tabs } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAuthStore, useMarketStore } from '../stores'
import type { ColumnsType } from 'antd/es/table'

const { Title, Text } = Typography

interface Future {
  symbol: string
  name: string
  price: number
  change: number
  changeRatio: number
  volume: number
  openInterest: number
  marginRatio: number
  settlementDate: string
}

export default function Futures() {
  const navigate = useNavigate()
  const { isLoggedIn } = useAuthStore()
  const { setSelectedSymbol } = useMarketStore()

  useEffect(() => {
    if (!isLoggedIn) {
      navigate('/login')
    }
  }, [isLoggedIn, navigate])

  const futures: Future[] = [
    { symbol: 'IF2406', name: '沪深300', price: 3850, change: 17, changeRatio: 0.45, volume: 123456, openInterest: 123456, marginRatio: 12, settlementDate: '2026-06-21' },
    { symbol: 'IC2406', name: '中证500', price: 5234, change: 17, changeRatio: 0.32, volume: 45678, openInterest: 45678, marginRatio: 12, settlementDate: '2026-06-21' },
    { symbol: 'IH2406', name: '上证50', price: 2456, change: -3, changeRatio: -0.12, volume: 34567, openInterest: 34567, marginRatio: 12, settlementDate: '2026-06-21' },
    { symbol: 'IM2406', name: '中证1000', price: 5678, change: 38, changeRatio: 0.67, volume: 23456, openInterest: 23456, marginRatio: 12, settlementDate: '2026-06-21' },
    { symbol: 'TS2406', name: '2年期国债', price: 101.25, change: 0.02, changeRatio: 0.02, volume: 12345, openInterest: 12345, marginRatio: 1.5, settlementDate: '2026-06-14' },
    { symbol: 'TF2406', name: '5年期国债', price: 101.56, change: 0.03, changeRatio: 0.03, volume: 9876, openInterest: 9876, marginRatio: 2, settlementDate: '2026-06-14' },
    { symbol: 'T2406', name: '10年期国债', price: 102.34, change: 0.05, changeRatio: 0.05, volume: 8765, openInterest: 8765, marginRatio: 3, settlementDate: '2026-06-14' },
  ]

  const handleSymbolClick = (symbol: string) => {
    setSelectedSymbol(symbol)
    navigate(`/trading?symbol=${symbol}`)
  }

  const columns: ColumnsType<Future> = [
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
      width: 100,
      render: (n: string) => <span style={{ color: '#fff' }}>{n}</span>,
    },
    {
      title: '最新价',
      dataIndex: 'price',
      width: 100,
      render: (p: number, record) => (
        <span style={{ color: record.change >= 0 ? '#34d399' : '#fb7185', fontWeight: 600 }}>
          {p.toFixed(2)}
        </span>
      ),
    },
    {
      title: '涨跌',
      dataIndex: 'change',
      width: 80,
      render: (c: number) => (
        <span style={{ color: c >= 0 ? '#34d399' : '#fb7185' }}>
          {c >= 0 ? '+' : ''}{c}
        </span>
      ),
    },
    {
      title: '涨跌幅',
      dataIndex: 'changeRatio',
      width: 80,
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
      sorter: (a, b) => a.volume - b.volume,
    },
    {
      title: '持仓量',
      dataIndex: 'openInterest',
      width: 100,
      render: (o: number) => <span style={{ color: '#fbbf24' }}>{o.toLocaleString()}</span>,
    },
    {
      title: '保证金率',
      dataIndex: 'marginRatio',
      width: 80,
      render: (m: number) => <span style={{ color: '#94a3b8' }}>{m}%</span>,
    },
    {
      title: '交割日',
      dataIndex: 'settlementDate',
      width: 100,
      render: (d: string) => <span style={{ color: '#64748b' }}>{d}</span>,
    },
  ]

  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ color: '#a78bfa', marginBottom: 16 }}>
        期货行情 (Futures.tsx)
      </Title>

      <Tabs defaultActiveKey="CFFEX">
        <Tabs.TabPane tab="中金所" key="CFFEX">
          <Card style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#a78bfa' }}>
            <Table
              columns={columns}
              dataSource={futures.filter(f => ['IF', 'IC', 'IH', 'IM'].some(p => f.symbol.startsWith(p)))}
              size="small"
              pagination={false}
              rowKey="symbol"
              scroll={{ y: 400 }}
            />
          </Card>
        </Tabs.TabPane>
        <Tabs.TabPane tab="国债期货" key="Treasury">
          <Card style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#a78bfa' }}>
            <Table
              columns={columns}
              dataSource={futures.filter(f => ['TS', 'TF', 'T'].some(p => f.symbol.startsWith(p)))}
              size="small"
              pagination={false}
              rowKey="symbol"
            />
          </Card>
        </Tabs.TabPane>
      </Tabs>

      {/* Quick Stats */}
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={8}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#a78bfa' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>股指期货总持仓</Text>
            <br />
            <Text style={{ color: '#a78bfa', fontSize: 18, fontWeight: 600 }}>
              {futures.filter(f => ['IF', 'IC', 'IH', 'IM'].some(p => f.symbol.startsWith(p))).reduce((s, f) => s + f.openInterest, 0).toLocaleString()}
            </Text>
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fbbf24' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>今日成交量</Text>
            <br />
            <Text style={{ color: '#fbbf24', fontSize: 18, fontWeight: 600 }}>
              {futures.reduce((s, f) => s + f.volume, 0).toLocaleString()}
            </Text>
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#22d3ee' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>主力合约</Text>
            <br />
            <Text style={{ color: '#22d3ee', fontSize: 18, fontWeight: 600 }}>
              IF2406
            </Text>
          </Card>
        </Col>
      </Row>
    </div>
  )
}