import { chamarApi } from './cliente'
import type {
  Identidade, Perfil, Plano, RecursoDoPlano, SituacaoDoPlano,
} from '../sessao/Identidade'

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

/** Todas as rotas de mudança respondem a configuração inteira. */
export type ConfiguracaoDaConta = {
  nomeNegocio: string
  estoqueHabilitado: boolean
  nsuObrigatorio: boolean
}

/** O cadastro de quem entrou, lido na hora e nunca guardado no aparelho. */
export type MeusDados = { nome: string; email: string; perfil: Perfil }

export type TipoDePedido = 'ADESAO' | 'RENOVACAO'

/**
 * Um pedido que a Conta pode fazer hoje, com o valor de hoje. As datas vêm como AAAA-MM-DD; o fim
 * é exclusive, o próprio vencimento. Na adesão não há período: ele começa quando o código é
 * aplicado.
 */
export type PropostaDePlano = {
  tipo: TipoDePedido
  plano: Plano
  valor: number
  periodoInicio?: string
  periodoFim?: string
}

export type PedidoDePlano = PropostaDePlano & {
  id: string
  situacao: 'ABERTO' | 'APLICADO' | 'SUBSTITUIDO'
  criadoEm: string
}

export type EstadoDoPlano = {
  plano: Plano
  situacao: SituacaoDoPlano
  vencimento?: string
  inicioDaSuspensao?: string
  recursos: RecursoDoPlano[]
  mensalidadeCompleto: number
  propostas: PropostaDePlano[]
  pedidoAberto?: PedidoDePlano
}

export const contas = {
  usuarios: () => chamarApi<UsuarioDaConta[]>('/api/usuarios'),
  criarUsuario: (dados: NovoUsuario) =>
    chamarApi<{ id: string }>('/api/usuarios', { metodo: 'POST', corpo: dados }),
  inativarUsuario: (id: string) =>
    chamarApi<void>(`/api/usuarios/${id}/inativar`, { metodo: 'POST' }),
  anonimizarUsuario: (id: string) =>
    chamarApi<void>(`/api/usuarios/${id}/anonimizar`, { metodo: 'POST' }),
  /** O histórico de vendas e de caixas do Usuário passa a mostrar o nome corrigido. */
  corrigirNomeDoUsuario: (id: string, nome: string) =>
    chamarApi<void>(`/api/usuarios/${id}/nome`, { metodo: 'PUT', corpo: { nome } }),
  /** A senha nova é provisória: o operador a troca na próxima entrada. */
  redefinirSenha: (id: string, senha: string) =>
    chamarApi<void>(`/api/usuarios/${id}/redefinir-senha`, { metodo: 'POST', corpo: { senha } }),
  /**
   * O token deste aparelho volta no corpo: o do cabeçalho de renovação saiu antes da troca, com a
   * versão de acesso anterior, e já não vale.
   */
  trocarSenha: (senhaAtual: string, senhaNova: string) =>
    chamarApi<{ token: string }>('/api/auth/senha', {
      metodo: 'POST', corpo: { senhaAtual, senhaNova },
    }),
  sairDeTodosOsAparelhos: () => chamarApi<void>('/api/auth/sair-de-todos', { metodo: 'POST' }),
  meusDados: () => chamarApi<MeusDados>('/api/auth/meus-dados'),
  /** Os aparelhos seguem dentro: o token não leva o e-mail, e só o próximo login usa o novo. */
  trocarEmail: (senhaAtual: string, emailNovo: string) =>
    chamarApi<void>('/api/auth/email', { metodo: 'POST', corpo: { senhaAtual, emailNovo } }),
  /** A versão é a que a tela mostrou; o servidor recusa com 409 a que não é a vigente. */
  aceitarTermos: (versao: string) =>
    chamarApi<void>('/api/conta/termos', { metodo: 'POST', corpo: { versao } }),
  configuracao: () => chamarApi<ConfiguracaoDaConta>('/api/conta/configuracao'),
  definirEstoque: (estoqueHabilitado: boolean) =>
    chamarApi<ConfiguracaoDaConta>('/api/conta/configuracao', {
      metodo: 'PUT', corpo: { estoqueHabilitado },
    }),
  definirNsu: (nsuObrigatorio: boolean) =>
    chamarApi<ConfiguracaoDaConta>('/api/conta/configuracao/nsu', {
      metodo: 'PUT', corpo: { nsuObrigatorio },
    }),
  corrigirNomeDoNegocio: (nomeNegocio: string) =>
    chamarApi<ConfiguracaoDaConta>('/api/conta/configuracao/nome-do-negocio', {
      metodo: 'PUT', corpo: { nomeNegocio },
    }),
  /** Quem está operando, relido depois de uma mudança que o menu precisa refletir. */
  identidade: () => chamarApi<Identidade>('/api/auth/eu'),
  plano: () => chamarApi<EstadoDoPlano>('/api/conta/plano'),
  pedirPlano: (plano: Plano) =>
    chamarApi<PedidoDePlano>('/api/conta/plano/pedidos', { metodo: 'POST', corpo: { plano } }),
  aplicarCodigo: (pedidoId: string, codigo: string) =>
    chamarApi<EstadoDoPlano>(`/api/conta/plano/pedidos/${pedidoId}/codigo`, {
      metodo: 'POST', corpo: { codigo },
    }),
}
