import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { cadastro, type DadosDoProduto, type Produto, type TipoProduto } from '../api/cadastro'
import { comServidor } from '../api/conexao'
import { contagem, lerDinheiro, moeda, valorNoCampo } from '../formatos'
import { apagarRascunho, chaveDoRascunho, guardarRascunho, lerRascunho } from '../sessao/rascunhos'
import { useSessao } from '../sessao/useSessao'
import { listarGestos } from '../offline/fila'
import { paraBusca } from '../textoDeBusca'
import { erroDeCadastro, errosDosCampos } from './erroDeCadastro'
import { filtrarPorNome } from './filtroPorNome'
import { ErroDoCampo, Mensagem } from './Mensagem'
import { ROTULO_CURTO_DO_TIPO, ROTULO_DO_TIPO } from './rotulos'
import { useConfirmacao } from './useConfirmacao'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'

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

/** O formulário aberto e deixado ao ir a outra tela: o item novo, sem id, ou o item em edição. */
type RascunhoDoItem = { editando: string | null; formulario: Formulario }

/** Em ordem alfabética, como os clientes: o servidor e o aparelho devolvem na ordem em que guardam. */
function emOrdem(produtos: Produto[]): Produto[] {
  return [...produtos].sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'))
}

/**
 * O valor do seletor de categoria. A categoria vai com um prefixo para não se confundir com as duas
 * opções fixas, porque o cadastro aceita qualquer texto como categoria.
 */
const TODAS = 'todas'
const SEM_CATEGORIA = 'sem'
const daCategoria = (categoria: string) => `categoria:${categoria}`

function naCategoria(produto: Produto, escolhida: string): boolean {
  if (escolhida === TODAS) return true
  if (escolhida === SEM_CATEGORIA) return !produto.categoria
  return !!produto.categoria && daCategoria(produto.categoria) === escolhida
}

/**
 * Quantos itens a lista desenha de cada vez. O catálogo de uma loja chega a milhares de itens, e desenhá-los todos
 * deixava a tela parada por segundos ao abrir e a cada letra do filtro. A busca e a categoria acham o resto.
 */
const BLOCO_DA_LISTA = 50

/**
 * A lista em blocos, com a contagem acima e Mostrar mais no fim. O foco vai ao primeiro item do bloco novo, que
 * aparece onde estava o botão: o teclado continua dali, e o leitor de tela lê o que entrou.
 */
