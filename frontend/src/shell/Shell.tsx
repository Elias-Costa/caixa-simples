import { useEffect, useState } from 'react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useRegisterSW } from 'virtual:pwa-register/react'
import type { Perfil } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'
import { marcarTourVisto, tourVisto } from '../telas/marcaDoTour'
import { PrimeirosPassos } from '../telas/PrimeirosPassos'
import { avisoDoPlano } from './avisoDoPlano'
import { useAvisoDoCaixaDeOutroDia } from './caixaDeOutroDia'
import { itensDoMenu } from './menu'
import { Navegacao } from './Navegacao'
import { useOnline } from './useOnline'
import { ContextoDoShellReact } from './ContextoDoShell'
import { SincronizacaoContext, useEnvioAutomatico } from './sincronizacao'

/** De quanto em quanto tempo o aplicativo aberto procura a versão nova, além da volta à tela. */
const INTERVALO_DA_PROCURA_DE_VERSAO = 60 * 60 * 1000

/**
 * A moldura de toda tela: quem está operando e em que negócio, a navegação e os avisos que
 * valem para o aplicativo inteiro (sem rede, fila por enviar, revisão por conferir, versão nova,
 * vencimento do plano, caixa aberto desde outro dia), os primeiros passos no primeiro acesso de cada
 * pessoa no aparelho, e o rodapé com o aviso de privacidade e os termos de uso. É também onde a fila
 * do aparelho é enviada sozinha, uma vez para o aplicativo inteiro.
 */
export function Shell() {
  const { identidade, sair } = useSessao()
  const navegar = useNavigate()
  const { pathname } = useLocation()
  const online = useOnline()
  const sincronizacao = useEnvioAutomatico(identidade, sair, pathname)
  const quem = identidade ? `${identidade.contaId}:${identidade.usuarioId}` : null
  const caixaDeOutroDia = useAvisoDoCaixaDeOutroDia(quem, sincronizacao.rodada, pathname)
  // Quem já viu o tour neste aparelho, por Conta e pessoa. Guardado por quem, para a troca de usuário
  // na mesma aba ler a marca da pessoa nova.
  const [tourFechadoPara, setTourFechadoPara] = useState<string | null>(null)
  const [faixaDeContexto, definirFaixa] = useState<string | null>(null)
  const [vendaAberta, definirVendaAberta] = useState(false)
  const [registro, definirRegistro] = useState<ServiceWorkerRegistration>()
  const {
    needRefresh: [precisaAtualizar],
    updateServiceWorker,
  } = useRegisterSW({ onRegisteredSW: (_endereco, registration) => definirRegistro(registration) })

  // O navegador só procura a versão nova quando o aplicativo abre ou recarrega: o tablet aberto o
  // expediente inteiro no balcão e o celular que vai e volta do bolso ficariam horas na versão
  // antiga, chamando a API nova. A volta à tela alcança o celular, e o intervalo, o tablet. O
  // onRegisteredSW só entrega o registro, e a procura vive neste efeito: o Shell desmonta nas páginas
  // públicas, e cada montagem registra de novo; presa ao onRegisteredSW, cada uma somaria um
  // intervalo e um ouvinte que nada desliga.
  useEffect(() => {
    if (!registro) return
    // Sem rede a procura falha; a próxima volta ou a próxima hora tenta de novo.
    const procurar = () => { registro.update().catch(() => undefined) }
    const aoVoltarATela = () => { if (document.visibilityState === 'visible') procurar() }
    const intervalo = window.setInterval(procurar, INTERVALO_DA_PROCURA_DE_VERSAO)
    document.addEventListener('visibilitychange', aoVoltarATela)
    return () => {
      window.clearInterval(intervalo)
      document.removeEventListener('visibilitychange', aoVoltarATela)
    }
  }, [registro])

  if (!identidade) return null
  const vencimento = avisoDoPlano(identidade)
  // A página dos primeiros passos já os mostra; o painel no alto ficaria repetido.
  const mostrarTour = tourFechadoPara !== quem && pathname !== '/primeiros-passos'
    && !tourVisto(identidade)

  function fecharTour() {
    if (!identidade) return
    marcarTourVisto(identidade)
    setTourFechadoPara(quem)
  }

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

      {/* As faixas ficam empilhadas num lugar só, no alto de toda tela: mais de uma pode valer ao mesmo
          tempo, como o vencimento do plano e o caixa aberto desde ontem. */}
      <div className="faixas">
      {/* Atualizar recarrega a página, e no meio da venda fecharia a comanda com o cliente no
          balcão, perdendo o que foi digitado e não lançado. Com a venda aberta na tela, a faixa só
          avisa; o botão volta quando ela sai. */}
      {precisaAtualizar && (
        <div className="faixa" role="status">
          {vendaAberta ? <span>Nova versão disponível. Atualize quando a venda terminar.</span> : <>
            <span>Nova versão disponível.</span>
            <button type="button" className="botao" onClick={() => updateServiceWorker(true)}>
              Atualizar
            </button>
          </>}
        </div>
      )}
      {vencimento && (
        <div className="faixa faixa--alerta" role="status">
          <span>{vencimento}</span>
          <Link className="botao" to="/plano">Renovar</Link>
        </div>
      )}
      {/* Só avisa: a venda de hoje continua no caixa aberto, como sempre foi. */}
      {caixaDeOutroDia && (
        <div className="faixa faixa--alerta" role="status">
          <span>{caixaDeOutroDia}</span>
          <Link className="botao" to="/caixa">Ir ao caixa</Link>
        </div>
      )}
      {faixaDeContexto && <div className="faixa" role="status">{faixaDeContexto}</div>}
      </div>

      <Navegacao itens={itensDoMenu(identidade)} />

      <main className="conteudo">
        {mostrarTour && <PrimeirosPassos aoTerminar={fecharTour} />}
        <ContextoDoShellReact.Provider value={{ definirFaixa, definirVendaAberta }}>
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
