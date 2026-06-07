import { useEffect, useState } from 'react'
import { Card, Row, Col, Statistic, Table, Tag, Button, Space, Typography, message } from 'antd'
import {
  UserOutlined,
  ShoppingOutlined,
  DollarOutlined,
  ThunderboltOutlined,
  AlertOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'

const { Title } = Typography

interface SystemStats {
  onlineUsers: number
  todayOrders: number
  fillRate: number
  todayTurnover: number
  tps: number
  p99Latency: number
  pendingDeposits: number
  tradingPhase: string
}

interface DepositRequest {
  id: string
  time: string
  username: string
  amount: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  bank: string
}

interface AccountInfo {
  accountId: string
  username: string
  balance: number
  positionValue: number
  available: number
  profitLoss: number
  riskRatio: number
  status: 'NORMAL' | 'WARNING' | 'FROZEN'
}

export default function AdminDashboard() {
  const navigate = useNavigate()
  const [stats] = useState<SystemStats>({
    onlineUsers: 128,
    todayOrders: 15678,
    fillRate: 87.5,
    todayTurnover: 2.3,
    tps: 85432,
    p99Latency: 8.2,
    pendingDeposits: 5,
    tradingPhase: '连续竞价',
  })

  const [deposits, setDeposits] = useState<DepositRequest[]>([])
  const [accounts, setAccounts] = useState<AccountInfo[]>([])

  useEffect(() => {
    // Mock deposit requests
    setDeposits([
      { id: 'D001', time: '10:25:30', username: 'trader001', amount: 100000, status: 'PENDING', bank: '工商银行' },
      { id: 'D002', time: '10:20:15', username: 'trader002', amount: 500000, status: 'PENDING', bank: '建设银行' },
      { id: 'D003', time: '10:15:00', username: 'trader003', amount: 50000, status: 'PENDING', bank: '招商银行' },
      { id: 'D004', time: '09:45:00', username: 'trader004', amount: 200000, status: 'APPROVED', bank: '农业银行' },
      { id: 'D005', time: '09:30:00', username: 'trader005', amount: 30000, status: 'REJECTED', bank: '中国银行' },
    ])

    // Mock accounts
    setAccounts([
      { accountId: '100001', username: 'trader001', balance: 1234567, positionValue: 789012, available: 567890, profitLoss: 12345, riskRatio: 45.6, status: 'NORMAL' },
      { accountId: '100002', username: 'trader002', balance: 5678901, positionValue: 4567890, available: 1111011, profitLoss: 234567, riskRatio: 80.2, status: 'NORMAL' },
      { accountId: '100003', username: 'trader003', balance: 234567, positionValue: 180000, available: 54678, profitLoss: -45678, riskRatio: 85, status: 'WARNING' },
      { accountId: '100004', username: 'trader004', balance: 56789, positionValue: 50000, available: 6789, profitLoss: -123456, riskRatio: 95, status: 'FROZEN' },
    ])
  }, [])

  const handleApproveDeposit = (id: string) => {
    message.success(`入金请求 ${id} 已批准`)
    setDeposits(deposits.map(d => d.id === id ? { ...d, status: 'APPROVED' as DepositRequest['status'] } : d))
  }

  const handleRejectDeposit = (id: string) => {
    message.success(`入金请求 ${id} 已拒绝`)
    setDeposits(deposits.map(d => d.id === id ? { ...d, status: 'REJECTED' as DepositRequest['status'] } : d))
  }

  const depositColumns: ColumnsType<DepositRequest> = [
    {
      title: '时间',
      dataIndex: 'time',
      width: 80,
    },
    {
      title: '用户',
      dataIndex: 'username',
      width: 100,
    },
    {
      title: '金额',
      dataIndex: 'amount',
      width: 120,
      render: (a: number) => <span style={{ color: '#34d399', fontWeight: 600 }}>+{a.toLocaleString()}</span>,
    },
    {
      title: '银行',
      dataIndex: 'bank',
      width: 100,
      render: (b: string) => <span style={{ color: '#94a3b8' }}>{b}</span>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (s: string) => {
        const colors: Record<string, string> = { PENDING: '#fb7185', APPROVED: '#34d399', REJECTED: '#fb7185' }
        const labels: Record<string, string> = { PENDING: '待处理', APPROVED: '已入账', REJECTED: '已拒绝' }
        return <Tag color={colors[s]}>{labels[s]}</Tag>
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) => {
        if (record.status === 'PENDING') {
          return (
            <Space>
              <Button size="small" type="primary" onClick={() => handleApproveDeposit(record.id)}>
                批准
              </Button>
              <Button size="small" danger onClick={() => handleRejectDeposit(record.id)}>
                拒绝
              </Button>
            </Space>
          )
        }
        return null
      },
    },
  ]

  const accountColumns: ColumnsType<AccountInfo> = [
    {
      title: '账户ID',
      dataIndex: 'accountId',
      width: 80,
    },
    {
      title: '用户名',
      dataIndex: 'username',
      width: 100,
    },
    {
      title: '总资产',
      dataIndex: 'balance',
      width: 120,
      render: (b: number) => <span style={{ color: '#fff', fontWeight: 600 }}>{b.toLocaleString()}</span>,
    },
    {
      title: '持仓市值',
      dataIndex: 'positionValue',
      width: 100,
      render: (v: number) => <span style={{ color: '#94a3b8' }}>{v.toLocaleString()}</span>,
    },
    {
      title: '盈亏',
      dataIndex: 'profitLoss',
      width: 100,
      render: (p: number) => (
        <span style={{ color: p >= 0 ? '#34d399' : '#fb7185' }}>
          {p >= 0 ? '+' : ''}{p.toLocaleString()}
        </span>
      ),
    },
    {
      title: '风险度',
      dataIndex: 'riskRatio',
      width: 80,
      render: (r: number) => (
        <span style={{ color: r > 80 ? '#fb7185' : r > 60 ? '#fbbf24' : '#34d399' }}>
          {r}%
        </span>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (s: string) => {
        const colors: Record<string, string> = { NORMAL: '#34d399', WARNING: '#fbbf24', FROZEN: '#fb7185' }
        const labels: Record<string, string> = { NORMAL: '正常', WARNING: '预警', FROZEN: '冻结' }
        return <Tag color={colors[s]}>{labels[s]}</Tag>
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) => (
        <Button size="small" onClick={() => navigate(`/admin/account/${record.accountId}`)}>
          查看
        </Button>
      ),
    },
  ]

  return (
    <div style={{ padding: 24 }}>
      {/* System Overview */}
      <Title level={4} style={{ color: '#fbbf24', marginBottom: 16 }}>
        系统概览 (SystemOverview.tsx)
      </Title>

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={12} md={4}>
          <Card style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#34d399' }}>
            <Statistic
              title={<span style={{ color: '#94a3b8' }}>在线用户数</span>}
              value={stats.onlineUsers}
              prefix={<UserOutlined style={{ color: '#34d399' }} />}
              valueStyle={{ color: '#fff' }}
              suffix={<span style={{ color: '#34d399', fontSize: 12 }}>↑ 12</span>}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#22d3ee' }}>
            <Statistic
              title={<span style={{ color: '#94a3b8' }}>今日订单数</span>}
              value={stats.todayOrders}
              prefix={<ShoppingOutlined style={{ color: '#22d3ee' }} />}
              valueStyle={{ color: '#fff' }}
              suffix={<span style={{ color: '#22d3ee', fontSize: 12 }}>{stats.fillRate}%成交</span>}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#a78bfa' }}>
            <Statistic
              title={<span style={{ color: '#94a3b8' }}>今日成交额</span>}
              value={stats.todayTurnover}
              prefix={<DollarOutlined style={{ color: '#a78bfa' }} />}
              valueStyle={{ color: '#fff' }}
              suffix={<span style={{ color: '#a78bfa', fontSize: 12 }}>亿</span>}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fbbf24' }}>
            <Statistic
              title={<span style={{ color: '#94a3b8' }}>系统TPS</span>}
              value={stats.tps}
              prefix={<ThunderboltOutlined style={{ color: '#fbbf24' }} />}
              valueStyle={{ color: '#fff' }}
              suffix={<span style={{ color: '#fbbf24', fontSize: 12 }}>P99: {stats.p99Latency}ms</span>}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fb7185' }}>
            <Statistic
              title={<span style={{ color: '#94a3b8' }}>待处理入金</span>}
              value={stats.pendingDeposits}
              prefix={<AlertOutlined style={{ color: '#fb7185' }} />}
              valueStyle={{ color: '#fb7185' }}
              suffix={<span style={{ color: '#fb7185', fontSize: 12 }}>需及时处理</span>}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={4}>
          <Card style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fb923c' }}>
            <Statistic
              title={<span style={{ color: '#94a3b8' }}>交易阶段</span>}
              value={stats.tradingPhase}
              prefix={<ClockCircleOutlined style={{ color: '#34d399' }} />}
              valueStyle={{ color: '#34d399' }}
            />
          </Card>
        </Col>
      </Row>

      {/* Pending Deposits */}
      <Card
        title={<span style={{ color: '#fbbf24' }}>待处理入金请求 (PendingDeposits.tsx)</span>}
        style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#fbbf24', marginBottom: 24 }}
      >
        <Table
          columns={depositColumns}
          dataSource={deposits.filter(d => d.status === 'PENDING')}
          size="small"
          pagination={false}
          rowKey="id"
        />

        <Title level={5} style={{ color: '#34d399', marginTop: 16, marginBottom: 8 }}>
          已处理（今日）
        </Title>
        <Table
          columns={depositColumns}
          dataSource={deposits.filter(d => d.status !== 'PENDING')}
          size="small"
          pagination={false}
          rowKey="id"
        />
      </Card>

      {/* Account List */}
      <Card
        title={<span style={{ color: '#fbbf24' }}>账户列表 (AccountList.tsx)</span>}
        extra={
          <Space>
            <Button onClick={() => message.info('导出功能开发中')}>导出Excel</Button>
          </Space>
        }
        style={{ background: 'rgba(8, 51, 68, 0.4)', borderColor: '#fbbf24' }}
      >
        <Table
          columns={accountColumns}
          dataSource={accounts}
          size="small"
          pagination={{ pageSize: 10 }}
          rowKey="accountId"
        />
      </Card>
    </div>
  )
}