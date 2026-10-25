import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { contas, type MeusDados } from '../api/contas'
import { marcarSessaoEncerrada, renovarTokenDaSessao, sessaoAtual } from '../sessao/armazenamento'
import { useSessao } from '../sessao/useSessao'
import { CampoDeSenha } from './CampoDeSenha'
import { problemaDoTamanhoDaSenha } from './regrasDaSenha'
import { useConfirmacao } from './useConfirmacao'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'
import { prepararCadastroLocal } from '../sessao/prepararCadastroLocal'

type Props = {
  /** A senha é a que outra pessoa escolheu, e esta é a única tela até a troca. */
  obrigatoria?: boolean
}

/**
 * Os próprios dados, a própria senha e os aparelhos em que se entrou (RNF06).
 *
 * Trocar a senha derruba todos os outros aparelhos na requisição seguinte; este continua, com o
 * token novo que a resposta traz no corpo. Sair de todos os aparelhos derruba também este, para que
 * quem está com um aparelho furtado não possa expulsar o dono e continuar dentro. As duas coisas
 * precisam de rede: é o servidor quem recusa os tokens antigos.
 *
 * Obrigatória, a tela aparece logo depois do login, sem o menu, enquanto a senha for a que o
 * administrador ou o mantenedor escolheu, porque quem a escolheu a conhece.
 */
export function TelaDaSenha({ obrigatoria = false }: Props) {
  const { identidade, sair, atualizarIdentidade } = useSessao()
  const [atual, setAtual] = useState('')
  const [nova, setNova] = useState('')
  const [repetida, setRepetida] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [confirmar, dialogoDeConfirmacao] = useConfirmacao()

  async function trocar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setErro(null)
    setAviso(null)
    const problema = problemaDoTamanhoDaSenha(nova)
      ?? (nova !== repetida ? 'A senha nova e a repetição não são iguais.' : null)
    if (problema) {
      setErro(problema)
      return
    }
    const sessao = sessaoAtual()
    if (!sessao || !identidade) return
    setEnviando(true)
    try {
      const { token } = await contas.trocarSenha(atual, nova)
      renovarTokenDaSessao(sessao, token)
      setAtual('')
      setNova('')
      setRepetida('')
      setAviso('Senha trocada. Os outros aparelhos saíram e entram de novo com a senha nova.')
      // A resposta já confirma a troca; a marca sai sem esperar outra pergunta ao servidor.
      const escolhida = { ...identidade, senhaProvisoria: false }
      atualizarIdentidade(escolhida, sessao)
      // Com a senha provisória o servidor não entregava o cadastro; agora o aparelho o guarda para
      // abrir sem rede. A falha não desfaz a troca, que o servidor já aceitou.
      if (identidade.senhaProvisoria) void prepararCadastroLocal(escolhida).catch(() => undefined)
    } catch (falha) {
      setErro(mensagemDe(falha))
    } finally {
      setEnviando(false)
    }
  }

  async function sairDeTodos() {
    const sim = await confirmar({
      titulo: 'Sair de todos os aparelhos?',
      texto: 'Todos saem, inclusive este, e será preciso entrar de novo em cada um.',
      acao: 'Sair de todos',
    })
    if (!sim) return
    setErro(null)
    setAviso(null)
    try {
      await contas.sairDeTodosOsAparelhos()
      marcarSessaoEncerrada()
      sair()
    } catch (falha) {
      setErro(mensagemDe(falha))
    }
  }

  const formulario = <form className={obrigatoria ? undefined : 'cadastro__formulario'}
    onSubmit={(evento) => void trocar(evento)} noValidate>
    {!obrigatoria && <h3>Trocar a senha</h3>}
    <CampoDeSenha rotulo="Senha atual" autoComplete="current-password" valor={atual} aoMudar={setAtual} />
    <CampoDeSenha rotulo="Senha nova" autoComplete="new-password" valor={nova} aoMudar={setNova} />
    <CampoDeSenha rotulo="Repita a senha nova" autoComplete="new-password" valor={repetida}
      aoMudar={setRepetida} />
    <p className="cadastro__apoio">De 15 caracteres a 72 letras sem acento; letra com acento ou cedilha conta como duas. Sem regra de número ou símbolo: uma frase longa funciona bem.</p>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {aviso && !obrigatoria && <p className="mensagem-sucesso" role="status">{aviso}</p>}
    <button className={obrigatoria ? 'botao botao--largo' : 'botao'} disabled={enviando}>
      {enviando ? 'Trocando...' : 'Trocar a senha'}
    </button>
  </form>

  if (obrigatoria) {
    return <main className="login">
      <section className="login__cartao">
        <h1 className="login__titulo">Escolha a sua senha</h1>
        <p>A senha com que você entrou foi escolhida por outra pessoa. Para continuar, troque-a por uma que só você conheça.</p>
        {formulario}
        <p><button className="botao botao--secundario" type="button" onClick={sair}>Sair</button></p>
      </section>
    </main>
  }

  return <section className="cadastro">
    <TituloComAjuda titulo="Minha conta" ajuda={AJUDA.minhaConta} />
    <SeusDados />
    {formulario}
    <div className="cadastro__formulario">
      <h3>Aparelhos</h3>
      <p>Se um aparelho em que você entrou sumiu, saia de todos. Este também sai, e você entra de novo com a mesma senha. Se a senha pode ter vazado, troque a senha acima: os outros aparelhos também saem.</p>
      <p className="cadastro__apoio">Um aparelho sem rede só sai quando voltar a falar com o servidor. O que ele registrou sem rede fica guardado nele.</p>
      <button className="botao botao--secundario" type="button" onClick={() => void sairDeTodos()}>
        Sair de todos os aparelhos
      </button>
    </div>
    {dialogoDeConfirmacao}
  </section>
}

