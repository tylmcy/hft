import { useState, useEffect } from 'react'
import { Card, Table, Input, Select, Button, Tag, Space } from 'antd'
import { SearchOutlined, ExportOutlined } from '@ant-design/icons'
import { adminApi } from '../../api/client'
import type { AccountAdminInfo } from '../../api/types'
import type { ColumnsType } from 'antd/es/table'

export default function AdminAccountList() {
  const [accounts, setAccounts] = useState<AccountAdminInfo[]>([])
  const [loading, setLoading] = useState(false)
  const [searchText, setSearchText] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('')

  useEffect(() => {
    loadAccounts()
  }, [statusFilter])

  const loadAccounts = async () => {
    setLoading(true)
    try {
      const result = await adminApi.getAccounts({ status: statusFilter || undefined })
      if (result.code === '0') {
        setAccounts(result.data.items)
      }
    } catch (error) {
      console.error('Failed to load accounts:', error)
    } finally {
      setLoading(false)
    }
  }

  const statusColor: Record<string, string> = {
    NORMAL: '#34d399',
    WARNING: '#fbbf24',
    FROZEN: '#fb7185',
  }

  const statusLabel: Record<string, string> = {
    NORMAL: '正常',
    WARNING: '预警',
    FROZEN: '冻结',
  }

  const columns: ColumnsType<AccountAdminInfo> = [
    { title: '账户ID', dataIndex: 'accountId', key: 'accountId', width: 100 },
    { title: '用户名', dataIndex: 'userId', key: 'userId', width: 100 },
    { title: '总资产', dataIndex: 'balance', key: 'balance', render: (v: string) => parseFloat(v).toLocaleString() },
    { title: '可用', dataIndex: 'available', key: 'available', render: (v: string) => parseFloat(v).toLocaleString() },
    { title: '冻结', dataIndex: 'frozen', key: 'frozen', render: (v: string) => parseFloat(v).toLocaleString() },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s: string) => <Tag color={statusColor[s] || '#64748b'}>{statusLabel[s] || s}</Tag>,
    },
    {
      title: '风险度',
      dataIndex: 'riskRatio',
      key: 'riskRatio',
      render: (v: number) => (
        <span style={{ color: v >= 80 ? '#fb7185' : v >= 50 ? '#fbbf24' : '#34d399' }}>
          {v}%
        </span>
      ),
    },
  ]

  const filtered = accounts.filter(a =>
    !searchText || a.accountId.includes(searchText) || a.userId.includes(searchText)
  )

  return (
    <div>
      <Card
        title={<span style={{ color: '#fff' }}>账户列表 (AccountList.tsx)</span>}
        style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fbbf24' }}
      >
        <Space style={{ marginBottom: 16 }}>
          <Input
            placeholder="搜索用户名/账户ID..."
            prefix={<SearchOutlined />}
            value={searchText}
            onChange={e => setSearchText(e.target.value)}
            style={{ width: 200, background: '#1e293b', borderColor: '#334155', color: '#fff' }}
          />
          <Select
            value={statusFilter}
            onChange={setStatusFilter}
            style={{ width: 120 }}
            options={[
              { value: '', label: '全部状态' },
              { value: 'NORMAL', label: '正常' },
              { value: 'WARNING', label: '预警' },
              { value: 'FROZEN', label: '冻结' },
            ]}
          />
          <Button icon={<ExportOutlined />} style={{ borderColor: '#fbbf24', color: '#fbbf24' }}>
            导出Excel
          </Button>
        </Space>

        <Table
          columns={columns}
          dataSource={filtered}
          loading={loading}
          rowKey="accountId"
          pagination={{ pageSize: 20 }}
        />
      </Card>
    </div>
  )
}