function ListaEmBlocos({ itens, linha }: { itens: Produto[]; linha: (produto: Produto) => ReactNode }) {
  const [mostrando, setMostrando] = useState(BLOCO_DA_LISTA)
  const primeiroNovo = useRef<HTMLLIElement>(null)
  useEffect(() => { primeiroNovo.current?.focus() }, [mostrando])
  const falta = itens.length - mostrando
  // No primeiro bloco não há item novo para focar: o foco fica na busca ou onde a pessoa o deixou.
  const primeiroDoBloco = mostrando > BLOCO_DA_LISTA ? mostrando - BLOCO_DA_LISTA : null
  return <>
    {/* Montada enquanto falta item, e só o número muda: o leitor de tela anuncia a contagem depois de cada toque.
        No último bloco ela sai junto com o botão, e quem anuncia é o foco no primeiro item novo. */}
    {falta > 0 && <p className="cadastro__contagem" role="status">
      Mostrando {contagem.format(mostrando)} de {contagem.format(itens.length)} itens.
    </p>}
    <ul className="cadastro__lista">
      {itens.slice(0, mostrando).map((produto, posicao) => posicao === primeiroDoBloco
        ? <li className="cadastro__item" key={produto.id} ref={primeiroNovo} tabIndex={-1}>{linha(produto)}</li>
        : <li className="cadastro__item" key={produto.id}>{linha(produto)}</li>)}
    </ul>
    {falta > 0 && <button className="botao botao--secundario cadastro__mais" type="button"
      onClick={() => setMostrando((atual) => atual + BLOCO_DA_LISTA)}>
      Mostrar mais {Math.min(BLOCO_DA_LISTA, falta)}
    </button>}
  </>
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
  const preco = lerDinheiro(formulario.preco)
  if (preco === null) throw new Error('Informe um preço válido com até duas casas decimais.')
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
  const chaveDoItem = chaveDoRascunho(identidade, 'produtos')
  // O formulário deixado aberto volta aberto, com o que foi digitado e no mesmo item.
  const [rascunho] = useState(() => lerRascunho<RascunhoDoItem>(chaveDoItem))
  const [produtos, setProdutos] = useState<Produto[]>([])
  const [inativos, setInativos] = useState<Produto[]>([])
  const [busca, setBusca] = useState('')
  const [categoria, setCategoria] = useState(TODAS)
  const [editando, setEditando] = useState<string | null>(rascunho?.editando ?? null)
  const [formulario, setFormulario] = useState<Formulario>(rascunho?.formulario ?? vazio)
  const [aberto, setAberto] = useState(rascunho !== undefined)
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  // O erro da lista fica no alto; o do formulário, dentro dele, perto do botão que o causou.
  const [erro, setErro] = useState<string | null>(null)
  const [erroDoFormulario, setErroDoFormulario] = useState<string | null>(null)
  const [camposComErro, setCamposComErro] = useState<Record<string, string>>({})
  const [aviso, setAviso] = useState<string | null>(null)
  // A recusa da reativação fica no item tocado: ela diz qual outro item está com o código.
  const [erroDoInativo, setErroDoInativo] = useState<{ id: string; texto: string } | null>(null)
  const [pendentes, setPendentes] = useState(0)
  const formularioRef = useRef<HTMLFormElement>(null)
  const [confirmar, dialogoDeConfirmacao] = useConfirmacao()

  const carregar = useCallback(async () => {
    try {
      // Só o administrador reativa, e por isso só ele vê os inativos.
      const [ativos, foraDoCatalogo] = await Promise.all([
        cadastro.produtos(), admin ? cadastro.produtosInativos() : Promise.resolve([]),
      ])
      setProdutos(emOrdem(ativos))
      setInativos(emOrdem(foraDoCatalogo))
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
  }, [admin])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])
  // Enquanto o formulário está aberto, ele é o rascunho da tela; fechado, ao salvar ou em Cancelar, não há rascunho.
  useEffect(() => {
    if (aberto) guardarRascunho<RascunhoDoItem>(chaveDoItem, { editando, formulario })
    else apagarRascunho(chaveDoItem)
  }, [aberto, chaveDoItem, editando, formulario])
  // Espera a lista: o formulário que volta do rascunho abre antes dela, e ela o empurraria para fora da vista.
  useEffect(() => {
    if (!aberto || carregando) return
    formularioRef.current?.scrollIntoView?.({ block: 'start' })
    formularioRef.current?.querySelector('input')?.focus()
  }, [aberto, carregando])
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
    setErroDoInativo(null)
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
      // Sem servidor, quem avisa é a contagem do que ficou guardado no aparelho, logo abaixo. O
      // sinal é lido agora, depois da gravação: a que ficou sem resposta foi para a fila.
      if (comServidor()) setAviso(`${dados.nome.trim()} salvo.`)
    } catch (falha) {
      setErroDoFormulario(erroDeCadastro(falha))
      setCamposComErro(errosDosCampos(falha))
    } finally {
      setSalvando(false)
    }
  }

  async function inativar(produto: Produto) {
    const sim = await confirmar({
      titulo: `Inativar ${produto.nome}?`,
      texto: 'O item sai do balcão e da lista de ativos e pode ser reativado depois. As vendas '
        + 'antigas continuam com ele.',
      acao: 'Inativar',
    })
    if (!sim) return
    limparMensagens()
    try {
      await cadastro.inativarProduto(produto.id)
      await carregar()
      if (comServidor()) setAviso(`${produto.nome} inativado.`)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }

  async function reativar(produto: Produto) {
    limparMensagens()
    try {
      await cadastro.reativarProduto(produto.id)
      await carregar()
      if (comServidor()) setAviso(`${produto.nome} reativado.`)
    } catch (falha) {
      setErroDoInativo({ id: produto.id, texto: erroDeCadastro(falha) })
    }
  }

  // As opções saem das categorias dos itens ativos: escolhida qualquer uma, a lista tem o que mostrar.
  const categorias = [...new Set(produtos.flatMap((produto) => produto.categoria ? [produto.categoria] : []))]
    .sort((a, b) => a.localeCompare(b, 'pt-BR'))
  const temSemCategoria = produtos.some((produto) => !produto.categoria)
  // A categoria escolhida pode sumir, com o último item dela inativado; aí a lista volta inteira.
  const categoriaEscolhida = categoria === SEM_CATEGORIA && temSemCategoria
    || categorias.some((nome) => daCategoria(nome) === categoria) ? categoria : TODAS
  const filtrar = (lista: Produto[]) => filtrarPorNome(lista, busca, (produto) => produto.nome)
    .filter((produto) => naCategoria(produto, categoriaEscolhida))
  const encontrados = filtrar(produtos)
  const inativosEncontrados = filtrar(inativos)
  // Dois itens com o mesmo nome aparecem iguais na busca do balcão. Só avisa: o servidor aceita o
  // nome repetido, e recusar aqui seria regra nova. Compara como a busca, sem acento nem maiúscula.
  const nomeDigitado = paraBusca(formulario.nome)
  const nomeRepetido = aberto && nomeDigitado !== '' && produtos.some((produto) =>
    produto.id !== editando && paraBusca(produto.nome) === nomeDigitado)
  const descricaoDoNome = [camposComErro.nome ? 'produto-erro-nome' : null,
    nomeRepetido ? 'produto-aviso-nome' : null].filter(Boolean).join(' ') || undefined

  /** O que a linha do item mostra, igual nas duas listas. */
  function dadosDoItem(produto: Produto) {
    return <>
      <div className="cadastro__dados">
        <strong>{produto.nome}</strong> <span className="cadastro__apoio">{ROTULO_CURTO_DO_TIPO[produto.tipo]}</span>
        <p>{produto.codigo ? `Código ${produto.codigo} · ` : ''}{produto.categoria ?? 'Sem categoria'}</p>
      </div>
      {/* O preço é o da unidade de venda, como no balcão. O catálogo sugerido nasce com preço zero;
          a marca lembra o que falta precificar. */}
      <div className="cadastro__valor">
        <strong>{moeda.format(produto.preco)}{produto.unidade && `/${produto.unidade}`}</strong>
        {produto.preco === 0 && <span className="cadastro__alerta">preço zerado</span>}
      </div>
    </>
  }

  return (
    <section className="cadastro">
      <div className="cadastro__topo">
        <div><TituloComAjuda titulo="Produtos e serviços" ajuda={AJUDA.produtos} />
          <p>{admin ? 'Itens da sua Conta. Os inativos ficam no fim da página.' : 'Itens ativos da sua Conta.'}</p></div>
        {admin && <button className="botao" type="button" onClick={novo}>Novo item</button>}
      </div>

      <Mensagem tipo="erro" texto={erro} />
      <Mensagem tipo="sucesso" texto={aviso} />
      {pendentes > 0 && <p className="mensagem-info" role="status">{pendentes === 1 ? 'Uma alteração de item guardada' : `${pendentes} alterações de item guardadas`} neste aparelho, aguardando sincronização.</p>}
      {carregando ? <p>Carregando...</p> : <>
        {(produtos.length > 0 || inativos.length > 0) && <div className="cadastro__filtros">
          <label className="cadastro__busca">Buscar por nome
            <input type="search" value={busca} onChange={(evento) => setBusca(evento.target.value)} />
          </label>
          {categorias.length > 0 && <label className="cadastro__busca">Categoria
            <select value={categoriaEscolhida} onChange={(evento) => setCategoria(evento.target.value)}>
              <option value={TODAS}>Todas as categorias</option>
              {categorias.map((nome) => <option key={nome} value={daCategoria(nome)}>{nome}</option>)}
              {temSemCategoria && <option value={SEM_CATEGORIA}>Sem categoria</option>}
            </select>
          </label>}
        </div>}
        {/* A chave com o filtro recria as listas a cada filtro novo, e elas voltam ao primeiro bloco. Salvar,
            inativar e reativar não mudam a chave, e quem estava no meio da lista continua nele. */}
        {produtos.length === 0 ? <p>Nenhum item ativo.</p>
          : encontrados.length === 0 ? <p role="status">Nenhum item encontrado para {busca.trim()}.</p>
          : <ListaEmBlocos key={`${busca}|${categoriaEscolhida}`} itens={encontrados} linha={(produto) => <>
            {dadosDoItem(produto)}
            {admin && <div className="cadastro__acoes">
              <button className="botao botao--secundario" type="button" onClick={() => editar(produto)}>Editar</button>
              <button className="botao botao--perigo" type="button" onClick={() => void inativar(produto)}>Inativar</button>
            </div>}
          </>} />}
        {inativosEncontrados.length > 0 && <><h3>Inativos</h3>
          <ListaEmBlocos key={`${busca}|${categoriaEscolhida}`} itens={inativosEncontrados} linha={(produto) => <>
            {dadosDoItem(produto)}
            <button className="botao botao--secundario" type="button" onClick={() => void reativar(produto)}>Reativar</button>
            <Mensagem tipo="erro" texto={erroDoInativo?.id === produto.id ? erroDoInativo.texto : null} />
          </>} />
        </>}
      </>}

      {admin && aberto && <form ref={formularioRef} className="cadastro__formulario" onSubmit={(evento) => void salvar(evento)}>
        <h3>{editando ? 'Editar item' : 'Novo item'}</h3>
        {/* A mensagem de cada campo fica fora do rótulo, que continua sendo só o nome do campo. */}
        {!editando ? <div className="campo"><label className="campo__rotulo" htmlFor="produto-tipo">Tipo</label>
          <select id="produto-tipo" className="campo__entrada" value={formulario.tipo} onChange={(evento) => campo('tipo', evento.target.value)} {...marcaDoCampo('tipo')}>
            <option value="PRODUTO">{ROTULO_DO_TIPO.PRODUTO}</option><option value="SERVICO">{ROTULO_DO_TIPO.SERVICO}</option>
          </select>
          <ErroDoCampo id="produto-erro-tipo" texto={camposComErro.tipo} />
        </div> : <p>Tipo: {ROTULO_DO_TIPO[formulario.tipo]}</p>}
        <div className="cadastro__grade">
          <div className="campo"><label className="campo__rotulo" htmlFor="produto-nome">Nome</label><input id="produto-nome" className="campo__entrada" value={formulario.nome} onChange={(evento) => campo('nome', evento.target.value)} required maxLength={120} {...marcaDoCampo('nome')} aria-describedby={descricaoDoNome} /><ErroDoCampo id="produto-erro-nome" texto={camposComErro.nome} />
            {/* Sempre montado, para o leitor de tela anunciar o aviso quando o texto aparece. */}
            <span id="produto-aviso-nome" className="campo__aviso" role="status">{nomeRepetido ? 'Já existe um item ativo com este nome. Na busca do balcão, os dois aparecem iguais.' : ''}</span></div>
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
      {dialogoDeConfirmacao}
    </section>
  )
}
