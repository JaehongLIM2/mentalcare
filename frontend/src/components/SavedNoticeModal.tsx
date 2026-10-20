import { useEffect } from 'react'
import type { CompletionDto } from '../api/types'

interface Props {
  completion: CompletionDto
  onClose: () => void
}

/** Shown once after a session is completed. Closes on a click anywhere outside the box, Escape, or the × button. */
export default function SavedNoticeModal({ completion, onClose }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="saved-notice-title" onClick={onClose}>
      <div className="modal saved-notice" onClick={(e) => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose} aria-label="닫기">
          ×
        </button>
        <h2 id="saved-notice-title">상담이 저장되었습니다</h2>
        <div className="small">
          기억 후보 {completion.memoriesCreated}개 생성, {completion.memoriesMerged}개 병합, {completion.memoriesActivated}개 확정.
        </div>
        {completion.warning && <div className="alert info" style={{ marginTop: 10, marginBottom: 0 }}>{completion.warning}</div>}
        <div className="small muted" style={{ marginTop: 12 }}>
          아무 곳이나 누르면 닫힙니다.
        </div>
      </div>
    </div>
  )
}
