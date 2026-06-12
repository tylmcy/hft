import { Card, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'

interface OrderItem {
  orderId: string
  symbol: string
  side: string
  orderType: string
  price: number
  quantity: number
  filledQuantity: number
  status: string
  createTime: string
}

const mockOrders: OrderItem[] = [
  { orderId: '100001', symbol: '600000', side: 'BUY', orderType: 'LIMIT', price: 10.00, quantity: 100, filledQuantity: 50, status: 'PARTIAL_FILLED', createTime: '10:25:30' },
  { orderId: '100002', symbol: '600000', side: 'SELL', orderType: 'LIMIT', price: 10.01, quantity: 100, filledQuantity: 0, status: 'NEW', createTime: '10:26:00' },
  { orderId: '100003', symbol: '600001', side: 'BUY', orderType: 'MARKET', price: 9.50, quantity: 200, filledQuantity: 200, status: 'FILLED', createTime: '10:20:00' },
]

export default function AdminOrderMonitor() {
  const statusColor: Record<string, string> = {
    NEW: '#22d3ee', PARTIAL_FILLED: '#fbbf24', FILLED: '#34d399', CANCELLED: '#64748b', REJECTED: '#fb7185',
  }

  const columns: ColumnsType<OrderItem> = [
    { title: '订单号', dataIndex: 'orderId', key: 'orderId' },
    { title: '股票', dataIndex: 'symbol', key: 'symbol' },
    { title: '方向', dataIndex: 'side', key: 'side', render: (s: string) => <Tag color={s === 'BUY' ? '#34d399' : '#fb7185'}>{s === 'BUY' ? '买入' : '卖出'}</Tag> },
    { title: '类型', dataIndex: 'orderType', key: 'orderType' },
    { title: '价格', dataIndex: 'price', key: 'price', render: (v: number) => v.toFixed(2) },
    { title: '数量', dataIndex: 'quantity', key: 'quantity' },
    { title: '已成交', dataIndex: 'filledQuantity', key: 'filledQuantity' },
    { title: '状态', dataIndex: 'status', key: 'status', render: (s: string) => <Tag color={statusColor[s]}>{s}</Tag> },
    { title: '时间', dataIndex: 'createTime', key: 'createTime' },
  ]

  return (
    <Card
      title={<span style={{ color: '#fff' }}>订单监控</span>}
      style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fbbf24' }}
    >
      <Table columns={columns} dataSource={mockOrders} rowKey="orderId" pagination={false} />
    </Card>
  )
}
