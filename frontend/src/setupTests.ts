import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { registrarResposta } from './api/conexao'

// O jsdom não abre diálogo modal. O mínimo de que as telas dependem: abrir marca o diálogo como
// aberto, e fechar guarda a resposta e avisa pelo evento close, como o navegador. O resto do modal
// (o fundo inerte, o Esc, o foco devolvido) é provado no navegador.
if (typeof HTMLDialogElement.prototype.showModal !== 'function') {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.open = true
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement, resposta?: string) {
    if (!this.open) return
    if (resposta !== undefined) this.returnValue = resposta
    this.open = false
    this.dispatchEvent(new Event('close'))
  }
}

// Cada teste começa sem sessão guardada e sem fetch nem variável do build falsos do teste anterior.
afterEach(() => {
  cleanup()
  localStorage.clear()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

// E com o servidor respondendo, porque o estado da conexão é do módulo e passaria de um teste ao outro.
// Fica aqui, e não no afterEach: o cleanup roda os efeitos que ficaram pendentes, já sem os simulados, e
// a falha da chamada de verdade só chega depois do fim dele.
beforeEach(() => registrarResposta())
