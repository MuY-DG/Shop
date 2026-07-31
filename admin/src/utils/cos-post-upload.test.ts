import assert from 'node:assert/strict'
import test from 'node:test'
import {
  CosPostUploadError,
  uploadFileToCosPost,
  uploadFileToCosPostWithSessionCancellation
} from './cos-post-upload'

type FakeXhrInstance = {
  method?: string
  url?: string
  body?: FormData
  aborted: boolean
  withCredentials: boolean
  timeout: number
  status: number
  upload: { onprogress: ((event: ProgressEvent) => void) | null }
  onload: (() => void) | null
  onerror: (() => void) | null
  ontimeout: (() => void) | null
  onabort: (() => void) | null
}

const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
const originalXhr = Object.getOwnPropertyDescriptor(globalThis, 'XMLHttpRequest')

function installFakeBrowser(completeImmediately: boolean) {
  const instances: FakeXhrInstance[] = []

  class FakeXMLHttpRequest implements FakeXhrInstance {
    method?: string
    url?: string
    body?: FormData
    aborted = false
    withCredentials = true
    timeout = 0
    status = 204
    upload = { onprogress: null as ((event: ProgressEvent) => void) | null }
    onload: (() => void) | null = null
    onerror: (() => void) | null = null
    ontimeout: (() => void) | null = null
    onabort: (() => void) | null = null

    constructor() {
      instances.push(this)
    }

    open(method: string, url: string) {
      this.method = method
      this.url = url
    }

    send(body: FormData) {
      this.body = body
      this.upload.onprogress?.({
        lengthComputable: true,
        loaded: 2,
        total: 4
      } as ProgressEvent)
      if (completeImmediately) this.onload?.()
    }

    abort() {
      this.aborted = true
      this.onabort?.()
    }
  }

  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { location: { href: 'https://admin.example.test/' } }
  })
  Object.defineProperty(globalThis, 'XMLHttpRequest', {
    configurable: true,
    value: FakeXMLHttpRequest
  })
  return instances
}

function restoreBrowserGlobals() {
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow)
  else Reflect.deleteProperty(globalThis, 'window')
  if (originalXhr) Object.defineProperty(globalThis, 'XMLHttpRequest', originalXhr)
  else Reflect.deleteProperty(globalThis, 'XMLHttpRequest')
}

test('COS POST appends all signed fields before file and reports upload progress', async () => {
  const instances = installFakeBrowser(true)
  const progress: number[] = []
  try {
    const file = new File(['data'], 'photo.png', { type: 'image/png' })
    await uploadFileToCosPost(
      {
        uploadUrl: 'https://uploads.storage.example/',
        formData: {
          key: 'staging/upload/photo.png',
          policy: 'signed-policy',
          'q-signature': 'signature'
        }
      },
      file,
      { onProgress: (event) => progress.push(event.percent) }
    )

    assert.equal(instances.length, 1)
    assert.equal(instances[0].method, 'POST')
    assert.equal(instances[0].url, 'https://uploads.storage.example/')
    assert.equal(instances[0].withCredentials, false)
    const fieldNames: string[] = []
    instances[0].body?.forEach((_value, key) => fieldNames.push(key))
    assert.deepEqual(fieldNames, ['key', 'policy', 'q-signature', 'file'])
    assert.deepEqual(progress, [0, 50, 100])
  } finally {
    restoreBrowserGlobals()
  }
})

test('COS POST aborts the underlying request through AbortSignal', async () => {
  const instances = installFakeBrowser(false)
  const controller = new AbortController()
  try {
    const upload = uploadFileToCosPost(
      {
        uploadUrl: 'https://bucket.cos.ap-guangzhou.myqcloud.com/',
        formData: { key: 'staging/upload/photo.webp' }
      },
      new File(['data'], 'photo.webp', { type: 'image/webp' }),
      { signal: controller.signal }
    )
    controller.abort()

    await assert.rejects(upload, (error: unknown) => {
      assert.equal(error instanceof Error ? error.name : '', 'AbortError')
      return true
    })
    assert.equal(instances[0].aborted, true)
  } finally {
    restoreBrowserGlobals()
  }
})

