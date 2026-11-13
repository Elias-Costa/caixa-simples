import { chamarApi, TEMPO_LIMITE_LONGO } from './cliente'

export type FormaPagamento = 'DINHEIRO' | 'PIX' | 'CARTAO' | 'FIADO'
export type StatusVenda = 'ABERTA' | 'CONCLUIDA' | 'CANCELADA'

export type ResumoDaVenda = {
  id: string
  sessaoCaixaId: string
  usuarioId: string
  status: StatusVenda
  total: number
  criadoEm: string
  /** Registrada neste dispositivo e ainda não recebida pelo servidor. */
  pendenteSincronizacao?: boolean
  /**
   * Quanto entrou por cada forma, só na lista do caixa: a parcela confirmada e a do fiado, somadas
   * por forma. Falta na resposta de um servidor anterior a ela.
   */
  formas?: ValorDaForma[]
  /**
   * Quando e por quem a venda foi cancelada. Faltam fora da venda cancelada, na cancelada antes de o
   * servidor guardá-los e na resposta de um servidor anterior a eles.
   */
  canceladaEm?: string | null
  canceladaPor?: string | null
}

export type ValorDaForma = { forma: FormaPagamento; valor: number }

export type ConciliacaoPix = {
  vendaId: string
  sessaoCaixaId: string
  status: StatusVenda
  valorPix: number
}

export type ItemDaVenda = {
  id: string
  produtoId: string
  nome: string
  /** A do cadastro; não vem quando o produto não tem, nem de um servidor anterior a ela. */
  unidade?: string | null
  quantidade: number
  precoUnitario: number
  desconto: number
  subtotal: number
}

export type ParcelaDaVenda = {
  id: string
  forma: FormaPagamento
  valor: number
  status: 'PENDENTE' | 'CONFIRMADO' | 'RECUSADO'
  troco: number
  pix?: null | { txid: string; expiraEm: string; copiaECola: string | null;
    estado: 'AGUARDANDO' | 'DISPONIVEL' | 'INCERTA' }
  /** Só no cartão, e não vem quando não foi informado. */
  nsu?: string
}

export type Venda = ResumoDaVenda & {
  clienteId: string | null
  saldoDevedor: number
  descontoDaVenda: number
  pago: number
  faltaPagar: number
  itens: ItemDaVenda[]
  parcelas: ParcelaDaVenda[]
  recebimentos: {
    id: string; sessaoCaixaId: string; valor: number; forma: FormaPagamento; criadoEm: string
    nsu?: string
  }[]
}

export type Comprovante = {
  vendaId: string
  usuarioId: string
  concluidoEm: string
  linhas: (Omit<ItemDaVenda, 'id'> & { unidade: string | null; valorBruto: number })[]
  somaDosItens: number
  descontoDaVenda: number
  valorTotal: number
  parcelas: Pick<ParcelaDaVenda, 'forma' | 'valor' | 'troco'>[]
  troco: number
  valorFiado: number
  saldoDevedor: number
  /**
   * De quem é a dívida, só na venda com parcela fiado; não vem na venda sem fiado, mesmo com
   * cliente vinculado.
   */
  nomeCliente?: string
  /** Montado no dispositivo, de uma Venda que o servidor ainda não recebeu. */
  pendenteSincronizacao?: boolean
}

export type ComprovanteDeRecebimento = {
  vendaId: string
  recebimentoId: string
  clienteId: string
  nomeCliente: string
  sessaoCaixaId: string
  recebidoEm: string
  forma: FormaPagamento
  valor: number
  saldoApos: number
}

export const vendas = {
  /** Com o id gerado no aparelho, o reenvio depois de uma resposta perdida não abre outra venda. */
  iniciar: (sessaoCaixaId: string, vendaId?: string) =>
    chamarApi<{ id: string }>('/api/vendas', { metodo: 'POST', corpo: { vendaId, sessaoCaixaId } }),
  daSessao: (sessaoCaixaId: string) =>
    chamarApi<ResumoDaVenda[]>(`/api/vendas?sessaoCaixaId=${encodeURIComponent(sessaoCaixaId)}`),
  conciliacoesPix: () => chamarApi<ConciliacaoPix[]>('/api/vendas/conciliacoes-pix'),
  consultar: (id: string) => chamarApi<Venda>(`/api/vendas/${id}`),
  vincularCliente: (id: string, clienteId: string) =>
    chamarApi<void>(`/api/vendas/${id}/cliente`, { metodo: 'PUT', corpo: { clienteId } }),
  desvincularCliente: (id: string) =>
    chamarApi<void>(`/api/vendas/${id}/cliente`, { metodo: 'DELETE' }),
  /** Com o id do item gerado no aparelho, o reenvio depois de uma resposta perdida não grava outro. */
  adicionarItem: (id: string, produtoId: string, quantidade: number, desconto: number, itemId?: string) =>
    chamarApi<{ id: string }>(`/api/vendas/${id}/itens`, {
      metodo: 'POST', corpo: { itemId, produtoId, quantidade, desconto },
    }),
  removerItem: (id: string, itemId: string) =>
    chamarApi<void>(`/api/vendas/${id}/itens/${itemId}`, { metodo: 'DELETE' }),
  descontar: (id: string, valor: number) =>
    chamarApi<void>(`/api/vendas/${id}/desconto`, { metodo: 'PUT', corpo: { valor } }),
  pagar: (id: string, forma: FormaPagamento, valor: number, valorRecebido: number | undefined,
    pagamentoId: string, nsu?: string) =>
    chamarApi<{ troco: number }>(`/api/vendas/${id}/pagamentos`, {
      metodo: 'POST', corpo: { pagamentoId, forma, valor, valorRecebido, nsu },
    }),
  cobrarPix: (id: string, tentativaId: string, valor: number) =>
    chamarApi<ParcelaDaVenda>(`/api/vendas/${id}/pagamentos/pix`, {
      metodo: 'POST', corpo: { tentativaId, valor }, tempoLimite: TEMPO_LIMITE_LONGO,
    }),
  concluir: (id: string) =>
    chamarApi<void>(`/api/vendas/${id}/conclusao`, { metodo: 'POST' }),
  /** Com Pix integrado, o servidor espera o provedor remover a cobrança antes de responder. */
  cancelar: (id: string) =>
    chamarApi<void>(`/api/vendas/${id}/cancelamento`, { metodo: 'POST', tempoLimite: TEMPO_LIMITE_LONGO }),
  comprovante: (id: string) => chamarApi<Comprovante>(`/api/vendas/${id}/comprovante`),
  receber: (id: string, valor: number, forma: FormaPagamento, recebimentoId: string, nsu?: string) =>
    chamarApi<{ id: string; saldoDevedor: number }>(`/api/vendas/${id}/recebimentos`, {
      metodo: 'POST', corpo: { recebimentoId, valor, forma, nsu },
    }),
  comprovanteDeRecebimento: (id: string, recebimentoId: string) =>
    chamarApi<ComprovanteDeRecebimento>(`/api/vendas/${id}/recebimentos/${recebimentoId}/comprovante`),
}
