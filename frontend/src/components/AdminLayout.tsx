import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Menu, Layout as AntLayout, Button, Dropdown, Space, Typography } from 'antd'
import {
  DashboardOutlined,
  SettingOutlined,
  DollarOutlined,
  FileTextOutlined,
  UserOutlined,
  LogoutOutlined,
  StockOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '../stores'

const { Header, Content, Sider } = AntLayout
const { Text } = Typography

function AdminLayout() {
  const location = useLocation()
  const navigate = useNavigate()
  const { user, logout } = useAuthStore()

  const handleLogout = () => {
    logout()
    window.location.href = '/login'
  }

  const menuItems = [
    { key: '/admin', icon: <DashboardOutlined />, label: '系统概览' },
    { key: '/admin/instruments', icon: <StockOutlined />, label: '品种管理' },
    { key: '/admin/cashflow', icon: <DollarOutlined />, label: '资金流水' },
    { key: '/', icon: <FileTextOutlined />, label: '交易端' },
  ]

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key)
  }

  const userMenuItems = [
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
  ]

  const handleUserMenuClick = ({ key }: { key: string }) => {
    if (key === 'logout') {
      handleLogout()
    }
  }

  return (
    <AntLayout style={{ minHeight: '100vh', background: '#020617' }}>
      <Header
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          background: 'rgba(120, 53, 15, 0.3)',
          borderBottom: '1px solid #fbbf24',
          padding: '0 24px',
        }}
      >
        {/* Logo */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <SettingOutlined style={{ fontSize: 24, color: '#fbbf24' }} />
          <Text style={{ fontSize: 18, fontWeight: 700, color: '#fbbf24' }}>
            HFT Admin
          </Text>
          <Text style={{ fontSize: 12, color: '#fbbf24', marginLeft: 8 }}>
            管理后台
          </Text>
        </div>

        {/* Admin Info */}
        <Space style={{ margin: '0 auto' }}>
          <Text style={{ color: '#94a3b8' }}>管理员: {user?.username}</Text>
          <Text style={{ color: '#fbbf24' }}>角色: SUPER_ADMIN</Text>
        </Space>

        {/* User Menu */}
        <Dropdown menu={{ items: userMenuItems, onClick: handleUserMenuClick }}>
          <Button
            icon={<UserOutlined />}
            style={{
              background: 'rgba(251, 191, 36, 0.2)',
              borderColor: '#fbbf24',
              color: '#fbbf24',
            }}
          >
            {user?.username || '管理员'}
          </Button>
        </Dropdown>
      </Header>

      <AntLayout>
        <Sider
          width={200}
          style={{
            background: 'rgba(15, 23, 42, 0.5)',
            borderRight: '1px solid #1e293b',
          }}
        >
          <Menu
            mode="inline"
            selectedKeys={[location.pathname]}
            items={menuItems}
            onClick={handleMenuClick}
            style={{
              height: '100%',
              background: 'transparent',
              color: '#94a3b8',
            }}
            theme="dark"
          />
        </Sider>

        <Content
          style={{
            padding: 24,
            background: '#020617',
            minHeight: 'calc(100vh - 64px)',
            overflow: 'auto',
          }}
        >
          <Outlet />
        </Content>
      </AntLayout>
    </AntLayout>
  )
}

export default AdminLayout