test('COS POST accepts a valid dynamic HTTPS root origin', async () => {
  const instances = installFakeBrowser(true)
  try {
    await uploadFileToCosPost(
      {
        uploadUrl: 'https://customer-origin.storage.example',
        formData: { key: 'private/direct-upload/id/source.webp' }
      },
      new File(['data'], 'photo.webp', { type: 'image/webp' })
    )
    assert.equal(instances.length, 1)
    assert.equal(instances[0].url, 'https://customer-origin.storage.example/')
  } finally {
    restoreBrowserGlobals()
  }
})

test('COS POST rejects anything other than a valid HTTPS root origin', async () => {
  const instances = installFakeBrowser(true)
  try {
    for (const uploadUrl of [
      'http://uploads.storage.example',
      'https://user:password@uploads.storage.example',
      'https://uploads.storage.example:443',
      'https://uploads.storage.example:8443',
      'https://uploads.storage.example/collect',
      'https://uploads.storage.example?redirect=1',
      'https://uploads.storage.example#fragment',
      'https://localhost',
      'https://127.0.0.1',
      'https://bad_host.storage.example',
      'https://-invalid.storage.example',
      'https://invalid-.storage.example'
    ]) {
      await assert.rejects(
        uploadFileToCosPost(
          {
            uploadUrl,
            formData: { key: 'private/direct-upload/id/source.webp' }
          },
          new File(['data'], 'photo.webp', { type: 'image/webp' })
        ),
        /COS 上传地址不安全/
      )
    }
    assert.equal(instances.length, 0)
  } finally {
    restoreBrowserGlobals()
  }
})

test('failed COS POST releases its upload session without replacing the upload error', async () => {
  const instances = installFakeBrowser(false)
  let cancellationCalls = 0
  try {
    const upload = uploadFileToCosPostWithSessionCancellation(
      {
        uploadUrl: 'https://bucket.cos.ap-guangzhou.myqcloud.com/',
        formData: { key: 'staging/upload/photo.png' }
      },
      new File(['data'], 'photo.png', { type: 'image/png' }),
      async () => {
        cancellationCalls += 1
        throw new Error('cancel endpoint unavailable')
      }
    )
    instances[0].status = 403
    instances[0].onload?.()

    await assert.rejects(upload, (error: unknown) => {
      assert.equal(error instanceof CosPostUploadError ? error.status : null, 403)
      assert.match(error instanceof Error ? error.message : '', /HTTP 403/)
      return true
    })
    assert.equal(cancellationCalls, 1)
  } finally {
    restoreBrowserGlobals()
  }
})

test('aborted COS POST cancels with an independent callback and keeps AbortError', async () => {
  const instances = installFakeBrowser(false)
  const controller = new AbortController()
  let cancellationSignalWasAborted: boolean | undefined
  try {
    const upload = uploadFileToCosPostWithSessionCancellation(
      {
        uploadUrl: 'https://bucket.cos.ap-guangzhou.myqcloud.com/',
        formData: { key: 'staging/upload/photo.webp' }
      },
      new File(['data'], 'photo.webp', { type: 'image/webp' }),
      async () => {
        cancellationSignalWasAborted = controller.signal.aborted
      },
      { signal: controller.signal }
    )
    controller.abort()

    await assert.rejects(upload, (error: unknown) => {
      assert.equal(error instanceof Error ? error.name : '', 'AbortError')
      return true
    })
    assert.equal(instances[0].aborted, true)
    assert.equal(cancellationSignalWasAborted, true)
  } finally {
    restoreBrowserGlobals()
  }
})

test('successful COS POST does not cancel its upload session', async () => {
  installFakeBrowser(true)
  let cancellationCalls = 0
  try {
    await uploadFileToCosPostWithSessionCancellation(
      {
        uploadUrl: 'https://bucket.cos.ap-guangzhou.myqcloud.com/',
        formData: { key: 'staging/upload/photo.png' }
      },
      new File(['data'], 'photo.png', { type: 'image/png' }),
      async () => {
        cancellationCalls += 1
      }
    )

    assert.equal(cancellationCalls, 0)
  } finally {
    restoreBrowserGlobals()
  }
})
