import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { SemConexao } from '../api/cliente'
import { estoque, type EstoqueDoProduto } from '../api/estoque'
import { quantidade, quantidadeComUnidade } from '../formatos'
import { erroDeCadastro } from './erroDeCadastro'
import { Mensagem } from './Mensagem'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'

/** O tamanho da coluna do motivo, o mesmo do servidor. */
const TAMANHO_DO_MOTIVO = 120

function lerQuantidade(valor: string, permiteNegativo: boolean): number {
  const formato = permiteNegativo ? /^-?\d+(?:[.,]\d{1,3})?$/ : /^\d+(?:[.,]\d{1,3})?$/
  if (!formato.test(valor.trim())) throw new Error('Informe uma quantidade com até três casas decimais.')
  return Number(valor.replace(',', '.'))
}

const porNome = (a: EstoqueDoProduto, b: EstoqueDoProduto) => a.nome.localeCompare(b.nome, 'pt-BR')

/**
 * O alerta com o saldo negativo no alto, que é venda acima do que o estoque registrava, e o resto em
 * ordem alfabética. Não ordena pela distância até o mínimo: quilos e unidades não se comparam.
 */
function emOrdemDeAlerta(baixos: EstoqueDoProduto[]): EstoqueDoProduto[] {
  return [...baixos].sort((a, b) => Number(b.estoqueAtual < 0) - Number(a.estoqueAtual < 0) || porNome(a, b))
}


