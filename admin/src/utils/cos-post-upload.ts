export interface CosPostUploadGrant {
  uploadUrl: string
  formData: Record<string, string>
}

export interface CosPostUploadProgress {
  loaded: number
  total: number
  percent: number
}

export interface CosPostUploadOptions {
  signal?: AbortSignal
  onProgress?: (progress: CosPostUploadProgress) => void
  timeoutMs?: number
}

export type CancelCosPostUploadSession = () => Promise<unknown>

const DEFAULT_UPLOAD_TIMEOUT_MS = 10 * 60 * 1000
const HTTPS_ROOT_HOSTNAME =
  /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/i

function isHttpsRootOrigin(value: string, parsed: URL): boolean {
  const candidate = value.trim()
  const authority = /^https:\/\/([^/?#]+)\/?$/i.exec(candidate)?.[1]
  return Boolean(
    authority &&
      HTTPS_ROOT_HOSTNAME.test(authority) &&
      parsed.protocol === 'https:' &&
      !parsed.username &&
      !parsed.password &&
      !parsed.port &&
      (parsed.pathname === '/' || parsed.pathname === '') &&
      !parsed.search &&
      !parsed.hash &&
      HTTPS_ROOT_HOSTNAME.test(parsed.hostname)
  )
}

export class CosPostUploadError extends Error {
  readonly status: number | null

  constructor(message: string, status: number | null = null) {
    super(message)
    this.name = 'CosPostUploadError'
    this.status = status
  }
}

function abortError() {
  if (typeof DOMException !== 'undefined') {
    return new DOMException('上传已取消', 'AbortError')
  }
  const error = new Error('上传已取消')
  error.name = 'AbortError'
  return error
}

function percent(loaded: number, total: number) {
  if (total <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((loaded / total) * 100)))
}

/**
 * Uploads a file straight to COS with the signed POST fields returned by the business API.
 * No business headers or cookies are attached to this request.
 */
export function uploadFileToCosPost(
  grant: CosPostUploadGrant,
  file: File,
  options: CosPostUploadOptions = {}
): Promise<void> {
  return new Promise((resolve, reject) => {
    let uploadUrl: URL
    try {
      uploadUrl = new URL(grant.uploadUrl)
    } catch {
      reject(new CosPostUploadError('COS 上传地址无效'))
      return
    }

    if (!isHttpsRootOrigin(grant.uploadUrl, uploadUrl)) {
      reject(new CosPostUploadError('COS 上传地址不安全'))
      return
    }
    if (Object.prototype.hasOwnProperty.call(grant.formData, 'file')) {
      reject(new CosPostUploadError('COS 上传表单不能预置 file 字段'))
      return
    }
    if (options.signal?.aborted) {
      reject(abortError())
      return
    }

    const xhr = new XMLHttpRequest()
    const formData = new FormData()
    for (const [key, value] of Object.entries(grant.formData)) {
      formData.append(key, value)
    }
    // COS requires the binary file field to be the final multipart field.
    formData.append('file', file, file.name)

    let settled = false
    const cleanup = () => options.signal?.removeEventListener('abort', handleAbort)
    const finish = (callback: () => void) => {
      if (settled) return
      settled = true
      cleanup()
      callback()
    }
    const handleAbort = () => {
      xhr.abort()
      finish(() => reject(abortError()))
    }

    xhr.open('POST', uploadUrl.toString(), true)
    xhr.withCredentials = false
    xhr.timeout = options.timeoutMs ?? DEFAULT_UPLOAD_TIMEOUT_MS
    xhr.upload.onprogress = (event) => {
      const total = event.lengthComputable && event.total > 0 ? event.total : file.size
      options.onProgress?.({
        loaded: Math.min(event.loaded, total),
        total,
        percent: percent(event.loaded, total)
      })
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        options.onProgress?.({ loaded: file.size, total: file.size, percent: 100 })
        finish(resolve)
        return
      }
      finish(() => reject(new CosPostUploadError(`COS 上传失败（HTTP ${xhr.status}）`, xhr.status)))
    }
    xhr.onerror = () => finish(() => reject(new CosPostUploadError('无法连接腾讯云 COS')))
    xhr.ontimeout = () => finish(() => reject(new CosPostUploadError('COS 上传超时，请重试')))
    xhr.onabort = () => finish(() => reject(abortError()))

    options.signal?.addEventListener('abort', handleAbort, { once: true })
    options.onProgress?.({ loaded: 0, total: file.size, percent: 0 })
    xhr.send(formData)
  })
}

/**
 * Runs only the client-to-COS stage and releases its server-side session when that stage fails.
 * The cancellation request is intentionally independent from the upload AbortSignal, and any
 * cancellation failure is ignored so the original COS/abort error remains observable to callers.
 */
export async function uploadFileToCosPostWithSessionCancellation(
  grant: CosPostUploadGrant,
  file: File,
  cancelSession: CancelCosPostUploadSession,
  options: CosPostUploadOptions = {}
): Promise<void> {
  try {
    await uploadFileToCosPost(grant, file, options)
  } catch (uploadError) {
    try {
      await cancelSession()
    } catch {
      // Best effort only: never replace the upload error with a cancellation error.
    }
    throw uploadError
  }
}
