import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { cadastro, type Produto } from '../api/cadastro'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { vendas, type Comprovante, type FormaPagamento, type ResumoDaVenda, type Venda } from '../api/vendas'
import { useSessao } from '../sessao/useSessao'
import { erroDeCadastro } from './erroDeCadastro'

const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
const dataHora = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short', timeStyle: 'short', timeZone: 'America/Bahia',
})

function numero(texto: string): number { return Number(texto.replace(',', '.')) }

/** O multiplicador na busca usa o mesmo estado do campo visível, para evitar duas quantidades. */
function separarMultiplicador(texto: string): { termo: string; quantidade?: string } {
  const encontrado = texto.match(/^(\d+(?:[.,]\d{1,3})?)\*(.*)$/)
  return encontrado ? { termo: encontrado[2], quantidade: encontrado[1].replace(',', '.') }
    : { termo: texto }
}

function textoDoComprovante(comprovante: Comprovante, negocio: string, operador: string): string {
  return [
    negocio, `Operador: ${operador}`, 'Comprovante não fiscal',
    dataHora.format(new Date(comprovante.concluidoEm)),
    ...comprovante.linhas.map((linha) =>
      `${linha.quantidade} × ${linha.nome}: ${moeda.format(linha.subtotal)}`),
    `Desconto da venda: ${moeda.format(comprovante.descontoDaVenda)}`,
    `Total: ${moeda.format(comprovante.valorTotal)}`,
    ...comprovante.parcelas.map((parcela) =>
      `${parcela.forma}: ${moeda.format(parcela.valor)}`),
    `Troco: ${moeda.format(comprovante.troco)}`,
    `Venda: ${comprovante.vendaId}`,
  ].join('\n')
}

