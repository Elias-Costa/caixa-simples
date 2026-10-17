import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { contas, type NovoUsuario, type UsuarioDaConta } from '../api/contas'
import { erroDeCadastro } from './erroDeCadastro'
import { problemaDoTamanhoDaSenha } from './regrasDaSenha'

const vazio: NovoUsuario = { nome: '', perfil: 'OPERADOR', email: '', senha: '' }

export function TelaDeUsuarios() {
  const [usuarios, setUsuarios] = useState<UsuarioDaConta[]>([])
  const [dados, setDados] = useState<NovoUsuario>(vazio)
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)
  const [redefinindo, setRedefinindo] = useState<string | null>(null)
  const [senhaProvisoria, setSenhaProvisoria] = useState('')

  const carregar = useCallback(async () => {
    try {
      setUsuarios(await contas.usuarios())
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setCarregando(false)
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  async function criar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setErro(null)
    setAviso(null)
    const problema = problemaDoTamanhoDaSenha(dados.senha)
    if (problema) {
      setErro(problema)
      return
    }
    setSalvando(true)
    try {
      await contas.criarUsuario(dados)
      setDados(vazio)
      await carregar()
      setAviso('Usuário criado. Ele entra com o e-mail e a senha cadastrados e escolhe a própria senha no primeiro acesso.')
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(false)
    }
  }

  async function inativar(usuario: UsuarioDaConta) {
    if (!window.confirm(`Inativar ${usuario.nome}? O acesso será encerrado na próxima requisição.`)) return
    setErro(null)
    setAviso(null)
    try {
      await contas.inativarUsuario(usuario.id)
      await carregar()
      // O aparelho guarda o que a pessoa viu e registrou, e o servidor não o alcança.
      setAviso(`${usuario.nome} foi inativado. No aparelho que só essa pessoa usava, apague os dados `
        + 'pelo botão da tela de entrada.')
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  /** A senha que o administrador escolhe é provisória: o operador a troca ao entrar. */
  async function redefinir(evento: FormEvent<HTMLFormElement>, usuario: UsuarioDaConta) {
    evento.preventDefault()
    setErro(null)
    setAviso(null)
    const problema = problemaDoTamanhoDaSenha(senhaProvisoria)
    if (problema) {
      setErro(problema)
      return
    }
    setSalvando(true)
    try {
      await contas.redefinirSenha(usuario.id, senhaProvisoria)
      setRedefinindo(null)
      setSenhaProvisoria('')
      setAviso(`Senha de ${usuario.nome} redefinida. Os aparelhos dele saíram, e ele escolhe a própria senha ao entrar.`)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(false)
    }
  }

  async function anonimizar(usuario: UsuarioDaConta) {
    if (!window.confirm('Remover o nome deste Usuário? Esta ação não pode ser desfeita.')) return
    setErro(null)
    setAviso(null)
    try {
      await contas.anonimizarUsuario(usuario.id)
      await carregar()
      setAviso('Nome do Usuário removido.')
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  return <section className="cadastro">
    <h2 className="titulo">Usuários</h2>
    <p>Crie acessos para quem trabalha nesta Conta. O plano Completo permite mais de um usuário.</p>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {aviso && <p role="status">{aviso}</p>}

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
        <label className="campo"><span className="campo__rotulo">Senha inicial</span>
          <input className="campo__entrada" type="password" value={dados.senha} required minLength={15}
            onChange={(evento) => setDados({ ...dados, senha: evento.target.value })} />
        </label>
      </div>
      <p className="cadastro__apoio">A senha precisa ter de 15 caracteres a 72 letras sem acento, em que letra com acento ou cedilha conta como duas, e passa pela verificação de vazamentos. O usuário escolhe a própria senha no primeiro acesso.</p>
      <button className="botao" disabled={salvando}>{salvando ? 'Criando...' : 'Criar usuário'}</button>
    </form>

    <h3>Usuários da Conta</h3>
    {carregando ? <p>Carregando...</p> : usuarios.length === 0 ? <p>Nenhum usuário encontrado.</p> :
      <ul className="cadastro__lista">{usuarios.map((usuario) => <li className="cadastro__item" key={usuario.id}>
        <div><strong>{usuario.nome}</strong><p>{usuario.perfil === 'ADMIN' ? 'Administrador' : 'Operador'} · {usuario.ativo ? 'Ativo' : 'Inativo'}</p></div>
        {usuario.ativo && usuario.perfil === 'OPERADOR' && redefinindo !== usuario.id &&
          <button className="botao botao--secundario" type="button"
            onClick={() => { setRedefinindo(usuario.id); setSenhaProvisoria('') }}>Redefinir senha</button>}
        {redefinindo === usuario.id &&
          <form onSubmit={(evento) => void redefinir(evento, usuario)} noValidate>
            <label className="campo"><span className="campo__rotulo">Senha provisória de {usuario.nome}</span>
              <input className="campo__entrada" type="password" autoComplete="new-password"
                value={senhaProvisoria} required onChange={(evento) => setSenhaProvisoria(evento.target.value)} />
            </label>
            <button className="botao" disabled={salvando}>{salvando ? 'Salvando...' : 'Salvar senha provisória'}</button>
            <button className="botao botao--secundario" type="button"
              onClick={() => setRedefinindo(null)}>Cancelar</button>
          </form>}
        {usuario.ativo && <button className="botao botao--secundario" type="button"
          onClick={() => void inativar(usuario)}>Inativar</button>}
        {!usuario.ativo && usuario.nome !== 'Usuário removido' &&
          <button className="botao botao--secundario" type="button"
            onClick={() => void anonimizar(usuario)}>Remover nome</button>}
      </li>)}</ul>}
  </section>
}
