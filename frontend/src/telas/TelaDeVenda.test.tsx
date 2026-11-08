import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { caixa } from '../api/caixa'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { comServidor as alcancaOServidor, registrarResposta, registrarSemResposta } from '../api/conexao'
import { fiado } from '../api/fiado'
import { vendas, type Comprovante, type Venda } from '../api/vendas'
import type { VendaDaFila } from '../offline/registrosDaFila'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { SincronizacaoContext, type Sincronizacao } from '../shell/sincronizacao'
import { confirmacaoAberta, responderConfirmacao } from './confirmacaoDeTeste'
import { TelaDeVenda } from './TelaDeVenda'

afterEach(() => { vi.restoreAllMocks(); sessionStorage.clear() })
// A lista de clientes vem do servidor; sem a resposta simulada, a chamada falharia, e o aparelho
// passaria a operar como sem servidor, com a comanda só para leitura.
beforeEach(() => {
  vi.spyOn(vendas, 'conciliacoesPix').mockResolvedValue([])
  vi.spyOn(cadastro, 'clientes').mockResolvedValue([])
})

const aberta = {
  id: 'sessao-1', usuarioId: 'usuario-1', valorAbertura: 0, valorFechamentoEsperado: 0,
  valorFechamentoContado: null, diferenca: null, abertaEm: '2026-09-22T12:00:00Z',
  fechadaEm: null, status: 'ABERTA' as const,
}
const comanda: Venda = {
  id: 'venda-1', sessaoCaixaId: aberta.id, usuarioId: aberta.usuarioId,
  clienteId: null, saldoDevedor: 0, recebimentos: [],
  status: 'ABERTA', criadoEm: aberta.abertaEm, descontoDaVenda: 0, total: 12.5,
  pago: 0, faltaPagar: 12.5,
  itens: [{ id: 'item-1', produtoId: 'produto-1', nome: 'Café', quantidade: 1,
    precoUnitario: 12.5, desconto: 0, subtotal: 12.5 }], parcelas: [],
}
const comprovante: Comprovante = {
  vendaId: comanda.id, usuarioId: comanda.usuarioId,
  concluidoEm: '2026-09-22T12:05:00Z',
  linhas: [{ produtoId: 'produto-1', nome: 'Café', unidade: 'un', quantidade: 1,
    precoUnitario: 12.5, valorBruto: 12.5, desconto: 0, subtotal: 12.5 }],
  somaDosItens: 12.5, descontoDaVenda: 0, valorTotal: 12.5,
  parcelas: [{ forma: 'DINHEIRO', valor: 12.5, troco: 2.5 }], troco: 2.5,
  valorFiado: 0, saldoDevedor: 0,
}

function mostrar(perfil: 'ADMIN' | 'OPERADOR' = 'OPERADOR', extra: Partial<Identidade> = {}) {
  render(<MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'usuario-1', nome: 'Ana', perfil,
      contaId: 'conta-1', nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false, ...extra },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><TelaDeVenda /></SessaoContext.Provider></MemoryRouter>)
}

describe('venda feita sem rede e recusada na volta', () => {
  const recusada: VendaDaFila = {
    vendaId: comanda.id, criadoEm: comanda.criadoEm, totalCentavos: 1250, formas: [], dinheiroCentavos: 0,
    situacao: 'recusada', recusadaNoInicio: false, conferida: false,
    motivo: 'O cliente estava inativo quando a venda chegou ao servidor, e a venda ficou aberta, sem cliente.'
      + ' Reative o cliente em Clientes e vincule-o de novo à venda.',
  }
  const contexto = (parcial: Partial<Sincronizacao>): Sincronizacao => ({
    enviando: false, porEnviar: 0, comFalha: 0, emRevisao: 0, vendasRecusadas: [], rodada: 0,
    enviarAgora: vi.fn(), atualizar: vi.fn(), ...parcial,
  })
  const tela = (sincronizacao: Sincronizacao) => <MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'usuario-1', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta-1',
      nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><SincronizacaoContext.Provider value={sincronizacao}><TelaDeVenda /></SincronizacaoContext.Provider>
  </SessaoContext.Provider></MemoryRouter>

  function comUmaVendaNaLista(venda: Venda = comanda) {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: venda.id, sessaoCaixaId: venda.sessaoCaixaId,
      usuarioId: venda.usuarioId, status: venda.status, total: venda.total, criadoEm: venda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(venda)
  }

  it('a comanda da venda que voltou aberta diz por que voltou', async () => {
    comUmaVendaNaLista()
    render(tela(contexto({ vendasRecusadas: [recusada] })))

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))

    const frase = await screen.findByText(/^Feita sem rede, esta venda não foi concluída no servidor\./)
    expect(frase).toHaveTextContent(recusada.motivo ?? '')
    expect(frase).toHaveClass('mensagem-alerta')
  })

  it('a venda conferida continua aberta, e a comanda continua dizendo por que voltou', async () => {
    comUmaVendaNaLista()
    render(tela(contexto({ vendasRecusadas: [{ ...recusada, conferida: true }] })))

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))

    expect(await screen.findByText(/^Feita sem rede, esta venda não foi concluída no servidor\./)).toBeInTheDocument()
  })

  it('a comanda de outra venda e a venda que não está aberta seguem como eram', async () => {
    comUmaVendaNaLista()
    const { unmount } = render(tela(contexto({ vendasRecusadas: [{ ...recusada, vendaId: 'outra-venda' }] })))
    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    expect(await screen.findByText(/Venda venda-1/)).toBeInTheDocument()
    expect(screen.queryByText(/Feita sem rede/)).not.toBeInTheDocument()
    unmount()

    vi.restoreAllMocks()
    vi.spyOn(vendas, 'conciliacoesPix').mockResolvedValue([])
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([])
    comUmaVendaNaLista({ ...comanda, status: 'CANCELADA' })
    render(tela(contexto({ vendasRecusadas: [recusada] })))
    fireEvent.click(await screen.findByRole('button', { name: /Cancelada/ }))
    expect(await screen.findByText(/Venda venda-1/)).toBeInTheDocument()
    expect(screen.queryByText(/Feita sem rede/)).not.toBeInTheDocument()
  })

  it('a comanda da venda que o servidor recusou no início fecha, sem ficar aguardando a sincronização', async () => {
    comUmaVendaNaLista({ ...comanda, pendenteSincronizacao: true })
    const { rerender } = render(tela(contexto({})))
    fireEvent.click(await screen.findByRole('button', { name: /Aberta/ }))
    expect(await screen.findByText('Registrada neste aparelho. Aguardando sincronização com o servidor.'))
      .toBeInTheDocument()
    vi.spyOn(vendas, 'consultar').mockRejectedValue(
      new ErroDaApi(404, 'Não encontrado', 'Venda não encontrada nesta Conta.', {}))

    rerender(tela(contexto({ rodada: 1, vendasRecusadas: [{ ...recusada, recusadaNoInicio: true }] })))

    expect(await screen.findByText('Busque e escolha o primeiro produto para iniciar.')).toBeInTheDocument()
    expect(screen.queryByText(/Aguardando sincronização/)).not.toBeInTheDocument()
    expect(document.activeElement).toBe(screen.getByLabelText('Produto por nome ou código'))
  })

  it('outra falha na releitura deixa a comanda como está', async () => {
    comUmaVendaNaLista({ ...comanda, pendenteSincronizacao: true })
    const { rerender } = render(tela(contexto({})))
    fireEvent.click(await screen.findByRole('button', { name: /Aberta/ }))
    expect(await screen.findByText(/Aguardando sincronização/)).toBeInTheDocument()
    vi.spyOn(vendas, 'consultar').mockRejectedValue(new ErroDaApi(500, 'Erro', undefined, {}))

    rerender(tela(contexto({ rodada: 1 })))

    await waitFor(() => expect(vendas.consultar).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/Aguardando sincronização/)).toBeInTheDocument()
  })

  it('conferida a última venda da faixa, o foco volta à busca, onde o leitor de código escreve', async () => {
    comUmaVendaNaLista()
    const { rerender } = render(tela(contexto({ vendasRecusadas: [recusada] })))
    const busca = await screen.findByLabelText('Produto por nome ou código')
    busca.blur()
    expect(document.activeElement).not.toBe(busca)

    rerender(tela(contexto({ vendasRecusadas: [{ ...recusada, conferida: true }] })))

    await waitFor(() => expect(document.activeElement).toBe(busca))
  })
})

