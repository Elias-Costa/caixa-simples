import { useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { QRCodeSVG } from 'qrcode.react'
import { cadastro, type Cliente, type Produto } from '../api/cadastro'
import type { SessaoCaixa } from '../api/caixa'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { comServidor as alcancaOServidor } from '../api/conexao'
import { contas } from '../api/contas'
import { fiado } from '../api/fiado'
import { intencaoOnline, type IntencaoOnline } from '../api/intencaoOnline'
import type { Comprovante as DadosDoComprovante, ConciliacaoPix, FormaPagamento, ResumoDaVenda, Venda } from '../api/vendas'
import { diaNoBalcao, hojeNoBalcao } from '../dataDoBalcao'
import { dataHora, diaEMes, hora, horaNoDia, lerDinheiro, moeda, quantidadeComUnidade, valorNoCampo } from '../formatos'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { normalizarNsu, recusaDaQuantidade } from '../offline/raizDaVenda'
import { criarVendaLocal } from '../offline/vendaLocal'
import { useContextoDoShell } from '../shell/ContextoDoShell'
import { useSincronizacao } from '../shell/sincronizacao'
import { useCelular } from '../shell/useCelular'
import { useConexao } from '../shell/useConexao'
import { useSessao } from '../sessao/useSessao'
import { paraBusca } from '../textoDeBusca'
import { CampoDeDinheiro } from './CampoDeDinheiro'
import { erroDeCadastro } from './erroDeCadastro'
import { filtrarPorNome } from './filtroPorNome'
import { Mensagem } from './Mensagem'
import { PixManual } from './QrDoPix'
import { ROTULO_DA_COBRANCA_PIX, ROTULO_DA_FORMA, ROTULO_DA_VENDA } from './rotulos'
import { motivoSemServidor, vendasSemServidor } from './semServidor'
import { useConfirmacao, type Pergunta } from './useConfirmacao'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'
import { Comprovante } from './Comprovante'

const caixa = criarCaixaLocal()
const vendas = criarVendaLocal()
const CANCELAR_DEPOIS_DE_SINCRONIZAR = 'O cancelamento fica disponível depois da sincronização com o servidor.'
/** As notas mais comuns na mão do cliente: um toque preenche o valor recebido sem abrir o teclado. */
const NOTAS = [10, 20, 50, 100]
/**
 * As vendas da lista do caixa antes de Ver todas. Com o turno inteiro, a lista passava de quatro mil
 * pixels abaixo da comanda; as últimas são as que o balcão procura.
 */
const VENDAS_A_VISTA = 10
const listaDeFormas = new Intl.ListFormat('pt-BR', { style: 'long', type: 'conjunction' })

/**
 * O que o operador escolhe na forma de pagamento. O Pix aparece duas vezes porque chega por dois
 * caminhos: o manual, lançado depois de o operador ver o dinheiro no aplicativo do banco, e o
 * integrado, cobrado pelo provedor e confirmado por ele. Os dois viram a forma PIX na parcela.
 */
type OpcaoDePagamento = Exclude<FormaPagamento, 'PIX'> | 'PIX_MANUAL' | 'PIX_INTEGRADO'

function formaDa(opcao: OpcaoDePagamento): FormaPagamento {
  return opcao === 'PIX_MANUAL' || opcao === 'PIX_INTEGRADO' ? 'PIX' : opcao
}

/** O painel em que a mensagem de erro aparece: o mesmo em que a ação foi tocada. */
type LugarDoErro = 'tela' | 'busca' | 'comanda' | 'historico' | 'comprovante'

/**
 * O erro e, quando veio de uma leitura que a tela repete sozinha, qual: a leitura seguinte que responde tira só o
 * erro dela, e a resposta a um gesto da pessoa continua à vista.
 */
type ErroNaTela = { texto: string; lugar: LugarDoErro; daLeitura?: 'busca' | 'clientes' }

/** A quantidade não é dinheiro: tem três casas, para o que se vende por peso, e fica fora do critério dele. */
function numero(texto: string): number { return Number(texto.replace(',', '.')) }

/** O desconto em branco é desconto nenhum, como o zero que o campo traz ao abrir. */
function descontoDigitado(texto: string): number | null {
  return texto === '' ? 0 : lerDinheiro(texto)
}

/**
 * A pergunta antes de cancelar diz o valor e o que muda fora da comanda. O dinheiro só entra no
 * caixa quando a venda conclui, então o estorno só existe para a concluída.
 */
function perguntaDoCancelamento(venda: Venda): Pergunta {
  const partes = ['O cancelamento não pode ser desfeito.']
  const emDinheiro = venda.parcelas.some((parcela) => parcela.forma === 'DINHEIRO')
    || venda.recebimentos.some((recebimento) => recebimento.forma === 'DINHEIRO')
  if (venda.status === 'CONCLUIDA' && emDinheiro) partes.push('O dinheiro recebido nela sai do caixa como estorno.')
  // O Pix confirmado, lançado à mão ou pelo integrado, já está na conta do negócio.
  if (venda.parcelas.some((parcela) => parcela.forma === 'PIX' && parcela.status === 'CONFIRMADO')) {
    partes.push('O Pix recebido precisa ser devolvido fora do sistema.')
  }
  return {
    titulo: `Cancelar a venda de ${moeda.format(venda.total)}?`,
    texto: partes.join(' '),
    acao: 'Cancelar venda',
  }
}

/**
 * Sem resposta, a operação pode ter chegado ao servidor mesmo assim; a comanda, relida quando ele
 * responde, mostra se ela valeu, e repetir sem olhar poderia lançar duas vezes.
 */
function erroDaComanda(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem resposta do servidor. Confira a comanda antes de repetir.'
  return erroDeCadastro(falha)
}

/**
 * A conclusão sem resposta pode ter valido, e a comanda à vista ainda é a de antes dela. Com a parcela
 * respondida, a frase diz que o dinheiro já está registrado: a comanda antiga não mostra a parcela, e
 * quem a lesse cobraria o cliente de novo.
 */
const CONCLUSAO_SEM_RESPOSTA = 'O servidor não respondeu à conclusão. A comanda mostra se a venda foi '
  + 'concluída quando ele responder.'
const PARCELA_SEM_CONCLUSAO = 'A parcela foi registrada, mas o servidor não respondeu à conclusão. A comanda '
  + 'mostra se a venda foi concluída quando ele responder.'

/**
 * A venda cuja escrita ficou sem resposta, relida quando o servidor responde de novo. A intenção é a do
 * id que a escrita levou, liberada quando a comanda relida mostra o item ou a parcela com ele.
 */
type ConferirNaVolta = { vendaId: string; intencao?: IntencaoOnline }

function tentativaPix(vendaId: string, valor: number): string {
  const chave = `ecrx-caixa-pix-${vendaId}`
  const guardada = sessionStorage.getItem(chave)
  if (guardada) {
    const tentativa = JSON.parse(guardada) as { id: string; valor: number }
    if (tentativa.valor === valor) return tentativa.id
  }
  const id = crypto.randomUUID()
  sessionStorage.setItem(chave, JSON.stringify({ id, valor }))
  return id
}

/**
 * A hora da venda. A de outro dia, no caixa aberto desde ontem, leva também o dia: só a hora
 * misturaria as vendas de ontem com as de hoje.
 */
function quando(criadoEm: string): string {
  return horaNoDia(new Date(criadoEm), hojeNoBalcao())
}

/** As formas pelas quais a venda entrou, como Dinheiro e Cartão; a venda sem parcela que vale não tem. */
function formasDaLinha(resumo: ResumoDaVenda): string {
  const formas = resumo.formas ?? []
  return formas.length === 0 ? ''
    : ` · ${listaDeFormas.format(formas.map((forma) => ROTULO_DA_FORMA[forma.forma]))}`
}

/**
 * O aviso da venda que ficou aberta no caixa. A lista vem da mais recente para a mais antiga, e o
 * Retomar abre a primeira.
 */
function avisoDasAbertas(abertas: ResumoDaVenda[]): string {
  const maisRecente = abertas[0]
  const instante = new Date(maisRecente.criadoEm)
  const iniciada = diaNoBalcao(instante) === hojeNoBalcao() ? `iniciada às ${hora.format(instante)}`
    : `iniciada em ${diaEMes.format(instante)}, às ${hora.format(instante)}`
  const valorEHora = `de ${moeda.format(maisRecente.total)}, ${iniciada}`
  return abertas.length === 1 ? `Este caixa tem uma venda aberta, ${valorEHora}.`
    : `Este caixa tem ${abertas.length} vendas abertas. A mais recente é ${valorEHora}.`
}

/**
 * Os resultados na ordem em que o balcão procura: o produto do código igual ao termo, o mesmo que o
 * Enter lança, depois os nomes que começam pelo termo, depois o resto, cada grupo em ordem
 * alfabética. Na ordem do cadastro, o mais vendido podia vir por último.
 */
function ordenarResultados(produtos: Produto[], termo: string): Produto[] {
  const alvo = paraBusca(termo)
  const grupo = (produto: Produto) => produto.codigo === termo ? 0
    : paraBusca(produto.nome).startsWith(alvo) ? 1 : 2
  return [...produtos].sort((a, b) => grupo(a) - grupo(b) || a.nome.localeCompare(b.nome, 'pt-BR'))
}

/** O multiplicador na busca usa o mesmo estado do campo visível, para evitar duas quantidades. */
function separarMultiplicador(texto: string): { termo: string; quantidade?: string } {
  const encontrado = texto.match(/^(\d+(?:[.,]\d{1,3})?)\*(.*)$/)
  return encontrado ? { termo: encontrado[2], quantidade: encontrado[1].replace(',', '.') }
    : { termo: texto }
}

/**
 * O que o Enter da busca guarda: o termo, a quantidade e o desconto do item que estavam na tela naquele instante, nos
 * textos dos campos, para voltarem a eles como estavam. Com os resultados do termo já à vista, o produto vem decidido.
 */
type Leitura = { termo: string; quantidade: string; descontoItem: string; produto?: Produto }

/**
 * O produto que o Enter lança: o do código igual ao termo, ou o único resultado. Com vários achados pelo nome, quem
 * escolhe é a pessoa.
 */
function escolhidoPeloEnter(encontrados: Produto[], termo: string): Produto | undefined {
  const doCodigo = encontrados.find((produto) => produto.codigo === termo)
  return doCodigo ?? (encontrados.length === 1 ? encontrados[0] : undefined)
}

/** O desconto da venda digitado e ainda não aplicado, com a venda em que foi digitado. */
type DescontoDaVendaDigitado = { vendaId: string; texto: string }

/**
 * O campo do desconto da venda: o que se digitou nesta venda ou, sem isso, o desconto que ela já tem. Aplicar troca o
 * desconto, não soma, e quem vai trocá-lo precisa ver o que vale; o texto de outra venda passaria por desconto
 * aplicado nesta.
 */
function textoDoDescontoDaVenda(venda: Venda | undefined, digitado: DescontoDaVendaDigitado | undefined): string {
  if (!venda) return ''
  if (digitado?.vendaId === venda.id) return digitado.texto
  return venda.descontoDaVenda > 0 ? valorNoCampo(venda.descontoDaVenda) : ''
}

/** O servidor define o total como a soma dos itens menos o desconto da venda; a conta volta em centavos. */
function somaDosItens(venda: Venda): number {
  return (Math.round(venda.total * 100) + Math.round(venda.descontoDaVenda * 100)) / 100
}

/** Quem passou códigos depois do que falhou precisa saber que eles não entraram, e não só que aquele falhou. */
function codigosDescartados(quantos: number): string {
  if (quantos === 0) return ''
  return quantos === 1 ? ' O código passado em seguida não foi lançado.'
    : ` Os ${quantos} códigos passados em seguida não foram lançados.`
}

/**
 * O rodapé preso não sobe acima do topo do painel da comanda: no celular, com cabeçalho, faixa e busca ocupando
 * a tela, o fim dele ficaria abaixo dela. A página rola só o que falta, e a busca continua à vista.
 */
function mostrarORodape(rodape: HTMLElement | null) {
  if (!rodape) return
  const falta = rodape.getBoundingClientRect().bottom - window.innerHeight
  if (falta > 0) window.scrollBy(0, falta)
}

export function TelaDeVenda() {
  const { identidade } = useSessao()
  const { definirFaixa, definirVendaAberta } = useContextoDoShell()
  const { rodada, vendasRecusadas } = useSincronizacao()
  const buscaRef = useRef<HTMLInputElement>(null)
  const rodapeRef = useRef<HTMLDivElement>(null)
  const [sessao, setSessao] = useState<SessaoCaixa>()
  const [historico, setHistorico] = useState<ResumoDaVenda[]>([])
  const [conciliacoes, setConciliacoes] = useState<ConciliacaoPix[]>([])
  const [conciliacaoSelecionada, setConciliacaoSelecionada] = useState<Venda>()
  const [clientes, setClientes] = useState<Cliente[]>([])
  const [buscaCliente, setBuscaCliente] = useState('')
  const [atual, setAtual] = useState<Venda>()
  const [comprovante, setComprovante] = useState<DadosDoComprovante>()
  // O comprovante que aparece logo depois de concluir é o original; reaberto pelo histórico, é
  // segunda via, com o instante em que foi reaberto.
  const [segundaViaEm, setSegundaViaEm] = useState<Date>()
  const [busca, setBusca] = useState('')
  // O termo cuja resposta está em resultados: sem ele, a busca vazia e a ainda em curso pareceriam iguais.
  const [buscado, setBuscado] = useState('')
  const [quantidade, setQuantidade] = useState('1')
  // Vazio vale zero; com 0 dentro, o que se digita depois dele vira 02,90.
  const [descontoItem, setDescontoItem] = useState('')
  const [descontoDaVendaDigitado, setDescontoDaVendaDigitado] = useState<DescontoDaVendaDigitado>()
  const [resultados, setResultados] = useState<Produto[]>([])
  const [forma, setForma] = useState<OpcaoDePagamento>('DINHEIRO')
  const [valorPagamento, setValorPagamento] = useState('')
  const [valorRecebido, setValorRecebido] = useState('')
  const [nsu, setNsu] = useState('')
  const [troco, setTroco] = useState<number>()
  const [copiado, setCopiado] = useState<string>()
  const [nomes, setNomes] = useState<Map<string, string>>(() => new Map())
  const [erro, setErro] = useState<ErroNaTela>()
  const [conferirNaVolta, setConferirNaVolta] = useState<ConferirNaVolta>()
  const [ocupado, setOcupado] = useState(false)
  // As leituras que o Enter guardou e que esperam a comanda ficar livre, na ordem do Enter. A lista fica numa ref
  // porque o Enter e o fim de uma leitura a consultam antes do desenho seguinte; o número em estado acompanha a lista,
  // mantém a comanda ocupada enquanto ela não esvazia e faz a fila andar.
  const fila = useRef<Leitura[]>([])
  const [naFila, setNaFila] = useState(0)
  const [carregando, setCarregando] = useState(true)
  const [todasAsVendas, setTodasAsVendas] = useState(false)
  const idDaLista = useId()
  const [confirmar, dialogoDeConfirmacao] = useConfirmacao()
  const conexao = useConexao()
  const celular = useCelular()
  const comServidor = conexao === 'com-servidor'
  // Entre dois itens da fila a comanda continua ocupada: um toque em Receber e concluir fecharia a venda sem os itens
  // que ainda esperam.
  const comandaOcupada = ocupado || naFila > 0
  const pixPendente = atual?.parcelas.some((parcela) => parcela.pix && parcela.status === 'PENDENTE') ?? false
  const doDispositivo = atual?.pendenteSincronizacao === true
  // No celular, o rodapé preso cobre a frase no painel. Ao concluir, é a hora de saber o que aconteceu, e ela
  // vai para o rodapé, em versão curta, porque ele divide a tela com a comanda.
  const registradaNoRodape = celular && doDispositivo && atual?.status === 'CONCLUIDA'
  // A comanda que existe no servidor só continua com ele respondendo, e o aparelho não a copia.
  // Sem servidor, ela fica para ler: o botão que só responderia com a recusa ensinaria o operador a
  // não confiar nos botões, e a ação que resta é começar outra venda, que fica no aparelho.
  const comandaSoLeitura = atual?.status === 'ABERTA' && !doDispositivo && !comServidor
  // Sem esta frase, a venda feita sem rede e recusada na volta reaparece aberta sem dizer por quê.
  const recusaDaComanda = atual?.status === 'ABERTA' && !doDispositivo
    ? vendasRecusadas.find((venda) => venda.vendaId === atual.id) : undefined
  const recusadasPorConferir = vendasRecusadas.filter((venda) => !venda.conferida).length
  const editavel = atual?.status === 'ABERTA' && !comandaSoLeitura
  const descontoVenda = textoDoDescontoDaVenda(atual, descontoDaVendaDigitado)
  const comFiado = atual?.parcelas.some((parcela) => parcela.forma === 'FIADO') ?? false
  // O Pix integrado depende do provedor, com o cliente presente, e de a Conta poder cobrar por ele
  // hoje; a Venda registrada sem rede não o recebe. A opção escolhida antes de a rede cair volta
  // para dinheiro, sem esperar outro toque. O Pix manual vale com e sem rede, em todo plano.
  const pixIntegradoPermitido = comServidor && !doDispositivo && identidade?.pixIntegrado === true
  // O fiado é do administrador, pede cliente e entra uma vez por venda. Tirado o cliente, ou com a
  // venda relida sem ele, o Fiado sai do seletor e a forma volta para dinheiro, como o Pix integrado:
  // o rodapé não pode oferecer o fiado com outra forma à vista.
  const fiadoPermitido = identidade?.perfil === 'ADMIN' && !!atual?.clienteId && !comFiado
  const opcaoEscolhida: OpcaoDePagamento = (forma === 'PIX_INTEGRADO' && !pixIntegradoPermitido)
    || (forma === 'FIADO' && !fiadoPermitido) ? 'DINHEIRO' : forma
  const formaEscolhida = formaDa(opcaoEscolhida)
  const termo = busca.trim()
  const termoCliente = buscaCliente.trim()
  // A lista de clientes já está carregada: filtrar aqui responde a cada letra, também sem rede.
  const clientesEncontrados = termoCliente ? filtrarPorNome(clientes, termoCliente, (cliente) => cliente.nome) : []
  // Com o campo vazio, a parcela é tudo o que falta lançar.
  const valorDaParcela = !atual ? null : valorPagamento ? lerDinheiro(valorPagamento) : atual.faltaPagar
  const lancando = atual !== undefined && editavel && atual.itens.length > 0 && atual.faltaPagar > 0
  const receberEConcluir = lancando && opcaoEscolhida !== 'PIX_INTEGRADO' && atual.parcelas.length === 0
    && valorDaParcela === atual.faltaPagar
  const concluirSemPagar = atual !== undefined && editavel && atual.itens.length > 0
    && atual.faltaPagar === 0 && !pixPendente
  const comBotaoPrincipal = receberEConcluir || concluirSemPagar || comandaSoLeitura
  // A prévia conta em centavos, como o servidor: subtrair em ponto flutuante erraria o último centavo.
  // O troco que vale continua sendo o que o registro da parcela devolve.
  const recebidoNaPrevia = lancando && formaEscolhida === 'DINHEIRO' && valorRecebido
    ? lerDinheiro(valorRecebido) : null
  const previaDoTroco = recebidoNaPrevia !== null && valorDaParcela !== null
    ? (Math.round(recebidoNaPrevia * 100) - Math.round(valorDaParcela * 100)) / 100 : undefined
  // No celular, a lista de itens fica atrás do rodapé preso, e quem passa código só veria o Total mudar. A
  // prévia do troco tira a linha, para o rodapé não passar da folga de rolagem do campo que recebe o foco.
  const ultimoItem = editavel && previaDoTroco === undefined ? atual?.itens.at(-1) : undefined
  // O administrador vende em qualquer caixa, e a venda dele aparece na lista do caixa do operador;
  // abrir, retomar e cancelar continuam com quem a fez, ou com o administrador.
  const abreAVenda = (resumo: ResumoDaVenda) =>
    identidade?.perfil === 'ADMIN' || resumo.usuarioId === identidade?.usuarioId
  // A venda aberta que ficou para trás no caixa, das que esta pessoa retoma. Com outra aberta na
  // comanda, quem vende já escolheu em qual continua, e o aviso sai.
  const abertasNoCaixa = historico.filter((resumo) => resumo.status === 'ABERTA' && resumo.id !== atual?.id
    && abreAVenda(resumo))
  const avisarAbertas = atual?.status !== 'ABERTA' && abertasNoCaixa.length > 0
  const vendasNaLista = todasAsVendas ? historico : historico.slice(0, VENDAS_A_VISTA)

  function falhar(lugar: LugarDoErro, texto: string) { setErro({ texto, lugar }) }
  function erroEm(lugar: LugarDoErro) { return erro?.lugar === lugar ? erro.texto : undefined }

  /** Sem resposta, a venda fica marcada para ser relida quando o servidor responder; outra falha é recusa, e não marca. */
  function conferirSeSemResposta(falha: unknown, vendaId: string | undefined, intencao?: IntencaoOnline) {
    if (falha instanceof SemConexao && vendaId) setConferirNaVolta({ vendaId, intencao })
  }

  /**
   * A venda terminou, e a próxima começa pela busca. No celular, quem desceu até as notas só voltaria até
   * o foco centralizar a busca, com o alto do cabeçalho (sem conexão, vencimento da sessão) fora da vista,
   * e assim ficaria nas vendas seguintes. Do alto, o foco só rola se a busca estiver atrás do rodapé preso.
   * Nas telas maiores a busca é presa, o foco não rola, e o comprovante continua à vista.
   */
  function voltarABusca() {
    if (celular) window.scrollTo(0, 0)
    buscaRef.current?.focus()
  }

  // Relê também quando uma rodada de envio grava resultado: a Venda feita sem rede deixa de estar
  // pendente, e a lista precisa mostrar isso. E quando o servidor volta: sem ele, a lista era só a
  // do aparelho.
  useEffect(() => {
    let vivo = true
    caixa.abertaDoOperadorAtual().then(async (aberta) => {
      if (!vivo) return
      setSessao(aberta)
      if (aberta) setHistorico(await vendas.daSessao(aberta.id))
    }).catch((falha) => { if (vivo) setErro({ texto: erroDeCadastro(falha), lugar: 'tela' }) })
      .finally(() => { if (vivo) setCarregando(false) })
    return () => { vivo = false }
  }, [rodada, comServidor])

  const vendaDoDispositivo = useRef<string | undefined>(undefined)
  useEffect(() => { vendaDoDispositivo.current = atual?.pendenteSincronizacao ? atual.id : undefined }, [atual])
  useEffect(() => {
    const id = vendaDoDispositivo.current
    if (rodada === 0 || !id) return
    let vivo = true
    vendas.consultar(id).then((venda) => {
      if (vivo) setAtual((anterior) => anterior?.id === id ? venda : anterior)
    }).catch((falha) => {
      // O servidor recusou o início, e a venda não existe lá: a comanda ficaria parada dizendo que
      // aguarda a sincronização, e quem diz o que houve é a faixa. Com outra falha, a venda continua
      // como estava, e a próxima ação a relê.
      if (vivo && falha instanceof ErroDaApi && falha.status === 404 && vendaDoDispositivo.current === id) novaVenda()
    })
    return () => { vivo = false }
  }, [rodada])

  // Depois de uma escrita sem resposta, a comanda à vista é a de antes dela, e o servidor pode ter gravado. Na volta
  // dele, a venda é relida antes de o operador repetir: o item gravado aparece, e a venda que concluiu mostra o
  // comprovante, como numa conclusão comum. A comanda vazia é a da venda nova cujo primeiro item ficou sem resposta;
  // outra venda à vista foi escolha de quem vende, e a marca espera.
  const idNaComanda = atual?.id
  const statusNaComanda = atual?.status
  const sessaoId = sessao?.id
  useEffect(() => {
    const pendente = conferirNaVolta
    if (!comServidor || !pendente) return
    if (idNaComanda !== undefined && idNaComanda !== pendente.vendaId) return
    const concluidaAntes = statusNaComanda === 'CONCLUIDA'
    let vivo = true
    void (async () => {
      try {
        const venda = await vendas.consultar(pendente.vendaId)
        const concluiuAgora = !concluidaAntes && venda.status === 'CONCLUIDA'
        const recibo = concluiuAgora ? await vendas.comprovante(venda.id) : undefined
        const lista = sessaoId ? await vendas.daSessao(sessaoId) : undefined
        if (!vivo) return
        setConferirNaVolta(undefined)
        setAtual(venda)
        if (lista) setHistorico(lista)
        const idDaIntencao = pendente.intencao?.id
        if (venda.itens.some((item) => item.id === idDaIntencao)) pendente.intencao?.confirmar()
        if (venda.parcelas.some((parcela) => parcela.id === idDaIntencao)) {
          pendente.intencao?.confirmar()
          setValorPagamento(''); setValorRecebido(''); setNsu('')
        }
        if (venda.status === 'CANCELADA') setComprovante(undefined)
        if (recibo) {
          setComprovante(recibo); setSegundaViaEm(undefined)
          // O resultado que a frase deixava em aberto agora está na tela, e a próxima venda começa pela busca,
          // como em voltarABusca depois de concluir.
          setErro((anterior) => anterior?.lugar === 'comanda' ? undefined : anterior)
          if (celular) window.scrollTo(0, 0)
          buscaRef.current?.focus()
          if (celular) mostrarORodape(rodapeRef.current)
        }
      } catch (falha) {
        // Sem resposta de novo, a marca fica para a próxima volta; outra falha deixa a comanda como está, e a
        // próxima ação a relê.
        if (vivo && !(falha instanceof SemConexao)) setConferirNaVolta(undefined)
      }
    })()
    return () => { vivo = false }
  }, [comServidor, conferirNaVolta, idNaComanda, statusNaComanda, sessaoId, celular])

  // Ao abrir a comanda, o botão principal aparece sem a pessoa procurar. Depois disso, quem rola é ela.
  useLayoutEffect(() => {
    if (celular && comBotaoPrincipal) mostrarORodape(rodapeRef.current)
  }, [atual?.id, comBotaoPrincipal, celular])

  // O botão da faixa some com a última venda conferida; no PDV, o leitor de código escreve na busca.
  const recusadasAntes = useRef(recusadasPorConferir)
  useEffect(() => {
    if (recusadasAntes.current > 0 && recusadasPorConferir === 0) buscaRef.current?.focus()
    recusadasAntes.current = recusadasPorConferir
  }, [recusadasPorConferir])

  // Relê também quando o servidor volta: o aparelho que entrou sem a cópia dos clientes a recebe nessa hora, e a
  // lista da tela não ficaria vazia até a pessoa trocar de tela.
  useEffect(() => {
    let vivo = true
    cadastro.clientes().then((lista) => {
      if (!vivo) return
      setClientes(lista)
      setErro((atual) => atual?.daLeitura === 'clientes' ? undefined : atual)
    }).catch((falha) => { if (vivo) setErro({ texto: erroDeCadastro(falha), lugar: 'tela', daLeitura: 'clientes' }) })
    return () => { vivo = false }
  }, [comServidor])

  useEffect(() => {
    if (identidade?.perfil !== 'ADMIN') return
    let vivo = true
    vendas.conciliacoesPix().then((lista) => { if (vivo) setConciliacoes(lista) })
      .catch((falha) => { if (vivo) setErro({ texto: erroDeCadastro(falha), lugar: 'tela' }) })
    return () => { vivo = false }
  }, [identidade?.perfil])

  // O comprovante de outro operador mostra o nome dele. A lista de usuários é do administrador; sem
  // ela, fica o começo do id, como nos relatórios.
  const operadorDoComprovante = comprovante?.usuarioId
  useEffect(() => {
    if (!operadorDoComprovante || operadorDoComprovante === identidade?.usuarioId
      || identidade?.perfil !== 'ADMIN') return
    let vivo = true
    contas.usuarios().then((lista) => {
      if (vivo) setNomes(new Map(lista.map((usuario) => [usuario.id, usuario.nome])))
    }).catch(() => { /* fica o começo do id */ })
    return () => { vivo = false }
  }, [operadorDoComprovante, identidade?.usuarioId, identidade?.perfil])

  useEffect(() => {
    const clienteId = atual?.clienteId
    if (!clienteId) { definirFaixa(null); return }
    const nome = clientes.find((c) => c.id === clienteId)?.nome ?? clienteId
    // O saldo devedor é calculado no servidor, com todas as Vendas do Cliente. Sem ele, a faixa
    // mostra quem é o Cliente e não inventa um saldo a partir do que está no dispositivo.
    const semSaldo = `Cliente: ${nome} · Saldo devedor indisponível até sincronizar`
    if (!comServidor || atual?.pendenteSincronizacao) {
      definirFaixa(semSaldo)
      return () => definirFaixa(null)
    }
    let vivo = true
    fiado.saldo(clienteId).then(({ saldoDevedor }) => {
      if (vivo) definirFaixa(`Cliente: ${nome} · Saldo devedor: ${moeda.format(saldoDevedor)}`)
    }).catch((falha) => {
      if (!vivo) return
      if (falha instanceof SemConexao || (falha instanceof ErroDaApi && falha.status === 404)) definirFaixa(semSaldo)
      else setErro({ texto: erroDeCadastro(falha), lugar: 'comanda' })
    })
    return () => { vivo = false; definirFaixa(null) }
  }, [atual?.clienteId, atual?.saldoDevedor, atual?.pendenteSincronizacao, clientes, definirFaixa, comServidor])

  // Enquanto a venda aberta está na tela, o Shell não oferece a versão nova: a recarga fecharia a
  // comanda. Concluída, cancelada, deixada pela próxima ou com a tela fechada, o botão volta.
  const vendaAberta = atual?.status === 'ABERTA'
  useEffect(() => {
    definirVendaAberta(vendaAberta)
    return () => definirVendaAberta(false)
  }, [vendaAberta, definirVendaAberta])

  useEffect(() => {
    const procurado = busca.trim()
    if (!sessao || !procurado) return
    let vivo = true
    const espera = window.setTimeout(() => {
      cadastro.buscarProdutos(procurado).then((produtos) => {
        if (!vivo) return
        setResultados(ordenarResultados(produtos, procurado))
        setBuscado(procurado)
        // A busca que respondeu desmente a que falhou antes, como a do catálogo que ainda não estava no aparelho.
        setErro((atual) => atual?.daLeitura === 'busca' ? undefined : atual)
      }).catch((falha) => {
        if (vivo) setErro({ lugar: 'busca', daLeitura: 'busca', texto: falha instanceof SemConexao
          ? 'Sem resposta do servidor na busca. Tente de novo.' : erroDeCadastro(falha) })
      })
    }, 180)
    return () => { vivo = false; window.clearTimeout(espera) }
  }, [busca, sessao])

  useEffect(() => {
    if (!atual?.id || !pixPendente) return
    const vendaId = atual.id
    let vivo = true
    const intervalo = window.setInterval(() => {
      if (!alcancaOServidor() || document.visibilityState !== 'visible') return
      vendas.consultar(vendaId).then((venda) => {
        if (!vivo) return
        setAtual((anterior) => anterior?.id === vendaId ? venda : anterior)
        if (venda.status === 'CONCLUIDA' && sessao) {
          void vendas.daSessao(sessao.id).then((lista) => { if (vivo) setHistorico(lista) })
        }
      }).catch(() => { /* o aviso de rede do shell permanece visível */ })
    }, 5000)
    return () => { vivo = false; window.clearInterval(intervalo) }
  }, [atual?.id, pixPendente, sessao])

  function nomeDoCliente(clienteId: string): string {
    return clientes.find((cliente) => cliente.id === clienteId)?.nome ?? clienteId
  }

  function nomeDoOperador(usuarioId: string): string {
    if (identidade && usuarioId === identidade.usuarioId) return identidade.nome
    return nomes.get(usuarioId) ?? `operador ${usuarioId.slice(0, 8)}`
  }

  async function atualizar(id: string) {
    const venda = await vendas.consultar(id)
    setAtual(venda)
    if (sessao) setHistorico(await vendas.daSessao(sessao.id))
    if (identidade?.perfil === 'ADMIN') setConciliacoes(await vendas.conciliacoesPix())
    return venda
  }

  async function consultarConciliacao(id: string) {
    setErro(undefined)
    try { setConciliacaoSelecionada(await vendas.consultar(id)) }
    catch (falha) { falhar('tela', erroDeCadastro(falha)) }
  }

  function guardarLeitura(leitura: Leitura) {
    fila.current.push(leitura)
    setNaFila(fila.current.length)
  }

  /** Devolve quantas leituras esperavam, para a mensagem da falha dizer que elas não entraram. */
  function esvaziarAFila(): number {
    const descartadas = fila.current.length
    fila.current = []
    setNaFila(0)
    return descartadas
  }

  /**
   * Lança o produto na venda da comanda ou, sem venda aberta nela, numa nova. Serve ao toque num resultado e às
   * leituras da fila; `aoGravar` é o que o toque faz com o campo depois de o item gravar e antes de a comanda aparecer.
   */
  async function lancar(produto: Produto, quantos: number, desconto: number, aoGravar?: () => void) {
    if (!sessao) return
    // A comanda do servidor só para leitura é como a concluída: o item começa outra venda, que fica
    // no aparelho, e ela continua na lista para retomar quando o servidor responder.
    const continua = atual?.status === 'ABERTA' && !comandaSoLeitura
    if (!continua) { setComprovante(undefined); setTroco(undefined) }
    let id: string | undefined
    let intencao: IntencaoOnline | undefined
    try {
      id = continua ? atual.id : (await vendas.iniciar(sessao.id)).id
      // O mesmo produto, na mesma quantidade e com o mesmo desconto, depois de uma resposta perdida, leva o mesmo
      // id, e o servidor responde o item que já gravou. O id sai quando o lançamento responde ou quando a comanda
      // relida mostra o item: daí em diante, o mesmo código é mais uma unidade.
      intencao = await intencaoOnline(`/api/vendas/${id}/itens`,
        { produtoId: produto.id, quantidade: quantos, desconto }, identidade)
      await vendas.adicionarItem(id, produto, quantos, desconto, intencao.id)
      intencao.confirmar()
      aoGravar?.()
      buscaRef.current?.focus()
      await atualizar(id)
    } catch (falha) {
      // A falha para a fila: a comanda precisa ser conferida antes de seguir e, sem resposta, a leitura seguinte
      // começaria outra venda no aparelho, longe dos itens que vieram antes.
      falhar('busca', erroDaComanda(falha) + codigosDescartados(esvaziarAFila()))
      conferirSeSemResposta(falha, id, intencao)
      // Sem resposta, a venda nova toma o lugar da que estava na comanda, já concluída: é a nova que a releitura
      // mostra quando o servidor voltar.
      if (id && !continua && falha instanceof SemConexao) setAtual(undefined)
      if (id && !atual) await atualizar(id).catch(() => undefined)
    }
  }

  async function adicionar(produto: Produto) {
    if (!sessao) return
    const desconto = descontoDigitado(descontoItem)
    if (desconto === null) {
      falhar('busca', 'Informe o desconto do item com até duas casas decimais.')
      return
    }
    const recusa = recusaDaQuantidade(numero(quantidade))
    if (recusa) {
      falhar('busca', recusa)
      return
    }
    const tocado = busca
    setOcupado(true); setErro(undefined)
    try {
      await lancar(produto, numero(quantidade), desconto, () => {
        // O termo buscado sai junto com os resultados: guardado, o mesmo código passado de novo pareceria
        // já buscado e sem resultado, e o Enter do leitor não lançaria nada. E sai antes de a comanda aparecer:
        // a lista ainda na tela empurraria o rodapé, e a rolagem que mostra o botão principal desceria a mais.
        // Sai só se o campo ainda tiver o termo tocado: o que o leitor digitou nele durante o lançamento é outra leitura.
        if (buscaRef.current?.value === tocado) { setBusca(''); setResultados([]); setBuscado('') }
        setQuantidade('1'); setDescontoItem('')
      })
    } finally { setOcupado(false) }
  }

  /**
   * O termo da fila que não acha produto, ou acha vários, não lança nada, e as leituras seguintes continuam. Com vários,
   * ele volta ao campo com os resultados, como o Enter que não lança, se nada foi lido depois dele: é quem digita o
   * nome e dá Enter antes de a lista aparecer. O código sem produto não volta, porque a leitura seguinte colaria nele.
   */
  function naoLancada(leitura: Leitura, encontrados: Produto[]) {
    if (encontrados.length === 0) {
      falhar('busca', `Nenhum produto encontrado para ${leitura.termo}.`)
      return
    }
    if (fila.current.length === 0 && buscaRef.current?.value === '') {
      setBusca(leitura.termo); setQuantidade(leitura.quantidade); setDescontoItem(leitura.descontoItem)
      setResultados(ordenarResultados(encontrados, leitura.termo)); setBuscado(leitura.termo)
      return
    }
    falhar('busca', `Mais de um produto encontrado para ${leitura.termo}. Nada foi lançado.`)
  }

  /** Uma leitura da fila: o termo que chegou antes da resposta da busca é buscado agora, na vez dele. */
  async function lancarLeitura(leitura: Leitura) {
    setOcupado(true)
    try {
      let produto = leitura.produto
      if (!produto) {
        let encontrados: Produto[]
        try {
          encontrados = await cadastro.buscarProdutos(leitura.termo)
        } catch (falha) {
          falhar('busca', (falha instanceof SemConexao ? 'Sem resposta do servidor na busca. Tente de novo.'
            : erroDeCadastro(falha)) + codigosDescartados(esvaziarAFila()))
          return
        }
        produto = escolhidoPeloEnter(encontrados, leitura.termo)
        if (!produto) { naoLancada(leitura, encontrados); return }
      }
      // O desconto foi conferido no Enter, e a leitura com desconto inválido nem entra na fila.
      await lancar(produto, numero(leitura.quantidade), descontoDigitado(leitura.descontoItem) ?? 0)
    } finally { setOcupado(false) }
  }

  // A leitura guardada entra quando a comanda fica livre, uma por vez e na ordem do Enter. O efeito roda depois do
  // desenho em que a anterior terminou, com a venda dela já relida: o segundo item vai para a venda que o primeiro
  // abriu, sem abrir outra, e a leitura que chegou durante Receber e concluir começa a venda seguinte.
  useEffect(() => {
    if (ocupado || naFila === 0) return
    const leitura = fila.current.shift()
    setNaFila(fila.current.length)
    if (leitura) void lancarLeitura(leitura)
  }, [ocupado, naFila])

  async function remover(itemId: string) {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try { await vendas.removerItem(atual.id, itemId); await atualizar(atual.id) }
    catch (falha) { falhar('comanda', erroDaComanda(falha)); conferirSeSemResposta(falha, atual.id) }
    finally { setOcupado(false) }
  }

  async function descontar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!atual) return
    const desconto = descontoDigitado(descontoVenda)
    if (desconto === null) {
      falhar('comanda', 'Informe o desconto da venda com até duas casas decimais.')
      return
    }
    setOcupado(true); setErro(undefined)
    try {
      await vendas.descontar(atual.id, desconto); await atualizar(atual.id)
      // O que foi digitado já está gravado: o campo passa a mostrar o valor da venda, 0,5 como 0,50.
      setDescontoDaVendaDigitado(undefined)
    }
    catch (falha) { falhar('comanda', erroDaComanda(falha)); conferirSeSemResposta(falha, atual.id) }
    finally { setOcupado(false) }
  }

  async function vincularCliente(clienteId: string) {
    if (!atual || !clienteId) return
    setOcupado(true); setErro(undefined)
    try {
      await vendas.vincularCliente(atual.id, clienteId); await atualizar(atual.id)
      setBuscaCliente('')
    }
    catch (falha) { falhar('comanda', erroDaComanda(falha)); conferirSeSemResposta(falha, atual.id) }
    finally { setOcupado(false) }
  }

  async function tirarCliente() {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try {
      await vendas.desvincularCliente(atual.id); await atualizar(atual.id)
      setBuscaCliente('')
    }
    catch (falha) { falhar('comanda', erroDaComanda(falha)); conferirSeSemResposta(falha, atual.id) }
    finally { setOcupado(false) }
  }

  async function pagar(evento: { preventDefault(): void }, concluirJunto = false) {
    evento.preventDefault()
    if (!atual) return
    // O zero e o recebido abaixo da parcela o servidor também recusa; a tela diz antes, sem a ida.
    const valor = valorDaParcela
    if (valor === null) {
      falhar('comanda', 'Informe o valor da parcela com até duas casas decimais.')
      return
    }
    if (valor <= 0) {
      falhar('comanda', 'Informe um valor da parcela maior que zero.')
      return
    }
    const recebido = formaEscolhida !== 'DINHEIRO' ? undefined
      : valorRecebido ? lerDinheiro(valorRecebido) : valor
    if (recebido === null) {
      falhar('comanda', 'Informe o valor recebido com até duas casas decimais.')
      return
    }
    if (recebido !== undefined && recebido < valor) {
      falhar('comanda', 'O valor recebido é menor que o da parcela.')
      return
    }
    // O campo fica escondido fora do cartão, e o que foi digitado nele antes da troca não vai junto.
    const nsuDaParcela = formaEscolhida === 'CARTAO' ? nsu : undefined
    setOcupado(true); setErro(undefined)
    let intencao: IntencaoOnline | undefined
    let parcelaRespondida = false
    try {
      if (opcaoEscolhida === 'PIX_INTEGRADO') {
        const parcela = await vendas.cobrarPix(atual.id, tentativaPix(atual.id, valor), valor)
        if (parcela.pix?.estado === 'DISPONIVEL') {
          sessionStorage.removeItem(`ecrx-caixa-pix-${atual.id}`)
        }
        setValorPagamento('')
        await atualizar(atual.id)
        return
      }
      // O NSU entra já sem os espaços das pontas, como o servidor compara o reenvio.
      intencao = await intencaoOnline(`/api/vendas/${atual.id}/pagamentos`,
        { forma: formaEscolhida, valor, valorRecebido: recebido ?? null, nsu: normalizarNsu(nsuDaParcela) },
        identidade)
      const resultado = await vendas.pagar(atual.id, formaEscolhida, valor, recebido, intencao.id,
        nsuDaParcela)
      parcelaRespondida = true
      setTroco(resultado.troco)
      if (concluirJunto) await concluir(atual.id)
      else await atualizar(atual.id)
      intencao.confirmar()
      setValorPagamento(''); setValorRecebido(''); setNsu('')
    } catch (falha) {
      // Com a parcela respondida, a falta de resposta depois dela deixa a conclusão em aberto. O id da parcela continua
      // guardado mesmo assim: se a comanda antiga oferecer de novo o recebimento, o reenvio não grava outra.
      falhar('comanda', falha instanceof SemConexao && parcelaRespondida && concluirJunto
        ? PARCELA_SEM_CONCLUSAO : erroDaComanda(falha))
      conferirSeSemResposta(falha, atual.id, intencao)
      const atualizada = await atualizar(atual.id).catch(() => undefined)
      const idDaIntencao = intencao?.id
      if (idDaIntencao && atualizada?.parcelas.some((parcela) => parcela.id === idDaIntencao)) {
        intencao?.confirmar()
        setValorPagamento(''); setValorRecebido(''); setNsu('')
      }
    } finally { setOcupado(false) }
  }

  async function retentarPix(parcelaId: string, valor: number) {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try {
      await vendas.cobrarPix(atual.id, parcelaId, valor)
      await atualizar(atual.id)
    } catch (falha) {
      falhar('comanda', erroDaComanda(falha))
      conferirSeSemResposta(falha, atual.id)
      await atualizar(atual.id).catch(() => undefined)
    } finally { setOcupado(false) }
  }

  async function concluir(id: string) {
    await vendas.concluir(id)
    await atualizar(id)
    setComprovante(await vendas.comprovante(id))
    setSegundaViaEm(undefined)
    // A próxima venda começa pela busca sem um toque para voltar a ela, como depois de lançar item.
    voltarABusca()
    // O troco é o que se lê com o cliente esperando: no alto da página, ele poderia ficar cortado.
    if (celular) mostrarORodape(rodapeRef.current)
  }

  async function concluirPendente() {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try { await concluir(atual.id) }
    catch (falha) {
      falhar('comanda', falha instanceof SemConexao ? CONCLUSAO_SEM_RESPOSTA : erroDaComanda(falha))
      conferirSeSemResposta(falha, atual.id)
    }
    finally { setOcupado(false) }
  }

  async function cancelar() {
    if (!atual) return
    if (!(await confirmar(perguntaDoCancelamento(atual)))) return
    setOcupado(true); setErro(undefined)
    try {
      await vendas.cancelar(atual.id); await atualizar(atual.id); setComprovante(undefined)
      voltarABusca()
    }
    catch (falha) { falhar('comanda', erroDaComanda(falha)); conferirSeSemResposta(falha, atual.id) }
    finally { setOcupado(false) }
  }

  async function selecionar(resumo: ResumoDaVenda, lugar: LugarDoErro = 'historico') {
    setErro(undefined); setTroco(undefined); setComprovante(undefined); setBuscaCliente('')
    try {
      await atualizar(resumo.id)
      if (resumo.status === 'CONCLUIDA') {
        setComprovante(await vendas.comprovante(resumo.id))
        setSegundaViaEm(new Date())
      }
    } catch (falha) { falhar(lugar, erroDeCadastro(falha)) }
  }

  /** A venda retomada pelo aviso continua pela busca, como depois de lançar um item. */
  async function retomar(resumo: ResumoDaVenda) {
    await selecionar(resumo, 'comanda')
    buscaRef.current?.focus()
  }

  async function copiarPix(parcelaId: string, codigo: string) {
    try {
      await navigator.clipboard.writeText(codigo)
      setCopiado(parcelaId)
    } catch {
      falhar('comanda', 'Não foi possível copiar. Selecione o código e copie à mão.')
    }
  }

  /**
   * O Enter que o leitor de código de barras manda depois do código. A leitura sai do campo na hora, com a
   * quantidade e o desconto daquele instante, e espera a comanda ficar livre: a seguinte começa no campo vazio, sem
   * colar no código anterior nem se perder enquanto ele lança. Com os resultados do termo já à vista, o Enter só
   * guarda o produto do código igual ou o único resultado; com vários achados pelo nome, ou nenhum, quem escolhe é
   * a pessoa. O leitor digita mais rápido que a espera da busca, e então o termo vai sem produto, buscado na vez dele.
   */
  function lerPeloEnter() {
    const termoLido = busca.trim()
    if (!termoLido || !sessao) return
    if (descontoDigitado(descontoItem) === null) {
      falhar('busca', 'Informe o desconto do item com até duas casas decimais.')
      return
    }
    // A quantidade que o servidor recusaria é recusada antes, com o mesmo texto: com a comanda concluída ou vazia, a
    // ida abriria antes da recusa uma venda nova, que ficaria aberta e vazia no caixa.
    const recusa = recusaDaQuantidade(numero(quantidade))
    if (recusa) {
      falhar('busca', recusa)
      return
    }
    let produto: Produto | undefined
    if (buscado === termoLido) {
      produto = escolhidoPeloEnter(resultados, termoLido)
      if (!produto) return
    }
    // A primeira leitura com a comanda livre começa sem o aviso da anterior; a que chega com outra em curso deixa à
    // vista o aviso do código que ficou de fora.
    if (!comandaOcupada) setErro(undefined)
    guardarLeitura({ termo: termoLido, quantidade, descontoItem, produto })
    setBusca(''); setResultados([]); setBuscado(''); setQuantidade('1'); setDescontoItem('')
  }

  function mudarBusca(texto: string) {
    const separado = separarMultiplicador(texto)
    if (separado.quantidade) setQuantidade(separado.quantidade)
    setBusca(separado.termo)
    if (!separado.termo.trim()) { setResultados([]); setBuscado('') }
  }

  function novaVenda() {
    setAtual(undefined); setComprovante(undefined); setTroco(undefined); setForma('DINHEIRO'); setNsu('')
    setErro(undefined); setCopiado(undefined); setBuscaCliente('')
    // Quem começa outra venda deixou a anterior para trás: a volta do servidor não a traz de novo para a comanda.
    setConferirNaVolta(undefined)
    voltarABusca()
  }

  return <section className="pdv">
    <TituloComAjuda titulo="Venda" ajuda={AJUDA.vender} />
    <Mensagem tipo="erro" texto={erroEm('tela')} />
    {identidade?.perfil === 'ADMIN' && conciliacoes.length > 0 &&
      <section className="pdv__painel" aria-label="Conciliações Pix">
        <h3>Pix recebido: conciliação necessária</h3>
        <p>Confira o recebimento. Venda cancelada com Pix pago exige devolução fora do sistema; este aplicativo não verifica se ela aconteceu. Pix após fechamento de caixa também exige conciliação.</p>
        <ul>{conciliacoes.map((pendencia) => <li key={pendencia.vendaId}>
          <button type="button" className="botao botao--secundario" onClick={() => void consultarConciliacao(pendencia.vendaId)}>
            Venda {pendencia.vendaId.slice(0, 8)} · {ROTULO_DA_VENDA[pendencia.status]} · Pix {moeda.format(pendencia.valorPix)}
          </button>
        </li>)}</ul>
        {conciliacaoSelecionada && <div>
          <p>Venda {conciliacaoSelecionada.id} · {ROTULO_DA_VENDA[conciliacaoSelecionada.status]}</p>
          <ul>{conciliacaoSelecionada.parcelas.filter((parcela) => parcela.pix).map((parcela) =>
            <li key={parcela.id}>Pix {parcela.pix?.txid}: {moeda.format(parcela.valor)} · {parcela.status.toLowerCase()}</li>)}</ul>
        </div>}
      </section>}
    {/* Sem caixa, ir ao caixa é a única ação da tela: um botão, não um link solto no texto. */}
    {carregando ? <p>Carregando...</p> : !sessao ? <>
      <p>Abra o seu caixa para vender.</p>
      <p><Link className="botao" to="/caixa">Ir ao caixa</Link></p>
    </>
      : <div className="pdv__grade">
        <section className="pdv__painel pdv__painel--busca">
          {/* No celular o título sai da vista, e a busca sobe para perto do alto da tela, longe do
              teclado virtual; o leitor de tela continua achando o painel por ele. */}
          <h3 className="pdv__titulo-da-busca">Busca de produtos</h3>
          <div className="pdv__busca">
            <label>Produto por nome ou código
              <input ref={buscaRef} autoFocus value={busca} onChange={(evento) => mudarBusca(evento.target.value)}
                onKeyDown={(evento) => {
                  if (evento.key !== 'Enter') return
                  evento.preventDefault()
                  lerPeloEnter()
                }}
                placeholder="Digite ou passe o código" />
            </label>
            <label>Quantidade
              <input type="number" min="0.001" step="0.001" value={quantidade}
                onChange={(evento) => setQuantidade(evento.target.value)} />
            </label>
            <p className="pdv__ajuda">Também aceita 3* no início da busca para lançar três unidades.</p>
          </div>
          {identidade?.perfil === 'ADMIN' &&
            <CampoDeDinheiro rotulo="Desconto deste item" placeholder="0,00" valor={descontoItem}
              aoMudar={setDescontoItem} />}
          <Mensagem tipo="erro" texto={erroEm('busca')} />
          {termo && buscado === termo && resultados.length === 0 &&
            <p className="pdv__ajuda" role="status">Nenhum produto encontrado para {termo}.</p>}
          {resultados.length > 0 && <ul className="pdv__resultados">
            {resultados.map((produto) => <li key={produto.id}>
              <button type="button" className="botao botao--secundario" disabled={comandaOcupada}
                onClick={() => void adicionar(produto)}>
                {/* O preço é o da unidade de venda: 16,90 o quilo não se confunde com o da unidade. */}
                {produto.nome} · {moeda.format(produto.preco)}{produto.unidade && `/${produto.unidade}`}
                {produto.codigo && ` · ${produto.codigo}`}
              </button>
            </li>)}
          </ul>}
        </section>

        {/* O comprovante vem logo abaixo da comanda que o gerou: depois da lista do caixa, ficava a
            milhares de pixels, com o cliente esperando. */}
        <div className="pdv__coluna">
        <section className="pdv__painel" aria-busy={comandaOcupada}>
          <div className="pdv__topo"><h3>Comanda</h3>
            <span className="pdv__ajuda" aria-live="polite">{comandaOcupada ? 'Registrando...' : ''}</span>
            {/* Com a comanda só para leitura, Nova venda é a ação principal e fica no rodapé. */}
            {!comandaSoLeitura &&
              <button className="botao botao--secundario" type="button" onClick={novaVenda}>Nova venda</button>}
          </div>
          {/* Começar outra continua possível: o aviso só lembra da venda que ficaria para trás. */}
          {avisarAbertas && <div className="pdv__aberta">
            <p className="mensagem-info">{avisoDasAbertas(abertasNoCaixa)}</p>
            <button className="botao botao--secundario" type="button" disabled={comandaOcupada}
              onClick={() => void retomar(abertasNoCaixa[0])}>Retomar</button>
          </div>}
          {!atual ? <p>Busque e escolha o primeiro produto para iniciar.</p> : <>
            {/* O cancelamento fica no alto, junto da venda que ele desfaz, e longe dos botões de
                pagar e concluir: depois de concluir a comanda encolhe, e o toque seguinte cairia nele. */}
            <div className="pdv__topo pdv__venda">
              <p>Venda {atual.id.slice(0, 8)} · {ROTULO_DA_VENDA[atual.status]}</p>
              {atual.status !== 'CANCELADA' && !doDispositivo && comServidor && <button className="botao botao--perigo"
                type="button" disabled={comandaOcupada} onClick={() => void cancelar()}>Cancelar venda</button>}
            </div>
            {atual.status !== 'CANCELADA' && doDispositivo && <p className="pdv__ajuda">{CANCELAR_DEPOIS_DE_SINCRONIZAR}</p>}
            {doDispositivo && !registradaNoRodape && <p className="mensagem-info" role="status">Registrada neste aparelho. Aguardando sincronização com o servidor.</p>}
            {recusaDaComanda && <p className="mensagem-alerta" role="status">
              Feita sem rede, esta venda não foi concluída no servidor.{recusaDaComanda.motivo && ` ${recusaDaComanda.motivo}`}
            </p>}
            {atual.status === 'CANCELADA' && atual.parcelas.some((p) => p.pix && p.status === 'CONFIRMADO') &&
              <p className="mensagem-info" role="status">Pix integrado recebido. A devolução precisa ser feita fora do sistema e ainda exige conciliação; este aplicativo não verifica se ela aconteceu.</p>}
            {/* O cliente se acha pelo nome, como o produto: uma lista com todos não cabe no balcão,
                e a tela virada para o cliente mostraria o nome de cada um. */}
            {atual.clienteId && <div className="pdv__topo">
              <p>Cliente: {nomeDoCliente(atual.clienteId)}</p>
              {editavel && !comFiado && <button className="botao botao--secundario"
                type="button" disabled={comandaOcupada} onClick={() => void tirarCliente()}>Tirar o cliente</button>}
            </div>}
            {/* A conclusão com fiado exige cliente, e a parcela lançada não se desfaz: sem ele, a
                venda ficaria sem como concluir. */}
            {atual.clienteId && editavel && comFiado &&
              <p className="pdv__ajuda">Com parcela fiado, o cliente só pode ser trocado.</p>}
            {editavel && <>
              {/* Opcional para os dois perfis: o operador não vende fiado, e o cliente se vincula
                  também sem ele. A conclusão no fiado sem cliente já é recusada com mensagem. */}
              <label>{atual.clienteId ? 'Trocar o cliente' : 'Cliente (opcional)'}
                <input type="search" value={buscaCliente} placeholder="Digite o nome" autoComplete="off"
                  onChange={(evento) => setBuscaCliente(evento.target.value)} />
              </label>
              {termoCliente && clientesEncontrados.length === 0 &&
                <p className="pdv__ajuda" role="status">Nenhum cliente encontrado para {termoCliente}.</p>}
              {clientesEncontrados.length > 0 && <ul className="pdv__resultados">
                {clientesEncontrados.map((cliente) => <li key={cliente.id}>
                  <button type="button" className="botao botao--secundario" disabled={comandaOcupada}
                    onClick={() => void vincularCliente(cliente.id)}>{cliente.nome}</button>
                </li>)}
              </ul>}
            </>}
            {atual.itens.length === 0 ? <p>Nenhum item lançado.</p> : <ul className="pdv__itens">
              {atual.itens.map((item) => <li key={item.id}>
                <span>{quantidadeComUnidade(item.quantidade, item.unidade)} × {item.nome} · {moeda.format(item.subtotal)}
                  {item.desconto > 0 && ` (desconto ${moeda.format(item.desconto)})`}</span>
                {editavel && <button className="botao botao--secundario" type="button"
                  disabled={comandaOcupada} onClick={() => void remover(item.id)}>Remover</button>}
              </li>)}
            </ul>}
            {/* Com desconto da venda, a comanda diz de onde sai o Total do rodapé, como o comprovante: quem confere com o
                cliente e quem retoma a venda veem o desconto dado. Sem ele, uma linha de R$ 0,00 faria o cliente perguntar. */}
            {atual.descontoDaVenda > 0 && <div className="pdv__soma">
              <p className="pdv__linha"><span>Soma dos itens:</span> <span>{moeda.format(somaDosItens(atual))}</span></p>
              <p className="pdv__linha"><span>Desconto da venda:</span> <span>{moeda.format(atual.descontoDaVenda)}</span></p>
            </div>}
            {identidade?.perfil === 'ADMIN' && editavel && <form onSubmit={(evento) => void descontar(evento)} className="pdv__desconto">
              <div className="pdv__desconto-campo">
                <CampoDeDinheiro rotulo="Desconto da venda" placeholder="0,00" valor={descontoVenda}
                  aoMudar={(texto) => setDescontoDaVendaDigitado({ vendaId: atual.id, texto })} />
              </div>
              <button className="botao botao--secundario" disabled={comandaOcupada}>Aplicar</button>
            </form>}
            {atual.saldoDevedor > 0 && <p><strong>Saldo devedor desta venda: {moeda.format(atual.saldoDevedor)}</strong></p>}
            {atual.parcelas.length > 0 && <ul className="pdv__parcelas">
              {atual.parcelas.map((parcela) => <li key={parcela.id}>{ROTULO_DA_FORMA[parcela.forma]}: {moeda.format(parcela.valor)}
                {parcela.troco > 0 && ` · troco ${moeda.format(parcela.troco)}`}
                {parcela.nsu && ` · NSU ${parcela.nsu}`}
                {parcela.pix && <div>
                  <strong>Pix {parcela.status === 'PENDENTE' ? 'aguardando confirmação' : parcela.status.toLowerCase()}</strong>
                  {' · '}{parcela.status === 'RECUSADO' ? 'cobrança removida'
                    : `cobrança ${ROTULO_DA_COBRANCA_PIX[parcela.pix.estado]}`}
                  {' · '}vence {dataHora.format(new Date(parcela.pix.expiraEm))}
                  {parcela.status === 'PENDENTE' && Date.now() >= Date.parse(parcela.pix.expiraEm) &&
                    <p>Prazo do QR encerrado. Aguarde a conciliação antes de tentar outro Pix.</p>}
                  {parcela.status === 'PENDENTE' && parcela.pix.copiaECola
                    && Date.now() < Date.parse(parcela.pix.expiraEm) &&
                    <>
                      <div className="pdv__qr" role="img" aria-label="QR Pix da cobrança">
                        <QRCodeSVG value={parcela.pix.copiaECola} size={220} />
                      </div>
                      <p>Pix copia e cola: <code className="pdv__codigo-pix">{parcela.pix.copiaECola}</code>
                        {' '}<button type="button" className="botao botao--secundario"
                          onClick={() => void copiarPix(parcela.id, parcela.pix?.copiaECola ?? '')}>
                          {copiado === parcela.id ? 'Copiado' : 'Copiar'}</button></p>
                    </>}
                  {parcela.status === 'PENDENTE' && parcela.pix.estado !== 'DISPONIVEL' && editavel &&
                    <button type="button" className="botao botao--secundario" disabled={comandaOcupada}
                      onClick={() => void retentarPix(parcela.id, parcela.valor)}>Retentar cobrança</button>}
                </div>}
              </li>)}
            </ul>}
            {lancando && <form className="pdv__pagamento" onSubmit={(evento) => void pagar(evento)}>
              <h4>Pagamento</h4>
              <label>Forma
                <select value={opcaoEscolhida}
                  onChange={(evento) => setForma(evento.target.value as OpcaoDePagamento)}>
                  <option value="DINHEIRO">Dinheiro</option>
                  <option value="PIX_MANUAL">Pix manual</option>
                  {pixIntegradoPermitido && <option value="PIX_INTEGRADO">Pix integrado</option>}
                  <option value="CARTAO">Cartão manual</option>
                  {fiadoPermitido && <option value="FIADO">Fiado</option>}
                </select>
              </label>
              <CampoDeDinheiro rotulo="Valor da parcela" valor={valorPagamento} aoMudar={setValorPagamento}
                placeholder={valorNoCampo(atual.faltaPagar)} />
              {opcaoEscolhida === 'PIX_MANUAL' && <PixManual identidade={identidade} valor={valorDaParcela} />}
              {formaEscolhida === 'DINHEIRO' && <>
                <CampoDeDinheiro rotulo="Valor recebido em dinheiro" valor={valorRecebido}
                  aoMudar={setValorRecebido}
                  placeholder={valorDaParcela !== null ? valorNoCampo(valorDaParcela) : undefined} />
                {/* A nota abaixo da parcela fica desligada: o servidor recusaria o recebido menor. */}
                <div className="pdv__notas" role="group" aria-label="Nota recebida">
                  {NOTAS.map((nota) => <button key={nota} type="button" className="botao botao--secundario"
                    disabled={comandaOcupada || (valorDaParcela !== null && nota < valorDaParcela)}
                    onClick={() => setValorRecebido(valorNoCampo(nota))}>R$ {nota}</button>)}
                </div>
              </>}
              {/* Sem limite de tamanho no campo: o navegador cortaria o número colado com espaços
                  em volta, que o servidor aceita depois de tirar os espaços. */}
              {formaEscolhida === 'CARTAO' && <label>
                {identidade?.nsuObrigatorio ? 'NSU do comprovante' : 'NSU do comprovante (opcional)'}
                <input value={nsu} autoComplete="off" required={identidade?.nsuObrigatorio === true}
                  onChange={(evento) => setNsu(evento.target.value)} />
              </label>}
              <div className="pdv__acoes">
                <button className="botao botao--secundario" disabled={comandaOcupada}>Registrar parcela</button>
              </div>
            </form>}
          </>}
          {/* O rodapé fica preso ao fim da tela enquanto a comanda está nela: total, o que falta, o
              troco e o botão principal não dependem de rolar, por mais itens que a venda tenha. O
              botão principal não envia o formulário, e o Enter num campo continua registrando só a
              parcela. A mensagem de erro fica aqui também, onde o rodapé não a cobre. */}
          {atual ? <div className="pdv__rodape" ref={rodapeRef}>
            <Mensagem tipo="erro" texto={erroEm('comanda')} />
            {comandaSoLeitura && <p className="mensagem-info" role="status">Esta venda está no servidor e continua quando a rede voltar. Para vender agora, comece uma nova venda.</p>}
            {/* O contêiner fica montado desde que a comanda abre: uma região que nasce junto com o texto pode não ser anunciada. */}
            {celular && <div className="pdv__regiao" role="status">
              {registradaNoRodape && <p className="mensagem-info">Registrada neste aparelho.</p>}
              {ultimoItem && <p className="pdv__ultimo">
                <span className="pdv__ultimo-nome">Último: {quantidadeComUnidade(ultimoItem.quantidade, ultimoItem.unidade)} × {ultimoItem.nome}</span>
                <span>{moeda.format(ultimoItem.subtotal)}</span>
              </p>}
            </div>}
            <p className="pdv__linha pdv__total"><span>Total:</span> <span>{moeda.format(atual.total)}</span></p>
            {/* Sem parcela, o que falta é o total: a linha só repetiria o número de cima, e o rodapé mais
                baixo deixa mais comanda à vista no celular, onde os dois dividem a tela. */}
            {atual.status === 'ABERTA' && atual.parcelas.length > 0 && <p className="pdv__linha">
              {atual.pago > 0 && <span>Parcelas lançadas: {moeda.format(atual.pago)}</span>}
              <span>Falta lançar: {moeda.format(atual.faltaPagar)}</span>
            </p>}
            {/* Enquanto se digita o recebido, a prévia toma o lugar do troco já registrado, que o
                cliente já levou. */}
            {previaDoTroco !== undefined && previaDoTroco >= 0 && <p className="pdv__troco pdv__troco--previa">
              <span>Troco previsto:</span> <strong>{moeda.format(previaDoTroco)}</strong>
              <span className="pdv__nota">Prévia: vale o troco confirmado ao registrar.</span>
            </p>}
            {previaDoTroco !== undefined && previaDoTroco < 0 && <p className="pdv__troco pdv__troco--previa">
              <span className="pdv__nota">O valor recebido é menor que o da parcela.</span>
            </p>}
            {previaDoTroco === undefined && troco !== undefined && <p className="pdv__troco" role="status">
              <span>Troco:</span> <strong>{moeda.format(troco)}</strong>
            </p>}
            {receberEConcluir && <button className="botao botao--largo" type="button" disabled={comandaOcupada}
              onClick={(evento) => void pagar(evento, true)}>
              {formaEscolhida === 'FIADO' ? 'Registrar fiado e concluir' : 'Receber e concluir'}</button>}
            {concluirSemPagar && <button className="botao botao--largo" type="button" disabled={comandaOcupada}
              onClick={() => void concluirPendente()}>Concluir venda</button>}
            {comandaSoLeitura && <button className="botao botao--largo" type="button" onClick={novaVenda}>
              Nova venda</button>}
          </div> : <Mensagem tipo="erro" texto={erroEm('comanda')} />}
        </section>

        {comprovante && identidade && <Comprovante comprovante={comprovante} negocio={identidade.nomeNegocio}
          operador={nomeDoOperador(comprovante.usuarioId)} segundaViaEm={segundaViaEm}
          aoFalhar={(mensagem) => falhar('comprovante', mensagem)} />}
        {/* Fora do papel branco do comprovante, onde o texto claro da mensagem não se leria. */}
        <Mensagem tipo="erro" texto={erroEm('comprovante')} />
        </div>

        <section className="pdv__painel pdv__historico">
          <h3>Vendas deste caixa</h3>
          {conexao !== 'com-servidor' && historico.length > 0 && <p className="pdv__ajuda">
            {motivoSemServidor(conexao)}, a lista mostra as vendas registradas neste aparelho.</p>}
          <Mensagem tipo="erro" texto={erroEm('historico')} />
          {historico.length === 0 ? <p>{conexao === 'com-servidor' ? 'Nenhuma venda neste caixa.'
            : vendasSemServidor(conexao)}</p> : <ul id={idDaLista}>
            {vendasNaLista.map((resumo) => <li key={resumo.id}>
              {abreAVenda(resumo) ? <button type="button" className="botao botao--secundario"
                onClick={() => void selecionar(resumo)}>
                {quando(resumo.criadoEm)} · {ROTULO_DA_VENDA[resumo.status]} · {moeda.format(resumo.total)}
                {formasDaLinha(resumo)}
                {resumo.status === 'ABERTA' && (resumo.pendenteSincronizacao ? ' · retomar' : ' · retomar ou cancelar')}
                {resumo.pendenteSincronizacao && ' · pendente de sincronização'}
              </button> : <p className="pdv__venda-de-outro">
                {quando(resumo.criadoEm)} · {ROTULO_DA_VENDA[resumo.status]} · {moeda.format(resumo.total)}
                {formasDaLinha(resumo)} · do administrador
              </p>}
            </li>)}
          </ul>}
          {historico.length > VENDAS_A_VISTA && <button type="button" className="botao botao--secundario"
            aria-expanded={todasAsVendas} aria-controls={idDaLista}
            onClick={() => setTodasAsVendas(!todasAsVendas)}>
            {todasAsVendas ? 'Ver só as dez mais recentes' : `Ver todas (${historico.length})`}</button>}
        </section>
      </div>}

    {dialogoDeConfirmacao}
  </section>
}
