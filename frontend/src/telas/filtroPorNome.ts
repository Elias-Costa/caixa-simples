/**
 * A lista já carregada, só com quem tem o termo no nome. Segue a regra da busca de produto do balcão:
 * o termo é aparado e maiúsculas não contam, mas acento conta, para a mesma palavra achar os mesmos
 * itens nas duas buscas. Sem termo, a lista volta inteira.
 */
export function filtrarPorNome<T>(lista: T[], termo: string, nome: (item: T) => string): T[] {
  const alvo = termo.trim().toLocaleLowerCase('pt-BR')
  if (!alvo) return lista
  return lista.filter((item) => nome(item).toLocaleLowerCase('pt-BR').includes(alvo))
}
