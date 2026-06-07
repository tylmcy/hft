import { Card, Table } from 'antd'

function History() {
  const columns = [
    { title: '成交时间', dataIndex: 'tradeTime', key: 'tradeTime' },
    { title: '股票代码', dataIndex: 'symbol', key: 'symbol' },
    { title: '方向', dataIndex: 'side', key: 'side' },
    { title: '成交价', dataIndex: 'price', key: 'price' },
    { title: '成交量', dataIndex: 'quantity', key: 'quantity' },
    { title: '成交金额', dataIndex: 'amount', key: 'amount' },
  ]

  return (
    <div>
      <h2 style={{ marginBottom: '24px' }}>历史成交</h2>
      <Card>
        <Table columns={columns} dataSource={[]} rowKey="tradeId" />
      </Card>
    </div>
  )
}

export default History