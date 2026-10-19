import { useState } from 'react'

interface Props {
  title: string
  /** What exactly will be removed. */
  details: string[]
  /** When set, the user must type this word before the button enables. */
  typeToConfirm?: string
  busy?: boolean
  onCancel: () => void
  onConfirm: () => void
}

/** Destructive-action dialog: states irreversibility plainly and never has delete as the default/focused action. */
export default function ConfirmDeleteModal({ title, details, typeToConfirm, busy, onCancel, onConfirm }: Props) {
  const [typed, setTyped] = useState('')
  const ready = !typeToConfirm || typed.trim() === typeToConfirm
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
      <div className="modal">
        <h2 id="confirm-title" style={{ color: 'var(--danger)' }}>{title}</h2>
        <p>
          <b>삭제된 데이터는 복구할 수 없습니다.</b> 백업이 없다면 영구적으로 사라집니다.
        </p>
        <ul className="small">
          {details.map((d, i) => (
            <li key={i}>{d}</li>
          ))}
        </ul>
        {typeToConfirm && (
          <label className="field">
            <span>계속하려면 <b>{typeToConfirm}</b> 를 입력하세요</span>
            <input type="text" value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus />
          </label>
        )}
        <div className="row" style={{ justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn" onClick={onCancel} disabled={busy} autoFocus={!typeToConfirm}>
            취소
          </button>
          <button className="btn danger" onClick={onConfirm} disabled={!ready || busy}>
            {busy ? '삭제하는 중…' : '영구 삭제'}
          </button>
        </div>
      </div>
    </div>
  )
}
