import { chamarApi } from './cliente'

export type FormaPagamento = 'DINHEIRO' | 'PIX' | 'CARTAO' | 'FIADO'
export type StatusVenda = 'ABERTA' | 'CONCLUIDA' | 'CANCELADA'

export type ResumoDaVenda = {
  id: string
  sessaoCaixaId: string
  usuarioId: string
  status: StatusVenda
  total: number
  criadoEm: string
}

export type ItemDaVenda = {
  id: string
  produtoId: string
  nome: string
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
}

export type Venda = ResumoDaVenda & {
  clienteId: string | null
  saldoDevedor: number
  descontoDaVenda: number
  pago: number
  faltaPagar: number
  itens: ItemDaVenda[]
  parcelas: ParcelaDaVenda[]
  recebimentos: { id: string; sessaoCaixaId: string; valor: number; forma: FormaPagamento; criadoEm: string }[]
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
  iniciar: (sessaoCaixaId: string) =>
    chamarApi<{ id: string }>('/api/vendas', { metodo: 'POST', corpo: { sessaoCaixaId } }),
  daSessao: (sessaoCaixaId: string) =>
    chamarApi<ResumoDaVenda[]>(`/api/vendas?sessaoCaixaId=${encodeURIComponent(sessaoCaixaId)}`),
  consultar: (id: string) => chamarApi<Venda>(`/api/vendas/${id}`),
  vincularCliente: (id: string, clienteId: string) =>
    chamarApi<void>(`/api/vendas/${id}/cliente`, { metodo: 'PUT', corpo: { clienteId } }),
  adicionarItem: (id: string, produtoId: string, quantidade: number, desconto: number) =>
    chamarApi<{ id: string }>(`/api/vendas/${id}/itens`, {
      metodo: 'POST', corpo: { produtoId, quantidade, desconto },
    }),
  removerItem: (id: string, itemId: string) =>
    chamarApi<void>(`/api/vendas/${id}/itens/${itemId}`, { metodo: 'DELETE' }),
  descontar: (id: string, valor: number) =>
    chamarApi<void>(`/api/vendas/${id}/desconto`, { metodo: 'PUT', corpo: { valor } }),
  pagar: (id: string, forma: FormaPagamento, valor: number, valorRecebido?: number) =>
    chamarApi<{ troco: number }>(`/api/vendas/${id}/pagamentos`, {
      metodo: 'POST', corpo: { forma, valor, valorRecebido },
    }),
  cobrarPix: (id: string, tentativaId: string, valor: number) =>
    chamarApi<ParcelaDaVenda>(`/api/vendas/${id}/pagamentos/pix`, {
      metodo: 'POST', corpo: { tentativaId, valor },
    }),
  concluir: (id: string) =>
    chamarApi<void>(`/api/vendas/${id}/conclusao`, { metodo: 'POST' }),
  cancelar: (id: string) =>
    chamarApi<void>(`/api/vendas/${id}/cancelamento`, { metodo: 'POST' }),
  comprovante: (id: string) => chamarApi<Comprovante>(`/api/vendas/${id}/comprovante`),
  receber: (id: string, valor: number, forma: FormaPagamento) =>
    chamarApi<{ id: string; saldoDevedor: number }>(`/api/vendas/${id}/recebimentos`, {
      metodo: 'POST', corpo: { valor, forma },
    }),
  comprovanteDeRecebimento: (id: string, recebimentoId: string) =>
    chamarApi<ComprovanteDeRecebimento>(`/api/vendas/${id}/recebimentos/${recebimentoId}/comprovante`),
}
