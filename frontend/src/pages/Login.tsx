import { Button, Form, Input, Card, message } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../stores/authStore'
import { authApi } from '../api/auth'
import './Login.css'

function Login() {
  const navigate = useNavigate()
  const { login } = useAuthStore()
  const [form] = Form.useForm()

  const handleSubmit = async (values: { username: string; password: string }) => {
    try {
      const response = await authApi.login(values)
      if (response.code === '0' && response.data) {
        const data = response.data
        login(data.accessToken, {
          userId: data.userId,
          username: data.username,
          accountId: data.accountId,
          role: data.role || 'TRADER',
        })
        message.success('登录成功')
        navigate('/')
      } else {
        message.error(response.message || '登录失败')
      }
    } catch (error: any) {
      message.error(error.response?.data?.message || '登录失败，请检查网络')
    }
  }

  return (
    <div className="login-container">
      <Card className="login-card" title="HFT 交易系统">
        <Form
          form={form}
          onFinish={handleSubmit}
          layout="vertical"
          autoComplete="off"
        >
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder="用户名: trader01"
              size="large"
            />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码: password123"
              size="large"
            />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block size="large">
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default Login