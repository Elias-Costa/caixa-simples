import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro, type Cliente } from '../api/cadastro'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { registrarResposta, registrarSemResposta } from '../api/conexao'
import { contas } from '../api/contas'
import { sincronizacao, type RevisaoDaConta } from '../api/sincronizacao'
import { enfileirarGesto, iniciarEnvio, listarGestos, registrarDesfechos, type Desfecho, type GestoNaFila } from '../offline/fila'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade, Perfil } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { SincronizacaoContext, type Sincronizacao } from '../shell/sincronizacao'
import { TelaDeSincronizacao } from './TelaDeSincronizacao'

afterEach(() => vi.restoreAllMocks())
beforeEach(async () => { await deleteDB('ecrx-caixa-offline') })

const VENDA = '00000000-0000-4000-8000-000000000011'
const OUTRA = '00000000-0000-4000-8000-000000000012'
const SESSAO = '00000000-0000-4000-8000-000000000021'
const CLIENTE = '00000000-0000-4000-8000-000000000031'
const MOTIVO = 'Esta venda já está cancelada e não aceita mais alteração.'
const CASCATA = 'A venda parou no passo anterior, que foi recusado.'

function identidade(perfil: Perfil): Identidade {
  return { usuarioId: 'u-1', nome: 'Ana', perfil, contaId: 'c-1', nomeNegocio: 'Loja da Esquina',
    estoqueHabilitado: false }
}

function entrar(perfil: Perfil) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade(perfil))
}

function mostrar(perfil: Perfil) {
  const estado: Sincronizacao = { enviando: false, porEnviar: 0, comFalha: 0, emRevisao: 0, rodada: 0,
    vendasRecusadas: [], enviarAgora: vi.fn(), atualizar: vi.fn() }
  render(
    <SessaoContext.Provider value={{ identidade: identidade(perfil), entrar: vi.fn(), sair: vi.fn(),
      atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn() }}>
      <SincronizacaoContext.Provider value={estado}><TelaDeSincronizacao /></SincronizacaoContext.Provider>
    </SessaoContext.Provider>,
  )
  return estado
}

/** Uma venda de um item em dinheiro, gesto a gesto, como o balcão grava sem rede. */
async function vendaNaFila(vendaId: string, valor: number): Promise<GestoNaFila[]> {
  const agora = new Date().toISOString()
  const inicio = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: vendaId,
    payload: { sessaoCaixaId: SESSAO, criadoEm: agora } })
  const item = await enfileirarGesto({ tipo: 'venda.adicionarItem', registroId: vendaId,
    payload: { itemId: `item-${vendaId}`, produtoId: 'produto-1', nome: 'Sonho', unidade: 'un', quantidade: 1,
      precoUnitario: valor, desconto: 0, versaoProduto: 1 }, dependeDe: [inicio.operacaoId] })
  const parcela = await enfileirarGesto({ tipo: 'venda.registrarPagamento', registroId: vendaId,
    payload: { pagamentoId: `parcela-${vendaId}`, forma: 'DINHEIRO', valor, valorRecebido: valor },
    dependeDe: [item.operacaoId] })
  const conclusao = await enfileirarGesto({ tipo: 'venda.concluir', registroId: vendaId,
    payload: { concluidoEm: agora }, dependeDe: [parcela.operacaoId] })
  return [inicio, item, parcela, conclusao]
}

async function responder(desfechos: Desfecho[]) {
  await iniciarEnvio(desfechos.map((desfecho) => desfecho.operacaoId))
  await registrarDesfechos(desfechos)
}

const aplicado = ({ operacaoId }: GestoNaFila): Desfecho =>
  ({ operacaoId, estado: 'sent', resultado: { aplicada: true } })
const recusado = ({ operacaoId }: GestoNaFila, detalhe: string): Desfecho =>
  ({ operacaoId, estado: 'needs_review', resultado: { aplicada: false, detalhe } })

/** O início e o item valeram; o pagamento foi recusado, e a conclusão parou junto. */
async function vendaRecusada(vendaId: string, valor: number): Promise<GestoNaFila[]> {
  const gestos = await vendaNaFila(vendaId, valor)
  await responder([aplicado(gestos[0]), aplicado(gestos[1]), recusado(gestos[2], MOTIVO), recusado(gestos[3], CASCATA)])
  return gestos
}

