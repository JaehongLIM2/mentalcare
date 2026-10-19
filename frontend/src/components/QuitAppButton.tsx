import { useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { SystemStatus } from '../api/types'
import ConfirmModal from './ConfirmModal'
import { SHUTDOWN_EVENT } from './ServerStatus'

interface Props {
  className?: string
  label?: string
}

/**
 * "앱 종료" with a confirmation. Used in the sidebar and on the settings page. On success it fires
 * SHUTDOWN_EVENT; the layout then replaces the page with the "app stopped" note.
 */
export default function QuitAppButton({ className = 'btn danger', label = '앱 종료' }: Props) {
  const [open, setOpen] = useState(false)
  const [system, setSystem] = useState<SystemStatus | null>(null)
  const [quitting, setQuitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const show = () => {
    setError(null)
    setOpen(true)
    // fresh status so a reply being generated right now blocks the quit
    api.system.status().then(setSystem).catch(() => setSystem(null))
  }

  const quit = async () => {
    setQuitting(true)
    setError(null)
    try {
      await api.system.shutdown()
      setOpen(false)
      window.dispatchEvent(new Event(SHUTDOWN_EVENT))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setQuitting(false)
    }
  }

  const busy = (system?.busySessions ?? 0) > 0
  return (
    <>
      <button className={className} onClick={show}>{label}</button>
      {open && (
        <ConfirmModal
          title="앱을 종료할까요?"
          confirmLabel="종료"
          busyLabel="종료하는 중…"
          busy={quitting}
          disabled={busy}
          onCancel={() => setOpen(false)}
          onConfirm={quit}
        >
          <p>서버가 멈추고 이 화면은 더 이상 동작하지 않습니다. 저장된 상담 데이터는 그대로 남습니다.</p>
          {busy ? (
            <p style={{ color: 'var(--danger)' }}>답변 생성이 진행 중입니다. 끝난 뒤 종료하세요.</p>
          ) : (
            <p className="muted">다시 시작하려면 MentalCare 시작.cmd 또는 docker compose up 을 실행하세요.</p>
          )}
          {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
        </ConfirmModal>
      )}
    </>
  )
}
