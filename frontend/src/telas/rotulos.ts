import type { TipoProduto } from '../api/cadastro'
import type { MovimentoCaixa, SessaoCaixa } from '../api/caixa'
import type { FormaPagamento, ParcelaDaVenda, StatusVenda } from '../api/vendas'

/*
 * Como cada valor da API aparece para quem opera. Na API os valores são identificadores, em
 * maiúsculas e sem acento; na tela e no comprovante vai a palavra do balcão.
 */

export const ROTULO_DA_VENDA: Record<StatusVenda, string> = {
  ABERTA: 'Aberta',
  CONCLUIDA: 'Concluída',
  CANCELADA: 'Cancelada',
}

export const ROTULO_DA_FORMA: Record<FormaPagamento, string> = {
  DINHEIRO: 'Dinheiro',
  PIX: 'Pix',
  CARTAO: 'Cartão',
  FIADO: 'Fiado',
}

/**
 * O tipo diz se o item controla estoque. Serviço era o nome que fazia o café parecer errado: o que
 * se prepara na hora, como o serviço, nunca baixa estoque, e o balcão o chama pelo que importa.
 */
export const ROTULO_DO_TIPO: Record<TipoProduto, string> = {
  PRODUTO: 'Com estoque',
  SERVICO: 'Sem estoque (serviço ou preparado na hora)',
}

/** Na lista, onde a linha é curta. */
export const ROTULO_CURTO_DO_TIPO: Record<TipoProduto, string> = {
  PRODUTO: 'Com estoque',
  SERVICO: 'Sem estoque',
}

/** A sessão é o caixa do operador, e caixa é palavra masculina. */
export const ROTULO_DO_CAIXA: Record<SessaoCaixa['status'], string> = {
  ABERTA: 'Aberto',
  FECHADA: 'Fechado',
}

export const ROTULO_DO_MOVIMENTO: Record<MovimentoCaixa['tipo'], string> = {
  VENDA: 'Venda',
  SANGRIA: 'Sangria',
  SUPRIMENTO: 'Suprimento',
  ESTORNO: 'Estorno',
  RECEBIMENTO: 'Recebimento de fiado',
}

type EstadoDaCobrancaPix = NonNullable<ParcelaDaVenda['pix']>['estado']

export const ROTULO_DA_COBRANCA_PIX: Record<EstadoDaCobrancaPix, string> = {
  AGUARDANDO: 'aguardando',
  DISPONIVEL: 'disponível',
  INCERTA: 'incerta',
}