/** A lista em que a linha com este texto está, para contar as linhas. */
async function listaDa(texto: RegExp): Promise<HTMLElement> {
  return (await screen.findByText(texto)).closest('ul') as HTMLElement
}

function daConta(gesto: GestoNaFila, parcial: Partial<RevisaoDaConta> = {}): RevisaoDaConta {
  return {
    operacaoId: gesto.operacaoId, usuarioId: 'u-2', tipo: gesto.tipo, registroId: gesto.registroId,
    payload: gesto.payload, criadaEm: gesto.criadoEm, recebidaEm: '2026-09-26T14:00:00Z',
    resultado: 'NAO_APLICADA', detalhe: CASCATA, ...parcial,
  }
}

/** Os gestos de uma venda como a lista da Conta os traz, sem passar pela fila deste aparelho. */
function vendaDaConta(vendaId: string, valor: number, parcial: Partial<RevisaoDaConta> = {}): RevisaoDaConta[] {
  const inicio = Date.now() - 60_000
  const em = (segundos: number) => new Date(inicio + segundos * 1000).toISOString()
  const criados: [string, GestoNaFila['payload']][] = [
    ['venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: em(0) }],
    ['venda.adicionarItem', { itemId: 'item', produtoId: 'produto-1', nome: 'Bolo', quantidade: 2,
      precoUnitario: valor / 2, desconto: 0, versaoProduto: 1 }],
    ['venda.registrarPagamento', { pagamentoId: 'parcela', forma: 'DINHEIRO', valor, valorRecebido: valor }],
    ['venda.concluir', { concluidoEm: em(3) }],
  ]
  return criados.map(([tipo, payload], indice) => ({
    operacaoId: crypto.randomUUID(), usuarioId: 'u-2', tipo, registroId: vendaId, payload, criadaEm: em(indice),
    recebidaEm: em(10), resultado: 'NAO_APLICADA', detalhe: 'O caixa foi fechado antes de esta venda chegar ao servidor.',
    ...parcial,
  }))
}

function equipe() {
  vi.spyOn(contas, 'usuarios').mockResolvedValue([
    { id: 'u-1', nome: 'Ana', perfil: 'ADMIN', ativo: true },
    { id: 'u-2', nome: 'Bruno', perfil: 'OPERADOR', ativo: true },
  ])
}

const cartaoDaConta = async () =>
  within((await screen.findByRole('heading', { name: 'Revisões da Conta' })).closest('div') as HTMLElement)

describe('a fila por registro', () => {
  it('mostra uma linha por registro, com a hora, o total, a forma e a situação, e os passos recolhidos', async () => {
    entrar('OPERADOR')
    await vendaNaFila(VENDA, 12.4)
    await vendaNaFila(OUTRA, 7)
    await enfileirarGesto({ tipo: 'caixa.sangrar', registroId: SESSAO,
      payload: { valor: 20, motivo: 'Cofre', criadoEm: new Date().toISOString() } })
    const clienteNovo = await enfileirarGesto({ tipo: 'cliente.criar', registroId: CLIENTE,
      payload: { nome: 'Maria', contato: null } })
    await enfileirarGesto({ tipo: 'cliente.editar', registroId: CLIENTE, dependeDe: [clienteNovo.operacaoId],
      payload: { nome: 'Maria Souza', contato: null } })

    mostrar('OPERADOR')

    const lista = await listaDa(/^Venda das \d\d:\d\d, R\$ 12,40$/)
    expect(lista.children).toHaveLength(4)
    expect(within(lista).getAllByText('Dinheiro · Na fila · 4 passos')).toHaveLength(2)
    expect(within(lista).getByText(/^Sangria das \d\d:\d\d, R\$ 20,00$/)).toBeInTheDocument()
    expect(within(lista).getByText('Cofre · Na fila')).toBeInTheDocument()
    expect(within(lista).getByText(/^Cliente cadastrado às \d\d:\d\d: Maria Souza$/)).toBeInTheDocument()
    expect(within(lista).getByText('Na fila · 2 passos')).toBeInTheDocument()

    const venda = within(lista).getByText(/^Venda das .*R\$ 12,40$/).closest('details') as HTMLElement
    expect(venda).not.toHaveAttribute('open')
    expect(within(venda).getByText('Venda concluída')).toBeInTheDocument()
    // A sangria é um gesto só: a linha já disse tudo, e não há o que abrir.
    expect(within(lista).getByText(/^Sangria/).closest('details')).toBeNull()
  })

  it('não perde o gesto feito depois de um recusado nem a venda cujo início saiu da fila', async () => {
    entrar('OPERADOR')
    const inicio = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: VENDA,
      payload: { sessaoCaixaId: SESSAO, criadoEm: new Date().toISOString() } })
    const item = await enfileirarGesto({ tipo: 'venda.adicionarItem', registroId: VENDA, dependeDe: [inicio.operacaoId],
      payload: { itemId: 'item-1', produtoId: 'produto-1', nome: 'Sonho', quantidade: 1, precoUnitario: 5, desconto: 0,
        versaoProduto: 1 } })
    const fora = await enfileirarGesto({ tipo: 'venda.adicionarItem', registroId: VENDA, dependeDe: [item.operacaoId],
      payload: { itemId: 'item-2', produtoId: 'produto-2', nome: 'Broa', quantidade: 1, precoUnitario: 3, desconto: 0,
        versaoProduto: 1 } })
    // O balcão recebeu e concluiu sem o item recusado: remontados com ele, os pagamentos não cobrem o total.
    const pagamento = await enfileirarGesto({ tipo: 'venda.registrarPagamento', registroId: VENDA, dependeDe: [fora.operacaoId],
      payload: { pagamentoId: 'parcela', forma: 'DINHEIRO', valor: 5, valorRecebido: 5 } })
    const conclusao = await enfileirarGesto({ tipo: 'venda.concluir', registroId: VENDA, dependeDe: [pagamento.operacaoId],
      payload: { concluidoEm: new Date().toISOString() } })
    const antiga = await enfileirarGesto({ tipo: 'venda.adicionarItem', registroId: OUTRA,
      payload: { itemId: 'item-3', produtoId: 'produto-1', nome: 'Sonho', quantidade: 2, precoUnitario: 5, desconto: 0,
        versaoProduto: 1 } })
    await responder([aplicado(inicio), aplicado(item), recusado(fora, 'Este produto foi inativado.'),
      recusado(pagamento, CASCATA), recusado(conclusao, CASCATA),
      { operacaoId: antiga.operacaoId, estado: 'needs_review', resultado: { aplicada: true, detalhe: 'Preço diferente.' } }])

    mostrar('OPERADOR')

    const lista = await listaDa(/^Venda das \d\d:\d\d, R\$ 8,00$/)
    expect(lista.children).toHaveLength(2)
    // Remontada com o recusado, a conclusão não cabe na conta, mas continua entre os passos.
    const recusada = within(lista).getByText(/^Venda das .*R\$ 8,00$/).closest('li') as HTMLElement
    expect(within(recusada).getByText('Não concluída: Este produto foi inativado.')).toBeInTheDocument()
    expect(within(recusada).getByText('Venda concluída')).toBeInTheDocument()
    const semInicio = within(lista).getByText(/^Venda das \d\d:\d\d$/).closest('li') as HTMLElement
    expect(within(semInicio).getByText('2 × Sonho · R$ 5,00')).toBeInTheDocument()
    expect(within(semInicio).getByText('Aplicada, com revisão: Preço diferente.')).toBeInTheDocument()
  })

  it('confere o registro inteiro num toque, e cada movimento de caixa é um registro', async () => {
    entrar('OPERADOR')
    const venda = await vendaRecusada(VENDA, 12.4)
    const sangria = await enfileirarGesto({ tipo: 'caixa.sangrar', registroId: SESSAO, payload: { valor: 5, motivo: 'Cofre' } })
    const suprimento = await enfileirarGesto({ tipo: 'caixa.suprir', registroId: SESSAO, payload: { valor: 9, motivo: 'Troco' } })
    await responder([recusado(sangria, 'O caixa parou no passo anterior, que foi recusado.'),
      recusado(suprimento, 'O caixa parou no passo anterior, que foi recusado.')])
    const revisoes = vi.spyOn(sincronizacao, 'revisoes')
    const estado = mostrar('OPERADOR')

    const lista = await listaDa(/^Venda das/)
    expect(lista.children).toHaveLength(3)
    expect(within(lista).getByText('Não concluída: Esta venda já está cancelada e não aceita mais alteração.')).toBeInTheDocument()
    expect(within(lista).getByText('Recusada: O caixa parou no passo anterior, que foi recusado.')).toBeInTheDocument()
    expect(within(lista).getByText('Recusado: O caixa parou no passo anterior, que foi recusado.')).toBeInTheDocument()

    fireEvent.click(within(lista).getByRole('button', { name: /^Conferir Venda/ }))
    await waitFor(() => expect(estado.atualizar).toHaveBeenCalled())
    await waitFor(() => expect(lista.children).toHaveLength(2))

    const depois = new Map((await listarGestos()).map((gesto) => [gesto.operacaoId, gesto]))
    expect(depois.get(venda[2].operacaoId)?.conferidoEm).toEqual(expect.any(String))
    expect(depois.get(venda[3].operacaoId)?.conferidoEm).toEqual(expect.any(String))
    expect(depois.get(sangria.operacaoId)?.conferidoEm).toBeUndefined()
    expect(screen.getByText('Conferidos neste aparelho (1)')).toBeInTheDocument()

    // Mesmo caixa, registros diferentes: conferir um não leva o outro.
    fireEvent.click(within(lista).getByRole('button', { name: /^Conferir Sangria/ }))
    await waitFor(() => expect(lista.children).toHaveLength(1))
    expect(within(lista).getByText(/^Suprimento/)).toBeInTheDocument()
    expect(screen.getByText('Conferidos neste aparelho (2)')).toBeInTheDocument()

    expect(screen.queryByRole('heading', { name: 'Revisões da Conta' })).not.toBeInTheDocument()
    expect(revisoes).not.toHaveBeenCalled()
  })

  it('diz a falha do envio na linha, e o registro continua por enviar enquanto falta resultado', async () => {
    entrar('OPERADOR')
    const [inicio, item] = await vendaNaFila(VENDA, 12.4)
    await responder([recusado(inicio, 'O caixa foi fechado.'),
      { operacaoId: item.operacaoId, estado: 'failed', falha: 'Sem resposta do servidor.' }])

    mostrar('OPERADOR')

    expect(await screen.findByText('Dinheiro · Com falha, será repetida: Sem resposta do servidor. · 4 passos')).toBeInTheDocument()
    expect(screen.getByText('Nenhuma revisão por conferir neste aparelho.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Conferir/ })).not.toBeInTheDocument()
  })
})

describe('o conteúdo dos passos', () => {
  it('diz a quantidade com a unidade e o nome do cliente pelo cadastro do aparelho', async () => {
    entrar('OPERADOR')
    const maria: Cliente = { id: CLIENTE, nome: 'Maria Souza', contato: null, ativo: true, versao: 1 } as Cliente
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([maria])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([])
    const inicio = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: VENDA,
      payload: { sessaoCaixaId: SESSAO, criadoEm: new Date().toISOString() } })
    const item = await enfileirarGesto({ tipo: 'venda.adicionarItem', registroId: VENDA, dependeDe: [inicio.operacaoId],
      payload: { itemId: 'item-1', produtoId: 'produto-1', nome: 'Pão francês', unidade: 'kg', quantidade: 0.35,
        precoUnitario: 12, desconto: 0, versaoProduto: 1 } })
    await enfileirarGesto({ tipo: 'venda.vincularCliente', registroId: VENDA, dependeDe: [item.operacaoId],
      payload: { clienteId: CLIENTE } })

    mostrar('OPERADOR')

    expect(await screen.findByText('0,35 kg × Pão francês · R$ 12,00')).toBeInTheDocument()
    expect(await screen.findByText('Maria Souza')).toBeInTheDocument()
  })

  it('diz o nome do cliente inativado sem rede, que o gesto não traz', async () => {
    entrar('OPERADOR')
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([
      { id: CLIENTE, nome: 'Maria Souza', contato: null, ativo: false, versao: 1 } as Cliente])
    await enfileirarGesto({ tipo: 'cliente.inativar', registroId: CLIENTE, payload: {} })

    mostrar('OPERADOR')

    expect(await screen.findByText(/^Cliente inativado às \d\d:\d\d: Maria Souza$/)).toBeInTheDocument()
  })
})

