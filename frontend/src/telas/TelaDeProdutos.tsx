import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { cadastro, type DadosDoProduto, type Produto, type TipoProduto } from '../api/cadastro'
import { useSessao } from '../sessao/useSessao'
import { listarGestos } from '../offline/fila'
import { erroDeCadastro } from './erroDeCadastro'

type Atributo = { chave: string; valor: string; original?: unknown; textoOriginal?: string }
type Formulario = {
  tipo: TipoProduto
  nome: string
  preco: string
  codigo: string
  categoria: string
  unidade: string
  atributos: Atributo[]
}

const vazio: Formulario = {
  tipo: 'PRODUTO', nome: '', preco: '', codigo: '', categoria: '', unidade: '', atributos: [],
}

function paraFormulario(produto: Produto): Formulario {
  return {
    tipo: produto.tipo,
    nome: produto.nome,
    preco: produto.preco.toFixed(2),
    codigo: produto.codigo ?? '',
    categoria: produto.categoria ?? '',
    unidade: produto.unidade ?? '',
    atributos: Object.entries(produto.atributos).map(([chave, original]) => {
      const valor = typeof original === 'string' ? original : JSON.stringify(original)
      return { chave, valor, original, textoOriginal: valor }
    }),
  }
}

function dadosDo(formulario: Formulario): DadosDoProduto {
  const preco = Number(formulario.preco.replace(',', '.'))
  if (!Number.isFinite(preco) || preco < 0 || !/^\d+([,.]\d{1,2})?$/.test(formulario.preco)) {
    throw new Error('Informe um preço válido com até duas casas decimais.')
  }
  const atributos: Record<string, unknown> = {}
  for (const atributo of formulario.atributos) {
    const chave = atributo.chave.trim()
    if (!chave) continue
    if (Object.hasOwn(atributos, chave)) throw new Error(`A chave ${chave} aparece duas vezes.`)
    atributos[chave] = atributo.textoOriginal === atributo.valor && atributo.original !== undefined
      ? atributo.original : atributo.valor
  }
  return {
    tipo: formulario.tipo,
    nome: formulario.nome,
    preco,
    codigo: formulario.codigo || null,
    categoria: formulario.categoria || null,
    unidade: formulario.unidade || null,
    atributos,
  }
}

