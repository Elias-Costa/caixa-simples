import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { cadastro, type DadosDoProduto, type Produto, type TipoProduto } from '../api/cadastro'
import { moeda, valorNoCampo } from '../formatos'
import { useOnline } from '../shell/useOnline'
import { useSessao } from '../sessao/useSessao'
import { listarGestos } from '../offline/fila'
import { erroDeCadastro, errosDosCampos } from './erroDeCadastro'
import { ErroDoCampo, Mensagem } from './Mensagem'

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
    preco: valorNoCampo(produto.preco),
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
  // O erro da lista fica no alto; o do formulário, dentro dele, perto do botão que o causou.
  const [erro, setErro] = useState<string | null>(null)
  const [erroDoFormulario, setErroDoFormulario] = useState<string | null>(null)
  const [camposComErro, setCamposComErro] = useState<Record<string, string>>({})
  const [aviso, setAviso] = useState<string | null>(null)
  const [pendentes, setPendentes] = useState(0)
  const formularioRef = useRef<HTMLFormElement>(null)
  const online = useOnline()

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
      ? { 'aria-invalid': true, 'aria-describedby': `produto-erro-${campo}` } : {}
  }

  function novo() {
    setEditando(null)
    setFormulario(vazio)
    setAberto(true)
    limparMensagens()
  }

  function editar(produto: Produto) {
    setEditando(produto.id)
    setFormulario(paraFormulario(produto))
    setAberto(true)
    limparMensagens()
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
    limparMensagens()
    let dados: DadosDoProduto
    try {
      dados = dadosDo(formulario)
    } catch (falha) {
      setErroDoFormulario(falha instanceof Error ? falha.message : 'Confira os dados do produto.')
      return
    }
    setSalvando(true)
    try {
      if (editando) await cadastro.editarProduto(editando, dados)
      else await cadastro.criarProduto(dados)
      setAberto(false)
      await carregar()
      // Sem rede, quem avisa é a contagem do que ficou guardado no aparelho, logo abaixo.
      if (online) setAviso(`${dados.nome.trim()} salvo.`)
    } catch (falha) {
      setErroDoFormulario(erroDeCadastro(falha))
      setCamposComErro(errosDosCampos(falha))
    } finally {
      setSalvando(false)
    }
  }

  async function inativar(produto: Produto) {
    if (!window.confirm(`Inativar ${produto.nome}?`)) return
    limparMensagens()
    try {
      await cadastro.inativarProduto(produto.id)
      await carregar()
      if (online) setAviso(`${produto.nome} inativado.`)
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

      <Mensagem tipo="erro" texto={erro} />
      <Mensagem tipo="sucesso" texto={aviso} />
      {pendentes > 0 && <p className="mensagem-info" role="status">{pendentes === 1 ? 'Uma alteração de item guardada' : `${pendentes} alterações de item guardadas`} neste dispositivo, aguardando sincronização.</p>}
      {carregando ? <p>Carregando...</p> : produtos.length === 0 ? <p>Nenhum item ativo.</p> : (
        <ul className="cadastro__lista">
          {produtos.map((produto) => (
            <li className="cadastro__item" key={produto.id}>
              <div className="cadastro__dados">
                <strong>{produto.nome}</strong> <span className="cadastro__apoio">{produto.tipo === 'SERVICO' ? 'Serviço' : 'Produto'}</span>
                <p>{produto.codigo ? `Código ${produto.codigo} · ` : ''}{produto.categoria ?? 'Sem categoria'} · {produto.unidade ?? 'Sem unidade'}</p>
              </div>
              {/* O catálogo sugerido nasce com preço zero; a marca lembra o que falta precificar. */}
              <div className="cadastro__valor">
                <strong>{moeda.format(produto.preco)}</strong>
                {produto.preco === 0 && <span className="cadastro__alerta">preço zerado</span>}
              </div>
              {admin && <div className="cadastro__acoes">
                <button className="botao botao--secundario" type="button" onClick={() => editar(produto)}>Editar</button>
                <button className="botao botao--perigo" type="button" onClick={() => void inativar(produto)}>Inativar</button>
              </div>}
            </li>
          ))}
        </ul>
      )}

      {admin && aberto && <form ref={formularioRef} className="cadastro__formulario" onSubmit={(evento) => void salvar(evento)}>
        <h3>{editando ? 'Editar item' : 'Novo item'}</h3>
        {/* A mensagem de cada campo fica fora do rótulo, que continua sendo só o nome do campo. */}
        {!editando ? <div className="campo"><label className="campo__rotulo" htmlFor="produto-tipo">Tipo</label>
          <select id="produto-tipo" className="campo__entrada" value={formulario.tipo} onChange={(evento) => campo('tipo', evento.target.value)} {...marcaDoCampo('tipo')}>
            <option value="PRODUTO">Produto</option><option value="SERVICO">Serviço</option>
          </select>
          <ErroDoCampo id="produto-erro-tipo" texto={camposComErro.tipo} />
        </div> : <p>Tipo: {formulario.tipo === 'SERVICO' ? 'Serviço' : 'Produto'}</p>}
        <div className="cadastro__grade">
          <div className="campo"><label className="campo__rotulo" htmlFor="produto-nome">Nome</label><input id="produto-nome" className="campo__entrada" value={formulario.nome} onChange={(evento) => campo('nome', evento.target.value)} required maxLength={120} {...marcaDoCampo('nome')} /><ErroDoCampo id="produto-erro-nome" texto={camposComErro.nome} /></div>
          <div className="campo"><label className="campo__rotulo" htmlFor="produto-preco">Preço (R$)</label><input id="produto-preco" className="campo__entrada" value={formulario.preco} onChange={(evento) => campo('preco', evento.target.value)} inputMode="decimal" required {...marcaDoCampo('preco')} /><ErroDoCampo id="produto-erro-preco" texto={camposComErro.preco} /></div>
          <div className="campo"><label className="campo__rotulo" htmlFor="produto-codigo">Código (opcional)</label><input id="produto-codigo" className="campo__entrada" value={formulario.codigo} onChange={(evento) => campo('codigo', evento.target.value)} maxLength={60} {...marcaDoCampo('codigo')} /><ErroDoCampo id="produto-erro-codigo" texto={camposComErro.codigo} /></div>
          <div className="campo"><label className="campo__rotulo" htmlFor="produto-categoria">Categoria (opcional)</label><input id="produto-categoria" className="campo__entrada" value={formulario.categoria} onChange={(evento) => campo('categoria', evento.target.value)} maxLength={60} {...marcaDoCampo('categoria')} /><ErroDoCampo id="produto-erro-categoria" texto={camposComErro.categoria} /></div>
          <div className="campo"><label className="campo__rotulo" htmlFor="produto-unidade">Unidade (opcional)</label><input id="produto-unidade" className="campo__entrada" value={formulario.unidade} onChange={(evento) => campo('unidade', evento.target.value)} maxLength={60} {...marcaDoCampo('unidade')} /><ErroDoCampo id="produto-erro-unidade" texto={camposComErro.unidade} /></div>
        </div>
        <fieldset className="cadastro__atributos"><legend>Atributos do item (opcional)</legend>
          {formulario.atributos.map((atributo, indice) => <div className="cadastro__par" key={indice}>
            <input className="campo__entrada" aria-label={`Chave do atributo ${indice + 1}`} placeholder="Chave" value={atributo.chave} onChange={(evento) => mudarAtributo(indice, 'chave', evento.target.value)} />
            <input className="campo__entrada" aria-label={`Valor do atributo ${indice + 1}`} placeholder="Valor" value={atributo.valor} onChange={(evento) => mudarAtributo(indice, 'valor', evento.target.value)} />
            <button className="botao botao--secundario" type="button" onClick={() => setFormulario((atual) => ({ ...atual, atributos: atual.atributos.filter((_, posicao) => posicao !== indice) }))}>Remover</button>
          </div>)}
          <button className="botao botao--secundario" type="button" onClick={() => setFormulario((atual) => ({ ...atual, atributos: [...atual.atributos, { chave: '', valor: '' }] }))}>Adicionar atributo</button>
          <ErroDoCampo id="produto-erro-atributos" texto={camposComErro.atributos} />
        </fieldset>
        <Mensagem tipo="erro" texto={erroDoFormulario} />
        <div className="cadastro__acoes">
          <button className="botao" type="submit" disabled={salvando}>{salvando ? 'Salvando...' : 'Salvar'}</button>
          <button className="botao botao--secundario" type="button" onClick={() => setAberto(false)}>Cancelar</button>
        </div>
      </form>}
    </section>
  )
}
