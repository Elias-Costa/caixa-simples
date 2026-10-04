import { cadastro, type Produto } from '../api/cadastro'
import { SemConexao } from '../api/cliente'
import { vendas, type Comprovante, type ResumoDaVenda, type Venda } from '../api/vendas'
import { lerIdentidade } from '../sessao/armazenamento'
import { criarCaixaLocal, sessaoTemPendencia, ultimoGestoDaGaveta } from './caixaLocal'
import { reais } from './dinheiro'
import {
  enfileirarGesto, gestoAplicavel, gestoPendente, guardarRetrato, guardarRetratoDaVenda, lerRetrato,
  listarGestos, listarRetratosDeVendas, ordenarPorDependencia, type GestoNaFila, type ValorJson,
} from './fila'
import * as raiz from './raizDaVenda'

type VendasDaApi = typeof vendas
type CaixaDoDispositivo = Pick<ReturnType<typeof criarCaixaLocal>, 'consultar' | 'escreverNaGavetaComRede'>
type ClientesDoDispositivo = Pick<typeof cadastro, 'clientes'>

/** O contrato da API de vendas, com o item levando o Produto como o operador o viu na busca. */
export type VendasDoPdv = Omit<VendasDaApi, 'adicionarItem' | 'pagar' | 'receber'> & {
  adicionarItem(id: string, produto: Produto, quantidade: number, desconto: number): Promise<{ id: string }>
  pagar(id: string, forma: Parameters<VendasDaApi['pagar']>[1], valor: number,
    valorRecebido?: number, pagamentoId?: string, nsu?: string): Promise<{ troco: number }>
  receber(id: string, valor: number, forma: Parameters<VendasDaApi['receber']>[2],
    recebimentoId?: string, nsu?: string): Promise<{ id: string; saldoDevedor: number }>
}

const VENDA_DO_SERVIDOR_SEM_REDE = 'Esta Venda está no servidor e continua quando a rede voltar. '
  + 'Para vender agora, comece uma nova venda.'
const CANCELAR_DEPOIS_DE_SINCRONIZAR = 'Esta Venda foi registrada neste dispositivo e ainda não chegou '
  + 'ao servidor. O cancelamento fica disponível depois da sincronização.'
export const NSU_EXIGIDO = 'Informe o NSU do comprovante da maquininha: esta Conta exige o NSU no '
  + 'pagamento em cartão.'

/** Uma Venda que continua neste dispositivo, remontada dos gestos gravados. */
type NoDispositivo = { gestos: GestoNaFila[]; venda: raiz.VendaNoDispositivo; pendente: boolean }

function temBanco(): boolean {
  return 'indexedDB' in globalThis
}

function usuarioAtual(): string {
  const id = lerIdentidade()?.usuarioId
  if (!id) throw new Error('Entre novamente para vender.')
  return id
}

function exigirAdmin(mensagem: string): void {
  if (lerIdentidade()?.perfil !== 'ADMIN') throw new Error(mensagem)
}

/**
 * A exigência do NSU como o aparelho a conhece, da identidade. Sem rede é a única conferência antes
 * de o gesto ser gravado; se a Conta passou a exigir depois da última leitura da identidade, o
 * servidor grava a parcela e a marca para o administrador conferir.
 */
function exigirNsuSeAContaExige(forma: string, nsu: string | null): void {
  if (forma === 'CARTAO' && nsu === null && lerIdentidade()?.nsuObrigatorio === true) {
    throw new Error(NSU_EXIGIDO)
  }
}

function ultimoGesto(gestos: GestoNaFila[], prefixo: string, id: string): GestoNaFila | undefined {
  return ordenarPorDependencia(gestos.filter((gesto) =>
    gesto.tipo.startsWith(prefixo) && gesto.registroId === id && gestoAplicavel(gesto))).at(-1)
}

function operacoes(...gestos: (GestoNaFila | undefined)[]): string[] {
  return gestos.flatMap((gesto) => gesto ? [gesto.operacaoId] : [])
}

async function enfileirar(tipo: string, id: string, dados: object, dependeDe: string[],
  versaoBase?: number): Promise<void> {
  await enfileirarGesto({ tipo, registroId: id, payload: JSON.parse(JSON.stringify(dados)) as ValorJson,
    dependeDe, versaoBase })
}

