import { chamarApi } from './cliente'
import type { Perfil } from '../sessao/Identidade'

export type UsuarioDaConta = {
  id: string
  nome: string
  perfil: Perfil
  ativo: boolean
}

export type NovoUsuario = {
  nome: string
  perfil: Perfil
  email: string
  senha: string
}

export type ConfiguracaoDaConta = { estoqueHabilitado: boolean }

export const contas = {
  usuarios: () => chamarApi<UsuarioDaConta[]>('/api/usuarios'),
  criarUsuario: (dados: NovoUsuario) =>
    chamarApi<{ id: string }>('/api/usuarios', { metodo: 'POST', corpo: dados }),
  inativarUsuario: (id: string) =>
    chamarApi<void>(`/api/usuarios/${id}/inativar`, { metodo: 'POST' }),
  configuracao: () => chamarApi<ConfiguracaoDaConta>('/api/conta/configuracao'),
  definirEstoque: (estoqueHabilitado: boolean) =>
    chamarApi<ConfiguracaoDaConta>('/api/conta/configuracao', {
      metodo: 'PUT', corpo: { estoqueHabilitado },
    }),
}