describe('o foco depois de Conferir', () => {
  it('no aparelho, vai ao Conferir seguinte e, quando acaba, ao título da lista', async () => {
    entrar('OPERADOR')
    await vendaRecusada(VENDA, 12.4)
    await vendaRecusada(OUTRA, 7)
    mostrar('OPERADOR')
    const lista = await listaDa(/^Venda das .*R\$ 12,40$/)

    const primeiro = within(lista).getByRole('button', { name: /12,40$/ })
    primeiro.focus()
    fireEvent.click(primeiro)
    // O navegador tira o foco do botão desligado; o jsdom não tira, e o foco em outro lugar faz o papel.
    screen.getByRole('heading', { name: 'Sincronização' }).focus()

    await waitFor(() => expect(document.activeElement).toBe(within(lista).getByRole('button', { name: /7,00$/ })))

    fireEvent.click(document.activeElement as HTMLElement)
    screen.getByRole('heading', { name: 'Sincronização' }).focus()

    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Em revisão' })))
  })

  it('na lista da Conta, vai ao Conferir seguinte e, quando acaba, ao título dela', async () => {
    entrar('ADMIN')
    equipe()
    const primeira = vendaDaConta(VENDA, 12.4)
    const segunda = vendaDaConta(OUTRA, 7).map((revisao) => ({ ...revisao,
      criadaEm: new Date(Date.parse(revisao.criadaEm) + 30_000).toISOString() }))
    const feita = { conferidaEm: '2026-09-26T15:00:00Z', conferidaPor: 'u-1' }
    const conferidas = (revisoes: RevisaoDaConta[]) => revisoes.map((revisao) => ({ ...revisao, ...feita }))
    vi.spyOn(sincronizacao, 'revisoes')
      .mockResolvedValueOnce({ pendentes: [...primeira, ...segunda], conferidas: [] })
      .mockResolvedValueOnce({ pendentes: segunda, conferidas: conferidas(primeira) })
      .mockResolvedValue({ pendentes: [], conferidas: conferidas([...primeira, ...segunda]) })
    vi.spyOn(sincronizacao, 'conferir').mockResolvedValue(undefined)
    mostrar('ADMIN')
    const cartao = await cartaoDaConta()

    fireEvent.click(await cartao.findByRole('button', { name: /12,40$/ }))
    screen.getByRole('heading', { name: 'Sincronização' }).focus()
    await waitFor(() => expect(document.activeElement).toBe(cartao.getByRole('button', { name: /7,00$/ })))

    fireEvent.click(document.activeElement as HTMLElement)
    screen.getByRole('heading', { name: 'Sincronização' }).focus()
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Revisões da Conta' })))
  })
})

