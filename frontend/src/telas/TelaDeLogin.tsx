import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { apagarDadosDoAparelho, contarGestosSemResultado, SessaoAbertaNoAparelho } from '../offline/fila'
import { sessaoFoiEncerrada } from '../sessao/armazenamento'
import { useSessao } from '../sessao/useSessao'

export function TelaDeLogin() {
  const { identidade, entrar } = useSessao()
  const navegar = useNavigate()
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [situacaoDoAparelho, setSituacaoDoAparelho] = useState<string | null>(null)
  const [erroDoAparelho, setErroDoAparelho] = useState<string | null>(null)
  const [apagando, setApagando] = useState(false)

  // Quem já está autenticado neste dispositivo não vê o login de novo.
  if (identidade) return <Navigate to="/" replace />

  async function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await entrar(email, senha)
      navegar('/', { replace: true })
    } catch (falha: unknown) {
      setErro(mensagemDe(falha))
      setEnviando(false)
    }
  }

  // Fica aqui, e não dentro do aplicativo, porque o aparelho de uma Conta encerrada ou de quem foi
  // inativado não entra mais, e porque aqui nenhuma sessão está usando a fila.
  async function apagarDados() {
    setSituacaoDoAparelho(null)
    setErroDoAparelho(null)
    setApagando(true)
    try {
      const semResultado = await contarGestosSemResultado()
      if (!window.confirm(perguntaAntesDeApagar(semResultado))) return
      await apagarDadosDoAparelho(() =>
        setSituacaoDoAparelho('Feche as outras abas do aplicativo para terminar de apagar.'))
      setSituacaoDoAparelho('Os dados deste aparelho foram apagados.')
    } catch (falha: unknown) {
      setSituacaoDoAparelho(null)
      setErroDoAparelho(falha instanceof SessaoAbertaNoAparelho
        ? falha.message
        : 'Não foi possível apagar os dados deste aparelho. Tente de novo.')
    } finally {
      setApagando(false)
    }
  }

  return (
    <main className="login">
      <form className="login__cartao" onSubmit={enviar} noValidate>
        <h1 className="login__titulo">Caixa Simples</h1>

        {sessaoFoiEncerrada() && (
          <p role="status">
            A sessão neste aparelho foi encerrada. Entre de novo; o que foi registrado sem rede continua
            guardado aqui e é enviado depois que você entrar.
          </p>
        )}

        <label className="campo">
          <span className="campo__rotulo">E-mail</span>
          <input
            className="campo__entrada"
            type="email"
            name="email"
            autoComplete="username"
            inputMode="email"
            value={email}
            onChange={(evento) => setEmail(evento.target.value)}
            required
          />
        </label>

        <label className="campo">
          <span className="campo__rotulo">Senha</span>
          <input
            className="campo__entrada"
            type="password"
            name="senha"
            autoComplete="current-password"
            value={senha}
            onChange={(evento) => setSenha(evento.target.value)}
            required
          />
        </label>

        {erro && (
          <p className="login__erro" role="alert">
            {erro}
          </p>
        )}

        <button type="submit" className="botao botao--largo" disabled={enviando}>
          {enviando ? 'Entrando...' : 'Entrar'}
        </button>
        <p><Link to="/privacidade">Aviso de privacidade</Link> · <Link to="/termos">Termos de uso</Link></p>

        <button type="button" className="botao botao--secundario" disabled={apagando}
          onClick={() => void apagarDados()}>
          Apagar os dados deste aparelho
        </button>
        {situacaoDoAparelho && <p role="status">{situacaoDoAparelho}</p>}
        {erroDoAparelho && <p className="login__erro" role="alert">{erroDoAparelho}</p>}
      </form>
    </main>
  )
}

/**
 * O que não teve resultado do servidor só existe neste aparelho e não volta depois de apagado: a
 * pergunta diz quantos são e como enviá-los antes.
 */
function perguntaAntesDeApagar(semResultado: number): string {
  if (semResultado === 0) {
    return 'Apagar o cadastro, as vendas e o que foi registrado neste aparelho? Para trabalhar sem rede '
      + 'de novo, será preciso entrar com rede.'
  }
  const quantos = semResultado === 1
    ? 'Um registro feito sem rede ainda não chegou ao servidor e será perdido. Para enviá-lo, entre com o '
      + 'usuário que o fez e espere o envio.'
    : `${semResultado} registros feitos sem rede ainda não chegaram ao servidor e serão perdidos. Para `
      + 'enviá-los, entre com cada usuário que os fez e espere o envio.'
  return `${quantos} Apagar os dados deste aparelho mesmo assim?`
}

function mensagemDe(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem conexão. Verifique a rede e tente de novo.'
  if (falha instanceof ErroDaApi && falha.status === 401) return 'E-mail ou senha incorretos.'
  // O servidor diz quanto falta para a janela acabar. Sem o detalhe, como num 429 de um proxy no
  // caminho, a tela ainda pede para esperar, em vez de sugerir tentar de novo na hora.
  if (falha instanceof ErroDaApi && falha.status === 429) {
    return falha.detalhe ?? 'Muitas tentativas de entrar. Aguarde alguns minutos e tente de novo.'
  }
  if (falha instanceof ErroDaApi && falha.detalhe) return falha.detalhe
  return 'Não foi possível entrar. Tente de novo.'
}
