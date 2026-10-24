import { cadastro } from '../api/cadastro'

/**
 * O nome de cada cliente pelo cadastro deste aparelho, que guarda ativos e inativos também sem rede.
 * O removido não volta às listas, e o nome dele já foi apagado no servidor: sai como Cliente
 * removido. Sem o cadastro carregado no aparelho, o nome fica indisponível, e a tela diz isso.
 */
export async function nomesDosClientesNoAparelho(): Promise<(clienteId: string) => string> {
  try {
    const [ativos, inativos] = await Promise.all([cadastro.clientes(), cadastro.clientesInativos()])
    const nomes = new Map([...ativos, ...inativos].map((cliente) => [cliente.id, cliente.nome]))
    return (clienteId) => nomes.get(clienteId) ?? 'Cliente removido'
  } catch {
    return () => 'nome indisponível neste aparelho'
  }
}
