import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { cadastro, type Cliente, type DadosDoCliente } from '../api/cadastro'
import { erroDeCadastro } from './erroDeCadastro'

export function TelaDeClientes() {
  const [ativos, setAtivos] = useState<Cliente[]>([])
  const [inativos, setInativos] = useState<Cliente[]>([])
  const [editando, setEditando] = useState<string | null>(null)
  const [formulario, setFormulario] = useState<DadosDoCliente>({ nome: '', contato: null })
  const [aberto, setAberto] = useState(false)
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const formularioRef = useRef<HTMLFormElement>(null)

  const carregar = useCallback(async () => {
    try {
      const [listaAtiva, listaInativa] = await Promise.all([
        cadastro.clientes(), cadastro.clientesInativos(),
      ])
      setAtivos(listaAtiva)
      setInativos(listaInativa)
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setCarregando(false)
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])
  useEffect(() => {
    if (!aberto) return
    formularioRef.current?.scrollIntoView?.({ block: 'start' })
    formularioRef.current?.querySelector('input')?.focus()
  }, [aberto])

  function novo() {
    setEditando(null)
    setFormulario({ nome: '', contato: null })
    setAberto(true)
    setErro(null)
  }

  function editar(cliente: Cliente) {
    setEditando(cliente.id)
    setFormulario({ nome: cliente.nome, contato: cliente.contato })
    setAberto(true)
    setErro(null)
  }

  async function salvar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setSalvando(true)
    setErro(null)
    try {
      if (editando) await cadastro.editarCliente(editando, formulario)
      else await cadastro.criarCliente(formulario)
      setAberto(false)
      await carregar()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(false)
    }
  }

  async function inativar(cliente: Cliente) {
    if (!window.confirm(`Inativar ${cliente.nome}?`)) return
    try {
      await cadastro.inativarCliente(cliente.id)
      await carregar()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  async function reativar(cliente: Cliente) {
    try {
      await cadastro.reativarCliente(cliente.id)
      await carregar()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  return <section className="cadastro">
    <div className="cadastro__topo">
      <div><h2 className="titulo">Clientes</h2><p>Cadastre no balcão com nome e contato opcional.</p></div>
      <button className="botao" type="button" onClick={novo}>Novo cliente</button>
    </div>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {carregando ? <p>Carregando...</p> : <>
      <h3>Ativos</h3>
      {ativos.length === 0 ? <p>Nenhum cliente ativo.</p> : <ul className="cadastro__lista">
        {ativos.map((cliente) => <li className="cadastro__item" key={cliente.id}>
          <div><strong>{cliente.nome}</strong><p>{cliente.contato ?? 'Sem contato'}</p></div>
          <div className="cadastro__acoes">
            <button className="botao botao--secundario" type="button" onClick={() => editar(cliente)}>Editar</button>
            <button className="botao botao--secundario" type="button" onClick={() => void inativar(cliente)}>Inativar</button>
          </div>
        </li>)}
      </ul>}
      {inativos.length > 0 && <><h3>Inativos</h3><ul className="cadastro__lista">
        {inativos.map((cliente) => <li className="cadastro__item" key={cliente.id}>
          <div><strong>{cliente.nome}</strong><p>{cliente.contato ?? 'Sem contato'}</p></div>
          <button className="botao botao--secundario" type="button" onClick={() => void reativar(cliente)}>Reativar</button>
        </li>)}
      </ul></>}
    </>}

    {aberto && <form ref={formularioRef} className="cadastro__formulario" onSubmit={(evento) => void salvar(evento)}>
      <h3>{editando ? 'Editar cliente' : 'Novo cliente'}</h3>
      <label className="campo"><span className="campo__rotulo">Nome</span>
        <input className="campo__entrada" value={formulario.nome} onChange={(evento) => setFormulario((atual) => ({ ...atual, nome: evento.target.value }))} maxLength={120} required />
      </label>
      <label className="campo"><span className="campo__rotulo">Contato (opcional)</span>
        <input className="campo__entrada" value={formulario.contato ?? ''} onChange={(evento) => setFormulario((atual) => ({ ...atual, contato: evento.target.value || null }))} maxLength={180} />
      </label>
      <div className="cadastro__acoes">
        <button className="botao" type="submit" disabled={salvando}>{salvando ? 'Salvando...' : 'Salvar'}</button>
        <button className="botao botao--secundario" type="button" onClick={() => setAberto(false)}>Cancelar</button>
      </div>
    </form>}
  </section>
}
