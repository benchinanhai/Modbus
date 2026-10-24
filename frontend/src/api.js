const BASE = '/api'

async function handle(response) {
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `HTTP ${response.status}`)
  }
  // DELETE 返回 204，没有 JSON 体。
  if (response.status === 204) return null
  return response.json()
}

/** GET 请求。 */
export function get(path) {
  return fetch(BASE + path).then(handle)
}

/** POST JSON。 */
export function post(path, body) {
  return fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).then(handle)
}

/** POST multipart 文件上传（如点表 Excel）。 */
export function upload(path, file) {
  const body = new FormData()
  body.append('file', file)
  return fetch(BASE + path, { method: 'POST', body }).then(handle)
}

/** DELETE 请求。 */
export function del(path) {
  return fetch(BASE + path, { method: 'DELETE' }).then(handle)
}

/**
 * 建立遥测 WebSocket，断线自动重连。
 * @param {(message: object) => void} onMessage 消息回调
 * @returns {{close: () => void}} 关闭句柄
 */
export function connect(onMessage) {
  let socket = null
  let retry = 0
  let closed = false

  const open = () => {
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
    socket = new WebSocket(`${protocol}://${location.host}/ws/telemetry`)
    socket.onopen = () => {
      retry = 0
      onMessage({ type: 'status', connected: true })
    }
    socket.onmessage = (event) => {
      try {
        onMessage(JSON.parse(event.data))
      } catch (error) {
        console.warn('bad websocket message', error)
      }
    }
    socket.onclose = () => {
      onMessage({ type: 'status', connected: false })
      if (!closed) {
        retry += 1
        setTimeout(open, Math.min(5000, 400 * retry))
      }
    }
    socket.onerror = () => socket.close()
  }

  open()

  return {
    close() {
      closed = true
      if (socket) socket.close()
    }
  }
}
