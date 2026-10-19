import type { ErrorDto, MessageDto, RiskLevel } from './types'

export interface TurnHandlers {
  onUserSaved?: (messageId: number) => void
  onSafety?: (level: RiskLevel) => void
  /** The safety check for the message could not be done (the reply still comes). */
  onSafetyUnavailable?: () => void
  /** How much of this session the model sees on this turn (older messages are omitted first). */
  onContext?: (includedMessages: number, omittedMessages: number) => void
  onDelta: (text: string) => void
  onDone?: (message: MessageDto) => void
  onError: (message: string, kind?: string) => void
}

/**
 * POSTs to a streaming endpoint and dispatches SSE events (user_saved, safety, safety_unavailable, context, delta, done, error).
 * Uses fetch + ReadableStream because EventSource cannot send a POST body.
 */
export async function streamTurn(
  url: string,
  body: unknown | null,
  handlers: TurnHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream',
        ...(body === null ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === null ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
      signal,
    })
  } catch {
    if (signal?.aborted) return
    handlers.onError('서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인하세요.', 'NETWORK')
    return
  }

  if (!res.ok || !res.body) {
    let message = `요청 실패 (${res.status})`
    let kind: string | undefined
    try {
      const err = (await res.json()) as ErrorDto
      if (err?.message) message = err.message
      kind = err?.kind
    } catch {
      // ignore
    }
    handlers.onError(message, kind)
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let terminal = false // a done or error event was received

  const dispatch = (chunk: string) => {
    let event = 'message'
    const dataLines: string[] = []
    for (const rawLine of chunk.split('\n')) {
      const line = rawLine.replace(/\r$/, '')
      if (line.startsWith(':') || line === '') continue
      const idx = line.indexOf(':')
      const field = idx < 0 ? line : line.slice(0, idx)
      let value = idx < 0 ? '' : line.slice(idx + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') event = value
      else if (field === 'data') dataLines.push(value)
    }
    if (dataLines.length === 0) return
    let data: unknown
    try {
      data = JSON.parse(dataLines.join('\n'))
    } catch {
      return
    }
    const obj = data as Record<string, unknown>
    switch (event) {
      case 'user_saved':
        handlers.onUserSaved?.(obj.messageId as number)
        break
      case 'safety':
        handlers.onSafety?.(obj.riskLevel as RiskLevel)
        break
      case 'safety_unavailable':
        handlers.onSafetyUnavailable?.()
        break
      case 'context':
        handlers.onContext?.(Number(obj.includedMessages ?? 0), Number(obj.omittedMessages ?? 0))
        break
      case 'delta':
        handlers.onDelta(String(obj.text ?? ''))
        break
      case 'done':
        terminal = true
        handlers.onDone?.(obj as unknown as MessageDto)
        break
      case 'error':
        terminal = true
        handlers.onError(String(obj.message ?? '오류'), obj.kind === undefined ? undefined : String(obj.kind))
        break
      default:
        break
    }
  }

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let sep: number
      while ((sep = buffer.search(/\r?\n\r?\n/)) >= 0) {
        const chunk = buffer.slice(0, sep)
        buffer = buffer.slice(sep).replace(/^\r?\n\r?\n/, '')
        dispatch(chunk)
      }
    }
    if (buffer.trim()) dispatch(buffer)
    if (!terminal && !signal?.aborted) {
      handlers.onError('응답이 완료되기 전에 연결이 끊겼습니다.', 'STREAM_INTERRUPTED')
    }
  } catch {
    if (!signal?.aborted) handlers.onError('응답 스트리밍이 중단되었습니다.', 'STREAM_INTERRUPTED')
  }
}
