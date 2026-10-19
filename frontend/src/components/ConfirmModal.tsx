import type { ReactNode } from 'react'

interface Props {
  title: string
  children: ReactNode
  confirmLabel: string
  busyLabel?: string
  danger?: boolean
  busy?: boolean
  disabled?: boolean
  onCancel: () => void
  onConfirm: () => void
}

/** Generic confirmation dialog for actions that are hard to undo but are not deletions (restore, quit). */
export default function ConfirmModal({ title, children, confirmLabel, busyLabel, danger, busy, disabled, onCancel, onConfirm }: Props) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="confirm-modal-title">
      <div className="modal">
        <h2 id="confirm-modal-title" style={danger ? { color: 'var(--danger)' } : undefined}>{title}</h2>
        <div className="small">{children}</div>
        <div className="row" style={{ justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn" onClick={onCancel} disabled={busy} autoFocus>
            취소
          </button>
          <button className={'btn ' + (danger ? 'danger' : 'primary')} onClick={onConfirm} disabled={busy || disabled}>
            {busy ? busyLabel ?? '처리 중…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
