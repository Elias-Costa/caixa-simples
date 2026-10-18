import { useId, useState } from 'react'
import { NavLink, useLocation } from 'react-router'
import type { ItemDoMenu } from './menu'

type Props = {
  itens: ItemDoMenu[]
}

function Item({ item }: { item: ItemDoMenu }) {
  return (
    <NavLink to={item.caminho} className={({ isActive }) =>
      isActive ? 'navegacao__item navegacao__item--ativo' : 'navegacao__item'}>
      {item.rotulo}
    </NavLink>
  )
}

/**
 * A barra de navegação. Os itens do balcão ficam sempre à vista; no tablet e no celular os outros
 * moram no botão Mais, e no desktop, onde a barra é uma coluna, aparecem todos. Cada item existe uma
 * vez só no documento: é o CSS que mostra a lista dos outros presa ao Mais ou sempre à vista.
 */
export function Navegacao({ itens }: Props) {
  const { pathname } = useLocation()
  // Aberta para a página em que foi aberta: escolhido um item, a página muda e a lista fecha sozinha,
  // sem ficar por cima da tela nova.
  const [abertaEm, setAbertaEm] = useState<string | null>(null)
  const aberta = abertaEm === pathname
  const idDosOutros = useId()
  const principais = itens.filter((item) => item.principal)
  const outros = itens.filter((item) => !item.principal)
  // Numa página de dentro do Mais, o próprio Mais fica marcado, para quem está nela saber onde está.
  const atualNoMais = outros.some((item) =>
    pathname === item.caminho || pathname.startsWith(`${item.caminho}/`))

  return (
    <nav className={aberta ? 'navegacao navegacao--aberta' : 'navegacao'} aria-label="Principal">
      {principais.map((item) => <Item key={item.caminho} item={item} />)}
      {outros.length > 0 && <>
        <button type="button" aria-expanded={aberta} aria-controls={idDosOutros}
          className={atualNoMais
            ? 'navegacao__item navegacao__mais navegacao__item--ativo' : 'navegacao__item navegacao__mais'}
          onClick={() => setAbertaEm(aberta ? null : pathname)}>
          Mais
        </button>
        <div id={idDosOutros} className="navegacao__outros">
          {outros.map((item) => <Item key={item.caminho} item={item} />)}
        </div>
      </>}
    </nav>
  )
}
