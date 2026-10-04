import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { contas, type UsuarioDaConta } from '../api/contas'
import {
  relatorios, type ConferenciaDoCartao, type Faturamento, type FluxoDeCaixa, type MaisVendidos,
} from '../api/relatorios'
import type { FormaPagamento, StatusVenda } from '../api/vendas'
import { hojeNoBalcao } from '../dataDoBalcao'
import { erroDeCadastro } from './erroDeCadastro'

const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
// Venda fracionada é real: até três casas, e nenhuma quando a quantidade é inteira.
const numero = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 })
// A hora do balcão, a mesma do extrato da maquininha que a conferência acompanha.
const hora = new Intl.DateTimeFormat('pt-BR', { timeStyle: 'short', timeZone: 'America/Bahia' })

const TAMANHO_DO_RANKING = 10

// As quatro somam o faturamento sem filtro, porque o servidor soma a parcela, e não a venda.
const FORMAS: { forma: FormaPagamento; rotulo: string }[] = [
  { forma: 'DINHEIRO', rotulo: 'Dinheiro' },
  { forma: 'PIX', rotulo: 'Pix' },
  { forma: 'CARTAO', rotulo: 'Cartão' },
  { forma: 'FIADO', rotulo: 'Fiado' },
]

type FaturamentoDaForma = { forma: FormaPagamento; rotulo: string; faturamento: Faturamento }

// A venda cancelada lembra o estorno que deve haver na maquininha; a concluída é o caso comum.
const SITUACAO_NA_CONFERENCIA: Record<StatusVenda, string> = {
  CONCLUIDA: '',
  ABERTA: ' · venda ainda aberta',
  CANCELADA: ' · venda cancelada: confira o estorno na maquininha',
}

type Consulta = {
  faturamento: Faturamento
  porForma: FaturamentoDaForma[]
  maisVendidos: MaisVendidos
  fluxo: FluxoDeCaixa
  /** Só existe quando o período é um dia: a conferência lista em vez de somar. */
  conferencia: ConferenciaDoCartao | null
  /** Quem foi consultado, e não quem está escolhido agora no seletor. */
  operador: UsuarioDaConta | null
}

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}

function doPeriodo(inicio: string, fim: string): string {
  return inicio === fim ? `de ${mostrarData(inicio)}` : `de ${mostrarData(inicio)} a ${mostrarData(fim)}`
}

/**
 * Faturamento, mais vendidos e fluxo de caixa de um período, numa consulta só (RF21 a RF24), e a
 * conferência do cartão quando o período é um dia.
 *
 * Os cartões falam do mesmo período, então saem juntos ou nenhum sai: o erro de uma chamada vale
 * para a tela inteira. A quebra por forma é o faturamento pedido uma vez por forma, com o filtro do
 * servidor. O operador escolhido vale para o faturamento, o ranking e a conferência; o fluxo de
 * caixa é a gaveta da Conta inteira e não tem esse filtro. A conferência não é soma, e por isso é
 * de um dia só: a tela abre em hoje, e com um período maior o cartão pede um dia.
 */
