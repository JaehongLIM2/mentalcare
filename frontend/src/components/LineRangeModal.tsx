import { useMemo, useState } from 'react'
import { latestFittingStart, lineRangeText, rangeFileName, type LineChat } from '../util/lineChat'
import type { PendingFile } from '../util/attachments'

interface Props {
  name: string
  chat: LineChat
  /** Characters still free in this message (limit minus files already attached) */
  budget: number
  onCancel: () => void
  onAttach: (file: PendingFile) => void
}

/** Picks a date range of a LINE chat export that is too long to attach whole. */
export default function LineRangeModal({ name, chat, budget, onCancel, onAttach }: Props) {
  const last = chat.days.length - 1
  const [dropNoText, setDropNoText] = useState(true)
  const [from, setFrom] = useState(() => Math.max(0, latestFittingStart(chat, budget, true)))
  const [to, setTo] = useState(last)
  const text = useMemo(() => lineRangeText(chat, from, to, dropNoText), [chat, from, to, dropNoText])
  const over = text.length > budget

  const fitLatest = () => {
    const start = latestFittingStart(chat, budget, dropNoText)
    setTo(last)
    setFrom(start < 0 ? last : start)
  }
  const day = (i: number) => chat.days[i].date

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="line-range-title">
      <div className="modal">
        <h2 id="line-range-title">LINE 대화에서 기간 고르기</h2>
        <div className="small">
          <p>
            <b>{name}</b>은(는) {day(0)} ~ {day(last)}, {chat.days.length}일 치 대화라서 한 번에 첨부할 수 있는 양
            ({budget.toLocaleString()}자)을 넘습니다. 이번 상담에서 이야기할 기간만 골라 주세요.
          </p>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <label>
              시작{' '}
              <select value={from} onChange={(e) => { const v = Number(e.target.value); setFrom(v); if (v > to) setTo(v) }}>
                {chat.days.map((d, i) => <option key={d.date + i} value={i}>{d.date}</option>)}
              </select>
            </label>
            <label>
              끝{' '}
              <select value={to} onChange={(e) => { const v = Number(e.target.value); setTo(v); if (v < from) setFrom(v) }}>
                {chat.days.map((d, i) => <option key={d.date + i} value={i}>{d.date}</option>)}
              </select>
            </label>
            <button className="btn sm" onClick={fitLatest}>한도 안에서 최근 기간</button>
          </div>
          <label style={{ display: 'block', marginTop: 8 }}>
            <input type="checkbox" checked={dropNoText} onChange={(e) => setDropNoText(e.target.checked)} />{' '}
            사진·스티커·취소된 메시지처럼 글이 없는 줄 빼기
          </label>
          <p style={{ color: over ? 'var(--danger)' : undefined }}>
            {to - from + 1}일, {text.length.toLocaleString()}자 / {budget.toLocaleString()}자
            {over && ' — 기간을 줄여 주세요.'}
          </p>
          <p className="muted">고른 기간의 원문만 첨부되고 원래 파일은 바뀌지 않습니다.</p>
        </div>
        <div className="row" style={{ justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn" onClick={onCancel} autoFocus>취소</button>
          <button className="btn primary" disabled={over || budget <= 0}
            onClick={() => onAttach({ name: rangeFileName(name, chat, from, to), content: text, chars: text.length })}>
            이 기간 첨부
          </button>
        </div>
      </div>
    </div>
  )
}
