import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { contas } from '../api/contas'
import { vendas, type Comprovante, type ResumoDaVenda, type Venda } from '../api/vendas'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { SessaoContext } from '../sessao/contexto'
import { confirmacaoAberta, responderConfirmacao } from './confirmacaoDeTeste'
import { TelaDeCaixa } from './TelaDeCaixa'

afterEach(() => vi.restoreAllMocks())
beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
  vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
  vi.spyOn(caixa, 'abertas').mockResolvedValue([])
})

const aberta: SessaoCaixa = {
  id: 's-1', usuarioId: 'u-1', valorAbertura: 20,
  valorFechamentoEsperado: 20, valorFechamentoContado: null, diferenca: null,
  abertaEm: '2026-09-22T12:00:00Z', fechadaEm: null, status: 'ABERTA',
}

function resumo(id: string, status: ResumoDaVenda['status'], total: number, criadoEm: string,
  formas?: ResumoDaVenda['formas']): ResumoDaVenda {
  return { id, sessaoCaixaId: 's-1', usuarioId: 'u-1', status, total, criadoEm, formas }
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

/** As listas do caixa nascem recolhidas: abre a que tem esse começo no resumo, como o toque faria. */
async function abrirLista(comeco: RegExp): Promise<HTMLElement> {
  const resumoDaLista = await screen.findByText(comeco, { selector: 'summary' })
  const lista = resumoDaLista.closest('details') as HTMLDetailsElement
  expect(lista.open).toBe(false)
  fireEvent.click(resumoDaLista)
  await waitFor(() => expect(lista.open).toBe(true))
  return lista
}

/** A linha de uma venda na tabela de vendas do caixa, pelo texto dela. */
function linhaDaVenda(lista: HTMLElement, texto: RegExp): HTMLElement {
  return within(within(lista).getByRole('table')).getByRole('row', { name: texto })
}

describe('caixa na tela', () => {
  it('abre o próprio caixa e mostra o detalhe dele, sem o Ver meu caixa que o repetiria', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValueOnce(undefined).mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValueOnce([]).mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    const abrir = vi.spyOn(caixa, 'abrir').mockResolvedValue({ id: 's-1' })
    mostrar()

    expect(await screen.findByText('Nenhum caixa aberto para você.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor inicial'), { target: { value: '20.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))

    await waitFor(() => expect(abrir).toHaveBeenCalledWith(20))
    expect(await screen.findByRole('heading', { name: 'Caixa de Ana' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ver meu caixa' })).not.toBeInTheDocument()
    // O próprio caixa não está em uso por outra pessoa.
    expect(screen.queryByText(/em uso desde/)).not.toBeInTheDocument()
  })

  it('abre com o valor digitado com vírgula e recusa o campo vazio e o ponto de milhar no cartão da abertura', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    vi.spyOn(caixa, 'historico').mockResolvedValue([])
    const abrir = vi.spyOn(caixa, 'abrir').mockResolvedValue({ id: 's-1' })
    mostrar()

    const campo = await screen.findByLabelText('Valor inicial')
    expect(campo).toHaveValue('')
    expect(campo).toHaveAttribute('placeholder', '0,00')
    // Vazio, o próprio formulário segura o envio: abrir sem troco é digitar 0.
    expect(campo).toBeRequired()
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))
    expect((campo as HTMLInputElement).validity.valueMissing).toBe(true)
    expect(abrir).not.toHaveBeenCalled()

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

  it('o histórico diz de quem é cada caixa, os horários, o valor inicial com rótulo e a diferença do fechamento', async () => {
    const daBia = { ...aberta, id: 's-2', usuarioId: 'u-2' }
    const fechadoDoCaio: SessaoCaixa = { ...aberta, id: 's-3', usuarioId: 'u-3', status: 'FECHADA',
      fechadaEm: '2026-09-22T15:00:00Z', valorFechamentoContado: 18, diferenca: 2 }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    vi.spyOn(caixa, 'historico').mockResolvedValue([daBia, fechadoDoCaio])
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'u-2', nome: 'Bia', perfil: 'OPERADOR', ativo: true },
      { id: 'u-3', nome: 'Caio', perfil: 'OPERADOR', ativo: true },
    ])
    mostrar('ADMIN')

    expect(await screen.findByRole('button', { name: /^Bia\s*Aberto às 09:00 · Valor inicial R\$\s20,00$/ }))
      .toBeInTheDocument()
    expect(screen.getByRole('button',
      { name: /^Caio\s*Aberto às 09:00 · Fechado às 12:00 · Valor inicial R\$\s20,00 · Falta de R\$\s2,00$/ }))
      .toBeInTheDocument()
    expect(screen.getByText('Caixas de todos os operadores.')).toBeInTheDocument()
    expect(screen.queryByText(/u-2/)).not.toBeInTheDocument()
    expect(screen.queryByText(/sessã/i)).not.toBeInTheDocument()
  })

  it('o administrador abre o caixa da operadora e confere as vendas, da mais antiga à mais nova, e o detalhe só de leitura logo abaixo da linha', async () => {
    const daBia: SessaoCaixa = { ...aberta, id: 's-2', usuarioId: 'u-2' }
    const fiada: Venda = {
      id: 'v-2', sessaoCaixaId: 's-2', usuarioId: 'u-2', status: 'CONCLUIDA', total: 20,
      criadoEm: '2026-09-22T13:00:00Z', clienteId: 'cl-1', saldoDevedor: 20, descontoDaVenda: 2,
      pago: 20, faltaPagar: 0, recebimentos: [],
      itens: [{ id: 'i-1', produtoId: 'p-1', nome: 'Café', quantidade: 2, precoUnitario: 11,
        desconto: 0, subtotal: 22 }],
      parcelas: [{ id: 'pg-1', forma: 'FIADO', valor: 20, status: 'PENDENTE', troco: 0 }],
    }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    vi.spyOn(caixa, 'historico').mockResolvedValue([daBia])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...daBia, movimentos: [] })
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'u-2', nome: 'Bia', perfil: 'OPERADOR', ativo: true },
    ])
    const daSessao = vi.spyOn(vendas, 'daSessao').mockResolvedValue([
      { ...resumo('v-3', 'CANCELADA', 12.5, '2026-09-22T14:00:00Z', [{ forma: 'DINHEIRO', valor: 12.5 }]),
        sessaoCaixaId: 's-2', usuarioId: 'u-2' },
      { ...resumo('v-2', 'CONCLUIDA', 20, '2026-09-22T13:00:00Z', [{ forma: 'FIADO', valor: 20 }]),
        sessaoCaixaId: 's-2', usuarioId: 'u-2' },
      { ...resumo('v-1', 'ABERTA', 5, '2026-09-22T12:30:00Z', []), sessaoCaixaId: 's-2', usuarioId: 'u-2' },
    ])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(fiada)
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([{ id: 'cl-1', nome: 'Marta', contato: null }])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([])
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /^Bia/ }))
    expect(await screen.findByRole('heading', { name: 'Caixa de Bia' })).toBeInTheDocument()
    // Aberto em outro dia: é dos esquecidos, e não o caixa que alguém usa no balcão agora.
    expect(screen.queryByText(/em uso desde/)).not.toBeInTheDocument()
    const lista = await abrirLista(/^Vendas \(3\)$/)
    expect(daSessao).toHaveBeenCalledWith('s-2')
    const linhas = within(within(lista).getByRole('table')).getAllByRole('row').slice(1)
    expect(linhas.map((linha) => linha.textContent)).toEqual([
      expect.stringMatching(/^09:30Aberta.*R\$\s5,00Ver$/),
      expect.stringMatching(/^10:00ConcluídaFiado.*R\$\s20,00Ver$/),
      expect.stringMatching(/^11:00CanceladaDinheiro.*R\$\s12,50Ver$/),
    ])
    // A cancelada não conta: o valor riscado, além da palavra.
    expect(within(linhas[2]).getByText(/R\$\s12,50/).tagName).toBe('S')
    expect(within(linhas[1]).queryByText(/R\$\s20,00/, { selector: 's' })).not.toBeInTheDocument()

    const ver = within(linhaDaVenda(lista, /Concluída/)).getByRole('button', { name: 'Ver' })
    fireEvent.click(ver)
    const detalhe = await screen.findByRole('region', { name: 'Detalhe da venda' })
    expect(ver).toHaveAttribute('aria-expanded', 'true')
    // Logo abaixo da linha tocada, e não no fim da lista.
    expect(detalhe.closest('tr')?.previousElementSibling).toBe(linhaDaVenda(lista, /Concluída/))
    expect(detalhe).toHaveTextContent('Cliente: Marta')
    expect(detalhe).toHaveTextContent('2 × Café · R$ 22,00')
    expect(detalhe).toHaveTextContent('Desconto da venda: R$ 2,00')
    expect(detalhe).toHaveTextContent('Total: R$ 20,00')
    expect(detalhe).toHaveTextContent('Fiado: R$ 20,00')
    // Conferir não é corrigir: nada no detalhe altera a venda, e a concluída só reimprime.
    expect(within(detalhe).getAllByRole('button').map((botao) => botao.textContent))
      .toEqual(['Segunda via do comprovante', 'Fechar detalhe'])
    fireEvent.click(within(detalhe).getByRole('button', { name: 'Fechar detalhe' }))
    expect(screen.queryByRole('region', { name: 'Detalhe da venda' })).not.toBeInTheDocument()
    expect(ver).toHaveFocus()
    expect(ver).toHaveAttribute('aria-expanded', 'false')
  })

  it('o administrador é avisado de que o caixa de outro operador, aberto hoje, está em uso, antes de lançar e fechar', async () => {
    const deHoje: SessaoCaixa = { ...aberta, id: 's-2', usuarioId: 'u-2', abertaEm: new Date().toISOString() }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    vi.spyOn(caixa, 'historico').mockResolvedValue([deHoje])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...deHoje, movimentos: [] })
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'u-2', nome: 'Bia', perfil: 'OPERADOR', ativo: true },
    ])
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /^Bia/ }))
    const aviso = await screen.findByText(/^Bia está com este caixa em uso desde as \d{2}:\d{2} de hoje\./)
    expect(aviso).toHaveTextContent(
      'Sangria, suprimento e fechamento lançados aqui mudam a gaveta que está no balcão.')
    expect(aviso.compareDocumentPosition(screen.getByRole('heading', { name: 'Lançar movimento' }))
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('soma o vendido no caixa por forma, só das concluídas, e diz o que não conseguiu separar por forma', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([
      resumo('v-1', 'CONCLUIDA', 15, '2026-09-22T13:00:00Z',
        [{ forma: 'DINHEIRO', valor: 10 }, { forma: 'CARTAO', valor: 5 }]),
      resumo('v-2', 'CONCLUIDA', 7, '2026-09-22T13:10:00Z', [{ forma: 'PIX', valor: 7 }]),
      // De um servidor anterior às formas: entra no total, mas não em nenhuma forma.
      resumo('v-3', 'CONCLUIDA', 3, '2026-09-22T13:20:00Z'),
      resumo('v-4', 'ABERTA', 40, '2026-09-22T13:30:00Z', [{ forma: 'DINHEIRO', valor: 40 }]),
      resumo('v-5', 'CANCELADA', 9, '2026-09-22T13:40:00Z', [{ forma: 'CARTAO', valor: 9 }]),
    ])
    mostrar()

    const tabela = await screen.findByRole('table', { name: 'Vendido neste caixa' })
    expect(within(tabela).getAllByRole('row').map((linha) => linha.textContent)).toEqual([
      'FormaValor',
      expect.stringMatching(/^DinheiroR\$\s10,00$/),
      expect.stringMatching(/^PixR\$\s7,00$/),
      expect.stringMatching(/^CartãoR\$\s5,00$/),
      expect.stringMatching(/^Forma não informadaR\$\s3,00$/),
      expect.stringMatching(/^Total de 3 vendasR\$\s25,00$/),
    ])
    expect(screen.getByText('Fora do total: uma venda aberta e uma cancelada.')).toBeInTheDocument()
    const gaveta = screen.getByRole('table', { name: 'Gaveta' })
    expect(within(gaveta).getByRole('row', { name: /Saldo esperado/ })).toHaveTextContent('R$ 20,00')
  })

  it('o fechamento lista as vendas abertas com o recebido, e a pergunta lembra delas sem impedir o fechamento', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([
      resumo('v-2', 'ABERTA', 12, '2026-09-22T14:00:00Z', []),
      resumo('v-1', 'ABERTA', 55.4, '2026-09-22T13:00:00Z',
        [{ forma: 'DINHEIRO', valor: 20 }, { forma: 'PIX', valor: 10 }, { forma: 'FIADO', valor: 5 }]),
      resumo('v-0', 'CONCLUIDA', 8, '2026-09-22T12:30:00Z', [{ forma: 'DINHEIRO', valor: 8 }]),
    ])
    const fechar = vi.spyOn(caixa, 'fechar').mockResolvedValue({ diferenca: -20 })
    mostrar()

    const formulario = (await screen.findByRole('heading', { name: 'Fechar caixa' })).closest('form') as HTMLElement
    expect(await within(formulario).findByText(/^Este caixa tem 2 vendas abertas\./)).toHaveTextContent(
      'Este caixa tem 2 vendas abertas. Depois do fechamento elas não concluem mais, só podem ser canceladas. '
      + 'O que já foi recebido nelas não está no saldo esperado: os R$ 20,00 em dinheiro, se estiverem na gaveta, '
      + 'aparecem como sobra.')
    const tabela = within(formulario).getByRole('table', { name: 'Vendas abertas' })
    expect(within(tabela).getAllByRole('row').map((linha) => linha.textContent)).toEqual([
      'HoraValor da vendaJá recebido',
      // O fiado é promessa: fica fora do já recebido.
      expect.stringMatching(/^10:00R\$\s55,40R\$\s20,00 em dinheiro e R\$\s10,00 em Pix$/),
      expect.stringMatching(/^11:00R\$\s12,00R\$\s0,00$/),
    ])

    fireEvent.change(within(formulario).getByLabelText('Valor contado'), { target: { value: '40,00' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Fechar e registrar diferença' }))
    const pergunta = await confirmacaoAberta()
    expect(pergunta).toHaveTextContent('Este caixa tem 2 vendas abertas, que só podem ser canceladas depois do '
      + 'fechamento, e R$ 20,00 em dinheiro recebido nelas fica fora do saldo esperado. '
      + 'O fechamento não pode ser refeito.')
    await responderConfirmacao('Fechar caixa')
    await waitFor(() => expect(fechar).toHaveBeenCalledWith('s-1', 40))
  })

  it('a venda aberta sem dinheiro recebido não fala de sobra', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([
      resumo('v-1', 'ABERTA', 30, '2026-09-22T13:00:00Z', [{ forma: 'CARTAO', valor: 30 }]),
    ])
    mostrar()

    expect(await screen.findByText('Este caixa tem uma venda aberta. Depois do fechamento ela não conclui mais, '
      + 'só pode ser cancelada.')).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /R\$\s30,00 em cartão/ })).toBeInTheDocument()
  })

  it('o cliente que não está mais no cadastro do aparelho aparece como removido no detalhe', async () => {
    const minha: Venda = {
      id: 'v-9', sessaoCaixaId: 's-1', usuarioId: 'u-1', status: 'CONCLUIDA', total: 8,
      criadoEm: '2026-09-22T13:00:00Z', clienteId: 'cl-x', saldoDevedor: 0, descontoDaVenda: 0,
      pago: 8, faltaPagar: 0, recebimentos: [], itens: [],
      parcelas: [{ id: 'pg-9', forma: 'CARTAO', valor: 8, status: 'CONFIRMADO', troco: 0, nsu: '123456' }],
    }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([minha])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(minha)
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([])
    mostrar()

    const lista = await abrirLista(/^Vendas \(1\)$/)
    fireEvent.click(within(linhaDaVenda(lista, /Concluída/)).getByRole('button', { name: 'Ver' }))
    const detalhe = await screen.findByRole('region', { name: 'Detalhe da venda' })
    expect(detalhe).toHaveTextContent('Cliente: Cliente removido')
    expect(detalhe).toHaveTextContent('Cartão: R$ 8,00 · NSU 123456')
  })

  it('o administrador vê no topo os caixas de outros abertos desde outro dia, e fecha um deles', async () => {
    const ontem = new Date(Date.now() - 26 * 60 * 60 * 1000).toISOString()
    const daBia: SessaoCaixa = { ...aberta, id: 's-2', usuarioId: 'u-2', abertaEm: ontem }
    vi.spyOn(caixa, 'abertas').mockResolvedValue([
      daBia,
      { ...aberta, id: 's-1', usuarioId: 'u-1', abertaEm: ontem },
      { ...aberta, id: 's-3', usuarioId: 'u-3', abertaEm: new Date().toISOString() },
    ])
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    vi.spyOn(caixa, 'historico').mockResolvedValue([])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...daBia, movimentos: [] })
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'u-2', nome: 'Bia', perfil: 'OPERADOR', ativo: true },
      { id: 'u-3', nome: 'Caio', perfil: 'OPERADOR', ativo: true },
    ])
    const fechar = vi.spyOn(caixa, 'fechar').mockResolvedValue({ diferenca: 0 })
    mostrar('ADMIN')

    const cartao = (await screen.findByRole('heading', { name: 'Caixas abertos desde outro dia' }))
      .closest('section') as HTMLElement
    expect(cartao).toHaveTextContent('Caixas de outros usuários que ficaram abertos. Escolha um para conferir e fechar.')
    // A do próprio administrador é a faixa dele; a de hoje não é esquecida.
    expect(within(cartao).getAllByRole('button').map((botao) => botao.textContent))
      .toEqual([expect.stringMatching(/^Bia · aberto em /)])

    fireEvent.click(within(cartao).getByRole('button', { name: /^Bia/ }))
    fireEvent.change(await screen.findByLabelText('Valor contado'), { target: { value: '20,00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))
    await responderConfirmacao('Fechar caixa')
    await waitFor(() => expect(fechar).toHaveBeenCalledWith('s-2', 20))
  })

  it('o operador não pede os caixas abertos da Conta', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    mostrar()

    expect(await screen.findByRole('heading', { name: 'Caixa de Ana' })).toBeInTheDocument()
    expect(screen.getByText('Seus caixas.')).toBeInTheDocument()
    expect(caixa.abertas).not.toHaveBeenCalled()
    expect(screen.queryByText('Caixas abertos desde outro dia')).not.toBeInTheDocument()
  })

  it('reimprime a venda concluída como segunda via, também sem rede, e a cancelada não tem o botão', async () => {
    const emPix: Venda = {
      id: 'v-5', sessaoCaixaId: 's-1', usuarioId: 'u-1', status: 'CONCLUIDA', total: 9,
      criadoEm: '2026-09-22T13:00:00Z', clienteId: null, saldoDevedor: 0, descontoDaVenda: 0,
      pago: 9, faltaPagar: 0, recebimentos: [],
      itens: [{ id: 'i-5', produtoId: 'p-5', nome: 'Pão de queijo', quantidade: 2, precoUnitario: 4.5,
        desconto: 0, subtotal: 9 }],
      parcelas: [{ id: 'pg-5', forma: 'PIX', valor: 9, status: 'CONFIRMADO', troco: 0 }],
    }
    const comprovante: Comprovante = {
      vendaId: 'v-5', usuarioId: 'u-1', concluidoEm: '2026-09-22T13:01:00Z',
      linhas: [{ produtoId: 'p-5', nome: 'Pão de queijo', unidade: 'un', quantidade: 2,
        precoUnitario: 4.5, valorBruto: 9, desconto: 0, subtotal: 9 }],
      somaDosItens: 9, descontoDaVenda: 0, valorTotal: 9,
      parcelas: [{ forma: 'PIX', valor: 9, troco: 0 }], troco: 0, valorFiado: 0, saldoDevedor: 0,
    }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([
      resumo('v-5', 'CONCLUIDA', 9, '2026-09-22T13:00:00Z'),
      resumo('v-6', 'CANCELADA', 4, '2026-09-22T13:30:00Z'),
    ])
    vi.spyOn(vendas, 'consultar').mockImplementation(async (id) =>
      id === 'v-5' ? emPix : { ...emPix, id: 'v-6', status: 'CANCELADA', total: 4 })
    const doServidor = vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()

    const lista = await abrirLista(/^Vendas \(2\)$/)
    fireEvent.click(within(linhaDaVenda(lista, /Cancelada/)).getByRole('button', { name: 'Ver' }))
    const cancelada = await screen.findByRole('region', { name: 'Detalhe da venda' })
    expect(within(cancelada).queryByRole('button', { name: 'Segunda via do comprovante' })).not.toBeInTheDocument()

    fireEvent.click(within(linhaDaVenda(lista, /Concluída/)).getByRole('button', { name: 'Ver' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Segunda via do comprovante' }))
    const papel = await screen.findByRole('region', { name: 'Comprovante não fiscal' })
    expect(papel).toHaveTextContent(/Segunda via, reimpressa em \d{2}\/\d{2}\/\d{4}/)
    expect(papel).toHaveTextContent('Pix: R$ 9,00')
    // Pix não tem troco, e a venda sem desconto não mostra a linha dele.
    expect(papel).not.toHaveTextContent('Troco')
    expect(papel).not.toHaveTextContent('Desconto da venda')
    expect(within(papel).getByRole('button', { name: 'Imprimir' })).toBeInTheDocument()
    expect(doServidor).toHaveBeenCalledWith('v-5')

    // Sem rede, a segunda via sai do que o aparelho guardou da primeira leitura.
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    doServidor.mockRejectedValue(new SemConexao())
    fireEvent.click(screen.getByRole('button', { name: 'Fechar detalhe' }))
    fireEvent.click(within(linhaDaVenda(lista, /Concluída/)).getByRole('button', { name: 'Ver' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Segunda via do comprovante' }))
    expect(await screen.findByRole('region', { name: 'Comprovante não fiscal' }))
      .toHaveTextContent('Segunda via, reimpressa em')
  })

  it('mostra esperado antes de fechar, diferença depois e o extrato recolhido, com o saldo depois de cada movimento', async () => {
    let fechada = false
    const sessaoFechada: SessaoCaixa = {
      ...aberta, status: 'FECHADA', valorFechamentoContado: 18, diferenca: 2,
      fechadaEm: '2026-09-22T15:00:00Z',
    }
    const movimentos = [{
      id: 'm-1', tipo: 'VENDA' as const, valor: 5, motivo: null,
      vendaId: 'v-1', recebimentoId: null, criadoEm: '2026-09-22T14:00:00Z',
    }, {
      id: 'm-2', tipo: 'SANGRIA' as const, valor: 5, motivo: 'Depósito no banco',
      vendaId: null, recebimentoId: null, criadoEm: '2026-09-22T14:30:00Z',
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

    const extrato = await abrirLista(/^Movimentos \(2\)$/)
    expect(within(extrato).getAllByRole('row').map((linha) => linha.textContent)).toEqual([
      'HoraTipoValorSaldo',
      expect.stringMatching(/^11:00VendaR\$\s5,00R\$\s25,00$/),
      // A saída leva o sinal, e o motivo fica sob o tipo.
      expect.stringMatching(/^11:30SangriaDepósito no banco-R\$\s5,00R\$\s20,00$/),
    ])
    // A venda de origem não aparece pelo id: a tabela das vendas, pela hora, é que a acha.
    expect(screen.queryByText(/v-1/)).not.toBeInTheDocument()
    // O fechamento vem antes do extrato, que ganha uma linha a cada venda do dia.
    const fechamento = screen.getByRole('heading', { name: 'Fechar caixa' })
    expect(fechamento.compareDocumentPosition(extrato) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(screen.getByRole('table', { name: 'Gaveta' })).getByRole('row', { name: /Saldo esperado/ }))
      .toHaveTextContent('R$ 20,00')
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '18.00' } })
    // O fechamento não se refaz: a pergunta diz a falta em palavras, e quem desiste não fecha.
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))
    const pergunta = await confirmacaoAberta()
    expect(pergunta).toHaveAccessibleName(/^Fechar o caixa com falta de R\$\s2,00\?$/)
    expect(pergunta).toHaveTextContent(/^Fechar o caixa com falta de R\$\s2,00\?O fechamento não pode ser refeito\./)
    await responderConfirmacao('Voltar')
    expect(fechar).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))
    await responderConfirmacao('Fechar caixa')

    await waitFor(() => expect(fechar).toHaveBeenCalledWith('s-1', 18))
    // O caixa fechado diz a diferença com as palavras da pergunta, sem sinal nem legenda.
    expect(await screen.findByText('Falta de R$ 2,00')).toBeInTheDocument()
    expect(screen.getByText(/^Fechamento: /)).toHaveTextContent('Fechamento: 22/09/2026, 12:00')
    expect(screen.queryByText(/positivo = falta/)).not.toBeInTheDocument()
  })

  it('mostra a conferência e fecha o caixa sem chamar a API quando está offline', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const remoto = vi.spyOn(caixa, 'abrir')
    const fecharRemoto = vi.spyOn(caixa, 'fechar')
    mostrar()

    expect(await screen.findByText('Nenhum caixa aberto para você.')).toBeInTheDocument()
    expect(screen.getByText('Caixas deste operador guardados no dispositivo.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor inicial'), { target: { value: '10.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))
    expect((await screen.findAllByText('Pendente de sincronização com o servidor.')).length).toBeGreaterThan(0)
    // Sem rede, o vendido é o das vendas guardadas aqui, e a tela diz isso.
    expect(screen.getByText('Sem conexão, o vendido conta só as vendas guardadas neste aparelho.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '12.00' } })
    expect(screen.getByText(/Se fechar agora:/)).toHaveTextContent('Se fechar agora: sobra de R$ 2,00')
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '10.00' } })
    expect(screen.getByText(/Se fechar agora:/)).toHaveTextContent('Se fechar agora: sem diferença')
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '8.00' } })
    expect(screen.getByText(/Se fechar agora:/)).toHaveTextContent('Se fechar agora: falta de R$ 2,00')
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))
    await responderConfirmacao('Fechar caixa')
    expect(await screen.findByText('Falta de R$ 2,00')).toBeInTheDocument()
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
    // Com rede, o aparelho guardou o caixa e depois ouviu que o servidor não tem caixa aberto.
    const local = criarCaixaLocal()
    await local.consultar(deHoje.id)
    await local.abertaDoOperadorAtual()
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    mostrar()

    expect(await screen.findByText('Nenhum caixa aberto para você.')).toBeInTheDocument()
    // Sem a diferença lida, o histórico não inventa uma.
    const noHistorico = await screen.findByRole('button', { name: /Fechado/ })
    expect(noHistorico).not.toHaveTextContent(/diferença|Falta|Sobra/)
    fireEvent.click(noHistorico)
    expect(await screen.findByText('Fechado no servidor; valor contado e diferença aparecem quando a rede voltar.'))
      .toBeInTheDocument()
    expect(screen.queryByRole('row', { name: /Valor contado/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Fechar e registrar diferença' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar sangria' })).not.toBeInTheDocument()
  })

  it('mostra indisponível sem rede o saldo do caixa que mudou com rede e não foi relido', async () => {
    const deHoje = { ...aberta, abertaEm: new Date().toISOString(), versao: 0, movimentos: [{
      id: 'm-0', tipo: 'SUPRIMENTO' as const, valor: 10, motivo: 'Troco', vendaId: null, recebimentoId: null,
      criadoEm: new Date().toISOString(),
    }] }
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

    expect(await screen.findByText(/indisponível até a próxima leitura do caixa/)).toBeInTheDocument()
    // O saldo corrido de um extrato que ficou para trás não bateria com o do servidor: a coluna sai.
    const extrato = await abrirLista(/^Movimentos/)
    expect(within(extrato).getAllByRole('columnheader').map((coluna) => coluna.textContent))
      .toEqual(['Hora', 'Tipo', 'Valor'])
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '20.00' } })
    expect(screen.queryByText(/Se fechar agora:/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor'), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'Depósito' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar sangria' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('a sangria espera')
  })
})