describe('a lista da Conta', () => {
  it('no administrador, Este aparelho mostra só o que falta enviar, e a revisão aparece uma vez, na lista da Conta', async () => {
    entrar('ADMIN')
    equipe()
    const venda = await vendaRecusada(VENDA, 12.4)
    await enfileirarGesto({ tipo: 'caixa.sangrar', registroId: SESSAO, payload: { valor: 5, motivo: 'Cofre' } })
    const revisoes = [daConta(venda[2], { usuarioId: 'u-1', detalhe: MOTIVO }), daConta(venda[3], { usuarioId: 'u-1' })]
    vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ pendentes: revisoes, conferidas: [] })

    mostrar('ADMIN')

    const cartao = await cartaoDaConta()
    expect(await cartao.findByText(/^Venda das/)).toBeInTheDocument()
    // A fila do aparelho, lida do IndexedDB, chega depois da lista simulada. Só depois dela o que se afirma
    // ausente abaixo está ausente de fato, e não por ainda não ter sido lido.
    const aparelho = within(screen.getByRole('heading', { name: 'Este aparelho' }).closest('.caixa__cartao') as HTMLElement)
    expect(await aparelho.findByText('Cofre · Na fila')).toBeInTheDocument()
    expect(screen.getAllByText(/^Venda das/)).toHaveLength(1)
    expect(screen.queryByRole('heading', { name: 'Em revisão' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Conferidos neste aparelho/)).not.toBeInTheDocument()
  })

  it('agrupa a venda com o autor, e Conferir chama a rota uma vez por operação e marca o que é deste aparelho', async () => {
    entrar('ADMIN')
    equipe()
    const local = await vendaRecusada(VENDA, 12.4)
    const doAparelho = [daConta(local[2], { detalhe: MOTIVO }), daConta(local[3])]
    const deOutro = vendaDaConta(OUTRA, 7)
    const conferidas = deOutro.map((revisao) => ({ ...revisao, conferidaEm: '2026-09-26T15:00:00Z', conferidaPor: 'u-1' }))
    vi.spyOn(sincronizacao, 'revisoes')
      .mockResolvedValueOnce({ pendentes: [...doAparelho, ...deOutro], conferidas: [] })
      .mockResolvedValueOnce({ pendentes: doAparelho, conferidas })
      .mockResolvedValue({ pendentes: [], conferidas: [...doAparelho.map((revisao) => ({ ...revisao,
        conferidaEm: '2026-09-26T15:00:00Z', conferidaPor: 'u-1' })), ...conferidas] })
    const conferir = vi.spyOn(sincronizacao, 'conferir').mockResolvedValue(undefined)
    const estado = mostrar('ADMIN')
    const cartao = await cartaoDaConta()

    const linha = (await cartao.findByText(/^Venda das \d\d:\d\d, R\$ 7,00$/)).closest('li') as HTMLElement
    expect(linha).toHaveTextContent(', por Bruno')
    expect(within(linha).getByText('Dinheiro · 4 passos')).toBeInTheDocument()
    expect(within(linha).getByText(/^Recusada: O caixa foi fechado antes de esta venda chegar ao servidor\. · recebido em /))
      .toBeInTheDocument()
    expect(within(linha).getByText('2 × Bolo · R$ 3,50')).toBeInTheDocument()
    expect(within(linha).getByRole('button', { name: /^Conferir/ })).toBeInTheDocument()

    fireEvent.click(within(linha).getByRole('button', { name: /^Conferir/ }))
    await waitFor(() => expect(conferir).toHaveBeenCalledTimes(4))
    expect(conferir.mock.calls.map(([id]) => id)).toEqual(deOutro.map((revisao) => revisao.operacaoId))
    await waitFor(() => expect(estado.atualizar).toHaveBeenCalled())

    const naLista = (await cartao.findByText(/^Venda das .*R\$ 7,00$/)).closest('li') as HTMLElement
    expect(naLista).toHaveTextContent(/Conferido por Ana em/)
    expect(within(naLista).queryByRole('button', { name: /^Conferir/ })).not.toBeInTheDocument()

    // A venda que saiu deste aparelho sai também do aviso daqui.
    fireEvent.click(await cartao.findByRole('button', { name: /^Conferir Venda das/ }))
    await waitFor(() => expect(conferir).toHaveBeenCalledTimes(6))
    await waitFor(async () => {
      const depois = new Map((await listarGestos()).map((gesto) => [gesto.operacaoId, gesto]))
      expect(depois.get(local[2].operacaoId)?.conferidoEm).toEqual(expect.any(String))
    })
  })

  it('sem o início na lista, a linha diz a hora, a forma com o valor e o motivo, sem inventar o total', async () => {
    entrar('ADMIN')
    equipe()
    const [, , pagamento, conclusao] = vendaDaConta(VENDA, 12.4)
    const estoque: RevisaoDaConta = { ...vendaDaConta(OUTRA, 7)[3], resultado: 'APLICADA_COM_REVISAO',
      detalhe: 'A baixa desta venda deixou o estoque negativo: Coxinha.' }
    vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ pendentes: [pagamento, conclusao, estoque], conferidas: [] })

    mostrar('ADMIN')
    const cartao = await cartaoDaConta()

    const linhas = await cartao.findAllByText(/^Venda das \d\d:\d\d$/)
    expect(linhas).toHaveLength(2)
    const comPagamento = linhas[0].closest('li') as HTMLElement
    expect(within(comPagamento).getByText('Dinheiro R$ 12,40 · 2 passos')).toBeInTheDocument()
    const soConclusao = linhas[1].closest('li') as HTMLElement
    expect(within(soConclusao).getByText(/^Aplicada, com revisão: A baixa desta venda deixou o estoque negativo: Coxinha\./))
      .toBeInTheDocument()
    expect(soConclusao.querySelector('details')).toBeNull()
  })

  it('a conferência que falha no meio deixa a linha dizendo o que falta, e repetir só confere o resto', async () => {
    entrar('ADMIN')
    equipe()
    const revisoes = vendaDaConta(VENDA, 12.4)
    const feitas = revisoes.slice(0, 2).map((revisao) => ({ ...revisao, conferidaEm: '2026-09-26T15:00:00Z', conferidaPor: 'u-1' }))
    const lista = vi.spyOn(sincronizacao, 'revisoes')
      .mockResolvedValueOnce({ pendentes: revisoes, conferidas: [] })
      .mockResolvedValueOnce({ pendentes: revisoes.slice(2), conferidas: feitas })
      .mockResolvedValue({ pendentes: [], conferidas: feitas })
    const conferir = vi.spyOn(sincronizacao, 'conferir')
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new ErroDaApi(500, 'Erro interno', 'Tente de novo em instantes.', {}))
      .mockResolvedValue(undefined)
    mostrar('ADMIN')
    const cartao = await cartaoDaConta()

    fireEvent.click(await cartao.findByRole('button', { name: /^Conferir Venda/ }))

    const alerta = await cartao.findByRole('alert')
    expect(alerta).toHaveTextContent('Tente de novo em instantes. Foram conferidos 2 de 4 passos. Toque em Conferir de novo.')
    await waitFor(() => expect(lista).toHaveBeenCalledTimes(2))
    expect(await cartao.findByText('Faltam 2 de 4 passos por conferir.')).toBeInTheDocument()
    // A linha continua sendo a venda inteira: o que já foi conferido segue somando o total.
    expect(cartao.getByText(/^Venda das .*R\$ 12,40$/)).toBeInTheDocument()
    await waitFor(() => expect(document.activeElement).toBe(cartao.getByRole('button', { name: /^Conferir Venda/ })))

    conferir.mockClear()
    fireEvent.click(cartao.getByRole('button', { name: /^Conferir Venda/ }))
    await waitFor(() => expect(conferir.mock.calls.map(([id]) => id)).toEqual(revisoes.slice(2).map((revisao) => revisao.operacaoId)))
    await waitFor(() => expect(cartao.queryByRole('alert')).not.toBeInTheDocument())
  })

  it('sem resposta no meio da conferência, a lista fica, e a frase sai quando o servidor volta', async () => {
    entrar('ADMIN')
    equipe()
    const revisoes = vendaDaConta(VENDA, 12.4)
    vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ pendentes: revisoes, conferidas: [] })
    vi.spyOn(sincronizacao, 'conferir').mockImplementation(async () => {
      registrarSemResposta()
      throw new SemConexao()
    })
    mostrar('ADMIN')
    const cartao = await cartaoDaConta()

    fireEvent.click(await cartao.findByRole('button', { name: /^Conferir Venda/ }))

    expect(await cartao.findByRole('alert')).toHaveTextContent('Sem resposta do servidor. Toque em Conferir de novo.')
    expect(await cartao.findByRole('button', { name: /^Conferir Venda/ })).toBeInTheDocument()

    act(() => registrarResposta())
    await waitFor(() => expect(cartao.queryByRole('alert')).not.toBeInTheDocument())
  })
})

