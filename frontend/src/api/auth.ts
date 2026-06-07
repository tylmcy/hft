import api from './index'

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  code: string
  message: string
  data: {
    token: string
    userId: string
    username: string
    accountId: string
    role?: 'ADMIN' | 'TRADER'
  }
}

export const authApi = {
  login: (data: LoginRequest): Promise<LoginResponse> =>
    api.post('/auth/login', data),

  logout: (): Promise<void> =>
    api.post('/auth/logout'),
}