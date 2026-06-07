import { useState, useEffect } from 'react'
import { Card, Table, Tabs, Button, Input, Space, Tag, message } from 'antd'
import { SearchOutlined, StarOutlined, BarChartOutlined, RiseOutlined, FallOutlined } from '@ant-design/icons'
import { useMarketStore } from '../stores'
import { useNavigate } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'

const { TabPane } = Tabs

interface MarketItem {
  symbol: string
  name: string
  price: string
  changeRatio: string
  volume: string
  turnover: string
}

export default function MarketList() {
  const navigate = useNavigate()
  const { setSelectedSymbol, watchlist, addToWatchlist, removeFromWatchlist } = useMarketStore()
  const [searchText, setSearchText] = useState('')
  const [activeTab, setActiveTab] = useState('all')
  const [marketData, setMarketData] = useState<MarketItem[]>([])
  const [filterType, setFilterType] = useState<'all' | 'gainers' | 'losers'>('all')

  // Mock market data
  useEffect(() => {
    setMarketData([
      { symbol: '600000', name: '浦发银行', price: '10.50', changeRatio: '+2.50%', volume: '1,234,567', turnover: '12.3亿' },
      { symbol: '600519', name: '贵州茅台', price: '1687.00', changeRatio: '+1.23%', volume: '456,789', turnover: '76.5亿' },
      { symbol: '600036', name: '招商银行', price: '32.45', changeRatio: '+0.85%', volume: '2,345,678', turnover: '76.2亿' },
      { symbol: '601318', name: '中国平安', price: '45.67', changeRatio: '-0.32%', volume: '1,567,890', turnover: '71.2亿' },
      { symbol: '000858', name: '五粮液', price: '156.78', changeRatio: '+9.98%', volume: '987,654', turnover: '15.5亿' },
      { symbol: '002415', name: '海康威视', price: '28.56', changeRatio: '-9.87%', volume: '654,321', turnover: '1.87亿' },
      { symbol: '300750', name: '宁德时代', price: '178.90', changeRatio: '-8.56%', volume: '321,654', turnover: '5.76亿' },
      { symbol: '000333', name: '美的集团', price: '67.89', changeRatio: '+6.78%', volume: '789,012', turnover: '5.34亿' },
      { symbol: 'IF2406', name: '沪深300期货', price: '3850.0', changeRatio: '+0.45%', volume: '123,456', turnover: '47.6亿' },
      { symbol: 'IC2406', name: '中证500期货', price: '5234.0', changeRatio: '+0.32%', volume: '45,678', turnover: '23.9亿' },
    ])
  }, [])

  const handleSymbolClick = (symbol: string) => {
    setSelectedSymbol(symbol)
    navigate(`/trading?symbol=${symbol}`)
  }

  const handleAddToWatchlist = (symbol: string) => {
    addToWatchlist(symbol)
    message.success(`已添加 ${symbol} 到自选`)
  }

  const handleRemoveFromWatchlist = (symbol: string) => {
    removeFromWatchlist(symbol)
    message.success(`已移除 ${symbol} 从自选`)
  }

  const getFilteredData = () => {
    let data = marketData

    // Filter by search text
    if (searchText) {
      data = data.filter(item =>
        item.symbol.includes(searchText) || item.name.includes(searchText)
      )
    }

    // Filter by type
    if (filterType === 'gainers') {
      data = data.filter(item => parseFloat(item.changeRatio) > 0)
    } else if (filterType === 'losers') {
      data = data.filter(item => parseFloat(item.changeRatio) < 0)
    }

    // Filter by watchlist
    if (activeTab === 'watchlist') {
      data = data.filter(item => watchlist.includes(item.symbol))
    }

    return data
  }

  const columns: ColumnsType<MarketItem> = [
    {
      title: '代码',
      dataIndex: 'symbol',
      width: 100,
      render: (symbol: string) => (
        <a onClick={() => handleSymbolClick(symbol)} style={{ color: '#22d3ee' }}>
          {symbol}
        </a>
      ),
    },
    {
      title: '名称',
      dataIndex: 'name',
      width: 120,
      render: (name: string) => <span style={{ color: '#fff' }}>{name}</span>,
    },
    {
      title: '最新价',
      dataIndex: 'price',
      width: 100,
      render: (price: string, record) => (
        <span style={{
          color: parseFloat(record.changeRatio) >= 0 ? '#34d399' : '#fb7185',
          fontWeight: 600,
        }}>
          {price}
        </span>
      ),
    },
    {
      title: '涨跌幅',
      dataIndex: 'changeRatio',
      width: 100,
      render: (change: string) => (
        <Tag color={parseFloat(change) >= 0 ? '#34d399' : '#fb7185'}>
          {change}
        </Tag>
      ),
      sorter: (a, b) => parseFloat(a.changeRatio) - parseFloat(b.changeRatio),
    },
    {
      title: '成交额',
      dataIndex: 'turnover',
      width: 100,
      render: (turnover: string) => <span style={{ color: '#a78bfa' }}>{turnover}</span>,
      sorter: (a, b) => parseFloat(a.turnover.replace('亿', '')) - parseFloat(b.turnover.replace('亿', '')),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, record) => (
        watchlist.includes(record.symbol) ? (
          <Button
            size="small"
            icon={<StarOutlined />}
            style={{ color: '#fbbf24', borderColor: '#fbbf24' }}
            onClick={() => handleRemoveFromWatchlist(record.symbol)}
          >
            已选
          </Button>
        ) : (
          <Button
            size="small"
            icon={<StarOutlined />}
            onClick={() => handleAddToWatchlist(record.symbol)}
          >
            加自选
          </Button>
        )
      ),
    },
  ]

  return (
    <Card
      style={{
        height: '100%',
        background: 'rgba(8, 51, 68, 0.4)',
        borderColor: '#22d3ee',
      }}
      bodyStyle={{ padding: 12 }}
    >
      {/* Search */}
      <Input
        placeholder="搜索代码/名称"
        prefix={<SearchOutlined />}
        value={searchText}
        onChange={e => setSearchText(e.target.value)}
        style={{ marginBottom: 12 }}
      />

      {/* Filter Tabs */}
      <Space style={{ marginBottom: 12 }}>
        <Button
          size="small"
          type={filterType === 'all' ? 'primary' : 'default'}
          onClick={() => setFilterType('all')}
        >
          全部
        </Button>
        <Button
          size="small"
          icon={<RiseOutlined />}
          type={filterType === 'gainers' ? 'primary' : 'default'}
          style={filterType === 'gainers' ? { background: '#34d399', borderColor: '#34d399' } : {}}
          onClick={() => setFilterType('gainers')}
        >
          涨幅
        </Button>
        <Button
          size="small"
          icon={<FallOutlined />}
          type={filterType === 'losers' ? 'primary' : 'default'}
          style={filterType === 'losers' ? { background: '#fb7185', borderColor: '#fb7185' } : {}}
          onClick={() => setFilterType('losers')}
        >
          跌幅
        </Button>
      </Space>

      {/* Watchlist Tab */}
      <Tabs
        activeKey={activeTab}
        onChange={key => setActiveTab(key)}
        size="small"
        style={{ marginBottom: 8 }}
      >
        <TabPane tab="市场" key="all" />
        <TabPane tab={`自选(${watchlist.length})`} key="watchlist" />
      </Tabs>

      {/* Table */}
      <Table
        columns={columns}
        dataSource={getFilteredData()}
        size="small"
        pagination={false}
        rowKey="symbol"
        scroll={{ y: 400 }}
        onRow={(record) => ({
          onClick: () => handleSymbolClick(record.symbol),
          style: { cursor: 'pointer' },
        })}
      />

      {/* Volume Ranking */}
      <div style={{ marginTop: 16 }}>
        <div style={{ color: '#a78bfa', fontSize: 12, marginBottom: 8 }}>
          <BarChartOutlined /> 成交额榜
        </div>
        {marketData
          .sort((a, b) => parseFloat(b.turnover.replace('亿', '')) - parseFloat(a.turnover.replace('亿', '')))
          .slice(0, 5)
          .map((item, idx) => (
            <div
              key={item.symbol}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                padding: '4px 8px',
                background: idx === 0 ? 'rgba(167, 139, 250, 0.1)' : 'transparent',
                borderRadius: 4,
                cursor: 'pointer',
              }}
              onClick={() => handleSymbolClick(item.symbol)}
            >
              <span style={{ color: '#94a3b8', fontSize: 11 }}>{item.symbol} {item.name}</span>
              <span style={{ color: '#a78bfa', fontSize: 11 }}>{item.turnover}</span>
            </div>
          ))}
      </div>
    </Card>
  )
}