import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { apagarDadosDoAparelho, contarGestosSemResultado, SessaoAbertaNoAparelho } from '../offline/fila'
import { sessaoAtual } from '../sessao/armazenamento'

/**
 * Apagar o que o aplicativo guarda neste aparelho. A página é aberta pela entrada, fora do
 * aplicativo, porque o aparelho de quem foi inativado ou de uma Conta encerrada não entra mais, e
 * porque ali nenhuma sessão está usando a fila. Ela mesma é a confirmação: diz o que sai, o que fica
 * no servidor e o que se perde antes do toque, em vez de uma pergunta solta num pop-up.
 */
export function TelaDoAparelho() {
  // Sem a contagem não se sabe o que se perderia: a falha dela deixa o botão desligado.
  const [semResultado, setSemResultado] = useState<number | 'falhou' | null>(null)
  const [apagando, setApagando] = useState(false)
  const [bloqueada, setBloqueada] = useState(false)
  const [apagada, setApagada] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const comSessao = sessaoAtual() !== null

  useEffect(() => {
    let descartado = false
    contarGestosSemResultado()
      .then((quantos) => { if (!descartado) setSemResultado(quantos) })
      .catch(() => { if (!descartado) setSemResultado('falhou') })
    return () => { descartado = true }
  }, [])

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
          clientes, com nome e contato, as vendas e o caixa lidos do servidor e o que foi registrado sem
          rede até ser enviado. Sair não apaga esses dados.
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

        {apagada ? <>
          <p className="mensagem-sucesso" role="status">Os dados deste aparelho foram apagados.</p>
          <Link className="botao" to="/entrar">Voltar à entrada</Link>
        </> : comSessao ? <>
          <p className="mensagem-info">
            Há uma sessão aberta neste aparelho. Saia dela antes de apagar os dados.
          </p>
          <Link className="botao botao--secundario" to="/">Voltar ao aplicativo</Link>
        </> : <>
          <Pendencias semResultado={semResultado} />
          {bloqueada && (
            <p className="mensagem-info" role="status">Feche as outras abas do aplicativo para terminar de apagar.</p>
          )}
          {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
          <div className="aparelho__acoes">
            <button type="button" className="botao botao--perigo"
              disabled={typeof semResultado !== 'number' || apagando} onClick={() => void apagar()}>
              {apagando ? 'Apagando...' : 'Apagar os dados'}
            </button>
            <Link className="botao botao--secundario" to="/entrar">Voltar à entrada</Link>
          </div>
        </>}
      </section>
    </main>
  )
}

/**
 * O que não teve resultado do servidor só existe neste aparelho e não volta depois de apagado: a
 * página diz quantos são e como enviá-los antes, contando os de todos os usuários do aparelho.
 */
function Pendencias({ semResultado }: { semResultado: number | 'falhou' | null }) {
  if (semResultado === null) return <p role="status">Conferindo o que ainda não foi enviado...</p>
  if (semResultado === 'falhou') {
    return <p className="mensagem-erro" role="alert">
      Não foi possível conferir o que ainda não foi enviado. Recarregue a página.
    </p>
  }
  if (semResultado === 0) {
    return <p className="mensagem-info" role="status">Nada do que foi registrado neste aparelho está esperando envio.</p>
  }
  const texto = semResultado === 1
    ? 'Um registro feito sem rede ainda não chegou ao servidor e será perdido se você apagar agora. Para '
      + 'enviá-lo, entre com rede usando o usuário que o fez e espere o envio.'
    : `${semResultado} registros feitos sem rede ainda não chegaram ao servidor e serão perdidos se você `
      + 'apagar agora. Para enviá-los, entre com rede usando cada usuário que os fez e espere o envio.'
  return <p className="mensagem-alerta" role="status">{texto}</p>
}
