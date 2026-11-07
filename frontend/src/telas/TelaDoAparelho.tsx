import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { apagarDadosDoAparelho, gestosDeCadaUsuario, SessaoAbertaNoAparelho } from '../offline/fila'
import { contarPorUsuario, type ContagemDosUsuarios } from '../offline/registrosDaFila'
import { sessaoAtual } from '../sessao/armazenamento'

/**
 * Apagar o que o aplicativo guarda neste aparelho. A página é aberta pela entrada, fora do
 * aplicativo, porque o aparelho de quem foi inativado ou de uma Conta encerrada não entra mais, e
 * porque ali nenhuma sessão está usando a fila. Ela mesma é a confirmação: diz o que sai, o que fica
 * no servidor e o que se perde antes do toque, em vez de uma pergunta solta num pop-up.
 */
export function TelaDoAparelho() {
  // Sem a contagem não se sabe o que se perderia: a falha dela deixa o botão desligado.
  const [contagem, setContagem] = useState<ContagemDosUsuarios | 'falhou' | null>(null)
  const [apagando, setApagando] = useState(false)
  const [bloqueada, setBloqueada] = useState(false)
  const [apagada, setApagada] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const voltar = useRef<HTMLAnchorElement>(null)
  const comSessao = sessaoAtual() !== null

  useEffect(() => {
    let descartado = false
    gestosDeCadaUsuario()
      .then((filas) => { if (!descartado) setContagem(contarPorUsuario(filas)) })
      .catch(() => { if (!descartado) setContagem('falhou') })
    return () => { descartado = true }
  }, [])

  // O botão de apagar some sob o dedo, e o foco cairia no corpo da página; Voltar é a única ação que resta.
  useEffect(() => {
    if (apagada) voltar.current?.focus()
  }, [apagada])

  async function apagar() {
    setErro(null)
    setApagando(true)
    try {
      await apagarDadosDoAparelho(() => setBloqueada(true))
      setApagada(true)
    } catch (falha: unknown) {
      setErro(falha instanceof SessaoAbertaNoAparelho
        ? falha.message
        : 'Não foi possível apagar os dados deste aparelho. Tente de novo.')
    } finally {
      setApagando(false)
      setBloqueada(false)
    }
  }

  return (
    <main className="login">
      <section className="login__cartao aparelho" aria-labelledby="titulo-do-aparelho">
        <h1 id="titulo-do-aparelho" className="login__titulo">Apagar os dados deste aparelho</h1>
        <p>
          Para funcionar sem rede, o aplicativo guarda neste aparelho o cadastro de produtos e de
          clientes, com nome e contato, as vendas e o caixa lidos do servidor e o que foi registrado até
          ser enviado. Sair não apaga esses dados.
        </p>
        <p>
          Use antes de passar o aparelho a outra pessoa ou de se desfazer dele, no aparelho que só era
          usado por quem foi inativado e em cada aparelho de uma Conta encerrada.
        </p>

        <h2 className="aparelho__subtitulo">Sai deste aparelho</h2>
        <p>Tudo o que o aplicativo guarda neste navegador, inclusive o que ainda não foi enviado.</p>
        <h2 className="aparelho__subtitulo">Continua no servidor</h2>
        <p>
          Tudo o que já foi enviado. Ao entrar de novo, com rede, o aplicativo lê o cadastro outra vez e
          volta a funcionar sem rede.
        </p>

        {comSessao && !apagada ? <>
          <p className="mensagem-info">
            Há uma sessão aberta neste aparelho. Saia dela antes de apagar os dados.
          </p>
          <Link className="botao botao--secundario" to="/">Voltar ao aplicativo</Link>
        </> : <>
          {!apagada && <Pendencias contagem={contagem} />}
          {/* Montada com a página e vazia: a região que nasce junto com o texto pode não ser anunciada. */}
          <div role="status">
            {bloqueada && <p className="mensagem-info">Feche as outras abas do aplicativo para terminar de apagar.</p>}
            {apagada && <p className="mensagem-sucesso">Os dados deste aparelho foram apagados.</p>}
          </div>
          {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
          <div className="aparelho__acoes">
            {!apagada && (
              <button type="button" className="botao botao--perigo"
                disabled={contagem === null || contagem === 'falhou' || apagando} onClick={() => void apagar()}>
                {apagando ? 'Apagando...' : 'Apagar os dados'}
              </button>
            )}
            <Link ref={voltar} className={apagada ? 'botao' : 'botao botao--secundario'} to="/entrar">
              Voltar à entrada
            </Link>
          </div>
        </>}
      </section>
    </main>
  )
}

/** Conta como o cabeçalho e a tela Sincronização de cada usuário contam: por registro. */
function fraseDosPorEnviar(registros: number, usuarios: number): string {
  if (usuarios > 1) {
    return `${registros} registros de ${usuarios} usuários ainda não chegaram ao servidor e serão perdidos se você `
      + 'apagar agora. Para enviá-los, entre com rede usando cada um desses usuários e espere o envio.'
  }
  if (registros === 1) {
    return '1 registro ainda não chegou ao servidor e será perdido se você apagar agora. Para enviá-lo, entre '
      + 'com rede usando o usuário que o fez e espere o envio.'
  }
  return `${registros} registros ainda não chegaram ao servidor e serão perdidos se você apagar agora. Para `
    + 'enviá-los, entre com rede usando o usuário que os fez e espere o envio.'
}

/**
 * A revisão já está no servidor, mas quem opera só a vê e confere neste aparelho: apagar leva a faixa e a
 * linha dela, e a página diz onde ela continua.
 */
function fraseDaRevisao(registros: number): string {
  return registros === 1
    ? '1 registro em revisão ainda não foi conferido. Ele sai deste aparelho e continua na lista do administrador.'
    : `${registros} registros em revisão ainda não foram conferidos. Eles saem deste aparelho e continuam na lista `
      + 'do administrador.'
}

/**
 * O que não teve resultado do servidor só existe neste aparelho e não volta depois de apagado: a
 * página diz quantos são e como enviá-los antes, contando os de todos os usuários do aparelho.
 */
function Pendencias({ contagem }: { contagem: ContagemDosUsuarios | 'falhou' | null }) {
  if (contagem === null) return <p role="status">Conferindo o que ainda não foi enviado...</p>
  if (contagem === 'falhou') {
    return <p className="mensagem-erro" role="alert">
      Não foi possível conferir o que ainda não foi enviado. Recarregue a página.
    </p>
  }
  const revisao = contagem.emRevisao > 0 ? ` ${fraseDaRevisao(contagem.emRevisao)}` : ''
  if (contagem.porEnviar === 0) {
    return <p className="mensagem-info" role="status">
      {`Nada do que foi registrado neste aparelho está esperando envio.${revisao}`}
    </p>
  }
  return <p className="mensagem-alerta" role="status">
    {`${fraseDosPorEnviar(contagem.porEnviar, contagem.usuariosComPorEnviar)}${revisao}`}
  </p>
}
