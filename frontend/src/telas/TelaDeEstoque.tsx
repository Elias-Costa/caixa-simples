import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { estoque, type EstoqueDoProduto } from '../api/estoque'
import { erroDeCadastro } from './erroDeCadastro'

const quantidade = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 })

function lerQuantidade(valor: string, permiteNegativo: boolean): number {
  const formato = permiteNegativo ? /^-?\d+(?:[.,]\d{1,3})?$/ : /^\d+(?:[.,]\d{1,3})?$/
  if (!formato.test(valor.trim())) throw new Error('Informe uma quantidade com até três casas decimais.')
  return Number(valor.replace(',', '.'))
}

export function TelaDeEstoque() {
  const [produtos, setProdutos] = useState<EstoqueDoProduto[]>([])
  const [baixos, setBaixos] = useState<EstoqueDoProduto[]>([])
  const [produtoId, setProdutoId] = useState('')
  const [modo, setModo] = useState<'diferenca' | 'contagem'>('diferenca')
  const [valor, setValor] = useState('')
  const [motivo, setMotivo] = useState('')
  const [minimo, setMinimo] = useState('')
  const [carregando, setCarregando] = useState(true)
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)

  const carregar = useCallback(async () => {
    setCarregando(true)
    try {
      const [lista, alerta] = await Promise.all([estoque.produtos(), estoque.baixo()])
      setProdutos(lista)
      setBaixos(alerta)
      setProdutoId((atual) => lista.some((produto) => produto.id === atual) ? atual : (lista[0]?.id ?? ''))
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setCarregando(false)
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  const produto = produtos.find((item) => item.id === produtoId)
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
    try {
      await estoque.ajustar(produto.id, ajuste, motivo.trim())
      setValor('')
      setMotivo('')
      await carregar()
      setAviso('Estoque ajustado.')
    } catch (falha) {
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
    <h2 className="titulo">Estoque</h2>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {aviso && <p role="status">{aviso}</p>}
    {carregando ? <p>Carregando estoque...</p> : <>
      <section className="relatorios__cartao">
        <h3>Estoque baixo</h3>
        {baixos.length === 0 ? <p>Nenhum produto com estoque baixo.</p> :
          <ul className="estoque__lista">{baixos.map((item) => <li key={item.id}>
            <strong>{item.nome}</strong>: {quantidade.format(item.estoqueAtual)} {item.unidade ?? ''}
            {' · '}mínimo {quantidade.format(item.estoqueMinimo)}
          </li>)}</ul>}
      </section>
      <section className="relatorios__cartao">
        <h3>Produtos</h3>
        {produtos.length === 0 ? <p>Nenhum produto ativo. Cadastre um produto para controlar o estoque.</p> : <>
          <label className="estoque__selecao">Produto
            <select className="campo__entrada" value={produtoId} onChange={(evento) => { setProdutoId(evento.target.value); setValor(''); setMinimo('') }}>
              {produtos.map((item) => <option key={item.id} value={item.id}>{item.nome}{item.codigo ? ` · ${item.codigo}` : ''}</option>)}
            </select>
          </label>
          {produto && <p>Saldo atual: <strong>{quantidade.format(produto.estoqueAtual)} {produto.unidade ?? ''}</strong>
            {' · '}mínimo: <strong>{quantidade.format(produto.estoqueMinimo)} {produto.unidade ?? ''}</strong></p>}
          <div className="estoque__formularios">
            <form onSubmit={(evento) => void ajustar(evento)}>
              <h4>Ajustar saldo</h4>
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
              <button className="botao" disabled={salvando}>Confirmar ajuste</button>
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
    </>}
  </section>
}