describe('PDV no tablet', () => {
  it('mostra ao ADMIN o Pix tardio que exige conciliação', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    vi.spyOn(vendas, 'conciliacoesPix').mockResolvedValue([{ vendaId: 'venda-antiga',
      sessaoCaixaId: 'sessao-fechada', status: 'ABERTA', valorPix: 12.5 }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue({ ...comanda, id: 'venda-antiga' })
    mostrar('ADMIN')
    expect(await screen.findByText(/Pix recebido: conciliação necessária/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Venda venda-an.*Pix/ }))
    await waitFor(() => expect(vendas.consultar).toHaveBeenCalledWith('venda-antiga'))
  })

  it('mostra Pix integrado pendente e não oferece conclusão antes da confirmação', async () => {
    let cobrada = false
    const parcela = { id: '00000000-0000-4000-8000-000000000001', forma: 'PIX' as const,
      valor: 12.5, status: 'PENDENTE' as const, troco: 0,
      pix: { txid: '00000000000040008000000000000001',
        expiraEm: '2099-09-22T12:15:00Z', copiaECola: 'codigo-pix',
        estado: 'DISPONIVEL' as const } }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => cobrada
      ? { ...comanda, pago: 12.5, faltaPagar: 0, parcelas: [parcela] } : comanda)
    const cobrar = vi.spyOn(vendas, 'cobrarPix').mockImplementation(async () => {
      cobrada = true; return parcela
    })
    mostrar('OPERADOR', { pixIntegrado: true })

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Forma'), { target: { value: 'PIX_INTEGRADO' } })
    expect(screen.queryByRole('button', { name: 'Receber e concluir' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))
    await waitFor(() => expect(cobrar).toHaveBeenCalledWith('venda-1', expect.any(String), 12.5))
    expect(await screen.findByText(/Pix aguardando confirmação/)).toBeInTheDocument()
    expect(screen.getByText(/codigo-pix/)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'QR Pix da cobrança' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Concluir venda' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar venda' })).toBeEnabled()

    const escrever = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: escrever }, configurable: true })
    try {
      fireEvent.click(screen.getByRole('button', { name: 'Copiar' }))
      expect(await screen.findByRole('button', { name: 'Copiado' })).toBeInTheDocument()
      expect(escrever).toHaveBeenCalledWith('codigo-pix')
    } finally {
      Reflect.deleteProperty(navigator, 'clipboard')
    }
  })

  it('o Pix manual mostra o QR do valor da chave da Conta e recebe e conclui como o cartão', async () => {
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => concluida
      ? { ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0 } : comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 0 })
    const concluir = vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    const cobrar = vi.spyOn(vendas, 'cobrarPix')
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar('OPERADOR', { chavePix: '123e4567-e89b-12d3-a456-426614174000', cidadePix: 'Ilhéus' })

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    const forma = await screen.findByLabelText('Forma')
    // Sem a marca do integrado na identidade, só o manual é oferecido.
    expect(within(forma).queryByRole('option', { name: 'Pix integrado' })).not.toBeInTheDocument()
    fireEvent.change(forma, { target: { value: 'PIX_MANUAL' } })
    expect(screen.getByRole('img', { name: 'QR Pix do valor' })).toBeInTheDocument()
    const codigo = screen.getByText(/br\.gov\.bcb\.pix/)
    expect(codigo).toHaveTextContent('5405' + '12.50')
    expect(codigo).toHaveTextContent('5915LOJA DA ESQUINA6006ILHEUS')
    fireEvent.change(screen.getByLabelText('Valor da parcela'), { target: { value: '5,00' } })
    expect(screen.getByText(/br\.gov\.bcb\.pix/)).toHaveTextContent('5404' + '5.00')
    fireEvent.change(screen.getByLabelText('Valor da parcela'), { target: { value: '' } })

    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))
    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'PIX', 12.5, undefined,
      expect.any(String), undefined))
    await waitFor(() => expect(concluir).toHaveBeenCalledWith('venda-1'))
    expect(cobrar).not.toHaveBeenCalled()
  })

  it('sem chave, o Pix manual é lançado sem QR, e só o ADMIN vê o caminho para cadastrá-la', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    const primeira = render(<MemoryRouter><SessaoContext.Provider value={{
      identidade: { usuarioId: 'usuario-1', nome: 'Ana', perfil: 'OPERADOR',
        contaId: 'conta-1', nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false },
      entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
    }}><TelaDeVenda /></SessaoContext.Provider></MemoryRouter>)

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Forma'), { target: { value: 'PIX_MANUAL' } })
    expect(screen.queryByRole('img', { name: 'QR Pix do valor' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Cadastre a chave Pix/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Receber e concluir' })).toBeEnabled()
    primeira.unmount()

    mostrar('ADMIN')
    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Forma'), { target: { value: 'PIX_MANUAL' } })
    expect(screen.getByText(/Cadastre a chave Pix/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Configuração' })).toHaveAttribute('href', '/configuracao')
  })

  it('permite cancelar Venda concluída com Pix pago e mantém aviso de devolução manual', async () => {
    let cancelada = false
    const parcela = { id: 'parcela-pix', forma: 'PIX' as const, valor: 12.5,
      status: 'CONFIRMADO' as const, troco: 0,
      pix: { txid: '12345678901234567890123456789012',
        expiraEm: '2026-09-22T12:15:00Z', copiaECola: 'codigo-pix',
        estado: 'DISPONIVEL' as const } }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'CONCLUIDA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => ({ ...comanda,
      status: cancelada ? 'CANCELADA' : 'CONCLUIDA', parcelas: [parcela],
      pago: 12.5, faltaPagar: 0 }))
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    const cancelar = vi.spyOn(vendas, 'cancelar').mockImplementation(async () => { cancelada = true })
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /Concluída.*R\$/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Cancelar venda' }))
    // A pergunta diz o valor e que o Pix pago volta ao cliente fora do sistema.
    const pergunta = await confirmacaoAberta()
    expect(pergunta).toHaveAccessibleName(/^Cancelar a venda de R\$\s12,50\?$/)
    expect(pergunta).toHaveTextContent('O Pix recebido precisa ser devolvido fora do sistema.')
    await responderConfirmacao('Cancelar venda')
    await waitFor(() => expect(cancelar).toHaveBeenCalledOnce())
    expect(await screen.findByText(/A devolução precisa ser feita fora do sistema/))
      .toHaveTextContent('não verifica se ela aconteceu')
  })

  it('não oferece QR de uma cobrança Pix removida da Venda cancelada', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'CANCELADA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue({ ...comanda, status: 'CANCELADA',
      parcelas: [{ id: 'parcela-pix', forma: 'PIX', valor: 12.5, status: 'RECUSADO',
        troco: 0, pix: { txid: '12345678901234567890123456789012',
          expiraEm: '2099-09-22T12:15:00Z', copiaECola: 'codigo-removido',
          estado: 'DISPONIVEL' } }] })
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Cancelada.*R\$/ }))
    expect(await screen.findByText(/cobrança removida/)).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'QR Pix da cobrança' })).not.toBeInTheDocument()
    expect(screen.queryByText(/codigo-removido/)).not.toBeInTheDocument()
  })

  it('conclui a venda simples em dinheiro em menos de seis toques e exibe o comprovante', async () => {
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([{ id: 'produto-1', tipo: 'PRODUTO',
      nome: 'Café', preco: 12.5, codigo: 'CA-1', categoria: null, unidade: 'un', atributos: {} }])
    const iniciar = vi.spyOn(vendas, 'iniciar').mockResolvedValue({ id: 'venda-1' })
    const adicionar = vi.spyOn(vendas, 'adicionarItem').mockResolvedValue({ id: 'item-1' })
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => concluida
      ? { ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0 } : comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 2.5 })
    const concluir = vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()

    const busca = await screen.findByLabelText('Produto por nome ou código')
    let toques = 0
    const contar = () => { toques++ }
    document.addEventListener('click', contar)
    fireEvent.change(busca, { target: { value: 'CA-1' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café · R\$\s*12,50/ }))
    await waitFor(() => expect(adicionar).toHaveBeenCalledWith('venda-1', 'produto-1', 1, 0, expect.any(String)))
    expect(iniciar).toHaveBeenCalledWith('sessao-1')
    fireEvent.click(screen.getByLabelText('Valor recebido em dinheiro'))
    fireEvent.change(screen.getByLabelText('Valor recebido em dinheiro'), { target: { value: '15.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))

    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'DINHEIRO', 12.5, 15,
      expect.any(String), undefined))
    await waitFor(() => expect(concluir).toHaveBeenCalledWith('venda-1'))
    const papel = await screen.findByLabelText('Comprovante não fiscal')
    expect(papel).toHaveTextContent('Troco: R$ 2,50')
    expect(papel).toHaveTextContent('Dinheiro: R$ 12,50')
    expect(papel).not.toHaveTextContent('DINHEIRO')
    // O comprovante logo depois de concluir é o original, e a venda sem desconto não mostra a linha.
    expect(papel).not.toHaveTextContent('Segunda via')
    expect(papel).not.toHaveTextContent('Desconto da venda')
    // A próxima venda começa sem um toque para voltar à busca.
    await waitFor(() => expect(busca).toHaveFocus())
    expect(toques).toBeLessThanOrEqual(6)
    document.removeEventListener('click', contar)
  })

  it('a nota preenche o recebido, a prévia mostra o troco e o rodapé conclui a venda', async () => {
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => concluida
      ? { ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0 } : comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 7.5 })
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    // A nota menor que a parcela não serve: o servidor recusaria o recebido menor.
    expect(await screen.findByRole('button', { name: 'R$ 10' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'R$ 20' }))
    expect(screen.getByLabelText('Valor recebido em dinheiro')).toHaveValue('20,00')
    const previa = screen.getByText('Troco previsto:').closest('p')!
    expect(previa).toHaveTextContent('R$ 7,50')
    expect(previa).toHaveTextContent('Prévia')
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))

    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'DINHEIRO', 12.5, 20,
      expect.any(String), undefined))
    // Depois de registrar, o troco à vista é o que o servidor devolveu, não mais a prévia.
    await waitFor(() => expect(screen.getAllByRole('status').map((aviso) =>
      aviso.textContent?.replace(/\s+/g, ' '))).toContain('Troco: R$ 7,50'))
    expect(screen.queryByText('Troco previsto:')).not.toBeInTheDocument()
  })

  it('aceita vírgula no dinheiro e recusa a terceira casa antes de ir ao servidor', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 2.5 })
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    const recebido = await screen.findByLabelText('Valor recebido em dinheiro')
    fireEvent.change(recebido, { target: { value: '1.500' } })
    expect(recebido).toHaveAccessibleDescription('Use até duas casas decimais, sem ponto de milhar.')
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Informe o valor recebido com até duas casas decimais.')
    expect(pagar).not.toHaveBeenCalled()

    // O símbolo e os espaços colados junto do número ficam de fora do campo.
    fireEvent.change(recebido, { target: { value: 'R$ 15,00' } })
    expect(recebido).toHaveValue('15,00')
    expect(recebido).not.toHaveAccessibleDescription()
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))
    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'DINHEIRO', 12.5, 15,
      expect.any(String), undefined))
  })

  it('o recebido abaixo da parcela aparece na prévia e não vai ao servidor', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    const pagar = vi.spyOn(vendas, 'pagar')
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Valor recebido em dinheiro'), { target: { value: '10' } })
    expect(screen.getByText('O valor recebido é menor que o da parcela.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('O valor recebido é menor que o da parcela.')
    expect(pagar).not.toHaveBeenCalled()
  })

  it('acha o cliente da venda pelo nome, sem listar todos', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([
      { id: 'cliente-1', nome: 'Lia', contato: null },
      { id: 'cliente-2', nome: 'Dona Marta', contato: '11 99999-0000' },
    ])
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    const campo = await screen.findByLabelText('Cliente (opcional)')
    expect(screen.queryByRole('button', { name: 'Lia' })).not.toBeInTheDocument()
    fireEvent.change(campo, { target: { value: 'xyz' } })
    expect(screen.getByText('Nenhum cliente encontrado para xyz.')).toBeInTheDocument()
    fireEvent.change(campo, { target: { value: 'MAR' } })
    expect(screen.getByRole('button', { name: 'Dona Marta' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Lia' })).not.toBeInTheDocument()
    // O contato não aparece na tela do balcão, que o cliente da vez também vê.
    expect(screen.queryByText(/99999/)).not.toBeInTheDocument()
  })

  it('diz quando a busca não encontra produto', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([])
    mostrar()

    fireEvent.change(await screen.findByLabelText('Produto por nome ou código'), { target: { value: 'xyz' } })
    expect(await screen.findByText('Nenhum produto encontrado para xyz.')).toBeInTheDocument()
  })

  it('mostra a recusa do item no painel da busca, onde o produto foi tocado', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([{ id: 'produto-1', tipo: 'PRODUTO',
      nome: 'Café', preco: 12.5, codigo: 'CA-1', categoria: null, unidade: 'un', atributos: {} }])
    vi.spyOn(vendas, 'iniciar').mockResolvedValue({ id: 'venda-1' })
    vi.spyOn(vendas, 'adicionarItem').mockRejectedValue(
      new ErroDaApi(409, 'Conflict', 'O produto foi inativado.', {}))
    vi.spyOn(vendas, 'consultar').mockResolvedValue({ ...comanda, itens: [], total: 0, faltaPagar: 0 })
    mostrar()

    fireEvent.change(await screen.findByLabelText('Produto por nome ou código'), { target: { value: 'CA-1' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café · R\$\s*12,50/ }))
    const painel = screen.getByRole('heading', { name: 'Busca de produtos' }).closest('section')!
    expect(await within(painel).findByRole('alert')).toHaveTextContent('O produto foi inativado.')
  })

  it('pede o NSU no cartão, manda o número sem os espaços e o mostra na lista de parcelas', async () => {
    let paga = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => paga
      ? { ...comanda, pago: 12.5, faltaPagar: 0, parcelas: [{ id: 'parcela-1', forma: 'CARTAO',
        valor: 12.5, status: 'CONFIRMADO', troco: 0, nsu: '004512' }] }
      : comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockImplementation(async () => {
      paga = true
      return { troco: 0 }
    })
    mostrar('OPERADOR', { nsuObrigatorio: true })

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    expect(await screen.findByLabelText('Forma')).toHaveValue('DINHEIRO')
    expect(screen.queryByLabelText(/NSU do comprovante/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Forma'), { target: { value: 'CARTAO' } })
    const campo = screen.getByLabelText('NSU do comprovante')
    expect(campo).toBeRequired()
    fireEvent.change(campo, { target: { value: ' 004512 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))

    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'CARTAO', 12.5, undefined,
      expect.any(String), '004512'))
    expect(await screen.findByText('Cartão: R$ 12,50 · NSU 004512', { normalizer: (texto) =>
      texto.replace(/\s+/g, ' ').trim() })).toBeInTheDocument()
  })

  it('sem a exigência, o NSU do cartão é opcional e a parcela vai sem ele', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 0 })
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Forma'), { target: { value: 'CARTAO' } })
    expect(screen.getByLabelText('NSU do comprovante (opcional)')).not.toBeRequired()
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))

    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'CARTAO', 12.5, undefined,
      expect.any(String), undefined))
  })

  it('mostra comanda ABERTA da sessão para retomar ou cancelar após recarga', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    const cancelar = vi.spyOn(vendas, 'cancelar').mockResolvedValue(undefined)
    mostrar()

    // Aberta em outro dia, a venda diz o dia além da hora, no fuso do balcão.
    expect(await screen.findByText('Este caixa tem uma venda aberta, de R$ 12,50, iniciada em 22/09, às 09:00.',
      { normalizer: (texto) => texto.replace(/\s+/g, ' ').trim() })).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    expect(await screen.findByText(/1 × Café/)).toBeInTheDocument()
    // Quem desiste na pergunta não cancela nada.
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar venda' }))
    // A venda aberta não passou pela gaveta: a pergunta não fala de estorno.
    expect(await confirmacaoAberta()).not.toHaveTextContent('estorno')
    await responderConfirmacao('Voltar')
    expect(cancelar).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar venda' }))
    await responderConfirmacao('Cancelar venda')
    await waitFor(() => expect(cancelar).toHaveBeenCalledWith('venda-1'))
  })

  it('o Enter do leitor lança o produto do código exato, mesmo antes da resposta da busca', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    // O código casa com outro produto também, pelo começo: vale o que é igual.
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([
      { id: 'produto-2', tipo: 'PRODUTO', nome: 'Café grande', preco: 15, codigo: '7891-2',
        categoria: null, unidade: 'un', atributos: {} },
      { id: 'produto-1', tipo: 'PRODUTO', nome: 'Café', preco: 12.5, codigo: '7891',
        categoria: null, unidade: 'un', atributos: {} },
    ])
    vi.spyOn(vendas, 'iniciar').mockResolvedValue({ id: 'venda-1' })
    const adicionar = vi.spyOn(vendas, 'adicionarItem').mockResolvedValue({ id: 'item-1' })
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    mostrar()

    const busca = await screen.findByLabelText('Produto por nome ou código')
    fireEvent.change(busca, { target: { value: '7891' } })
    fireEvent.keyDown(busca, { key: 'Enter' })

    await waitFor(() => expect(adicionar).toHaveBeenCalledWith('venda-1', 'produto-1', 1, 0, expect.any(String)))
    await waitFor(() => expect(busca).toHaveValue(''))

    // O mesmo código passado de novo lança outra unidade: o termo da busca anterior não fica valendo.
    fireEvent.change(busca, { target: { value: '7891' } })
    fireEvent.keyDown(busca, { key: 'Enter' })
    await waitFor(() => expect(adicionar).toHaveBeenCalledTimes(2))
  })

  it('o desconto do item nasce vazio, vale zero assim e volta vazio depois de lançar', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([
      { id: 'produto-1', tipo: 'PRODUTO', nome: 'Café', preco: 12.5, codigo: '7891',
        categoria: null, unidade: 'un', atributos: {} },
    ])
    vi.spyOn(vendas, 'iniciar').mockResolvedValue({ id: 'venda-1' })
    const adicionar = vi.spyOn(vendas, 'adicionarItem').mockResolvedValue({ id: 'item-1' })
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    mostrar('ADMIN')

    const desconto = await screen.findByLabelText('Desconto deste item')
    expect(desconto).toHaveValue('')
    expect(desconto).toHaveAttribute('placeholder', '0,00')
    const busca = screen.getByLabelText('Produto por nome ou código')
    fireEvent.change(busca, { target: { value: '7891' } })
    fireEvent.keyDown(busca, { key: 'Enter' })
    await waitFor(() => expect(adicionar).toHaveBeenCalledWith('venda-1', 'produto-1', 1, 0, expect.any(String)))

    await waitFor(() => expect(busca).toHaveValue(''))
    fireEvent.change(desconto, { target: { value: '2,90' } })
    fireEvent.change(busca, { target: { value: '7891' } })
    fireEvent.keyDown(busca, { key: 'Enter' })
    await waitFor(() => expect(adicionar).toHaveBeenLastCalledWith('venda-1', 'produto-1', 1, 2.9, expect.any(String)))
    await waitFor(() => expect(desconto).toHaveValue(''))
  })

  it('tira o cliente da venda aberta sem fiado, e com fiado só deixa trocar', async () => {
    let comCliente = true
    let fiada = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([{ id: 'cliente-1', nome: 'Lia', contato: null }])
    vi.spyOn(fiado, 'saldo').mockResolvedValue({ clienteId: 'cliente-1', saldoDevedor: 0 })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => ({ ...comanda,
      clienteId: comCliente ? 'cliente-1' : null,
      parcelas: fiada ? [{ id: 'parcela-1', forma: 'FIADO', valor: 5, status: 'PENDENTE', troco: 0 }] : [],
      pago: fiada ? 5 : 0, faltaPagar: fiada ? 7.5 : 12.5 }))
    const tirar = vi.spyOn(vendas, 'desvincularCliente').mockImplementation(async () => { comCliente = false })
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    expect(await screen.findByText('Cliente: Lia')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Tirar o cliente' }))
    await waitFor(() => expect(tirar).toHaveBeenCalledWith('venda-1'))
    await waitFor(() => expect(screen.queryByText('Cliente: Lia')).not.toBeInTheDocument())
    expect(screen.getByLabelText('Cliente (opcional)')).toBeInTheDocument()

    // Com a parcela fiado lançada, o botão some e a tela diz por quê.
    comCliente = true
    fiada = true
    fireEvent.click(screen.getByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    expect(await screen.findByText('Com parcela fiado, o cliente só pode ser trocado.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tirar o cliente' })).not.toBeInTheDocument()
  })

  it('com o Fiado escolhido, tirar o cliente volta a forma para dinheiro, e o rodapé acompanha', async () => {
    let comCliente = true
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([{ id: 'cliente-1', nome: 'Lia', contato: null }])
    vi.spyOn(fiado, 'saldo').mockResolvedValue({ clienteId: 'cliente-1', saldoDevedor: 0 })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => ({ ...comanda,
      clienteId: comCliente ? 'cliente-1' : null }))
    vi.spyOn(vendas, 'desvincularCliente').mockImplementation(async () => { comCliente = false })
    const pagar = vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 0 })
    vi.spyOn(vendas, 'concluir').mockResolvedValue(undefined)
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Forma'), { target: { value: 'FIADO' } })
    expect(screen.getByRole('button', { name: 'Registrar fiado e concluir' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Tirar o cliente' }))
    await waitFor(() => expect(screen.queryByText('Cliente: Lia')).not.toBeInTheDocument())
    expect(screen.queryByRole('option', { name: 'Fiado' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Forma')).toHaveValue('DINHEIRO')
    expect(screen.getByLabelText('Valor recebido em dinheiro')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar fiado e concluir' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))
    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'DINHEIRO', 12.5, 12.5,
      expect.any(String), undefined))
  })

  it('com a parcela fiado lançada, o Fiado sai do seletor e a parcela seguinte vai em dinheiro', async () => {
    let fiada = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([{ id: 'cliente-1', nome: 'Lia', contato: null }])
    vi.spyOn(fiado, 'saldo').mockResolvedValue({ clienteId: 'cliente-1', saldoDevedor: 0 })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => ({ ...comanda, clienteId: 'cliente-1',
      parcelas: fiada ? [{ id: 'parcela-1', forma: 'FIADO', valor: 5, status: 'PENDENTE', troco: 0 }] : [],
      pago: fiada ? 5 : 0, faltaPagar: fiada ? 7.5 : 12.5 }))
    const pagar = vi.spyOn(vendas, 'pagar').mockImplementation(async () => { fiada = true; return { troco: 0 } })
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Forma'), { target: { value: 'FIADO' } })
    fireEvent.change(screen.getByLabelText('Valor da parcela'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))
    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'FIADO', 5, undefined,
      expect.any(String), undefined))

    expect(await screen.findByText('Com parcela fiado, o cliente só pode ser trocado.')).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Fiado' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Forma')).toHaveValue('DINHEIRO')
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))
    await waitFor(() => expect(pagar).toHaveBeenLastCalledWith('venda-1', 'DINHEIRO', 7.5, 7.5,
      expect.any(String), undefined))
  })

  it('o Enter não lança quando o nome digitado acha vários produtos', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([
      { id: 'produto-1', tipo: 'PRODUTO', nome: 'Café', preco: 12.5, codigo: '7891',
        categoria: null, unidade: 'un', atributos: {} },
      { id: 'produto-2', tipo: 'PRODUTO', nome: 'Café grande', preco: 15, codigo: '7892',
        categoria: null, unidade: 'un', atributos: {} },
    ])
    const iniciar = vi.spyOn(vendas, 'iniciar')
    mostrar()

    const busca = await screen.findByLabelText('Produto por nome ou código')
    fireEvent.change(busca, { target: { value: 'café' } })
    fireEvent.keyDown(busca, { key: 'Enter' })

    expect(await screen.findByRole('button', { name: /Café grande/ })).toBeInTheDocument()
    expect(iniciar).not.toHaveBeenCalled()
  })

  it('administrador vincula Cliente e conclui fiado com valor pendente no comprovante', async () => {
    let vinculada = false
    let paga = false
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([{ id: 'cliente-1', nome: 'Lia', contato: null }])
    vi.spyOn(fiado, 'saldo').mockResolvedValue({ clienteId: 'cliente-1', saldoDevedor: 0 })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => ({ ...comanda,
      clienteId: vinculada ? 'cliente-1' : null,
      status: concluida ? 'CONCLUIDA' : 'ABERTA',
      pago: paga ? 12.5 : 0, faltaPagar: paga ? 0 : 12.5,
      saldoDevedor: concluida ? 12.5 : 0,
      parcelas: paga ? [{ id: 'parcela-1', forma: 'FIADO', valor: 12.5,
        status: 'PENDENTE', troco: 0 }] : [],
    }))
    const vincular = vi.spyOn(vendas, 'vincularCliente').mockImplementation(async () => { vinculada = true })
    const pagar = vi.spyOn(vendas, 'pagar').mockImplementation(async () => {
      paga = true; return { troco: 0 }
    })
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue({ ...comprovante,
      parcelas: [{ forma: 'FIADO', valor: 12.5, troco: 0 }], troco: 0,
      valorFiado: 12.5, saldoDevedor: 12.5, nomeCliente: 'Lia',
    })
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Cliente (opcional)'), { target: { value: 'li' } })
    fireEvent.click(screen.getByRole('button', { name: 'Lia' }))
    await waitFor(() => expect(vincular).toHaveBeenCalledWith('venda-1', 'cliente-1'))
    expect(await screen.findByText('Cliente: Lia')).toBeInTheDocument()
    expect(screen.getByLabelText('Trocar o cliente')).toHaveValue('')
    fireEvent.change(await screen.findByLabelText('Forma'), { target: { value: 'FIADO' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar fiado e concluir' }))
    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'FIADO', 12.5, undefined,
      expect.any(String), undefined))
    const papel = await screen.findByLabelText('Comprovante não fiscal')
    expect(papel).toHaveTextContent('Valor pendente: R$ 12,50')
    // O papel que prova a dívida diz de quem ela é.
    expect(papel).toHaveTextContent('Cliente: Lia')
    // Fiado não devolve troco: a linha não aparece.
    expect(papel).not.toHaveTextContent('Troco')
  })

  it('a venda concluída reaberta pelo histórico sai como segunda via, com o desconto e sem troco no Pix', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id, sessaoCaixaId: aberta.id,
      usuarioId: aberta.usuarioId, status: 'CONCLUIDA', total: 11.5, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue({ ...comanda, status: 'CONCLUIDA',
      descontoDaVenda: 1, total: 11.5, pago: 11.5, faltaPagar: 0,
      parcelas: [{ id: 'parcela-1', forma: 'PIX', valor: 11.5, status: 'CONFIRMADO', troco: 0 }] })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue({ ...comprovante, descontoDaVenda: 1,
      valorTotal: 11.5, parcelas: [{ forma: 'PIX', valor: 11.5, troco: 0 }], troco: 0 })
    const compartilhar = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { value: compartilhar, configurable: true })
    try {
      mostrar()

      fireEvent.click(await screen.findByRole('button', { name: /Concluída.*R\$/ }))
      const papel = await screen.findByLabelText('Comprovante não fiscal')
      expect(papel).toHaveTextContent(/Segunda via, reimpressa em \d{2}\/\d{2}\/\d{4}/)
      expect(papel).toHaveTextContent('Desconto da venda: R$ 1,00')
      expect(papel).toHaveTextContent('Pix: R$ 11,50')
      expect(papel).not.toHaveTextContent('Troco')

      fireEvent.click(within(papel).getByRole('button', { name: 'Compartilhar' }))
      await waitFor(() => expect(compartilhar).toHaveBeenCalled())
      const texto = compartilhar.mock.calls[0][0].text as string
      expect(texto).toContain('Segunda via, reimpressa em')
      expect(texto).toMatch(/Desconto da venda: R\$\s1,00/)
      expect(texto).not.toContain('Troco')
    } finally {
      Reflect.deleteProperty(navigator, 'share')
    }
  })

  it('a quantidade sai com vírgula e unidade na comanda, no papel e no texto, e o papel vem antes da lista', async () => {
    const pao = { id: 'item-pao', produtoId: 'produto-pao', nome: 'Pão francês', unidade: 'kg',
      quantidade: 0.35, precoUnitario: 16.9, desconto: 0, subtotal: 5.92 }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id, sessaoCaixaId: aberta.id,
      usuarioId: aberta.usuarioId, status: 'CONCLUIDA', total: 5.92, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue({ ...comanda, status: 'CONCLUIDA', total: 5.92,
      pago: 5.92, faltaPagar: 0, itens: [pao],
      parcelas: [{ id: 'parcela-1', forma: 'DINHEIRO', valor: 5.92, status: 'CONFIRMADO', troco: 0 }] })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue({ ...comprovante, somaDosItens: 5.92, valorTotal: 5.92,
      linhas: [{ produtoId: 'produto-pao', nome: 'Pão francês', unidade: 'kg', quantidade: 0.35,
        precoUnitario: 16.9, valorBruto: 5.92, desconto: 0, subtotal: 5.92 }],
      parcelas: [{ forma: 'DINHEIRO', valor: 5.92, troco: 0 }], troco: 0 })
    const compartilhar = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'share', { value: compartilhar, configurable: true })
    try {
      mostrar()

      fireEvent.click(await screen.findByRole('button', { name: /Concluída.*R\$/ }))
      expect(await screen.findByText(/^0,35 kg × Pão francês · R\$\s5,92$/)).toBeInTheDocument()
      const papel = await screen.findByLabelText('Comprovante não fiscal')
      expect(papel).toHaveTextContent('0,35 kg × Pão francês')
      // O papel vem logo depois da comanda, antes da lista do caixa, que pode ter milhares de pixels.
      const lista = screen.getByRole('heading', { name: 'Vendas deste caixa' })
      expect(papel.compareDocumentPosition(lista) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

      fireEvent.click(within(papel).getByRole('button', { name: 'Compartilhar' }))
      await waitFor(() => expect(compartilhar).toHaveBeenCalled())
      expect(compartilhar.mock.calls[0][0].text).toMatch(/0,35 kg × Pão francês: R\$\s5,92/)
    } finally {
      Reflect.deleteProperty(navigator, 'share')
    }
  })

  it('a busca põe o código igual primeiro e depois o nome que começa pelo termo, com o preço por unidade', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    const buscar = vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([
      { id: 'p-1', tipo: 'PRODUTO', nome: 'Coxinha de pão', preco: 7, codigo: null, categoria: null,
        unidade: 'un', atributos: {} },
      { id: 'p-2', tipo: 'PRODUTO', nome: 'Pão francês', preco: 16.9, codigo: '2000004', categoria: null,
        unidade: 'kg', atributos: {} },
      { id: 'p-3', tipo: 'PRODUTO', nome: 'Pão de queijo', preco: 3.5, codigo: null, categoria: null,
        unidade: null, atributos: {} },
    ])
    mostrar()
    const resultados = () => screen.getAllByRole('button', { name: /R\$/ })
      .map((botao) => botao.textContent?.replace(/\s/g, ' '))

    const busca = await screen.findByLabelText('Produto por nome ou código')
    fireEvent.change(busca, { target: { value: 'pão' } })
    await screen.findByRole('button', { name: /Coxinha de pão/ })
    expect(resultados()).toEqual(['Pão de queijo · R$ 3,50', 'Pão francês · R$ 16,90/kg · 2000004',
      'Coxinha de pão · R$ 7,00/un'])

    buscar.mockResolvedValue([
      { id: 'p-4', tipo: 'PRODUTO', nome: 'Café grande', preco: 15, codigo: '7891-2', categoria: null,
        unidade: null, atributos: {} },
      { id: 'p-5', tipo: 'PRODUTO', nome: 'Café', preco: 12.5, codigo: '7891', categoria: null,
        unidade: null, atributos: {} },
    ])
    fireEvent.change(busca, { target: { value: '7891' } })
    await waitFor(() => expect(resultados()).toEqual(['Café · R$ 12,50 · 7891', 'Café grande · R$ 15,00 · 7891-2']))
  })

  it('a lista do caixa mostra as dez mais recentes, com a hora e as formas, e Ver todas alterna', async () => {
    const agora = Date.now()
    const resumos = Array.from({ length: 12 }, (_, indice) => ({
      id: `venda-${indice}`, sessaoCaixaId: aberta.id, usuarioId: aberta.usuarioId,
      status: 'CONCLUIDA' as const, total: 12.5, criadoEm: new Date(agora - indice * 1000).toISOString(),
      formas: [{ forma: 'DINHEIRO' as const, valor: 2.5 }, { forma: 'CARTAO' as const, valor: 10 }],
    }))
    // A mais antiga é de outro dia, como num caixa aberto desde ontem, e sem parcela que valha.
    resumos[11] = { ...resumos[11], criadoEm: new Date(agora - 2 * 24 * 60 * 60 * 1000).toISOString(), formas: [] }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue(resumos)
    mostrar()

    const lista = (await screen.findByRole('heading', { name: 'Vendas deste caixa' })).closest('section')!
    await waitFor(() => expect(within(lista).getAllByRole('listitem')).toHaveLength(10))
    expect(within(lista).getAllByRole('listitem')[0])
      .toHaveTextContent(/^\d{2}:\d{2} · Concluída · R\$\s12,50 · Dinheiro e Cartão$/)
    const verTodas = within(lista).getByRole('button', { name: 'Ver todas (12)' })
    expect(verTodas).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(verTodas)
    expect(within(lista).getAllByRole('listitem')).toHaveLength(12)
    expect(within(lista).getAllByRole('listitem')[11])
      .toHaveTextContent(/^\d{2}\/\d{2} \d{2}:\d{2} · Concluída · R\$\s12,50$/)
    const dezMais = within(lista).getByRole('button', { name: 'Ver só as dez mais recentes' })
    expect(dezMais).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(dezMais)
    expect(within(lista).getAllByRole('listitem')).toHaveLength(10)
  })

  it('avisa as vendas abertas do caixa, e o Retomar abre a mais recente com o foco de volta na busca', async () => {
    const resumo = { sessaoCaixaId: aberta.id, usuarioId: aberta.usuarioId }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([
      { ...resumo, id: 'venda-1', status: 'ABERTA', total: 12.5, criadoEm: new Date().toISOString() },
      { ...resumo, id: 'venda-2', status: 'ABERTA', total: 8, criadoEm: new Date(Date.now() - 1000).toISOString() },
      { ...resumo, id: 'venda-3', status: 'CONCLUIDA', total: 3, criadoEm: new Date(Date.now() - 2000).toISOString() },
    ])
    const consultar = vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    mostrar()

    const painel = (await screen.findByRole('heading', { name: 'Comanda' })).closest('section')!
    expect(await within(painel).findByText(
      /^Este caixa tem 2 vendas abertas\. A mais recente é de R\$\s12,50, iniciada às \d{2}:\d{2}\.$/))
      .toBeInTheDocument()
    // Começar outra continua possível.
    expect(within(painel).getByText('Busque e escolha o primeiro produto para iniciar.')).toBeInTheDocument()

    fireEvent.click(within(painel).getByRole('button', { name: 'Retomar' }))
    await waitFor(() => expect(consultar).toHaveBeenCalledWith('venda-1'))
    expect(await within(painel).findByText(/1 × Café/)).toBeInTheDocument()
    // Com a venda aberta na comanda, o aviso sai: quem vende já escolheu em qual continua.
    expect(within(painel).queryByRole('button', { name: 'Retomar' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Produto por nome ou código')).toHaveFocus())
  })

  it('a venda do administrador no caixa do operador aparece na lista só para leitura e fora do aviso', async () => {
    const doCaixa = { sessaoCaixaId: aberta.id }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([
      { ...doCaixa, id: 'venda-adm-1', usuarioId: 'admin-1', status: 'ABERTA', total: 8,
        criadoEm: new Date().toISOString() },
      { ...doCaixa, id: 'venda-adm-2', usuarioId: 'admin-1', status: 'CONCLUIDA', total: 12.5,
        criadoEm: new Date(Date.now() - 1000).toISOString(), formas: [{ forma: 'DINHEIRO', valor: 12.5 }] },
      { ...doCaixa, id: 'venda-1', usuarioId: aberta.usuarioId, status: 'CONCLUIDA', total: 3,
        criadoEm: new Date(Date.now() - 2000).toISOString() },
    ])
    const consultar = vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    mostrar()

    const lista = (await screen.findByRole('heading', { name: 'Vendas deste caixa' })).closest('section')!
    await waitFor(() => expect(within(lista).getAllByRole('listitem')).toHaveLength(3))
    const [abertaDoAdmin, concluidaDoAdmin, minha] = within(lista).getAllByRole('listitem')
    expect(abertaDoAdmin).toHaveTextContent(/^\d{2}:\d{2} · Aberta · R\$\s8,00 · do administrador$/)
    expect(concluidaDoAdmin).toHaveTextContent(/^\d{2}:\d{2} · Concluída · R\$\s12,50 · Dinheiro · do administrador$/)
    expect(within(abertaDoAdmin).queryByRole('button')).not.toBeInTheDocument()
    expect(within(concluidaDoAdmin).queryByRole('button')).not.toBeInTheDocument()
    expect(within(minha).getByRole('button')).toBeInTheDocument()
    // A comanda do administrador não é desta pessoa retomar: o aviso de venda aberta não a conta.
    const painel = screen.getByRole('heading', { name: 'Comanda' }).closest('section')!
    expect(within(painel).queryByRole('button', { name: 'Retomar' })).not.toBeInTheDocument()
    expect(within(painel).queryByText(/Este caixa tem/)).not.toBeInTheDocument()
    expect(consultar).not.toHaveBeenCalled()
  })

  it('o administrador abre pela lista a venda de qualquer usuário do caixa', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id, sessaoCaixaId: aberta.id,
      usuarioId: 'outro-1', status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    const consultar = vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    await waitFor(() => expect(consultar).toHaveBeenCalledWith(comanda.id))
    expect(screen.queryByText(/do administrador/)).not.toBeInTheDocument()
  })

  it('sem caixa aberto, Ir ao caixa é um botão', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    mostrar()

    const ir = await screen.findByRole('link', { name: 'Ir ao caixa' })
    expect(ir).toHaveAttribute('href', '/caixa')
    expect(ir).toHaveClass('botao')
  })
})

