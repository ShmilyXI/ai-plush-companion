'use client'

import { Plus, X } from 'lucide-react'
import type { WebAgent } from '../lib/manager'
import { roleLook } from '../lib/roles'
import Avatar from './Avatar'

interface RoleRailProps {
  agents: WebAgent[]
  agentId: string
  /** 角色列表是从左边滑出来的抽屉，这里是它有没有打开。 */
  open: boolean
  /** 能不能添加角色。从管理台过来的会话在管理台里管理角色。 */
  canAdd: boolean
  onSelect: (id: string) => void
  onAdd: () => void
  onClose: () => void
}

export function agentName(agent: WebAgent) {
  return agent.agentName || agent.name || agent.id
}

/** 用户的角色列表。每个角色的对话各自分开，点一下就切过去。 */
export default function RoleRail({ agents, agentId, open, canAdd, onSelect, onAdd, onClose }: RoleRailProps) {
  return <>
    <aside className={`rail ${open ? 'open' : ''}`}>
      <div className="rail-head">
        <span className="rail-title">我的角色</span>
        <button type="button" className="tool-button rail-close" aria-label="关闭角色列表" onClick={onClose}><X size={20} /></button>
      </div>
      <nav className="role-nav" aria-label="角色">
        {agents.map((agent) => {
          const name = agentName(agent)
          const tagline = roleLook(name).tagline
          return <button
            key={agent.id} type="button" className="role-item" aria-current={agent.id === agentId ? 'true' : undefined}
            onClick={() => onSelect(agent.id)}
          >
            <Avatar name={name} size={44} />
            <span className="role-item-text">
              <span className="role-item-name">{name}</span>
              {tagline && <span className="role-item-tagline">{tagline}</span>}
            </span>
          </button>
        })}
      </nav>
      {canAdd && <button type="button" className="role-add" onClick={onAdd}><Plus size={18} />添加角色</button>}
    </aside>
    {open && <div className="rail-backdrop" onClick={onClose} />}
  </>
}
