import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState, type ReactNode } from 'react'
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

/** A tela dentro de uma sessão da Ana, com a atualização da identidade observável pelo teste. */
function comSessao(tela: ReactNode, atualizarIdentidade = vi.fn()) {
  return <SessaoContext.Provider value={{
    identidade: admin, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade,
  }}>{tela}</SessaoContext.Provider>
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
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: false })
    const definir = vi.spyOn(contas, 'definirEstoque')
      .mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: true, nsuObrigatorio: false })
    render(<ComSessaoEConfig />)

    fireEvent.click(await screen.findByRole('button', { name: 'Ligar controle de estoque' }))
    await waitFor(() => expect(definir).toHaveBeenCalledWith(true))
    expect(await screen.findByRole('button', { name: 'Desligar controle de estoque' })).toBeInTheDocument()
    expect(screen.getByText('Ligado')).toBeInTheDocument()
    expect(screen.getByText(/Menu:.*Estoque/)).toBeInTheDocument()
  })

  it('redefine a senha só do operador ativo, com a senha provisória digitada', async () => {
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'admin', nome: 'Ana', perfil: 'ADMIN', ativo: true },
      { id: 'bia', nome: 'Bia', perfil: 'OPERADOR', ativo: true },
      { id: 'caio', nome: 'Caio', perfil: 'OPERADOR', ativo: false },
    ])
    const redefinir = vi.spyOn(contas, 'redefinirSenha').mockResolvedValue(undefined)
    render(comSessao(<TelaDeUsuarios />))
    expect(await screen.findByText('Bia')).toBeInTheDocument()

    // Um botão só: o da Bia. O administrador e o inativo não têm.
    fireEvent.click(screen.getByRole('button', { name: 'Redefinir senha' }))
    fireEvent.change(screen.getByLabelText('Senha provisória de Bia'),
      { target: { value: 'curta' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar senha provisória' }))
    expect(screen.getByRole('alert')).toHaveTextContent('ao menos 15 caracteres')
    expect(redefinir).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('Senha provisória de Bia'),
      { target: { value: 'uma provisoria longa da Bia' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar senha provisória' }))

    await waitFor(() => expect(redefinir).toHaveBeenCalledWith('bia', 'uma provisoria longa da Bia'))
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Senha de Bia redefinida. Os aparelhos dele saíram')
    expect(screen.queryByLabelText('Senha provisória de Bia')).not.toBeInTheDocument()
  })

  it('depois de inativar, lembra de apagar os dados do aparelho de quem saiu', async () => {
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'bia', nome: 'Bia', perfil: 'OPERADOR', ativo: true },
    ])
    const inativar = vi.spyOn(contas, 'inativarUsuario').mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(comSessao(<TelaDeUsuarios />))
    expect(await screen.findByText('Bia')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Inativar' }))

    await waitFor(() => expect(inativar).toHaveBeenCalledWith('bia'))
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Bia foi inativado. No aparelho que só essa pessoa usava, apague os dados pelo botão da tela de entrada.')
  })

  it('corrige o nome do operador e o do próprio administrador, que vai também para a identidade', async () => {
    gravarToken('token-admin')
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'admin', nome: 'Ana', perfil: 'ADMIN', ativo: true },
      { id: 'bia', nome: 'Bia', perfil: 'OPERADOR', ativo: true },
      { id: 'caio', nome: 'Usuário removido', perfil: 'OPERADOR', ativo: false },
    ])
    const corrigir = vi.spyOn(contas, 'corrigirNomeDoUsuario').mockResolvedValue(undefined)
    const atualizarIdentidade = vi.fn()
    render(comSessao(<TelaDeUsuarios />, atualizarIdentidade))
    expect(await screen.findByText('Bia')).toBeInTheDocument()

    // O nome removido a pedido não se corrige: dois botões, o da Ana e o da Bia.
    const botoes = screen.getAllByRole('button', { name: 'Corrigir nome' })
    expect(botoes).toHaveLength(2)

    fireEvent.click(botoes[1])
    fireEvent.change(screen.getByLabelText('Nome de Bia'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar nome' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Informe o nome.')
    expect(corrigir).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('Nome de Bia'), { target: { value: ' Bia Souza ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar nome' }))
    await waitFor(() => expect(corrigir).toHaveBeenCalledWith('bia', 'Bia Souza'))
    expect(await screen.findByRole('status')).toHaveTextContent('Nome corrigido para Bia Souza.')
    expect(atualizarIdentidade).not.toHaveBeenCalled()

    fireEvent.click(screen.getAllByRole('button', { name: 'Corrigir nome' })[0])
    fireEvent.change(screen.getByLabelText('Nome de Ana'), { target: { value: 'Ana Paula' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar nome' }))
    await waitFor(() => expect(corrigir).toHaveBeenCalledWith('admin', 'Ana Paula'))
    expect(atualizarIdentidade).toHaveBeenCalledWith(
      expect.objectContaining({ usuarioId: 'admin', nome: 'Ana Paula' }), expect.anything())
  })

  it('corrige o nome do negócio e o leva à identidade, e recusa o nome em branco sem enviar', async () => {
    gravarToken('token-admin')
    vi.spyOn(contas, 'configuracao')
      .mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: false })
    const corrigir = vi.spyOn(contas, 'corrigirNomeDoNegocio')
      .mockResolvedValue({ nomeNegocio: 'Loja da Ana', estoqueHabilitado: false, nsuObrigatorio: false })
    const atualizarIdentidade = vi.fn()
    render(<MemoryRouter>{comSessao(<TelaDeConfiguracao />, atualizarIdentidade)}</MemoryRouter>)

    const campo = await screen.findByLabelText('Nome do negócio')
    expect(campo).toHaveValue('Loja')
    fireEvent.change(campo, { target: { value: '  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar o nome' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Informe o nome do negócio.')
    expect(corrigir).not.toHaveBeenCalled()

    fireEvent.change(campo, { target: { value: ' Loja da Ana ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar o nome' }))
    await waitFor(() => expect(corrigir).toHaveBeenCalledWith('Loja da Ana'))
    expect(await screen.findByRole('status')).toHaveTextContent('Nome do negócio corrigido.')
    expect(atualizarIdentidade).toHaveBeenCalledWith(
      expect.objectContaining({ nomeNegocio: 'Loja da Ana', estoqueHabilitado: false }), expect.anything())
  })

  it('mostra a recusa por plano sem esconder a lista de usuários', async () => {
    vi.spyOn(contas, 'usuarios').mockResolvedValue([{
      id: 'admin', nome: 'Ana', perfil: 'ADMIN', ativo: true,
    }])
    vi.spyOn(contas, 'criarUsuario').mockRejectedValue(
      new ErroDaApi(409, 'Conflict', 'o plano admite um único usuário', {}),
    )
    render(comSessao(<TelaDeUsuarios />))
    expect(await screen.findByText('Ana')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Bia' } })
    fireEvent.change(screen.getByLabelText('E-mail de entrada'), { target: { value: 'bia@exemplo.test' } })
    fireEvent.change(screen.getByLabelText('Senha inicial'), { target: { value: 'uma senha longa de teste' } })
    fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('o plano admite um único usuário')
    expect(screen.getByText('Ana')).toBeInTheDocument()
  })

  it('sem o estoque no plano, ligar o controle fica indisponível e a tela aponta o plano', async () => {
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: false })
    const definir = vi.spyOn(contas, 'definirEstoque')
    render(<MemoryRouter>
      <SessaoContext.Provider value={{
        identidade: { ...admin, plano: 'GRATIS', recursos: [] },
        entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
      }}>
        <TelaDeConfiguracao />
      </SessaoContext.Provider>
    </MemoryRouter>)

    expect(await screen.findByRole('button', { name: 'Ligar controle de estoque' })).toBeDisabled()
    expect(screen.getByText(/faz parte do plano Completo/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Plano' })).toHaveAttribute('href', '/plano')
    expect(definir).not.toHaveBeenCalled()
  })

  it('exige o NSU no cartão mesmo no plano grátis e guarda a exigência na identidade', async () => {
    gravarToken('token-admin')
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: false })
    const definir = vi.spyOn(contas, 'definirNsu')
      .mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: true })
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
    fireEvent.click(screen.getByRole('button', { name: 'Exigir o NSU no cartão' }))
    await waitFor(() => expect(definir).toHaveBeenCalledWith(true))
    expect(await screen.findByRole('button', { name: 'Deixar o NSU opcional' })).toBeInTheDocument()
    expect(screen.getByText('Obrigatório')).toBeInTheDocument()
    expect(atualizarIdentidade).toHaveBeenCalledWith(
      expect.objectContaining({ nsuObrigatorio: true, estoqueHabilitado: false }), expect.anything())
  })

  it('pelo provedor de sessão real, guarda a exigência do NSU na identidade da sessão da aba', async () => {
    abrirComoAna()
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: false })
    vi.spyOn(contas, 'definirNsu').mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: true })
    render(<ConfiguracaoComProvedorReal />)

    fireEvent.click(await screen.findByRole('button', { name: 'Exigir o NSU no cartão' }))

    expect(await screen.findByText('Na aba: Ana, NSU exigido')).toBeInTheDocument()
    expect(lerIdentidade()).toEqual({ ...admin, nsuObrigatorio: true })
  })

  it('não grava a identidade desta aba na sessão da Conta que outra aba abriu', async () => {
    const sessaoDaAna = abrirComoAna()
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: false })
    // O cliente HTTP recusaria esta chamada, saída depois que outra aba trocou a sessão; aqui ela
    // responde mesmo assim, para provar que o provedor também recusa a identidade.
    vi.spyOn(contas, 'definirNsu').mockResolvedValue({ nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: true })
    render(<ConfiguracaoComProvedorReal />)
    const exigir = await screen.findByRole('button', { name: 'Exigir o NSU no cartão' })

    outraAbaEntraNaContaB(sessaoDaAna)
    fireEvent.click(exigir)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Deixar o NSU opcional' })).toBeEnabled())
    expect(lerIdentidade()).toEqual(daContaB)
  })
})
