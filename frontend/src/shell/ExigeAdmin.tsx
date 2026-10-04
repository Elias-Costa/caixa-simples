import { Navigate, Outlet } from 'react-router'
import { temRecurso, type RecursoDoPlano } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'

type Props = {
  /** Além de administrador, exige que a conta tenha ligado o controle de estoque. */
  comEstoque?: boolean
  /** Além de administrador, exige o recurso do plano; sem ele, a tela Plano explica o porquê. */
  recurso?: RecursoDoPlano
}

/**
 * Rota só do administrador. É conveniência de navegação, não a autorização: quem recusa de
 * verdade é o caso de uso no servidor, com 403 pelo perfil e 409 pelo plano.
 */
export function ExigeAdmin({ comEstoque = false, recurso }: Props) {
  const { identidade } = useSessao()
  if (!identidade) return <Navigate to="/entrar" replace />
  if (identidade.perfil !== 'ADMIN') return <Navigate to="/" replace />
  if (recurso && !temRecurso(identidade, recurso)) return <Navigate to="/plano" replace />
  if (comEstoque && !identidade.estoqueHabilitado) return <Navigate to="/" replace />
  return <Outlet />
}
