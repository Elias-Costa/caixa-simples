import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { contas, type UsuarioDaConta } from '../api/contas'
import {
  relatorios, type ConferenciaDoCartao, type ConferenciaDoPix, type Faturamento, type FluxoDeCaixa,
  type MaisVendidos,
} from '../api/relatorios'
import type { FormaPagamento, StatusVenda } from '../api/vendas'
import { hojeNoBalcao } from '../dataDoBalcao'
import { hora, moeda, quantidade as numero } from '../formatos'
import { nomesDosClientesNoAparelho } from './clientesNoAparelho'
import { erroDeCadastro } from './erroDeCadastro'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'

const TAMANHO_DO_RANKING = 10

// As quatro somam o faturamento sem filtro, porque o servidor soma a parcela, e não a venda.
const FORMAS: { forma: FormaPagamento; rotulo: string }[] = [
  { forma: 'DINHEIRO', rotulo: 'Dinheiro' },
  { forma: 'PIX', rotulo: 'Pix' },
  { forma: 'CARTAO', rotulo: 'Cartão' },
  { forma: 'FIADO', rotulo: 'Fiado' },
]

type FaturamentoDaForma = { forma: FormaPagamento; rotulo: string; faturamento: Faturamento }

/** O dia AAAA-MM-DD contado para trás no calendário, sem passar pelo fuso do aparelho. */
function diasAntes(dia: string, quantos: number): string {
  const [ano, mes, data] = dia.split('-').map(Number)
  return new Date(Date.UTC(ano, mes - 1, data - quantos)).toISOString().slice(0, 10)
}

// Cada atalho parte do dia do balcão. Sete dias são hoje e os seis anteriores, e não a semana do
// calendário: a pergunta é como foi a última semana, em qualquer dia em que ela for feita.
const ATALHOS: { rotulo: string; periodo: (hoje: string) => [string, string] }[] = [
  { rotulo: 'Hoje', periodo: (hoje) => [hoje, hoje] },
  { rotulo: 'Ontem', periodo: (hoje) => [diasAntes(hoje, 1), diasAntes(hoje, 1)] },
  { rotulo: '7 dias', periodo: (hoje) => [diasAntes(hoje, 6), hoje] },
  { rotulo: 'Este mês', periodo: (hoje) => [`${hoje.slice(0, 8)}01`, hoje] },
]

type Marca = { texto: string; alerta?: boolean }

// A venda cancelada pede uma ação do dono, o estorno ou a devolução; a aberta só avisa.
const MARCA_DA_SITUACAO: Record<StatusVenda, Marca | null> = {
  CONCLUIDA: null,
  ABERTA: { texto: 'Aberta' },
  CANCELADA: { texto: 'Cancelada', alerta: true },
}

type Consulta = {
  faturamento: Faturamento
  porForma: FaturamentoDaForma[]
  maisVendidos: MaisVendidos
  fluxo: FluxoDeCaixa
  /** Só existe quando o período é um dia: a conferência lista em vez de somar. */
  conferencia: ConferenciaDoCartao | null
  /** A do Pix, também só num dia, com o nome do cliente pelo cadastro do aparelho. */
  conferenciaDoPix: ConferenciaDoPix | null
  nomeDoCliente: (clienteId: string) => string
  /** Quem foi consultado, e não quem está escolhido agora no seletor. */
  operador: UsuarioDaConta | null
}

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}

function mostrarPeriodo(inicio: string, fim: string): string {
  return inicio === fim ? mostrarData(inicio) : `${mostrarData(inicio)} a ${mostrarData(fim)}`
}

/** A soma feita em centavos: somar os reais direto acumula o erro do ponto flutuante. */
function somar(valores: number[]): number {
  return valores.reduce((centavos, valor) => centavos + Math.round(valor * 100), 0) / 100
}

