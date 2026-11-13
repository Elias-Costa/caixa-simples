import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { gravarIdentidade, gravarToken } from './armazenamento'
import type { Identidade } from './Identidade'
import { chaveDoRascunho, guardarRascunho, lerRascunho } from './rascunhos'
import { SessaoProvider } from './SessaoProvider'
import { tokenComExpiracao } from './tokenDeTeste'
import { useSessao } from './useSessao'

const ana: Identidade = { usuarioId: 'u-ana', nome: 'Ana', perfil: 'ADMIN', contaId: 'c-1',
  nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false }
const bruno: Identidade = { ...ana, usuarioId: 'u-bruno', nome: 'Bruno', perfil: 'OPERADOR' }

function BotaoDeSair() {
  const { sair } = useSessao()
  return <button type="button" onClick={sair}>Sair</button>
}

describe('o rascunho das telas', () => {
  it('é de cada usuário e de cada tela, e sem sessão nada se guarda', () => {
    guardarRascunho(chaveDoRascunho(ana, 'caixa'), { motivo: 'Troco para a tarde' })

    expect(lerRascunho(chaveDoRascunho(ana, 'caixa'))).toEqual({ motivo: 'Troco para a tarde' })
    expect(lerRascunho(chaveDoRascunho(bruno, 'caixa'))).toBeUndefined()
    expect(lerRascunho(chaveDoRascunho(ana, 'produtos'))).toBeUndefined()
    expect(chaveDoRascunho(null, 'caixa')).toBeUndefined()
    expect(lerRascunho(chaveDoRascunho(null, 'caixa'))).toBeUndefined()
  })

  it('some quando a sessão termina, para quem entrar depois neste aparelho', () => {
    // A pergunta da abertura ao servidor fica sem resposta: a sessão só muda pelo Sair.
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
    gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
    gravarIdentidade(ana)
    render(<SessaoProvider><BotaoDeSair /></SessaoProvider>)
    guardarRascunho(chaveDoRascunho(ana, 'produtos'), { nome: 'Bolo de cenoura' })

    fireEvent.click(screen.getByRole('button', { name: 'Sair' }))

    expect(lerRascunho(chaveDoRascunho(ana, 'produtos'))).toBeUndefined()
  })
})
