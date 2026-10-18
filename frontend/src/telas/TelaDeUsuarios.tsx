import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { contas, type NovoUsuario, type UsuarioDaConta } from '../api/contas'
import { sessaoAtual } from '../sessao/armazenamento'
import { useSessao } from '../sessao/useSessao'
import { CampoDeSenha } from './CampoDeSenha'
import { erroDeCadastro } from './erroDeCadastro'
import { Mensagem } from './Mensagem'
import { problemaDoTamanhoDaSenha } from './regrasDaSenha'

const vazio: NovoUsuario = { nome: '', perfil: 'OPERADOR', email: '', senha: '' }

/** O que fica no lugar do nome removido a pedido; esse nome não se corrige nem se remove de novo. */
const NOME_REMOVIDO = 'Usuário removido'

/**
 * A resposta de uma ação aparece onde ela foi tocada: no alto, quando é da lista (onde é lista), no
 * formulário de usuário novo (novo) ou no item do usuário (o id dele), que numa lista longa fica
 * longe do alto da tela.
 */
type MensagemNaTela = { tipo: 'erro' | 'sucesso'; texto: string; onde: string }

export function TelaDeUsuarios() {
  const { identidade, atualizarIdentidade } = useSessao()
  const [usuarios, setUsuarios] = useState<UsuarioDaConta[]>([])
  const [dados, setDados] = useState<NovoUsuario>(vazio)
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  const [mensagem, setMensagem] = useState<MensagemNaTela | null>(null)
  const [redefinindo, setRedefinindo] = useState<string | null>(null)
  const [senhaProvisoria, setSenhaProvisoria] = useState('')
  const [corrigindo, setCorrigindo] = useState<string | null>(null)
  const [nomeCorrigido, setNomeCorrigido] = useState('')

  const carregar = useCallback(async () => {
    try {
      setUsuarios(await contas.usuarios())
      setMensagem((atual) => atual?.onde === 'lista' ? null : atual)
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), onde: 'lista' })
    } finally {
      setCarregando(false)
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  function mostrarEm(onde: string) {
    return mensagem?.onde === onde ? <Mensagem tipo={mensagem.tipo} texto={mensagem.texto} /> : null
  }

  async function criar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setMensagem(null)
    const problema = problemaDoTamanhoDaSenha(dados.senha)
    if (problema) {
      setMensagem({ tipo: 'erro', texto: problema, onde: 'novo' })
      return
    }
    setSalvando(true)
    try {
      await contas.criarUsuario(dados)
      setDados(vazio)
      await carregar()
      setMensagem({ tipo: 'sucesso', onde: 'novo',
        texto: 'Usuário criado. Ele entra com o e-mail e a senha cadastrados e escolhe a própria senha no primeiro acesso.' })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), onde: 'novo' })
    } finally {
      setSalvando(false)
    }
  }

  async function inativar(usuario: UsuarioDaConta) {
    if (!window.confirm(`Inativar ${usuario.nome}? O acesso será encerrado na próxima requisição.`)) return
    setMensagem(null)
    try {
      await contas.inativarUsuario(usuario.id)
      await carregar()
      // O aparelho guarda o que a pessoa viu e registrou, e o servidor não o alcança.
      setMensagem({ tipo: 'sucesso', onde: usuario.id,
        texto: `${usuario.nome} foi inativado. No aparelho que só essa pessoa usava, apague os dados `
          + 'pelo botão da tela de entrada.' })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), onde: usuario.id })
    }
  }

  /** A senha que o administrador escolhe é provisória: o operador a troca ao entrar. */
  async function redefinir(evento: FormEvent<HTMLFormElement>, usuario: UsuarioDaConta) {
    evento.preventDefault()
    setMensagem(null)
    const problema = problemaDoTamanhoDaSenha(senhaProvisoria)
    if (problema) {
      setMensagem({ tipo: 'erro', texto: problema, onde: usuario.id })
      return
    }
    setSalvando(true)
    try {
      await contas.redefinirSenha(usuario.id, senhaProvisoria)
      setRedefinindo(null)
      setSenhaProvisoria('')
      setMensagem({ tipo: 'sucesso', onde: usuario.id,
        texto: `Senha de ${usuario.nome} redefinida. Os aparelhos dele saíram, e ele escolhe a própria senha ao entrar.` })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), onde: usuario.id })
    } finally {
      setSalvando(false)
    }
  }

  /**
   * O nome corrigido aparece no histórico de vendas e de caixas do mesmo Usuário. O do próprio
   * administrador vai também para a identidade guardada, que o cabeçalho mostra.
   */
  async function corrigir(evento: FormEvent<HTMLFormElement>, usuario: UsuarioDaConta) {
    evento.preventDefault()
    setMensagem(null)
    const nome = nomeCorrigido.trim()
    if (!nome) {
      setMensagem({ tipo: 'erro', texto: 'Informe o nome.', onde: usuario.id })
      return
    }
    setSalvando(true)
    try {
      await contas.corrigirNomeDoUsuario(usuario.id, nome)
      setCorrigindo(null)
      setNomeCorrigido('')
      const sessao = sessaoAtual()
      if (identidade && sessao && usuario.id === identidade.usuarioId) {
        atualizarIdentidade({ ...identidade, nome }, sessao)
      }
      await carregar()
      setMensagem({ tipo: 'sucesso', texto: `Nome corrigido para ${nome}.`, onde: usuario.id })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), onde: usuario.id })
    } finally {
      setSalvando(false)
    }
  }

  async function anonimizar(usuario: UsuarioDaConta) {
    if (!window.confirm('Remover o nome deste Usuário? Esta ação não pode ser desfeita.')) return
    setMensagem(null)
    try {
      await contas.anonimizarUsuario(usuario.id)
      await carregar()
      setMensagem({ tipo: 'sucesso', texto: 'Nome do Usuário removido.', onde: usuario.id })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), onde: usuario.id })
    }
  }

  return <section className="cadastro">
    <h2 className="titulo">Usuários</h2>
    <p>Crie acessos para quem trabalha nesta Conta. O plano Completo permite mais de um usuário.</p>
    {mostrarEm('lista')}

    <form className="cadastro__formulario" onSubmit={(evento) => void criar(evento)}>
      <h3>Novo usuário</h3>
      <div className="cadastro__grade">
        <label className="campo"><span className="campo__rotulo">Nome</span>
          <input className="campo__entrada" value={dados.nome} required
            onChange={(evento) => setDados({ ...dados, nome: evento.target.value })} />
        </label>
        <label className="campo"><span className="campo__rotulo">Perfil</span>
          <select className="campo__entrada" value={dados.perfil}
            onChange={(evento) => setDados({ ...dados, perfil: evento.target.value as NovoUsuario['perfil'] })}>
            <option value="OPERADOR">Operador</option>
            <option value="ADMIN">Administrador</option>
          </select>
        </label>
        <label className="campo"><span className="campo__rotulo">E-mail de entrada</span>
          <input className="campo__entrada" type="email" value={dados.email} required
            onChange={(evento) => setDados({ ...dados, email: evento.target.value })} />
        </label>
        <CampoDeSenha rotulo="Senha inicial" autoComplete="new-password" minLength={15} valor={dados.senha}
          aoMudar={(senha) => setDados({ ...dados, senha })} />
      </div>
      <p className="cadastro__apoio">A senha precisa ter de 15 caracteres a 72 letras sem acento, em que letra com acento ou cedilha conta como duas, e passa pela verificação de vazamentos. O usuário escolhe a própria senha no primeiro acesso.</p>
      {mostrarEm('novo')}
      <button className="botao" disabled={salvando}>{salvando ? 'Criando...' : 'Criar usuário'}</button>
    </form>

    <h3>Usuários da Conta</h3>
    {carregando ? <p>Carregando...</p> : usuarios.length === 0 ? <p>Nenhum usuário encontrado.</p> :
      <ul className="cadastro__lista">{usuarios.map((usuario) => <li className="cadastro__item" key={usuario.id}>
        <div><strong>{usuario.nome}</strong><p>{usuario.perfil === 'ADMIN' ? 'Administrador' : 'Operador'} · {usuario.ativo ? 'Ativo' : 'Inativo'}</p></div>
        {usuario.nome !== NOME_REMOVIDO && corrigindo !== usuario.id &&
          <button className="botao botao--secundario" type="button"
            onClick={() => { setCorrigindo(usuario.id); setNomeCorrigido(usuario.nome); setRedefinindo(null) }}>Corrigir nome</button>}
        {corrigindo === usuario.id &&
          <form onSubmit={(evento) => void corrigir(evento, usuario)} noValidate>
            <label className="campo"><span className="campo__rotulo">Nome de {usuario.nome}</span>
              <input className="campo__entrada" value={nomeCorrigido} required maxLength={120}
                onChange={(evento) => setNomeCorrigido(evento.target.value)} />
            </label>
            <button className="botao" disabled={salvando}>{salvando ? 'Salvando...' : 'Salvar nome'}</button>
            <button className="botao botao--secundario" type="button"
              onClick={() => setCorrigindo(null)}>Cancelar</button>
          </form>}
        {usuario.ativo && usuario.perfil === 'OPERADOR' && redefinindo !== usuario.id &&
          <button className="botao botao--secundario" type="button"
            onClick={() => { setRedefinindo(usuario.id); setSenhaProvisoria(''); setCorrigindo(null) }}>Redefinir senha</button>}
        {redefinindo === usuario.id &&
          <form onSubmit={(evento) => void redefinir(evento, usuario)} noValidate>
            <CampoDeSenha rotulo={`Senha provisória de ${usuario.nome}`} autoComplete="new-password"
              valor={senhaProvisoria} aoMudar={setSenhaProvisoria} />
            <button className="botao" disabled={salvando}>{salvando ? 'Salvando...' : 'Salvar senha provisória'}</button>
            <button className="botao botao--secundario" type="button"
              onClick={() => setRedefinindo(null)}>Cancelar</button>
          </form>}
        {usuario.ativo && <button className="botao botao--perigo" type="button"
          onClick={() => void inativar(usuario)}>Inativar</button>}
        {!usuario.ativo && usuario.nome !== NOME_REMOVIDO &&
          <button className="botao botao--perigo" type="button"
            onClick={() => void anonimizar(usuario)}>Remover nome</button>}
        {mostrarEm(usuario.id)}
      </li>)}</ul>}
  </section>
}
