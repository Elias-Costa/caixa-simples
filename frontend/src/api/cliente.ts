import { gravarToken, lerToken } from '../sessao/armazenamento'

/** Cabeçalho pelo qual toda resposta autenticada devolve um token novo. */
export const CABECALHO_DO_TOKEN_RENOVADO = 'X-Caixa-Simples-Token'

/**
 * Resposta de erro da API, já lida.
 *
 * O servidor responde todo erro no formato Problem Details: status, título e detalhe, mais a
 * propriedade campos quando o pedido falhou na validação, com a mensagem por campo. Os status
 * têm significado fixo para a tela: 400 é corrigir o que se digitou, 401 é sessão que não vale
 * mais, 403 é operação que o perfil não alcança, 409 é estado que mudou e precisa ser recarregado.
 */
export class ErroDaApi extends Error {
  readonly status: number
  readonly titulo: string
  readonly detalhe: string | undefined
  readonly campos: Record<string, string>

  constructor(
    status: number,
    titulo: string,
    detalhe: string | undefined,
    campos: Record<string, string>,
  ) {
    super(detalhe ?? titulo)
    this.name = 'ErroDaApi'
    this.status = status
    this.titulo = titulo
    this.detalhe = detalhe
    this.campos = campos
  }
}

/** A requisição não chegou ao servidor: sem rede, ou o servidor fora do ar. */
export class SemConexao extends Error {
  constructor() {
    super('Sem conexão com o servidor')
    this.name = 'SemConexao'
  }
}

export type OpcoesDaChamada = {
  metodo?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  corpo?: unknown
  /** Falso só no login, que ainda não tem token para enviar. */
  autenticado?: boolean
}

/**
 * Faz uma chamada à API e devolve o corpo da resposta já lido.
 *
 * Toda resposta autenticada traz um token novo no cabeçalho, e é ele que a próxima requisição
 * envia: a validade do token conta do último contato com o servidor, não do login, e é isso que
 * dá ao aplicativo um dia inteiro de resistência sem rede. A troca acontece aqui, uma vez, para
 * nenhuma tela precisar lembrar disso.
 */
export async function chamarApi<T>(caminho: string, opcoes: OpcoesDaChamada = {}): Promise<T> {
  const cabecalhos = new Headers()
  if (opcoes.corpo !== undefined) cabecalhos.set('Content-Type', 'application/json')
  const token = lerToken()
  if ((opcoes.autenticado ?? true) && token) cabecalhos.set('Authorization', `Bearer ${token}`)

  let resposta: Response
  try {
    resposta = await fetch(caminho, {
      method: opcoes.metodo ?? 'GET',
      headers: cabecalhos,
      body: opcoes.corpo === undefined ? undefined : JSON.stringify(opcoes.corpo),
    })
  } catch {
    throw new SemConexao()
  }

  const tokenRenovado = resposta.headers.get(CABECALHO_DO_TOKEN_RENOVADO)
  if (tokenRenovado) gravarToken(tokenRenovado)

  if (!resposta.ok) throw await lerErro(resposta)
  if (resposta.status === 204) return undefined as T
  return (await resposta.json()) as T
}

type ProblemDetails = {
  title?: string
  detail?: string
  campos?: Record<string, string>
}

async function lerErro(resposta: Response): Promise<ErroDaApi> {
  // O 401 de credencial recusada e o de token recusado vêm sem corpo; o resto é Problem Details.
  let corpo: ProblemDetails = {}
  try {
    corpo = (await resposta.json()) as ProblemDetails
  } catch {
    corpo = {}
  }
  return new ErroDaApi(
    resposta.status,
    corpo.title ?? `Erro ${resposta.status}`,
    corpo.detail,
    corpo.campos ?? {},
  )
}