const ROTULO_DO_PERFIL: Record<MeusDados['perfil'], string> = {
  ADMIN: 'Administrador',
  OPERADOR: 'Operador',
}

/**
 * O que o Fechadia guarda do cadastro de quem entrou, e a troca do e-mail de entrada (LGPD, art.
 * 18, I a III). Os dados são lidos do servidor ao abrir a tela e não ficam no aparelho: a identidade
 * guardada na sessão não leva o e-mail.
 *
 * O e-mail novo é pedido duas vezes porque um erro de digitação só apareceria no login seguinte, com
 * a pessoa já trancada fora. Os aparelhos seguem dentro: o token não leva o e-mail.
 */
function SeusDados() {
  const [dados, setDados] = useState<MeusDados | null>(null)
  const [semDados, setSemDados] = useState<string | null>(null)
  const [emailNovo, setEmailNovo] = useState('')
  const [repetido, setRepetido] = useState('')
  const [senhaAtual, setSenhaAtual] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  const carregar = useCallback(async () => {
    try {
      setDados(await contas.meusDados())
      setSemDados(null)
    } catch (falha) {
      setSemDados(mensagemDe(falha, 'Sem conexão. Ver os seus dados precisa de rede.'))
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  async function trocarEmail(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setErro(null)
    setAviso(null)
    const novo = emailNovo.trim()
    const problema = !novo ? 'Informe o e-mail novo.'
      : novo.toLowerCase() !== repetido.trim().toLowerCase() ? 'O e-mail novo e a repetição não são iguais.'
        : !senhaAtual ? 'Informe a senha atual para confirmar.' : null
    if (problema) {
      setErro(problema)
      return
    }
    setEnviando(true)
    try {
      await contas.trocarEmail(senhaAtual, novo)
      setDados((antes) => antes && { ...antes, email: novo.toLowerCase() })
      setEmailNovo('')
      setRepetido('')
      setSenhaAtual('')
      setAviso('E-mail trocado. Da próxima vez, entre com o e-mail novo.')
    } catch (falha) {
      setErro(mensagemDe(falha, 'Sem conexão. Trocar o e-mail precisa de rede.'))
    } finally {
      setEnviando(false)
    }
  }

  return <div className="cadastro__formulario">
    <h3>Seus dados</h3>
    {dados ? <>
      <p>Nome: <strong>{dados.nome}</strong></p>
      <p>E-mail de entrada: <strong>{dados.email}</strong></p>
      <p>Perfil: <strong>{ROTULO_DO_PERFIL[dados.perfil]}</strong></p>
    </> : <p>{semDados ?? 'Carregando os seus dados...'}</p>}
    <p className="cadastro__apoio">O nome é corrigido pelo administrador da Conta, na tela Usuários. O e-mail de entrada só você troca, aqui.</p>
    <form onSubmit={(evento) => void trocarEmail(evento)} noValidate>
      <label className="campo"><span className="campo__rotulo">E-mail novo</span>
        <input className="campo__entrada" type="email" autoComplete="email" maxLength={180}
          value={emailNovo} required onChange={(evento) => setEmailNovo(evento.target.value)} />
      </label>
      <label className="campo"><span className="campo__rotulo">Repita o e-mail novo</span>
        <input className="campo__entrada" type="email" autoComplete="off" maxLength={180}
          value={repetido} required onChange={(evento) => setRepetido(evento.target.value)} />
      </label>
      <CampoDeSenha rotulo="Senha atual, para confirmar" autoComplete="current-password"
        valor={senhaAtual} aoMudar={setSenhaAtual} />
      {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
      {aviso && <p className="mensagem-sucesso" role="status">{aviso}</p>}
      <button className="botao" disabled={enviando}>{enviando ? 'Trocando...' : 'Trocar o e-mail'}</button>
    </form>
  </div>
}

function mensagemDe(falha: unknown,
  semRede = 'Sem conexão. Trocar a senha e sair dos aparelhos precisam de rede.'): string {
  if (falha instanceof SemConexao) return semRede
  if (falha instanceof ErroDaApi) {
    if (falha.status === 401) return 'A sessão foi encerrada. Entre de novo.'
    return falha.detalhe ?? falha.titulo
  }
  return 'Não foi possível concluir. Tente de novo.'
}
