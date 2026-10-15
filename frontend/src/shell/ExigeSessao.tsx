import { Navigate, Outlet } from 'react-router'
import { useSessao } from '../sessao/useSessao'
import { TelaDaSenha } from '../telas/TelaDaSenha'

/**
 * Rota que só existe para quem está autenticado neste dispositivo; sem sessão, vai ao login.
 *
 * Com a senha que outra pessoa escolheu, a única tela é a de troca, em qualquer rota: quem escolheu
 * a senha a conhece, e ela deixa de valer quando a pessoa escolhe a dela.
 */
export function ExigeSessao() {
  const { identidade } = useSessao()
  if (!identidade) return <Navigate to="/entrar" replace />
  if (identidade.senhaProvisoria) return <TelaDaSenha obrigatoria />
  return <Outlet />
}
