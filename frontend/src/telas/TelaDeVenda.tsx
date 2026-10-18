import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { QRCodeSVG } from 'qrcode.react'
import { cadastro, type Cliente, type Produto } from '../api/cadastro'
import type { SessaoCaixa } from '../api/caixa'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { contas } from '../api/contas'
import { fiado } from '../api/fiado'
import { intencaoOnline, type IntencaoOnline } from '../api/intencaoOnline'
import type { Comprovante, ConciliacaoPix, FormaPagamento, ResumoDaVenda, Venda } from '../api/vendas'
import { dataHora, moeda, valorNoCampo } from '../formatos'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { normalizarNsu } from '../offline/raizDaVenda'
import { criarVendaLocal } from '../offline/vendaLocal'
import { useContextoDoShell } from '../shell/ContextoDoShell'
import { useSincronizacao } from '../shell/sincronizacao'
import { useOnline } from '../shell/useOnline'
import { useSessao } from '../sessao/useSessao'
import { erroDeCadastro } from './erroDeCadastro'
import { Mensagem } from './Mensagem'
import { ROTULO_DA_COBRANCA_PIX, ROTULO_DA_FORMA, ROTULO_DA_VENDA } from './rotulos'

const caixa = criarCaixaLocal()
const vendas = criarVendaLocal()
const PENDENTE_NO_COMPROVANTE = 'Pendente de sincronização com o servidor'
const CANCELAR_DEPOIS_DE_SINCRONIZAR = 'O cancelamento fica disponível depois da sincronização com o servidor.'

/** O painel em que a mensagem de erro aparece: o mesmo em que a ação foi tocada. */
type LugarDoErro = 'tela' | 'busca' | 'comanda' | 'historico' | 'comprovante'

function numero(texto: string): number { return Number(texto.replace(',', '.')) }

/**
 * Sem resposta, a operação pode ter chegado ao servidor mesmo assim; a comanda relida mostra se ela
 * valeu, e repetir sem olhar poderia lançar duas vezes.
 */
function erroDaComanda(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem resposta do servidor. Confira a comanda antes de repetir.'
  return erroDeCadastro(falha)
}

