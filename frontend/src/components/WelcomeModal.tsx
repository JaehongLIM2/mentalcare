import { CHATGPT_USAGE_URL } from '../api/client'

interface Props {
  onClose: () => void
}

/** First-sign-in disclosure required by OpenAI's Sign in with ChatGPT UX guidelines. */
export default function WelcomeModal({ onClose }: Props) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="welcome-title">
      <div className="modal">
        <h2 id="welcome-title">You're using your ChatGPT plan</h2>
        <p>
          이 앱에서 보내는 AI 상담 요청은 당신의 ChatGPT 플랜 사용량으로 처리됩니다. 사용량은 ChatGPT 설정의{' '}
          <a href={CHATGPT_USAGE_URL} target="_blank" rel="noreferrer noopener">
            Usage
          </a>
          에서 관리할 수 있습니다.
        </p>
        <p className="muted small">
          mentalcare 자체는 요금을 청구하지 않으며, API 키 결제로 자동 전환하지 않습니다. 사용 한도에 도달하면 요청이 중단되고 안내가
          표시됩니다.
        </p>
        <button className="btn primary" onClick={onClose}>
          Got it
        </button>
      </div>
    </div>
  )
}
