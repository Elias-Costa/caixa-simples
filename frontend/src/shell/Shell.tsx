import { useEffect, useState } from 'react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { useRegisterSW } from 'virtual:pwa-register/react'
import type { Perfil } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'
import { avisoDoPlano } from './avisoDoPlano'
import { useAvisoDoCaixaDeOutroDia } from './caixaDeOutroDia'
import { itensDoMenu } from './menu'
import { Navegacao } from './Navegacao'
import { useCelular } from './useCelular'
import { useConexao } from './useConexao'
import { ContextoDoShellReact } from './ContextoDoShell'
import { SincronizacaoContext, useEnvioAutomatico } from './sincronizacao'

/** De quanto em quanto tempo o aplicativo aberto procura a versão nova, além da volta à tela. */
const INTERVALO_DA_PROCURA_DE_VERSAO = 60 * 60 * 1000

/**
 * A moldura de toda tela: quem está operando e em que negócio, a navegação e os avisos que
 * valem para o aplicativo inteiro (sem rede ou sem resposta do servidor, fila por enviar, revisão
 * por conferir, versão nova, vencimento do plano, caixa aberto desde outro dia), e o rodapé com o
 * aviso de privacidade e os termos de uso. É também onde a fila do aparelho é enviada sozinha, uma vez para o aplicativo
 * inteiro. Os primeiros passos não ficam aqui: só a tela Caixa os mostra, porque é ela que sabe o
 * que a pessoa já fez.
 */
export function Shell() {
  const { identidade, sair, relerIdentidade } = useSessao()
  const navegar = useNavigate()
  const { pathname } = useLocation()
  const conexao = useConexao()
  const sincronizacao = useEnvioAutomatico(identidade, sair, pathname, relerIdentidade)
  const quem = identidade ? `${identidade.contaId}:${identidade.usuarioId}` : null
  const caixaDeOutroDia = useAvisoDoCaixaDeOutroDia(quem, sincronizacao.rodada, pathname)
  // No celular, a tela de venda fica com a barra numa linha só: com o menu em duas linhas, a busca
  // começava perto do fim da tela, onde o teclado virtual a cobre. As outras telas continuam como estão.
  const balcaoNoCelular = useCelular() && pathname === '/vender'
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
        {conexao !== 'com-servidor' && (
          <span className="cabecalho__aviso" role="status">
            {conexao === 'sem-rede' ? 'Sem conexão' : 'Sem resposta do servidor'}
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

      <Navegacao itens={itensDoMenu(identidade)} compacta={balcaoNoCelular} />

      <main className="conteudo">
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