describe('a página ao terminar a venda', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  // Sem matchMedia a tela conta como larga; aqui ela responde como a do celular ou a do tablet.
  function comLargura(celular: boolean) {
    vi.stubGlobal('matchMedia', () => ({ matches: !celular, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    return vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
  }

  /** Lança um item pela busca e deixa a venda pronta para Receber e concluir. */
  async function lancarUmItem() {
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([{ id: 'produto-1', tipo: 'PRODUTO',
      nome: 'Café', preco: 12.5, codigo: 'CA-1', categoria: null, unidade: 'un', atributos: {} }])
    vi.spyOn(vendas, 'iniciar').mockResolvedValue({ id: 'venda-1' })
    vi.spyOn(vendas, 'adicionarItem').mockResolvedValue({ id: 'item-1' })
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => concluida
      ? { ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0 } : comanda)
    vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 0 })
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()
    const busca = await screen.findByLabelText('Produto por nome ou código')
    fireEvent.change(busca, { target: { value: 'CA-1' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café · R\$\s*12,50/ }))
    const concluir = await screen.findByRole('button', { name: 'Receber e concluir' })
    await waitFor(() => expect(busca).toHaveFocus())
    await waitFor(() => expect(concluir).toBeEnabled())
    return busca
  }

  it('no celular, concluir volta ao alto antes do foco na busca, e lançar o item não volta', async () => {
    const rolar = comLargura(true)
    const busca = await lancarUmItem()
    // No meio da venda a pessoa continua onde está.
    expect(rolar).not.toHaveBeenCalled()

    const foco = vi.spyOn(busca, 'focus')
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))
    await screen.findByLabelText('Comprovante não fiscal')
    await waitFor(() => expect(foco).toHaveBeenCalledOnce())

    expect(rolar).toHaveBeenCalledOnce()
    expect(rolar).toHaveBeenCalledWith(0, 0)
    // Na ordem contrária, o foco centralizaria a busca, e a página não voltaria para onde foi mandada.
    expect(rolar.mock.invocationCallOrder[0]).toBeLessThan(foco.mock.invocationCallOrder[0])
  })

  it('no celular, Nova venda e o cancelamento voltam ao alto, e retomar a venda não', async () => {
    const rolar = comLargura(true)
    let cancelada = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => cancelada ? { ...comanda, status: 'CANCELADA' } : comanda)
    vi.spyOn(vendas, 'cancelar').mockImplementation(async () => { cancelada = true })
    mostrar()

    const busca = await screen.findByLabelText('Produto por nome ou código')
    fireEvent.click(await screen.findByRole('button', { name: 'Retomar' }))
    await screen.findByRole('button', { name: 'Cancelar venda' })
    await waitFor(() => expect(busca).toHaveFocus())
    expect(rolar).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Nova venda' }))
    expect(rolar).toHaveBeenCalledOnce()
    expect(busca).toHaveFocus()
    rolar.mockClear()

    fireEvent.click(await screen.findByRole('button', { name: 'Retomar' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Cancelar venda' }))
    await confirmacaoAberta()
    await responderConfirmacao('Cancelar venda')
    await waitFor(() => expect(rolar).toHaveBeenCalledOnce())
    expect(rolar).toHaveBeenCalledWith(0, 0)
  })

  it('no tablet, a página fica onde está: a busca é presa, e o comprovante continua à vista', async () => {
    const rolar = comLargura(false)
    const busca = await lancarUmItem()
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))
    await screen.findByLabelText('Comprovante não fiscal')
    await waitFor(() => expect(busca).toHaveFocus())
    expect(rolar).not.toHaveBeenCalled()
  })
})

