import { chamarApi } from './cliente'
import type { FormaPagamento } from './vendas'

/** Datas como AAAA-MM-DD, no dia do balcão; os dois extremos entram no período. */
export type Faturamento = {
  inicio: string
  fim: string
  total: number
  quantidadeDeVendas: number
}

/** Filtro ausente é o faturamento inteiro naquela dimensão; os dois se combinam (RF24). */
export type FiltrosDoFaturamento = {
  forma?: FormaPagamento
  operadorId?: string
}

/** A unidade não vem quando o cadastro não a informou. */
export type PosicaoMaisVendida = {
  produtoId: string
  nome: string
  unidade?: string
  quantidade: number
  valor: number
}

export type MaisVendidos = {
  inicio: string
  fim: string
  posicoes: PosicaoMaisVendida[]
}

/** Só o dinheiro em espécie da gaveta: entradas são vendas, recebimentos e suprimentos. */
export type FluxoDeCaixa = {
  inicio: string
  fim: string
  vendas: number
  suprimentos: number
  sangrias: number
  estornos: number
  recebimentos: number
  entradas: number
  saidas: number
  saldo: number
}

/**
 * Filtro ausente fica fora da consulta: escrito, ele chegaria ao servidor como o texto undefined,
 * que ele recusa.
 */
function comParametros(caminho: string, parametros: Record<string, string | number | undefined>): string {
  const consulta = new URLSearchParams()
  for (const [nome, valor] of Object.entries(parametros)) {
    if (valor !== undefined) consulta.set(nome, String(valor))
  }
  return `${caminho}?${consulta.toString()}`
}

export const relatorios = {
  faturamento: (inicio: string, fim: string, filtros: FiltrosDoFaturamento = {}) =>
    chamarApi<Faturamento>(comParametros('/api/relatorios/faturamento', {
      inicio, fim, forma: filtros.forma, operadorId: filtros.operadorId,
    })),
  maisVendidos: (inicio: string, fim: string, limite: number, operadorId?: string) =>
    chamarApi<MaisVendidos>(comParametros('/api/relatorios/mais-vendidos', {
      inicio, fim, limite, operadorId,
    })),
  fluxoDeCaixa: (inicio: string, fim: string) =>
    chamarApi<FluxoDeCaixa>(comParametros('/api/relatorios/fluxo-de-caixa', { inicio, fim })),
}
