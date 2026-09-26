import { openDB, type DBSchema } from 'idb'
import { lerIdentidade, lerTokenDaSessao, sessaoAtual, sessaoDaAba } from '../sessao/armazenamento'
import { tokenExpirado } from '../sessao/token'

export type EstadoDoGesto = 'queued' | 'syncing' | 'sent' | 'failed' | 'needs_review'
export type ValorJson = null | boolean | number | string | ValorJson[] | { [campo: string]: ValorJson }

export type NovoGesto = {
  tipo: string
  registroId: string
  payload: ValorJson
  versaoBase?: number | string
  dependeDe?: string[]
}

export type ResultadoDoGesto = {
  aplicada: boolean
  versao?: number | string
  detalhe?: string
}

export type GestoNaFila = Readonly<{
  operacaoId: string
  registroId: string
  tipo: string
  payload: ValorJson
  versaoBase?: number | string
  dependeDe: string[]
  estado: EstadoDoGesto
  criadoEm: string
  resultado?: ResultadoDoGesto
  /** Em que posição, entre todos os resultados gravados neste aparelho, este chegou. */
  ordemDoResultado?: number
  /** Por que o último envio ficou sem resposta; o gesto é repetido com o mesmo id. */
  falha?: string
  /** Quando quem operou conferiu a revisão neste aparelho. */
  conferidoEm?: string
}>

/** A lista lida do servidor e a ordem do último resultado gravado quando o pedido saiu. */
export type Retrato<T> = { dados: T; ordemDaLeitura: number }

/** O que o servidor disse de um gesto enviado, ou por que ele ficou sem resposta. */
export type Desfecho =
  | { operacaoId: string; estado: 'sent' | 'needs_review'; resultado: ResultadoDoGesto }
  | { operacaoId: string; estado: 'failed'; falha: string }
  | { operacaoId: string; estado: 'queued' }

export type MudancaDaFila = 'gesto-novo' | 'estado' | 'conferencia'

type GestoGravado = GestoNaFila & { chave: string; dono: string }

interface BancoDaFila extends DBSchema {
  gestos: {
    key: string
    value: GestoGravado
    indexes: { porDono: string }
  }
  retratos: {
    key: string
    value: { chave: string; conta: string; tipo: string; dados: unknown; ordemDaLeitura?: number }
  }
  contadores: {
    key: string
    value: { chave: string; valor: number }
  }
}

const NOME_DO_BANCO = 'caixa-simples-offline'
const CONTADOR_DE_RESULTADOS = 'resultados'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const MUDANCAS_PERMITIDAS: Record<EstadoDoGesto, EstadoDoGesto[]> = {
  queued: ['syncing'],
  // De volta à fila só quando o servidor recusou a sessão sem ler o lote. Qualquer outro envio sem
  // resposta pode ter chegado lá e fica como falha.
  syncing: ['queued', 'sent', 'failed', 'needs_review'],
  sent: [],
  // A falha é repetida com o mesmo id e nunca volta à fila como se não tivesse saído: o servidor
  // pode ter aplicado o gesto, e um retrato lido nesse meio tempo não pode recebê-lo de novo.
  failed: ['syncing'],
  needs_review: [],
}
const ouvintes = new Set<(mudanca: MudancaDaFila) => void>()

function donoDaSessao(): { dono: string; conta: string; sessao: string } {
  const sessao = sessaoAtual()
  const identidade = lerIdentidade()
  if (!sessao || sessaoDaAba() !== sessao || !identidade) {
    throw new Error('A sessão autenticada mudou')
  }
  const token = lerTokenDaSessao(sessao)
  if (!token || tokenExpirado(token)) throw new Error('Entre novamente para usar a fila')
  return { dono: `${identidade.contaId}\u0000${identidade.usuarioId}`, conta: identidade.contaId, sessao }
}

function conferirDono(dono: string, sessao: string): void {
  const atual = donoDaSessao()
  if (atual.dono !== dono || atual.sessao !== sessao) {
    throw new Error('A sessão autenticada mudou')
  }
}

function chaveDoGesto(dono: string, operacaoId: string): string {
  return `${dono}\u0000${operacaoId}`
}

function avisar(mudanca: MudancaDaFila): void {
  for (const ouvinte of ouvintes) ouvinte(mudanca)
}

async function abrirBanco() {
  return openDB<BancoDaFila>(NOME_DO_BANCO, 3, {
    upgrade(banco, versaoAnterior) {
      if (versaoAnterior < 1) {
        const gestos = banco.createObjectStore('gestos', { keyPath: 'chave' })
        gestos.createIndex('porDono', 'dono')
      }
      if (versaoAnterior < 2) banco.createObjectStore('retratos', { keyPath: 'chave' })
      if (versaoAnterior < 3) banco.createObjectStore('contadores', { keyPath: 'chave' })
    },
  })
}