export function TelaDeEstoque() {
  const [produtos, setProdutos] = useState<EstoqueDoProduto[]>([])
  const [baixos, setBaixos] = useState<EstoqueDoProduto[]>([])
  const formulariosRef = useRef<HTMLElement>(null)
  const seletorRef = useRef<HTMLSelectElement>(null)
  const [produtoId, setProdutoId] = useState('')
  const [modo, setModo] = useState<'diferenca' | 'contagem'>('diferenca')
  const [valor, setValor] = useState('')
  const [motivo, setMotivo] = useState('')
  const [comprado, setComprado] = useState('')
  const [motivoDaEntrada, setMotivoDaEntrada] = useState('')
  const [minimo, setMinimo] = useState('')
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)
  /**
   * O id do movimento que ficou sem resposta. A resposta perdida pode ter gravado, e o toque igual
   * antes de reabrir a tela leva o mesmo id, que o servidor reconhece sem mover o saldo de novo. A
   * lista relida mostra o saldo, e não o movimento: por isso qualquer resposta, outro conteúdo ou a
   * tela reaberta liberam o id, e repetir depois disso é um ajuste de propósito.
   */
  const semResposta = useRef<{ chave: string; id: string } | null>(null)

  function idDoMovimento(chave: string): string {
    if (semResposta.current?.chave !== chave) semResposta.current = { chave, id: crypto.randomUUID() }
    return semResposta.current.id
  }

  function depoisDaResposta(falha?: unknown) {
    if (!(falha instanceof SemConexao)) semResposta.current = null
  }

  const carregar = useCallback(async () => {
    setCarregando(true)
    try {
      const [lista, alerta] = await Promise.all([estoque.produtos(), estoque.baixo()])
      const emOrdem = [...lista].sort(porNome)
      setProdutos(emOrdem)
      setBaixos(emOrdemDeAlerta(alerta))
      setProdutoId((atual) => emOrdem.some((produto) => produto.id === atual) ? atual : (emOrdem[0]?.id ?? ''))
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setCarregando(false)
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  function trocarProduto(id: string) {
    setProdutoId(id)
    setValor('')
    setMinimo('')
    setComprado('')
  }

  /**
   * O item tocado no alerta ou na lista vai para os formulários, sem procurá-lo de novo no seletor. O
   * foco vai ao seletor, que diz qual produto ficou escolhido, sem abrir o teclado de um campo.
   */
  function escolher(id: string) {
    trocarProduto(id)
    formulariosRef.current?.scrollIntoView?.({ block: 'start' })
    seletorRef.current?.focus({ preventScroll: true })
  }

  /**
   * O alerta e a lista inteira na mesma tabela. O nome é o botão que escolhe o produto, e a marca do
   * negativo fica embaixo dele: ao lado do saldo, espremeria o nome no celular.
   */
  function tabelaDeSaldos(idDoTitulo: string, itens: EstoqueDoProduto[]) {
    return <table className="tabela" aria-labelledby={idDoTitulo}>
      <thead><tr><th scope="col">Produto</th><th scope="col" className="tabela__numero">Saldo</th>
        <th scope="col" className="tabela__numero">Mínimo</th></tr></thead>
      <tbody>{itens.map((item) => <tr key={item.id}>
        <th scope="row">
          <button type="button" className="estoque__escolher" onClick={() => escolher(item.id)}>{item.nome}</button>
          {item.estoqueAtual < 0 && <span className="marcas"><span className="marca marca--alerta">negativo</span></span>}
        </th>
        <td className={item.estoqueAtual < 0 ? 'tabela__numero estoque__negativo' : 'tabela__numero'}>
          {quantidadeComUnidade(item.estoqueAtual, item.unidade)}</td>
        <td className="tabela__numero">{quantidadeComUnidade(item.estoqueMinimo, item.unidade)}</td>
      </tr>)}</tbody>
    </table>
  }

  const produto = produtos.find((item) => item.id === produtoId)
  const temNegativo = baixos.some((item) => item.estoqueAtual < 0)
  let diferenca: number | null = null
  if (produto && valor.trim()) {
    try {
      const lido = lerQuantidade(valor, modo === 'diferenca')
      diferenca = modo === 'contagem'
        ? Number((lido - produto.estoqueAtual).toFixed(3)) : lido
    } catch { /* A mensagem de formato aparece ao enviar. */ }
  }

  async function ajustar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!produto) return
    setErro(null)
    setAviso(null)
    let ajuste: number
    try {
      const lido = lerQuantidade(valor, modo === 'diferenca')
      ajuste = modo === 'contagem' ? Number((lido - produto.estoqueAtual).toFixed(3)) : lido
      if (ajuste === 0) throw new Error('A diferença precisa ser diferente de zero.')
      if (!motivo.trim()) throw new Error('Informe o motivo do ajuste.')
    } catch (falha) {
      setErro(falha instanceof Error ? falha.message : 'Confira a quantidade.')
      return
    }
    setSalvando(true)
    const motivoDoAjuste = motivo.trim()
    try {
      await estoque.ajustar(produto.id, ajuste, motivoDoAjuste,
        idDoMovimento(JSON.stringify(['ajuste', produto.id, ajuste, motivoDoAjuste])))
      depoisDaResposta()
      setValor('')
      setMotivo('')
      await carregar()
      setAviso('Estoque ajustado.')
    } catch (falha) {
      depoisDaResposta(falha)
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(false)
    }
  }

  /** Só com rede, como o ajuste: é decisão do administrador sobre o saldo, não gesto do balcão. */
  async function registrarEntrada(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!produto) return
    setErro(null)
    setAviso(null)
    let chegou: number
    try {
      chegou = lerQuantidade(comprado, false)
      if (chegou === 0) throw new Error('A quantidade da entrada precisa ser maior que zero.')
    } catch (falha) {
      setErro(falha instanceof Error ? falha.message : 'Confira a quantidade.')
      return
    }
    setSalvando(true)
    const motivoDaCompra = motivoDaEntrada.trim()
    try {
      await estoque.registrarEntrada(produto.id, chegou, motivoDaCompra,
        idDoMovimento(JSON.stringify(['entrada', produto.id, chegou, motivoDaCompra])))
      depoisDaResposta()
      setComprado('')
      setMotivoDaEntrada('')
      await carregar()
      setAviso('Entrada registrada.')
    } catch (falha) {
      depoisDaResposta(falha)
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(false)
    }
  }

  async function definirMinimo(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!produto) return
    setErro(null)
    setAviso(null)
    let novoMinimo: number
    try {
      novoMinimo = lerQuantidade(minimo, false)
    } catch (falha) {
      setErro(falha instanceof Error ? falha.message : 'Confira o mínimo.')
      return
    }
    setSalvando(true)
    try {
      await estoque.definirMinimo(produto.id, novoMinimo)
      setMinimo('')
      await carregar()
      setAviso('Estoque mínimo atualizado.')
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(false)
    }
  }

  return <section className="estoque">
    <TituloComAjuda titulo="Estoque" ajuda={AJUDA.estoque} />
    <Mensagem tipo="erro" texto={erro} />
    <Mensagem tipo="sucesso" texto={aviso} />
    {carregando ? <p>Carregando estoque...</p> : <>
      <section className="relatorios__cartao">
        <h3 id="estoque-baixo">Estoque baixo</h3>
        {baixos.length === 0 ? <p>Nenhum produto com estoque baixo.</p> : tabelaDeSaldos('estoque-baixo', baixos)}
        {temNegativo && <p>Saldo negativo é venda acima do estoque registrado. Conte a prateleira e lance a contagem física no ajuste.</p>}
      </section>
      <section className="relatorios__cartao" ref={formulariosRef}>
        <h3>Produtos</h3>
        {produtos.length === 0 ? <p>Nenhum produto ativo. Cadastre um produto para controlar o estoque.</p> : <>
          <label className="estoque__selecao">Produto
            <select ref={seletorRef} className="campo__entrada" value={produtoId} onChange={(evento) => trocarProduto(evento.target.value)}>
              {produtos.map((item) => <option key={item.id} value={item.id}>{item.nome}{item.codigo ? ` · ${item.codigo}` : ''}</option>)}
            </select>
          </label>
          {/* O saldo negativo leva a palavra, e a cor do alerta só a reforça. */}
          {produto && <p>Saldo atual: <strong className={produto.estoqueAtual < 0 ? 'estoque__negativo' : undefined}>
            {quantidadeComUnidade(produto.estoqueAtual, produto.unidade)}</strong>
            {produto.estoqueAtual < 0 && <> <span className="marca marca--alerta">negativo</span></>}
            {' · '}mínimo: <strong>{quantidadeComUnidade(produto.estoqueMinimo, produto.unidade)}</strong></p>}
          <div className="estoque__formularios">
            <form onSubmit={(evento) => void registrarEntrada(evento)}>
              <h4>Entrada de mercadoria</h4>
              <label>Quantidade comprada
                <input className="campo__entrada" inputMode="decimal" value={comprado} required
                  onChange={(evento) => setComprado(evento.target.value)} placeholder="Ex.: 12" />
              </label>
              <label>Motivo (opcional)
                <input className="campo__entrada" value={motivoDaEntrada} maxLength={TAMANHO_DO_MOTIVO}
                  onChange={(evento) => setMotivoDaEntrada(evento.target.value)} placeholder="Ex.: nota 123 ou fornecedor" />
              </label>
              <button className="botao" disabled={salvando}>Registrar entrada</button>
            </form>
            <form onSubmit={(evento) => void ajustar(evento)}>
              <h4>Ajustar saldo</h4>
              <p>Para perda, quebra ou contagem. A mercadoria que chegou vai na entrada de mercadoria.</p>
              <label>Modo
                <select className="campo__entrada" value={modo} onChange={(evento) => { setModo(evento.target.value as 'diferenca' | 'contagem'); setValor('') }}>
                  <option value="diferenca">Diferença (perda ou sobra)</option>
                  <option value="contagem">Contagem física</option>
                </select>
              </label>
              <label>{modo === 'contagem' ? 'Quantidade contada' : 'Diferença com sinal'}
                <input className="campo__entrada" inputMode="decimal" value={valor} required
                  onChange={(evento) => setValor(evento.target.value)} placeholder={modo === 'contagem' ? 'Ex.: 12' : 'Ex.: -2 ou 3'} />
              </label>
              {modo === 'contagem' && diferenca !== null && <p>Diferença a lançar: <strong>{diferenca > 0 ? '+' : ''}{quantidade.format(diferenca)} {produto?.unidade ?? ''}</strong></p>}
              <label>Motivo
                <input className="campo__entrada" value={motivo} required onChange={(evento) => setMotivo(evento.target.value)} placeholder="Ex.: perda, quebra ou contagem" />
              </label>
              {/* A entrada é a ação de todo dia e fica como a principal; o ajuste e o mínimo, não. */}
              <button className="botao botao--secundario" disabled={salvando}>Confirmar ajuste</button>
            </form>
            <form onSubmit={(evento) => void definirMinimo(evento)}>
              <h4>Alerta de estoque baixo</h4>
              <p>O produto aparece no alerta quando o saldo chega ao mínimo ou fica abaixo dele.</p>
              <label>Novo estoque mínimo
                <input className="campo__entrada" inputMode="decimal" value={minimo} required
                  onChange={(evento) => setMinimo(evento.target.value)} placeholder="Ex.: 5" />
              </label>
              <button className="botao botao--secundario" disabled={salvando}>Salvar mínimo</button>
            </form>
          </div>
        </>}
      </section>
      {/* A lista inteira vem depois dos formulários: a ação da tela antes do extrato longo. */}
      {produtos.length > 0 && <section className="relatorios__cartao">
        <h3 id="estoque-saldos">Saldo dos produtos</h3>
        {tabelaDeSaldos('estoque-saldos', produtos)}
      </section>}
    </>}
  </section>
}
