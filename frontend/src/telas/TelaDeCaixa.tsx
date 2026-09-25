import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { SessaoCaixa } from '../api/caixa'
import { hojeNoBalcao } from '../dataDoBalcao'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { useSessao } from '../sessao/useSessao'
import { erroDeCadastro } from './erroDeCadastro'

const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
const caixa = criarCaixaLocal()
const dataHora = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short', timeStyle: 'short', timeZone: 'America/Bahia',
})

function dinheiroDigitado(valor: string): number {
  return Number(valor.replace(',', '.'))
}

export function TelaDeCaixa() {
  const { identidade } = useSessao()
  const [aberta, setAberta] = useState<SessaoCaixa | undefined>()
  const [selecionada, setSelecionada] = useState<SessaoCaixa | undefined>()
  const [historico, setHistorico] = useState<SessaoCaixa[]>([])
  const [dia, setDia] = useState(hojeNoBalcao)
  const [valorAbertura, setValorAbertura] = useState('0.00')
  const [tipoMovimento, setTipoMovimento] = useState<'SANGRIA' | 'SUPRIMENTO'>('SANGRIA')
  const [valorMovimento, setValorMovimento] = useState('')
  const [motivo, setMotivo] = useState('')
  const [valorContado, setValorContado] = useState('')
  const contado = valorContado.trim() ? dinheiroDigitado(valorContado) : null
  const diferencaPrevista = selecionada && contado !== null && Number.isFinite(contado) && contado >= 0
    ? Math.round((selecionada.valorFechamentoEsperado - contado) * 100) / 100 : null
  const [erro, setErro] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const [carregando, setCarregando] = useState(true)

  const carregar = useCallback(async () => {
    try {
      const [sessaoAberta, sessoesDoDia] = await Promise.all([
        caixa.abertaDoOperadorAtual(), caixa.historico(dia),
      ])
      setAberta(sessaoAberta)
      setHistorico(sessoesDoDia)
      setSelecionada((atual) => {
        if (atual) return sessoesDoDia.find((sessao) => sessao.id === atual.id)
          ?? (sessaoAberta?.id === atual.id ? sessaoAberta : atual)
        return sessaoAberta
      })
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setCarregando(false)
    }
  }, [dia])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])
  useEffect(() => {
    if (!selecionada || selecionada.movimentos) return
    let ativo = true
    caixa.consultar(selecionada.id).then((detalhes) => {
      if (ativo) setSelecionada(detalhes)
    }).catch((falha) => {
      if (ativo) setErro(erroDeCadastro(falha))
    })
    return () => { ativo = false }
  }, [selecionada])

  async function abrir(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setOcupado(true)
    try {
      await caixa.abrir(dinheiroDigitado(valorAbertura))
      await carregar()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setOcupado(false)
    }
  }

  async function lancar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!selecionada) return
    setOcupado(true)
    try {
      const valor = dinheiroDigitado(valorMovimento)
      if (tipoMovimento === 'SANGRIA') await caixa.sangrar(selecionada.id, valor, motivo)
      else await caixa.suprir(selecionada.id, valor, motivo)
      setValorMovimento('')
      setMotivo('')
      await carregar()
      setSelecionada(await caixa.consultar(selecionada.id))
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setOcupado(false)
    }
  }

  async function fechar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!selecionada) return
    setOcupado(true)
    try {
      await caixa.fechar(selecionada.id, dinheiroDigitado(valorContado))
      setValorContado('')
      await carregar()
      setSelecionada(await caixa.consultar(selecionada.id))
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setOcupado(false)
    }
  }

  return <section className="caixa">
    <h2 className="titulo">Caixa</h2>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {carregando ? <p>Carregando...</p> : <>
      <section className="caixa__cartao">
        <h3>Meu caixa</h3>
        {aberta ? <>
          <p>Aberto em {dataHora.format(new Date(aberta.abertaEm))}</p>
          <p>Valor inicial: <strong>{moeda.format(aberta.valorAbertura)}</strong></p>
          {aberta.pendenteSincronizacao && <p>Pendente de sincronização com o servidor.</p>}
          <button className="botao botao--secundario" type="button"
            onClick={() => setSelecionada(aberta)}>Ver meu caixa</button>
        </> : <form onSubmit={(evento) => void abrir(evento)}>
          <p>Nenhum caixa aberto para você.</p>
          <label>Valor inicial
            <input type="number" min="0" step="0.01" required value={valorAbertura}
              onChange={(evento) => setValorAbertura(evento.target.value)} />
          </label>
          <button className="botao" disabled={ocupado}>Abrir caixa</button>
        </form>}
      </section>

      <section className="caixa__cartao">
        <h3>Histórico do dia</h3>
        <label>Dia
          <input type="date" value={dia} onChange={(evento) => setDia(evento.target.value)} />
        </label>
        <p>{!navigator.onLine ? 'Sessões deste operador guardadas no dispositivo.'
          : identidade?.perfil === 'ADMIN' ? 'Sessões de todos os operadores.' : 'Suas sessões.'}</p>
        {historico.length === 0 ? <p>Nenhuma sessão neste dia.</p> : <ul className="caixa__lista">
          {historico.map((sessao) => <li key={sessao.id}>
            <button className="botao botao--secundario" type="button"
              onClick={() => setSelecionada(sessao)}>
              {dataHora.format(new Date(sessao.abertaEm))} · {sessao.status} · {moeda.format(sessao.valorAbertura)}
              {identidade?.perfil === 'ADMIN' && ` · operador ${sessao.usuarioId}`}
            </button>
          </li>)}
        </ul>}
      </section>

      {selecionada && <section className="caixa__cartao">
        <h3>Sessão selecionada</h3>
        <p>Status: <strong>{selecionada.status}</strong></p>
        {selecionada.pendenteSincronizacao && <p>Pendente de sincronização com o servidor.</p>}
        <p>Abertura: {dataHora.format(new Date(selecionada.abertaEm))}</p>
        <p>Valor inicial: {moeda.format(selecionada.valorAbertura)}</p>
        <p>Saldo esperado: <strong>{moeda.format(selecionada.valorFechamentoEsperado)}</strong></p>
        {selecionada.status === 'FECHADA' && <>
          <p>Valor contado: {moeda.format(selecionada.valorFechamentoContado ?? 0)}</p>
          <p>Diferença: {moeda.format(selecionada.diferenca ?? 0)}
            {' '}(positivo = falta, negativo = sobra)</p>
        </>}

        <h4>Movimentos</h4>
        {!selecionada.movimentos ? <p>Carregando extrato...</p>
          : selecionada.movimentos.length === 0 ? <p>Nenhum movimento nesta sessão.</p>
            : <ul className="caixa__movimentos">
              {selecionada.movimentos.map((movimento) => <li key={movimento.id}>
                <strong>{movimento.tipo}</strong> · {moeda.format(movimento.valor)}
                {' · '}{dataHora.format(new Date(movimento.criadoEm))}
                {movimento.motivo && <span> · {movimento.motivo}</span>}
                {movimento.vendaId && <span> · Venda {movimento.vendaId}</span>}
              </li>)}
            </ul>}

        {selecionada.status === 'ABERTA' && <form className="caixa__formulario"
          onSubmit={(evento) => void lancar(evento)}>
          <h4>Lançar movimento</h4>
          <label>Tipo
            <select value={tipoMovimento}
              onChange={(evento) => setTipoMovimento(evento.target.value as 'SANGRIA' | 'SUPRIMENTO')}>
              <option value="SANGRIA">Sangria</option>
              <option value="SUPRIMENTO">Suprimento</option>
            </select>
          </label>
          <label>Valor
            <input type="number" min="0" step="0.01" required value={valorMovimento}
              onChange={(evento) => setValorMovimento(evento.target.value)} />
          </label>
          <label>Motivo
            <input required value={motivo} onChange={(evento) => setMotivo(evento.target.value)} />
          </label>
          <button className="botao" disabled={ocupado}>Registrar {tipoMovimento.toLowerCase()}</button>
        </form>}
        {selecionada.status === 'ABERTA' && <form className="caixa__formulario"
          onSubmit={(evento) => void fechar(evento)}>
          <h4>Fechar caixa</h4>
          <p>Confira o dinheiro da gaveta contra o saldo esperado acima.</p>
          <label>Valor contado
            <input type="number" min="0" step="0.01" required value={valorContado}
              onChange={(evento) => setValorContado(evento.target.value)} />
          </label>
          {diferencaPrevista !== null && <p>Diferença prevista: {moeda.format(diferencaPrevista)}
            {' '}(positivo = falta, negativo = sobra)</p>}
          <button className="botao" disabled={ocupado}>Fechar e registrar diferença</button>
        </form>}
      </section>}
    </>}
  </section>
}
