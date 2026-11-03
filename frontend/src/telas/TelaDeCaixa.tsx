import { Fragment, useCallback, useEffect, useId, useState, type FormEvent } from 'react'
import { caixa as caixaNoServidor, type MovimentoCaixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { contas } from '../api/contas'
import { intencaoOnline } from '../api/intencaoOnline'
import type {
  Comprovante as DadosDoComprovante, FormaPagamento, ResumoDaVenda, ValorDaForma, Venda,
} from '../api/vendas'
import { abertaAntesDeHoje, diaNoBalcao, hojeNoBalcao } from '../dataDoBalcao'
import { dataHora, diaEMes, hora, horaNoDia, lerDinheiro, moeda } from '../formatos'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { criarVendaLocal } from '../offline/vendaLocal'
import { useSincronizacao } from '../shell/sincronizacao'
import { useOnline } from '../shell/useOnline'
import { useSessao } from '../sessao/useSessao'
import { CampoDeDinheiro } from './CampoDeDinheiro'
import { nomesDosClientesNoAparelho } from './clientesNoAparelho'
import { Comprovante } from './Comprovante'
import { erroDeCadastro } from './erroDeCadastro'
import { Mensagem } from './Mensagem'
import { PrimeirosPassos } from './PrimeirosPassos'
import { ROTULO_DA_FORMA, ROTULO_DA_VENDA, ROTULO_DO_CAIXA, ROTULO_DO_MOVIMENTO } from './rotulos'
import { useConfirmacao, type Pergunta } from './useConfirmacao'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'
import { useTourDoCaixa } from './tourDoCaixa'

const caixa = criarCaixaLocal()
// A mesma leitura da tela Vender: sem rede, as vendas da sessão guardadas neste aparelho.
const vendas = criarVendaLocal()
// Fora do componente, para ser a mesma função em toda renderização: o tour a usa como dependência.
const vendasDoCaixa = (sessaoId: string) => vendas.daSessao(sessaoId)

/** A ordem em que as formas aparecem no vendido, a mesma do comprovante e dos relatórios. */
const FORMAS: FormaPagamento[] = ['DINHEIRO', 'PIX', 'CARTAO', 'FIADO']
const listaDeFormas = new Intl.ListFormat('pt-BR', { style: 'long', type: 'conjunction' })
/** A forma no meio de uma frase: o Pix é nome próprio e continua com maiúscula. */
const EM_FORMA: Record<FormaPagamento, string> = {
  DINHEIRO: 'em dinheiro', PIX: 'em Pix', CARTAO: 'em cartão', FIADO: 'no fiado',
}
/** Sangria e estorno tiram dinheiro da gaveta; venda, suprimento e recebimento de fiado põem. */
const SAI_DA_GAVETA: ReadonlySet<MovimentoCaixa['tipo']> = new Set(['SANGRIA', 'ESTORNO'])

/** O cartão em que a resposta de uma ação aparece: o mesmo em que ela foi tocada. */
type LugarDaMensagem = 'tela' | 'abertura' | 'sessao' | 'movimento' | 'fechamento' | 'vendas'
type MensagemNaTela = { tipo: 'erro' | 'sucesso'; texto: string; lugar: LugarDaMensagem }

/** A lista guarda de qual sessão é: trocada a sessão, a lista anterior some antes da nova chegar. */
type VendasDaSessao = { sessaoId: string; lista: ResumoDaVenda[] }
type VendaEmDetalhe = { venda: Venda; cliente: string | null }
/** A segunda via aberta no detalhe, com o instante da reimpressão, que vai no papel. */
type SegundaVia = { comprovante: DadosDoComprovante; em: Date }
/** As vendas abertas do caixa e o dinheiro já recebido nelas, que o saldo esperado não conta. */
type Abertas = { quantas: number; dinheiro: number }

/**
 * A diferença do fechamento em palavras, falta ou sobra, no lugar do sinal, que é convenção do
 * sistema e não do balcão. A mesma frase serve à pergunta antes de fechar, à prévia e à sessão fechada.
 */
function diferencaEmPalavras(diferenca: number): string {
  return diferenca > 0 ? `falta de ${moeda.format(diferenca)}`
    : diferenca < 0 ? `sobra de ${moeda.format(-diferenca)}`
      : 'sem diferença'
}

function comMaiuscula(texto: string): string {
  return texto.charAt(0).toUpperCase() + texto.slice(1)
}

/** Em centavos, a soma de dezenas de linhas não acumula o erro do ponto flutuante. */
function centavos(valor: number): number {
  return Math.round(valor * 100)
}

/**
 * A pergunta antes de fechar diz a diferença em palavras: o fechamento não se refaz, e é a última
 * chance de recontar a gaveta. Sem o saldo esperado lido, diz o valor contado. Com venda aberta,
 * lembra que ela não conclui depois e que o dinheiro dela está fora do esperado.
 */
function perguntaDoFechamento(contado: number, diferenca: number | null, abertas: Abertas | null): Pergunta {
  const como = diferenca === null ? `com ${moeda.format(contado)} contados`
    : diferenca === 0 ? 'sem diferença'
      : `com ${diferencaEmPalavras(diferenca)}`
  return {
    titulo: `Fechar o caixa ${como}?`,
    texto: (abertas ? `${abertasNaPergunta(abertas)} ` : '')
      + 'O fechamento não pode ser refeito. Se o valor não parece certo, volte e conte a gaveta de novo.',
    acao: 'Fechar caixa',
  }
}

function abertasNaPergunta({ quantas, dinheiro }: Abertas): string {
  const quais = quantas === 1 ? 'uma venda aberta, que só pode ser cancelada'
    : `${quantas} vendas abertas, que só podem ser canceladas`
  const fora = dinheiro > 0
    ? `, e ${moeda.format(dinheiro)} em dinheiro recebido ${quantas === 1 ? 'nela' : 'nelas'} fica fora do saldo esperado`
    : ''
  return `Este caixa tem ${quais} depois do fechamento${fora}.`
}

/**
 * O aviso do fechamento com venda aberta. O servidor não conclui venda de caixa fechado, e o dinheiro
 * de parcela só entra no saldo esperado quando a venda conclui: na contagem, ele apareceria como sobra.
 */
function avisoDasAbertas({ quantas, dinheiro }: Abertas): string {
  const inicio = quantas === 1
    ? 'Este caixa tem uma venda aberta. Depois do fechamento ela não conclui mais, só pode ser cancelada.'
    : `Este caixa tem ${quantas} vendas abertas. Depois do fechamento elas não concluem mais, só podem ser canceladas.`
  if (dinheiro === 0) return inicio
  return `${inicio} O que já foi recebido ${quantas === 1 ? 'nela' : 'nelas'} não está no saldo esperado: `
    + `os ${moeda.format(dinheiro)} em dinheiro, se estiverem na gaveta, aparecem como sobra.`
}

/** O que a venda aberta já recebeu, por forma. O fiado fica de fora: é promessa, não entrou dinheiro. */
function recebidoNaAberta(venda: ResumoDaVenda): ValorDaForma[] | undefined {
  return venda.formas?.filter((forma) => forma.forma !== 'FIADO')
}

/** Sem as formas, de um servidor anterior a elas, a tela diz que não sabe, e não que é zero. */
function recebidoEmPalavras(recebido: ValorDaForma[] | undefined): string {
  if (!recebido) return 'Não informado'
  if (recebido.length === 0) return moeda.format(0)
  return listaDeFormas.format(recebido.map((forma) => `${moeda.format(forma.valor)} ${EM_FORMA[forma.forma]}`))
}

type Vendido = {
  porForma: ValorDaForma[]
  /** As concluídas que chegaram sem as formas: entram no total e numa linha à parte. */
  semForma: number
  total: number
  vendas: number
}

/**
 * O vendido no caixa, por forma: só a venda concluída conta, como no faturamento. A soma das formas de
 * uma concluída é o total dela; a que chega sem as formas não vira zero numa forma que não foi a dela.
 */
function vendidoNoCaixa(lista: ResumoDaVenda[]): Vendido {
  const porForma = new Map<FormaPagamento, number>()
  let semForma = 0
  let total = 0
  let quantas = 0
  for (const venda of lista) {
    if (venda.status !== 'CONCLUIDA') continue
    quantas += 1
    total += centavos(venda.total)
    if (!venda.formas) {
      semForma += centavos(venda.total)
      continue
    }
    for (const { forma, valor } of venda.formas) {
      porForma.set(forma, (porForma.get(forma) ?? 0) + centavos(valor))
    }
  }
  return {
    porForma: FORMAS.filter((forma) => porForma.has(forma))
      .map((forma) => ({ forma, valor: (porForma.get(forma) ?? 0) / 100 })),
    semForma: semForma / 100, total: total / 100, vendas: quantas,
  }
}

function foraDoTotal(abertas: number, canceladas: number): string | null {
  const contar = (quantas: number, uma: string, varias: string) => quantas === 1 ? uma : `${quantas} ${varias}`
  if (abertas > 0 && canceladas > 0) {
    return `Fora do total: ${contar(abertas, 'uma venda aberta', 'vendas abertas')} e `
      + `${contar(canceladas, 'uma cancelada', 'canceladas')}.`
  }
  if (abertas > 0) return `Fora do total: ${contar(abertas, 'uma venda aberta', 'vendas abertas')}.`
  if (canceladas > 0) return `Fora do total: ${contar(canceladas, 'uma venda cancelada', 'vendas canceladas')}.`
  return null
}

/**
 * Cada movimento com o sinal de quem sai da gaveta e o saldo depois dele, a partir do valor inicial,
 * na ordem do lançamento: a última linha bate com o saldo esperado.
 */
function extratoComSaldo(sessao: SessaoCaixa): { movimento: MovimentoCaixa; valor: number; saldo: number }[] {
  let saldo = centavos(sessao.valorAbertura)
  return (sessao.movimentos ?? []).map((movimento) => {
    const valor = SAI_DA_GAVETA.has(movimento.tipo) ? -centavos(movimento.valor) : centavos(movimento.valor)
    saldo += valor
    return { movimento, valor: valor / 100, saldo: saldo / 100 }
  })
}

/**
 * Um caixa no histórico do dia: quando abriu e fechou, o valor inicial com o rótulo, para não se ler
 * como vendido, e a diferença do fechamento, que é o que se procura nele.
 */
function linhaDoHistorico(sessao: SessaoCaixa): string {
  const abertura = new Date(sessao.abertaEm)
  const partes = [`Aberto às ${hora.format(abertura)}`]
  if (sessao.status === 'FECHADA') {
    const fechamento = sessao.fechadaEm ? new Date(sessao.fechadaEm) : null
    partes.push(!fechamento ? 'Fechado'
      : diaNoBalcao(fechamento) === diaNoBalcao(abertura) ? `Fechado às ${hora.format(fechamento)}`
        : `Fechado em ${diaEMes.format(fechamento)} às ${hora.format(fechamento)}`)
  }
  partes.push(`Valor inicial ${moeda.format(sessao.valorAbertura)}`)
  if (sessao.status === 'FECHADA' && sessao.diferenca !== null && !sessao.fechamentoSemValores
    && !sessao.saldoDesatualizado) {
    partes.push(comMaiuscula(diferencaEmPalavras(sessao.diferenca)))
  }
  return partes.join(' · ')
}

/**
 * Uma venda da sessão como foi: itens, descontos, pagamentos e o cliente, sem nada para alterar. A
 * venda concluída reimprime o comprovante, como segunda via; a cancelada não tem comprovante.
 */
function DetalheDaVenda({ venda, cliente, aoFechar, aoPedirSegundaVia }: VendaEmDetalhe & {
  aoFechar: () => void
  aoPedirSegundaVia: () => void
}) {
  return <section className="caixa__formulario" aria-label="Detalhe da venda">
    <h4>Venda de {dataHora.format(new Date(venda.criadoEm))} · {ROTULO_DA_VENDA[venda.status]}</h4>
    {venda.pendenteSincronizacao && <p>Pendente de sincronização com o servidor.</p>}
    {cliente && <p>Cliente: {cliente}</p>}
    {venda.itens.length === 0 ? <p>Nenhum item lançado.</p> : <ul className="caixa__movimentos">
      {venda.itens.map((item) => <li key={item.id}>
        {item.quantidade} × {item.nome} · {moeda.format(item.subtotal)}
        {item.desconto > 0 && ` (desconto ${moeda.format(item.desconto)})`}
      </li>)}
    </ul>}
    {venda.descontoDaVenda > 0 && <p>Desconto da venda: {moeda.format(venda.descontoDaVenda)}</p>}
    <p><strong>Total: {moeda.format(venda.total)}</strong></p>
    {venda.parcelas.length === 0 ? <p>Nenhum pagamento lançado.</p> : <ul className="caixa__movimentos">
      {venda.parcelas.map((parcela) => <li key={parcela.id}>
        {ROTULO_DA_FORMA[parcela.forma]}: {moeda.format(parcela.valor)}
        {parcela.troco > 0 && ` · troco ${moeda.format(parcela.troco)}`}
        {parcela.nsu && ` · NSU ${parcela.nsu}`}
        {parcela.pix && parcela.status === 'PENDENTE' && ' · aguardando confirmação'}
        {parcela.status === 'RECUSADO' && ' · recusada'}
      </li>)}
    </ul>}
    <div className="comprovante__acoes">
      {venda.status === 'CONCLUIDA' && <button className="botao" type="button"
        onClick={aoPedirSegundaVia}>Segunda via do comprovante</button>}
      <button className="botao botao--secundario" type="button" onClick={aoFechar}>Fechar detalhe</button>
    </div>
  </section>
}

/** A situação da venda na tabela: a concluída em texto; a aberta e a cancelada com a marca, que salta. */
function SituacaoDaVenda({ status }: { status: ResumoDaVenda['status'] }) {
  if (status === 'CONCLUIDA') return <>{ROTULO_DA_VENDA[status]}</>
  return <span className={status === 'ABERTA' ? 'marca marca--alerta' : 'marca'}>{ROTULO_DA_VENDA[status]}</span>
}

/** O valor do campo, ou o erro que diz qual campo corrigir, perto do botão que foi tocado. */
function dinheiroDoCampo(texto: string, nome: string): number {
  const valor = lerDinheiro(texto)
  if (valor === null) throw new Error(`Informe ${nome} com até duas casas decimais.`)
  return valor
}

/** O botão Ver de uma venda, que recebe o foco de volta quando o detalhe dela fecha. */
function idDoVer(vendaId: string): string {
  return `caixa-ver-venda-${vendaId}`
}

export function TelaDeCaixa() {
  const { identidade } = useSessao()
  const { rodada } = useSincronizacao()
  const online = useOnline()
  const ids = useId()
  const administrador = identidade?.perfil === 'ADMIN'
  const [aberta, setAberta] = useState<SessaoCaixa | undefined>()
  const [selecionada, setSelecionada] = useState<SessaoCaixa | undefined>()
  const [historico, setHistorico] = useState<SessaoCaixa[]>([])
  const [nomes, setNomes] = useState<Map<string, string>>(() => new Map())
  const [dia, setDia] = useState(hojeNoBalcao)
  // Começa vazio: com 0,00 dentro, o toque põe o cursor no fim e 150 vira 0,00150. O campo é
  // obrigatório, e abrir sem troco é digitar 0, para o esquecimento não abrir a gaveta em zero e
  // virar sobra no fechamento.
  const [valorAbertura, setValorAbertura] = useState('')
  const [tipoMovimento, setTipoMovimento] = useState<'SANGRIA' | 'SUPRIMENTO'>('SANGRIA')
  const [valorMovimento, setValorMovimento] = useState('')
  const [motivo, setMotivo] = useState('')
  const [valorContado, setValorContado] = useState('')
  const contado = lerDinheiro(valorContado)
  const diferencaPrevista = selecionada && !selecionada.saldoDesatualizado && contado !== null
    ? Math.round((selecionada.valorFechamentoEsperado - contado) * 100) / 100 : null
  const [mensagem, setMensagem] = useState<MensagemNaTela>()
  // Qual ação está em curso: só o botão dela diz que está trabalhando.
  const [ocupado, setOcupado] = useState<'abrir' | 'lancar' | 'fechar' | null>(null)
  const [carregando, setCarregando] = useState(true)
  const [vendasDaSessao, setVendasDaSessao] = useState<VendasDaSessao>()
  const [emDetalhe, setEmDetalhe] = useState<VendaEmDetalhe>()
  const [segundaVia, setSegundaVia] = useState<SegundaVia>()
  // Os caixas de outros usuários esquecidos abertos desde um dia anterior, que o histórico por dia de
  // abertura não mostra no dia de hoje. Só do administrador, e só com rede.
  const [deOutroDia, setDeOutroDia] = useState<SessaoCaixa[]>([])
  const [confirmar, dialogoDeConfirmacao] = useConfirmacao()
  // A entrada traz para cá quem está sem caixa aberto; o tour mora nesta tela, e não no alto de todas.
  const tour = useTourDoCaixa({ identidade, aberta, historico, carregando, vendasDoCaixa })
  const sessaoSelecionada = selecionada?.id
  const listaDaSessao = vendasDaSessao?.sessaoId === sessaoSelecionada ? vendasDaSessao?.lista : undefined
  const detalheDaSessao = emDetalhe?.venda.sessaoCaixaId === sessaoSelecionada ? emDetalhe : undefined
  const segundaViaDoDetalhe = segundaVia?.comprovante.vendaId === detalheDaSessao?.venda.id
    ? segundaVia : undefined
  // A lista chega da mais recente para a mais antiga; o caixa a mostra como o extrato, na ordem do dia.
  const vendasEmOrdem = listaDaSessao ? [...listaDaSessao].sort((a, b) => a.criadoEm.localeCompare(b.criadoEm)) : []
  const vendasAbertas = vendasEmOrdem.filter((venda) => venda.status === 'ABERTA')
  const resumoDasAbertas: Abertas | null = vendasAbertas.length === 0 ? null : {
    quantas: vendasAbertas.length,
    dinheiro: vendasAbertas.reduce((soma, venda) => soma + centavos(recebidoNaAberta(venda)
      ?.find((forma) => forma.forma === 'DINHEIRO')?.valor ?? 0), 0) / 100,
  }
  const diaDaAbertura = selecionada ? diaNoBalcao(new Date(selecionada.abertaEm)) : dia
  // O administrador pode lançar e fechar no caixa de quem está no balcão agora; o aviso vem antes.
  const emUsoPorOutro = selecionada !== undefined && administrador
    && selecionada.usuarioId !== identidade?.usuarioId && selecionada.status === 'ABERTA'
    && !abertaAntesDeHoje(selecionada.abertaEm)

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
      // A releitura só apaga o erro da própria leitura: a resposta de uma ação continua à vista.
      setMensagem((atual) => atual?.lugar === 'tela' ? undefined : atual)
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'tela' })
    } finally {
      setCarregando(false)
    }
  }, [dia])

  // A rodada de envio que grava resultado muda o que o servidor sabe da sessão: a tela relê.
  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar, rodada])
  useEffect(() => {
    if (!selecionada || selecionada.movimentos) return
    let ativo = true
    caixa.consultar(selecionada.id).then((detalhes) => {
      if (ativo) setSelecionada(detalhes)
    }).catch((falha) => {
      if (ativo) setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'sessao' })
    })
    return () => { ativo = false }
  }, [selecionada])

  // As vendas da sessão, abertas, concluídas e canceladas: o administrador vê as de qualquer operador,
  // e o operador, as das próprias sessões. A rodada de envio relê, porque a venda feita sem rede deixa
  // de estar pendente.
  useEffect(() => {
    if (!sessaoSelecionada) return
    let ativo = true
    vendas.daSessao(sessaoSelecionada).then((lista) => {
      if (ativo) setVendasDaSessao({ sessaoId: sessaoSelecionada, lista })
    }).catch((falha) => {
      if (ativo) setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'vendas' })
    })
    return () => { ativo = false }
  }, [sessaoSelecionada, rodada])

  // A sessão fechada aqui sai do cartão na releitura seguinte, que a rodada da fila e o carregar fazem.
  useEffect(() => {
    if (!administrador || !online) return
    let ativo = true
    caixaNoServidor.abertas().then((abertas) => {
      if (ativo) setDeOutroDia(abertas.filter((sessao) => sessao.usuarioId !== identidade?.usuarioId
        && abertaAntesDeHoje(sessao.abertaEm)))
    }).catch(() => { /* o cartão é um atalho; o histórico continua chegando a essas sessões */ })
    return () => { ativo = false }
  }, [administrador, online, identidade?.usuarioId, rodada, historico])

  // O administrador vê os caixas de todos; o nome de quem abriu vem da lista de usuários da Conta.
  useEffect(() => {
    if (!administrador || !online) return
    let ativo = true
    contas.usuarios().then((lista) => {
      if (ativo) setNomes(new Map(lista.map((usuario) => [usuario.id, usuario.nome])))
    }).catch(() => { /* fica o começo do id, como nos relatórios */ })
    return () => { ativo = false }
  }, [administrador, online])

  function nomeDoOperador(usuarioId: string): string {
    if (identidade && usuarioId === identidade.usuarioId) return identidade.nome
    return nomes.get(usuarioId) ?? `operador ${usuarioId.slice(0, 8)}`
  }

  function mostrarEm(lugar: LugarDaMensagem) {
    return mensagem?.lugar === lugar ? <Mensagem tipo={mensagem.tipo} texto={mensagem.texto} /> : null
  }

  /** Só leitura: corrigir ou cancelar uma venda continua na tela Vender, de quem a fez. */
  async function verVenda(resumo: ResumoDaVenda) {
    setMensagem(undefined)
    try {
      const venda = await vendas.consultar(resumo.id)
      const cliente = venda.clienteId ? (await nomesDosClientesNoAparelho())(venda.clienteId) : null
      setSegundaVia(undefined)
      setEmDetalhe({ venda, cliente })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'vendas' })
    }
  }

  /**
   * O detalhe abre logo abaixo da linha da venda; fechado pelo botão de dentro dele, o foco volta ao
   * Ver da linha, em vez de cair no começo da página com o botão que sumiu.
   */
  function fecharDetalhe(vendaId: string) {
    setEmDetalhe(undefined)
    document.getElementById(idDoVer(vendaId))?.focus()
  }

  /** Sem rede, o comprovante é montado com o que o aparelho guarda, como o da venda feita sem rede. */
  async function pedirSegundaVia(venda: Venda) {
    setMensagem(undefined)
    try {
      setSegundaVia({ comprovante: await vendas.comprovante(venda.id), em: new Date() })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'vendas' })
    }
  }

  async function abrir(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setOcupado('abrir')
    setMensagem(undefined)
    try {
      await caixa.abrir(dinheiroDoCampo(valorAbertura, 'o valor inicial'))
      await carregar()
      setMensagem({ tipo: 'sucesso', texto: 'Caixa aberto.', lugar: 'abertura' })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'abertura' })
    } finally {
      setOcupado(null)
    }
  }

  async function lancar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!selecionada) return
    const tipo = tipoMovimento
    setOcupado('lancar')
    setMensagem(undefined)
    try {
      const valor = dinheiroDoCampo(valorMovimento, 'o valor do movimento')
      const rota = `/api/caixa/sessoes/${selecionada.id}/`
        + (tipo === 'SANGRIA' ? 'sangrias' : 'suprimentos')
      const intencao = await intencaoOnline(rota, { valor, motivo: motivo.trim() }, identidade)
      if (tipo === 'SANGRIA') await caixa.sangrar(selecionada.id, valor, motivo, intencao.id)
      else await caixa.suprir(selecionada.id, valor, motivo, intencao.id)
      await carregar()
      setSelecionada(await caixa.consultar(selecionada.id))
      intencao.confirmar()
      setValorMovimento('')
      setMotivo('')
      setMensagem({ tipo: 'sucesso', lugar: 'movimento',
        texto: tipo === 'SANGRIA' ? 'Sangria registrada.' : 'Suprimento registrado.' })
    } catch (falha) {
      setMensagem({ tipo: 'erro', lugar: 'movimento', texto: falha instanceof SemConexao
        ? 'Sem conexão. Tente registrar o mesmo movimento quando a rede voltar.'
        : erroDeCadastro(falha) })
    } finally {
      setOcupado(null)
    }
  }

  async function fechar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!selecionada) return
    setMensagem(undefined)
    if (contado === null) {
      setMensagem({ tipo: 'erro', texto: 'Informe o valor contado com até duas casas decimais.',
        lugar: 'fechamento' })
      return
    }
    if (!(await confirmar(perguntaDoFechamento(contado, diferencaPrevista, resumoDasAbertas)))) return
    setOcupado('fechar')
    try {
      await caixa.fechar(selecionada.id, contado)
      setValorContado('')
      await carregar()
      setSelecionada(await caixa.consultar(selecionada.id))
      // Os formulários somem com o caixa fechado; o aviso vai para o alto do cartão da sessão.
      setMensagem({ tipo: 'sucesso', texto: 'Caixa fechado.', lugar: 'sessao' })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'fechamento' })
    } finally {
      setOcupado(null)
    }
  }

  const vendido = listaDaSessao ? vendidoNoCaixa(listaDaSessao) : undefined
  const notaForaDoTotal = vendido && vendido.vendas > 0 ? foraDoTotal(vendasAbertas.length,
    vendasEmOrdem.filter((venda) => venda.status === 'CANCELADA').length) : null

  return <section className="caixa">
    {tour.mostrar && <PrimeirosPassos feitos={tour.feitos} aoTerminar={tour.fechar} />}
    <TituloComAjuda titulo="Caixa" ajuda={AJUDA.caixa} />
    {mostrarEm('tela')}
    {carregando ? <p>Carregando...</p> : <>
      {deOutroDia.length > 0 && <section className="caixa__cartao">
        <h3>Caixas abertos desde outro dia</h3>
        <p>Caixas de outros usuários que ficaram abertos. Escolha um para conferir e fechar.</p>
        <ul className="caixa__lista">
          {deOutroDia.map((sessao) => <li key={sessao.id}>
            <button className="botao botao--secundario" type="button" onClick={() => setSelecionada(sessao)}>
              {nomeDoOperador(sessao.usuarioId)} · aberto em {dataHora.format(new Date(sessao.abertaEm))}
            </button>
          </li>)}
        </ul>
      </section>}
      <section className="caixa__cartao">
        <h3>Meu caixa</h3>
        {aberta ? <>
          <p>Aberto em {dataHora.format(new Date(aberta.abertaEm))}</p>
          <p>Valor inicial: <strong>{moeda.format(aberta.valorAbertura)}</strong></p>
          {aberta.pendenteSincronizacao && <p>Pendente de sincronização com o servidor.</p>}
          {/* Com o próprio caixa já aberto embaixo, o botão só repetiria o que está na tela. */}
          {selecionada?.id !== aberta.id && <button className="botao botao--secundario" type="button"
            onClick={() => setSelecionada(aberta)}>Ver meu caixa</button>}
        </> : <form onSubmit={(evento) => void abrir(evento)}>
          <p>Nenhum caixa aberto para você.</p>
          <CampoDeDinheiro rotulo="Valor inicial" required placeholder="0,00" valor={valorAbertura}
            aoMudar={setValorAbertura} />
          <button className="botao" disabled={ocupado !== null}>
            {ocupado === 'abrir' ? 'Abrindo...' : 'Abrir caixa'}
          </button>
        </form>}
        {mostrarEm('abertura')}
      </section>

      <section className="caixa__cartao">
        <h3>Histórico do dia</h3>
        <label>Dia
          <input type="date" value={dia} onChange={(evento) => setDia(evento.target.value)} />
        </label>
        <p>{!online ? 'Caixas deste operador guardados no aparelho.'
          : administrador ? 'Caixas de todos os operadores.' : 'Seus caixas.'}</p>
        {historico.length === 0 ? <p>Nenhum caixa neste dia.</p> : <ul className="caixa__lista">
          {historico.map((sessao) => <li key={sessao.id}>
            <button className="botao botao--secundario caixa__item" type="button"
              onClick={() => setSelecionada(sessao)}>
              {administrador && <strong>{nomeDoOperador(sessao.usuarioId)}</strong>}
              <span>{linhaDoHistorico(sessao)}</span>
            </button>
          </li>)}
        </ul>}
      </section>

      {/* A chave recolhe de novo as listas abertas quando outro caixa é escolhido. */}
      {selecionada && <section className="caixa__cartao" key={selecionada.id}>
        <h3>Caixa de {nomeDoOperador(selecionada.usuarioId)}</h3>
        {mostrarEm('sessao')}
        {emUsoPorOutro && <p className="mensagem-alerta">
          {nomeDoOperador(selecionada.usuarioId)} está com este caixa em uso desde
          as {hora.format(new Date(selecionada.abertaEm))} de hoje. Sangria, suprimento e fechamento
          lançados aqui mudam a gaveta que está no balcão.
        </p>}
        <p>Situação: <strong>{ROTULO_DO_CAIXA[selecionada.status]}</strong></p>
        {selecionada.pendenteSincronizacao && <p>Pendente de sincronização com o servidor.</p>}
        <p>Abertura: {dataHora.format(new Date(selecionada.abertaEm))}</p>
        {selecionada.fechadaEm && <p>Fechamento: {dataHora.format(new Date(selecionada.fechadaEm))}</p>}

        <div className="caixa__resumo">
          <div>
            <h4 id={`${ids}-gaveta`}>Gaveta</h4>
            <table className="tabela" aria-labelledby={`${ids}-gaveta`}>
              <tbody>
                <tr><th scope="row">Valor inicial</th>
                  <td className="tabela__numero">{moeda.format(selecionada.valorAbertura)}</td></tr>
                <tr><th scope="row">Saldo esperado</th>
                  {selecionada.saldoDesatualizado ? <td>indisponível até a próxima leitura do caixa</td>
                    : <td className="tabela__numero"><strong>{moeda.format(selecionada.valorFechamentoEsperado)}</strong></td>}</tr>
                {selecionada.status === 'FECHADA' && !selecionada.fechamentoSemValores && <>
                  <tr><th scope="row">Valor contado</th>
                    <td className="tabela__numero">{moeda.format(selecionada.valorFechamentoContado ?? 0)}</td></tr>
                  <tr><th scope="row">Diferença</th>
                    {selecionada.saldoDesatualizado ? <td>aparece quando o caixa for lido de novo.</td>
                      : <td className="tabela__numero">
                        <strong>{comMaiuscula(diferencaEmPalavras(selecionada.diferenca ?? 0))}</strong></td>}</tr>
                </>}
              </tbody>
            </table>
            {selecionada.saldoDesatualizado && <p>Este caixa mudou com rede depois da última leitura feita neste
              aparelho. Saldo, diferença e extrato atualizados aparecem quando ele for lido de novo; até lá, a
              sangria sem rede espera.</p>}
            {selecionada.status === 'FECHADA' && selecionada.fechamentoSemValores
              && <p>Fechado no servidor; valor contado e diferença aparecem quando a rede voltar.</p>}
          </div>
          <div>
            <h4 id={`${ids}-vendido`}>Vendido neste caixa</h4>
            {!vendido ? <p>Carregando vendas...</p>
              : vendido.vendas === 0 ? <p>Nenhuma venda concluída neste caixa.</p>
                : <table className="tabela" aria-labelledby={`${ids}-vendido`}>
                  <thead><tr><th scope="col">Forma</th><th scope="col" className="tabela__numero">Valor</th></tr></thead>
                  <tbody>
                    {vendido.porForma.map((daForma) => <tr key={daForma.forma}>
                      <th scope="row">{ROTULO_DA_FORMA[daForma.forma]}</th>
                      <td className="tabela__numero">{moeda.format(daForma.valor)}</td>
                    </tr>)}
                    {vendido.semForma > 0 && <tr><th scope="row">Forma não informada</th>
                      <td className="tabela__numero">{moeda.format(vendido.semForma)}</td></tr>}
                  </tbody>
                  <tfoot><tr>
                    <th scope="row">Total de {vendido.vendas} {vendido.vendas === 1 ? 'venda' : 'vendas'}</th>
                    <td className="tabela__numero">{moeda.format(vendido.total)}</td>
                  </tr></tfoot>
                </table>}
            {notaForaDoTotal && <p className="caixa__nota">{notaForaDoTotal}</p>}
            {!online && <p className="caixa__nota">Sem conexão, o vendido conta só as vendas guardadas neste aparelho.</p>}
          </div>
        </div>

        {/* As ações vêm antes do extrato: cada venda acrescenta uma linha a ele, e o fechamento, no
            fim do dia, ficaria depois de todas. */}
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
          <CampoDeDinheiro rotulo="Valor" required valor={valorMovimento} aoMudar={setValorMovimento} />
          <label>Motivo
            <input required value={motivo} onChange={(evento) => setMotivo(evento.target.value)} />
          </label>
          <button className="botao" disabled={ocupado !== null}>
            {ocupado === 'lancar' ? 'Registrando...' : `Registrar ${tipoMovimento.toLowerCase()}`}
          </button>
          {mostrarEm('movimento')}
        </form>}
        {selecionada.status === 'ABERTA' && <form className="caixa__formulario"
          onSubmit={(evento) => void fechar(evento)}>
          <h4>Fechar caixa</h4>
          <p>Confira o dinheiro da gaveta contra o saldo esperado, e o cartão e o Pix contra o vendido neste caixa.</p>
          {resumoDasAbertas && <>
            <p className="mensagem-alerta">{avisoDasAbertas(resumoDasAbertas)}</p>
            <table className="tabela" aria-label="Vendas abertas">
              <thead><tr><th scope="col">Hora</th><th scope="col" className="tabela__numero">Valor da venda</th>
                <th scope="col" className="caixa__recebido">Já recebido</th></tr></thead>
              <tbody>
                {vendasAbertas.map((venda) => <tr key={venda.id}>
                  <td className="tabela__hora">{horaNoDia(new Date(venda.criadoEm), diaDaAbertura)}</td>
                  <td className="tabela__numero">{moeda.format(venda.total)}</td>
                  <td className="caixa__recebido">{recebidoEmPalavras(recebidoNaAberta(venda))}</td>
                </tr>)}
              </tbody>
            </table>
          </>}
          <CampoDeDinheiro rotulo="Valor contado" required valor={valorContado} aoMudar={setValorContado} />
          {diferencaPrevista !== null && <p>Se fechar agora: {diferencaEmPalavras(diferencaPrevista)}</p>}
          <button className="botao" disabled={ocupado !== null}>
            {ocupado === 'fechar' ? 'Fechando...' : 'Fechar e registrar diferença'}
          </button>
          {mostrarEm('fechamento')}
        </form>}

        {/* Recolhido: com um turno cheio, o extrato e as vendas abertos levavam a página a milhares de
            pixels. O resumo acima já diz o saldo e o vendido; a lista é para conferir uma linha. */}
        <details className="caixa__extrato">
          <summary id={`${ids}-movimentos`}>
            Movimentos{selecionada.movimentos ? ` (${selecionada.movimentos.length})` : ''}
          </summary>
          {!selecionada.movimentos ? <p>Carregando extrato...</p>
            : selecionada.movimentos.length === 0 ? <p>Nenhum movimento neste caixa.</p>
              : <table className="tabela" aria-labelledby={`${ids}-movimentos`}>
                <thead><tr>
                  <th scope="col">Hora</th><th scope="col">Tipo</th>
                  <th scope="col" className="tabela__numero">Valor</th>
                  {/* O saldo corrido de um extrato que ficou para trás não bateria com o do servidor. */}
                  {!selecionada.saldoDesatualizado && <th scope="col" className="tabela__numero">Saldo</th>}
                </tr></thead>
                <tbody>
                  {extratoComSaldo(selecionada).map(({ movimento, valor, saldo }) => <tr key={movimento.id}>
                    <td className="tabela__hora">{horaNoDia(new Date(movimento.criadoEm), diaDaAbertura)}</td>
                    <td>
                      {ROTULO_DO_MOVIMENTO[movimento.tipo]}
                      {movimento.motivo && <span className="caixa__sob">{movimento.motivo}</span>}
                    </td>
                    <td className="tabela__numero">{moeda.format(valor)}</td>
                    {!selecionada.saldoDesatualizado && <td className="tabela__numero">{moeda.format(saldo)}</td>}
                  </tr>)}
                </tbody>
              </table>}
        </details>

        {/* O extrato só mostra o dinheiro; aqui ficam todas as vendas, inclusive as canceladas e as
            pagas em Pix, cartão ou fiado. Nenhuma ação de escrita: a lista é para conferir. */}
        <details className="caixa__extrato">
          <summary id={`${ids}-vendas`}>Vendas{listaDaSessao ? ` (${listaDaSessao.length})` : ''}</summary>
          {mostrarEm('vendas')}
          {!online && <p>Sem conexão, a lista mostra as vendas guardadas neste aparelho.</p>}
          {!listaDaSessao ? <p>Carregando vendas...</p>
            : listaDaSessao.length === 0 ? <p>Nenhuma venda neste caixa.</p>
              : <table className="tabela" aria-labelledby={`${ids}-vendas`}>
                <thead><tr>
                  <th scope="col">Hora</th><th scope="col">Situação</th>
                  <th scope="col" className="tabela__numero">Valor</th><td />
                </tr></thead>
                <tbody>
                  {vendasEmOrdem.map((resumo) => {
                    const aberto = detalheDaSessao?.venda.id === resumo.id
                    const cancelada = resumo.status === 'CANCELADA'
                    // O administrador vende em qualquer caixa: a venda dele entra na lista e no vendido
                    // do caixa do operador, mas o detalhe dela só ele abre.
                    const abre = administrador || resumo.usuarioId === identidade?.usuarioId
                    return <Fragment key={resumo.id}>
                      <tr>
                        <td className="tabela__hora">{horaNoDia(new Date(resumo.criadoEm), diaDaAbertura)}</td>
                        <td>
                          <SituacaoDaVenda status={resumo.status} />
                          {resumo.formas && resumo.formas.length > 0 && <span className="caixa__sob">
                            {listaDeFormas.format(resumo.formas.map((forma) => ROTULO_DA_FORMA[forma.forma]))}
                          </span>}
                          {resumo.pendenteSincronizacao && <span className="caixa__sob">pendente de sincronização</span>}
                          {!abre && <span className="caixa__sob">do administrador</span>}
                        </td>
                        <td className={cancelada ? 'tabela__numero caixa__cancelada' : 'tabela__numero'}>
                          {cancelada ? <s>{moeda.format(resumo.total)}</s> : moeda.format(resumo.total)}
                        </td>
                        <td>
                          {abre && <button id={idDoVer(resumo.id)} className="botao botao--secundario caixa__ver" type="button"
                            aria-expanded={aberto}
                            onClick={() => aberto ? setEmDetalhe(undefined) : void verVenda(resumo)}>Ver</button>}
                        </td>
                      </tr>
                      {aberto && detalheDaSessao && <tr className="caixa__detalhe-da-linha"><td colSpan={4}>
                        <DetalheDaVenda {...detalheDaSessao} aoFechar={() => fecharDetalhe(resumo.id)}
                          aoPedirSegundaVia={() => void pedirSegundaVia(detalheDaSessao.venda)} />
                        {segundaViaDoDetalhe && identidade && <Comprovante comprovante={segundaViaDoDetalhe.comprovante}
                          negocio={identidade.nomeNegocio} operador={nomeDoOperador(segundaViaDoDetalhe.comprovante.usuarioId)}
                          segundaViaEm={segundaViaDoDetalhe.em}
                          aoFalhar={(texto) => setMensagem({ tipo: 'erro', texto, lugar: 'vendas' })} />}
                      </td></tr>}
                    </Fragment>
                  })}
                </tbody>
              </table>}
        </details>
      </section>}
    </>}
    {dialogoDeConfirmacao}
  </section>
}