/**
 * Faturamento, mais vendidos e fluxo de caixa de um período, numa consulta só (RF21 a RF24), e as
 * conferências do cartão e do Pix quando o período é um dia.
 *
 * Os cartões falam do mesmo período, então saem juntos ou nenhum sai: o erro de uma chamada vale
 * para a tela inteira. A quebra por forma é o faturamento pedido uma vez por forma, com o filtro do
 * servidor. O operador escolhido vale para o faturamento, o ranking e a conferência; o fluxo de
 * caixa é a gaveta da Conta inteira e não tem esse filtro. A conferência não é soma, e por isso é
 * de um dia só: a tela abre em hoje, e com um período maior uma linha diz que ela pede um dia.
 *
 * Os resumos vêm em cima, curtos e de altura parecida; as conferências, que têm uma linha por
 * pagamento, vêm embaixo, lado a lado e cada uma rolando por dentro, para a do Pix não ir parar
 * depois de todos os pagamentos em cartão.
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
    // O resultado anterior fica à vista, esmaecido, até o novo chegar: a página não encolhe e volta.
    const id = operador?.id
    try {
      const [faturamento, porForma, maisVendidos, fluxo, conferencia, conferenciaDoPix, nomeDoCliente] = await Promise.all([
        relatorios.faturamento(de, ate, { operadorId: id }),
        Promise.all(FORMAS.map(async ({ forma, rotulo }) => ({
          forma, rotulo, faturamento: await relatorios.faturamento(de, ate, { forma, operadorId: id }),
        }))),
        // Pelo valor: pela quantidade, o que se vende a peso nunca chegaria aos primeiros.
        relatorios.maisVendidos(de, ate, TAMANHO_DO_RANKING, id, 'VALOR'),
        relatorios.fluxoDeCaixa(de, ate),
        de === ate ? relatorios.conferenciaDoCartao(de, id) : Promise.resolve(null),
        de === ate ? relatorios.conferenciaDoPix(de, id) : Promise.resolve(null),
        de === ate ? nomesDosClientesNoAparelho() : Promise.resolve(() => ''),
      ])
      if (pedido === pedidoAtual.current) {
        setResultado({
          faturamento, porForma, maisVendidos, fluxo, conferencia, conferenciaDoPix, nomeDoCliente, operador,
        })
      }
    } catch (falha) {
      if (pedido === pedidoAtual.current) {
        // O resultado anterior é de outro período: ao lado do erro, ele passaria pelo que foi pedido.
        setResultado(null)
        setErro(erroDeCadastro(falha))
      }
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
  // O nome vem da lista de usuários; sem ela, a linha mostra o começo do id de quem lançou.
  const nomeDe = (id: string) => usuarios.find((usuario) => usuario.id === id)?.nome ?? `operador ${id.slice(0, 8)}`

  function consultarPeriodo(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    void consultar(inicio, fim, operadorEscolhido)
  }

  function consultarAtalho(periodo: (hoje: string) => [string, string]) {
    const [de, ate] = periodo(hojeNoBalcao())
    setInicio(de)
    setFim(ate)
    void consultar(de, ate, operadorEscolhido)
  }

  return <section className="relatorios">
    <TituloComAjuda titulo="Relatórios" ajuda={AJUDA.relatorios} />
    <section className="relatorios__cartao">
      <div className="relatorios__atalhos">
        {ATALHOS.map(({ rotulo, periodo }) => <button key={rotulo} className="botao botao--secundario" type="button"
          disabled={carregando} onClick={() => consultarAtalho(periodo)}>{rotulo}</button>)}
      </div>
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
        <button className="botao" disabled={carregando}>{carregando ? 'Consultando' : 'Consultar período'}</button>
      </form>
    </section>

    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {!resultado && carregando && <p>Carregando relatórios...</p>}
    {resultado && <div className="relatorios__resultado" aria-live="polite" aria-busy={carregando}>
      <p className="relatorios__periodo">
        {mostrarPeriodo(resultado.faturamento.inicio, resultado.faturamento.fim)}
        {' · '}{resultado.operador ? resultado.operador.nome : 'todos os operadores'}
      </p>
      <div className="relatorios__resumos">
        <CartaoDoFaturamento consulta={resultado} />
        <CartaoDoFluxoDeCaixa consulta={resultado} />
        <CartaoDosMaisVendidos consulta={resultado} />
      </div>
      {resultado.conferencia && resultado.conferenciaDoPix
        ? <div className="relatorios__conferencias">
          <CartaoDaConferencia conferencia={resultado.conferencia} nomeDe={nomeDe} />
          <CartaoDaConferenciaDoPix conferencia={resultado.conferenciaDoPix} nomeDe={nomeDe}
            nomeDoCliente={resultado.nomeDoCliente} />
        </div>
        : <p className="relatorios__sem-conferencia">As conferências do cartão e do Pix são de um dia só,
          como os extratos: escolha o mesmo dia no início e no fim.</p>}
    </div>}
  </section>
}

function CartaoDoFaturamento({ consulta }: { consulta: Consulta }) {
  const { faturamento, porForma } = consulta
  const vendas = faturamento.quantidadeDeVendas
  return <section className="relatorios__cartao">
    <h3>Faturamento</h3>
    <div className="relatorios__numeros">
      <p><span className="relatorios__rotulo">Total</span>
        <strong className="relatorios__total">{moeda.format(faturamento.total)}</strong></p>
      {/* Sem venda não há média, e um zero passaria por uma média de verdade. */}
      {vendas > 0 && <p><span className="relatorios__rotulo">Ticket médio</span>
        <strong className="relatorios__medio">{moeda.format(faturamento.total / vendas)}</strong></p>}
    </div>
    <p>{vendas} {vendas === 1 ? 'venda concluída' : 'vendas concluídas'}</p>
    {vendas === 0 && <p>Nenhuma venda concluída neste período.</p>}
    <table className="tabela">
      <caption>Por forma de pagamento</caption>
      <thead>
        <tr><th scope="col">Forma</th><th scope="col" className="tabela__numero">Valor</th>
          <th scope="col" className="tabela__numero">Vendas</th></tr>
      </thead>
      <tbody>
        {porForma.map(({ forma, rotulo, faturamento: daForma }) => <tr key={forma}>
          <th scope="row">{rotulo}</th>
          <td className="tabela__numero">{moeda.format(daForma.total)}</td>
          <td className="tabela__numero">{daForma.quantidadeDeVendas}</td>
        </tr>)}
      </tbody>
    </table>
    <p className="relatorios__nota">A venda paga em mais de uma forma conta em cada uma delas.</p>
  </section>
}

