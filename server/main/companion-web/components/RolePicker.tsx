'use client'

import { Fragment, useEffect, useState } from 'react'
import { Plus } from 'lucide-react'
import { managerJson } from '../lib/manager'
import RoleFigure from './RoleFigure'

interface RoleTemplate { id: string; name: string; description?: string }

interface RolePickerProps {
  /** 参数是新角色的标识；blank 表示这是一个空白角色，接下来要让用户填它的设置。 */
  onCreated: (id?: string, blank?: boolean) => void
  onSignOut: () => void
  /** 用户已经有的角色的名字。再添加角色时，这些不能重复选。 */
  existingNames?: string[]
  /** 已经有角色的用户来添加新角色时提供，用来回到对话。 */
  onBack?: () => void
}

/** 后端的错误换成用户知道下一步怎么做的话。 */
function explain(message: string) {
  if (message.includes('角色数量已达到当前套餐上限')) {
    return '角色数已经到了当前套餐的上限。可以先回到对话，在角色列表里删掉一个不常用的角色，再来添加。'
  }
  return message
}

/**
 * 错误出现在用户刚点的地方（那张角色卡或“从空白开始”的下面）。
 * 角色一多列表就很长，错误放在页面最底下的话，用户点了上面的卡片看不到它，以为没反应。
 */
function InlineError({ text }: { text: string }) {
  return <div
    className="error-banner role-error" role="alert"
    ref={(element) => element?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })}
  >{text}</div>
}

/** 从预设里选一个角色。新账号必须先选一个才能开始；已经有角色的用户可以再添加别的。创建出来的角色直接可以对话。 */
export default function RolePicker({ onCreated, onSignOut, existingNames = [], onBack }: RolePickerProps) {
  const [templates, setTemplates] = useState<RoleTemplate[] | null>(null)
  const [creating, setCreating] = useState('')
  // target 是出错的那张卡（模板 id）、'blank'（从空白开始）或 'load'（角色列表没加载出来）。
  const [error, setError] = useState<{ target: string; message: string } | null>(null)

  useEffect(() => {
    let active = true
    managerJson<RoleTemplate[]>('/role-templates')
      .then((items) => { if (active) setTemplates(Array.isArray(items) ? items : []) })
      .catch((reason: unknown) => {
        if (!active) return
        setTemplates([])
        setError({ target: 'load', message: reason instanceof Error ? reason.message : '角色加载失败' })
      })
    return () => { active = false }
  }, [])

  async function choose(template: RoleTemplate) {
    setCreating(template.id); setError(null)
    try {
      const created = await managerJson<{ id?: string }>('/roles', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ templateId: template.id, name: template.name }),
      })
      onCreated(typeof created?.id === 'string' ? created.id : undefined)
    } catch (reason) {
      setError({ target: template.id, message: explain(reason instanceof Error ? reason.message : '角色创建失败') })
      setCreating('')
    }
  }

  const count = templates?.length ?? 0
  async function createBlank() {
    setCreating('blank'); setError(null)
    // 先给一个不会重名的临时名字，用户接下来在设置里改。
    let name = '新朋友'
    for (let index = 2; existingNames.includes(name); index += 1) name = `新朋友 ${index}`
    try {
      const created = await managerJson<{ id?: string }>('/roles', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ blank: true, name }),
      })
      onCreated(typeof created?.id === 'string' ? created.id : undefined, true)
    } catch (reason) {
      setError({ target: 'blank', message: explain(reason instanceof Error ? reason.message : '角色创建失败') })
      setCreating('')
    }
  }

  return <main className="picker">
    <header className="picker-head">
      <h1>{onBack ? '添加一个角色' : '选一个陪伴角色'}</h1>
      <p className="subtle">{onBack ? '每个角色有自己的性格和声音，聊天记录各自分开' : '先选一个开始聊天，之后可以再添加别的'}</p>
    </header>
    {templates === null && <div className="loading-state">正在加载角色</div>}
    {error?.target === 'load' && <div className="error-banner" role="alert">{error.message}</div>}
    {templates !== null && templates.length === 0 && !error && <div className="notice-banner">暂时没有可选的角色，请稍后再来</div>}
    {/* 两三个角色时在宽屏上并排成卡片，更多时保持一列。 */}
    <div className={`role-list ${count >= 2 && count <= 3 ? 'wide' : ''}`}>
        {(templates ?? []).map((template) => {
          const added = existingNames.includes(template.name)
          return <Fragment key={template.id}>
            <button
              type="button" className="role-option" disabled={Boolean(creating) || added}
              onClick={() => void choose(template)}
            >
              <RoleFigure name={template.name} size={92} />
              <span className="role-text">
                <span className="role-name">{template.name}</span>
                {template.description && <span className="role-description">{template.description}</span>}
              </span>
              <span className="role-action">{added ? '已添加' : creating === template.id ? '正在准备' : '选择'}</span>
            </button>
            {error?.target === template.id && <InlineError text={error.message} />}
          </Fragment>
        })}
    </div>
    {templates !== null && templates.length > 0 && <button type="button" className="blank-option" disabled={Boolean(creating)} onClick={() => void createBlank()}>
      <Plus size={18} />{creating === 'blank' ? '正在准备' : '从空白开始，自己设定一个'}
    </button>}
    {error?.target === 'blank' && <InlineError text={error.message} />}
    {onBack
      ? <button type="button" className="link-button" onClick={onBack}>返回对话</button>
      : <button type="button" className="link-button" onClick={onSignOut}>退出登录</button>}
  </main>
}
