import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { contas } from '../api/contas'
import { ErroDaApi } from '../api/cliente'
import {
  fixarSessaoDaAba, gravarIdentidade, gravarToken, lerIdentidade, sessaoAtual,
} from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { useSessao } from '../sessao/useSessao'
import { itensDoMenu } from '../shell/menu'
import { TelaDeConfiguracao } from './TelaDeConfiguracao'
import { TelaDeUsuarios } from './TelaDeUsuarios'

afterEach(() => vi.restoreAllMocks())

const admin: Identidade = {
  usuarioId: 'admin', nome: 'Ana', perfil: 'ADMIN', contaId: 'conta',
  nomeNegocio: 'Loja', estoqueHabilitado: false,
}

const daContaB: Identidade = {
  usuarioId: 'bia', nome: 'Bia', perfil: 'ADMIN', contaId: 'conta-b',
  nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false,
}

/** A Ana entra; a pergunta da abertura ao servidor fica sem resposta, e a identidade só muda pela tela. */
function abrirComoAna(): string {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 24 * 60 * 60 * 1000)))
  gravarIdentidade(admin)
  vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>(() => undefined)))
  return sessaoAtual()!
}

/** Outra aba entra na Conta B; esta continua presa à sessão com que abriu. */
function outraAbaEntraNaContaB(sessaoDestaAba: string): void {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 23 * 60 * 60 * 1000)))
  gravarIdentidade(daContaB)
  fixarSessaoDaAba(sessaoDestaAba)
}

function IdentidadeDaAba() {
  const { identidade } = useSessao()
  return <p>{identidade
    ? `Na aba: ${identidade.nome}, NSU ${identidade.nsuObrigatorio ? 'exigido' : 'livre'}` : 'Na aba: ninguém'}</p>
}

/** A tela com o provedor de sessão de verdade, que grava a identidade da sessão da aba. */
function ConfiguracaoComProvedorReal() {
  return <MemoryRouter>
    <SessaoProvider>
      <IdentidadeDaAba />
      <TelaDeConfiguracao />
    </SessaoProvider>
  </MemoryRouter>
}

function ComSessaoEConfig() {
  const [identidade, atualizarIdentidade] = useState(admin)
  return <SessaoContext.Provider value={{
    identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade,
  }}>
    <p>Menu: {itensDoMenu(identidade).map((item) => item.rotulo).join(', ')}</p>
    <TelaDeConfiguracao />
  </SessaoContext.Provider>
}