describe('sem o servidor', () => {
  it('sem resposta do servidor, diz o que espera por ele, sem a frase da escrita sem resposta', async () => {
    entrar('ADMIN')
    await enfileirarGesto({ tipo: 'caixa.sangrar', registroId: SESSAO, payload: { valor: 5, motivo: 'Cofre' } })
    const revisoes = vi.spyOn(sincronizacao, 'revisoes')
    registrarSemResposta()

    mostrar('ADMIN')

    expect(await screen.findByText('Sem resposta do servidor: o envio recomeça sozinho quando ele responder.')).toBeInTheDocument()
    expect(screen.getByText('Sem resposta do servidor: a lista da Conta aparece quando ele responder.')).toBeInTheDocument()
    expect(revisoes).not.toHaveBeenCalled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enviar agora' })).toBeEnabled()
  })

  it('a leitura da lista que fica sem resposta não vira erro na tela', async () => {
    entrar('ADMIN')
    equipe()
    vi.spyOn(sincronizacao, 'revisoes').mockImplementation(async () => {
      registrarSemResposta()
      throw new SemConexao()
    })

    mostrar('ADMIN')

    expect(await screen.findByText('Sem resposta do servidor: a lista da Conta aparece quando ele responder.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('sem rede, não lê a lista da Conta nem deixa enviar', async () => {
    entrar('ADMIN')
    await enfileirarGesto({ tipo: 'caixa.sangrar', registroId: SESSAO, payload: { valor: 5, motivo: 'Cofre' } })
    const revisoes = vi.spyOn(sincronizacao, 'revisoes')
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    window.dispatchEvent(new Event('offline'))

    mostrar('ADMIN')

    expect(await screen.findByText('Sem conexão: o envio recomeça sozinho quando a rede voltar.')).toBeInTheDocument()
    expect(screen.getByText('Sem conexão: a lista da Conta aparece quando a rede voltar.')).toBeInTheDocument()
    expect(revisoes).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Enviar agora' })).toBeDisabled()
    window.dispatchEvent(new Event('online'))
  })
})
