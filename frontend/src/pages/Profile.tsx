import { useState, useEffect } from 'react'
import { Card, Descriptions, Button, Table, Tag, Modal, Form, Select, Space, message, Popconfirm, Input } from 'antd'
import { UserOutlined, KeyOutlined, PlusOutlined, DeleteOutlined, EyeOutlined, LockOutlined } from '@ant-design/icons'
import { useAuthStore, useAccountStore } from '../stores'
import { useNavigate } from 'react-router-dom'
import { authApi } from '../api/auth'
import type { ColumnsType } from 'antd/es/table'

interface ApiKeyItem {
  apiKeyId: string
  apiKey: string
  keyType: 'READ_ONLY' | 'TRADING'
  allowedInstruments: string
  status: 'ACTIVE' | 'DISABLED'
  createdAt: number
  lastUsedAt: number
}

export default function Profile() {
  const navigate = useNavigate()
  const { user, logout } = useAuthStore()
  const { account } = useAccountStore()

  const [apiKeys, setApiKeys] = useState<ApiKeyItem[]>([])
  const [loading, setLoading] = useState(false)
  const [createModalVisible, setCreateModalVisible] = useState(false)
  const [createForm] = Form.useForm()
  const [secretModalVisible, setSecretModalVisible] = useState(false)
  const [currentSecret, setCurrentSecret] = useState<string>('')
  const [changePasswordModalVisible, setChangePasswordModalVisible] = useState(false)
  const [changePasswordForm] = Form.useForm()

  // Redirect if not logged in
  useEffect(() => {
    if (!user) {
      navigate('/login')
    }
  }, [user, navigate])

  // Load API keys
  useEffect(() => {
    loadApiKeys()
  }, [])

  const loadApiKeys = async () => {
    setLoading(true)
    try {
      // Mock data
      setApiKeys([
        {
          apiKeyId: '1',
          apiKey: 'ak_a1b2c3d4e5f6...',
          keyType: 'TRADING',
          allowedInstruments: '股票,期货',
          status: 'ACTIVE',
          createdAt: Date.now() - 86400000 * 5,
          lastUsedAt: Date.now() - 3600000,
        },
        {
          apiKeyId: '2',
          apiKey: 'ak_g7h8i9j0k1l2...',
          keyType: 'TRADING',
          allowedInstruments: '期权',
          status: 'ACTIVE',
          createdAt: Date.now() - 86400000 * 10,
          lastUsedAt: Date.now() - 7200000,
        },
        {
          apiKeyId: '3',
          apiKey: 'ak_m3n4o5p6q7r8...',
          keyType: 'READ_ONLY',
          allowedInstruments: '',
          status: 'ACTIVE',
          createdAt: Date.now() - 86400000,
          lastUsedAt: Date.now() - 60000,
        },
      ])
    } catch (error) {
      message.error('加载API Key失败')
    } finally {
      setLoading(false)
    }
  }

  const handleCreateApiKey = async () => {
    try {
      message.success('API Key创建成功')
      setCreateModalVisible(false)
      createForm.resetFields()
      loadApiKeys()
    } catch (error) {
      message.error('创建失败')
    }
  }

  const handleDeleteApiKey = async () => {
    try {
      message.success('删除成功')
      loadApiKeys()
    } catch (error) {
      message.error('删除失败')
    }
  }

  const handleViewSecret = async () => {
    setCurrentSecret('sk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx')
    setSecretModalVisible(true)
  }

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const handleChangePassword = async () => {
    try {
      const values = await changePasswordForm.validateFields()
      const result = await authApi.changePassword({
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
      })
      if (result.code === '0') {
        message.success('密码修改成功')
        setChangePasswordModalVisible(false)
        changePasswordForm.resetFields()
      } else {
        message.error(result.message || '密码修改失败')
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || '密码修改失败')
    }
  }

  const apiKeyColumns: ColumnsType<ApiKeyItem> = [
    {
      title: '名称',
      dataIndex: 'apiKey',
      render: (key: string) => (
        <span style={{ color: '#22d3ee', fontFamily: 'monospace' }}>{key}</span>
      ),
    },
    {
      title: '类型',
      dataIndex: 'keyType',
      render: (type: string) => (
        <Tag color={type === 'TRADING' ? '#22d3ee' : '#a78bfa'}>
          {type === 'TRADING' ? '交易Key' : '只读Key'}
        </Tag>
      ),
    },
    {
      title: '可交易品种',
      dataIndex: 'allowedInstruments',
      render: (instruments: string) => (
        <span style={{ color: instruments ? '#fff' : '#64748b' }}>
          {instruments || '-'}
        </span>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: (status: string) => (
        <Tag color={status === 'ACTIVE' ? '#34d399' : '#64748b'}>
          {status === 'ACTIVE' ? '启用' : '禁用'}
        </Tag>
      ),
    },
    {
      title: '最后使用',
      dataIndex: 'lastUsedAt',
      render: (time: number) => (
        <span style={{ color: '#94a3b8' }}>
          {new Date(time).toLocaleString()}
        </span>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: () => (
        <Space>
          <Button
            size="small"
            icon={<EyeOutlined />}
            onClick={() => handleViewSecret()}
          >
            查看Secret
          </Button>
          <Popconfirm
            title="确定删除此API Key?"
            onConfirm={() => handleDeleteApiKey()}
          >
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div style={{ padding: 24 }}>
      {/* User Info Card */}
      <Card
        style={{
          marginBottom: 24,
          background: 'rgba(8, 51, 68, 0.4)',
          borderColor: '#22d3ee',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
          {/* Avatar */}
          <div
            style={{
              width: 80,
              height: 80,
              borderRadius: '50%',
              background: 'rgba(34, 211, 238, 0.2)',
              border: '2px solid #22d3ee',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <UserOutlined style={{ fontSize: 32, color: '#22d3ee' }} />
          </div>

          {/* User Info */}
          <div>
            <div style={{ color: '#fff', fontSize: 18, fontWeight: 600, marginBottom: 8 }}>
              用户名: {user?.username}
            </div>
            <div style={{ color: '#94a3b8', fontSize: 14 }}>
              用户ID: {user?.userId} | 账户ID: {user?.accountId}
            </div>
            <div style={{ color: '#94a3b8', fontSize: 14 }}>
              角色: <Tag color={user?.role === 'ADMIN' ? '#fbbf24' : '#22d3ee'}>
                {user?.role === 'ADMIN' ? '管理员' : '交易员'}
              </Tag>
            </div>
          </div>

          {/* Account Stats */}
          {account && (
            <div style={{ marginLeft: 'auto' }}>
              <Descriptions column={3} size="small">
                <Descriptions.Item label="总资产" style={{ color: '#34d399' }}>
                  {parseFloat(account.balance).toLocaleString()}
                </Descriptions.Item>
                <Descriptions.Item label="可用资金" style={{ color: '#22d3ee' }}>
                  {parseFloat(account.available).toLocaleString()}
                </Descriptions.Item>
                <Descriptions.Item label="冻结资金" style={{ color: '#fbbf24' }}>
                  {parseFloat(account.frozen).toLocaleString()}
                </Descriptions.Item>
              </Descriptions>
            </div>
          )}

          {/* Logout Button */}
          <Space>
            <Button
              icon={<LockOutlined />}
              onClick={() => setChangePasswordModalVisible(true)}
            >
              修改密码
            </Button>
            <Button onClick={handleLogout}>
              退出登录
            </Button>
          </Space>
        </div>
      </Card>

      {/* API Key Management */}
      <Card
        title={
          <Space>
            <KeyOutlined style={{ color: '#22d3ee' }} />
            <span style={{ color: '#fff', fontWeight: 600 }}>API Key管理</span>
          </Space>
        }
        extra={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateModalVisible(true)}
            style={{ background: 'rgba(34, 211, 238, 0.3)', borderColor: '#22d3ee' }}
          >
            创建新API Key
          </Button>
        }
        style={{
          background: 'rgba(8, 51, 68, 0.4)',
          borderColor: '#22d3ee',
        }}
      >
        <Table
          columns={apiKeyColumns}
          dataSource={apiKeys}
          loading={loading}
          pagination={false}
          rowKey="apiKeyId"
        />
      </Card>

      {/* Create API Key Modal */}
      <Modal
        title="创建API Key"
        open={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        footer={null}
      >
        <Form
          form={createForm}
          layout="vertical"
          onFinish={handleCreateApiKey}
        >
          <Form.Item
            name="keyType"
            label="Key类型"
            rules={[{ required: true, message: '请选择Key类型' }]}
          >
            <Select>
              <Select.Option value="READ_ONLY">只读Key</Select.Option>
              <Select.Option value="TRADING">交易Key</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item
            noStyle
            shouldUpdate={(prev, curr) => prev.keyType !== curr.keyType}
          >
            {({ getFieldValue }) =>
              getFieldValue('keyType') === 'TRADING' ? (
                <Form.Item
                  name="allowedInstruments"
                  label="可交易品种"
                >
                  <Select mode="multiple">
                    <Select.Option value="股票">股票</Select.Option>
                    <Select.Option value="期货">期货</Select.Option>
                    <Select.Option value="期权">期权</Select.Option>
                  </Select>
                </Form.Item>
              ) : null
            }
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                创建
              </Button>
              <Button onClick={() => setCreateModalVisible(false)}>
                取消
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* View Secret Modal */}
      <Modal
        title="Secret Key"
        open={secretModalVisible}
        onCancel={() => setSecretModalVisible(false)}
        footer={
          <Button onClick={() => setSecretModalVisible(false)}>
            关闭
          </Button>
        }
      >
        <div style={{
          padding: 16,
          background: '#1e293b',
          borderRadius: 4,
          marginBottom: 16,
        }}>
          <code style={{ color: '#22d3ee', wordBreak: 'break-all' }}>
            {currentSecret}
          </code>
        </div>
        <div style={{ color: '#fbbf24', fontSize: 12 }}>
          ⚠️ Secret Key仅显示一次，请妥善保存。关闭此窗口后将无法再次查看。
        </div>
      </Modal>

      {/* Change Password Modal */}
      <Modal
        title="修改密码"
        open={changePasswordModalVisible}
        onCancel={() => setChangePasswordModalVisible(false)}
        footer={null}
      >
        <Form
          form={changePasswordForm}
          layout="vertical"
          onFinish={handleChangePassword}
        >
          <Form.Item
            name="oldPassword"
            label="旧密码"
            rules={[{ required: true, message: '请输入旧密码' }]}
          >
            <Input.Password placeholder="请输入旧密码" />
          </Form.Item>

          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, message: '密码长度至少6位' },
            ]}
          >
            <Input.Password placeholder="请输入新密码" />
          </Form.Item>

          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请确认新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve()
                  }
                  return Promise.reject(new Error('两次输入的密码不一致'))
                },
              }),
            ]}
          >
            <Input.Password placeholder="请再次输入新密码" />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                确认修改
              </Button>
              <Button onClick={() => setChangePasswordModalVisible(false)}>
                取消
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}