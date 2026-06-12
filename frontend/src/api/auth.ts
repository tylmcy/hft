import api from './index'
import type { ApiResponse, LoginResponse } from './types'

export interface LoginRequest {
  username: string
  password: string
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

export const authApi = {
  login: (data: LoginRequest): Promise<ApiResponse<LoginResponse>> =>
    api.post('/auth/login', data),

  logout: (): Promise<void> =>
    api.post('/auth/logout'),

  changePassword: (data: ChangePasswordRequest): Promise<{ code: string; message: string }> =>
    api.post('/auth/change-password', data),
}