import type { ReactNode } from 'react'
import type { MessageDto } from '../api/types'
import { fmtTime } from '../util/format'

interface Props {
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt?: string
  status?: MessageDto['status']
  streaming?: boolean
}

/**
 * The counselor sometimes emphasises with Markdown **bold**. Only that one construct is turned into <strong>;
 * everything stays a React text node, so no markup from the model is ever interpreted.
 */
export function withBold(text: string): ReactNode[] {
  const out: ReactNode[] = []
  const re = /\*\*([^*\n][^*]*?)\*\*/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) out.push(text.slice(last, m.index))
    out.push(<strong key={m.index}>{m[1]}</strong>)
    last = m.index + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

export default function ChatMessage({ role, content, createdAt, status, streaming }: Props) {
  const cls = role === 'USER' ? 'msg user' : 'msg assistant'
  return (
    <div className={cls}>
      <div className={'bubble' + (streaming ? ' cursor' : '')}>{role === 'ASSISTANT' ? withBold(content) : content}</div>
      <div className="meta">
        {role === 'USER' ? '나' : '상담자'}
        {createdAt ? ` · ${fmtTime(createdAt)}` : ''}
        {status === 'INTERRUPTED' ? ' · 응답이 중간에 끊김' : ''}
      </div>
    </div>
  )
}