describe('o botão principal ao abrir a comanda', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  // O jsdom não calcula layout: o fim do rodapé na tela vem da prova, e a altura da tela é a dele, 768. A lista
  // de resultados à vista empurra o rodapé, como no navegador; medido com ela, a página desceria a mais.
  function comRodapeEm(baixo: number, celular = true) {
    vi.stubGlobal('matchMedia', () => ({ matches: !celular, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    const original = HTMLElement.prototype.getBoundingClientRect
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (!this.classList.contains('pdv__rodape')) return original.call(this)
      const empurrado = document.querySelector('.pdv__resultados') ? 100 : 0
      return { bottom: baixo + empurrado } as DOMRect
    })
    return vi.spyOn(window, 'scrollBy').mockImplementation(() => undefined)
  }

  function simularVenda() {
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    // A lista do caixa demora, como a do servidor: é nesse intervalo que a comanda aparece na tela.
    vi.spyOn(vendas, 'daSessao').mockImplementation(async () => {
      await new Promise((resolver) => setTimeout(resolver, 20))
      return []
    })
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([{ id: 'produto-1', tipo: 'PRODUTO',
      nome: 'Café', preco: 12.5, codigo: 'CA-1', categoria: null, unidade: 'un', atributos: {} }])
    vi.spyOn(vendas, 'iniciar').mockResolvedValue({ id: 'venda-1' })
    vi.spyOn(vendas, 'adicionarItem').mockResolvedValue({ id: 'item-1' })
    // Um objeto novo a cada leitura, como a resposta do servidor: é o que distingue a mesma venda da venda seguinte.
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => concluida
      ? { ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0 } : { ...comanda })
    vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 0 })
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    return { reabrir: () => { concluida = false } }
  }

  async function lancar(busca: HTMLElement) {
    fireEvent.change(busca, { target: { value: 'CA-1' } })
    const resultado = await screen.findByRole('button', { name: /Café · R\$\s*12,50/ })
    // Enquanto a chamada anterior termina, o resultado fica desligado, e o toque não lançaria nada.
    await waitFor(() => expect(resultado).toBeEnabled())
    fireEvent.click(resultado)
    const concluir = await screen.findByRole('button', { name: 'Receber e concluir' })
    await waitFor(() => expect(busca).toHaveFocus())
    await waitFor(() => expect(concluir).toBeEnabled())
  }

  it('no celular, com o botão abaixo da tela, rola só o que falta, e o foco fica na busca', async () => {
    const rolar = comRodapeEm(828)
    simularVenda()
    mostrar()
    const busca = await screen.findByLabelText('Produto por nome ou código')
    expect(rolar).not.toHaveBeenCalled()

    await lancar(busca)

    expect(rolar).toHaveBeenCalledOnce()
    expect(rolar).toHaveBeenCalledWith(0, 60)
    expect(busca).toHaveFocus()
  })

  it('o segundo item não rola de novo, e a venda seguinte rola outra vez', async () => {
    const rolar = comRodapeEm(828)
    const venda = simularVenda()
    mostrar()
    const busca = await screen.findByLabelText('Produto por nome ou código')
    await lancar(busca)
    expect(rolar).toHaveBeenCalledOnce()

    // A pessoa já decidiu onde a página fica; mais um item não a tira de lá.
    await lancar(busca)
    expect(rolar).toHaveBeenCalledOnce()

    venda.reabrir()
    fireEvent.click(screen.getByRole('button', { name: 'Nova venda' }))
    vi.mocked(vendas.iniciar).mockResolvedValue({ id: 'venda-2' })
    vi.mocked(vendas.consultar).mockImplementation(async () => ({ ...comanda, id: 'venda-2' }))
    await lancar(busca)
    expect(rolar).toHaveBeenCalledTimes(2)
  })

  it('trocar direto para outra venda aberta, sem passar por Nova venda, rola de novo', async () => {
    const rolar = comRodapeEm(828)
    const resumo = (id: string, total: number) => ({ id, sessaoCaixaId: aberta.id, usuarioId: aberta.usuarioId,
      status: 'ABERTA' as const, total, criadoEm: aberta.abertaEm })
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([resumo('venda-1', 12.5), resumo('venda-2', 20)])
    vi.spyOn(vendas, 'consultar').mockImplementation(async (id) => ({ ...comanda, id }))
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*12,50.*retomar ou cancelar/ }))
    await screen.findByRole('button', { name: 'Receber e concluir' })
    expect(rolar).toHaveBeenCalledOnce()

    fireEvent.click(screen.getByRole('button', { name: /Aberta.*20,00.*retomar ou cancelar/ }))
    await waitFor(() => expect(rolar).toHaveBeenCalledTimes(2))
  })

  it('o botão que aparece depois, com o primeiro item recusado antes, também rola', async () => {
    const rolar = comRodapeEm(828)
    simularVenda()
    const semItens = { ...comanda, itens: [], total: 0, faltaPagar: 0 }
    vi.spyOn(vendas, 'adicionarItem').mockRejectedValueOnce(new ErroDaApi(409, 'Conflict', 'Sem estoque.', {}))
    vi.mocked(vendas.consultar).mockResolvedValueOnce(semItens)
    mostrar()
    const busca = await screen.findByLabelText('Produto por nome ou código')
    fireEvent.change(busca, { target: { value: 'CA-1' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café · R\$\s*12,50/ }))
    await screen.findByText('Nenhum item lançado.')
    expect(rolar).not.toHaveBeenCalled()

    await lancar(busca)
    expect(rolar).toHaveBeenCalledOnce()
  })

  it('ao concluir, volta ao alto e desce só o que falta para o troco aparecer inteiro', async () => {
    const descer = comRodapeEm(828)
    simularVenda()
    mostrar()
    await lancar(await screen.findByLabelText('Produto por nome ou código'))
    descer.mockClear()

    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))
    await screen.findByLabelText('Comprovante não fiscal')
    await waitFor(() => expect(descer).toHaveBeenCalledOnce())

    expect(descer).toHaveBeenCalledWith(0, 60)
    // Na ordem contrária, a volta ao alto desfaria a descida, e o troco ficaria cortado.
    const subir = vi.mocked(window.scrollTo)
    expect(subir.mock.invocationCallOrder[0]).toBeLessThan(descer.mock.invocationCallOrder[0])
  })

  it('com o botão à vista não rola', async () => {
    const rolar = comRodapeEm(768)
    simularVenda()
    mostrar()
    await lancar(await screen.findByLabelText('Produto por nome ou código'))
    expect(rolar).not.toHaveBeenCalled()
  })

  it('a venda concluída aberta pela lista não rola: o rodapé dela não tem botão principal', async () => {
    const rolar = comRodapeEm(828)
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'CONCLUIDA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue({ ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0 })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Concluída.*R\$/ }))
    await screen.findByLabelText('Comprovante não fiscal')
    expect(rolar).not.toHaveBeenCalled()
  })

  it('no tablet não rola, nem ao abrir nem ao concluir: lá o painel da comanda começa no alto', async () => {
    const rolar = comRodapeEm(1100, false)
    simularVenda()
    mostrar()
    await lancar(await screen.findByLabelText('Produto por nome ou código'))
    expect(rolar).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))
    await screen.findByLabelText('Comprovante não fiscal')
    await waitFor(() => expect(screen.getByLabelText('Produto por nome ou código')).toHaveFocus())
    expect(rolar).not.toHaveBeenCalled()
  })
})

