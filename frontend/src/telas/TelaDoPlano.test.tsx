import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ErroDaApi } from '../api/cliente'
import { contas, type EstadoDoPlano, type PedidoDePlano } from '../api/contas'
import {
  fixarSessaoDaAba, gravarIdentidade, gravarToken, lerIdentidade, sessaoAtual,
} from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { useSessao } from '../sessao/useSessao'
import { itensDoMenu } from '../shell/menu'
import { TelaDoPlano } from './TelaDoPlano'

afterEach(() => vi.restoreAllMocks())

const admin: Identidade = {
  usuarioId: 'admin', nome: 'Ana', perfil: 'ADMIN', contaId: 'conta',
  nomeNegocio: 'Loja', estoqueHabilitado: false, plano: 'GRATIS',
  situacaoDoPlano: 'SEM_MENSALIDADE', recursos: [],
}

const gratis: EstadoDoPlano = {
  plano: 'GRATIS',
  situacao: 'SEM_MENSALIDADE',
  recursos: [],
  mensalidadeCaixaSimples: 30,
  mensalidadeCompleto: 70,
  propostas: [
    { tipo: 'ADESAO', plano: 'CAIXA_SIMPLES', valor: 30 },
    { tipo: 'ADESAO', plano: 'COMPLETO', valor: 70 },
  ],
}

const pedido: PedidoDePlano = {
  id: '8d5b2c1e-4f3a-4b6c-9d7e-1a2b3c4d5e6f', tipo: 'ADESAO', plano: 'COMPLETO', valor: 70,
  situacao: 'ABERTO', criadoEm: '2026-10-03T12:00:00Z',
}

/** O que o servidor responde ao código certo do pedido. */
const completoEmDia: EstadoDoPlano = {
  plano: 'COMPLETO', situacao: 'EM_DIA', vencimento: '2026-11-03',
  inicioDaSuspensao: '2026-11-11', recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO'],
  mensalidadeCaixaSimples: 30, mensalidadeCompleto: 70,
  propostas: [{ tipo: 'RENOVACAO', plano: 'COMPLETO', valor: 70,
    periodoInicio: '2026-11-03', periodoFim: '2026-12-03' }],
}

const adminNoCompleto: Identidade = {
  ...admin, plano: 'COMPLETO', situacaoDoPlano: 'EM_DIA', recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO'],
}

const daContaB: Identidade = {
  usuarioId: 'bia', nome: 'Bia', perfil: 'ADMIN', contaId: 'conta-b',
  nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false, plano: 'GRATIS',
  situacaoDoPlano: 'SEM_MENSALIDADE', recursos: [],
}

/** O formato de moeda separa o símbolo do número com espaço inseparável; \s também o aceita. */
const pedirCompleto = /^Pedir o Completo \(R\$\s70,00 por mês\)$/
const pedirCaixaSimples = /^Pedir o Caixa Simples \(R\$\s30,00 por mês\)$/

