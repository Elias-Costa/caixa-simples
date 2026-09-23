import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { relatorios, type Faturamento } from '../api/relatorios'
import { hojeNoBalcao } from '../dataDoBalcao'
import { erroDeCadastro } from './erroDeCadastro'

const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}

export function TelaDeRelatorios() {
  const [inicio, setInicio] = useState(hojeNoBalcao)
  const [fim, setFim] = useState(hojeNoBalcao)
  const [resultado, setResultado] = useState<Faturamento | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [carregando, setCarregando] = useState(true)
  const pedidoAtual = useRef(0)

  const consultar = useCallback(async (pedido: Promise<Faturamento>) => {
    const numero = ++pedidoAtual.current
    setCarregando(true)
    setErro(null)
    setResultado(null)
    try {
      const faturamento = await pedido
      if (numero === pedidoAtual.current) setResultado(faturamento)
    } catch (falha) {
      if (numero === pedidoAtual.current) setErro(erroDeCadastro(falha))
    } finally {
      if (numero === pedidoAtual.current) setCarregando(false)
    }
  }, [])

  const consultarHoje = useCallback(() => {
    const hoje = hojeNoBalcao()
    setInicio(hoje)
    setFim(hoje)
    return consultar(relatorios.faturamentoDoDia(hoje))
  }, [consultar])

  useEffect(() => { queueMicrotask(() => void consultarHoje()) }, [consultarHoje])

  function consultarPeriodo(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    void consultar(relatorios.faturamentoDoPeriodo(inicio, fim))
  }

  return <section className="relatorios">
    <h2 className="titulo">Faturamento</h2>
    <section className="relatorios__cartao">
      <form className="relatorios__formulario" onSubmit={consultarPeriodo}>
        <label>Início
          <input type="date" required value={inicio}
            onChange={(evento) => setInicio(evento.target.value)} />
        </label>
        <label>Fim
          <input type="date" required value={fim} min={inicio}
            onChange={(evento) => setFim(evento.target.value)} />
        </label>
        <button className="botao" disabled={carregando}>Consultar período</button>
        <button className="botao botao--secundario" type="button" disabled={carregando}
          onClick={() => void consultarHoje()}>Hoje</button>
      </form>
    </section>

    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {carregando ? <p>Carregando faturamento...</p> : resultado && <section className="relatorios__cartao" aria-live="polite">
      <h3>{resultado.inicio === resultado.fim
        ? `Faturamento de ${mostrarData(resultado.inicio)}`
        : `Faturamento de ${mostrarData(resultado.inicio)} a ${mostrarData(resultado.fim)}`}</h3>
      <p className="relatorios__total">{moeda.format(resultado.total)}</p>
      <p>{resultado.quantidadeDeVendas} {resultado.quantidadeDeVendas === 1 ? 'venda concluída' : 'vendas concluídas'}</p>
      {resultado.quantidadeDeVendas === 0 && <p>Nenhuma venda concluída neste período.</p>}
    </section>}
  </section>
}