/**
 * Onde uma Venda existente continua. A que nasceu neste dispositivo e ainda tem gesto sem resultado
 * continua aqui, com ou sem rede, porque o servidor ainda não sabe tudo o que aconteceu com ela.
 * Qualquer outra é do servidor e só continua com rede: a comanda aberta com conexão não é copiada
 * para o dispositivo quando a rede cai. A leitura sem rede de uma Venda nascida aqui usa os gestos
 * guardados, mesmo depois de enviados.
 */
async function localizar(id: string, leitura: boolean): Promise<NoDispositivo | 'servidor'> {
  if (!temBanco()) return 'servidor'
  const gestos = await listarGestos()
  const daVenda = gestos.filter((gesto) => gesto.tipo.startsWith('venda.') && gesto.registroId === id)
  const nasceuAqui = daVenda.some((gesto) => gesto.tipo === 'venda.iniciar')
  const pendente = daVenda.some(gestoPendente)
  if (nasceuAqui && (pendente || (leitura && !navigator.onLine))) {
    const venda = raiz.projetarVendas(gestos, usuarioAtual()).get(id)
    if (venda) return { gestos, venda, pendente }
  }
  if (!navigator.onLine) throw new Error(VENDA_DO_SERVIDOR_SEM_REDE)
  return 'servidor'
}

/**
 * A sessão em que a Venda do servidor nasceu, pelo retrato que a tela leu antes de concluí-la ou
 * cancelá-la. Sem o retrato, o caixa usa a sessão aberta de quem opera.
 */
async function sessaoDaVenda(id: string): Promise<string | undefined> {
  return (await lerRetrato<Venda>(`venda:${id}`))?.dados.sessaoCaixaId
}

function resumo(venda: raiz.VendaNoDispositivo, pendente: boolean): ResumoDaVenda {
  return {
    id: venda.id, sessaoCaixaId: venda.sessaoCaixaId, usuarioId: venda.usuarioId, status: venda.status,
    total: reais(raiz.total(venda)), criadoEm: venda.criadoEm, pendenteSincronizacao: pendente,
  }
}

function valorFiado(venda: raiz.VendaNoDispositivo): number {
  return venda.parcelas.filter((parcela) => parcela.forma === 'FIADO')
    .reduce((soma, parcela) => soma + parcela.valorCentavos, 0)
}

function paraTela(venda: raiz.VendaNoDispositivo, pendente: boolean): Venda {
  const pago = raiz.lancado(venda)
  return {
    ...resumo(venda, pendente),
    clienteId: venda.clienteId,
    saldoDevedor: reais(valorFiado(venda)),
    descontoDaVenda: reais(venda.descontoCentavos),
    pago: reais(pago),
    faltaPagar: reais(raiz.total(venda) - pago),
    itens: venda.itens.map((item) => ({
      id: item.id, produtoId: item.produtoId, nome: item.nome, quantidade: item.milesimos / 1000,
      precoUnitario: reais(item.precoCentavos), desconto: reais(item.descontoCentavos),
      subtotal: reais(raiz.subtotal(item)),
    })),
    parcelas: venda.parcelas.map((parcela) => ({
      id: parcela.id, forma: parcela.forma, valor: reais(parcela.valorCentavos), status: parcela.status,
      troco: reais(parcela.trocoCentavos), pix: null, nsu: parcela.nsu ?? undefined,
    })),
    recebimentos: [],
  }
}

/**
 * Com o nome e o preço vistos no balcão. A marca de pendência fica enquanto algum gesto da Venda
 * não teve resultado; depois disso o comprovante lido sem rede é o mesmo que o servidor emitiria.
 */
