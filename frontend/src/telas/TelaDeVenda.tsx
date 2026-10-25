import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { QRCodeSVG } from 'qrcode.react'
import { cadastro, type Cliente, type Produto } from '../api/cadastro'
import type { SessaoCaixa } from '../api/caixa'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { contas } from '../api/contas'
import { fiado } from '../api/fiado'
import { intencaoOnline, type IntencaoOnline } from '../api/intencaoOnline'
import type { Comprovante as DadosDoComprovante, ConciliacaoPix, FormaPagamento, ResumoDaVenda, Venda } from '../api/vendas'
import { dataHora, lerDinheiro, moeda, valorNoCampo } from '../formatos'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { normalizarNsu } from '../offline/raizDaVenda'
import { criarVendaLocal } from '../offline/vendaLocal'
import { useContextoDoShell } from '../shell/ContextoDoShell'
import { useSincronizacao } from '../shell/sincronizacao'
import { useOnline } from '../shell/useOnline'
import { useSessao } from '../sessao/useSessao'
import { CampoDeDinheiro } from './CampoDeDinheiro'
import { erroDeCadastro } from './erroDeCadastro'
import { filtrarPorNome } from './filtroPorNome'
import { Mensagem } from './Mensagem'
import { PixManual } from './QrDoPix'
import { ROTULO_DA_COBRANCA_PIX, ROTULO_DA_FORMA, ROTULO_DA_VENDA } from './rotulos'
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
 * Sem resposta, a operação pode ter chegado ao servidor mesmo assim; a comanda relida mostra se ela
 * valeu, e repetir sem olhar poderia lançar duas vezes.
 */
function erroDaComanda(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem resposta do servidor. Confira a comanda antes de repetir.'
  return erroDeCadastro(falha)
}

function tentativaPix(vendaId: string, valor: number): string {
  const chave = `fechadia-pix-${vendaId}`
  const guardada = sessionStorage.getItem(chave)
  if (guardada) {
    const tentativa = JSON.parse(guardada) as { id: string; valor: number }
    if (tentativa.valor === valor) return tentativa.id
  }
  const id = crypto.randomUUID()
  sessionStorage.setItem(chave, JSON.stringify({ id, valor }))
  return id
}

/** O multiplicador na busca usa o mesmo estado do campo visível, para evitar duas quantidades. */
function separarMultiplicador(texto: string): { termo: string; quantidade?: string } {
  const encontrado = texto.match(/^(\d+(?:[.,]\d{1,3})?)\*(.*)$/)
  return encontrado ? { termo: encontrado[2], quantidade: encontrado[1].replace(',', '.') }
    : { termo: texto }
}

