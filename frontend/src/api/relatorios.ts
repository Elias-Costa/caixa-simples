import { chamarApi } from './cliente'

export type Faturamento = {
  inicio: string
  fim: string
  total: number
  quantidadeDeVendas: number
}

export const relatorios = {
  faturamentoDoDia: (dia: string) =>
    chamarApi<Faturamento>(`/api/relatorios/faturamento/dia?dia=${encodeURIComponent(dia)}`),
  faturamentoDoPeriodo: (inicio: string, fim: string) =>
    chamarApi<Faturamento>(`/api/relatorios/faturamento?inicio=${encodeURIComponent(inicio)}&fim=${encodeURIComponent(fim)}`),
}
