import '@ant-design/v5-patch-for-react-19'
import React from 'react'
import ReactDOM from 'react-dom/client'

import { ConsoleApp } from './app/ConsoleApp'
import './tokens.css'
import './styles.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConsoleApp />
  </React.StrictMode>,
)