function CartaoDosMaisVendidos({ consulta }: { consulta: Consulta }) {
  const { posicoes } = consulta.maisVendidos
  const idDoTitulo = useId()
  return <section className="relatorios__cartao relatorios__cartao--largo">
    <h3 id={idDoTitulo}>Mais vendidos</h3>
    {posicoes.length === 0
      ? <p>Nenhum produto vendido neste período.</p>
      : <table className="tabela" aria-labelledby={idDoTitulo}>
        <thead>
          <tr><th scope="col">Produto</th><th scope="col" className="tabela__numero">Quantidade</th>
            <th scope="col" className="tabela__numero">Valor</th></tr>
        </thead>
        <tbody>
          {posicoes.map((posicao) => <tr key={posicao.produtoId}>
            <th scope="row">{posicao.nome}</th>
            {/* A unidade dá sentido à quantidade; o serviço sem unidade mostra só o número. */}
            <td className="tabela__numero">{numero.format(posicao.quantidade)}{posicao.unidade ? ` ${posicao.unidade}` : ''}</td>
            <td className="tabela__numero">{moeda.format(posicao.valor)}</td>
          </tr>)}
        </tbody>
      </table>}
    <p className="relatorios__nota">Os dez que mais faturaram no período.</p>
  </section>
}

function Marcas({ marcas }: { marcas: (Marca | null)[] }) {
  const presentes = marcas.filter((marca): marca is Marca => marca !== null)
  if (presentes.length === 0) return null
  return <span className="marcas">
    {presentes.map(({ texto, alerta }) => <span key={texto}
      className={alerta ? 'marca marca--alerta' : 'marca'}>{texto}</span>)}
  </span>
}

const MARCA_DO_FIADO: Marca = { texto: 'Fiado' }

function CartaoDaConferencia({ conferencia, nomeDe }: {
  conferencia: ConferenciaDoCartao
  nomeDe: (id: string) => string
}) {
  const [soSemNsu, setSoSemNsu] = useState(false)
  const idDoTitulo = useId()
  const { lancamentos } = conferencia
  const semNsu = lancamentos.filter((lancamento) => !lancamento.nsu)
  // O filtro só vale com algum sem NSU; num dia em que todos têm, o botão some e a lista é a inteira.
  const linhas = soSemNsu && semNsu.length > 0 ? semNsu : lancamentos
  return <section className="relatorios__cartao">
    <h3 id={idDoTitulo}>Conferência do cartão</h3>
    {lancamentos.length === 0 ? <p>Nenhum pagamento em cartão neste dia.</p> : <>
      <p className="relatorios__resumo"><strong>{moeda.format(somar(lancamentos.map((lancamento) => lancamento.valor)))}</strong>
        {' '}em {lancamentos.length} {lancamentos.length === 1 ? 'pagamento' : 'pagamentos'}
        {semNsu.length > 0 && `, ${semNsu.length} sem NSU`}.</p>
      <p className="relatorios__nota">Este total não é o faturamento em cartão: aqui entram também o fiado
        recebido em cartão e a venda cancelada ou ainda aberta, e a venda dividida aparece uma vez por
        parcela. Cada pagamento fica na hora em que foi lançado, como no extrato da maquininha.</p>
      {semNsu.length > 0 && <button type="button" className="botao botao--secundario relatorios__filtro"
        aria-pressed={soSemNsu} onClick={() => setSoSemNsu((antes) => !antes)}>Só sem NSU</button>}
      {/* A região rola por dentro e recebe o foco, para quem usa o teclado também poder rolar. */}
      <div className="relatorios__rolagem" role="region" aria-labelledby={idDoTitulo} tabIndex={0}>
        <table className="tabela" aria-labelledby={idDoTitulo}>
          <thead>
            <tr><th scope="col">Hora</th><th scope="col" className="tabela__numero">Valor</th>
              <th scope="col">NSU</th><th scope="col">Operador</th></tr>
          </thead>
          <tbody>
            {linhas.map((lancamento) => <tr key={lancamento.id}>
              <td className="tabela__hora">{hora.format(new Date(lancamento.lancadoEm))}</td>
              <td className="tabela__numero">{moeda.format(lancamento.valor)}</td>
              <td className="relatorios__nsu">{lancamento.nsu ?? <span className="relatorios__sem-nsu">Sem NSU</span>}</td>
              <td>{nomeDe(lancamento.operadorId)}
                <Marcas marcas={[
                  lancamento.origem === 'RECEBIMENTO' ? MARCA_DO_FIADO : null,
                  MARCA_DA_SITUACAO[lancamento.situacaoDaVenda],
                ]} /></td>
            </tr>)}
          </tbody>
        </table>
      </div>
      <p className="relatorios__nota">Fiado é o recebimento de um fiado. Na venda cancelada, confira o
        estorno na maquininha.</p>
    </>}
  </section>
}

