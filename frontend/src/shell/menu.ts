import { temRecurso, type Identidade } from '../sessao/Identidade'

export type ItemDoMenu = {
  rotulo: string
  caminho: string
}

/**
 * Os itens de navegação que quem opera pode ver.
 *
 * O menu segue a autorização do servidor, que é quem decide de fato: só o administrador escreve
 * produto, mexe no estoque, lê relatório e gere usuários, configuração e plano (RF29, RF30).
 * Esconder o item do operador evita oferecer o que a API recusaria com 403; não substitui a
 * recusa. Relatórios e Estoque aparecem só quando o plano os inclui e não estão suspensos, e a
 * tela Plano explica o que falta; o estoque também precisa do controle ligado, que nasce
 * desligado (RF17). A sincronização é dos dois perfis: quem operou sem rede confere ali o que o
 * servidor revisou ou recusou.
 */
export function itensDoMenu(identidade: Identidade): ItemDoMenu[] {
  const itens: ItemDoMenu[] = [
    { rotulo: 'Vender', caminho: '/vender' },
    { rotulo: 'Caixa', caminho: '/caixa' },
    { rotulo: 'Produtos', caminho: '/produtos' },
    { rotulo: 'Clientes', caminho: '/clientes' },
    { rotulo: 'Fiado', caminho: '/fiado' },
    { rotulo: 'Sincronização', caminho: '/sincronizacao' },
  ]
  if (identidade.perfil !== 'ADMIN') return itens

  if (identidade.estoqueHabilitado && temRecurso(identidade, 'ESTOQUE')) {
    itens.push({ rotulo: 'Estoque', caminho: '/estoque' })
  }
  if (temRecurso(identidade, 'RELATORIOS')) {
    itens.push({ rotulo: 'Relatórios', caminho: '/relatorios' })
  }
  itens.push(
    { rotulo: 'Usuários', caminho: '/usuarios' },
    { rotulo: 'Configuração', caminho: '/configuracao' },
    { rotulo: 'Plano', caminho: '/plano' },
  )
  return itens
}
