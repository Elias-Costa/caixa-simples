import { caixa, type MovimentoCaixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { lerIdentidade } from '../sessao/armazenamento'
import { centavos, centavosDoSaldo, reais } from './dinheiro'
import {
  enfileirarGesto, gestoAplicavel, gestoPendente, guardarRetrato, jaEstaNoRetrato, lerDoServidor,
  lerRetrato, listarGestos, ordenarPorDependencia, versaoDoResultado, type GestoNaFila,
} from './fila'
import { dinheiroNaGaveta, projetarVendas, type DadosDoInicio } from './raizDaVenda'

type CaixaRemoto = typeof caixa
/** Cada sessão guardada leva a ordem da leitura que a trouxe, porque cada uma é lida numa hora. */
type SessaoGuardada = SessaoCaixa & { ordemDaLeitura?: number }
const prefixo = 'caixa.'

function semBanco(): boolean {
  if ('indexedDB' in globalThis) return false
  if (!navigator.onLine) throw new Error('O armazenamento local não está disponível para operar sem rede.')
  return true
}

function diaNoBalcao(instante: string): string {
  const data = new Date(instante)
  const partes = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Bahia', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(data)
  const campo = (tipo: string) => partes.find((parte) => parte.type === tipo)?.value ?? ''
  return `${campo('year')}-${campo('month')}-${campo('day')}`
}

function usuarioAtual(): string {
  const id = lerIdentidade()?.usuarioId
  if (!id) throw new Error('Entre novamente para operar o caixa.')
  return id
}

function tipoDoRetrato(): string { return `caixa:${usuarioAtual()}:sessoes` }

function versaoLida(sessao: SessaoCaixa): number {
  if (typeof sessao.versao !== 'number') {
    throw new Error('A revisão desta SessaoCaixa precisa ser carregada online antes da alteração.')
  }
  return sessao.versao
}

/** As Vendas registradas neste dispositivo dentro da sessão, reconhecidas pelo gesto de início. */
function vendasDaSessao(gestos: GestoNaFila[], id: string): Set<string> {
  return new Set(gestos.filter((gesto) => gesto.tipo === 'venda.iniciar'
    && (gesto.payload as DadosDoInicio).sessaoCaixaId === id).map((gesto) => gesto.registroId))
}

/**
 * Tudo o que pertence à sessão no dispositivo: os gestos de caixa dela e todos os gestos das
 * Vendas registradas nela. O fechamento depende de todos, e a sessão continua pendente enquanto
 * qualquer um deles não chegou ao servidor.
 */
function gestosDaSessao(gestos: GestoNaFila[], id: string): GestoNaFila[] {
  const vendas = vendasDaSessao(gestos, id)
  return ordenarPorDependencia(gestos.filter((gesto) => gestoAplicavel(gesto)
    && ((gesto.tipo.startsWith(prefixo) && gesto.registroId === id)
      || (gesto.tipo.startsWith('venda.') && vendas.has(gesto.registroId)))))
}

/**
 * Os gestos que mudam o esperado da gaveta formam uma fila única por sessão: abertura, sangria,
 * suprimento, fechamento e a conclusão de cada Venda registrada no dispositivo. Cada gesto novo
 * dessa fila depende do último, porque foi conferido contra um esperado que já contava os
 * anteriores: a sangria que só coube na gaveta por causa de uma Venda em dinheiro precisa encontrar
 * essa Venda aplicada no servidor.
 */
function gestosDaGaveta(gestos: GestoNaFila[], id: string): GestoNaFila[] {
  const vendas = vendasDaSessao(gestos, id)
  return ordenarPorDependencia(gestos.filter((gesto) => gestoAplicavel(gesto)
    && ((gesto.tipo.startsWith(prefixo) && gesto.registroId === id)
      || (gesto.tipo === 'venda.concluir' && vendas.has(gesto.registroId)))))
}

function pendente(gestos: GestoNaFila[]): boolean {
  return gestos.some(gestoPendente)
}

function algumGestoDeCaixaPendente(gestos: GestoNaFila[]): boolean {
  return gestos.some((gesto) => gesto.tipo.startsWith(prefixo) && gestoPendente(gesto))
}

/** Se a sessão tem gesto no dispositivo ainda sem resultado do servidor, de caixa ou de Venda. */
export function sessaoTemPendencia(gestos: GestoNaFila[], id: string): boolean {
  return pendente(gestosDaSessao(gestos, id))
}

/** O gesto do qual depende o próximo que mudar o esperado da gaveta desta sessão. */
export function ultimoGestoDaGaveta(gestos: GestoNaFila[], id: string): GestoNaFila | undefined {
  return gestosDaGaveta(gestos, id).at(-1)
}

async function retratos(): Promise<SessaoGuardada[]> {
  return (await lerRetrato<SessaoGuardada[]>(tipoDoRetrato()))?.dados ?? []
}

async function guardar(sessoes: SessaoCaixa[], ordemDaLeitura: number): Promise<void> {
  const atuais = new Map((await retratos()).map((sessao) => [sessao.id, sessao]))
  for (const sessao of sessoes) {
    if (sessao.usuarioId !== usuarioAtual()) continue
    const anterior = atuais.get(sessao.id)
    if (sessao.movimentos) {
      atuais.set(sessao.id, { ...sessao, ordemDaLeitura })
    } else if (!anterior?.movimentos || anterior.versao === sessao.versao) {
      // O resumo do histórico não traz o extrato; o guardado continua valendo porque é da mesma
      // revisão.
      atuais.set(sessao.id, { ...anterior, ...sessao, movimentos: anterior?.movimentos, ordemDaLeitura })
    }
    // O resumo de outra revisão não substitui um extrato guardado: ficariam os movimentos de uma
    // leitura com o esperado de outra. O extrato é trocado na próxima consulta da sessão.
  }
  // Cada sessão leva a própria ordem; a do retrato inteiro não é lida.
  await guardarRetrato(tipoDoRetrato(), [...atuais.values()], 0)
}

/**
 * A sessão guardada mais os gestos da gaveta que ela ainda não contém. O gesto cujo resultado
 * chegou antes da leitura já está no esperado do servidor, e somá-lo de novo dobraria a sangria ou
 * a Venda. O confirmado depois da leitura entra com a revisão que o servidor devolveu.
 */
function projetar(base: SessaoGuardada | undefined, gestos: GestoNaFila[], id: string): SessaoCaixa | undefined {
  let sessao: SessaoCaixa | undefined
  if (base) {
    const { ordemDaLeitura: _ordem, ...guardada } = base
    sessao = { ...guardada, movimentos: [...(guardada.movimentos ?? [])] }
  }
  const leitura = base ? { ordemDaLeitura: base.ordemDaLeitura ?? 0 } : undefined
  const vendas = projetarVendas(gestos, usuarioAtual())
  for (const gesto of gestosDaGaveta(gestos, id)) {
    if (jaEstaNoRetrato(gesto, leitura)) continue
    if (gesto.tipo === 'caixa.abrir') {
      // A sessão que já veio do servidor foi aberta lá; reabri-la aqui apagaria os movimentos.
      if (!sessao) {
        const dados = gesto.payload as { valorAbertura: number; abertaEm: string }
        sessao = { id, usuarioId: usuarioAtual(), versao: versaoDoResultado(gesto) ?? 0,
          valorAbertura: dados.valorAbertura, valorFechamentoEsperado: dados.valorAbertura,
          valorFechamentoContado: null, diferenca: null, abertaEm: dados.abertaEm, fechadaEm: null,
          status: 'ABERTA', movimentos: [] }
      }
      continue
    }
    if (!sessao) continue
    if (gesto.tipo === 'caixa.sangrar' || gesto.tipo === 'caixa.suprir') {
      const dados = gesto.payload as { valor: number; motivo: string; criadoEm: string }
      const tipo = gesto.tipo === 'caixa.sangrar' ? 'SANGRIA' : 'SUPRIMENTO'
      const movimento: MovimentoCaixa = { id: gesto.operacaoId, tipo, valor: dados.valor,
        motivo: dados.motivo, vendaId: null, recebimentoId: null, criadoEm: dados.criadoEm }
      const esperado = centavosDoSaldo(sessao.valorFechamentoEsperado)
      const quantia = centavos(dados.valor, 'Valor do movimento')
      sessao = { ...sessao, movimentos: [...(sessao.movimentos ?? []), movimento],
        versao: versaoDoResultado(gesto) ?? versaoLida(sessao) + 1,
        valorFechamentoEsperado: reais(esperado + (tipo === 'SUPRIMENTO' ? quantia : -quantia)) }
    }
    const venda = gesto.tipo === 'venda.concluir' ? vendas.get(gesto.registroId) : undefined
    const emDinheiro = venda ? dinheiroNaGaveta(venda) : 0
    // Como no servidor: só o dinheiro em espécie entra na gaveta, e sem dinheiro não há movimento.
    if (venda && emDinheiro > 0) {
      const movimento: MovimentoCaixa = { id: gesto.operacaoId, tipo: 'VENDA', valor: reais(emDinheiro),
        motivo: null, vendaId: venda.id, recebimentoId: null, criadoEm: venda.concluidoEm ?? gesto.criadoEm }
      // A Venda não devolve a revisão da sessão; o dispositivo conta a entrada do dinheiro.
      sessao = { ...sessao, movimentos: [...(sessao.movimentos ?? []), movimento],
        versao: versaoLida(sessao) + 1,
        valorFechamentoEsperado: reais(centavosDoSaldo(sessao.valorFechamentoEsperado) + emDinheiro) }
    }
    if (gesto.tipo === 'caixa.fechar') {
      const dados = gesto.payload as { valorContado: number; fechadaEm: string }
      sessao = { ...sessao, status: 'FECHADA', fechadaEm: dados.fechadaEm,
        versao: versaoDoResultado(gesto) ?? versaoLida(sessao) + 1,
        valorFechamentoContado: dados.valorContado,
        diferenca: reais(centavosDoSaldo(sessao.valorFechamentoEsperado)
          - centavos(dados.valorContado, 'Valor contado')) }
    }
  }
  return sessao ? { ...sessao, pendenteSincronizacao: pendente(gestosDaSessao(gestos, id)) } : undefined
}

async function locais(): Promise<SessaoCaixa[]> {
  const [base, gestos] = await Promise.all([retratos(), listarGestos()])
  const ids = new Set([...base.map((sessao) => sessao.id),
    ...gestos.filter((gesto) => gesto.tipo === 'caixa.abrir').map((gesto) => gesto.registroId)])
  return [...ids].map((id) => projetar(base.find((sessao) => sessao.id === id), gestos, id))
    .filter((sessao): sessao is SessaoCaixa => !!sessao)
}

async function local(id: string): Promise<SessaoCaixa> {
  const sessao = (await locais()).find((item) => item.id === id)
  if (!sessao) throw new Error('Sessão de caixa não encontrada neste dispositivo.')
  if (sessao.usuarioId !== usuarioAtual()) throw new Error('Este caixa pertence a outro operador.')
  return sessao
}

async function enfileirar(tipo: string, id: string, dados: object, dependeDe: string[] = [],
  versaoBase?: number): Promise<void> {
  await enfileirarGesto({ tipo, registroId: id, payload: JSON.parse(JSON.stringify(dados)),
    dependeDe, versaoBase })
}

async function consultarLocalOuRemoto(remoto: CaixaRemoto, id: string): Promise<SessaoCaixa> {
  if (semBanco()) return remoto.consultar(id)
  const gestos = gestosDaSessao(await listarGestos(), id)
  if (!navigator.onLine || pendente(gestos)) return local(id)
  try {
    // Sem gesto pendente, o servidor já tem tudo desta sessão, e a resposta vale como está.
    return await lerDoServidor(() => remoto.consultar(id), (sessao, ordem) => guardar([sessao], ordem))
  } catch (falha) {
    if (!(falha instanceof SemConexao)) throw falha
    return local(id)
  }
}

/** Projeta apenas o caixa do usuário autenticado; ADMIN consulta outros caixas pela API online. */
export function criarCaixaLocal(remoto: CaixaRemoto = caixa): CaixaRemoto {
  return {
    async abertaDoOperadorAtual() {
      if (semBanco()) return remoto.abertaDoOperadorAtual()
      if (navigator.onLine) {
        try {
          const recebida = await lerDoServidor(() => remoto.abertaDoOperadorAtual(),
            async (sessao, ordem) => { if (sessao) await guardar([sessao], ordem) })
          const gestos = await listarGestos()
          const sobreposta = (await locais()).find((sessao) => sessao.status === 'ABERTA'
            && pendente(gestosDaSessao(gestos, sessao.id)))
          return sobreposta ?? (recebida && !gestosDaSessao(gestos, recebida.id)
            .some((gesto) => gesto.tipo === 'caixa.fechar') ? recebida : undefined)
        } catch (falha) {
          if (!(falha instanceof SemConexao)) throw falha
        }
      }
      return (await locais()).find((sessao) => sessao.usuarioId === usuarioAtual()
        && sessao.status === 'ABERTA')
    },
    async consultar(id) { return consultarLocalOuRemoto(remoto, id) },
    async historico(dia, operadorId) {
      if (semBanco()) return remoto.historico(dia, operadorId)
      if (navigator.onLine) {
        try {
          const recebidas = await lerDoServidor(() => remoto.historico(dia, operadorId),
            (sessoes, ordem) => guardar(sessoes, ordem))
          const gestos = await listarGestos()
          const doUsuario = (await locais()).filter((sessao) => diaNoBalcao(sessao.abertaEm) === dia)
          const mescladas = new Map(recebidas.map((sessao) => [sessao.id, sessao]))
          for (const sessao of doUsuario) {
            if (pendente(gestosDaSessao(gestos, sessao.id))) mescladas.set(sessao.id, sessao)
          }
          return [...mescladas.values()]
        } catch (falha) {
          if (!(falha instanceof SemConexao)) throw falha
        }
      }
      return (await locais()).filter((sessao) => diaNoBalcao(sessao.abertaEm) === dia
        && (!operadorId || operadorId === usuarioAtual()))
    },
    async abrir(valorAbertura) {
      if (semBanco()) return remoto.abrir(valorAbertura)
      centavos(valorAbertura, 'Valor de abertura')
      const gestos = await listarGestos()
      if (navigator.onLine && !algumGestoDeCaixaPendente(gestos)) {
        return remoto.abrir(valorAbertura)
      }
      if ((await locais()).some((sessao) => sessao.status === 'ABERTA')) {
        throw new Error('Já existe uma SessaoCaixa aberta neste dispositivo.')
      }
      const id = crypto.randomUUID()
      const ultimo = ordenarPorDependencia(gestos.filter((gesto) => gesto.tipo.startsWith(prefixo))).at(-1)
      await enfileirar('caixa.abrir', id, { valorAbertura,
        abertaEm: new Date().toISOString() }, ultimo ? [ultimo.operacaoId] : [])
      return { id }
    },
    async sangrar(id, valorMovimento, motivo) {
      if (semBanco()) return remoto.sangrar(id, valorMovimento, motivo)
      if (navigator.onLine && (await retratos()).every((sessao) => sessao.id !== id)
        && lerIdentidade()?.perfil === 'ADMIN') return remoto.sangrar(id, valorMovimento, motivo)
      return movimentar(remoto, id, valorMovimento, motivo, 'caixa.sangrar')
    },
    async suprir(id, valorMovimento, motivo) {
      if (semBanco()) return remoto.suprir(id, valorMovimento, motivo)
      if (navigator.onLine && (await retratos()).every((sessao) => sessao.id !== id)
        && lerIdentidade()?.perfil === 'ADMIN') return remoto.suprir(id, valorMovimento, motivo)
      return movimentar(remoto, id, valorMovimento, motivo, 'caixa.suprir')
    },
    async fechar(id, valorContado) {
      if (semBanco()) return remoto.fechar(id, valorContado)
      const contado = centavos(valorContado, 'Valor contado')
      if (navigator.onLine && (await retratos()).every((sessao) => sessao.id !== id)
        && lerIdentidade()?.perfil === 'ADMIN') return remoto.fechar(id, valorContado)
      const sessao = await local(id)
      if (sessao.status !== 'ABERTA') throw new Error('SessaoCaixa já fechada; não pode fechar novamente.')
      const gestos = gestosDaSessao(await listarGestos(), id)
      if (navigator.onLine && !pendente(gestos)) {
        return remoto.fechar(id, valorContado)
      }
      // Todas as operações conhecidas desta sessão, inclusive cada gesto das Vendas registradas
      // nela, precisam chegar ao servidor antes do fechamento.
      await enfileirar('caixa.fechar', id, { valorContado, fechadaEm: new Date().toISOString() },
        gestos.map((gesto) => gesto.operacaoId), versaoLida(sessao))
      return { diferenca: reais(centavosDoSaldo(sessao.valorFechamentoEsperado) - contado) }
    },
  }
}

async function movimentar(remoto: CaixaRemoto, id: string, quantia: number, motivo: string,
  tipo: 'caixa.sangrar' | 'caixa.suprir'): Promise<void> {
  const cent = centavos(quantia, 'Valor do movimento')
  const sessao = await local(id)
  if (sessao.status !== 'ABERTA') throw new Error('SessaoCaixa fechada não aceita movimento.')
  if (!motivo.trim()) throw new Error('Motivo é obrigatório para sangria e suprimento (RF14).')
  // Com o esperado já negativo toda sangria é recusada, como no servidor: não se tira da gaveta o
  // que não está lá, e o suprimento é que corrige o saldo.
  if (tipo === 'caixa.sangrar' && cent > centavosDoSaldo(sessao.valorFechamentoEsperado)) {
    throw new Error('Sangria maior que o saldo esperado da gaveta.')
  }
  const gestos = await listarGestos()
  if (navigator.onLine && !pendente(gestosDaSessao(gestos, id))) {
    if (tipo === 'caixa.sangrar') return remoto.sangrar(id, quantia, motivo)
    return remoto.suprir(id, quantia, motivo)
  }
  const ultimo = ultimoGestoDaGaveta(gestos, id)
  await enfileirar(tipo, id, { valor: quantia, motivo: motivo.trim(), criadoEm: new Date().toISOString() },
    ultimo ? [ultimo.operacaoId] : [], versaoLida(sessao))
}