export function TelaDeVenda() {
  const { identidade } = useSessao()
  const buscaRef = useRef<HTMLInputElement>(null)
  const [sessao, setSessao] = useState<SessaoCaixa>()
  const [historico, setHistorico] = useState<ResumoDaVenda[]>([])
  const [atual, setAtual] = useState<Venda>()
  const [comprovante, setComprovante] = useState<Comprovante>()
  const [busca, setBusca] = useState('')
  const [quantidade, setQuantidade] = useState('1')
  const [descontoItem, setDescontoItem] = useState('0')
  const [descontoVenda, setDescontoVenda] = useState('0')
  const [resultados, setResultados] = useState<Produto[]>([])
  const [forma, setForma] = useState<FormaPagamento>('DINHEIRO')
  const [valorPagamento, setValorPagamento] = useState('')
  const [valorRecebido, setValorRecebido] = useState('')
  const [troco, setTroco] = useState<number>()
  const [erro, setErro] = useState<string>()
  const [ocupado, setOcupado] = useState(false)
  const [carregando, setCarregando] = useState(true)

  useEffect(() => {
    let vivo = true
    caixa.abertaDoOperadorAtual().then(async (aberta) => {
      if (!vivo) return
      setSessao(aberta)
      if (aberta) setHistorico(await vendas.daSessao(aberta.id))
    }).catch((falha) => { if (vivo) setErro(erroDeCadastro(falha)) })
      .finally(() => { if (vivo) setCarregando(false) })
    return () => { vivo = false }
  }, [])

  useEffect(() => {
    if (!sessao || !busca.trim()) return
    let vivo = true
    const espera = window.setTimeout(() => {
      cadastro.buscarProdutos(busca.trim()).then((produtos) => {
        if (vivo) setResultados(produtos)
      }).catch((falha) => { if (vivo) setErro(erroDeCadastro(falha)) })
    }, 180)
    return () => { vivo = false; window.clearTimeout(espera) }
  }, [busca, sessao])

  async function atualizar(id: string) {
    const venda = await vendas.consultar(id)
    setAtual(venda)
    if (sessao) setHistorico(await vendas.daSessao(sessao.id))
  }

  async function adicionar(produto: Produto) {
    if (!sessao) return
    setOcupado(true); setErro(undefined)
    if (atual?.status !== 'ABERTA') { setComprovante(undefined); setTroco(undefined) }
    let id: string | undefined
    try {
      id = atual?.status === 'ABERTA' ? atual.id : (await vendas.iniciar(sessao.id)).id
      await vendas.adicionarItem(id, produto.id, numero(quantidade), numero(descontoItem))
      await atualizar(id)
      setBusca(''); setResultados([]); setQuantidade('1'); setDescontoItem('0')
      buscaRef.current?.focus()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
      if (id && !atual) await atualizar(id).catch(() => undefined)
    }
    finally { setOcupado(false) }
  }

  async function remover(itemId: string) {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try { await vendas.removerItem(atual.id, itemId); await atualizar(atual.id) }
    catch (falha) { setErro(erroDeCadastro(falha)) }
    finally { setOcupado(false) }
  }

  async function descontar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try { await vendas.descontar(atual.id, numero(descontoVenda)); await atualizar(atual.id) }
    catch (falha) { setErro(erroDeCadastro(falha)) }
    finally { setOcupado(false) }
  }

  async function pagar(evento: { preventDefault(): void }, concluirJunto = false) {
    evento.preventDefault()
    if (!atual) return
    const valor = valorPagamento ? numero(valorPagamento) : atual.faltaPagar
    const recebido = forma === 'DINHEIRO'
      ? (valorRecebido ? numero(valorRecebido) : valor) : undefined
    setOcupado(true); setErro(undefined)
    try {
      const resultado = await vendas.pagar(atual.id, forma, valor, recebido)
      setTroco(resultado.troco)
      setValorPagamento(''); setValorRecebido('')
      if (concluirJunto) await concluir(atual.id)
      else await atualizar(atual.id)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
      await atualizar(atual.id).catch(() => undefined)
    } finally { setOcupado(false) }
  }

  async function concluir(id: string) {
    await vendas.concluir(id)
    await atualizar(id)
    setComprovante(await vendas.comprovante(id))
  }

  async function concluirPendente() {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try { await concluir(atual.id) }
    catch (falha) { setErro(erroDeCadastro(falha)) }
    finally { setOcupado(false) }
  }

  async function cancelar() {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try { await vendas.cancelar(atual.id); await atualizar(atual.id); setComprovante(undefined) }
    catch (falha) { setErro(erroDeCadastro(falha)) }
    finally { setOcupado(false) }
  }

  async function selecionar(resumo: ResumoDaVenda) {
    setErro(undefined); setTroco(undefined); setComprovante(undefined)
    try {
      await atualizar(resumo.id)
      if (resumo.status === 'CONCLUIDA') setComprovante(await vendas.comprovante(resumo.id))
    } catch (falha) { setErro(erroDeCadastro(falha)) }
  }

  async function compartilhar() {
    if (!comprovante || !identidade || !navigator.share) return
    try {
      await navigator.share({
        title: 'Comprovante não fiscal',
        text: textoDoComprovante(comprovante, identidade.nomeNegocio,
          comprovante.usuarioId === identidade.usuarioId ? identidade.nome : comprovante.usuarioId),
      })
    } catch (falha) {
      if ((falha as Error).name !== 'AbortError') setErro(erroDeCadastro(falha))
    }
  }

  function mudarBusca(texto: string) {
    const separado = separarMultiplicador(texto)
    if (separado.quantidade) setQuantidade(separado.quantidade)
    setBusca(separado.termo)
    if (!separado.termo.trim()) setResultados([])
  }

  return <section className="pdv">
    <h2 className="titulo">Venda</h2>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {carregando ? <p>Carregando...</p> : !sessao ? <p>Abra sua SessaoCaixa para vender. <Link to="/caixa">Ir ao caixa</Link></p>
      : <div className="pdv__grade">
        <section className="pdv__painel">
          <h3>Busca de produtos</h3>
          <div className="pdv__busca">
            <label>Produto por nome ou código
              <input ref={buscaRef} autoFocus value={busca} onChange={(evento) => mudarBusca(evento.target.value)}
                placeholder="Digite ou passe o código" />
            </label>
            <label>Quantidade
              <input type="number" min="0.001" step="0.001" value={quantidade}
                onChange={(evento) => setQuantidade(evento.target.value)} />
            </label>
          </div>
          <p className="pdv__ajuda">Também aceita 3* no início da busca para lançar três unidades.</p>
          {identidade?.perfil === 'ADMIN' && <label>Desconto deste item
            <input type="number" min="0" step="0.01" value={descontoItem}
              onChange={(evento) => setDescontoItem(evento.target.value)} />
          </label>}
          {resultados.length > 0 && <ul className="pdv__resultados">
            {resultados.map((produto) => <li key={produto.id}>
              <button type="button" className="botao botao--secundario" disabled={ocupado}
                onClick={() => void adicionar(produto)}>
                {produto.nome} · {moeda.format(produto.preco)}{produto.codigo && ` · ${produto.codigo}`}
              </button>
            </li>)}
          </ul>}
        </section>

        <section className="pdv__painel">
          <div className="pdv__topo"><h3>Comanda</h3>
            <button className="botao botao--secundario" type="button" onClick={() => {
              setAtual(undefined); setComprovante(undefined); setTroco(undefined); buscaRef.current?.focus()
            }}>Nova venda</button>
          </div>
          {!atual ? <p>Busque e escolha o primeiro produto para iniciar.</p> : <>
            <p>Venda {atual.id.slice(0, 8)} · {atual.status}</p>
            {atual.itens.length === 0 ? <p>Nenhum item lançado.</p> : <ul className="pdv__itens">
              {atual.itens.map((item) => <li key={item.id}>
                <span>{item.quantidade} × {item.nome} · {moeda.format(item.subtotal)}
                  {item.desconto > 0 && ` (desconto ${moeda.format(item.desconto)})`}</span>
                {atual.status === 'ABERTA' && <button className="botao botao--secundario" type="button"
                  disabled={ocupado} onClick={() => void remover(item.id)}>Remover</button>}
              </li>)}
            </ul>}
            {identidade?.perfil === 'ADMIN' && atual.status === 'ABERTA' && <form onSubmit={(evento) => void descontar(evento)} className="pdv__desconto">
              <label>Desconto da venda
                <input type="number" min="0" step="0.01" value={descontoVenda}
                  onChange={(evento) => setDescontoVenda(evento.target.value)} />
              </label>
              <button className="botao botao--secundario" disabled={ocupado}>Aplicar</button>
            </form>}
            <p className="pdv__total">Total: {moeda.format(atual.total)}</p>
            <p>Pago: {moeda.format(atual.pago)} · Falta: {moeda.format(atual.faltaPagar)}</p>
            {atual.parcelas.length > 0 && <ul className="pdv__parcelas">
              {atual.parcelas.map((parcela) => <li key={parcela.id}>{parcela.forma}: {moeda.format(parcela.valor)}
                {parcela.troco > 0 && ` · troco ${moeda.format(parcela.troco)}`}</li>)}
            </ul>}
            {troco !== undefined && <p role="status">Troco: {moeda.format(troco)}</p>}
            {atual.status === 'ABERTA' && <>
              {atual.itens.length > 0 && atual.faltaPagar > 0 && <form className="pdv__pagamento" onSubmit={(evento) => void pagar(evento)}>
                <h4>Pagamento</h4>
                <label>Forma
                  <select value={forma} onChange={(evento) => setForma(evento.target.value as FormaPagamento)}>
                    <option value="DINHEIRO">Dinheiro</option><option value="PIX">Pix manual</option>
                    <option value="CARTAO">Cartão manual</option>
                  </select>
                </label>
                <label>Valor da parcela
                  <input type="number" min="0.01" step="0.01" value={valorPagamento}
                    placeholder={atual.faltaPagar.toFixed(2)} onChange={(evento) => setValorPagamento(evento.target.value)} />
                </label>
                {forma === 'DINHEIRO' && <label>Valor recebido em dinheiro
                  <input type="number" min="0" step="0.01" value={valorRecebido}
                    placeholder={(valorPagamento ? numero(valorPagamento) : atual.faltaPagar).toFixed(2)}
                    onChange={(evento) => setValorRecebido(evento.target.value)} />
                </label>}
                <div className="pdv__acoes">
                  <button className="botao botao--secundario" disabled={ocupado}>Registrar parcela</button>
                  {atual.parcelas.length === 0 && (!valorPagamento || numero(valorPagamento) === atual.faltaPagar)
                    && <button className="botao" type="button" disabled={ocupado}
                    onClick={(evento) => void pagar(evento, true)}>Receber e concluir</button>}
                </div>
              </form>}
              {atual.faltaPagar === 0 && atual.itens.length > 0 && <button className="botao" type="button"
                disabled={ocupado} onClick={() => void concluirPendente()}>Concluir venda</button>}
              <button className="botao botao--secundario" type="button" disabled={ocupado}
                onClick={() => void cancelar()}>Cancelar venda</button>
            </>}
          </>}
        </section>

        <section className="pdv__painel pdv__historico">
          <h3>Vendas da SessaoCaixa</h3>
          {historico.length === 0 ? <p>Nenhuma venda nesta sessão.</p> : <ul>
            {historico.map((resumo) => <li key={resumo.id}>
              <button type="button" className="botao botao--secundario"
                onClick={() => void selecionar(resumo)}>
                {dataHora.format(new Date(resumo.criadoEm))} · {resumo.status} · {moeda.format(resumo.total)}
                {resumo.status === 'ABERTA' && ' · retomar ou cancelar'}
              </button>
            </li>)}
          </ul>}
        </section>
      </div>}

    {comprovante && identidade && <section className="comprovante" aria-label="Comprovante não fiscal">
      <h3>{identidade.nomeNegocio}</h3>
      <p>Operador: {comprovante.usuarioId === identidade.usuarioId
        ? identidade.nome : comprovante.usuarioId}</p>
      <p><strong>Comprovante não fiscal</strong></p>
      <p>{dataHora.format(new Date(comprovante.concluidoEm))}</p>
      <ul>{comprovante.linhas.map((linha, indice) => <li key={`${linha.produtoId}-${indice}`}>
        {linha.quantidade} × {linha.nome} · {moeda.format(linha.precoUnitario)} · {moeda.format(linha.subtotal)}
        {linha.desconto > 0 && ` · desconto ${moeda.format(linha.desconto)}`}
      </li>)}</ul>
      <p>Soma dos itens: {moeda.format(comprovante.somaDosItens)}</p>
      <p>Desconto da venda: {moeda.format(comprovante.descontoDaVenda)}</p>
      <p><strong>Total: {moeda.format(comprovante.valorTotal)}</strong></p>
      <ul>{comprovante.parcelas.map((parcela, indice) => <li key={indice}>
        {parcela.forma}: {moeda.format(parcela.valor)}</li>)}</ul>
      <p>Troco: {moeda.format(comprovante.troco)}</p>
      <p>Venda: {comprovante.vendaId}</p>
      <div className="comprovante__acoes">
        <button className="botao" type="button" onClick={() => window.print()}>Imprimir</button>
        {typeof navigator.share === 'function' && <button className="botao botao--secundario" type="button"
          onClick={() => void compartilhar()}>Compartilhar</button>}
      </div>
    </section>}
  </section>
}