function ComSessao() {
  const [identidade, atualizarIdentidade] = useState(admin)
  return <SessaoContext.Provider value={{
    identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade,
  }}>
    <p>Menu: {itensDoMenu(identidade).map((item) => item.rotulo).join(', ')}</p>
    <TelaDoPlano />
  </SessaoContext.Provider>
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

function MenuDaAba() {
  const { identidade } = useSessao()
  return <p>Menu: {identidade ? itensDoMenu(identidade).map((item) => item.rotulo).join(', ') : 'ninguém'}</p>
}

/** A tela com o provedor de sessão de verdade, que grava a identidade da sessão da aba. */
function PlanoComProvedorReal() {
  return <SessaoProvider>
    <MenuDaAba />
    <TelaDoPlano />
  </SessaoProvider>
}

describe('tela do plano', () => {
  it('mostra o que cada plano inclui, as mensalidades e o aviso de NFC-e antes do pedido', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValue(gratis)
    render(<ComSessao />)

    expect(await screen.findByText('Plano atual: Gratuito')).toBeInTheDocument()
    expect(screen.getByText(/mais os relatórios de faturamento/)).toHaveTextContent('R$ 30,00 por mês')
    expect(screen.getByText(/mais o controle de estoque/)).toHaveTextContent('R$ 70,00 por mês')
    expect(screen.getByText('A emissão de NFC-e ainda não está disponível.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: pedirCompleto })).toBeInTheDocument()
  })

  it('pede o plano e mostra o texto do pedido, sem nome do negócio nem de pessoa', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValueOnce(gratis)
      .mockResolvedValueOnce({ ...gratis, pedidoAberto: pedido })
    const pedir = vi.spyOn(contas, 'pedirPlano').mockResolvedValue(pedido)
    render(<ComSessao />)

    fireEvent.click(await screen.findByRole('button', { name: pedirCompleto }))

    await waitFor(() => expect(pedir).toHaveBeenCalledWith('COMPLETO'))
    const campo = await screen.findByLabelText('Texto do pedido')
    const texto = (campo as HTMLTextAreaElement).value.replace(/ /g, ' ')
    expect(texto).toBe([
      'Pedido de plano',
      `Pedido: ${pedido.id}`,
      'Tipo: Adesão',
      'Plano: Completo',
      'Valor: R$ 70,00',
      'Período: começa no dia em que o código for aplicado',
    ].join('\n'))
    expect(texto).not.toContain('Loja')
    expect(texto).not.toContain('Ana')
  })

  it('com um pedido aberto, pedir de novo pede confirmação antes de substituir', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...gratis, pedidoAberto: pedido })
    const pedir = vi.spyOn(contas, 'pedirPlano').mockResolvedValue(pedido)
    const confirmar = vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<ComSessao />)

    fireEvent.click(await screen.findByRole('button', { name: pedirCaixaSimples }))

    expect(confirmar).toHaveBeenCalledWith(expect.stringContaining('substitui o aberto'))
    expect(pedir).not.toHaveBeenCalled()
  })

  it('aplica o código, relê a identidade e o menu passa a mostrar os recursos do plano', async () => {
    gravarToken('token-admin')
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...gratis, pedidoAberto: pedido })
    const aplicar = vi.spyOn(contas, 'aplicarCodigo').mockResolvedValue(completoEmDia)
    vi.spyOn(contas, 'identidade').mockResolvedValue(adminNoCompleto)
    render(<ComSessao />)

    fireEvent.change(await screen.findByLabelText('Código de ativação'),
      { target: { value: '2jsy pfpp bna2 yxnn' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar código' }))

    await waitFor(() => expect(aplicar).toHaveBeenCalledWith(pedido.id, '2jsy pfpp bna2 yxnn'))
    expect(await screen.findByText('Plano atual: Completo')).toBeInTheDocument()
    expect(screen.getByRole('button',
      { name: /^Renovar o Completo de 03\/11\/2026 a 02\/12\/2026 \(R\$\s70,00\)$/ }))
      .toBeInTheDocument()
    expect(await screen.findByText(/Menu:.*Relatórios/)).toBeInTheDocument()
  })

  it('pelo provedor de sessão real, o código aplicado leva o plano à identidade da sessão e ao menu', async () => {
    abrirComoAna()
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...gratis, pedidoAberto: pedido })
    vi.spyOn(contas, 'aplicarCodigo').mockResolvedValue(completoEmDia)
    vi.spyOn(contas, 'identidade').mockResolvedValue(adminNoCompleto)
    render(<PlanoComProvedorReal />)
    expect(screen.getByText(/^Menu:/)).not.toHaveTextContent('Relatórios')

    fireEvent.change(await screen.findByLabelText('Código de ativação'),
      { target: { value: '2jsy pfpp bna2 yxnn' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar código' }))

    expect(await screen.findByText(/Menu:.*Relatórios/)).toBeInTheDocument()
    expect(lerIdentidade()).toEqual(adminNoCompleto)
  })

  it('não grava a identidade relida na sessão da Conta que outra aba abriu', async () => {
    const sessaoDaAna = abrirComoAna()
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...gratis, pedidoAberto: pedido })
    vi.spyOn(contas, 'aplicarCodigo').mockResolvedValue(completoEmDia)
    // O cliente HTTP recusaria a releitura, saída depois que outra aba trocou a sessão; aqui ela
    // responde mesmo assim, para provar que o provedor também recusa a identidade.
    const releitura = vi.spyOn(contas, 'identidade').mockResolvedValue(adminNoCompleto)
    render(<PlanoComProvedorReal />)
    const campo = await screen.findByLabelText('Código de ativação')

    outraAbaEntraNaContaB(sessaoDaAna)
    fireEvent.change(campo, { target: { value: '2jsy pfpp bna2 yxnn' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar código' }))

    await waitFor(() => expect(screen.getByRole('button', { name: /^Renovar o Completo/ })).toBeEnabled())
    expect(releitura).toHaveBeenCalled()
    expect(lerIdentidade()).toEqual(daContaB)
  })

  it('mostra a recusa do código sem perder o pedido aberto', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...gratis, pedidoAberto: pedido })
    vi.spyOn(contas, 'aplicarCodigo').mockRejectedValue(
      new ErroDaApi(400, 'Bad Request', 'o codigo nao e o deste pedido', {}))
    render(<ComSessao />)

    fireEvent.change(await screen.findByLabelText('Código de ativação'),
      { target: { value: '0000-0000-0000-0000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar código' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('o codigo nao e o deste pedido')
    expect(screen.getByLabelText('Texto do pedido')).toBeInTheDocument()
  })

  it('com o intermediário vencido, explica que o upgrade espera a renovação', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValue({
      ...gratis, plano: 'CAIXA_SIMPLES', situacao: 'SUSPENSO', vencimento: '2026-09-01',
      inicioDaSuspensao: '2026-09-09',
      propostas: [{ tipo: 'RENOVACAO', plano: 'CAIXA_SIMPLES', valor: 30,
        periodoInicio: '2026-09-01', periodoFim: '2026-10-01' }],
    })
    render(<ComSessao />)

    expect(await screen.findByText(/renove antes o Caixa Simples/)).toBeInTheDocument()
    expect(screen.getByText(/suspensos desde 09\/09\/2026/)).toBeInTheDocument()
    expect(screen.getByRole('button',
      { name: /^Renovar o Caixa Simples de 01\/09\/2026 a 30\/09\/2026 \(R\$\s30,00\)$/ }))
      .toBeInTheDocument()
  })
})
