import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { contas, type NovoUsuario, type UsuarioDaConta } from '../api/contas'
import { erroDeCadastro } from './erroDeCadastro'

const vazio: NovoUsuario = { nome: '', perfil: 'OPERADOR', email: '', senha: '' }

export function TelaDeUsuarios() {
  const [usuarios, setUsuarios] = useState<UsuarioDaConta[]>([])
  const [dados, setDados] = useState<NovoUsuario>(vazio)
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)

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
    setSalvando(true)
    setErro(null)
    setAviso(null)
    try {
      await contas.criarUsuario(dados)
      setDados(vazio)
      await carregar()
      setAviso('Usuário criado. Ele já pode entrar com o e-mail e a senha cadastrados.')
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
      setAviso(`${usuario.nome} foi inativado.`)
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
      <p className="cadastro__apoio">A senha precisa ter pelo menos 15 caracteres e passa pela verificação de vazamentos.</p>
      <button className="botao" disabled={salvando}>{salvando ? 'Criando...' : 'Criar usuário'}</button>
    </form>

    <h3>Usuários da Conta</h3>
    {carregando ? <p>Carregando...</p> : usuarios.length === 0 ? <p>Nenhum usuário encontrado.</p> :
      <ul className="cadastro__lista">{usuarios.map((usuario) => <li className="cadastro__item" key={usuario.id}>
        <div><strong>{usuario.nome}</strong><p>{usuario.perfil === 'ADMIN' ? 'Administrador' : 'Operador'} · {usuario.ativo ? 'Ativo' : 'Inativo'}</p></div>
        {usuario.ativo && <button className="botao botao--secundario" type="button"
          onClick={() => void inativar(usuario)}>Inativar</button>}
      </li>)}</ul>}
  </section>
}
