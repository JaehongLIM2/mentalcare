import type { MessageDto } from '../api/types'
import { fmtTime } from '../util/format'

interface Props {
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt?: string
  status?: MessageDto['status']
  streaming?: boolean
}

export default function ChatMessage({ role, content, createdAt, status, streaming }: Props) {
  const cls = role === 'USER' ? 'msg user' : 'msg assistant'
  return (
    <div className={cls}>
      <div className={'bubble' + (streaming ? ' cursor' : '')}>{content}</div>
      <div className="meta">
        {role === 'USER' ? '나' : '상담자'}
        {createdAt ? ` · ${fmtTime(createdAt)}` : ''}
        {status === 'INTERRUPTED' ? ' · 응답이 중간에 끊김' : ''}
      </div>
    </div>
  )
}
