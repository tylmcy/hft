import { Card, Table, Tag, Space, DatePicker, Typography, Row, Col, message, Button, Select } from 'antd'
import { ExportOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'

const { Title, Text } = Typography
const { RangePicker } = DatePicker

interface Cashflow {
  id: string
  time: string
  accountId: string
  username: string
  type: 'DEPOSIT' | 'WITHDRAW' | 'BUY' | 'SELL' | 'COMMISSION' | 'STAMP_TAX' | 'MARGIN'
  amount: number
  balance: number
  relatedId: string
  remark: string
  operator: string
}

export default function CashflowManager() {
  const [cashflows, setCashflows] = useState<Cashflow[]>([])
  const [stats] = useState({
    todayDeposit: 850000,
    todayWithdraw: 120000,
    todayCommission: 3456.78,
    todayStampTax: 4321.00,
    todayNetInflow: 722222.22,
  })

  useEffect(() => {
    setCashflows([
      { id: 'CF001', time: '10:25:30', accountId: '100001', username: 'trader001', type: 'DEPOSIT', amount: 100000, balance: 1234567, relatedId: '-', remark: '银行转账 工商银行', operator: 'admin' },
      { id: 'CF002', time: '10:30:15', accountId: '100001', username: 'trader001', type: 'BUY', amount: -10000, balance: 1224567, relatedId: 'T001', remark: '买入 600000 1000股@10.00', operator: '系统' },
      { id: 'CF003', time: '10:30:15', accountId: '100001', username: 'trader001', type: 'COMMISSION', amount: -30, balance: 1224537, relatedId: 'T001', remark: '买入手续费 万分之三', operator: '系统' },
      { id: 'CF004', time: '10:35:22', accountId: '100001', username: 'trader001', type: 'SELL', amount: 15000, balance: 1239537, relatedId: 'T002', remark: '卖出 600000 1500股@10.00', operator: '系统' },
      { id: 'CF005', time: '10:35:22', accountId: '100001', username: 'trader001', type: 'STAMP_TAX', amount: -15, balance: 1239522, relatedId: 'T002', remark: '卖出印花税 千分之一', operator: '系统' },
      { id: 'CF006', time: '11:00:00', accountId: '100002', username: 'trader002', type: 'WITHDRAW', amount: -50000, balance: 5628901, relatedId: '-', remark: '银行转账 建设银行', operator: 'admin' },
      { id: 'CF007', time: '11:15:30', accountId: '100001', username: 'trader001', type: 'MARGIN', amount: -92400, balance: 1147122, relatedId: 'T003', remark: '开多 IF2406 2手 保证金12%', operator: '系统' },
    ])
  }, [])

  const typeColors: Record<string, string> = {
    DEPOSIT: '#34d399',
    WITHDRAW: '#fb7185',
    BUY: '#a78bfa',
    SELL: '#22d3ee',
    COMMISSION: '#fbbf24',
    STAMP_TAX: '#fb7185',
    MARGIN: '#a78bfa',
  }

  const typeLabels: Record<string, string> = {
    DEPOSIT: '入金',
    WITHDRAW: '出金',
    BUY: '买入',
    SELL: '卖出',
    COMMISSION: '手续费',
    STAMP_TAX: '印花税',
    MARGIN: '保证金',
  }

  const columns: ColumnsType<Cashflow> = [
    {
      title: '流水ID',
      dataIndex: 'id',
      width: 100,
      render: (s: string) => <span style={{ color: '#94a3b8' }}>{s}</span>,
    },
    {
      title: '时间',
      dataIndex: 'time',
      width: 80,
    },
    {
      title: '账户',
      dataIndex: 'accountId',
      width: 80,
    },
    {
      title: '用户名',
      dataIndex: 'username',
      width: 100,
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 80,
      render: (t: string) => <Tag color={typeColors[t]}>{typeLabels[t]}</Tag>,
    },
    {
      title: '金额',
      dataIndex: 'amount',
      width: 120,
      render: (a: number) => (
        <span style={{ color: a >= 0 ? '#34d399' : '#fb7185', fontWeight: 600 }}>
          {a >= 0 ? '+' : ''}{a.toLocaleString()}
        </span>
      ),
    },
    {
      title: '余额',
      dataIndex: 'balance',
      width: 120,
      render: (b: number) => <span style={{ color: '#fff' }}>{b.toLocaleString()}</span>,
    },
    {
      title: '关联ID',
      dataIndex: 'relatedId',
      width: 100,
      render: (s: string) => <span style={{ color: s !== '-' ? '#22d3ee' : '#64748b' }}>{s}</span>,
    },
    {
      title: '备注',
      dataIndex: 'remark',
      width: 200,
      render: (s: string) => <span style={{ color: '#94a3b8', fontSize: 11 }}>{s}</span>,
    },
    {
      title: '操作人',
      dataIndex: 'operator',
      width: 80,
      render: (s: string) => (
        <span style={{ color: s === 'admin' ? '#fbbf24' : '#64748b' }}>{s}</span>
      ),
    },
  ]

  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ color: '#fbbf24', marginBottom: 16 }}>
        资金流水管理 (CashflowManager.tsx)
      </Title>

      {/* Stats */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={12} md={4}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#34d399' }}>
            <div style={{ color: '#94a3b8', fontSize: 12 }}>今日入金</div>
            <div style={{ color: '#34d399', fontSize: 18, fontWeight: 600 }}>
              +{stats.todayDeposit.toLocaleString()}
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fb7185' }}>
            <div style={{ color: '#94a3b8', fontSize: 12 }}>今日出金</div>
            <div style={{ color: '#fb7185', fontSize: 18, fontWeight: 600 }}>
              -{stats.todayWithdraw.toLocaleString()}
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#a78bfa' }}>
            <div style={{ color: '#94a3b8', fontSize: 12 }}>今日手续费</div>
            <div style={{ color: '#a78bfa', fontSize: 18, fontWeight: 600 }}>
              -{stats.todayCommission.toLocaleString()}
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fbbf24' }}>
            <div style={{ color: '#94a3b8', fontSize: 12 }}>今日印花税</div>
            <div style={{ color: '#fbbf24', fontSize: 18, fontWeight: 600 }}>
              -{stats.todayStampTax.toLocaleString()}
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card size="small" style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#22d3ee' }}>
            <div style={{ color: '#94a3b8', fontSize: 12 }}>今日净流入</div>
            <div style={{ color: '#22d3ee', fontSize: 18, fontWeight: 600 }}>
              +{stats.todayNetInflow.toLocaleString()}
            </div>
          </Card>
        </Col>
      </Row>

      <Card style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#fbbf24' }}>
        {/* Filters */}
        <Space style={{ marginBottom: 16 }}>
          <input placeholder="搜索用户名/账户ID" style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid #334155', background: '#1e293b', color: '#fff' }} />
          <Select style={{ width: 120 }}>
            <Select.Option value="all">全部类型</Select.Option>
            <Select.Option value="DEPOSIT">入金</Select.Option>
            <Select.Option value="WITHDRAW">出金</Select.Option>
            <Select.Option value="BUY">买入</Select.Option>
            <Select.Option value="SELL">卖出</Select.Option>
            <Select.Option value="COMMISSION">手续费</Select.Option>
            <Select.Option value="STAMP_TAX">印花税</Select.Option>
          </Select>
          <RangePicker />
          <Button type="primary">查询</Button>
          <Button icon={<ExportOutlined />} onClick={() => message.info('导出功能开发中')}>导出Excel</Button>
        </Space>

        <Table
          columns={columns}
          dataSource={cashflows}
          size="small"
          pagination={{ pageSize: 20 }}
          rowKey="id"
          expandable={{
            expandedRowRender: (record) => (
              <div style={{ padding: 8 }}>
                <Text style={{ color: '#64748b', fontSize: 11 }}>
                  审批备注: {record.type === 'DEPOSIT' || record.type === 'WITHDRAW' ? '审核通过' : '-'}
                </Text>
              </div>
            ),
            rowExpandable: (record) => record.type === 'DEPOSIT' || record.type === 'WITHDRAW',
          }}
        />
      </Card>
    </div>
  )
}