/** Se há sessão válida para ler e gravar a fila; sem ela, ninguém envia nem conta nada. */
export function sessaoPodeUsarAFila(): boolean {
  try {
    donoDaSessao()
    return true
  } catch {
    return false
  }
}

/** Avisa quem mostra ou envia a fila, nesta aba, a cada gesto novo, mudança de estado ou conferência. */
export function aoMudarFila(ouvinte: (mudanca: MudancaDaFila) => void): () => void {
  ouvintes.add(ouvinte)
  return () => { ouvintes.delete(ouvinte) }
}

/**
 * O catálogo recebido da API pertence à Conta; os gestos pendentes continuam do usuário. Os
 * retratos da lista são gravados juntos, com a mesma ordem da leitura.
 */
export async function guardarRetratos(retratos: { tipo: string; dados: unknown }[],
  ordemDaLeitura: number): Promise<void> {
  const { dono, conta, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    const transacao = banco.transaction('retratos', 'readwrite')
    for (const { tipo, dados } of retratos) {
      await transacao.store.put({ chave: `${conta}\u0000${tipo}`, conta, tipo,
        dados: structuredClone(dados), ordemDaLeitura })
    }
    await transacao.done
    conferirDono(dono, sessao)
  } finally {
    banco.close()
  }
}

export async function guardarRetrato<T>(tipo: string, dados: T, ordemDaLeitura: number): Promise<void> {
  await guardarRetratos([{ tipo, dados }], ordemDaLeitura)
}

/** O retrato gravado antes desta versão não tem ordem: vale como anterior a todo resultado. */
export async function lerRetrato<T>(tipo: string): Promise<Retrato<T> | null> {
  const { dono, conta, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    const retrato = await banco.get('retratos', `${conta}\u0000${tipo}`)
    conferirDono(dono, sessao)
    return retrato
      ? { dados: structuredClone(retrato.dados) as T, ordemDaLeitura: retrato.ordemDaLeitura ?? 0 }
      : null
  } finally {
    banco.close()
  }
}

async function momentoDaFila(): Promise<{ ordem: number; incerto: boolean }> {
  const { dono, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    const transacao = banco.transaction(['gestos', 'contadores'], 'readonly')
    const gestos = await transacao.objectStore('gestos').index('porDono').getAll(dono)
    const contador = await transacao.objectStore('contadores').get(CONTADOR_DE_RESULTADOS)
    await transacao.done
    conferirDono(dono, sessao)
    return {
      ordem: contador?.valor ?? 0,
      incerto: gestos.some((gesto) => gesto.estado === 'syncing' || gesto.estado === 'failed'),
    }
  } finally {
    banco.close()
  }
}

/**
 * Lê do servidor e guarda o retrato só quando se sabe quais resultados ele já contém.
 *
 * O retrato é a base das projeções, e todo gesto cujo resultado chegou depois da leitura é
 * reaplicado por cima dele. Por isso a leitura só é guardada se, antes e depois do pedido, nenhum
 * gesto deste usuário estava sendo enviado ou com falha, que o servidor pode ter aplicado ou não,
 * e nenhum resultado novo foi gravado no meio. Fora disso os dados voltam sem ser guardados, e a
 * projeção continua sobre o retrato anterior mais os gestos, que segue certa.
 */
export async function lerDoServidor<T>(buscar: () => Promise<T>,
  guardar: (dados: T, ordemDaLeitura: number) => Promise<void>): Promise<T> {
  const antes = await momentoDaFila()
  const dados = await buscar()
  const depois = await momentoDaFila()
  if (!antes.incerto && !depois.incerto && antes.ordem === depois.ordem) {
    await guardar(dados, antes.ordem)
  }
  return dados
}

/**
 * A ordem em que os gestos são reaplicados e enviados: cada um depois das dependências que estão
 * na lista e, entre os livres, o mais antigo primeiro. Uma dependência fora da lista pertence a
 * outro registro e não segura ninguém aqui.
 */
export function ordenarPorDependencia(gestos: readonly GestoNaFila[]): GestoNaFila[] {
  const restantes = [...gestos]
  const ordenados: GestoNaFila[] = []
  while (restantes.length) {
    const indice = restantes.findIndex((gesto) =>
      gesto.dependeDe.every((id) => !restantes.some((outro) => outro.operacaoId === id)))
    if (indice < 0) throw new Error('Dependências cíclicas na fila local.')
    ordenados.push(restantes.splice(indice, 1)[0])
  }
  return ordenados
}

