import { useEffect } from 'react'
import { RouterProvider } from 'react-router-dom'

import { useAuthStore } from '../auth/authStore'
import { router } from './router'

export function ConsoleApp() {
  const status = useAuthStore((state) => state.status)
  const hydrate = useAuthStore((state) => state.hydrate)
  useEffect(() => {
    void hydrate()
  }, [hydrate])
  if (status === 'initializing') {
    return <div className="app-loading" role="status" aria-label="正在验证登录状态"><span className="loading-dot" /></div>
  }
  return <RouterProvider router={router} />
}
