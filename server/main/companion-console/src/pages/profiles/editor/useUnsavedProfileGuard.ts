import { Modal } from 'antd'
import { useEffect, useRef } from 'react'
import { useBlocker } from 'react-router-dom'

export function useUnsavedProfileGuard(dirty: boolean) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && currentLocation.pathname !== nextLocation.pathname)
  const blockerRef = useRef(blocker)
  const modalRef = useRef<{ destroy: () => void } | null>(null)
  blockerRef.current = blocker

  useEffect(() => {
    if (!dirty) return
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [dirty])

  useEffect(() => {
    if (blocker.state !== 'blocked' || modalRef.current) return
    const modal = Modal.confirm({
      title: '离开角色编辑？',
      content: '尚有未保存的修改',
      okText: '放弃修改',
      cancelText: '继续编辑',
      onOk: () => {
        modalRef.current = null
        if (blockerRef.current.state === 'blocked') blockerRef.current.proceed()
      },
      onCancel: () => {
        modalRef.current = null
        if (blockerRef.current.state === 'blocked') blockerRef.current.reset()
      },
    })
    modalRef.current = modal
  }, [blocker.state])

  useEffect(() => () => {
    modalRef.current?.destroy()
    modalRef.current = null
  }, [])
}
