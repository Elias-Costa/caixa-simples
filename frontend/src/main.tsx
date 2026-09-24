import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { App } from './App'
import { SessaoProvider } from './sessao/SessaoProvider'
import { observarInstalacao } from './offline/armazenamentoPersistente'
import './estilos.css'

observarInstalacao()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <SessaoProvider>
        <App />
      </SessaoProvider>
    </BrowserRouter>
  </StrictMode>,
)
