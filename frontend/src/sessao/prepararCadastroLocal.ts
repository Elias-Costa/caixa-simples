import { cadastro } from '../api/cadastro'
import { cadastroCopiado } from '../offline/cadastroLocal'
import type { Identidade } from './Identidade'

/**
 * Copia para o aparelho os produtos e os clientes, para o balcão abrir sem rede. O primeiro login
 * ADMIN já copiou o catálogo no servidor; guardar as listas recebidas evita aplicar os modelos outra
 * vez.
 *
 * Com a senha provisória não copia nada: o servidor recusa toda rota fora da troca, e cada pedido
 * viraria uma recusa no registro de segurança. A tela de troca chama de novo depois da senha nova.
 */
export async function prepararCadastroLocal(atual: Identidade): Promise<void> {
  if (!('indexedDB' in globalThis) || !atual.contaId || !atual.usuarioId) return
  if (atual.senhaProvisoria) return
  await Promise.all([cadastro.produtos(), cadastro.clientes(), cadastro.clientesInativos()])
}

/**
 * A cópia que a entrada não conseguiu fazer, porque o servidor parou de responder no meio dela, é
 * feita na volta dele, sem pedir que a pessoa entre de novo. Com a cópia já guardada, não pede nada ao
 * servidor: a pergunta de quem opera, que chama esta função, sai muitas vezes.
 */
export async function copiarCadastroSeFaltar(atual: Identidade): Promise<void> {
  if (!('indexedDB' in globalThis) || !atual.contaId || !atual.usuarioId || atual.senhaProvisoria) return
  if (await cadastroCopiado()) return
  await prepararCadastroLocal(atual)
}
