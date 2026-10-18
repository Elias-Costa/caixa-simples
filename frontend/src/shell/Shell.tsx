import { useState } from 'react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useRegisterSW } from 'virtual:pwa-register/react'
import type { Perfil } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'
import { avisoDoPlano } from './avisoDoPlano'
import { itensDoMenu } from './menu'
import { Navegacao } from './Navegacao'
import { useOnline } from './useOnline'
import { ContextoDoShellReact } from './ContextoDoShell'
import { SincronizacaoContext, useEnvioAutomatico } from './sincronizacao'

/**
 * A moldura de toda tela: quem está operando e em que negócio, a navegação e os avisos que
 * valem para o aplicativo inteiro (sem rede, fila por enviar, revisão por conferir, versão nova,
 * vencimento do plano), e o rodapé com o aviso de privacidade e os termos de uso. É também onde a
 * fila do aparelho é enviada sozinha, uma vez para o aplicativo inteiro.
 */
export function Shell() {
  const { identidade, sair } = useSessao()
  const navegar = useNavigate()
  const { pathname } = useLocation()
  const online = useOnline()
  const sincronizacao = useEnvioAutomatico(identidade, sair, pathname)
  const [faixaDeContexto, definirFaixa] = useState<string | null>(null)
  const {
    needRefresh: [precisaAtualizar],
    updateServiceWorker,
  } = useRegisterSW()

  if (!identidade) return null
  const vencimento = avisoDoPlano(identidade)

  function encerrar() {
    sair()
    navegar('/entrar', { replace: true })
  }

  return (
    <div className="aplicativo">
      <header className="cabecalho">
        <div className="cabecalho__quem">
          <h1 className="cabecalho__negocio">{identidade.nomeNegocio}</h1>
          <p className="cabecalho__operador">
            {identidade.nome} ({rotuloDoPerfil(identidade.perfil)})
          </p>
        </div>
        {!online && (
          <span className="cabecalho__aviso" role="status">
            Sem conexão
          </span>
        )}
        {sincronizacao.enviando && (
          <span className="cabecalho__aviso" role="status">
            Enviando
          </span>
        )}
        {!sincronizacao.enviando && sincronizacao.porEnviar > 0 && (
          <Link className="cabecalho__aviso" to="/sincronizacao">
            {sincronizacao.porEnviar} por enviar
          </Link>
        )}
        {sincronizacao.emRevisao > 0 && (
          <Link className="cabecalho__aviso" to="/sincronizacao">
            {sincronizacao.emRevisao} em revisão
          </Link>
        )}
        <button type="button" className="botao botao--secundario" onClick={encerrar}>
          Sair
        </button>
      </header>

      {precisaAtualizar && (
        <div className="faixa" role="status">
          <span>Nova versão disponível.</span>
          <button type="button" className="botao" onClick={() => updateServiceWorker(true)}>
            Atualizar
          </button>
        </div>
      )}
      {vencimento && (
        <div className="faixa faixa--alerta" role="status">
          <span>{vencimento}</span>
          <Link className="botao" to="/plano">Renovar</Link>
        </div>
      )}
      {faixaDeContexto && <div className="faixa" role="status">{faixaDeContexto}</div>}

      <Navegacao itens={itensDoMenu(identidade)} />

      <main className="conteudo">
        <ContextoDoShellReact.Provider value={{ definirFaixa }}>
          <SincronizacaoContext.Provider value={sincronizacao}><Outlet /></SincronizacaoContext.Provider>
        </ContextoDoShellReact.Provider>
      </main>

      {/* Em toda tela, para os dois perfis: o aviso e os termos ficam a um toque de quem já entrou. */}
      <footer className="rodape">
        <Link to="/privacidade">Aviso de privacidade</Link> · <Link to="/termos">Termos de uso</Link>
      </footer>
    </div>
  )
}

function rotuloDoPerfil(perfil: Perfil): string {
  return perfil === 'ADMIN' ? 'administrador' : 'operador'
}
