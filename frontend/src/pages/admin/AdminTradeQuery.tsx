import { Card, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'

interface TradeItem {
  tradeId: string
  symbol: string
  side: string
  price: number
  quantity: number
  amount: number
  tradeTime: string
}

const mockTrades: TradeItem[] = [
  { tradeId: 'T001', symbol: '600000', side: 'BUY', price: 10.00, quantity: 50, amount: 500.00, tradeTime: '10:25:30' },
  { tradeId: 'T002', symbol: '600001', side: 'SELL', price: 9.50, quantity: 100, amount: 950.00, tradeTime: '10:26:00' },
]

export default function AdminTradeQuery() {
  const columns: ColumnsType<TradeItem> = [
    { title: '成交ID', dataIndex: 'tradeId', key: 'tradeId' },
    { title: '股票', dataIndex: 'symbol', key: 'symbol' },
    { title: '方向', dataIndex: 'side', key: 'side', render: (s: string) => <Tag color={s === 'BUY' ? '#34d399' : '#fb7185'}>{s === 'BUY' ? '买入' : '卖出'}</Tag> },
    { title: '成交价', dataIndex: 'price', key: 'price', render: (v: number) => v.toFixed(2) },
    { title: '数量', dataIndex: 'quantity', key: 'quantity' },
    { title: '金额', dataIndex: 'amount', key: 'amount', render: (v: number) => v.toFixed(2) },
    { title: '时间', dataIndex: 'tradeTime', key: 'tradeTime' },
  ]

  return (
    <Card
      title={<span style={{ color: '#fff' }}>成交查询</span>}
      style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fbbf24' }}
    >
      <Table columns={columns} dataSource={mockTrades} rowKey="tradeId" pagination={false} />
    </Card>
  )
}
