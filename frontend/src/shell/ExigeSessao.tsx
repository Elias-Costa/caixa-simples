import { Navigate, Outlet } from 'react-router'
import { useSessao } from '../sessao/useSessao'

/** Rota que só existe para quem está autenticado neste dispositivo; sem sessão, vai ao login. */
export function ExigeSessao() {
  const { identidade } = useSessao()
  if (!identidade) return <Navigate to="/entrar" replace />
  return <Outlet />
}