/** O gesto recusado na revisão não produziu efeito; o aplicado com pendência, sim. */
export function gestoAplicavel(gesto: GestoNaFila): boolean {
  return gesto.estado !== 'needs_review' || gesto.resultado?.aplicada === true
}

/** Ainda sem resultado do servidor: na fila, sendo enviado ou com falha no último envio. */
export function gestoPendente(gesto: GestoNaFila): boolean {
  return gesto.estado === 'queued' || gesto.estado === 'syncing' || gesto.estado === 'failed'
}

/** A revisão que o servidor devolveu para o gesto aplicado, quando o registro tem revisão. */
export function versaoDoResultado(gesto: GestoNaFila): number | undefined {
  return typeof gesto.resultado?.versao === 'number' ? gesto.resultado.versao : undefined
}

/**
 * Se o retrato já contém o efeito do gesto: o resultado foi gravado antes de a leitura sair, e o
 * servidor aplica antes de responder. Gesto sem resultado nunca está no retrato.
 */
export function jaEstaNoRetrato(gesto: GestoNaFila, retrato: { ordemDaLeitura: number } | null | undefined): boolean {
  return !!retrato && gesto.ordemDoResultado !== undefined && gesto.ordemDoResultado <= retrato.ordemDaLeitura
}

/** A tela só pode avançar depois que esta promessa confirmar a gravação no IndexedDB. */
export async function enfileirarGesto(novo: NovoGesto): Promise<GestoNaFila> {
  const { dono, sessao } = donoDaSessao()
  if (!novo.tipo.trim() || !UUID.test(novo.registroId)) {
    throw new Error('Tipo e UUID do registro são obrigatórios')
  }
  const operacaoId = crypto.randomUUID()
  const dependeDe = [...new Set(novo.dependeDe ?? [])]
  const gesto: GestoGravado = {
    chave: chaveDoGesto(dono, operacaoId),
    dono,
    operacaoId,
    registroId: novo.registroId,
    tipo: novo.tipo,
    payload: structuredClone(novo.payload),
    versaoBase: novo.versaoBase,
    dependeDe,
    estado: 'queued',
    criadoEm: new Date().toISOString(),
  }
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    const transacao = banco.transaction('gestos', 'readwrite')
    for (const dependencia of dependeDe) {
      if (!(await transacao.store.get(chaveDoGesto(dono, dependencia)))) {
        throw new Error('Dependência fora da fila da sessão')
      }
    }
    await transacao.store.add(gesto)
    await transacao.done
    conferirDono(dono, sessao)
  } finally {
    banco.close()
  }
  avisar('gesto-novo')
  return semChavesInternas(gesto)
}

export async function listarGestos(): Promise<GestoNaFila[]> {
  const { dono, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    const gestos = await banco.getAllFromIndex('gestos', 'porDono', dono)
    conferirDono(dono, sessao)
    return gestos.map(semChavesInternas).sort((a, b) => a.criadoEm.localeCompare(b.criadoEm))
  } finally {
    banco.close()
  }
}

type Mudanca = {
  operacaoId: string
  de: EstadoDoGesto[]
  para: EstadoDoGesto
  resultado?: ResultadoDoGesto
  falha?: string
}

/**
 * Aplica as mudanças numa transação só e devolve os gestos que mudaram. Cada resultado novo
 * recebe a ordem seguinte do contador, na mesma transação que muda o estado. O gesto que não está
 * mais no estado esperado fica como está, a não ser que a mudança seja exigida.
 */
