/**
 * HTTP 请求封装模块
 * 基于 Axios 封装的 HTTP 请求工具，提供统一的请求/响应处理
 *
 * ## 主要功能
 *
 * - 请求/响应拦截器（自动添加 Token、统一错误处理）
 * - 401 未授权自动登出（带防抖机制）
 * - 请求失败自动重试（可配置）
 * - 统一的成功/错误消息提示
 * - 支持 GET/POST/PUT/DELETE 等常用方法
 *
 * @module utils/http
 * @author Art Design Pro Team
 */

import axios, { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { nextTick } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { ApiStatus } from './status'
import { HttpError, handleError, showError, showSuccess } from './error'
import { $t } from '@/locales'
import { BaseResponse } from '@/types'
import { getOrCreateAdminDeviceId } from '@/utils/device-identity'

/** 请求配置常量 */
const REQUEST_TIMEOUT = 15000
const LOGOUT_DELAY = 500
const MAX_RETRIES = 0
const RETRY_DELAY = 1000
const UNAUTHORIZED_DEBOUNCE_TIME = 3000
const AUTH_REFRESH_LOCK_NAME = 'shop-admin-auth-refresh'

/** 401防抖状态 */
let isUnauthorizedErrorShown = false
let unauthorizedTimer: NodeJS.Timeout | null = null

/** 扩展 AxiosRequestConfig */
interface ExtendedAxiosRequestConfig extends AxiosRequestConfig {
  showErrorMessage?: boolean
  showSuccessMessage?: boolean
  _retryAuth?: boolean
  _skipAuthRefresh?: boolean
}

const { VITE_API_URL, VITE_WITH_CREDENTIALS } = import.meta.env

/** Axios实例 */
const axiosInstance = axios.create({
  timeout: REQUEST_TIMEOUT,
  baseURL: VITE_API_URL,
  withCredentials: VITE_WITH_CREDENTIALS === 'true',
  validateStatus: (status) => status >= 200 && status < 300,
  transformResponse: [
    (data, headers) => {
      const contentType = headers['content-type']
      if (contentType?.includes('application/json')) {
        try {
          return JSON.parse(data)
        } catch {
          return data
        }
      }
      return data
    }
  ]
})

let refreshPromise: Promise<string> | null = null

/** 请求拦截器 */
axiosInstance.interceptors.request.use(
  (request: InternalAxiosRequestConfig) => {
    const { accessToken } = useUserStore()
    if (accessToken) request.headers.set('Authorization', `Bearer ${accessToken}`)
    request.headers.set('X-Device-Id', getOrCreateAdminDeviceId())

    if (request.data && !(request.data instanceof FormData) && !request.headers['Content-Type']) {
      request.headers.set('Content-Type', 'application/json')
      request.data = JSON.stringify(request.data)
    }

    return request
  },
  (error) => {
    showError(createHttpError($t('httpMsg.requestConfigError'), ApiStatus.error))
    return Promise.reject(error)
  }
)

/** 响应拦截器 */
axiosInstance.interceptors.response.use(
  (response: AxiosResponse<BaseResponse>) => {
    if (response.config.responseType === 'blob') return response
    const { code, msg } = response.data
    if (code === ApiStatus.success) return response
    if (code === ApiStatus.unauthorized) handleUnauthorizedError(msg)
    throw createHttpError(msg || $t('httpMsg.requestFailed'), code)
  },
  async (error) => {
    const config = error.config as ExtendedAxiosRequestConfig | undefined
    if (error.response?.status === ApiStatus.unauthorized) {
      const userStore = useUserStore()
      if (config && !config._skipAuthRefresh && !config._retryAuth && userStore.refreshToken) {
        try {
          const accessToken = await refreshAdminAccessToken(accessTokenFromRequest(config))
          config._retryAuth = true
          config.headers = config.headers || {}
          config.headers.Authorization = `Bearer ${accessToken}`
          return axiosInstance.request(config)
        } catch {
          handleUnauthorizedError()
        }
      }
      handleUnauthorizedError()
    }
    return Promise.reject(handleError(error))
  }
)

async function refreshAdminAccessToken(failedAccessToken: string | null): Promise<string> {
  if (refreshPromise) return refreshPromise

  const userStore = useUserStore()
  if (!userStore.refreshToken) {
    throw createHttpError($t('httpMsg.unauthorized'), ApiStatus.unauthorized)
  }

  refreshPromise = withCrossTabRefreshLock(async () => {
    // 等待锁期间，其他标签页可能已经完成 refresh token 旋转。
    userStore.syncAuthFromStorage()
    if (failedAccessToken && userStore.accessToken && userStore.accessToken !== failedAccessToken) {
      return userStore.accessToken
    }

    const refreshToken = userStore.refreshToken
    if (!refreshToken) {
      throw createHttpError($t('httpMsg.unauthorized'), ApiStatus.unauthorized)
    }

    const response = await axiosInstance.request<BaseResponse<Api.Auth.LoginResponse>>({
      url: '/admin/auth/refresh',
      method: 'POST',
      data: { refreshToken },
      _skipAuthRefresh: true
    } as ExtendedAxiosRequestConfig)
    const session = response.data.data
    if (!session?.token || !session.refreshToken) {
      throw createHttpError($t('httpMsg.unauthorized'), ApiStatus.unauthorized)
    }

    userStore.setToken(session.token, session.refreshToken)
    userStore.setLoginStatus(true)
    // 等待持久化订阅写入 localStorage 后再释放跨标签页锁。
    await nextTick()
    return session.token
  }).finally(() => {
    refreshPromise = null
  })

  return refreshPromise
}

/**
 * Web Locks 在同源标签页之间提供真正的互斥；不支持时仍保留单标签页去重。
 */
function withCrossTabRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks) {
    return navigator.locks.request(AUTH_REFRESH_LOCK_NAME, { mode: 'exclusive' }, task)
  }
  return task()
}

