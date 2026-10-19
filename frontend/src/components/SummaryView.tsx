import type { SummaryDto } from '../api/types'
import { CATEGORY_LABEL, fmtDateTime } from '../util/format'

interface Props {
  summary: SummaryDto
  tags: string[]
}

function Section({ title, items, interpretation }: { title: string; items: string[]; interpretation?: boolean }) {
  return (
    <div className={'summary-section' + (interpretation ? ' interpretation' : '')}>
      <h3>
        {title}
        {interpretation && <span className="badge ai">AI 해석</span>}
      </h3>
      {items.length === 0 ? (
        <div className="muted small">(없음)</div>
      ) : (
        <ul>
          {items.map((it, i) => (
            <li key={i}>{it}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Structured summary. Sections that hold the model's interpretation are visually marked. */
export default function SummaryView({ summary, tags }: Props) {
  const o = summary.output
  return (
    <div>
      <div className="muted small" style={{ marginBottom: 14 }}>
        요약 생성: {fmtDateTime(summary.createdAt)} · 모델 {summary.model}
        {summary.markdownPath && (
          <>
            <br />
            Markdown: <code>{summary.markdownPath}</code>
          </>
        )}
      </div>
      <Section title="이번 상담의 주요 주제" items={o.topics} />
      <Section title="이야기한 내용 (사용자가 말한 사실)" items={o.keyEvents} />
      <Section title="주요 감정" items={o.emotions} />
      <Section title="사용자의 생각" items={o.userThoughts} />
      <Section title="상담에서 관찰된 가능성 있는 패턴" items={o.observedPatterns} interpretation />
      <Section title="새롭게 발견된 점" items={o.discoveries} interpretation />
      <Section title="이번 주에 해볼 것" items={o.actionItems} />
      <Section title="다음 상담에서 확인할 것" items={o.nextSessionTopics} />

      <div className="summary-section interpretation">
        <h3>
          장기 기억 후보 <span className="badge ai">AI 제안</span>
        </h3>
        {o.memoryCandidates.length === 0 ? (
          <div className="muted small">(없음)</div>
        ) : (
          <ul>
            {o.memoryCandidates.map((c, i) => (
              <li key={i}>
                <span className={'badge ' + (c.kind === 'USER_STATED' ? 'user' : 'ai')}>
                  {c.kind === 'USER_STATED' ? '사용자가 말함' : 'AI 관찰'}
                </span>{' '}
                <span className="muted small">[{CATEGORY_LABEL[c.category] ?? c.category}]</span> {c.content}
                {c.reason && <div className="muted small">이유: {c.reason}</div>}
              </li>
            ))}
          </ul>
        )}
        <div className="muted small" style={{ marginTop: 6 }}>
          후보는 <a href="/memory">기억</a> 화면에서 확인·수정·보관할 수 있습니다.
        </div>
      </div>

      <div className="summary-section">
        <h3>Tags</h3>
        {tags.length === 0 ? <span className="muted small">(없음)</span> : tags.map((t) => <span className="tag" key={t}>{t}</span>)}
      </div>
    </div>
  )
}