export function TelaDeRelatorios() {
  const [inicio, setInicio] = useState(hojeNoBalcao)
  const [fim, setFim] = useState(hojeNoBalcao)
  const [operadorId, setOperadorId] = useState('')
  const [usuarios, setUsuarios] = useState<UsuarioDaConta[]>([])
  const [resultado, setResultado] = useState<Consulta | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [carregando, setCarregando] = useState(true)
  const pedidoAtual = useRef(0)

  const consultar = useCallback(async (de: string, ate: string, operador: UsuarioDaConta | null) => {
    const pedido = ++pedidoAtual.current
    setCarregando(true)
    setErro(null)
    setResultado(null)
    const id = operador?.id
    try {
      const [faturamento, porForma, maisVendidos, fluxo, conferencia] = await Promise.all([
        relatorios.faturamento(de, ate, { operadorId: id }),
        Promise.all(FORMAS.map(async ({ forma, rotulo }) => ({
          forma, rotulo, faturamento: await relatorios.faturamento(de, ate, { forma, operadorId: id }),
        }))),
        relatorios.maisVendidos(de, ate, TAMANHO_DO_RANKING, id),
        relatorios.fluxoDeCaixa(de, ate),
        de === ate ? relatorios.conferenciaDoCartao(de, id) : Promise.resolve(null),
      ])
      if (pedido === pedidoAtual.current) {
        setResultado({ faturamento, porForma, maisVendidos, fluxo, conferencia, operador })
      }
    } catch (falha) {
      if (pedido === pedidoAtual.current) setErro(erroDeCadastro(falha))
    } finally {
      if (pedido === pedidoAtual.current) setCarregando(false)
    }
  }, [])

  useEffect(() => {
    queueMicrotask(() => {
      const hoje = hojeNoBalcao()
      void consultar(hoje, hoje, null)
    })
  }, [consultar])

  useEffect(() => {
    // Sem a lista, o seletor não aparece e os relatórios seguem: o erro que importa é o da consulta.
    contas.usuarios().then(setUsuarios, () => undefined)
  }, [])

  const operadorEscolhido = usuarios.find((usuario) => usuario.id === operadorId) ?? null

  function consultarPeriodo(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    void consultar(inicio, fim, operadorEscolhido)
  }

  function consultarHoje() {
    const hoje = hojeNoBalcao()
    setInicio(hoje)
    setFim(hoje)
    void consultar(hoje, hoje, operadorEscolhido)
  }

  return <section className="relatorios">
    <h2 className="titulo">Relatórios</h2>
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
        {/* Com um usuário só, não há o que filtrar; o inativo fica, porque as vendas dele contam. */}
        {usuarios.length > 1 && <label>Operador
          <select value={operadorId} onChange={(evento) => setOperadorId(evento.target.value)}>
            <option value="">Todos</option>
            {usuarios.map((usuario) => <option key={usuario.id} value={usuario.id}>
              {usuario.ativo ? usuario.nome : `${usuario.nome} (inativo)`}
            </option>)}
          </select>
        </label>}
        <button className="botao" disabled={carregando}>Consultar período</button>
        <button className="botao botao--secundario" type="button" disabled={carregando}
          onClick={consultarHoje}>Hoje</button>
      </form>
    </section>

    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {carregando ? <p>Carregando relatórios...</p> : resultado && <div className="relatorios__cartoes" aria-live="polite">
      <CartaoDoFaturamento consulta={resultado} />
      <CartaoDosMaisVendidos consulta={resultado} />
      <CartaoDoFluxoDeCaixa consulta={resultado} />
      <CartaoDaConferencia consulta={resultado} usuarios={usuarios} />
    </div>}
  </section>
}

function CartaoDoFaturamento({ consulta }: { consulta: Consulta }) {
  const { faturamento, porForma, operador } = consulta
  return <section className="relatorios__cartao">
    <h3>Faturamento {doPeriodo(faturamento.inicio, faturamento.fim)}</h3>
    {operador && <p>Vendas de {operador.nome}</p>}
    <p className="relatorios__total">{moeda.format(faturamento.total)}</p>
    <p>{faturamento.quantidadeDeVendas} {faturamento.quantidadeDeVendas === 1 ? 'venda concluída' : 'vendas concluídas'}</p>
    {faturamento.quantidadeDeVendas === 0 && <p>Nenhuma venda concluída neste período.</p>}
    <h4>Por forma de pagamento</h4>
    <ul className="relatorios__linhas">
      {porForma.map(({ forma, rotulo, faturamento: daForma }) => <li key={forma}>
        <span>{rotulo}</span>
        <span>{moeda.format(daForma.total)} em {daForma.quantidadeDeVendas} {daForma.quantidadeDeVendas === 1 ? 'venda' : 'vendas'}</span>
      </li>)}
    </ul>
    <p className="relatorios__nota">A venda paga em mais de uma forma conta em cada uma delas.</p>
  </section>
}

function CartaoDosMaisVendidos({ consulta }: { consulta: Consulta }) {
  const { maisVendidos, operador } = consulta
  return <section className="relatorios__cartao">
    <h3>Mais vendidos {doPeriodo(maisVendidos.inicio, maisVendidos.fim)}</h3>
    {operador && <p>Vendas de {operador.nome}</p>}
    {maisVendidos.posicoes.length === 0
      ? <p>Nenhum produto vendido neste período.</p>
      : <ol className="relatorios__ranking">
        {maisVendidos.posicoes.map((posicao) => <li key={posicao.produtoId}>
          <span className="relatorios__linha">
            <span>{posicao.nome}</span>
            <span>{numero.format(posicao.quantidade)}{posicao.unidade ? ` ${posicao.unidade}` : ''}, {moeda.format(posicao.valor)}</span>
          </span>
        </li>)}
      </ol>}
    <p className="relatorios__nota">Os {TAMANHO_DO_RANKING} primeiros, pela quantidade que saiu.</p>
  </section>
}