describe('a frase da venda feita sem rede', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  const LONGA = 'Registrada neste aparelho. Aguardando sincronização com o servidor.'
  const CURTA = 'Registrada neste aparelho.'

  /** Abre pela lista uma venda da fila do aparelho, aberta, e deixa a conclusão pronta para o toque. */
  async function abrirVendaDoAparelho(celular: boolean, pendente = true) {
    vi.stubGlobal('matchMedia', () => ({ matches: !celular, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm, pendenteSincronizacao: pendente }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => concluida
      ? { ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0, pendenteSincronizacao: pendente }
      : { ...comanda, pendenteSincronizacao: pendente })
    vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 0 })
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()
    fireEvent.click(await screen.findByRole('button', { name: /Aberta/ }))
    const concluir = await screen.findByRole('button', { name: 'Receber e concluir' })
    await waitFor(() => expect(concluir).toBeEnabled())
    return concluir
  }

  /** O rodapé preso é o bloco do total, que é a linha que ele sempre tem. */
  const rodape = () => screen.getByText('Total:').closest('p')!.parentElement!

  it('no celular, ao concluir, a curta aparece no rodapé, numa região já montada, e a longa sai', async () => {
    const concluir = await abrirVendaDoAparelho(true)
    expect(screen.getByText(LONGA)).toBeInTheDocument()
    const regiao = within(rodape()).getByRole('status')
    expect(regiao).not.toHaveTextContent(CURTA)

    fireEvent.click(concluir)
    await screen.findByLabelText('Comprovante não fiscal')

    expect(regiao).toHaveTextContent(CURTA)
    expect(regiao).toBeInTheDocument()
    expect(screen.queryByText(LONGA)).not.toBeInTheDocument()
  })

  it('no tablet, a longa continua no painel, sem a curta nem a região no rodapé', async () => {
    const concluir = await abrirVendaDoAparelho(false)
    expect(within(rodape()).queryByRole('status')).not.toBeInTheDocument()

    fireEvent.click(concluir)
    await screen.findByLabelText('Comprovante não fiscal')

    expect(screen.getByText(LONGA)).toBeInTheDocument()
    expect(screen.queryByText(CURTA)).not.toBeInTheDocument()
  })

  it('no celular, a venda que está no servidor conclui sem frase nenhuma', async () => {
    const concluir = await abrirVendaDoAparelho(true, false)
    fireEvent.click(concluir)
    await screen.findByLabelText('Comprovante não fiscal')

    expect(screen.queryByText(CURTA)).not.toBeInTheDocument()
    expect(screen.queryByText(LONGA)).not.toBeInTheDocument()
  })
})

