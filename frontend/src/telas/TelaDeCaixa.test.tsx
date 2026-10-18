import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { contas } from '../api/contas'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { SessaoContext } from '../sessao/contexto'
import { TelaDeCaixa } from './TelaDeCaixa'

afterEach(() => vi.restoreAllMocks())
beforeEach(async () => { await deleteDB('caixa-simples-offline') })

const aberta: SessaoCaixa = {
  id: 's-1', usuarioId: 'u-1', valorAbertura: 20,
  valorFechamentoEsperado: 20, valorFechamentoContado: null, diferenca: null,
  abertaEm: '2026-09-22T12:00:00Z', fechadaEm: null, status: 'ABERTA',
}

function mostrar(perfil: 'ADMIN' | 'OPERADOR' = 'OPERADOR') {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade({ usuarioId: 'u-1', nome: 'Ana', perfil, contaId: 'c-1',
    nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false })
  render(<SessaoContext.Provider value={{
    identidade: {
      usuarioId: 'u-1', nome: 'Ana', perfil, contaId: 'c-1',
      nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false,
    },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
  }}><TelaDeCaixa /></SessaoContext.Provider>)
}

describe('caixa na tela', () => {
  it('abre o próprio caixa e recarrega a sessão ABERTA', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValueOnce(undefined).mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValueOnce([]).mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    const abrir = vi.spyOn(caixa, 'abrir').mockResolvedValue({ id: 's-1' })
    mostrar()

    expect(await screen.findByText('Nenhum caixa aberto para você.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor inicial'), { target: { value: '20.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))

    await waitFor(() => expect(abrir).toHaveBeenCalledWith(20))
    expect(await screen.findByRole('button', { name: 'Ver meu caixa' })).toBeInTheDocument()
  })

  it('abre com o valor digitado com vírgula e recusa o ponto de milhar no cartão da abertura', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    vi.spyOn(caixa, 'historico').mockResolvedValue([])
    const abrir = vi.spyOn(caixa, 'abrir').mockResolvedValue({ id: 's-1' })
    mostrar()

    const campo = await screen.findByLabelText('Valor inicial')
    expect(campo).toHaveValue('0,00')
    fireEvent.change(campo, { target: { value: '1.500,00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))
    const cartao = screen.getByRole('heading', { name: 'Meu caixa' }).closest('section')!
    expect(await within(cartao).findByRole('alert'))
      .toHaveTextContent('Informe o valor inicial com até duas casas decimais.')
    expect(abrir).not.toHaveBeenCalled()

    fireEvent.change(campo, { target: { value: '150,50' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))
    await waitFor(() => expect(abrir).toHaveBeenCalledWith(150.5))
  })

  it('lança sangria com motivo e valor informado', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    const sangrar = vi.spyOn(caixa, 'sangrar').mockResolvedValue(undefined)
    mostrar()

    expect(await screen.findByRole('button', { name: 'Registrar sangria' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor'), { target: { value: '5.00' } })
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'Retirada' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar sangria' }))

    await waitFor(() => expect(sangrar).toHaveBeenCalledWith('s-1', 5, 'Retirada', expect.any(String)))
    expect(await screen.findByRole('status')).toHaveTextContent('Sangria registrada.')
  })

  it('o administrador vê no histórico o nome de quem abriu o caixa, e não o id', async () => {
    const daBia = { ...aberta, id: 's-2', usuarioId: 'u-2' }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    vi.spyOn(caixa, 'historico').mockResolvedValue([daBia])
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'u-2', nome: 'Bia', perfil: 'OPERADOR', ativo: true },
    ])
    mostrar('ADMIN')

    expect(await screen.findByRole('button', { name: /Aberto.*Bia$/ })).toBeInTheDocument()
    expect(screen.queryByText(/u-2/)).not.toBeInTheDocument()
  })

  it('mostra esperado antes de fechar, diferença depois e Venda no extrato', async () => {
    let fechada = false
    const sessaoFechada: SessaoCaixa = {
      ...aberta, status: 'FECHADA', valorFechamentoContado: 18, diferenca: 2,
      fechadaEm: '2026-09-22T15:00:00Z',
    }
    const movimentos = [{
      id: 'm-1', tipo: 'VENDA' as const, valor: 5, motivo: null,
      vendaId: 'v-1', recebimentoId: null, criadoEm: '2026-09-22T14:00:00Z',
    }]
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockImplementation(async () => fechada ? undefined : aberta)
    vi.spyOn(caixa, 'historico').mockImplementation(async () => [fechada ? sessaoFechada : aberta])
    vi.spyOn(caixa, 'consultar').mockImplementation(async () => ({
      ...(fechada ? sessaoFechada : aberta), movimentos,
    }))
    const fechar = vi.spyOn(caixa, 'fechar').mockImplementation(async () => {
      fechada = true
      return { diferenca: 2 }
    })
    mostrar()

    expect(await screen.findByText(/Venda v-1/)).toBeInTheDocument()
    expect(screen.getByText('Venda', { selector: 'strong' })).toBeInTheDocument()
    // O fechamento vem antes do extrato, que ganha uma linha a cada venda do dia.
    const fechamento = screen.getByRole('heading', { name: 'Fechar caixa' })
    expect(fechamento.compareDocumentPosition(screen.getByText('Movimentos (1)'))
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText(/Saldo esperado:/)).toHaveTextContent('R$')
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '18.00' } })
    // O fechamento não se refaz: a pergunta diz a falta em palavras, e quem desiste não fecha.
    const pergunta = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true)
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))
    expect(pergunta.mock.calls[0][0]).toMatch(
      /^Fechar o caixa com falta de R\$\s2,00\? O fechamento não pode ser refeito\.$/)
    expect(fechar).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))

    await waitFor(() => expect(fechar).toHaveBeenCalledWith('s-1', 18))
    expect(await screen.findByText(/Diferença:/)).toHaveTextContent('R$')
  })

  it('mostra a conferência e fecha a SessaoCaixa sem chamar a API quando está offline', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const remoto = vi.spyOn(caixa, 'abrir')
    const fecharRemoto = vi.spyOn(caixa, 'fechar')
    mostrar()

    expect(await screen.findByText('Nenhum caixa aberto para você.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor inicial'), { target: { value: '10.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))
    expect((await screen.findAllByText('Pendente de sincronização com o servidor.')).length).toBeGreaterThan(0)
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '8.00' } })
    expect(screen.getByText(/Diferença prevista:/)).toHaveTextContent('R$ 2,00')
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))
    expect(await screen.findByText(/Diferença:/)).toHaveTextContent('R$ 2,00')
    expect(remoto).not.toHaveBeenCalled()
    expect(fecharRemoto).not.toHaveBeenCalled()
  })

  it('mostra fechado, sem valores nem formulários, o caixa que o servidor fechou fora do aparelho', async () => {
    const deHoje = { ...aberta, abertaEm: new Date().toISOString() }
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...deHoje, versao: 0, movimentos: [] })
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
    gravarIdentidade({ usuarioId: 'u-1', nome: 'Ana', perfil: 'OPERADOR', contaId: 'c-1',
      nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false })
    // Com rede, o aparelho guardou a sessão e depois ouviu que o servidor não tem caixa aberto.
    const local = criarCaixaLocal()
    await local.consultar(deHoje.id)
    await local.abertaDoOperadorAtual()
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    mostrar()

    expect(await screen.findByText('Nenhum caixa aberto para você.')).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('button', { name: /Fechado/ }))
    expect(await screen.findByText('Fechada no servidor; valor contado e diferença aparecem quando a rede voltar.'))
      .toBeInTheDocument()
    expect(screen.queryByText(/Valor contado:/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Fechar e registrar diferença' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar sangria' })).not.toBeInTheDocument()
  })

  it('mostra indisponível sem rede o saldo do caixa que mudou com rede e não foi relido', async () => {
    const deHoje = { ...aberta, abertaEm: new Date().toISOString(), versao: 0, movimentos: [] }
    vi.spyOn(caixa, 'consultar').mockResolvedValueOnce(deHoje).mockRejectedValue(new SemConexao())
    vi.spyOn(caixa, 'suprir').mockResolvedValue(undefined)
    gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
    gravarIdentidade({ usuarioId: 'u-1', nome: 'Ana', perfil: 'OPERADOR', contaId: 'c-1',
      nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false })
    // Com rede, o aparelho guardou o caixa e lançou um suprimento; a rede caiu antes da releitura.
    const local = criarCaixaLocal()
    await local.consultar(deHoje.id)
    await local.suprir(deHoje.id, 5, 'Troco', crypto.randomUUID())
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: 'Ver meu caixa' }))
    expect(await screen.findByText(/indisponível até a próxima leitura do caixa/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '20.00' } })
    expect(screen.queryByText(/Diferença prevista:/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor'), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'Depósito' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar sangria' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('a sangria espera')
  })
})
