import { paraBusca } from '../textoDeBusca'

/**
 * A lista já carregada, só com quem tem o termo no nome. Segue a regra da busca de produto do balcão:
 * o termo é aparado, e maiúsculas e acento não contam, para a mesma palavra achar os mesmos itens nas
 * duas buscas. Sem termo, a lista volta inteira.
 */
export function filtrarPorNome<T>(lista: T[], termo: string, nome: (item: T) => string): T[] {
  const alvo = paraBusca(termo)
  if (!alvo) return lista
  return lista.filter((item) => paraBusca(nome(item)).includes(alvo))
}
