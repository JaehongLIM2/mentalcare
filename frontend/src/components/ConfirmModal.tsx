import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'

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

/**
 * Generic confirmation dialog for actions that are hard to undo but are not deletions (restore, quit).
 * Rendered into document.body: the quit button lives in the sticky sidebar, whose stacking context would
 * otherwise put the dialog underneath the page content.
 */
export default function ConfirmModal({ title, children, confirmLabel, busyLabel, danger, busy, disabled, onCancel, onConfirm }: Props) {
  return createPortal(
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
    </div>,
    document.body,
  )
}