describe('o último item no rodapé', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  const cafe = { ...comanda.itens[0], unidade: 'un' }
  const pao = { id: 'item-2', produtoId: 'produto-2', nome: 'Pão de queijo', quantidade: 2, unidade: 'un',
    precoUnitario: 3.5, desconto: 0, subtotal: 7 }
  const rodape = () => screen.getByText('Total:').closest('p')!.parentElement!
  const regiao = () => within(rodape()).getByRole('status')

  /** Abre pela lista a venda com um item; o retorno troca o que a próxima leitura da venda devolve. */
  async function abrir(celular: boolean) {
    vi.stubGlobal('matchMedia', () => ({ matches: !celular, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    let venda: Venda = { ...comanda, itens: [cafe] }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => venda)
    mostrar()
    fireEvent.click(await screen.findByRole('button', { name: /Aberta/ }))
    const concluir = await screen.findByRole('button', { name: 'Receber e concluir' })
    await waitFor(() => expect(concluir).toBeEnabled())
    return { concluir, trocarVenda: (nova: Venda) => { venda = nova } }
  }

  it('no celular, mostra o último lançado com o preço, e acompanha o item seguinte', async () => {
    const { trocarVenda } = await abrir(true)
    expect(regiao()).toHaveTextContent('Último: 1 un × Café')
    expect(within(regiao()).getByText('R$ 12,50')).toBeInTheDocument()

    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([{ id: 'produto-2', tipo: 'PRODUTO',
      nome: 'Pão de queijo', preco: 3.5, codigo: 'PQ-1', categoria: null, unidade: 'un', atributos: {} }])
    vi.spyOn(vendas, 'adicionarItem').mockResolvedValue({ id: 'item-2' })
    trocarVenda({ ...comanda, itens: [cafe, pao], total: 19.5, faltaPagar: 19.5 })
    fireEvent.change(screen.getByLabelText('Produto por nome ou código'), { target: { value: 'PQ-1' } })
    const resultado = await screen.findByRole('button', { name: /Pão de queijo · R\$\s*3,50/ })
    fireEvent.click(resultado)

    await waitFor(() => expect(regiao()).toHaveTextContent('Último: 2 un × Pão de queijo'))
    expect(within(regiao()).getByText('R$ 7,00')).toBeInTheDocument()
    expect(regiao()).not.toHaveTextContent('Café')
  })

  it('a prévia do troco tira a linha, e ela volta quando a prévia sai', async () => {
    await abrir(true)
    fireEvent.click(screen.getByRole('button', { name: 'R$ 20' }))
    await screen.findByText('Troco previsto:')
    expect(regiao()).toBeEmptyDOMElement()

    fireEvent.change(screen.getByLabelText('Valor recebido em dinheiro'), { target: { value: '' } })
    await waitFor(() => expect(regiao()).toHaveTextContent('Último: 1 un × Café'))
  })

  it('ao concluir, a linha sai', async () => {
    const { concluir, trocarVenda } = await abrir(true)
    vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 0 })
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => {
      trocarVenda({ ...comanda, itens: [cafe], status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0 })
    })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    fireEvent.click(concluir)
    await screen.findByLabelText('Comprovante não fiscal')
    expect(screen.queryByText(/Último:/)).not.toBeInTheDocument()
  })

  it('no tablet não aparece: lá a lista de itens está à vista', async () => {
    await abrir(false)
    expect(within(rodape()).queryByText(/Último:/)).not.toBeInTheDocument()
  })

  it('a comanda só para leitura não mostra a linha: a ação ali é começar outra venda', async () => {
    await abrir(true)
    act(() => { registrarSemResposta() })
    await screen.findByText(/Esta venda está no servidor/)
    expect(screen.queryByText(/Último:/)).not.toBeInTheDocument()
  })
})

