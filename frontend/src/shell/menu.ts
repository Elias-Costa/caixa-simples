import { temRecurso, type Identidade } from '../sessao/Identidade'

export type ItemDoMenu = {
  rotulo: string
  caminho: string
  /**
   * Item do balcão, sempre à vista. No tablet e no celular os outros ficam no botão Mais: os dez
   * itens do administrador não cabem numa linha, e numa rolagem lateral sumiam sem aviso.
   */
  principal: boolean
}

/** O último item dos dois perfis: o tour do primeiro acesso, de novo. */
const PRIMEIROS_PASSOS: ItemDoMenu = { rotulo: 'Primeiros passos', caminho: '/primeiros-passos', principal: false }

/**
 * Os itens de navegação que quem opera pode ver.
 *
 * O menu segue a autorização do servidor, que é quem decide de fato: só o administrador escreve
 * produto, mexe no estoque, lê relatório e gere usuários, configuração e plano (RF29, RF30).
 * Esconder o item do operador evita oferecer o que a API recusaria com 403; não substitui a
 * recusa. Relatórios e Estoque aparecem só quando o plano os inclui e não estão suspensos, e a
 * tela Plano explica o que falta; o estoque também precisa do controle ligado, que nasce
 * desligado (RF17). A sincronização é dos dois perfis: quem operou sem rede confere ali o que o
 * servidor revisou ou recusou. Minha conta também: cada um vê os próprios dados, troca a própria
 * senha e sai dos próprios aparelhos. E os primeiros passos, que reabrem o tour do primeiro acesso.
 */
export function itensDoMenu(identidade: Identidade): ItemDoMenu[] {
  const itens: ItemDoMenu[] = [
    { rotulo: 'Vender', caminho: '/vender', principal: true },
    { rotulo: 'Caixa', caminho: '/caixa', principal: true },
    { rotulo: 'Produtos', caminho: '/produtos', principal: true },
    { rotulo: 'Clientes', caminho: '/clientes', principal: true },
    { rotulo: 'Fiado', caminho: '/fiado', principal: true },
    { rotulo: 'Sincronização', caminho: '/sincronizacao', principal: false },
    { rotulo: 'Minha conta', caminho: '/senha', principal: false },
  ]
  if (identidade.perfil !== 'ADMIN') return [...itens, PRIMEIROS_PASSOS]

  if (identidade.estoqueHabilitado && temRecurso(identidade, 'ESTOQUE')) {
    itens.push({ rotulo: 'Estoque', caminho: '/estoque', principal: false })
  }
  if (temRecurso(identidade, 'RELATORIOS')) {
    itens.push({ rotulo: 'Relatórios', caminho: '/relatorios', principal: false })
  }
  itens.push(
    { rotulo: 'Usuários', caminho: '/usuarios', principal: false },
    { rotulo: 'Configuração', caminho: '/configuracao', principal: false },
    { rotulo: 'Plano', caminho: '/plano', principal: false },
    PRIMEIROS_PASSOS,
  )
  return itens
}