function comprovanteDoDispositivo(venda: raiz.VendaNoDispositivo, pendente: boolean): Comprovante {
  if (venda.status !== 'CONCLUIDA' || !venda.concluidoEm) {
    throw new Error('Só Venda concluída tem comprovante.')
  }
  const fiado = valorFiado(venda)
  return {
    vendaId: venda.id,
    usuarioId: venda.usuarioId,
    concluidoEm: venda.concluidoEm,
    linhas: venda.itens.map((item) => ({
      produtoId: item.produtoId, nome: item.nome, unidade: null, quantidade: item.milesimos / 1000,
      precoUnitario: reais(item.precoCentavos), valorBruto: reais(raiz.valorBruto(item)),
      desconto: reais(item.descontoCentavos), subtotal: reais(raiz.subtotal(item)),
    })),
    somaDosItens: reais(raiz.somaDosItens(venda)),
    descontoDaVenda: reais(venda.descontoCentavos),
    valorTotal: reais(raiz.total(venda)),
    parcelas: venda.parcelas.map((parcela) => ({
      forma: parcela.forma, valor: reais(parcela.valorCentavos), troco: reais(parcela.trocoCentavos),
    })),
    troco: reais(venda.parcelas.reduce((soma, parcela) => soma + parcela.trocoCentavos, 0)),
    valorFiado: reais(fiado),
    saldoDevedor: reais(fiado),
    pendenteSincronizacao: pendente,
  }
}

/**
 * O PDV sem rede. Uma Venda nova fica no dispositivo quando não há rede ou quando a SessaoCaixa já
 * tem gesto pendente: nesse caso o que vem depois também precisa entrar na fila, ou o servidor
 * receberia os fatos fora da ordem em que aconteceram no balcão. Cada gesto é conferido pela raiz
 * local e gravado antes de a promessa terminar, e só então a tela avança.
 */