describe('o rodapé da comanda', () => {
  it('sem parcela não repete o total em Falta lançar, e com parcela diz o que falta e o que foi lançado', async () => {
    const parcela = { id: 'parcela-1', forma: 'DINHEIRO' as const, valor: 5, status: 'CONFIRMADO' as const, troco: 0 }
    const consultar = vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    await screen.findByRole('button', { name: 'Receber e concluir' })
    expect(screen.queryByText(/Falta lançar/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Parcelas lançadas/)).not.toBeInTheDocument()

    consultar.mockResolvedValue({ ...comanda, parcelas: [parcela], pago: 5, faltaPagar: 7.5 })
    fireEvent.click(screen.getByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    expect(await screen.findByText(/Falta lançar/)).toHaveTextContent('R$ 7,50')
    expect(screen.getByText(/Parcelas lançadas/)).toHaveTextContent('R$ 5,00')
  })
})

describe('comanda do servidor sem servidor', () => {
  it('fica só para leitura, com a frase e Nova venda no rodapé como única ação', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    const cancelar = vi.spyOn(vendas, 'cancelar')
    mostrar('ADMIN')
    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    expect(await screen.findByRole('button', { name: 'Receber e concluir' })).toBeInTheDocument()

    act(() => registrarSemResposta())

    const frase = 'Esta venda está no servidor e continua quando a rede voltar. Para vender agora, comece uma nova venda.'
    expect(await screen.findAllByText(frase)).toHaveLength(1)
    expect(screen.getByText(/1 × Café/)).toBeInTheDocument()
    for (const nome of ['Cancelar venda', 'Remover', 'Registrar parcela', 'Receber e concluir',
      'Concluir venda', 'Aplicar']) {
      expect(screen.queryByRole('button', { name: nome })).not.toBeInTheDocument()
    }
    expect(screen.queryByLabelText('Cliente (opcional)')).not.toBeInTheDocument()
    const novas = screen.getAllByRole('button', { name: 'Nova venda' })
    expect(novas).toHaveLength(1)
    expect(novas[0]).toHaveClass('botao--largo')

    fireEvent.click(novas[0])
    expect(await screen.findByText('Busque e escolha o primeiro produto para iniciar.')).toBeInTheDocument()
    expect(screen.getByLabelText('Produto por nome ou código')).toHaveFocus()
    expect(cancelar).not.toHaveBeenCalled()
  })

  it('o produto tocado começa outra venda, e a comanda do servidor fica para retomar', async () => {
    const nova: Venda = { ...comanda, id: 'venda-2', itens: [{ ...comanda.itens[0], id: 'item-2', nome: 'Pão' }] }
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async (id: string) => id === nova.id ? nova : comanda)
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([{ id: 'produto-2', tipo: 'PRODUTO',
      nome: 'Pão', preco: 12.5, codigo: 'PA-1', categoria: null, unidade: 'un', atributos: {} }])
    const iniciar = vi.spyOn(vendas, 'iniciar').mockResolvedValue({ id: nova.id })
    const adicionar = vi.spyOn(vendas, 'adicionarItem').mockResolvedValue({ id: 'item-2' })
    mostrar()
    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    await screen.findByRole('button', { name: 'Receber e concluir' })
    act(() => registrarSemResposta())

    fireEvent.change(screen.getByLabelText('Produto por nome ou código'), { target: { value: 'PA-1' } })
    fireEvent.click(await screen.findByRole('button', { name: /Pão · R\$\s*12,50/ }))

    await waitFor(() => expect(adicionar).toHaveBeenCalledWith('venda-2', 'produto-2', 1, 0, expect.any(String)))
    expect(iniciar).toHaveBeenCalledWith('sessao-1')
    expect(adicionar).not.toHaveBeenCalledWith('venda-1', expect.anything(), expect.anything(), expect.anything(),
      expect.anything())
    expect(await screen.findByText(/Venda venda-2/)).toBeInTheDocument()
  })
})

