'use client'

import { useEffect, useState } from 'react'
import { managerJson } from '../lib/manager'

interface RoleTemplate { id: string; name: string }

/** 新账号还没有角色时，从预设里选一个开始。创建出来的角色直接可以对话。 */
export default function RolePicker({ onCreated, onSignOut }: { onCreated: () => void; onSignOut: () => void }) {
  const [templates, setTemplates] = useState<RoleTemplate[] | null>(null)
  const [creating, setCreating] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    managerJson<RoleTemplate[]>('/role-templates')
      .then((items) => { if (active) setTemplates(Array.isArray(items) ? items : []) })
      .catch((reason: unknown) => {
        if (!active) return
        setTemplates([])
        setError(reason instanceof Error ? reason.message : '角色加载失败')
      })
    return () => { active = false }
  }, [])

  async function choose(template: RoleTemplate) {
    setCreating(template.id); setError('')
    try {
      await managerJson('/roles', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ templateId: template.id, name: template.name }),
      })
      onCreated()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '角色创建失败')
      setCreating('')
    }
  }

  return <main className="companion-shell login-shell">
    <section className="login-card">
      <p className="kicker">AI PLUSH COMPANION</p>
      <h1>选一个陪伴角色</h1>
      <p className="subtle">先选一个开始聊天，之后可以再换</p>
      {templates === null && <div className="loading-state compact">正在加载角色</div>}
      {templates !== null && templates.length === 0 && !error && <div className="notice-banner">暂时没有可选的角色，请稍后再来</div>}
      <div className="role-list">
        {(templates ?? []).map((template) => <button
          key={template.id} type="button" className="role-option" disabled={Boolean(creating)}
          onClick={() => void choose(template)}
        >
          <span className="role-avatar" aria-hidden="true">{template.name.slice(0, 1)}</span>
          <span className="role-name">{template.name}</span>
          <span className="role-action">{creating === template.id ? '正在准备' : '选择'}</span>
        </button>)}
      </div>
      {error && <div className="error-banner" role="alert">{error}</div>}
      <button type="button" className="link-button" onClick={onSignOut}>退出登录</button>
    </section>
  </main>
}
