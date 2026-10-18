/**
 * 本地后端的唯一 HTTP 入口（插件与核心共用这一份实现）。
 *
 * 后端只提供 API：默认基址按优先级解析 —— 桌面壳注入（__THISNOTE_API__）→
 * 本机设置（localStorage）→ 同源（开发期经 dev server 代理）→ 127.0.0.1:45917。
 */

import { ref } from 'vue'

const DEFAULT_PORT = 45917

function resolveBaseUrl(): string {
  const injected = (window as any).__THISNOTE_API__
  if (typeof injected === 'string' && injected.length > 0) return injected
  const fromStorage = localStorage.getItem('thisnote.apiBase')
  if (fromStorage) return fromStorage
  // Packaged app: sidecar on the default port. Dev: Vite proxies /api to the same place.
  if (window.location.protocol === 'http:' || window.location.protocol === 'https:') {
    if (window.location.port === '5173' || window.location.port === '') {
      return ''
    }
  }
  return `http://127.0.0.1:${DEFAULT_PORT}`
}

export const apiBase = ref(resolveBaseUrl())

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** Connectivity state surfaced in the status bar. */
export const backendOnline = ref(false)
export const backendMessage = ref('正在连接后端…')

type Query = Record<string, string | number | boolean | null | undefined>

function withQuery(path: string, query?: Query): string {
  if (!query) return path
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue
    params.append(k, String(v))
  }
  const qs = params.toString()
  return qs ? `${path}?${qs}` : path
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body && body.message) message = body.message
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, message)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text) return undefined as T
  return JSON.parse(text) as T
}

export async function request<T>(
  method: string,
  path: string,
  options: { query?: Query; body?: unknown; form?: FormData; raw?: boolean } = {},
): Promise<T> {
  const url = `${apiBase.value}${withQuery(path, options.query)}`
  const init: RequestInit = { method }
  if (options.form) {
    init.body = options.form
  } else if (options.body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(options.body)
  }
  let res: Response
  try {
    res = await fetch(url, init)
  } catch (e) {
    backendOnline.value = false
    backendMessage.value = '无法连接本地后端（127.0.0.1:45917）'
    throw new ApiError(0, backendMessage.value)
  }
  backendOnline.value = true
  backendMessage.value = '已连接本地后端'
  if (options.raw) return res as unknown as T
  return handle<T>(res)
}

export const http = {
  get: <T>(path: string, query?: Query) => request<T>('GET', path, { query }),
  post: <T>(path: string, body?: unknown, query?: Query) => request<T>('POST', path, { body, query }),
  patch: <T>(path: string, body?: unknown, query?: Query) => request<T>('PATCH', path, { body, query }),
  put: <T>(path: string, body?: unknown, query?: Query) => request<T>('PUT', path, { body, query }),
  del: <T>(path: string, query?: Query) => request<T>('DELETE', path, { query }),
  upload: <T>(path: string, form: FormData, query?: Query) =>
    request<T>('POST', path, { form, query }),
  /** Download a URL returned by the backend (used for JSON export). */
  url: (path: string, query?: Query) => `${apiBase.value}${withQuery(path, query)}`,
}

/**
 * Poll /api/health/get until the sidecar answers (used at startup).
 *
 * <p><b>并发去重</b>：启动时 main.ts 与 App.vue 都会等后端就绪，各等各的就会把
 * /api/health/get 打两轮。这里让并发调用共用同一个轮询；返回后即可重新发起
 * （「重试连接」按钮依赖这一点）。
 */
let backendWait: Promise<boolean> | null = null

export async function waitForBackend(timeoutMs = 30000): Promise<boolean> {
  if (backendWait) {
    return backendWait
  }
  backendWait = (async () => {
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
      try {
        const res = await fetch(`${apiBase.value}/api/health/get`, { method: 'GET' })
        if (res.ok) {
          backendOnline.value = true
          backendMessage.value = '已连接本地后端'
          return true
        }
      } catch {
        /* keep retrying */
      }
      await new Promise((r) => setTimeout(r, 500))
    }
    backendOnline.value = false
    backendMessage.value = '后端未响应，请检查本地服务'
    return false
  })()
  try {
    return await backendWait
  } finally {
    backendWait = null
  }
}
