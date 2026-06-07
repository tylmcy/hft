import { Card, Table, Tag, Space, Button, Popconfirm, message, Tabs, DatePicker, Select } from 'antd'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { DeleteOutlined, HistoryOutlined, DollarOutlined, BarChartOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'

const { TabPane } = Tabs
const { RangePicker } = DatePicker

interface Order {
  orderId: string
  symbol: string
  side: 'BUY' | 'SELL'
  orderType: 'LIMIT' | 'MARKET'
  price: number
  quantity: number
  filledQuantity: number
  status: 'PENDING' | 'PARTIAL_FILLED' | 'FILLED' | 'CANCELLED'
  createTime: string
}

interface Trade {
  tradeId: string
  orderId: string
  symbol: string
  side: 'BUY' | 'SELL'
  price: number
  quantity: number
  commission: number
  tradeTime: string
}

export default function OrderTable() {
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState('activeOrders')
  const [orders, setOrders] = useState<Order[]>([])
  const [trades, setTrades] = useState<Trade[]>([])

  useEffect(() => {
    // Mock orders
    setOrders([
      { orderId: '100001', symbol: '600000', side: 'BUY', orderType: 'LIMIT', price: 10.00, quantity: 100, filledQuantity: 50, status: 'PARTIAL_FILLED', createTime: '09:30:15' },
      { orderId: '100002', symbol: '600001', side: 'SELL', orderType: 'LIMIT', price: 5.50, quantity: 200, filledQuantity: 0, status: 'PENDING', createTime: '09:31:22' },
      { orderId: '100003', symbol: 'IF2406', side: 'BUY', orderType: 'LIMIT', price: 3850.0, quantity: 2, filledQuantity: 2, status: 'FILLED', createTime: '09:35:08' },
      { orderId: '100004', symbol: '600519', side: 'BUY', orderType: 'MARKET', price: 0, quantity: 10, filledQuantity: 10, status: 'FILLED', createTime: '09:40:00' },
    ])

    // Mock trades
    setTrades([
      { tradeId: 'T001', orderId: '100001', symbol: '600000', side: 'BUY', price: 10.00, quantity: 50, commission: 1.50, tradeTime: '09:30:15' },
      { tradeId: 'T002', orderId: '100003', symbol: 'IF2406', side: 'BUY', price: 3850.0, quantity: 2, commission: 23.04, tradeTime: '09:35:08' },
      { tradeId: 'T003', orderId: '100004', symbol: '600519', side: 'BUY', price: 1687.00, quantity: 10, commission: 50.61, tradeTime: '09:40:00' },
    ])
  }, [])

  const handleCancelOrder = (orderId: string) => {
    message.success(`订单 ${orderId} 已撤单`)
    setOrders(orders.map(o => o.orderId === orderId ? { ...o, status: 'CANCELLED' as Order['status'] } : o))
  }

  const orderColumns: ColumnsType<Order> = [
    {
      title: '订单ID',
      dataIndex: 'orderId',
      width: 100,
      render: (id: string) => <span style={{ color: '#fff' }}>{id}</span>,
    },
    {
      title: '标的',
      dataIndex: 'symbol',
      width: 100,
      render: (s: string) => <a onClick={() => navigate(`/trading?symbol=${s}`)} style={{ color: '#22d3ee' }}>{s}</a>,
    },
    {
      title: '方向',
      dataIndex: 'side',
      width: 80,
      render: (side: string) => (
        <Tag color={side === 'BUY' ? '#34d399' : '#fb7185'}>
          {side === 'BUY' ? '买入' : '卖出'}
        </Tag>
      ),
    },
    {
      title: '类型',
      dataIndex: 'orderType',
      width: 80,
      render: (type: string) => <span style={{ color: '#94a3b8' }}>{type === 'LIMIT' ? '限价' : '市价'}</span>,
    },
    {
      title: '价格',
      dataIndex: 'price',
      width: 100,
      render: (p: number) => <span style={{ color: '#fff' }}>{p > 0 ? p.toFixed(2) : '-'}</span>,
    },
    {
      title: '数量',
      dataIndex: 'quantity',
      width: 80,
      render: (q: number) => <span style={{ color: '#fff' }}>{q}</span>,
    },
    {
      title: '已成交',
      dataIndex: 'filledQuantity',
      width: 80,
      render: (q: number) => <span style={{ color: '#22d3ee' }}>{q}</span>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (status: string) => {
        const colors: Record<string, string> = {
          PENDING: '#fbbf24',
          PARTIAL_FILLED: '#fbbf24',
          FILLED: '#34d399',
          CANCELLED: '#fb7185',
        }
        const labels: Record<string, string> = {
          PENDING: '待成交',
          PARTIAL_FILLED: '部分成交',
          FILLED: '全部成交',
          CANCELLED: '已撤单',
        }
        return <Tag color={colors[status]}>{labels[status]}</Tag>
      },
    },
    {
      title: '时间',
      dataIndex: 'createTime',
      width: 100,
      render: (t: string) => <span style={{ color: '#94a3b8' }}>{t}</span>,
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, record) => {
        if (record.status === 'PENDING' || record.status === 'PARTIAL_FILLED') {
          return (
            <Popconfirm title="确认撤单?" onConfirm={() => handleCancelOrder(record.orderId)}>
              <Button size="small" danger icon={<DeleteOutlined />}>
                撤单
              </Button>
            </Popconfirm>
          )
        }
        return <span style={{ color: '#64748b' }}>-</span>
      },
    },
  ]

  const tradeColumns: ColumnsType<Trade> = [
    {
      title: '成交ID',
      dataIndex: 'tradeId',
      width: 100,
      render: (id: string) => <span style={{ color: '#22d3ee' }}>{id}</span>,
    },
    {
      title: '订单ID',
      dataIndex: 'orderId',
      width: 100,
      render: (id: string) => <span style={{ color: '#94a3b8' }}>{id}</span>,
    },
    {
      title: '标的',
      dataIndex: 'symbol',
      width: 100,
      render: (s: string) => <a onClick={() => navigate(`/trading?symbol=${s}`)} style={{ color: '#22d3ee' }}>{s}</a>,
    },
    {
      title: '方向',
      dataIndex: 'side',
      width: 80,
      render: (side: string) => (
        <Tag color={side === 'BUY' ? '#34d399' : '#fb7185'}>
          {side === 'BUY' ? '买入' : '卖出'}
        </Tag>
      ),
    },
    {
      title: '成交价',
      dataIndex: 'price',
      width: 100,
      render: (p: number) => <span style={{ color: '#fff', fontWeight: 600 }}>{p.toFixed(2)}</span>,
    },
    {
      title: '数量',
      dataIndex: 'quantity',
      width: 80,
      render: (q: number) => <span style={{ color: '#fff' }}>{q}</span>,
    },
    {
      title: '手续费',
      dataIndex: 'commission',
      width: 100,
      render: (c: number) => <span style={{ color: '#fbbf24' }}>{c.toFixed(2)}</span>,
    },
    {
      title: '成交时间',
      dataIndex: 'tradeTime',
      width: 100,
      render: (t: string) => <span style={{ color: '#94a3b8' }}>{t}</span>,
    },
  ]

  return (
    <Card
      style={{
        background: 'rgba(8, 51, 68, 0.4)',
        borderColor: '#22d3ee',
      }}
    >
      <Tabs activeKey={activeTab} onChange={setActiveTab}>
        <TabPane
          tab={<span><HistoryOutlined /> 当前委托</span>}
          key="activeOrders"
        >
          <Table
            columns={orderColumns}
            dataSource={orders.filter(o => o.status !== 'CANCELLED' && o.status !== 'FILLED')}
            size="small"
            pagination={false}
            rowKey="orderId"
            scroll={{ y: 300 }}
          />
        </TabPane>

        <TabPane
          tab={<span><HistoryOutlined /> 历史订单</span>}
          key="historyOrders"
        >
          <Space style={{ marginBottom: 16 }}>
            <RangePicker />
            <Select defaultValue="all" style={{ width: 100 }}>
              <Select.Option value="all">全部状态</Select.Option>
              <Select.Option value="FILLED">已成交</Select.Option>
              <Select.Option value="CANCELLED">已撤单</Select.Option>
            </Select>
          </Space>
          <Table
            columns={orderColumns}
            dataSource={orders.filter(o => o.status === 'CANCELLED' || o.status === 'FILLED')}
            size="small"
            pagination={{ pageSize: 10 }}
            rowKey="orderId"
          />
        </TabPane>

        <TabPane
          tab={<span><DollarOutlined /> 成交记录</span>}
          key="trades"
        >
          <Table
            columns={tradeColumns}
            dataSource={trades}
            size="small"
            pagination={{ pageSize: 10 }}
            rowKey="tradeId"
          />
        </TabPane>

        <TabPane
          tab={<span><BarChartOutlined /> 交易统计</span>}
          key="stats"
        >
          <div style={{ padding: 24, textAlign: 'center', color: '#94a3b8' }}>
            今日成交: {trades.length} 笔 | 总手续费: {trades.reduce((sum, t) => sum + t.commission, 0).toFixed(2)} 元
          </div>
        </TabPane>
      </Tabs>
    </Card>
  )
}