describe('administração na tela', () => {
  it('liga o estoque e mostra o menu imediatamente', async () => {
    gravarToken('token-admin')
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ estoqueHabilitado: false, nsuObrigatorio: false })
    const definir = vi.spyOn(contas, 'definirEstoque')
      .mockResolvedValue({ estoqueHabilitado: true, nsuObrigatorio: false })
    render(<ComSessaoEConfig />)

    fireEvent.click(await screen.findByRole('switch', { name: 'Ligar controle de estoque' }))
    await waitFor(() => expect(definir).toHaveBeenCalledWith(true))
    expect(await screen.findByRole('switch', { name: 'Desligar controle de estoque' }))
      .toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText(/Menu:.*Estoque/)).toBeInTheDocument()
  })

  it('mostra a recusa por plano sem esconder a lista de usuários', async () => {
    vi.spyOn(contas, 'usuarios').mockResolvedValue([{
      id: 'admin', nome: 'Ana', perfil: 'ADMIN', ativo: true,
    }])
    vi.spyOn(contas, 'criarUsuario').mockRejectedValue(
      new ErroDaApi(409, 'Conflict', 'o plano admite um único usuário', {}),
    )
    render(<TelaDeUsuarios />)
    expect(await screen.findByText('Ana')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Bia' } })
    fireEvent.change(screen.getByLabelText('E-mail de entrada'), { target: { value: 'bia@exemplo.test' } })
    fireEvent.change(screen.getByLabelText('Senha inicial'), { target: { value: 'uma senha longa de teste' } })
    fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('o plano admite um único usuário')
    expect(screen.getByText('Ana')).toBeInTheDocument()
  })

  it('sem o estoque no plano, ligar o controle fica indisponível e a tela aponta o plano', async () => {
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ estoqueHabilitado: false, nsuObrigatorio: false })
    const definir = vi.spyOn(contas, 'definirEstoque')
    render(<MemoryRouter>
      <SessaoContext.Provider value={{
        identidade: { ...admin, plano: 'GRATIS', recursos: [] },
        entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
      }}>
        <TelaDeConfiguracao />
      </SessaoContext.Provider>
    </MemoryRouter>)

    expect(await screen.findByRole('switch', { name: 'Ligar controle de estoque' })).toBeDisabled()
    expect(screen.getByText(/faz parte do plano Completo/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Plano' })).toHaveAttribute('href', '/plano')
    expect(definir).not.toHaveBeenCalled()
  })

  it('exige o NSU no cartão mesmo no plano grátis e guarda a exigência na identidade', async () => {
    gravarToken('token-admin')
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ estoqueHabilitado: false, nsuObrigatorio: false })
    const definir = vi.spyOn(contas, 'definirNsu')
      .mockResolvedValue({ estoqueHabilitado: false, nsuObrigatorio: true })
    const atualizarIdentidade = vi.fn()
    render(<MemoryRouter>
      <SessaoContext.Provider value={{
        identidade: { ...admin, plano: 'GRATIS', recursos: [] },
        entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade,
      }}>
        <TelaDeConfiguracao />
      </SessaoContext.Provider>
    </MemoryRouter>)

    expect(await screen.findByText('Opcional')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('switch', { name: 'Exigir o NSU no cartão' }))
    await waitFor(() => expect(definir).toHaveBeenCalledWith(true))
    expect(await screen.findByRole('switch', { name: 'Deixar o NSU opcional' }))
      .toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('Obrigatório')).toBeInTheDocument()
    expect(atualizarIdentidade).toHaveBeenCalledWith(
      expect.objectContaining({ nsuObrigatorio: true, estoqueHabilitado: false }), expect.anything())
  })

  it('pelo provedor de sessão real, guarda a exigência do NSU na identidade da sessão da aba', async () => {
    abrirComoAna()
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ estoqueHabilitado: false, nsuObrigatorio: false })
    vi.spyOn(contas, 'definirNsu').mockResolvedValue({ estoqueHabilitado: false, nsuObrigatorio: true })
    render(<ConfiguracaoComProvedorReal />)

    fireEvent.click(await screen.findByRole('switch', { name: 'Exigir o NSU no cartão' }))

    expect(await screen.findByText('Na aba: Ana, NSU exigido')).toBeInTheDocument()
    expect(lerIdentidade()).toEqual({ ...admin, nsuObrigatorio: true })
  })

  it('não grava a identidade desta aba na sessão da Conta que outra aba abriu', async () => {
    const sessaoDaAna = abrirComoAna()
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ estoqueHabilitado: false, nsuObrigatorio: false })
    // O cliente HTTP recusaria esta chamada, saída depois que outra aba trocou a sessão; aqui ela
    // responde mesmo assim, para provar que o provedor também recusa a identidade.
    vi.spyOn(contas, 'definirNsu').mockResolvedValue({ estoqueHabilitado: false, nsuObrigatorio: true })
    render(<ConfiguracaoComProvedorReal />)
    const exigir = await screen.findByRole('switch', { name: 'Exigir o NSU no cartão' })

    outraAbaEntraNaContaB(sessaoDaAna)
    fireEvent.click(exigir)

    await waitFor(() => expect(screen.getByRole('switch', { name: 'Deixar o NSU opcional' })).toBeEnabled())
    expect(lerIdentidade()).toEqual(daContaB)
  })
})
