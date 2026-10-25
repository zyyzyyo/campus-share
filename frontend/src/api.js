const TOKEN_KEY = 'campus-access-token-v2'
let accessToken = ''
try { accessToken = sessionStorage.getItem(TOKEN_KEY) || '' } catch { /* Memory-only login still works. */ }

export function hasToken() { return Boolean(accessToken) }
export function saveToken(token) {
  accessToken = token || ''
  try { accessToken ? sessionStorage.setItem(TOKEN_KEY, accessToken) : sessionStorage.removeItem(TOKEN_KEY) } catch { /* Memory-only fallback. */ }
}

export async function api(path, { method = 'GET', body, signal, anonymous = false } = {}) {
  const requestToken = accessToken
  const headers = { Accept: 'application/json' }
  if (accessToken && !anonymous) headers.Authorization = `Bearer ${accessToken}`
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'
  let response
  try {
    response = await fetch(`/api${path}`, {
      method, headers, signal, credentials: 'omit',
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new Error('无法连接后端，请确认 Java 服务已启动（默认端口 8080）')
  }
  let payload
  try { payload = await response.json() } catch { throw new Error('后端响应异常，请确认 Java 服务和 /api 代理配置') }
  if (!response.ok || payload.code !== 'OK') {
    if (response.status === 401 && !anonymous && requestToken === accessToken) {
      saveToken('')
      window.dispatchEvent(new Event('campus-auth-expired'))
    }
    const error = new Error(payload.message || `请求失败（${response.status}）`)
    error.status = response.status
    error.code = payload.code
    throw error
  }
  return payload.data
}
