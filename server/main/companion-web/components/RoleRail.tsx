'use client'

import { useEffect, useState } from 'react'
import { ChevronRight, Plus, UserRound, X } from 'lucide-react'
import type { WebAgent } from '../lib/manager'
import { loadProfile, planLine, toMillis, type ProfileSummary } from '../lib/profile'
import Avatar from './Avatar'

interface RoleRailProps {
  agents: WebAgent[]
  agentId: string
  /** 侧栏是从左边滑出来的抽屉，这里是它有没有打开。 */
  open: boolean
  /** 能不能添加角色。 */
  canAdd: boolean
  onSelect: (id: string) => void
  onAdd: () => void
  onClose: () => void
  /** 跳到和当前角色某一天的对话。参数是那一天开始的时间（毫秒）。 */
  onJump: (start: number) => void
  /** 打开个人中心。 */
  onProfile: () => void
}

export interface ThreadDay { day: string; start: number; turns: number; preview: string }

export function agentName(agent: WebAgent) {
  return agent.agentName || agent.name || agent.id
}

/** 2026-11-24 → 今天 / 昨天 / 11月20日；不是今年的带上年份。 */
export function dayLabel(day: string, now = new Date()) {
  const [year, month, date] = day.split('-').map(Number)
  if (!year || !month || !date) return day
  const target = new Date(year, month - 1, date)
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const diff = Math.round((today.getTime() - target.getTime()) / 86_400_000)
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  return year === now.getFullYear() ? `${month}月${date}日` : `${year}年${month}月${date}日`
}

/**
 * 左侧抽屉：最上面一排是用户的角色，点头像就换过去；中间是和当前角色按天分开的对话，点一天就跳到那天；
 * 最下面是自己的账号，点进去是个人中心。每个角色只有一段一直延续的对话，所以这里列的是“哪几天聊过”。
 */
export default function RoleRail({ agents, agentId, open, canAdd, onSelect, onAdd, onClose, onJump, onProfile }: RoleRailProps) {
  const [days, setDays] = useState<ThreadDay[] | null>(null)
  const [profile, setProfile] = useState<ProfileSummary | null>(null)
  const current = agents.find((agent) => agent.id === agentId)
  const currentName = current ? agentName(current) : ''

  // 每次打开都重新读一遍，刚聊过的那天要出现在最上面。
  useEffect(() => {
    if (!open || !agentId) return
    let active = true
    setDays(null)
    const tz = -new Date().getTimezoneOffset()
    fetch(`/api/history/days?agentId=${encodeURIComponent(agentId)}&tz=${tz}`, { cache: 'no-store' })
      .then(async (response) => {
        const payload = await response.json().catch(() => ({})) as { data?: unknown }
        if (!active) return
        const items = Array.isArray(payload.data) ? payload.data : []
        setDays(items.flatMap((item: Record<string, unknown>) => {
          const start = toMillis(item.start)
          return typeof item.day === 'string' && start != null
            ? [{ day: item.day, start, turns: toMillis(item.turns) ?? 0, preview: typeof item.preview === 'string' ? item.preview : '' }]
            : []
        }))
      })
      .catch(() => { if (active) setDays([]) })
    return () => { active = false }
  }, [open, agentId])

  useEffect(() => {
    if (!open || profile) return
    let active = true
    void loadProfile().then((loaded) => { if (active) setProfile(loaded) })
    return () => { active = false }
  }, [open, profile])

  return <>
    <aside className={`rail ${open ? 'open' : ''}`} aria-label="侧栏">
      <div className="rail-head">
        <span className="rail-title">我的角色</span>
        <button type="button" className="tool-button rail-close" aria-label="关闭侧栏" onClick={onClose}><X size={20} /></button>
      </div>
      <nav className="role-strip" aria-label="角色">
        {agents.map((agent) => {
          const name = agentName(agent)
          const selected = agent.id === agentId
          return <button
            key={agent.id} type="button" className="role-chip" aria-current={selected ? 'true' : undefined}
            aria-label={name} onClick={() => onSelect(agent.id)}
          >
            <Avatar name={name} size={48} />
            <span className="role-chip-name" aria-hidden="true">{name}</span>
          </button>
        })}
        {canAdd && <button type="button" className="role-chip role-chip-add" aria-label="添加角色" onClick={onAdd}>
          <span className="role-chip-plus" aria-hidden="true"><Plus size={20} /></span>
          <span className="role-chip-name" aria-hidden="true">添加</span>
        </button>}
      </nav>
      <div className="rail-days">
        {currentName && <p className="rail-section">和{currentName}聊过的</p>}
        {days === null && currentName && <p className="rail-empty">正在读取</p>}
        {days !== null && days.length === 0 && <p className="rail-empty">还没有聊过，回到对话说第一句吧</p>}
        {days && days.length > 0 && <ul className="day-list" aria-label={`和${currentName}聊过的日子`}>
          {days.map((item) => <li key={item.day}>
            <button type="button" className="day-item" onClick={() => onJump(item.start)}>
              <span className="day-label">{dayLabel(item.day)}</span>
              {item.preview && <span className="day-preview">{item.preview}</span>}
            </button>
          </li>)}
        </ul>}
      </div>
      <button type="button" className="rail-profile" onClick={onProfile} aria-label="个人中心">
        <span className="rail-profile-avatar" aria-hidden="true"><UserRound size={20} /></span>
        <span className="rail-profile-text">
          <span className="rail-profile-name">{profile?.label ?? '我的账号'}</span>
          {profile && <span className="rail-profile-plan">{planLine(profile, agents.length)}</span>}
        </span>
        <ChevronRight size={18} aria-hidden="true" />
      </button>
    </aside>
    <div className={`rail-backdrop ${open ? 'open' : ''}`} onClick={onClose} />
  </>
}
