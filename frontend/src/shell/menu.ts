import type { Identidade } from '../sessao/Identidade'

export type ItemDoMenu = {
  rotulo: string
  caminho: string
}

/**
 * Os itens de navegação que quem opera pode ver.
 *
 * O menu segue a autorização do servidor, que é quem decide de fato: só o administrador escreve
 * produto, mexe no estoque, lê relatório e gere usuários e configuração (RF29, RF30). Esconder o
 * item do operador evita oferecer o que a API recusaria com 403; não substitui a recusa. O
 * estoque aparece só quando a conta ligou o controle, que nasce desligado (RF17).
 */
export function itensDoMenu(identidade: Identidade): ItemDoMenu[] {
  const itens: ItemDoMenu[] = [
    { rotulo: 'Vender', caminho: '/vender' },
    { rotulo: 'Caixa', caminho: '/caixa' },
    { rotulo: 'Produtos', caminho: '/produtos' },
    { rotulo: 'Clientes', caminho: '/clientes' },
  ]
  if (identidade.perfil !== 'ADMIN') return itens

  if (identidade.estoqueHabilitado) itens.push({ rotulo: 'Estoque', caminho: '/estoque' })
  itens.push(
    { rotulo: 'Relatórios', caminho: '/relatorios' },
    { rotulo: 'Usuários', caminho: '/usuarios' },
    { rotulo: 'Configuração', caminho: '/configuracao' },
  )
  return itens
}
