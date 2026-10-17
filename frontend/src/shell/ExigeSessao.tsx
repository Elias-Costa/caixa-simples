import { Navigate, Outlet } from 'react-router'
import { useSessao } from '../sessao/useSessao'
import { TelaDaSenha } from '../telas/TelaDaSenha'
import { TelaDosTermos } from '../telas/TelaDosTermos'

/**
 * Rota que só existe para quem está autenticado neste dispositivo; sem sessão, vai ao login.
 *
 * Com a senha que outra pessoa escolheu, a única tela é a de troca, em qualquer rota: quem escolheu
 * a senha a conhece, e ela deixa de valer quando a pessoa escolhe a dela.
 *
 * Enquanto a Conta não aceitou a versão vigente dos termos de uso, o administrador só vê a tela dos
 * termos. O operador nunca é parado por ela: o aceite é do administrador, e o balcão continua.
 */
export function ExigeSessao() {
  const { identidade } = useSessao()
  if (!identidade) return <Navigate to="/entrar" replace />
  if (identidade.senhaProvisoria) return <TelaDaSenha obrigatoria />
  if (identidade.perfil === 'ADMIN' && identidade.termosPendentes) return <TelaDosTermos obrigatoria />
  return <Outlet />
}
