import { useEffect, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { DeletionResult, ModelCatalog, SettingsDto } from '../api/types'
import ChatGptConnect from '../components/ChatGptConnect'
import ConfirmDeleteModal from '../components/ConfirmDeleteModal'

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
  }

  useEffect(() => {
    loadAll(false)
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

      <div className="card" style={{ borderColor: 'var(--danger)' }}>
        <h2 style={{ color: 'var(--danger)' }}>데이터 삭제</h2>
        <p className="muted small">
          모든 상담 기록(대화 원문, 체크인, 요약, 기억, 태그, 안전 기록, Markdown 파일)을 영구 삭제합니다. 프로필·목표·모델 설정과 ChatGPT
          연결은 유지됩니다. 개별 상담은 상담 상세 화면에서 삭제할 수 있습니다.
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
            '모든 상담의 대화 원문, 체크인, AI 요약, 태그, 안전 기록',
            '모든 장기 기억(후보·확정·보관)과 API 사용량 기록',
            `Markdown 기록 파일 (${settings.markdownDir})`,
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