function CartaoDaConferenciaDoPix({ conferencia, nomeDe, nomeDoCliente }: {
  conferencia: ConferenciaDoPix
  nomeDe: (id: string) => string
  nomeDoCliente: (clienteId: string) => string
}) {
  const idDoTitulo = useId()
  const { lancamentos } = conferencia
  // A cobrança que aguarda confirmação ainda não está no extrato do banco: fica fora do total.
  const confirmados = lancamentos.filter((lancamento) => !lancamento.aguardandoConfirmacao)
  const aguardando = lancamentos.filter((lancamento) => lancamento.aguardandoConfirmacao)
  return <section className="relatorios__cartao">
    <h3 id={idDoTitulo}>Conferência do Pix</h3>
    {lancamentos.length === 0 ? <p>Nenhum Pix neste dia.</p> : <>
      <p className="relatorios__resumo"><strong>{moeda.format(somar(confirmados.map((lancamento) => lancamento.valor)))}</strong>
        {' '}em {confirmados.length} Pix.</p>
      {aguardando.length > 0 && <p className="relatorios__resumo">{aguardando.length} aguardando confirmação, de
        {' '}{moeda.format(somar(aguardando.map((lancamento) => lancamento.valor)))}, fora do total.</p>}
      <div className="relatorios__rolagem" role="region" aria-labelledby={idDoTitulo} tabIndex={0}>
        <table className="tabela" aria-labelledby={idDoTitulo}>
          <thead>
            <tr><th scope="col">Hora</th><th scope="col" className="tabela__numero">Valor</th>
              <th scope="col">Operador</th></tr>
          </thead>
          <tbody>
            {lancamentos.map((lancamento) => <tr key={lancamento.id}>
              <td className="tabela__hora">{hora.format(new Date(lancamento.lancadoEm))}</td>
              <td className="tabela__numero">{moeda.format(lancamento.valor)}</td>
              <td>{nomeDe(lancamento.operadorId)}
                {lancamento.clienteId && <span className="relatorios__cliente">Cliente: {nomeDoCliente(lancamento.clienteId)}</span>}
                <Marcas marcas={[
                  lancamento.integrado ? { texto: 'Integrado' } : null,
                  lancamento.aguardandoConfirmacao ? { texto: 'Aguardando', alerta: true } : null,
                  lancamento.origem === 'RECEBIMENTO' ? MARCA_DO_FIADO : null,
                  MARCA_DA_SITUACAO[lancamento.situacaoDaVenda],
                ]} /></td>
            </tr>)}
          </tbody>
        </table>
      </div>
      <p className="relatorios__nota">O Pix lançado à mão não tem identificador: confira pelo valor e pela
        hora. Aguardando é a cobrança integrada ainda não paga; se o banco já tem o crédito, confira a
        conciliação na tela de venda. Fiado é o recebimento de um fiado. Na venda cancelada, confira a
        devolução ao cliente.</p>
    </>}
  </section>
}

function CartaoDoFluxoDeCaixa({ consulta }: { consulta: Consulta }) {
  const { fluxo, operador } = consulta
  return <section className="relatorios__cartao">
    <h3>Fluxo de caixa</h3>
    {/* No alto, e não no rodapé: ao lado do faturamento filtrado, o número da Conta inteira engana. */}
    {operador && <p className="relatorios__aviso">O fluxo de caixa é da gaveta da Conta inteira: o filtro de
      operador não vale para ele.</p>}
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
  </section>
}