export function TelaDeVenda() {
  const { identidade } = useSessao()
  const { definirFaixa, definirVendaAberta } = useContextoDoShell()
  const { rodada } = useSincronizacao()
  const buscaRef = useRef<HTMLInputElement>(null)
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
  const [descontoVenda, setDescontoVenda] = useState('')
  const [resultados, setResultados] = useState<Produto[]>([])
  const [forma, setForma] = useState<OpcaoDePagamento>('DINHEIRO')
  const [valorPagamento, setValorPagamento] = useState('')
  const [valorRecebido, setValorRecebido] = useState('')
  const [nsu, setNsu] = useState('')
  const [troco, setTroco] = useState<number>()
  const [copiado, setCopiado] = useState<string>()
  const [nomes, setNomes] = useState<Map<string, string>>(() => new Map())
  const [erro, setErro] = useState<{ texto: string; lugar: LugarDoErro }>()
  const [ocupado, setOcupado] = useState(false)
  const [carregando, setCarregando] = useState(true)
  const [confirmar, dialogoDeConfirmacao] = useConfirmacao()
  const online = useOnline()
  const pixPendente = atual?.parcelas.some((parcela) => parcela.pix && parcela.status === 'PENDENTE') ?? false
  const doDispositivo = atual?.pendenteSincronizacao === true
  const comFiado = atual?.parcelas.some((parcela) => parcela.forma === 'FIADO') ?? false
  // O Pix integrado depende do provedor, com o cliente presente, e de a Conta poder cobrar por ele
  // hoje; a Venda registrada sem rede não o recebe. A opção escolhida antes de a rede cair volta
  // para dinheiro, sem esperar outro toque. O Pix manual vale com e sem rede, em todo plano.
  const pixIntegradoPermitido = online && !doDispositivo && identidade?.pixIntegrado === true
  const opcaoEscolhida: OpcaoDePagamento = forma === 'PIX_INTEGRADO' && !pixIntegradoPermitido
    ? 'DINHEIRO' : forma
  const formaEscolhida = formaDa(opcaoEscolhida)
  const termo = busca.trim()
  const termoCliente = buscaCliente.trim()
  // A lista de clientes já está carregada: filtrar aqui responde a cada letra, também sem rede.
  const clientesEncontrados = termoCliente ? filtrarPorNome(clientes, termoCliente, (cliente) => cliente.nome) : []
  // Com o campo vazio, a parcela é tudo o que falta lançar.
  const valorDaParcela = !atual ? null : valorPagamento ? lerDinheiro(valorPagamento) : atual.faltaPagar
  const lancando = atual !== undefined && atual.status === 'ABERTA' && atual.itens.length > 0
    && atual.faltaPagar > 0
  const receberEConcluir = lancando && opcaoEscolhida !== 'PIX_INTEGRADO' && atual.parcelas.length === 0
    && valorDaParcela === atual.faltaPagar
  const concluirSemPagar = atual !== undefined && atual.status === 'ABERTA' && atual.itens.length > 0
    && atual.faltaPagar === 0 && !pixPendente
  // A prévia conta em centavos, como o servidor: subtrair em ponto flutuante erraria o último centavo.
  // O troco que vale continua sendo o que o registro da parcela devolve.
  const recebidoNaPrevia = lancando && formaEscolhida === 'DINHEIRO' && valorRecebido
    ? lerDinheiro(valorRecebido) : null
  const previaDoTroco = recebidoNaPrevia !== null && valorDaParcela !== null
    ? (Math.round(recebidoNaPrevia * 100) - Math.round(valorDaParcela * 100)) / 100 : undefined

  function falhar(lugar: LugarDoErro, texto: string) { setErro({ texto, lugar }) }
  function erroEm(lugar: LugarDoErro) { return erro?.lugar === lugar ? erro.texto : undefined }

  // Relê também quando uma rodada de envio grava resultado: a Venda feita sem rede deixa de estar
  // pendente, e a lista precisa mostrar isso.
  useEffect(() => {
    let vivo = true
    caixa.abertaDoOperadorAtual().then(async (aberta) => {
      if (!vivo) return
      setSessao(aberta)
      if (aberta) setHistorico(await vendas.daSessao(aberta.id))
    }).catch((falha) => { if (vivo) setErro({ texto: erroDeCadastro(falha), lugar: 'tela' }) })
      .finally(() => { if (vivo) setCarregando(false) })
    return () => { vivo = false }
  }, [rodada])

  const vendaDoDispositivo = useRef<string | undefined>(undefined)
  useEffect(() => { vendaDoDispositivo.current = atual?.pendenteSincronizacao ? atual.id : undefined }, [atual])
  useEffect(() => {
    const id = vendaDoDispositivo.current
    if (rodada === 0 || !id) return
    let vivo = true
    vendas.consultar(id).then((venda) => {
      if (vivo) setAtual((anterior) => anterior?.id === id ? venda : anterior)
    }).catch(() => { /* a Venda continua como estava; a próxima ação a relê */ })
    return () => { vivo = false }
  }, [rodada])

  useEffect(() => {
    let vivo = true
    cadastro.clientes().then((lista) => { if (vivo) setClientes(lista) })
      .catch((falha) => { if (vivo) setErro({ texto: erroDeCadastro(falha), lugar: 'tela' }) })
    return () => { vivo = false }
  }, [])

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
    if (!online || atual?.pendenteSincronizacao) {
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
  }, [atual?.clienteId, atual?.saldoDevedor, atual?.pendenteSincronizacao, clientes, definirFaixa, online])

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
        if (vivo) { setResultados(produtos); setBuscado(procurado) }
      }).catch((falha) => {
        if (vivo) setErro({ lugar: 'busca', texto: falha instanceof SemConexao
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
      if (!navigator.onLine || document.visibilityState !== 'visible') return
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

  async function adicionar(produto: Produto) {
    if (!sessao) return
    const desconto = descontoDigitado(descontoItem)
    if (desconto === null) {
      falhar('busca', 'Informe o desconto do item com até duas casas decimais.')
      return
    }
    setOcupado(true); setErro(undefined)
    if (atual?.status !== 'ABERTA') { setComprovante(undefined); setTroco(undefined) }
    let id: string | undefined
    try {
      id = atual?.status === 'ABERTA' ? atual.id : (await vendas.iniciar(sessao.id)).id
      await vendas.adicionarItem(id, produto, numero(quantidade), desconto)
      await atualizar(id)
      // O termo buscado sai junto com os resultados: guardado, o mesmo código passado de novo pareceria
      // já buscado e sem resultado, e o Enter do leitor não lançaria nada.
      setBusca(''); setResultados([]); setBuscado(''); setQuantidade('1'); setDescontoItem('')
      buscaRef.current?.focus()
    } catch (falha) {
      falhar('busca', erroDaComanda(falha))
      if (id && !atual) await atualizar(id).catch(() => undefined)
    }
    finally { setOcupado(false) }
  }

  async function remover(itemId: string) {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try { await vendas.removerItem(atual.id, itemId); await atualizar(atual.id) }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
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
    try { await vendas.descontar(atual.id, desconto); await atualizar(atual.id) }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
    finally { setOcupado(false) }
  }

  async function vincularCliente(clienteId: string) {
    if (!atual || !clienteId) return
    setOcupado(true); setErro(undefined)
    try {
      await vendas.vincularCliente(atual.id, clienteId); await atualizar(atual.id)
      setBuscaCliente('')
    }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
    finally { setOcupado(false) }
  }

  async function tirarCliente() {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try {
      await vendas.desvincularCliente(atual.id); await atualizar(atual.id)
      setBuscaCliente('')
    }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
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
    try {
      if (opcaoEscolhida === 'PIX_INTEGRADO') {
        const parcela = await vendas.cobrarPix(atual.id, tentativaPix(atual.id, valor), valor)
        if (parcela.pix?.estado === 'DISPONIVEL') {
          sessionStorage.removeItem(`fechadia-pix-${atual.id}`)
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
      setTroco(resultado.troco)
      if (concluirJunto) await concluir(atual.id)
      else await atualizar(atual.id)
      intencao.confirmar()
      setValorPagamento(''); setValorRecebido(''); setNsu('')
    } catch (falha) {
      falhar('comanda', falha instanceof SemConexao && intencao
        ? 'Sem conexão. Tente registrar a mesma parcela quando a rede voltar.'
        : erroDaComanda(falha))
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
      await atualizar(atual.id).catch(() => undefined)
    } finally { setOcupado(false) }
  }

  async function concluir(id: string) {
    await vendas.concluir(id)
    await atualizar(id)
    setComprovante(await vendas.comprovante(id))
    setSegundaViaEm(undefined)
    // A próxima venda começa pela busca sem um toque para voltar a ela, como depois de lançar item.
    buscaRef.current?.focus()
  }

  async function concluirPendente() {
    if (!atual) return
    setOcupado(true); setErro(undefined)
    try { await concluir(atual.id) }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
    finally { setOcupado(false) }
  }

  async function cancelar() {
    if (!atual) return
    if (!(await confirmar(perguntaDoCancelamento(atual)))) return
    setOcupado(true); setErro(undefined)
    try {
      await vendas.cancelar(atual.id); await atualizar(atual.id); setComprovante(undefined)
      buscaRef.current?.focus()
    }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
    finally { setOcupado(false) }
  }

  async function selecionar(resumo: ResumoDaVenda) {
    setErro(undefined); setTroco(undefined); setComprovante(undefined); setBuscaCliente('')
    try {
      await atualizar(resumo.id)
      if (resumo.status === 'CONCLUIDA') {
        setComprovante(await vendas.comprovante(resumo.id))
        setSegundaViaEm(new Date())
      }
    } catch (falha) { falhar('historico', erroDeCadastro(falha)) }
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
   * O Enter que o leitor de código de barras manda depois do código. Lança o produto cujo código é
   * igual ao termo, ou o único resultado; com vários achados pelo nome, quem escolhe é a pessoa. O
   * leitor digita mais rápido que a espera da busca, e o Enter pode chegar antes da resposta: aí a
   * busca sai na hora.
   */
  async function lancarPeloEnter() {
    const procurado = busca.trim()
    if (!procurado || ocupado || !sessao) return
    let encontrados = resultados
    if (buscado !== procurado) {
      try {
        encontrados = await cadastro.buscarProdutos(procurado)
      } catch (falha) {
        falhar('busca', falha instanceof SemConexao
          ? 'Sem resposta do servidor na busca. Tente de novo.' : erroDeCadastro(falha))
        return
      }
      setResultados(encontrados); setBuscado(procurado)
    }
    // O campo pode ter mudado durante a busca; lançar pelo termo antigo seria lançar outro produto.
    if (buscaRef.current && buscaRef.current.value.trim() !== procurado) return
    const doCodigo = encontrados.find((produto) => produto.codigo === procurado)
    const escolhido = doCodigo ?? (encontrados.length === 1 ? encontrados[0] : undefined)
    if (escolhido) await adicionar(escolhido)
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
    buscaRef.current?.focus()
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
    {carregando ? <p>Carregando...</p> : !sessao ? <p>Abra o seu caixa para vender. <Link to="/caixa">Ir ao caixa</Link></p>
      : <div className="pdv__grade">
        <section className="pdv__painel">
          <h3>Busca de produtos</h3>
          <div className="pdv__busca">
            <label>Produto por nome ou código
              <input ref={buscaRef} autoFocus value={busca} onChange={(evento) => mudarBusca(evento.target.value)}
                onKeyDown={(evento) => {
                  if (evento.key !== 'Enter') return
                  evento.preventDefault()
                  void lancarPeloEnter()
                }}
                placeholder="Digite ou passe o código" />
            </label>
            <label>Quantidade
              <input type="number" min="0.001" step="0.001" value={quantidade}
                onChange={(evento) => setQuantidade(evento.target.value)} />
            </label>
          </div>
          <p className="pdv__ajuda">Também aceita 3* no início da busca para lançar três unidades.</p>
          {identidade?.perfil === 'ADMIN' &&
            <CampoDeDinheiro rotulo="Desconto deste item" placeholder="0,00" valor={descontoItem}
              aoMudar={setDescontoItem} />}
          <Mensagem tipo="erro" texto={erroEm('busca')} />
          {termo && buscado === termo && resultados.length === 0 &&
            <p className="pdv__ajuda" role="status">Nenhum produto encontrado para {termo}.</p>}
          {resultados.length > 0 && <ul className="pdv__resultados">
            {resultados.map((produto) => <li key={produto.id}>
              <button type="button" className="botao botao--secundario" disabled={ocupado}
                onClick={() => void adicionar(produto)}>
                {produto.nome} · {moeda.format(produto.preco)}{produto.codigo && ` · ${produto.codigo}`}
              </button>
            </li>)}
          </ul>}
        </section>

        <section className="pdv__painel" aria-busy={ocupado}>
          <div className="pdv__topo"><h3>Comanda</h3>
            <span className="pdv__ajuda" aria-live="polite">{ocupado ? 'Registrando...' : ''}</span>
            <button className="botao botao--secundario" type="button" onClick={novaVenda}>Nova venda</button>
          </div>
          {!atual ? <p>Busque e escolha o primeiro produto para iniciar.</p> : <>
            {/* O cancelamento fica no alto, junto da venda que ele desfaz, e longe dos botões de
                pagar e concluir: depois de concluir a comanda encolhe, e o toque seguinte cairia nele. */}
            <div className="pdv__topo pdv__venda">
              <p>Venda {atual.id.slice(0, 8)} · {ROTULO_DA_VENDA[atual.status]}</p>
              {atual.status !== 'CANCELADA' && !doDispositivo && <button className="botao botao--perigo"
                type="button" disabled={ocupado} onClick={() => void cancelar()}>Cancelar venda</button>}
            </div>
            {atual.status !== 'CANCELADA' && doDispositivo && <p className="pdv__ajuda">{CANCELAR_DEPOIS_DE_SINCRONIZAR}</p>}
            {doDispositivo && <p className="mensagem-info" role="status">Registrada neste dispositivo. Aguardando sincronização com o servidor.</p>}
            {!online && !doDispositivo && atual.status === 'ABERTA' &&
              <p className="mensagem-info" role="status">Esta Venda está no servidor e continua quando a rede voltar. Para vender agora, comece uma nova venda.</p>}
            {atual.status === 'CANCELADA' && atual.parcelas.some((p) => p.pix && p.status === 'CONFIRMADO') &&
              <p className="mensagem-info" role="status">Pix integrado recebido. A devolução precisa ser feita fora do sistema e ainda exige conciliação; este aplicativo não verifica se ela aconteceu.</p>}
            {/* O cliente se acha pelo nome, como o produto: uma lista com todos não cabe no balcão,
                e a tela virada para o cliente mostraria o nome de cada um. */}
            {atual.clienteId && <div className="pdv__topo">
              <p>Cliente: {nomeDoCliente(atual.clienteId)}</p>
              {atual.status === 'ABERTA' && !comFiado && <button className="botao botao--secundario"
                type="button" disabled={ocupado} onClick={() => void tirarCliente()}>Tirar o cliente</button>}
            </div>}
            {/* A conclusão com fiado exige cliente, e a parcela lançada não se desfaz: sem ele, a
                venda ficaria sem como concluir. */}
            {atual.clienteId && atual.status === 'ABERTA' && comFiado &&
              <p className="pdv__ajuda">Com parcela fiado, o cliente só pode ser trocado.</p>}
            {atual.status === 'ABERTA' && <>
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
                  <button type="button" className="botao botao--secundario" disabled={ocupado}
                    onClick={() => void vincularCliente(cliente.id)}>{cliente.nome}</button>
                </li>)}
              </ul>}
            </>}
            {atual.itens.length === 0 ? <p>Nenhum item lançado.</p> : <ul className="pdv__itens">
              {atual.itens.map((item) => <li key={item.id}>
                <span>{item.quantidade} × {item.nome} · {moeda.format(item.subtotal)}
                  {item.desconto > 0 && ` (desconto ${moeda.format(item.desconto)})`}</span>
                {atual.status === 'ABERTA' && <button className="botao botao--secundario" type="button"
                  disabled={ocupado} onClick={() => void remover(item.id)}>Remover</button>}
              </li>)}
            </ul>}
            {identidade?.perfil === 'ADMIN' && atual.status === 'ABERTA' && <form onSubmit={(evento) => void descontar(evento)} className="pdv__desconto">
              <div className="pdv__desconto-campo">
                <CampoDeDinheiro rotulo="Desconto da venda" placeholder="0,00" valor={descontoVenda}
                  aoMudar={setDescontoVenda} />
              </div>
              <button className="botao botao--secundario" disabled={ocupado}>Aplicar</button>
            </form>}
            {atual.saldoDevedor > 0 && <p><strong>Saldo devedor desta Venda: {moeda.format(atual.saldoDevedor)}</strong></p>}
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
                  {parcela.status === 'PENDENTE' && parcela.pix.estado !== 'DISPONIVEL' &&
                    <button type="button" className="botao botao--secundario" disabled={ocupado}
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
                  {identidade?.perfil === 'ADMIN' && atual.clienteId &&
                    !atual.parcelas.some((parcela) => parcela.forma === 'FIADO') &&
                    <option value="FIADO">Fiado</option>}
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
                    disabled={ocupado || (valorDaParcela !== null && nota < valorDaParcela)}
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
                <button className="botao botao--secundario" disabled={ocupado}>Registrar parcela</button>
              </div>
            </form>}
          </>}
          {/* O rodapé fica preso ao fim da tela enquanto a comanda está nela: total, o que falta, o
              troco e o botão principal não dependem de rolar, por mais itens que a venda tenha. O
              botão principal não envia o formulário, e o Enter num campo continua registrando só a
              parcela. A mensagem de erro fica aqui também, onde o rodapé não a cobre. */}
          {atual ? <div className="pdv__rodape">
            <Mensagem tipo="erro" texto={erroEm('comanda')} />
            <p className="pdv__linha pdv__total"><span>Total:</span> <span>{moeda.format(atual.total)}</span></p>
            {/* Sem parcela, o lançado é zero e o que falta é o total: a linha a menos deixa o rodapé
                mais baixo no celular, onde ele divide a tela com a comanda. */}
            {atual.status === 'ABERTA' && <p className="pdv__linha">
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
            {receberEConcluir && <button className="botao botao--largo" type="button" disabled={ocupado}
              onClick={(evento) => void pagar(evento, true)}>
              {formaEscolhida === 'FIADO' ? 'Registrar fiado e concluir' : 'Receber e concluir'}</button>}
            {concluirSemPagar && <button className="botao botao--largo" type="button" disabled={ocupado}
              onClick={() => void concluirPendente()}>Concluir venda</button>}
          </div> : <Mensagem tipo="erro" texto={erroEm('comanda')} />}
        </section>

        <section className="pdv__painel pdv__historico">
          <h3>Vendas deste caixa</h3>
          {!online && <p className="pdv__ajuda">Sem conexão, a lista mostra as Vendas registradas neste dispositivo.</p>}
          <Mensagem tipo="erro" texto={erroEm('historico')} />
          {historico.length === 0 ? <p>Nenhuma venda neste caixa.</p> : <ul>
            {historico.map((resumo) => <li key={resumo.id}>
              <button type="button" className="botao botao--secundario"
                onClick={() => void selecionar(resumo)}>
                {dataHora.format(new Date(resumo.criadoEm))} · {ROTULO_DA_VENDA[resumo.status]} · {moeda.format(resumo.total)}
                {resumo.status === 'ABERTA' && (resumo.pendenteSincronizacao ? ' · retomar' : ' · retomar ou cancelar')}
                {resumo.pendenteSincronizacao && ' · pendente de sincronização'}
              </button>
            </li>)}
          </ul>}
        </section>
      </div>}

    {comprovante && identidade && <Comprovante comprovante={comprovante} negocio={identidade.nomeNegocio}
      operador={nomeDoOperador(comprovante.usuarioId)} segundaViaEm={segundaViaEm}
      aoFalhar={(mensagem) => falhar('comprovante', mensagem)} />}
    {/* Fora do papel branco do comprovante, onde o texto claro da mensagem não se leria. */}
    <Mensagem tipo="erro" texto={erroEm('comprovante')} />
    {dialogoDeConfirmacao}
  </section>
}