export function TelaDeProdutos() {
  const { identidade } = useSessao()
  const admin = identidade?.perfil === 'ADMIN'
  const [produtos, setProdutos] = useState<Produto[]>([])
  const [editando, setEditando] = useState<string | null>(null)
  const [formulario, setFormulario] = useState<Formulario>(vazio)
  const [aberto, setAberto] = useState(false)
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [pendentes, setPendentes] = useState(0)
  const formularioRef = useRef<HTMLFormElement>(null)

  const carregar = useCallback(async () => {
    try {
      setProdutos(await cadastro.produtos())
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      if ('indexedDB' in globalThis) {
        void listarGestos().then((gestos) => setPendentes(gestos.filter((gesto) =>
          gesto.tipo.startsWith('produto.') && gesto.estado !== 'sent').length)).catch(() => undefined)
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

  function novo() {
    setEditando(null)
    setFormulario(vazio)
    setAberto(true)
    setErro(null)
  }

  function editar(produto: Produto) {
    setEditando(produto.id)
    setFormulario(paraFormulario(produto))
    setAberto(true)
    setErro(null)
  }

  function campo(campo: keyof Omit<Formulario, 'atributos'>, valor: string) {
    setFormulario((atual) => ({ ...atual, [campo]: valor }))
  }

  function mudarAtributo(indice: number, campo: 'chave' | 'valor', valor: string) {
    setFormulario((atual) => ({
      ...atual,
      atributos: atual.atributos.map((item, posicao) =>
        posicao === indice ? { ...item, [campo]: valor } : item),
    }))
  }

  async function salvar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setErro(null)
    let dados: DadosDoProduto
    try {
      dados = dadosDo(formulario)
    } catch (falha) {
      setErro(falha instanceof Error ? falha.message : 'Confira os dados do produto.')
      return
    }
    setSalvando(true)
    try {
      if (editando) await cadastro.editarProduto(editando, dados)
      else await cadastro.criarProduto(dados)
      setAberto(false)
      await carregar()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(false)
    }
  }

  async function inativar(produto: Produto) {
    if (!window.confirm(`Inativar ${produto.nome}?`)) return
    try {
      await cadastro.inativarProduto(produto.id)
      await carregar()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  return (
    <section className="cadastro">
      <div className="cadastro__topo">
        <div><h2 className="titulo">Produtos e serviços</h2><p>Itens ativos da sua Conta.</p></div>
        {admin && <button className="botao" type="button" onClick={novo}>Novo item</button>}
      </div>

      {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
      {pendentes > 0 && <p role="status">{pendentes === 1 ? 'Uma alteração de item guardada' : `${pendentes} alterações de item guardadas`} neste dispositivo, aguardando sincronização.</p>}
      {carregando ? <p>Carregando...</p> : produtos.length === 0 ? <p>Nenhum item ativo.</p> : (
        <ul className="cadastro__lista">
          {produtos.map((produto) => (
            <li className="cadastro__item" key={produto.id}>
              <div>
                <strong>{produto.nome}</strong> <span className="cadastro__apoio">{produto.tipo === 'SERVICO' ? 'Serviço' : 'Produto'}</span>
                <p>{produto.codigo ? `Código ${produto.codigo} · ` : ''}{produto.categoria ?? 'Sem categoria'} · {produto.unidade ?? 'Sem unidade'}</p>
              </div>
              <strong>R$ {produto.preco.toFixed(2).replace('.', ',')}</strong>
              {admin && <div className="cadastro__acoes">
                <button className="botao botao--secundario" type="button" onClick={() => editar(produto)}>Editar</button>
                <button className="botao botao--secundario" type="button" onClick={() => void inativar(produto)}>Inativar</button>
              </div>}
            </li>
          ))}
        </ul>
      )}

      {admin && aberto && <form ref={formularioRef} className="cadastro__formulario" onSubmit={(evento) => void salvar(evento)}>
        <h3>{editando ? 'Editar item' : 'Novo item'}</h3>
        {!editando ? <label className="campo"><span className="campo__rotulo">Tipo</span>
          <select className="campo__entrada" value={formulario.tipo} onChange={(evento) => campo('tipo', evento.target.value)}>
            <option value="PRODUTO">Produto</option><option value="SERVICO">Serviço</option>
          </select>
        </label> : <p>Tipo: {formulario.tipo === 'SERVICO' ? 'Serviço' : 'Produto'}</p>}
        <div className="cadastro__grade">
          <label className="campo"><span className="campo__rotulo">Nome</span><input className="campo__entrada" value={formulario.nome} onChange={(evento) => campo('nome', evento.target.value)} required maxLength={120} /></label>
          <label className="campo"><span className="campo__rotulo">Preço (R$)</span><input className="campo__entrada" value={formulario.preco} onChange={(evento) => campo('preco', evento.target.value)} inputMode="decimal" required /></label>
          <label className="campo"><span className="campo__rotulo">Código (opcional)</span><input className="campo__entrada" value={formulario.codigo} onChange={(evento) => campo('codigo', evento.target.value)} maxLength={60} /></label>
          <label className="campo"><span className="campo__rotulo">Categoria (opcional)</span><input className="campo__entrada" value={formulario.categoria} onChange={(evento) => campo('categoria', evento.target.value)} maxLength={60} /></label>
          <label className="campo"><span className="campo__rotulo">Unidade (opcional)</span><input className="campo__entrada" value={formulario.unidade} onChange={(evento) => campo('unidade', evento.target.value)} maxLength={60} /></label>
        </div>
        <fieldset className="cadastro__atributos"><legend>Atributos do item (opcional)</legend>
          {formulario.atributos.map((atributo, indice) => <div className="cadastro__par" key={indice}>
            <input className="campo__entrada" aria-label={`Chave do atributo ${indice + 1}`} placeholder="Chave" value={atributo.chave} onChange={(evento) => mudarAtributo(indice, 'chave', evento.target.value)} />
            <input className="campo__entrada" aria-label={`Valor do atributo ${indice + 1}`} placeholder="Valor" value={atributo.valor} onChange={(evento) => mudarAtributo(indice, 'valor', evento.target.value)} />
            <button className="botao botao--secundario" type="button" onClick={() => setFormulario((atual) => ({ ...atual, atributos: atual.atributos.filter((_, posicao) => posicao !== indice) }))}>Remover</button>
          </div>)}
          <button className="botao botao--secundario" type="button" onClick={() => setFormulario((atual) => ({ ...atual, atributos: [...atual.atributos, { chave: '', valor: '' }] }))}>Adicionar atributo</button>
        </fieldset>
        <div className="cadastro__acoes">
          <button className="botao" type="submit" disabled={salvando}>{salvando ? 'Salvando...' : 'Salvar'}</button>
          <button className="botao botao--secundario" type="button" onClick={() => setAberto(false)}>Cancelar</button>
        </div>
      </form>}
    </section>
  )
}