export function criarVendaLocal(remoto: VendasDaApi = vendas, caixa: CaixaDoDispositivo = criarCaixaLocal(),
  clientes: ClientesDoDispositivo = cadastro): VendasDoPdv {
  return {
    async iniciar(sessaoCaixaId) {
      if (!temBanco()) return remoto.iniciar(sessaoCaixaId)
      const gestos = await listarGestos()
      if (navigator.onLine && !sessaoTemPendencia(gestos, sessaoCaixaId)) {
        return remoto.iniciar(sessaoCaixaId)
      }
      const sessao = await caixa.consultar(sessaoCaixaId)
      if (sessao.status !== 'ABERTA') {
        throw new Error('A SessaoCaixa não está ABERTA e não aceita Venda nova. Abra um caixa antes de vender.')
      }
      const id = crypto.randomUUID()
      const abertura = gestos.find((gesto) => gesto.tipo === 'caixa.abrir' && gesto.registroId === sessaoCaixaId)
      const dados: raiz.DadosDoInicio = { sessaoCaixaId, criadoEm: new Date().toISOString() }
      await enfileirar('venda.iniciar', id, dados, operacoes(abertura))
      return { id }
    },
    async daSessao(sessaoCaixaId) {
      if (!temBanco()) return remoto.daSessao(sessaoCaixaId)
      const gestos = await listarGestos()
      const doDispositivo = [...raiz.projetarVendas(gestos, usuarioAtual()).values()]
        .filter((venda) => venda.sessaoCaixaId === sessaoCaixaId)
        .map((venda) => resumo(venda, gestos.some((gesto) =>
          gesto.tipo.startsWith('venda.') && gesto.registroId === venda.id && gestoPendente(gesto))))
      let doServidor: ResumoDaVenda[] = []
      if (navigator.onLine) {
        try {
          doServidor = await remoto.daSessao(sessaoCaixaId)
        } catch (falha) {
          if (!(falha instanceof SemConexao)) throw falha
        }
      }
      const porId = new Map(doServidor.map((venda) => [venda.id, venda]))
      for (const guardada of await listarRetratosDeVendas<Venda>()) {
        if (guardada.sessaoCaixaId === sessaoCaixaId && !porId.has(guardada.id)) {
          porId.set(guardada.id, guardada)
        }
      }
      for (const venda of doDispositivo) {
        if (venda.pendenteSincronizacao || !porId.has(venda.id)) porId.set(venda.id, venda)
      }
      return [...porId.values()].sort((a, b) => b.criadoEm.localeCompare(a.criadoEm))
    },
    async conciliacoesPix() {
      if (!navigator.onLine) return []
      try {
        return await remoto.conciliacoesPix()
      } catch (falha) {
        if (falha instanceof SemConexao) return []
        throw falha
      }
    },
    async consultar(id) {
      if (!navigator.onLine && temBanco()) {
        const gestos = await listarGestos()
        if (!gestos.some((gesto) => gesto.tipo.startsWith('venda.')
          && gesto.registroId === id && gestoPendente(gesto))) {
          const guardada = await lerRetrato<Venda>(`venda:${id}`)
          if (guardada) return guardada.dados
        }
      }
      const alvo = await localizar(id, true)
      if (alvo === 'servidor') {
        const recebida = await remoto.consultar(id)
        if (temBanco()) await guardarRetratoDaVenda(id, recebida)
        return recebida
      }
      return paraTela(alvo.venda, alvo.pendente)
    },
    async vincularCliente(id, clienteId) {
      const alvo = await localizar(id, false)
      if (alvo === 'servidor') return remoto.vincularCliente(id, clienteId)
      // Cliente inativo não entra em Venda nova; o cadastro do dispositivo inclui os criados na fila.
      if (!(await clientes.clientes()).some((cliente) => cliente.id === clienteId)) {
        throw new Error('Cliente inativo ou desconhecido neste dispositivo. Reative ou cadastre o '
          + 'Cliente antes de vincular.')
      }
      const dados: raiz.DadosDoVinculo = { clienteId }
      raiz.vincularCliente(alvo.venda, clienteId)
      await enfileirar('venda.vincularCliente', id, dados, operacoes(
        ultimoGesto(alvo.gestos, 'venda.', id), ultimoGesto(alvo.gestos, 'cliente.', clienteId)),
      alvo.venda.versao)
    },
    async adicionarItem(id, produto, quantidade, desconto) {
      const alvo = await localizar(id, false)
      if (alvo === 'servidor') return remoto.adicionarItem(id, produto.id, quantidade, desconto)
      if (desconto !== 0) exigirAdmin('Só ADMIN concede desconto.')
      // A busca só devolve item ativo, e o preço que fica na Venda é o que o operador viu. Um
      // Produto reajustado ou inativado no servidor antes de a Venda chegar lá vira revisão na
      // sincronização, sem mudar o que foi cobrado.
      const dados: raiz.DadosDoItem = {
        itemId: crypto.randomUUID(), produtoId: produto.id, nome: produto.nome, quantidade,
        precoUnitario: produto.preco, desconto,
        versaoProduto: typeof produto.versao === 'number' ? produto.versao : null,
      }
      raiz.adicionarItem(alvo.venda, dados)
      await enfileirar('venda.adicionarItem', id, dados, operacoes(
        ultimoGesto(alvo.gestos, 'venda.', id), ultimoGesto(alvo.gestos, 'produto.', produto.id)),
      alvo.venda.versao)
      return { id: dados.itemId }
    },
    async removerItem(id, itemId) {
      const alvo = await localizar(id, false)
      if (alvo === 'servidor') return remoto.removerItem(id, itemId)
      const dados: raiz.DadosDaRemocao = { itemId }
      raiz.removerItem(alvo.venda, itemId)
      await enfileirar('venda.removerItem', id, dados, operacoes(ultimoGesto(alvo.gestos, 'venda.', id)),
        alvo.venda.versao)
    },
    async descontar(id, valor) {
      const alvo = await localizar(id, false)
      if (alvo === 'servidor') return remoto.descontar(id, valor)
      exigirAdmin('Só ADMIN concede desconto.')
      const dados: raiz.DadosDoDesconto = { valor }
      raiz.aplicarDesconto(alvo.venda, valor)
      await enfileirar('venda.aplicarDesconto', id, dados, operacoes(ultimoGesto(alvo.gestos, 'venda.', id)),
        alvo.venda.versao)
    },
    async pagar(id, forma, valor, valorRecebido, pagamentoId, nsu) {
      // Conferida antes de escolher o caminho: com rede o servidor recusaria igual, e sem rede
      // ninguém mais confere antes de o gesto ficar gravado.
      const nsuDaParcela = raiz.normalizarNsu(nsu)
      exigirNsuSeAContaExige(forma, nsuDaParcela)
      const alvo = await localizar(id, false)
      if (alvo === 'servidor') return remoto.pagar(id, forma, valor, valorRecebido,
        pagamentoId ?? crypto.randomUUID(), nsuDaParcela ?? undefined)
      if (forma === 'FIADO') exigirAdmin('Só ADMIN registra Venda com FIADO.')
      const dados: raiz.DadosDaParcela = {
        pagamentoId: crypto.randomUUID(), forma, valor, valorRecebido: valorRecebido ?? null,
        nsu: nsuDaParcela,
      }
      const paga = raiz.registrarPagamento(alvo.venda, dados)
      await enfileirar('venda.registrarPagamento', id, dados,
        operacoes(ultimoGesto(alvo.gestos, 'venda.', id)), alvo.venda.versao)
      const parcela = paga.parcelas.find((candidata) => candidata.id === dados.pagamentoId)
      return { troco: reais(parcela?.trocoCentavos ?? 0) }
    },
    async cobrarPix(id, tentativaId, valor) {
      const alvo = await localizar(id, false)
      if (alvo === 'servidor') return remoto.cobrarPix(id, tentativaId, valor)
      throw new Error(raiz.PIX_FORA_DA_FILA)
    },
    async concluir(id) {
      const alvo = await localizar(id, false)
      if (alvo === 'servidor') {
        if (!temBanco()) return remoto.concluir(id)
        // A conclusão lança na gaveta do servidor o dinheiro da Venda.
        return caixa.escreverNaGavetaComRede(await sessaoDaVenda(id), () => remoto.concluir(id))
      }
      if (alvo.venda.parcelas.some((parcela) => parcela.forma === 'FIADO')) {
        exigirAdmin('Só ADMIN conclui Venda com FIADO.')
      }
      const sessao = await caixa.consultar(alvo.venda.sessaoCaixaId)
      if (sessao.status !== 'ABERTA') {
        throw new Error('A SessaoCaixa desta Venda não está ABERTA. A Venda só conclui com o caixa em '
          + 'que nasceu ainda aberto.')
      }
      const dados: raiz.DadosDaConclusao = { concluidoEm: new Date().toISOString() }
      raiz.concluir(alvo.venda, dados.concluidoEm)
      // A conclusão entra na fila da gaveta: o dinheiro dela precisa chegar ao caixa do servidor
      // antes da sangria que o contou.
      await enfileirar('venda.concluir', id, dados, operacoes(ultimoGesto(alvo.gestos, 'venda.', id),
        ultimoGestoDaGaveta(alvo.gestos, alvo.venda.sessaoCaixaId)), alvo.venda.versao)
    },
    async cancelar(id) {
      const alvo = await localizar(id, false)
      if (alvo !== 'servidor') throw new Error(CANCELAR_DEPOIS_DE_SINCRONIZAR)
      if (!temBanco()) return remoto.cancelar(id)
      // O cancelamento devolve da gaveta do servidor o dinheiro que a Venda tinha trazido.
      return caixa.escreverNaGavetaComRede(await sessaoDaVenda(id), () => remoto.cancelar(id))
    },
    async comprovante(id) {
      if (!navigator.onLine && temBanco()) {
        const guardado = await lerRetrato<Comprovante>(`comprovante:${id}`)
        if (guardado) return guardado.dados
      }
      const alvo = await localizar(id, true)
      if (alvo === 'servidor') {
        const recebido = await remoto.comprovante(id)
        if (temBanco()) await guardarRetrato(`comprovante:${id}`, recebido, 0)
        return recebido
      }
      return comprovanteDoDispositivo(alvo.venda, alvo.pendente)
    },
    async receber(id, valor, forma, recebimentoId?: string, nsu?: string) {
      // O recebimento só acontece com rede, e quem confere a exigência do NSU é o servidor.
      const nsuDoRecebimento = raiz.normalizarNsu(nsu) ?? undefined
      const receberNoServidor = () => remoto.receber(id, valor, forma,
        recebimentoId ?? crypto.randomUUID(), nsuDoRecebimento)
      // Só o dinheiro entra na gaveta, e na sessão aberta de quem recebe; Pix e cartão não passam por ela.
      if (!temBanco() || forma !== 'DINHEIRO') return receberNoServidor()
      return caixa.escreverNaGavetaComRede(undefined, receberNoServidor)
    },
    async comprovanteDeRecebimento(id, recebimentoId) {
      return remoto.comprovanteDeRecebimento(id, recebimentoId)
    },
  }
}