describe('escrita sem resposta, relida quando o servidor volta', () => {
  // Uma chamada atrasada do teste anterior pode ter deixado o aparelho sem servidor.
  beforeEach(() => { registrarResposta() })

  const parcelaGravada = { id: 'parcela-1', forma: 'DINHEIRO' as const, valor: 12.5, status: 'CONFIRMADO' as const,
    troco: 7.5 }
  const pao = { id: 'produto-2', tipo: 'PRODUTO' as const, nome: 'Pão', preco: 12.5, codigo: 'PA-1',
    categoria: null, unidade: 'un', atributos: {} }

  /** O cliente HTTP marca o servidor sem resposta antes de recusar a chamada; o simulado faz o mesmo. */
  function semResposta(): never {
    registrarSemResposta()
    throw new SemConexao()
  }

  /**
   * O servidor simulado guarda as vendas, e sem servidor a leitura também fica sem resposta, como a chamada de
   * verdade: a comanda à vista continua a de antes.
   */
  function servidorCom(...iniciais: Venda[]) {
    const gravadas = new Map(iniciais.map((venda) => [venda.id, venda]))
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockImplementation(async () => [...gravadas.values()].map((venda) => ({
      id: venda.id, sessaoCaixaId: venda.sessaoCaixaId, usuarioId: venda.usuarioId, status: venda.status,
      total: venda.total, criadoEm: venda.criadoEm })))
    const consultar = vi.spyOn(vendas, 'consultar').mockImplementation(async (id: string) => {
      if (!alcancaOServidor()) throw new SemConexao()
      return ler(id)
    })
    function ler(id: string): Venda {
      const venda = gravadas.get(id)
      if (!venda) throw new Error('venda desconhecida no teste')
      return venda
    }
    return { consultar, ler, gravar(venda: Venda) { gravadas.set(venda.id, venda) } }
  }

  function comPao(venda: Venda, itemId: string): Venda {
    return { ...venda, total: venda.total + 12.5, faltaPagar: venda.faltaPagar + 12.5,
      itens: [...venda.itens, { id: itemId, produtoId: pao.id, nome: 'Pão', quantidade: 1, precoUnitario: 12.5,
        desconto: 0, subtotal: 12.5 }] }
  }

  async function passarOPao() {
    fireEvent.change(screen.getByLabelText('Produto por nome ou código'), { target: { value: 'PA-1' } })
    fireEvent.click(await screen.findByRole('button', { name: /Pão · R\$\s*12,50/ }))
  }

  it('a conclusão sem resposta depois da parcela diz que a parcela foi registrada, e na volta a venda aparece concluída com o comprovante', async () => {
    const servidor = servidorCom(comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockImplementation(async () => {
      servidor.gravar({ ...servidor.ler('venda-1'), pago: 12.5, faltaPagar: 0, parcelas: [parcelaGravada] })
      return { troco: 7.5 }
    })
    const concluir = vi.spyOn(vendas, 'concluir').mockImplementation(async () => {
      // A conclusão grava, e a resposta se perde.
      servidor.gravar({ ...servidor.ler('venda-1'), status: 'CONCLUIDA' })
      return semResposta()
    })
    const recibo = vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'R$ 20' }))
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))

    expect(await screen.findByText('A parcela foi registrada, mas o servidor não respondeu à conclusão. A comanda '
      + 'mostra se a venda foi concluída quando ele responder.')).toBeInTheDocument()
    expect(screen.queryByText(/Sem conexão/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Comprovante não fiscal')).not.toBeInTheDocument()

    act(() => registrarResposta())

    const papel = await screen.findByLabelText('Comprovante não fiscal')
    expect(papel).not.toHaveTextContent('Segunda via')
    expect(screen.getByText('Venda venda-1 · Concluída')).toBeInTheDocument()
    expect(screen.queryByText(/não respondeu à conclusão/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Receber e concluir' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Produto por nome ou código')).toHaveFocus())
    expect(pagar).toHaveBeenCalledTimes(1)
    expect(concluir).toHaveBeenCalledTimes(1)
    expect(recibo).toHaveBeenCalledWith('venda-1')
  })

  it('Concluir venda sem resposta diz que o servidor não respondeu, e na volta a venda aparece concluída', async () => {
    const paga: Venda = { ...comanda, pago: 12.5, faltaPagar: 0, parcelas: [parcelaGravada] }
    const servidor = servidorCom(paga)
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => {
      servidor.gravar({ ...servidor.ler('venda-1'), status: 'CONCLUIDA' })
      return semResposta()
    })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Concluir venda' }))
    expect(await screen.findByText('O servidor não respondeu à conclusão. A comanda mostra se a venda foi '
      + 'concluída quando ele responder.')).toBeInTheDocument()

    act(() => registrarResposta())

    expect(await screen.findByLabelText('Comprovante não fiscal')).toBeInTheDocument()
    expect(screen.getByText('Venda venda-1 · Concluída')).toBeInTheDocument()
    expect(screen.queryByText(/não respondeu à conclusão/)).not.toBeInTheDocument()
  })

  it('a conclusão que não chegou deixa a venda aberta na volta, com a frase até o próximo toque', async () => {
    const paga: Venda = { ...comanda, pago: 12.5, faltaPagar: 0, parcelas: [parcelaGravada] }
    servidorCom(paga)
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => semResposta())
    const recibo = vi.spyOn(vendas, 'comprovante')
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Concluir venda' }))
    await screen.findByText(/O servidor não respondeu à conclusão/)

    act(() => registrarResposta())

    expect(await screen.findByRole('button', { name: 'Concluir venda' })).toBeEnabled()
    expect(screen.getByText('Venda venda-1 · Aberta')).toBeInTheDocument()
    expect(screen.getByText(/O servidor não respondeu à conclusão/)).toBeInTheDocument()
    expect(recibo).not.toHaveBeenCalled()
  })

  it('a parcela sem resposta manda conferir a comanda, sem dizer sem conexão, e na volta a comanda mostra a parcela e libera o id', async () => {
    const servidor = servidorCom(comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockImplementationOnce(async (_id, forma, valor, _recebido,
      pagamentoId) => {
      servidor.gravar({ ...servidor.ler('venda-1'), pago: valor, faltaPagar: 12.5 - valor,
        parcelas: [{ ...parcelaGravada, id: pagamentoId, forma, valor, troco: 0 }] })
      return semResposta()
    }).mockResolvedValue({ troco: 0 })
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Valor da parcela'), { target: { value: '5,00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))
    expect(await screen.findByText('Sem resposta do servidor. Confira a comanda antes de repetir.'))
      .toBeInTheDocument()
    expect(screen.queryByText(/Sem conexão/)).not.toBeInTheDocument()

    act(() => registrarResposta())

    expect(await screen.findByText(/Falta lançar/)).toHaveTextContent('R$ 7,50')
    expect(screen.getByLabelText('Valor da parcela')).toHaveValue('')
    // A parcela que a comanda mostra já valeu: a próxima, mesmo de igual valor, é outra.
    fireEvent.change(screen.getByLabelText('Valor da parcela'), { target: { value: '5,00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar parcela' }))
    await waitFor(() => expect(pagar).toHaveBeenCalledTimes(2))
    expect(pagar.mock.calls[1][4]).not.toBe(pagar.mock.calls[0][4])
  })

  it('o item gravado sem resposta aparece na comanda na volta do servidor, e o mesmo código depois disso é outra unidade', async () => {
    const servidor = servidorCom(comanda)
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([pao])
    const adicionar = vi.spyOn(vendas, 'adicionarItem').mockImplementationOnce(async (id, _produto, _quantidade,
      _desconto, itemId) => {
      servidor.gravar(comPao(servidor.ler(id), itemId ?? 'sem-id'))
      return semResposta()
    }).mockImplementation(async (id, _produto, _quantidade, _desconto, itemId) => {
      servidor.gravar(comPao(servidor.ler(id), itemId ?? 'sem-id'))
      return { id: itemId ?? 'sem-id' }
    })
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    await screen.findByRole('button', { name: 'Receber e concluir' })
    await passarOPao()
    expect(await screen.findByText('Sem resposta do servidor. Confira a comanda antes de repetir.'))
      .toBeInTheDocument()
    expect(screen.queryByText(/× Pão/)).not.toBeInTheDocument()

    act(() => registrarResposta())

    expect(await screen.findByText(/1 × Pão/)).toBeInTheDocument()
    expect(adicionar).toHaveBeenCalledTimes(1)
    const primeiroId = adicionar.mock.calls[0][4]
    expect(primeiroId).toEqual(expect.any(String))

    // Quem vê o item na comanda e passa o código de novo quer mais um.
    await passarOPao()
    await waitFor(() => expect(adicionar).toHaveBeenCalledTimes(2))
    expect(adicionar.mock.calls[1][4]).not.toBe(primeiroId)
    await waitFor(() => expect(screen.getAllByText(/1 × Pão/)).toHaveLength(2))
  })

  it('o item que não chegou ao servidor é repetido com o mesmo id', async () => {
    servidorCom(comanda)
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([pao])
    const adicionar = vi.spyOn(vendas, 'adicionarItem').mockImplementationOnce(async () => semResposta())
      .mockResolvedValue({ id: 'item-2' })
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    await screen.findByRole('button', { name: 'Receber e concluir' })
    await passarOPao()
    await screen.findByText('Sem resposta do servidor. Confira a comanda antes de repetir.')
    act(() => registrarResposta())
    await screen.findByRole('button', { name: 'Receber e concluir' })

    await passarOPao()
    await waitFor(() => expect(adicionar).toHaveBeenCalledTimes(2))
    expect(adicionar.mock.calls[0][4]).toEqual(expect.any(String))
    expect(adicionar.mock.calls[1][4]).toBe(adicionar.mock.calls[0][4])
  })

  it('a venda nova cujo primeiro item ficou sem resposta toma o lugar da concluída e aparece com ele na volta', async () => {
    const concluida: Venda = { ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0, parcelas: [parcelaGravada] }
    const nova: Venda = { ...comanda, id: 'venda-2', total: 0, faltaPagar: 0, itens: [] }
    const servidor = servidorCom(concluida)
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([pao])
    vi.spyOn(vendas, 'iniciar').mockImplementation(async () => {
      servidor.gravar(nova)
      return { id: nova.id }
    })
    vi.spyOn(vendas, 'adicionarItem').mockImplementation(async (id, _produto, _quantidade, _desconto, itemId) => {
      servidor.gravar(comPao(servidor.ler(id), itemId ?? 'sem-id'))
      return semResposta()
    })
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Concluída/ }))
    await screen.findByLabelText('Comprovante não fiscal')
    await passarOPao()
    await screen.findByText('Sem resposta do servidor. Confira a comanda antes de repetir.')
    expect(await screen.findByText('Busque e escolha o primeiro produto para iniciar.')).toBeInTheDocument()

    act(() => registrarResposta())

    expect(await screen.findByText('Venda venda-2 · Aberta')).toBeInTheDocument()
    expect(screen.getByText(/1 × Pão/)).toBeInTheDocument()
  })

  it('Nova venda sem servidor deixa a comanda anterior para trás também na volta', async () => {
    const servidor = servidorCom(comanda)
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([pao])
    vi.spyOn(vendas, 'adicionarItem').mockImplementation(async (id, _produto, _quantidade, _desconto, itemId) => {
      servidor.gravar(comPao(servidor.ler(id), itemId ?? 'sem-id'))
      return semResposta()
    })
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar ou cancelar/ }))
    await screen.findByRole('button', { name: 'Receber e concluir' })
    await passarOPao()
    await screen.findByText('Sem resposta do servidor. Confira a comanda antes de repetir.')
    fireEvent.click(await screen.findByRole('button', { name: 'Nova venda' }))
    expect(await screen.findByText('Busque e escolha o primeiro produto para iniciar.')).toBeInTheDocument()
    const leiturasAntes = servidor.consultar.mock.calls.length
    const listasAntes = vi.mocked(vendas.daSessao).mock.calls.length

    act(() => registrarResposta())

    // A volta relê a lista do caixa; a comanda continua vazia, e a venda anterior não é lida de novo.
    await waitFor(() => expect(vi.mocked(vendas.daSessao).mock.calls.length).toBeGreaterThan(listasAntes))
    await act(async () => { await new Promise((resolver) => setTimeout(resolver, 50)) })
    expect(screen.getByText('Busque e escolha o primeiro produto para iniciar.')).toBeInTheDocument()
    expect(servidor.consultar.mock.calls.length).toBe(leiturasAntes)
  })
})
