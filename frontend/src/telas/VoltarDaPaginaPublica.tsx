import { Link } from 'react-router'
import { useSessao } from '../sessao/useSessao'

/**
 * O link do fim do aviso de privacidade e dos termos de uso, que se leem antes de entrar e também a
 * partir do rodapé do aplicativo. Com sessão neste aparelho, o login mandaria de volta à tela
 * inicial; o link diz isso em vez de prometer o acesso.
 */
export function VoltarDaPaginaPublica() {
  const { identidade } = useSessao()
  return identidade
    ? <p><Link to="/">Voltar para o aplicativo</Link></p>
    : <p><Link to="/entrar">Voltar para o acesso</Link></p>
}