function tentativaPix(vendaId: string, valor: number): string {
  const chave = `caixa-simples-pix-${vendaId}`
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

function textoDoComprovante(comprovante: Comprovante, negocio: string, operador: string): string {
  return [
    negocio, `Operador: ${operador}`, 'Comprovante não fiscal',
    ...(comprovante.pendenteSincronizacao ? [PENDENTE_NO_COMPROVANTE] : []),
    dataHora.format(new Date(comprovante.concluidoEm)),
    ...comprovante.linhas.map((linha) =>
      `${linha.quantidade} × ${linha.nome}: ${moeda.format(linha.subtotal)}`),
    `Desconto da venda: ${moeda.format(comprovante.descontoDaVenda)}`,
    `Total: ${moeda.format(comprovante.valorTotal)}`,
    ...comprovante.parcelas.map((parcela) =>
      `${ROTULO_DA_FORMA[parcela.forma]}: ${moeda.format(parcela.valor)}`),
    ...(comprovante.valorFiado > 0 ? [
      `Valor fiado: ${moeda.format(comprovante.valorFiado)}`,
      `Valor pendente: ${moeda.format(comprovante.saldoDevedor)}`,
    ] : []),
    `Troco: ${moeda.format(comprovante.troco)}`,
    `Venda: ${comprovante.vendaId}`,
  ].join('\n')
}

export function TelaDeVenda() {
  const { identidade } = useSessao()
  const { definirFaixa } = useContextoDoShell()
  const { rodada } = useSincronizacao()
  const buscaRef = useRef<HTMLInputElement>(null)
  const [sessao, setSessao] = useState<SessaoCaixa>()
  const [historico, setHistorico] = useState<ResumoDaVenda[]>([])
  const [conciliacoes, setConciliacoes] = useState<ConciliacaoPix[]>([])
  const [conciliacaoSelecionada, setConciliacaoSelecionada] = useState<Venda>()
  const [clientes, setClientes] = useState<Cliente[]>([])
  const [atual, setAtual] = useState<Venda>()
  const [comprovante, setComprovante] = useState<Comprovante>()
  const [busca, setBusca] = useState('')
  // O termo cuja resposta está em resultados: sem ele, a busca vazia e a ainda em curso pareceriam iguais.
  const [buscado, setBuscado] = useState('')
  const [quantidade, setQuantidade] = useState('1')
  const [descontoItem, setDescontoItem] = useState('0')
  const [descontoVenda, setDescontoVenda] = useState('0')
  const [resultados, setResultados] = useState<Produto[]>([])
  const [forma, setForma] = useState<FormaPagamento>('DINHEIRO')
  const [valorPagamento, setValorPagamento] = useState('')
  const [valorRecebido, setValorRecebido] = useState('')
  const [nsu, setNsu] = useState('')
  const [troco, setTroco] = useState<number>()
  const [copiado, setCopiado] = useState<string>()
  const [nomes, setNomes] = useState<Map<string, string>>(() => new Map())
  const [erro, setErro] = useState<{ texto: string; lugar: LugarDoErro }>()
  const [ocupado, setOcupado] = useState(false)
  const [carregando, setCarregando] = useState(true)
  const online = useOnline()
  const pixPendente = atual?.parcelas.some((parcela) => parcela.pix && parcela.status === 'PENDENTE') ?? false
  const doDispositivo = atual?.pendenteSincronizacao === true
  // Pix não entra na Venda registrada sem rede: a cobrança depende do provedor, com o cliente
  // presente. A forma escolhida antes de a rede cair volta para dinheiro, sem esperar outro toque.
  const pixPermitido = online && !doDispositivo
  const formaEscolhida: FormaPagamento = forma === 'PIX' && !pixPermitido ? 'DINHEIRO' : forma
  const termo = busca.trim()

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
    setOcupado(true); setErro(undefined)
    if (atual?.status !== 'ABERTA') { setComprovante(undefined); setTroco(undefined) }
    let id: string | undefined
    try {
      id = atual?.status === 'ABERTA' ? atual.id : (await vendas.iniciar(sessao.id)).id
      await vendas.adicionarItem(id, produto, numero(quantidade), numero(descontoItem))
      await atualizar(id)
      setBusca(''); setResultados([]); setQuantidade('1'); setDescontoItem('0')
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
    setOcupado(true); setErro(undefined)
    try { await vendas.descontar(atual.id, numero(descontoVenda)); await atualizar(atual.id) }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
    finally { setOcupado(false) }
  }

  async function vincularCliente(clienteId: string) {
    if (!atual || !clienteId) return
    setOcupado(true); setErro(undefined)
    try { await vendas.vincularCliente(atual.id, clienteId); await atualizar(atual.id) }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
    finally { setOcupado(false) }
  }

  async function pagar(evento: { preventDefault(): void }, concluirJunto = false) {
    evento.preventDefault()
    if (!atual) return
    const valor = valorPagamento ? numero(valorPagamento) : atual.faltaPagar
    const recebido = formaEscolhida === 'DINHEIRO'
      ? (valorRecebido ? numero(valorRecebido) : valor) : undefined
    // O campo fica escondido fora do cartão, e o que foi digitado nele antes da troca não vai junto.
    const nsuDaParcela = formaEscolhida === 'CARTAO' ? nsu : undefined
    setOcupado(true); setErro(undefined)
    let intencao: IntencaoOnline | undefined
    try {
      if (formaEscolhida === 'PIX') {
        const parcela = await vendas.cobrarPix(atual.id, tentativaPix(atual.id, valor), valor)
        if (parcela.pix?.estado === 'DISPONIVEL') {
          sessionStorage.removeItem(`caixa-simples-pix-${atual.id}`)
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
    setOcupado(true); setErro(undefined)
    try {
      await vendas.cancelar(atual.id); await atualizar(atual.id); setComprovante(undefined)
      buscaRef.current?.focus()
    }
    catch (falha) { falhar('comanda', erroDaComanda(falha)) }
    finally { setOcupado(false) }
  }

  async function selecionar(resumo: ResumoDaVenda) {
    setErro(undefined); setTroco(undefined); setComprovante(undefined)
    try {
      await atualizar(resumo.id)
      if (resumo.status === 'CONCLUIDA') setComprovante(await vendas.comprovante(resumo.id))
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

  async function compartilhar() {
    if (!comprovante || !identidade || !navigator.share) return
    try {
      await navigator.share({
        title: 'Comprovante não fiscal',
        text: textoDoComprovante(comprovante, identidade.nomeNegocio, nomeDoOperador(comprovante.usuarioId)),
      })
    } catch (falha) {
      if ((falha as Error).name !== 'AbortError') falhar('comprovante', erroDeCadastro(falha))
    }
  }

  function mudarBusca(texto: string) {
    const separado = separarMultiplicador(texto)
    if (separado.quantidade) setQuantidade(separado.quantidade)
    setBusca(separado.termo)
    if (!separado.termo.trim()) setResultados([])
  }

  function novaVenda() {
    setAtual(undefined); setComprovante(undefined); setTroco(undefined); setForma('DINHEIRO'); setNsu('')
    setErro(undefined); setCopiado(undefined)
    buscaRef.current?.focus()
  }

  return <section className="pdv">
    <h2 className="titulo">Venda</h2>
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
            {atual.status === 'ABERTA' ? <label>Cliente (para fiado)
              <select value={atual.clienteId ?? ''} disabled={ocupado}
                onChange={(evento) => void vincularCliente(evento.target.value)}>
                <option value="">Selecione um Cliente</option>
                {clientes.map((cliente) => <option key={cliente.id} value={cliente.id}>{cliente.nome}</option>)}
              </select>
            </label> : atual.clienteId && <p>Cliente: {clientes.find((c) => c.id === atual.clienteId)?.nome ?? atual.clienteId}</p>}
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
            <p>Parcelas lançadas: {moeda.format(atual.pago)} · Falta lançar: {moeda.format(atual.faltaPagar)}</p>
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
            {troco !== undefined && <p className="mensagem-info" role="status">Troco: {moeda.format(troco)}</p>}
            {atual.status === 'ABERTA' && <>
              {atual.itens.length > 0 && atual.faltaPagar > 0 && <form className="pdv__pagamento" onSubmit={(evento) => void pagar(evento)}>
                <h4>Pagamento</h4>
                <label>Forma
                  <select value={formaEscolhida} onChange={(evento) => setForma(evento.target.value as FormaPagamento)}>
                    <option value="DINHEIRO">Dinheiro</option>
                    {pixPermitido && <option value="PIX">Pix integrado</option>}
                    <option value="CARTAO">Cartão manual</option>
                    {identidade?.perfil === 'ADMIN' && atual.clienteId &&
                      !atual.parcelas.some((parcela) => parcela.forma === 'FIADO') &&
                      <option value="FIADO">Fiado</option>}
                  </select>
                </label>
                <label>Valor da parcela
                  <input type="number" min="0.01" step="0.01" value={valorPagamento}
                    placeholder={valorNoCampo(atual.faltaPagar)} onChange={(evento) => setValorPagamento(evento.target.value)} />
                </label>
                {formaEscolhida === 'DINHEIRO' && <label>Valor recebido em dinheiro
                  <input type="number" min="0" step="0.01" value={valorRecebido}
                    placeholder={valorNoCampo(valorPagamento ? numero(valorPagamento) : atual.faltaPagar)}
                    onChange={(evento) => setValorRecebido(evento.target.value)} />
                </label>}
                {/* Sem limite de tamanho no campo: o navegador cortaria o número colado com espaços
                    em volta, que o servidor aceita depois de tirar os espaços. */}
                {formaEscolhida === 'CARTAO' && <label>
                  {identidade?.nsuObrigatorio ? 'NSU do comprovante' : 'NSU do comprovante (opcional)'}
                  <input value={nsu} autoComplete="off" required={identidade?.nsuObrigatorio === true}
                    onChange={(evento) => setNsu(evento.target.value)} />
                </label>}
                <div className="pdv__acoes">
                  <button className="botao botao--secundario" disabled={ocupado}>Registrar parcela</button>
                  {formaEscolhida !== 'PIX' && atual.parcelas.length === 0 && (!valorPagamento || numero(valorPagamento) === atual.faltaPagar)
                    && <button className="botao" type="button" disabled={ocupado}
                    onClick={(evento) => void pagar(evento, true)}>{formaEscolhida === 'FIADO' ? 'Registrar fiado e concluir' : 'Receber e concluir'}</button>}
                </div>
              </form>}
              {atual.faltaPagar === 0 && atual.itens.length > 0 && !atual.parcelas.some((p) => p.pix && p.status === 'PENDENTE') && <button className="botao" type="button"
                disabled={ocupado} onClick={() => void concluirPendente()}>Concluir venda</button>}
            </>}
          </>}
          <Mensagem tipo="erro" texto={erroEm('comanda')} />
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

    {comprovante && identidade && <section className="comprovante" aria-label="Comprovante não fiscal">
      <h3>{identidade.nomeNegocio}</h3>
      <p>Operador: {nomeDoOperador(comprovante.usuarioId)}</p>
      <p><strong>Comprovante não fiscal</strong></p>
      {comprovante.pendenteSincronizacao && <p><strong>{PENDENTE_NO_COMPROVANTE}</strong></p>}
      <p>{dataHora.format(new Date(comprovante.concluidoEm))}</p>
      <ul>{comprovante.linhas.map((linha, indice) => <li key={`${linha.produtoId}-${indice}`}>
        {linha.quantidade} × {linha.nome} · {moeda.format(linha.precoUnitario)} · {moeda.format(linha.subtotal)}
        {linha.desconto > 0 && ` · desconto ${moeda.format(linha.desconto)}`}
      </li>)}</ul>
      <p>Soma dos itens: {moeda.format(comprovante.somaDosItens)}</p>
      <p>Desconto da venda: {moeda.format(comprovante.descontoDaVenda)}</p>
      <p><strong>Total: {moeda.format(comprovante.valorTotal)}</strong></p>
      <ul>{comprovante.parcelas.map((parcela, indice) => <li key={indice}>
        {ROTULO_DA_FORMA[parcela.forma]}: {moeda.format(parcela.valor)}</li>)}</ul>
      {comprovante.valorFiado > 0 && <>
        <p>Valor fiado: {moeda.format(comprovante.valorFiado)}</p>
        <p><strong>Valor pendente: {moeda.format(comprovante.saldoDevedor)}</strong></p>
      </>}
      <p>Troco: {moeda.format(comprovante.troco)}</p>
      <p>Venda: {comprovante.vendaId}</p>
      <div className="comprovante__acoes">
        <button className="botao" type="button" onClick={() => window.print()}>Imprimir</button>
        {typeof navigator.share === 'function' && <button className="botao botao--secundario" type="button"
          onClick={() => void compartilhar()}>Compartilhar</button>}
      </div>
    </section>}
    {/* Fora do papel branco do comprovante, onde o texto claro da mensagem não se leria. */}
    <Mensagem tipo="erro" texto={erroEm('comprovante')} />
  </section>
}
