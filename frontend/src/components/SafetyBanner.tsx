import type { RiskLevel } from '../api/types'

interface Props {
  level: RiskLevel | null
  crisisFlag: boolean
}

/** Shown when the safety layer raised a concern. Resources are listed plainly; no judgement. */
export default function SafetyBanner({ level, crisisFlag }: Props) {
  if (!crisisFlag && (level === null || level === 'NONE' || level === 'LOW')) return null
  const imminent = level === 'IMMINENT'
  return (
    <div className={'alert ' + (imminent ? 'error' : 'warn')} role="status">
      <strong>{imminent ? '지금 당신의 안전이 가장 중요합니다.' : '힘든 시간을 보내고 계신 것 같습니다.'}</strong>
      <div className="small" style={{ marginTop: 6 }}>
        이 앱은 긴급 상황을 대신할 수 없습니다. 지금 위험하다고 느껴지면 가까운 사람에게 연락하거나 아래 번호로 전화하세요.
        <br />
        한국: 자살예방상담전화 <b>109</b> · 정신건강위기상담 <b>1577-0199</b> · 긴급 <b>112 / 119</b>
        <br />
        日本: いのちの電話 <b>0570-783-556</b> · よりそいホットライン <b>0120-279-338</b> · 緊急 <b>110 / 119</b>
      </div>
    </div>
  )
}
