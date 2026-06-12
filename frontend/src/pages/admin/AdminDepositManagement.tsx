import { useState, useEffect } from 'react'
import { Card, Table, Button, Tag, Space, message, Modal, Form, Input, Descriptions } from 'antd'
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons'
import { adminApi } from '../../api/client'
import type { DepositRecord } from '../../api/types'
import type { ColumnsType } from 'antd/es/table'

export default function AdminDepositManagement() {
  const [pendingDeposits, setPendingDeposits] = useState<DepositRecord[]>([])
  const [loading, setLoading] = useState(false)
  const [approvalVisible, setApprovalVisible] = useState(false)
  const [selectedDeposit, setSelectedDeposit] = useState<DepositRecord | null>(null)
  const [form] = Form.useForm()

  useEffect(() => {
    loadPending()
  }, [])

  const loadPending = async () => {
    setLoading(true)
    try {
      const result = await adminApi.getPendingDeposits()
      if (result.code === '0') {
        setPendingDeposits(result.data)
      }
    } catch (error) {
      console.error('Failed to load pending deposits:', error)
    } finally {
      setLoading(false)
    }
  }

  const handleApprove = async () => {
    if (!selectedDeposit) return
    try {
      const values = await form.validateFields()
      const result = await adminApi.approveDeposit({
        depositId: selectedDeposit.depositId,
        actualAmount: values.actualAmount,
        reason: values.reason,
      })
      if (result.code === '0') {
        message.success('入金审批成功')
        setApprovalVisible(false)
        loadPending()
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || '审批失败')
    }
  }

  const handleReject = async () => {
    if (!selectedDeposit) return
    try {
      const values = await form.validateFields()
      const result = await adminApi.rejectDeposit({
        depositId: selectedDeposit.depositId,
        reason: values.rejectReason || '审批拒绝',
      })
      if (result.code === '0') {
        message.success('已拒绝')
        setApprovalVisible(false)
        loadPending()
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || '操作失败')
    }
  }

  const columns: ColumnsType<DepositRecord> = [
    { title: '时间', dataIndex: 'processedAt', key: 'processedAt', render: (v: number) => new Date(v).toLocaleTimeString() },
    { title: '用户', dataIndex: 'userId', key: 'userId' },
    { title: '金额', dataIndex: 'amount', key: 'amount', render: (v: string) => <span style={{ color: '#34d399' }}>+{parseFloat(v).toLocaleString()}</span> },
    { title: '备注', dataIndex: 'reason', key: 'reason', render: (v: string) => v || '-' },
    {
      title: '状态', dataIndex: 'status', key: 'status',
      render: (s: string) => <Tag color={s === 'PENDING' ? '#fb7185' : s === 'APPROVED' ? '#34d399' : '#64748b'}>{s}</Tag>,
    },
    {
      title: '操作', key: 'action',
      render: (_: any, record: DepositRecord) => (
        record.status === 'PENDING' ? (
          <Button
            size="small"
            onClick={() => { setSelectedDeposit(record); setApprovalVisible(true); form.resetFields() }}
          >
            处理
          </Button>
        ) : null
      ),
    },
  ]

  return (
    <div>
      <Card
        title={<span style={{ color: '#fff' }}>入金管理 (PendingDeposits.tsx)</span>}
        style={{ background: 'rgba(15, 23, 42, 0.8)', borderColor: '#fbbf24' }}
      >
        <Table columns={columns} dataSource={pendingDeposits} loading={loading} rowKey="depositId" pagination={false} />
      </Card>

      <Modal
        title="入金处理"
        open={approvalVisible}
        onCancel={() => setApprovalVisible(false)}
        footer={null}
        width={700}
      >
        {selectedDeposit && (
          <>
            <Descriptions column={1} size="small" bordered style={{ marginBottom: 24 }}>
              <Descriptions.Item label="用户">{selectedDeposit.userId}</Descriptions.Item>
              <Descriptions.Item label="申请金额">
                <span style={{ color: '#34d399', fontWeight: 700 }}>+{parseFloat(selectedDeposit.amount).toLocaleString()}</span>
              </Descriptions.Item>
              <Descriptions.Item label="备注">{selectedDeposit.reason || '-'}</Descriptions.Item>
            </Descriptions>

            <Form form={form} layout="vertical">
              <Form.Item name="actualAmount" label="实际入金金额" initialValue={selectedDeposit.amount}
                rules={[{ required: true, message: '请输入实际入金金额' }]}>
                <Input type="number" suffix="元" style={{ background: '#1e293b', borderColor: '#fbbf24', color: '#fff' }} />
              </Form.Item>
              <Form.Item name="reason" label="审批备注">
                <Input.TextArea rows={2} placeholder="输入审批备注（可选）" style={{ background: '#1e293b', borderColor: '#334155', color: '#fff' }} />
              </Form.Item>
              <Form.Item name="rejectReason" label="拒绝原因（拒绝时填写）">
                <Input placeholder="拒绝原因" style={{ background: '#1e293b', borderColor: '#334155', color: '#fff' }} />
              </Form.Item>
              <Form.Item>
                <Space>
                  <Button type="primary" icon={<CheckCircleOutlined />} onClick={handleApprove}
                    style={{ background: '#34d399', borderColor: '#34d399' }}>批准入金</Button>
                  <Button danger icon={<CloseCircleOutlined />} onClick={handleReject}>拒绝申请</Button>
                  <Button onClick={() => setApprovalVisible(false)}>取消</Button>
                </Space>
              </Form.Item>
            </Form>
          </>
        )}
      </Modal>
    </div>
  )
}
