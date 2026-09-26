import { useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { useRegisterSW } from 'virtual:pwa-register/react'
import type { Perfil } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'
import { itensDoMenu } from './menu'
import { useOnline } from './useOnline'
import { ContextoDoShellReact } from './ContextoDoShell'
import { SincronizacaoContext, useEnvioAutomatico } from './sincronizacao'

/**
 * A moldura de toda tela: quem está operando e em que negócio, a navegação e os avisos que
 * valem para o aplicativo inteiro (sem rede, fila por enviar, revisão por conferir, versão nova).
 * É também onde a fila do aparelho é enviada sozinha, uma vez para o aplicativo inteiro.
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
      {faixaDeContexto && <div className="faixa" role="status">{faixaDeContexto}</div>}

      <nav className="navegacao" aria-label="Principal">
        {itensDoMenu(identidade).map((item) => (
          <NavLink
            key={item.caminho}
            to={item.caminho}
            className={({ isActive }) =>
              isActive ? 'navegacao__item navegacao__item--ativo' : 'navegacao__item'
            }
          >
            {item.rotulo}
          </NavLink>
        ))}
      </nav>

      <main className="conteudo">
        <ContextoDoShellReact.Provider value={{ definirFaixa }}>
          <SincronizacaoContext.Provider value={sincronizacao}><Outlet /></SincronizacaoContext.Provider>
        </ContextoDoShellReact.Provider>
      </main>
    </div>
  )
}

function rotuloDoPerfil(perfil: Perfil): string {
  return perfil === 'ADMIN' ? 'administrador' : 'operador'
}
