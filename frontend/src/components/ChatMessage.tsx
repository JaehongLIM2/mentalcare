import { useState, type ReactNode } from 'react'
import type { AttachmentDto, MessageDto } from '../api/types'
import { fmtTime } from '../util/format'

interface Props {
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt?: string
  status?: MessageDto['status']
  streaming?: boolean
  safetyUnavailable?: boolean | null
  attachments?: AttachmentDto[] | null
}

/** An attached file: name and size; the text opens in place (plain text, never HTML). */
function AttachmentChip({ file }: { file: AttachmentDto }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="attachment">
      <button className="attachment-chip" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        📎 {file.name} <span className="muted">({file.charCount.toLocaleString()}자)</span> {open ? '접기' : '보기'}
      </button>
      {open && <pre className="attachment-text">{file.content}</pre>}
    </div>
  )
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

export default function ChatMessage({ role, content, createdAt, status, streaming, safetyUnavailable, attachments }: Props) {
  const cls = role === 'USER' ? 'msg user' : 'msg assistant'
  return (
    <div className={cls}>
      {(content || !attachments?.length) && (
        <div className={'bubble' + (streaming ? ' cursor' : '')}>{role === 'ASSISTANT' ? withBold(content) : content}</div>
      )}
      {attachments?.map((a) => <AttachmentChip key={a.id} file={a} />)}
      <div className="meta">
        {role === 'USER' ? '나' : '상담자'}
        {createdAt ? ` · ${fmtTime(createdAt)}` : ''}
        {status === 'INTERRUPTED' ? ' · 응답이 중간에 끊김' : ''}
        {safetyUnavailable ? ' · 안전 검사 못 함' : ''}
      </div>
    </div>
  )
}
