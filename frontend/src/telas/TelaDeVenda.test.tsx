import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { caixa } from '../api/caixa'
import { ErroDaApi } from '../api/cliente'
import { fiado } from '../api/fiado'
import { vendas, type Comprovante, type Venda } from '../api/vendas'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { confirmacaoAberta, responderConfirmacao } from './confirmacaoDeTeste'
import { TelaDeVenda } from './TelaDeVenda'

afterEach(() => { vi.restoreAllMocks(); sessionStorage.clear() })
beforeEach(() => { vi.spyOn(vendas, 'conciliacoesPix').mockResolvedValue([]) })

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
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
  }}><TelaDeVenda /></SessaoContext.Provider></MemoryRouter>)
}

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
      entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
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
    await waitFor(() => expect(adicionar).toHaveBeenCalledWith('venda-1', 'produto-1', 1, 0))
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

    await waitFor(() => expect(adicionar).toHaveBeenCalledWith('venda-1', 'produto-1', 1, 0))
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
    await waitFor(() => expect(adicionar).toHaveBeenCalledWith('venda-1', 'produto-1', 1, 0))

    await waitFor(() => expect(busca).toHaveValue(''))
    fireEvent.change(desconto, { target: { value: '2,90' } })
    fireEvent.change(busca, { target: { value: '7891' } })
    fireEvent.keyDown(busca, { key: 'Enter' })
    await waitFor(() => expect(adicionar).toHaveBeenLastCalledWith('venda-1', 'produto-1', 1, 2.9))
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
  })
})
