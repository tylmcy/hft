import { useEffect } from 'react'
import { Card, Row, Col, Typography, Table, Tag, Tabs, Space, Button } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAuthStore, useMarketStore } from '../stores'
import type { ColumnsType } from 'antd/es/table'

const { Title, Text } = Typography

interface Option {
  symbol: string
  name: string
  underlying: string
  strikePrice: number
  optionType: 'CALL' | 'PUT'
  price: number
  change: number
  changeRatio: number
  volume: number
  openInterest: number
  impliedVol: number
  expiryDate: string
}

export default function Options() {
  const navigate = useNavigate()
  const { isLoggedIn } = useAuthStore()
  const { setSelectedSymbol } = useMarketStore()

  useEffect(() => {
    if (!isLoggedIn) {
      navigate('/login')
    }
  }, [isLoggedIn, navigate])

  const options: Option[] = [
    { symbol: '10003720', name: '50ETF购6月2500', underlying: '50ETF', strikePrice: 2.50, optionType: 'CALL', price: 0.0856, change: 0.0094, changeRatio: 12.34, volume: 12345, openInterest: 56789, impliedVol: 18.5, expiryDate: '2026-06-28' },
    { symbol: '10003721', name: '50ETF沽6月2500', underlying: '50ETF', strikePrice: 2.50, optionType: 'PUT', price: 0.0234, change: -0.0022, changeRatio: -8.56, volume: 8765, openInterest: 45678, impliedVol: 20.2, expiryDate: '2026-06-28' },
    { symbol: '10003722', name: '50ETF购6月2600', underlying: '50ETF', strikePrice: 2.60, optionType: 'CALL', price: 0.0423, change: 0.0023, changeRatio: 5.67, volume: 6543, openInterest: 34567, impliedVol: 17.8, expiryDate: '2026-06-28' },
    { symbol: '10003723', name: '50ETF沽6月2600', underlying: '50ETF', strikePrice: 2.60, optionType: 'PUT', price: 0.0567, change: -0.0019, changeRatio: -3.21, volume: 5432, openInterest: 23456, impliedVol: 19.5, expiryDate: '2026-06-28' },
    { symbol: '10003724', name: '50ETF购6月2700', underlying: '50ETF', strikePrice: 2.70, optionType: 'CALL', price: 0.0189, change: 0.0004, changeRatio: 2.34, volume: 4321, openInterest: 12345, impliedVol: 16.5, expiryDate: '2026-06-28' },
    { symbol: '10003725', name: '50ETF沽6月2700', underlying: '50ETF', strikePrice: 2.70, optionType: 'PUT', price: 0.0892, change: -0.0012, changeRatio: -1.32, volume: 3210, openInterest: 9876, impliedVol: 21.0, expiryDate: '2026-06-28' },
  ]

  const handleSymbolClick = (symbol: string) => {
    setSelectedSymbol(symbol)
    navigate(`/trading?symbol=${symbol}`)
  }

  const columns: ColumnsType<Option> = [
    {
      title: '代码',
      dataIndex: 'symbol',
      width: 120,
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
      dataIndex: 'optionType',
      width: 80,
      render: (t: string) => (
        <Tag color={t === 'CALL' ? '#34d399' : '#fb7185'}>
          {t === 'CALL' ? 'Call' : 'Put'}
        </Tag>
      ),
      filters: [
        { text: 'Call', value: 'CALL' },
        { text: 'Put', value: 'PUT' },
      ],
      onFilter: (value, record) => record.optionType === value,
    },
    {
      title: '行权价',
      dataIndex: 'strikePrice',
      width: 80,
      render: (p: number) => <span style={{ color: '#fbbf24' }}>{p.toFixed(2)}</span>,
    },
    {
      title: '最新价',
      dataIndex: 'price',
      width: 80,
      render: (p: number, record) => (
        <span style={{ color: record.change >= 0 ? '#34d399' : '#fb7185', fontWeight: 600 }}>
          {p.toFixed(4)}
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
      width: 80,
      render: (v: number) => <span style={{ color: '#a78bfa' }}>{v.toLocaleString()}</span>,
    },
    {
      title: '持仓量',
      dataIndex: 'openInterest',
      width: 80,
      render: (o: number) => <span style={{ color: '#fbbf24' }}>{o.toLocaleString()}</span>,
    },
    {
      title: '隐含波动率',
      dataIndex: 'impliedVol',
      width: 80,
      render: (v: number) => <span style={{ color: '#94a3b8' }}>{v}%</span>,
    },
    {
      title: '到期日',
      dataIndex: 'expiryDate',
      width: 100,
      render: (d: string) => <span style={{ color: '#64748b' }}>{d}</span>,
    },
  ]

  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ color: '#fbbf24', marginBottom: 16 }}>
        期权行情 (Options.tsx)
      </Title>

      <Tabs defaultActiveKey="50ETF">
        <Tabs.TabPane tab="50ETF期权" key="50ETF">
          <Card style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#fbbf24' }}>
            {/* Strike Price Filter */}
            <Space style={{ marginBottom: 16 }}>
              <Text style={{ color: '#94a3b8' }}>行权价:</Text>
              <Button size="small">2.50</Button>
              <Button size="small">2.60</Button>
              <Button size="small">2.70</Button>
              <Button size="small">全部</Button>
            </Space>

            <Table
              columns={columns}
              dataSource={options}
              size="small"
              pagination={false}
              rowKey="symbol"
              scroll={{ y: 400 }}
            />
          </Card>
        </Tabs.TabPane>
        <Tabs.TabPane tab="沪深300期权" key="HS300">
          <Card style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#fbbf24' }}>
            <div style={{ padding: 24, textAlign: 'center', color: '#94a3b8' }}>
              沪深300期权合约列表（开发中）
            </div>
          </Card>
        </Tabs.TabPane>
        <Tabs.TabPane tab="中证1000期权" key="ZZ1000">
          <Card style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#fbbf24' }}>
            <div style={{ padding: 24, textAlign: 'center', color: '#94a3b8' }}>
              中证1000期权合约列表（开发中）
            </div>
          </Card>
        </Tabs.TabPane>
      </Tabs>

      {/* Quick Stats */}
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={6}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#34d399' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>Call总持仓</Text>
            <br />
            <Text style={{ color: '#34d399', fontSize: 16, fontWeight: 600 }}>
              {options.filter(o => o.optionType === 'CALL').reduce((s, o) => s + o.openInterest, 0).toLocaleString()}
            </Text>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fb7185' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>Put总持仓</Text>
            <br />
            <Text style={{ color: '#fb7185', fontSize: 16, fontWeight: 600 }}>
              {options.filter(o => o.optionType === 'PUT').reduce((s, o) => s + o.openInterest, 0).toLocaleString()}
            </Text>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fbbf24' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>隐含波动率均值</Text>
            <br />
            <Text style={{ color: '#fbbf24', fontSize: 16, fontWeight: 600 }}>
              {(options.reduce((s, o) => s + o.impliedVol, 0) / options.length).toFixed(1)}%
            </Text>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#22d3ee' }}>
            <Text style={{ color: '#94a3b8', fontSize: 12 }}>PCR比率</Text>
            <br />
            <Text style={{ color: '#22d3ee', fontSize: 16, fontWeight: 600 }}>
              {(options.filter(o => o.optionType === 'PUT').reduce((s, o) => s + o.openInterest, 0) / options.filter(o => o.optionType === 'CALL').reduce((s, o) => s + o.openInterest, 0)).toFixed(2)}
            </Text>
          </Card>
        </Col>
      </Row>
    </div>
  )
}