async function mudarGestos(mudancas: Mudanca[], exigida: boolean): Promise<GestoNaFila[]> {
  const { dono, sessao } = donoDaSessao()
  for (const mudanca of mudancas) {
    if (mudanca.de.some((estado) => !MUDANCAS_PERMITIDAS[estado].includes(mudanca.para))) {
      throw new Error('Transição de estado inválida')
    }
    if ((mudanca.para === 'sent' || mudanca.para === 'needs_review') && !mudanca.resultado) {
      throw new Error('O resultado precisa informar se o gesto foi aplicado')
    }
  }
  const banco = await abrirBanco()
  const mudados: GestoGravado[] = []
  try {
    conferirDono(dono, sessao)
    const transacao = banco.transaction(['gestos', 'contadores'], 'readwrite')
    const gestos = transacao.objectStore('gestos')
    const contadores = transacao.objectStore('contadores')
    const encontrados: { mudanca: Mudanca; gesto: GestoGravado }[] = []
    for (const mudanca of mudancas) {
      const gesto = await gestos.get(chaveDoGesto(dono, mudanca.operacaoId))
      if (gesto && mudanca.de.includes(gesto.estado)) encontrados.push({ mudanca, gesto })
      else if (exigida) throw new Error('Gesto não encontrado no estado esperado')
    }
    let ordem = (await contadores.get(CONTADOR_DE_RESULTADOS))?.valor ?? 0
    for (const { mudanca, gesto } of encontrados) {
      const comResultado = mudanca.para === 'sent' || mudanca.para === 'needs_review'
      if (comResultado) ordem++
      const alterado: GestoGravado = {
        ...gesto,
        estado: mudanca.para,
        resultado: comResultado ? mudanca.resultado : gesto.resultado,
        ordemDoResultado: comResultado ? ordem : gesto.ordemDoResultado,
        falha: mudanca.para === 'failed' ? mudanca.falha : undefined,
      }
      await gestos.put(alterado)
      mudados.push(alterado)
    }
    await contadores.put({ chave: CONTADOR_DE_RESULTADOS, valor: ordem })
    await transacao.done
    conferirDono(dono, sessao)
  } finally {
    banco.close()
  }
  if (mudados.length) avisar('estado')
  return mudados.map(semChavesInternas)
}

export async function mudarEstadoDoGesto(
  operacaoId: string,
  estadoEsperado: EstadoDoGesto,
  proximoEstado: EstadoDoGesto,
  resultado?: ResultadoDoGesto,
): Promise<GestoNaFila> {
  const [mudado] = await mudarGestos([{ operacaoId, de: [estadoEsperado], para: proximoEstado,
    resultado }], true)
  return mudado
}

/**
 * Marca como em envio os gestos pedidos que ainda esperam resultado, na fila ou com falha, e
 * devolve os marcados, na ordem pedida.
 */
export async function iniciarEnvio(operacaoIds: string[]): Promise<GestoNaFila[]> {
  return mudarGestos(operacaoIds.map((operacaoId) => ({ operacaoId, de: ['queued', 'failed'],
    para: 'syncing' })), false)
}

/** Grava o desfecho de cada gesto em envio. Um desfecho atrasado não desfaz o que já mudou. */
export async function registrarDesfechos(desfechos: Desfecho[]): Promise<void> {
  await mudarGestos(desfechos.map((desfecho) => ({
    operacaoId: desfecho.operacaoId,
    de: ['syncing'],
    para: desfecho.estado,
    resultado: 'resultado' in desfecho ? desfecho.resultado : undefined,
    falha: 'falha' in desfecho ? desfecho.falha : undefined,
  })), false)
}

/**
 * O envio que ficou sem resposta, por recarga ou fechamento do aplicativo, pode ter chegado ao
 * servidor: vira falha, e é repetido com o mesmo id. Só quem garante que nenhum envio está em
 * curso pode chamar isto.
 */
export async function recuperarEnviosInterrompidos(): Promise<void> {
  const { dono, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  let recuperados = 0
  try {
    conferirDono(dono, sessao)
    const transacao = banco.transaction('gestos', 'readwrite')
    let cursor = await transacao.store.index('porDono').openCursor(dono)
    while (cursor) {
      if (cursor.value.estado === 'syncing') {
        await cursor.update({ ...cursor.value, estado: 'failed',
          falha: 'O envio foi interrompido antes da resposta do servidor.' })
        recuperados++
      }
      cursor = await cursor.continue()
    }
    await transacao.done
    conferirDono(dono, sessao)
  } finally {
    banco.close()
  }
  if (recuperados) avisar('estado')
}

/**
 * Registra que quem operou viu a revisão deste gesto. O estado não muda: o gesto continua em
 * revisão, sai do aviso e fica no histórico. Conferir de novo mantém a primeira vez.
 */
export async function conferirGesto(operacaoId: string): Promise<GestoNaFila> {
  const { dono, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  let conferido: GestoGravado
  try {
    conferirDono(dono, sessao)
    const transacao = banco.transaction('gestos', 'readwrite')
    const gesto = await transacao.store.get(chaveDoGesto(dono, operacaoId))
    if (!gesto || gesto.estado !== 'needs_review') throw new Error('Só o gesto em revisão é conferido.')
    conferido = gesto.conferidoEm ? gesto : { ...gesto, conferidoEm: new Date().toISOString() }
    await transacao.store.put(conferido)
    await transacao.done
    conferirDono(dono, sessao)
  } finally {
    banco.close()
  }
  avisar('conferencia')
  return semChavesInternas(conferido)
}

function semChavesInternas({ chave: _chave, dono: _dono, ...gesto }: GestoGravado): GestoNaFila {
  return gesto
}
