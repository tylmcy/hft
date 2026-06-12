import { Modal, Form, Input, Descriptions, Button, Space, message, Spin } from 'antd'
import { useState, useEffect } from 'react'
import { adminApi } from '../api/client'
import type { DepositRecord } from '../api/types'

interface DepositApprovalProps {
  visible: boolean
  deposit: DepositRecord | null
  onCancel: () => void
  onSuccess: () => void
}

export function DepositApproval({ visible, deposit, onCancel, onSuccess }: DepositApprovalProps) {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [accountInfo, setAccountInfo] = useState<{
    balance: string
    available: string
  } | null>(null)

  useEffect(() => {
    if (visible && deposit) {
      // Reset form
      form.setFieldsValue({
        actualAmount: deposit.amount,
        reason: '',
      })
      // Mock account info
      setAccountInfo({
        balance: '1,134,567.00',
        available: '567,890.00',
      })
    }
  }, [visible, deposit, form])

  const handleApprove = async () => {
    if (!deposit) return

    setLoading(true)
    try {
      const values = await form.validateFields()
      const result = await adminApi.approveDeposit({
        depositId: deposit.depositId,
        actualAmount: values.actualAmount,
        reason: values.reason,
      })

      if (result.code === '0') {
        message.success('入金审批成功')
        onSuccess()
      } else {
        message.error(result.message || '审批失败')
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || '审批失败')
    } finally {
      setLoading(false)
    }
  }

  const handleReject = async () => {
    if (!deposit) return

    setLoading(true)
    try {
      const values = await form.validateFields()
      const result = await adminApi.rejectDeposit({
        depositId: deposit.depositId,
        reason: values.rejectReason || '审批拒绝',
      })

      if (result.code === '0') {
        message.success('已拒绝入金请求')
        onSuccess()
      } else {
        message.error(result.message || '操作失败')
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || '操作失败')
    } finally {
      setLoading(false)
    }
  }

  if (!deposit) return null

  // Calculate post-deposit balance
  const newBalance = accountInfo
    ? (parseFloat(accountInfo.balance.replace(/,/g, '')) + parseFloat(deposit.amount)).toLocaleString()
    : '0'

  return (
    <Modal
      title="入金审批"
      open={visible}
      onCancel={onCancel}
      footer={null}
      width={700}
    >
      <Spin spinning={loading}>
        <div style={{ display: 'flex', gap: 24 }}>
          {/* Left: Deposit Info */}
          <div style={{ flex: 1 }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="用户">{deposit.userId}</Descriptions.Item>
              <Descriptions.Item label="申请时间">
                {new Date(deposit.processedAt).toLocaleString()}
              </Descriptions.Item>
              <Descriptions.Item label="申请金额">
                <span style={{ color: '#34d399', fontSize: 16, fontWeight: 700 }}>
                  +{parseFloat(deposit.amount).toLocaleString()}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label="备注">{deposit.reason || '-'}</Descriptions.Item>
            </Descriptions>
          </div>

          {/* Right: Current Account Info */}
          <div style={{ flex: 1 }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="当前账户状态">
                <Space direction="vertical" size="small">
                  <span>总资产: {accountInfo?.balance || '-'}</span>
                  <span>可用资金: {accountInfo?.available || '-'}</span>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="入金后资产">
                <span style={{ color: '#22d3ee', fontWeight: 600 }}>{newBalance}</span>
              </Descriptions.Item>
            </Descriptions>
          </div>
        </div>

        <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
          <Form.Item
            name="actualAmount"
            label="实际入金金额"
            rules={[{ required: true, message: '请输入实际入金金额' }]}
          >
            <Input
              type="number"
              suffix="元"
              style={{ background: '#1e293b', borderColor: '#fbbf24', color: 'white' }}
            />
          </Form.Item>

          <Form.Item name="reason" label="审批备注">
            <Input.TextArea
              rows={2}
              placeholder="输入审批备注（可选）"
              style={{ background: '#1e293b', borderColor: '#334155', color: 'white' }}
            />
          </Form.Item>

          <Form.Item name="rejectReason" label="拒绝原因（拒绝时填写）">
            <Input
              placeholder="拒绝原因"
              style={{ background: '#1e293b', borderColor: '#334155', color: 'white' }}
            />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button
                type="primary"
                onClick={handleApprove}
                style={{ background: '#34d399', borderColor: '#34d399' }}
              >
                ✓ 批准入金
              </Button>
              <Button
                danger
                onClick={handleReject}
                style={{ background: 'rgba(251, 113, 133, 0.2)' }}
              >
                ✗ 拒绝申请
              </Button>
              <Button onClick={onCancel}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Spin>
    </Modal>
  )
}

export default DepositApproval
