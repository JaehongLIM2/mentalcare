import { useEffect, useState } from 'react'
import { api, backupApi, errorMessage, importApi } from '../api/client'
import type {
  BackupInspection,
  DeletionResult,
  ImportPreview,
  ImportResult,
  ModelCatalog,
  RestoreResult,
  SettingsDto,
  SystemStatus,
} from '../api/types'
import ChatGptConnect from '../components/ChatGptConnect'
import ConfirmDeleteModal from '../components/ConfirmDeleteModal'
import ConfirmModal from '../components/ConfirmModal'
import { SHUTDOWN_EVENT } from '../components/ServerStatus'
import { fmtDateTime } from '../util/format'

function fmtUptime(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return h > 0 ? `${h}시간 ${m}분` : `${m}분`
}

export default function SettingsPage() {
  const [settings, setSettings] = useState<SettingsDto | null>(null)
  const [catalog, setCatalog] = useState<ModelCatalog | null>(null)
  const [form, setForm] = useState({
    counselingReasoningEffort: '',
    recentSessionCount: 3,
    profileSummary: '',
    goals: '',
    counselingModel: '',
    utilityModel: '',
  })
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmWipe, setConfirmWipe] = useState(false)
  const [wiping, setWiping] = useState(false)
  const [wipeResult, setWipeResult] = useState<DeletionResult | null>(null)
  const [importFile, setImportFile] = useState<File | null>(null)
  const [importPreview, setImportPreview] = useState<ImportPreview | null>(null)
  const [importResult, setImportResult] = useState<ImportResult | null>(null)
  const [importing, setImporting] = useState(false)

  // backup & restore
  const [backupCreds, setBackupCreds] = useState(false)
  const [backingUp, setBackingUp] = useState(false)
  const [backupNotice, setBackupNotice] = useState<string | null>(null)
  const [restoreFile, setRestoreFile] = useState<File | null>(null)
  const [inspection, setInspection] = useState<BackupInspection | null>(null)
  const [restoreCreds, setRestoreCreds] = useState(false)
  const [confirmRestore, setConfirmRestore] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [restoreResult, setRestoreResult] = useState<RestoreResult | null>(null)

  // app status & quit
  const [system, setSystem] = useState<SystemStatus | null>(null)
  const [confirmQuit, setConfirmQuit] = useState(false)
  const [quitting, setQuitting] = useState(false)
  const [stopped, setStopped] = useState(false)

  const apply = (s: SettingsDto) => {
    setSettings(s)
    setForm({
      counselingReasoningEffort: s.counselingReasoningEffort,
      recentSessionCount: s.recentSessionCount,
      profileSummary: s.profileSummary,
      goals: s.goals,
      counselingModel: s.counselingModel,
      utilityModel: s.utilityModel,
    })
  }

  const loadAll = (refreshModels = false) => {
    api.settings().then(apply).catch((e) => setError(errorMessage(e)))
    api.models(refreshModels).then(setCatalog).catch(() => setCatalog(null))
    api.system.status().then(setSystem).catch(() => setSystem(null))
  }

  useEffect(() => {
    loadAll(false)
    if (window.location.hash === '#app-status') {
      window.setTimeout(() => document.getElementById('app-status')?.scrollIntoView({ behavior: 'smooth' }), 200)
    }
  }, [])

  const save = async () => {
    setBusy(true)
    setSaved(false)
    setError(null)
    try {
      apply(await api.updateSettings(form))
      setSaved(true)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const pickImport = async (file: File | null) => {
    setImportFile(file)
    setImportPreview(null)
    setImportResult(null)
    if (!file) return
    setImporting(true)
    try {
      setImportPreview(await importApi.preview(file))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setImporting(false)
    }
  }

  const runImport = async () => {
    if (!importFile) return
    setImporting(true)
    setError(null)
    try {
      setImportResult(await importApi.importArchive(importFile))
      setImportPreview(null)
      setImportFile(null)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setImporting(false)
    }
  }

  const wipeAll = async () => {
    setWiping(true)
    setError(null)
    try {
      setWipeResult(await api.deleteAllData())
      setConfirmWipe(false)
      loadAll(false)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setWiping(false)
    }
  }

  const downloadBackup = async () => {
    setBackingUp(true)
    setError(null)
    setBackupNotice(null)
    try {
      const name = await backupApi.download(backupCreds)
      setBackupNotice(`백업 파일 ${name} 을(를) 내려받았습니다. 다른 저장 장치나 클라우드에 옮겨 두세요.`)
      api.settings().then(apply).catch(() => {})
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBackingUp(false)
    }
  }

  const pickRestore = async (file: File | null) => {
    setRestoreFile(file)
    setInspection(null)
    setRestoreResult(null)
    setRestoreCreds(false)
    if (!file) return
    setRestoring(true)
    setError(null)
    try {
      setInspection(await backupApi.inspect(file))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setRestoring(false)
    }
  }

  const runRestore = async () => {
    if (!restoreFile) return
    setRestoring(true)
    setError(null)
    try {
      setRestoreResult(await backupApi.restore(restoreFile, restoreCreds))
      setConfirmRestore(false)
      setRestoreFile(null)
      setInspection(null)
      loadAll(true)
    } catch (e) {
      setError(errorMessage(e))
      setConfirmRestore(false)
    } finally {
      setRestoring(false)
    }
  }

  const quit = async () => {
    setQuitting(true)
    setError(null)
    try {
      await api.system.shutdown()
      setStopped(true)
      setConfirmQuit(false)
      window.dispatchEvent(new Event(SHUTDOWN_EVENT))
    } catch (e) {
      setError(errorMessage(e))
      setConfirmQuit(false)
    } finally {
      setQuitting(false)
    }
  }

  if (stopped) {
    return (
      <div className="card overlay-note">
        <h1>앱이 종료되었습니다</h1>
        <p className="muted">
          서버가 정상적으로 멈췄고 상담 데이터는 모두 저장되어 있습니다. 이 브라우저 탭은 닫아도 됩니다.
        </p>
        <p className="muted small">
          다시 사용하려면 <b>MentalCare 시작.cmd</b>(Windows) 또는 <code>docker compose up</code>으로 실행하세요.
        </p>
      </div>
    )
  }

  if (error && !settings) return <div className="alert error">{error}</div>
  if (!settings) return <div className="muted">불러오는 중…</div>

  const totals = settings.usageLast30Days.reduce(
    (acc, u) => ({ in: acc.in + u.inputTokens, out: acc.out + u.outputTokens, n: acc.n + u.requests }),
    { in: 0, out: 0, n: 0 },
  )
  const models = catalog?.available ?? []
  const options = (current: string) => {
    const list = models.map((m) => ({ value: m.slug, label: m.displayName }))
    if (current && !list.some((o) => o.value === current)) list.unshift({ value: current, label: `${current} (목록에 없음)` })
    return list
  }

  return (
    <div>
      <div className="page-head">
        <h1>설정</h1>
        <p>인증은 ChatGPT 계정으로, 모델은 계정에서 사용 가능한 목록에서 고릅니다. 비밀 값은 이 화면에 표시되지 않습니다.</p>
      </div>

      {error && <div className="alert error">{error}</div>}
      {saved && <div className="alert info">저장되었습니다.</div>}

      <ChatGptConnect onChange={() => loadAll(true)} />

      <div className="card">
        <h2>프로필과 목표</h2>
        <label className="field">
          <span>나에 대한 짧은 소개 (상담자가 매번 참고합니다. 비워도 됩니다)</span>
          <textarea
            rows={3}
            value={form.profileSummary}
            onChange={(e) => setForm({ ...form, profileSummary: e.target.value })}
            placeholder="예: 직장인. 주로 업무 스트레스와 인간관계를 이야기하고 싶음."
          />
        </label>
        <label className="field">
          <span>현재 유지 중인 상담 목표 (한 줄에 하나)</span>
          <textarea rows={3} value={form.goals} onChange={(e) => setForm({ ...form, goals: e.target.value })} />
        </label>
      </div>

      <div className="card">
        <div className="spread">
          <h2>모델</h2>
          <button className="btn sm" onClick={() => loadAll(true)}>목록 새로고침</button>
        </div>
        {catalog?.catalogError && <div className="alert warn">{catalog.catalogError}</div>}
        {catalog && models.length === 0 && !catalog.catalogError && (
          <div className="muted small">이 계정에서 사용 가능한 모델 목록이 비어 있습니다. 기본 설정값이 사용됩니다.</div>
        )}
        <div className="grid-2">
          <label className="field">
            <span>상담 모델 (기본 {settings.defaultCounselingModel})</span>
            <select value={form.counselingModel} onChange={(e) => setForm({ ...form, counselingModel: e.target.value })}>
              {options(form.counselingModel).map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>요약·정리 모델 (기본 {settings.defaultUtilityModel})</span>
            <select value={form.utilityModel} onChange={(e) => setForm({ ...form, utilityModel: e.target.value })}>
              {options(form.utilityModel).map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </label>
        </div>
        <div className="muted small">
          {catalog?.fromAccount
            ? '목록은 연결된 ChatGPT 계정의 /v1/models 응답(visibility=list)에서 가져옵니다. 선택한 모델이 더 이상 제공되지 않으면 첫 번째 가능한 모델로 대체됩니다.'
            : '개발자 모드에서는 플랫폼 API의 모델 목록을 사용합니다.'}
        </div>
      </div>

      <div className="card">
        <h2>상담 동작</h2>
        <div className="grid-2">
          <label className="field">
            <span>상담 모델 reasoning effort (기본 {settings.defaultReasoningEffort})</span>
            <select
              value={form.counselingReasoningEffort}
              onChange={(e) => setForm({ ...form, counselingReasoningEffort: e.target.value })}
            >
              <option value="low">low</option>
              <option value="medium">medium</option>
              <option value="high">high</option>
            </select>
          </label>
          <label className="field">
            <span>컨텍스트에 포함할 최근 상담 요약 개수 (0~10)</span>
            <input
              type="number"
              min={0}
              max={10}
              value={form.recentSessionCount}
              onChange={(e) => setForm({ ...form, recentSessionCount: Number(e.target.value) })}
            />
          </label>
        </div>
        <button className="btn primary" onClick={save} disabled={busy}>{busy ? '저장 중…' : '저장'}</button>
      </div>

      <div className="card">
        <h2>백업과 복원</h2>
        <p className="muted small">
          백업 파일(zip) 하나에 상담 데이터베이스(대화·요약·기억·설정)와 Markdown 기록이 모두 들어갑니다.
          로그인 자격증명은 기본적으로 제외되며, 포함하더라도 암호화된 상태 그대로이고 같은 PC·같은 사용자 계정에서만 풀립니다.
        </p>
        <table className="plain">
          <tbody>
            <tr>
              <th>마지막 백업</th>
              <td>
                {settings.lastBackupAt ? fmtDateTime(settings.lastBackupAt) : <span style={{ color: 'var(--warn)' }}>아직 백업한 적이 없습니다</span>}
              </td>
            </tr>
            <tr><th>복원 전 자동 스냅샷</th><td><code>{settings.backupsDir}</code> (최근 5개 보관)</td></tr>
          </tbody>
        </table>
        <div className="row" style={{ marginTop: 10 }}>
          <button className="btn primary" onClick={downloadBackup} disabled={backingUp}>
            {backingUp ? '백업 만드는 중…' : '백업 파일 내려받기'}
          </button>
          <label className="small row" style={{ gap: 6 }}>
            <input type="checkbox" checked={backupCreds} onChange={(e) => setBackupCreds(e.target.checked)} />
            로그인 자격증명(암호화됨)도 포함
          </label>
        </div>
        {backupNotice && <div className="alert info" style={{ marginTop: 10 }}>{backupNotice}</div>}

        <h3 style={{ marginTop: 18 }}>백업에서 복원</h3>
        <p className="muted small">
          복원하면 현재 데이터가 백업 시점의 내용으로 <b>통째로 바뀝니다</b>. 복원 직전 상태는 자동 스냅샷으로 남으므로 다시 되돌릴 수 있습니다.
        </p>
        <input
          type="file"
          accept=".zip,application/zip"
          onChange={(e) => void pickRestore(e.target.files?.[0] ?? null)}
          disabled={restoring}
        />
        {inspection && restoreFile && (
          <div className="card soft" style={{ marginTop: 12 }}>
            <table className="plain">
              <tbody>
                <tr><th>백업 시점</th><td>{inspection.createdAt ? fmtDateTime(inspection.createdAt) : '알 수 없음'}{inspection.appVersion && ` · 앱 ${inspection.appVersion}`}</td></tr>
                <tr><th>상담 / 메시지 / 기억</th><td>{inspection.sessions} / {inspection.messages} / {inspection.memories}</td></tr>
                <tr><th>Markdown 파일</th><td>{inspection.markdownFiles}개</td></tr>
                <tr><th>자격증명</th><td>{inspection.includesCredentials ? '포함됨' : '없음'}</td></tr>
              </tbody>
            </table>
            {inspection.warnings.length > 0 && (
              <ul className="small" style={{ color: 'var(--warn)' }}>
                {inspection.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            )}
            {inspection.includesCredentials && (
              <label className="small row" style={{ gap: 6, marginTop: 6 }}>
                <input type="checkbox" checked={restoreCreds} onChange={(e) => setRestoreCreds(e.target.checked)} />
                로그인 자격증명도 복원 (같은 PC·같은 계정일 때만 동작)
              </label>
            )}
            <button className="btn danger" onClick={() => setConfirmRestore(true)} disabled={restoring} style={{ marginTop: 8 }}>
              이 백업으로 복원…
            </button>
          </div>
        )}
        {restoreResult && (
          <div className="alert info" style={{ marginTop: 10 }}>
            상담 {restoreResult.sessionsRestored}건과 Markdown 파일 {restoreResult.markdownFilesRestored}개를 복원했습니다.
            복원 전 상태는 <code>{restoreResult.preRestoreBackup}</code>으로 남겨 두었습니다.
            {restoreResult.credentialsRestored && ' 로그인 자격증명도 복원했습니다.'}
            {restoreResult.warnings.length > 0 && (
              <ul className="small" style={{ marginTop: 6 }}>
                {restoreResult.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            )}
          </div>
        )}
      </div>

      {confirmRestore && inspection && (
        <ConfirmModal
          title="백업으로 되돌릴까요?"
          confirmLabel="복원"
          busyLabel="복원하는 중…"
          danger
          busy={restoring}
          onCancel={() => setConfirmRestore(false)}
          onConfirm={runRestore}
        >
          <p>
            지금 있는 모든 상담 기록·기억·설정·Markdown 파일이{' '}
            <b>{inspection.createdAt ? fmtDateTime(inspection.createdAt) : '백업'} 시점의 내용(상담 {inspection.sessions}건)</b>으로 바뀝니다.
          </p>
          <p>복원 직전 상태는 자동으로 스냅샷에 저장되어 되돌릴 수 있습니다. 진행 중인 응답 생성이 있으면 거부됩니다.</p>
        </ConfirmModal>
      )}

      <div className="card soft" id="app-status">
        <h2>앱 실행 상태</h2>
        <p className="muted small">
          이 앱은 브라우저가 아니라 이 PC에서 돌아가는 작은 서버입니다. 브라우저 탭을 닫아도 서버는 계속 실행되며, 아래 버튼이나
          <b> MentalCare 종료.cmd</b>로 끌 수 있습니다. 왼쪽 메뉴 아래의 표시로 실행 여부를 항상 확인할 수 있습니다.
        </p>
        {system ? (
          <table className="plain">
            <tbody>
              <tr><th>상태</th><td><span className="badge active">실행 중</span>{system.busySessions > 0 && <span className="badge ai" style={{ marginLeft: 6 }}>응답 생성 중 {system.busySessions}</span>}</td></tr>
              <tr><th>시작 시각</th><td>{fmtDateTime(system.startedAt)} (가동 {fmtUptime(system.uptimeSeconds)})</td></tr>
              <tr><th>주소</th><td><code>http://127.0.0.1:{system.port}</code> · PID {system.pid} · 버전 {system.version}{system.docker ? ' · Docker' : ''}</td></tr>
            </tbody>
          </table>
        ) : (
          <div className="muted small">상태를 가져오지 못했습니다.</div>
        )}
        <button className="btn danger" onClick={() => setConfirmQuit(true)} disabled={!system} style={{ marginTop: 10 }}>
          앱 종료
        </button>
      </div>

      {confirmQuit && (
        <ConfirmModal
          title="앱을 종료할까요?"
          confirmLabel="종료"
          busyLabel="종료하는 중…"
          busy={quitting}
          disabled={(system?.busySessions ?? 0) > 0}
          onCancel={() => setConfirmQuit(false)}
          onConfirm={quit}
        >
          <p>서버가 멈추고 이 화면은 더 이상 동작하지 않습니다. 저장된 상담 데이터는 그대로 남습니다.</p>
          {(system?.busySessions ?? 0) > 0 ? (
            <p style={{ color: 'var(--danger)' }}>답변 생성이 진행 중입니다. 끝난 뒤 종료하세요.</p>
          ) : (
            <p className="muted">다시 시작하려면 MentalCare 시작.cmd 또는 docker compose up 을 실행하세요.</p>
          )}
        </ConfirmModal>
      )}

      <div className="card soft">
        <h2>구성 (읽기 전용)</h2>
        <table className="plain">
          <tbody>
            <tr><th>인증 방식</th><td>{settings.authMode === 'CHATGPT' ? 'Sign in with ChatGPT (ChatGPT 플랜 사용)' : 'API 키 (개발자 모드)'}</td></tr>
            <tr><th>AI 사용 가능</th><td>{settings.aiReady ? '예' : <span style={{ color: 'var(--danger)' }}>아니오 — {settings.aiUnavailableReason}</span>}</td></tr>
            <tr><th>Moderation 모델</th><td>{settings.moderationModel}</td></tr>
            <tr><th>데이터 폴더 (SQLite)</th><td><code>{settings.dataDir}</code></td></tr>
            <tr><th>Markdown 폴더</th><td><code>{settings.markdownDir}</code></td></tr>
            <tr><th>자격 증명 폴더</th><td><code>{settings.credentialsDir}</code> · {settings.credentialProvider}</td></tr>
          </tbody>
        </table>
        <div className="muted small" style={{ marginTop: 8 }}>
          모든 OpenAI 요청은 <code>store: false</code>로 전송되며, 상담 원문과 토큰은 로그에 기록되지 않습니다.
        </div>
      </div>

      <div className="card">
        <h2>다른 상담 도구의 기록 가져오기</h2>
        <p className="muted small">
          ChatGPT 등에서 "상담 이관 기록" 템플릿으로 만든 Markdown 파일을 올리면, 완료된 상담 한 건(대화 없음)과 기억 후보로 들어옵니다.
          핵심 맥락은 이후 상담의 참고 자료로 전달되고, 기억 후보는 기억 화면에서 직접 확정해야 상담에 반영됩니다. AI 호출은 없습니다.
        </p>
        <input
          type="file"
          accept=".md,.txt,text/markdown,text/plain"
          onChange={(e) => void pickImport(e.target.files?.[0] ?? null)}
          disabled={importing}
        />
        {importPreview && (
          <div className="card soft" style={{ marginTop: 12 }}>
            <h3>{importPreview.title ?? '(제목 없음)'}</h3>
            <table className="plain">
              <tbody>
                <tr><th>상담 시점</th><td>{importPreview.counseledAt ?? '알 수 없음 (오늘 날짜로 저장)'}</td></tr>
                <tr><th>주요 주제</th><td>{importPreview.mainTopics ?? '-'}</td></tr>
                <tr><th>사실 / 사건 / 관계 / 고민</th><td>{importPreview.factCount} / {importPreview.eventCount} / {importPreview.relationshipCount} / {importPreview.concernCount}</td></tr>
                <tr><th>AI 관찰 패턴</th><td>{importPreview.aiPatternCount}</td></tr>
                <tr><th>기억 후보</th><td>{importPreview.memoryCandidateCount}</td></tr>
                <tr><th>핵심 맥락</th><td>{importPreview.coreContextChars.toLocaleString()}자</td></tr>
              </tbody>
            </table>
            {importPreview.alreadyImportedSessionId && (
              <div className="alert warn">
                같은 내용의 파일이 이미 상담 #{importPreview.alreadyImportedSessionId}로 들어와 있습니다. 다시 가져오면 거부됩니다.
              </div>
            )}
            {importPreview.warnings.length > 0 && (
              <ul className="small" style={{ color: 'var(--warn)' }}>
                {importPreview.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            )}
            <button className="btn primary" onClick={runImport} disabled={importing || !!importPreview.alreadyImportedSessionId} style={{ marginTop: 8 }}>
              {importing ? '가져오는 중…' : '가져오기'}
            </button>
          </div>
        )}
        {importResult && (
          <div className="alert info">
            {importResult.date} "{importResult.title}" 기록을 가져왔습니다. 기억 후보 {importResult.memoriesCreated}개 생성,{' '}
            {importResult.memoriesMerged}개 병합. <a href={`/history/${importResult.sessionId}`}>기록 보기</a> ·{' '}
            <a href="/memory">기억 후보 검토</a>
          </div>
        )}
      </div>

      <div className="card" style={{ borderColor: 'var(--danger)' }}>
        <h2 style={{ color: 'var(--danger)' }}>데이터 삭제</h2>
        <p className="muted small">
          모든 상담 기록(대화 원문, 체크인, 요약, 기억, 태그, 안전 기록, 초안, Markdown 파일)을 영구 삭제합니다. 프로필·목표·모델 설정과 ChatGPT
          연결은 유지됩니다. 개별 상담은 상담 상세 화면에서 삭제할 수 있습니다. 삭제 전에 위에서 백업을 먼저 내려받아 두세요.
        </p>
        {wipeResult && (
          <div className="alert info">
            상담 {wipeResult.sessionsDeleted}건과 Markdown 파일 {wipeResult.filesDeleted}개를 삭제했습니다.
            {wipeResult.filesFailed > 0 && ` 파일 ${wipeResult.filesFailed}개는 삭제하지 못했습니다.`}
          </div>
        )}
        <button className="btn danger" onClick={() => setConfirmWipe(true)}>모든 상담 데이터 삭제</button>
      </div>

      {confirmWipe && (
        <ConfirmDeleteModal
          title="모든 상담 데이터를 삭제할까요?"
          details={[
            '모든 상담의 대화 원문, 체크인, AI 요약, 태그, 안전 기록, 작성 중 초안',
            '모든 장기 기억(후보·확정·보관)과 API 사용량 기록',
            `Markdown 기록 파일 (${settings.markdownDir})`,
            settings.lastBackupAt ? `마지막 백업: ${fmtDateTime(settings.lastBackupAt)}` : '백업을 내려받은 적이 없습니다',
          ]}
          typeToConfirm="삭제"
          busy={wiping}
          onCancel={() => setConfirmWipe(false)}
          onConfirm={wipeAll}
        />
      )}

      <div className="card soft">
        <h2>최근 30일 API 사용량</h2>
        {settings.usageLast30Days.length === 0 ? (
          <div className="muted small">기록된 요청이 없습니다.</div>
        ) : (
          <table className="plain">
            <thead>
              <tr><th>모델</th><th>종류</th><th style={{ textAlign: 'right' }}>요청</th><th style={{ textAlign: 'right' }}>입력 토큰</th><th style={{ textAlign: 'right' }}>출력 토큰</th></tr>
            </thead>
            <tbody>
              {settings.usageLast30Days.map((u, i) => (
                <tr key={i}>
                  <td>{u.model}</td><td>{u.requestType}</td>
                  <td className="num">{u.requests}</td>
                  <td className="num">{u.inputTokens.toLocaleString()}</td>
                  <td className="num">{u.outputTokens.toLocaleString()}</td>
                </tr>
              ))}
              <tr>
                <td colSpan={2}><b>합계</b></td>
                <td className="num"><b>{totals.n}</b></td>
                <td className="num"><b>{totals.in.toLocaleString()}</b></td>
                <td className="num"><b>{totals.out.toLocaleString()}</b></td>
              </tr>
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
