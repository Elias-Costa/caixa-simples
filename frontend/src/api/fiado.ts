import { chamarApi } from './cliente'

export type Divida = {
  vendaId: string
  clienteId: string
  nomeCliente: string
  concluidoEm: string
  saldoDevedor: number
}

export const fiado = {
  saldo: (clienteId: string) =>
    chamarApi<{ clienteId: string; saldoDevedor: number }>(
      `/api/fiado/clientes/${clienteId}/saldo`),
  dividas: () => chamarApi<Divida[]>('/api/fiado/dividas'),
}
