'use client'

import { useEffect, useState } from 'react'
import { managerJson } from '../lib/manager'

interface RoleTemplate { id: string; name: string; description?: string }

interface RolePickerProps {
  /** 参数是新角色的标识。 */
  onCreated: (id?: string) => void
  onSignOut: () => void
  /** 用户已经有的角色的名字。再添加角色时，这些不能重复选。 */
  existingNames?: string[]
  /** 已经有角色的用户来添加新角色时提供，用来回到对话。 */
  onBack?: () => void
}

/** 从预设里选一个角色。新账号必须先选一个才能开始；已经有角色的用户可以再添加别的。创建出来的角色直接可以对话。 */
export default function RolePicker({ onCreated, onSignOut, existingNames = [], onBack }: RolePickerProps) {
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
      const created = await managerJson<{ id?: string }>('/roles', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ templateId: template.id, name: template.name }),
      })
      onCreated(typeof created?.id === 'string' ? created.id : undefined)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '角色创建失败')
      setCreating('')
    }
  }

  return <main className="companion-shell login-shell">
    <section className="login-card">
      <p className="kicker">AI PLUSH COMPANION</p>
      <h1>{onBack ? '添加一个角色' : '选一个陪伴角色'}</h1>
      <p className="subtle">{onBack ? '每个角色有自己的性格和声音，聊天记录各自分开' : '先选一个开始聊天，之后可以再添加别的'}</p>
      {templates === null && <div className="loading-state compact">正在加载角色</div>}
      {templates !== null && templates.length === 0 && !error && <div className="notice-banner">暂时没有可选的角色，请稍后再来</div>}
      <div className="role-list">
        {(templates ?? []).map((template) => {
          const added = existingNames.includes(template.name)
          return <button
            key={template.id} type="button" className="role-option" disabled={Boolean(creating) || added}
            onClick={() => void choose(template)}
          >
            <span className="role-avatar" aria-hidden="true">{template.name.slice(0, 1)}</span>
            <span className="role-text">
              <span className="role-name">{template.name}</span>
              {template.description && <span className="role-description">{template.description}</span>}
            </span>
            <span className="role-action">{added ? '已添加' : creating === template.id ? '正在准备' : '选择'}</span>
          </button>
        })}
      </div>
      {error && <div className="error-banner" role="alert">{error}</div>}
      {onBack
        ? <button type="button" className="link-button" onClick={onBack}>返回对话</button>
        : <button type="button" className="link-button" onClick={onSignOut}>退出登录</button>}
    </section>
  </main>
}
