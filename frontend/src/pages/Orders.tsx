import { Card, Table } from 'antd'

function Orders() {
  const columns = [
    { title: '订单号', dataIndex: 'orderId', key: 'orderId' },
    { title: '股票代码', dataIndex: 'symbol', key: 'symbol' },
    { title: '方向', dataIndex: 'side', key: 'side' },
    { title: '价格', dataIndex: 'price', key: 'price' },
    { title: '数量', dataIndex: 'quantity', key: 'quantity' },
    { title: '已成交', dataIndex: 'filledQuantity', key: 'filledQuantity' },
    { title: '状态', dataIndex: 'status', key: 'status' },
    { title: '操作', key: 'action' },
  ]

  return (
    <div>
      <h2 style={{ marginBottom: '24px' }}>订单管理</h2>
      <Card>
        <Table columns={columns} dataSource={[]} rowKey="orderId" />
      </Card>
    </div>
  )
}

export default Orders