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

/**
 * Os achados na ordem em que o balcão procura: primeiro os nomes que começam pelo termo, depois o resto, cada grupo
 * em ordem alfabética. A busca do balcão desenha só os primeiros, e na ordem do cadastro o mais procurado podia
 * ficar de fora.
 */
export function naOrdemDaBusca<T>(lista: T[], termo: string, nome: (item: T) => string): T[] {
  const alvo = paraBusca(termo)
  const grupo = (item: T) => paraBusca(nome(item)).startsWith(alvo) ? 0 : 1
  return [...lista].sort((a, b) => grupo(a) - grupo(b) || nome(a).localeCompare(nome(b), 'pt-BR'))
}
