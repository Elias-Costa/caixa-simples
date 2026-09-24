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
}>

type GestoGravado = GestoNaFila & { chave: string; dono: string }

interface BancoDaFila extends DBSchema {
  gestos: {
    key: string
    value: GestoGravado
    indexes: { porDono: string }
  }
  retratos: {
    key: string
    value: { chave: string; conta: string; tipo: string; dados: unknown }
  }
}

const NOME_DO_BANCO = 'caixa-simples-offline'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const MUDANCAS_PERMITIDAS: Record<EstadoDoGesto, EstadoDoGesto[]> = {
  queued: ['syncing'],
  syncing: ['queued', 'sent', 'failed', 'needs_review'],
  sent: [],
  failed: ['queued'],
  needs_review: [],
}

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

async function abrirBanco() {
  return openDB<BancoDaFila>(NOME_DO_BANCO, 2, {
    upgrade(banco, versaoAnterior) {
      if (versaoAnterior < 1) {
        const gestos = banco.createObjectStore('gestos', { keyPath: 'chave' })
        gestos.createIndex('porDono', 'dono')
      }
      if (versaoAnterior < 2) banco.createObjectStore('retratos', { keyPath: 'chave' })
    },
  })
}

/** O catálogo recebido da API pertence à Conta; os gestos pendentes continuam do usuário. */
export async function guardarRetrato<T>(tipo: string, dados: T): Promise<void> {
  const { dono, conta, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    await banco.put('retratos', { chave: `${conta}\u0000${tipo}`, conta, tipo, dados: structuredClone(dados) })
    conferirDono(dono, sessao)
  } finally {
    banco.close()
  }
}

export async function lerRetrato<T>(tipo: string): Promise<T | null> {
  const { dono, conta, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    const retrato = await banco.get('retratos', `${conta}\u0000${tipo}`)
    conferirDono(dono, sessao)
    return retrato ? structuredClone(retrato.dados) as T : null
  } finally {
    banco.close()
  }
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
    return semChavesInternas(gesto)
  } finally {
    banco.close()
  }
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

export async function mudarEstadoDoGesto(
  operacaoId: string,
  estadoEsperado: EstadoDoGesto,
  proximoEstado: EstadoDoGesto,
  resultado?: ResultadoDoGesto,
): Promise<GestoNaFila> {
  const { dono, sessao } = donoDaSessao()
  if (!MUDANCAS_PERMITIDAS[estadoEsperado].includes(proximoEstado)) {
    throw new Error('Transição de estado inválida')
  }
  if (proximoEstado === 'needs_review' && !resultado) {
    throw new Error('A revisão precisa informar se o gesto foi aplicado')
  }
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    const transacao = banco.transaction('gestos', 'readwrite')
    const gesto = await transacao.store.get(chaveDoGesto(dono, operacaoId))
    if (!gesto || gesto.estado !== estadoEsperado) throw new Error('Gesto não encontrado no estado esperado')
    const alterado = { ...gesto, estado: proximoEstado, resultado }
    await transacao.store.put(alterado)
    await transacao.done
    conferirDono(dono, sessao)
    return semChavesInternas(alterado)
  } finally {
    banco.close()
  }
}

/** Um envio sem resposta pode ser repetido com o mesmo UUID após reabrir o aplicativo. */
export async function recuperarEnviosInterrompidos(): Promise<void> {
  const { dono, sessao } = donoDaSessao()
  const banco = await abrirBanco()
  try {
    conferirDono(dono, sessao)
    const transacao = banco.transaction('gestos', 'readwrite')
    let cursor = await transacao.store.index('porDono').openCursor(dono)
    while (cursor) {
      if (cursor.value.estado === 'syncing') {
        await cursor.update({ ...cursor.value, estado: 'queued' })
      }
      cursor = await cursor.continue()
    }
    await transacao.done
    conferirDono(dono, sessao)
  } finally {
    banco.close()
  }
}

function semChavesInternas({ chave: _chave, dono: _dono, ...gesto }: GestoGravado): GestoNaFila {
  return gesto
}
