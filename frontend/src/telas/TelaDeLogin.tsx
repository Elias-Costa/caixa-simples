import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { estadoDaConexao } from '../api/conexao'
import { aoMudarMarcasDaEntrada, sessaoFoiEncerrada, sessaoVenceu, voltaPorOutraAba } from '../sessao/armazenamento'
import { RelogioAdiantado } from '../sessao/token'
import { useSessao } from '../sessao/useSessao'
import { CampoDeSenha } from './CampoDeSenha'

export function TelaDeLogin() {
  const { identidade, entrar } = useSessao()
  const navegar = useNavigate()
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const botaoDeEntrar = useRef<HTMLButtonElement>(null)
  const focarDepoisDoErro = useRef(false)
  // A marca pode mudar com a entrada à vista, quando alguém entra ou sai em outra aba.
  const porQueVoltou = useSyncExternalStore(aoMudarMarcasDaEntrada, avisoDaEntrada)

  // O botão desligado durante a tentativa perde o foco, que cai no corpo da página, e quem usa teclado
  // recomeçaria do alto. A espera é pelo botão religado, porque botão desligado não recebe foco.
  useEffect(() => {
    if (!focarDepoisDoErro.current || enviando) return
    focarDepoisDoErro.current = false
    botaoDeEntrar.current?.focus()
  })

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
      setErro(mensagemDe(falha, porQueVoltou !== null))
      focarDepoisDoErro.current = true
      setEnviando(false)
    }
  }

  return (
    <main className="login">
      <form className="login__cartao" onSubmit={enviar} noValidate>
        <div className="login__marca">
          <img className="login__icone" src="/icone.svg" alt="" width="48" height="48" />
          {/* O nome sozinho não diz para que o aplicativo serve; a categoria vem logo abaixo dele. */}
          <div>
            <h1 className="login__titulo">ECRX Caixa</h1>
            <p className="login__descritor">Sistema de caixa e vendas</p>
          </div>
        </div>

        {porQueVoltou && <p className="mensagem-info" role="status">{porQueVoltou}</p>}

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

        <CampoDeSenha rotulo="Senha" name="senha" autoComplete="current-password" valor={senha}
          aoMudar={setSenha} />

        {erro && (
          <p className="login__erro" role="alert">
            {erro}
          </p>
        )}

        <button ref={botaoDeEntrar} type="submit" className="botao botao--largo" disabled={enviando}>
          {enviando ? 'Entrando...' : 'Entrar'}
        </button>
        {/* Não há recuperação por e-mail: o administrador redefine a senha do operador, e o próprio
            administrador é socorrido pelo atendimento. Fica à vista antes do primeiro erro. */}
        <p className="login__ajuda">
          Esqueceu a senha? Peça ao administrador da Conta uma senha provisória. Se você é o
          administrador, fale com o atendimento do ECRX Caixa.
        </p>
        <p className="links-de-apoio"><Link to="/privacidade">Aviso de privacidade</Link> · <Link to="/termos">Termos de uso</Link></p>
        {/* Fica aqui, e não dentro do aplicativo, porque o aparelho de uma Conta encerrada ou de quem
            foi inativado não entra mais. A página explica e confirma antes de apagar. */}
        <p className="links-de-apoio links-de-apoio--discreto">
          <Link to="/aparelho">Apagar os dados deste aparelho</Link>
        </p>
      </form>
    </main>
  )
}

/**
 * Por que a pessoa voltou à entrada, quando não foi ela quem saiu. O servidor recusou o token, por um ato de
 * alguém; o token venceu sozinho, e aí entrar pede rede; ou outra aba do aplicativo saiu ou entrou, e o
 * navegador fica com uma sessão por vez. A entrada não sabe quem vai entrar, e a fila de cada usuário só sai
 * quando ele entra: a frase não promete o envio a quem está lendo. Quem entrou em outra aba vem primeiro,
 * porque é o fato mais novo e porque entrar aqui tira a outra aba; quem saiu vem por último, porque o 401 e o
 * vencimento também fazem a outra aba sair e dizem mais.
 */
function avisoDaEntrada(): string | null {
  if (voltaPorOutraAba() === 'entrou') {
    return 'Alguém entrou em outra aba do aplicativo, e só uma sessão fica aberta por vez. Se entrar aqui, a '
      + 'outra aba volta à entrada; o que foi registrado continua guardado aqui.'
  }
  if (sessaoFoiEncerrada()) {
    return 'A sessão neste aparelho foi encerrada. Entre de novo; o que foi registrado continua guardado aqui '
      + 'e é enviado depois que quem o registrou entrar.'
  }
  if (sessaoVenceu()) {
    return 'A sessão neste aparelho venceu. Entre de novo, com rede; o que foi registrado continua '
      + 'guardado aqui e é enviado depois que quem o registrou entrar.'
  }
  if (voltaPorOutraAba() === 'saiu') {
    return 'Alguém saiu em outra aba do aplicativo, e a sessão terminou aqui também. Entre de novo; o que foi '
      + 'registrado continua guardado aqui.'
  }
  return null
}

/**
 * Entrar sem resposta. O servidor fora com o Wi-Fi ligado tem frase própria, para a pessoa não ir
 * reiniciar o roteador à toa. A parte do que fica guardado sai quando a frase de cima já diz isso.
 */
function semResposta(guardadoJaDito: boolean): string {
  if (estadoDaConexao() === 'sem-rede') {
    return guardadoJaDito
      ? 'Sem conexão. Entrar precisa de rede.'
      : 'Sem conexão. Entrar precisa de rede; o que foi registrado continua guardado neste aparelho.'
  }
  const frase = 'Sem resposta do servidor. A rede deste aparelho pode estar funcionando; tente de novo em alguns minutos.'
  return guardadoJaDito ? frase : `${frase} O que foi registrado continua guardado neste aparelho.`
}

function mensagemDe(falha: unknown, guardadoJaDito: boolean): string {
  if (falha instanceof RelogioAdiantado) {
    return 'O relógio deste aparelho está adiantado. Acerte a data e a hora e entre de novo; o que foi '
      + 'registrado continua guardado aqui.'
  }
  if (falha instanceof SemConexao) return semResposta(guardadoJaDito)
  if (falha instanceof ErroDaApi && falha.status === 401) return 'E-mail ou senha incorretos.'
  // O servidor diz quanto falta para a janela acabar. Sem o detalhe, como num 429 de um proxy no
  // caminho, a tela ainda pede para esperar, em vez de sugerir tentar de novo na hora.
  if (falha instanceof ErroDaApi && falha.status === 429) {
    return falha.detalhe ?? 'Muitas tentativas de entrar. Aguarde alguns minutos e tente de novo.'
  }
  if (falha instanceof ErroDaApi && falha.detalhe) return falha.detalhe
  return 'Não foi possível entrar. Tente de novo.'
}
