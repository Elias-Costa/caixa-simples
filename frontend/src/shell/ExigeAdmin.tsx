import { Navigate, Outlet } from 'react-router'
import { useSessao } from '../sessao/useSessao'

type Props = {
  /** Além de administrador, exige que a conta tenha ligado o controle de estoque. */
  comEstoque?: boolean
}

/**
 * Rota só do administrador. É conveniência de navegação, não a autorização: quem recusa de
 * verdade é o caso de uso no servidor, com 403.
 */
export function ExigeAdmin({ comEstoque = false }: Props) {
  const { identidade } = useSessao()
  if (!identidade) return <Navigate to="/entrar" replace />
  if (identidade.perfil !== 'ADMIN') return <Navigate to="/" replace />
  if (comEstoque && !identidade.estoqueHabilitado) return <Navigate to="/" replace />
  return <Outlet />
}
