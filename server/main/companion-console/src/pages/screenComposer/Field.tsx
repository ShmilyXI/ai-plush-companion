import type { ReactNode } from 'react'
import { Typography } from 'antd'

/** One labelled setting: fixed-width label column (with optional hint) and the control beside it. */
export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <div className="screen-composer-field">
    <div className="screen-composer-field-label">
      <Typography.Text>{label}</Typography.Text>
      {hint && <Typography.Text type="secondary" className="screen-composer-hint">{hint}</Typography.Text>}
    </div>
    <div className="screen-composer-field-control">{children}</div>
  </div>
}

export function SectionTitle({ children, extra }: { children: ReactNode; extra?: ReactNode }) {
  return <div className="screen-composer-section-title"><Typography.Text strong>{children}</Typography.Text>{extra}</div>
}