function CartaoDaConferencia({ consulta, usuarios }: { consulta: Consulta; usuarios: UsuarioDaConta[] }) {
  const { conferencia, operador } = consulta
  if (!conferencia) {
    return <section className="relatorios__cartao">
      <h3>Conferência do cartão</h3>
      <p>A conferência é de um dia só, como o extrato da maquininha. Escolha o mesmo dia no início e no
        fim para ver os pagamentos em cartão com o NSU de cada um.</p>
    </section>
  }
  const { lancamentos } = conferencia
  const semNsu = lancamentos.filter((lancamento) => !lancamento.nsu).length
  // O nome vem da lista de usuários; sem ela, a linha mostra o começo do id de quem lançou.
  const nomeDe = (id: string) => usuarios.find((usuario) => usuario.id === id)?.nome ?? `operador ${id.slice(0, 8)}`
  return <section className="relatorios__cartao">
    <h3>Conferência do cartão {doPeriodo(conferencia.dia, conferencia.dia)}</h3>
    {operador && <p>Lançamentos de {operador.nome}</p>}
    {lancamentos.length === 0 ? <p>Nenhum pagamento em cartão neste dia.</p> : <>
      <p>{lancamentos.length} {lancamentos.length === 1 ? 'pagamento' : 'pagamentos'} em cartão
        {semNsu > 0 && `, ${semNsu} sem NSU`}</p>
      <ul className="relatorios__conferencia">
        {lancamentos.map((lancamento) => <li key={lancamento.id}>
          <span><strong>{hora.format(new Date(lancamento.lancadoEm))}</strong> · {moeda.format(lancamento.valor)}
            {' · '}{lancamento.nsu ? `NSU ${lancamento.nsu}` : <span className="relatorios__sem-nsu">sem NSU</span>}</span>
          <span>{nomeDe(lancamento.operadorId)}
            {' · '}{lancamento.origem === 'RECEBIMENTO' ? 'fiado recebido' : 'venda'} {lancamento.vendaId.slice(0, 8)}
            {SITUACAO_NA_CONFERENCIA[lancamento.situacaoDaVenda]}</span>
        </li>)}
      </ul>
    </>}
    <p className="relatorios__nota">Cada pagamento fica no dia e na hora em que foi lançado, como no
      extrato da maquininha, mesmo de venda aberta ou cancelada depois.</p>
  </section>
}

function CartaoDoFluxoDeCaixa({ consulta }: { consulta: Consulta }) {
  const { fluxo, operador } = consulta
  return <section className="relatorios__cartao">
    <h3>Fluxo de caixa {doPeriodo(fluxo.inicio, fluxo.fim)}</h3>
    <ul className="relatorios__linhas">
      <li className="relatorios__soma"><span>Entradas</span><span>{moeda.format(fluxo.entradas)}</span></li>
      <li><span>Vendas em dinheiro</span><span>{moeda.format(fluxo.vendas)}</span></li>
      <li><span>Fiado recebido em dinheiro</span><span>{moeda.format(fluxo.recebimentos)}</span></li>
      <li><span>Suprimentos</span><span>{moeda.format(fluxo.suprimentos)}</span></li>
      <li className="relatorios__soma"><span>Saídas</span><span>{moeda.format(fluxo.saidas)}</span></li>
      <li><span>Sangrias</span><span>{moeda.format(fluxo.sangrias)}</span></li>
      <li><span>Estornos de vendas canceladas</span><span>{moeda.format(fluxo.estornos)}</span></li>
      <li className="relatorios__soma"><span>Saldo</span><span>{moeda.format(fluxo.saldo)}</span></li>
    </ul>
    <p className="relatorios__nota">Só o dinheiro em espécie que passou pela gaveta; Pix e cartão estão no
      faturamento. O valor de abertura não entra.</p>
    {operador && <p className="relatorios__nota">O fluxo de caixa é da gaveta da Conta inteira: o filtro de
      operador não vale para ele.</p>}
  </section>
}
