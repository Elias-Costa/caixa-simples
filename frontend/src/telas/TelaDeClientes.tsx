import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { cadastro, type Cliente, type DadosDoCliente } from '../api/cadastro'
import { listarGestos } from '../offline/fila'
import { lerIdentidade } from '../sessao/armazenamento'
import { useOnline } from '../shell/useOnline'
import { erroDeCadastro, errosDosCampos } from './erroDeCadastro'
import { filtrarPorNome } from './filtroPorNome'
import { ErroDoCampo, Mensagem } from './Mensagem'
import { useConfirmacao } from './useConfirmacao'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'

/**
 * As listas em ordem alfabética, com e sem rede: o servidor e o aparelho devolvem na ordem em que
 * guardam, e o cliente reativado ia para o fim, escondido de quem o procurava.
 */
function emOrdem(clientes: Cliente[]): Cliente[] {
  return [...clientes].sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'))
}

export function TelaDeClientes() {
  const podeRemover = lerIdentidade()?.perfil === 'ADMIN'
  const online = useOnline()
  const [ativos, setAtivos] = useState<Cliente[]>([])
  const [inativos, setInativos] = useState<Cliente[]>([])
  const [busca, setBusca] = useState('')
  const [editando, setEditando] = useState<string | null>(null)
  const [formulario, setFormulario] = useState<DadosDoCliente>({ nome: '', contato: null })
  const [aberto, setAberto] = useState(false)
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  // O erro da lista fica no alto; o do formulário, dentro dele, perto do botão que o causou.
  const [erro, setErro] = useState<string | null>(null)
  const [erroDoFormulario, setErroDoFormulario] = useState<string | null>(null)
  const [camposComErro, setCamposComErro] = useState<Record<string, string>>({})
  const [aviso, setAviso] = useState<string | null>(null)
  const [pendentes, setPendentes] = useState(0)
  const formularioRef = useRef<HTMLFormElement>(null)
  const [confirmar, dialogoDeConfirmacao] = useConfirmacao()

  const carregar = useCallback(async () => {
    try {
      const [listaAtiva, listaInativa] = await Promise.all([
        cadastro.clientes(), cadastro.clientesInativos(),
      ])
      setAtivos(emOrdem(listaAtiva))
      setInativos(emOrdem(listaInativa))
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      if ('indexedDB' in globalThis) {
        void listarGestos().then((gestos) => setPendentes(gestos.filter((gesto) =>
          gesto.tipo.startsWith('cliente.') && gesto.estado !== 'sent').length)).catch(() => undefined)
      }
      setCarregando(false)
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])
  useEffect(() => {
    if (!aberto) return
    formularioRef.current?.scrollIntoView?.({ block: 'start' })
    formularioRef.current?.querySelector('input')?.focus()
  }, [aberto])
  // A recusa do servidor marca os campos; o foco vai ao primeiro, para corrigir sem procurar.
  useEffect(() => {
    if (Object.keys(camposComErro).length === 0) return
    formularioRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [camposComErro])

  function limparMensagens() {
    setErro(null)
    setErroDoFormulario(null)
    setCamposComErro({})
    setAviso(null)
  }

  /** O que o campo precisa para ser anunciado com o erro que o servidor apontou nele. */
  function marcaDoCampo(campo: string) {
    return camposComErro[campo]
      ? { 'aria-invalid': true, 'aria-describedby': `cliente-erro-${campo}` } : {}
  }

  /** Sem rede, quem avisa é a contagem do que ficou guardado no aparelho, logo abaixo. */
  function avisar(texto: string) {
    if (online) setAviso(texto)
  }

  function novo() {
    setEditando(null)
    setFormulario({ nome: '', contato: null })
    setAberto(true)
    limparMensagens()
  }

  function editar(cliente: Cliente) {
    setEditando(cliente.id)
    setFormulario({ nome: cliente.nome, contato: cliente.contato })
    setAberto(true)
    limparMensagens()
  }

  async function salvar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setSalvando(true)
    limparMensagens()
    try {
      if (editando) await cadastro.editarCliente(editando, formulario)
      else await cadastro.criarCliente(formulario)
      setAberto(false)
      await carregar()
      avisar(`${formulario.nome.trim()} salvo.`)
    } catch (falha) {
      setErroDoFormulario(erroDeCadastro(falha))
      setCamposComErro(errosDosCampos(falha))
    } finally {
      setSalvando(false)
    }
  }

  async function inativar(cliente: Cliente) {
    const sim = await confirmar({
      titulo: `Inativar ${cliente.nome}?`,
      texto: 'O cliente sai da lista de ativos e pode ser reativado depois.',
      acao: 'Inativar',
    })
    if (!sim) return
    limparMensagens()
    try {
      await cadastro.inativarCliente(cliente.id)
      await carregar()
      avisar(`${cliente.nome} inativado.`)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  async function reativar(cliente: Cliente) {
    limparMensagens()
    try {
      await cadastro.reativarCliente(cliente.id)
      await carregar()
      avisar(`${cliente.nome} reativado.`)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  async function remover(cliente: Cliente) {
    const sim = await confirmar({
      titulo: `Remover os dados de ${cliente.nome}?`,
      texto: 'O nome e o contato saem do cadastro, e esta ação não pode ser desfeita. As vendas '
        + 'continuam no histórico, sem eles.',
      acao: 'Remover os dados',
    })
    if (!sim) return
    limparMensagens()
    try {
      await cadastro.removerCliente(cliente.id)
      await carregar()
      avisar('Dados do cliente removidos.')
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  // A busca vale para as duas listas: quem procura um nome não sabe se o cliente foi inativado.
  const ativosEncontrados = filtrarPorNome(ativos, busca, (cliente) => cliente.nome)
  const inativosEncontrados = filtrarPorNome(inativos, busca, (cliente) => cliente.nome)
  const termo = busca.trim()

  return <section className="cadastro">
    <div className="cadastro__topo">
      <div><TituloComAjuda titulo="Clientes" ajuda={AJUDA.clientes} /><p>Cadastre no balcão com nome e contato opcional.</p></div>
      <button className="botao" type="button" onClick={novo}>Novo cliente</button>
    </div>
    <Mensagem tipo="erro" texto={erro} />
    <Mensagem tipo="sucesso" texto={aviso} />
    {pendentes > 0 && <p className="mensagem-info" role="status">{pendentes === 1 ? 'Uma alteração de cliente guardada' : `${pendentes} alterações de cliente guardadas`} neste dispositivo, aguardando sincronização.</p>}
    {carregando ? <p>Carregando...</p> : <>
      {(ativos.length > 0 || inativos.length > 0) && <label className="cadastro__busca">Buscar por nome
        <input type="search" value={busca} onChange={(evento) => setBusca(evento.target.value)} />
      </label>}
      <h3>Ativos</h3>
      {ativos.length === 0 ? <p>Nenhum cliente ativo.</p>
        : ativosEncontrados.length === 0 ? <p role="status">Nenhum cliente ativo encontrado para {termo}.</p>
        : <ul className="cadastro__lista">
        {ativosEncontrados.map((cliente) => <li className="cadastro__item" key={cliente.id}>
          <div><strong>{cliente.nome}</strong><p>{cliente.contato ?? 'Sem contato'}</p></div>
          <div className="cadastro__acoes">
            <button className="botao botao--secundario" type="button" onClick={() => editar(cliente)}>Editar</button>
            <button className="botao botao--perigo" type="button" onClick={() => void inativar(cliente)}>Inativar</button>
            {podeRemover && <button className="botao botao--perigo" type="button"
              onClick={() => void remover(cliente)}>Remover dados</button>}
          </div>
        </li>)}
      </ul>}
      {inativosEncontrados.length > 0 && <><h3>Inativos</h3><ul className="cadastro__lista">
        {inativosEncontrados.map((cliente) => <li className="cadastro__item" key={cliente.id}>
          <div><strong>{cliente.nome}</strong><p>{cliente.contato ?? 'Sem contato'}</p></div>
          <button className="botao botao--secundario" type="button" onClick={() => void reativar(cliente)}>Reativar</button>
          {podeRemover && <button className="botao botao--perigo" type="button"
            onClick={() => void remover(cliente)}>Remover dados</button>}
        </li>)}
      </ul></>}
    </>}

    {aberto && <form ref={formularioRef} className="cadastro__formulario" onSubmit={(evento) => void salvar(evento)}>
      <h3>{editando ? 'Editar cliente' : 'Novo cliente'}</h3>
      {/* A mensagem do campo fica fora do rótulo, que continua sendo só o nome do campo. */}
      <div className="campo"><label className="campo__rotulo" htmlFor="cliente-nome">Nome</label>
        <input id="cliente-nome" className="campo__entrada" value={formulario.nome} onChange={(evento) => setFormulario((atual) => ({ ...atual, nome: evento.target.value }))} maxLength={120} required {...marcaDoCampo('nome')} />
        <ErroDoCampo id="cliente-erro-nome" texto={camposComErro.nome} />
      </div>
      <div className="campo"><label className="campo__rotulo" htmlFor="cliente-contato">Contato (opcional)</label>
        <input id="cliente-contato" className="campo__entrada" value={formulario.contato ?? ''} onChange={(evento) => setFormulario((atual) => ({ ...atual, contato: evento.target.value || null }))} maxLength={180} {...marcaDoCampo('contato')} />
        <ErroDoCampo id="cliente-erro-contato" texto={camposComErro.contato} />
      </div>
      <Mensagem tipo="erro" texto={erroDoFormulario} />
      <div className="cadastro__acoes">
        <button className="botao" type="submit" disabled={salvando}>{salvando ? 'Salvando...' : 'Salvar'}</button>
        <button className="botao botao--secundario" type="button" onClick={() => setAberto(false)}>Cancelar</button>
      </div>
    </form>}
    {dialogoDeConfirmacao}
  </section>
}