/**
 * 读取触发 401 的请求实际使用的 access token。
 */
function accessTokenFromRequest(config?: ExtendedAxiosRequestConfig): string | null {
  const headers = config?.headers
  if (!headers) {
    return null
  }

  const headerReader = headers as unknown as {
    get?: (name: string) => unknown
    Authorization?: unknown
    authorization?: unknown
  }
  const authorization =
    typeof headerReader.get === 'function'
      ? headerReader.get('Authorization')
      : (headerReader.Authorization ?? headerReader.authorization)
  if (typeof authorization !== 'string') {
    return null
  }

  const match = /^Bearer\s+(.+)$/i.exec(authorization.trim())
  return match?.[1] || null
}

/** 统一创建HttpError */
function createHttpError(message: string, code: number) {
  return new HttpError(message, code)
}

/** 处理401错误（带防抖） */
function handleUnauthorizedError(message?: string): never {
  const error = createHttpError(message || $t('httpMsg.unauthorized'), ApiStatus.unauthorized)

  if (!isUnauthorizedErrorShown) {
    isUnauthorizedErrorShown = true
    logOut()

    unauthorizedTimer = setTimeout(resetUnauthorizedError, UNAUTHORIZED_DEBOUNCE_TIME)

    showError(error, true)
    throw error
  }

  throw error
}

/** 重置401防抖状态 */
function resetUnauthorizedError() {
  isUnauthorizedErrorShown = false
  if (unauthorizedTimer) clearTimeout(unauthorizedTimer)
  unauthorizedTimer = null
}

/** 退出登录函数 */
function logOut() {
  setTimeout(() => {
    useUserStore().logOut()
  }, LOGOUT_DELAY)
}

/** 是否需要重试 */
function shouldRetry(statusCode: number) {
  return [
    ApiStatus.requestTimeout,
    ApiStatus.internalServerError,
    ApiStatus.badGateway,
    ApiStatus.serviceUnavailable,
    ApiStatus.gatewayTimeout
  ].includes(statusCode)
}

/** 请求重试逻辑 */
async function retryRequest<T>(
  config: ExtendedAxiosRequestConfig,
  retries: number = MAX_RETRIES
): Promise<T> {
  try {
    return await request<T>(config)
  } catch (error) {
    if (retries > 0 && error instanceof HttpError && shouldRetry(error.code)) {
      await delay(RETRY_DELAY)
      return retryRequest<T>(config, retries - 1)
    }
    throw error
  }
}

/** 延迟函数 */
function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 请求函数 */
async function request<T = any>(config: ExtendedAxiosRequestConfig): Promise<T> {
  // POST | PUT 参数自动填充
  if (
    ['POST', 'PUT'].includes(config.method?.toUpperCase() || '') &&
    config.params &&
    !config.data
  ) {
    config.data = config.params
    config.params = undefined
  }

  try {
    const res = await axiosInstance.request<BaseResponse<T>>(config)

    if (config.responseType === 'blob') {
      return res.data as unknown as T
    }

    // 显示成功消息
    if (config.showSuccessMessage && res.data.msg) {
      showSuccess(res.data.msg)
    }

    return res.data.data as T
  } catch (error) {
    if (error instanceof HttpError && error.code !== ApiStatus.unauthorized) {
      const showMsg = config.showErrorMessage !== false
      showError(error, showMsg)
    }
    return Promise.reject(error)
  }
}

/** API方法集合 */
const api = {
  get<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>({ ...config, method: 'GET' })
  },
  post<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>({ ...config, method: 'POST' })
  },
  put<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>({ ...config, method: 'PUT' })
  },
  del<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>({ ...config, method: 'DELETE' })
  },
  request<T>(config: ExtendedAxiosRequestConfig) {
    return retryRequest<T>(config)
  }
}

export default api
