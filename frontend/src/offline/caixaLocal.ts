import { caixa, type MovimentoCaixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { lerIdentidade } from '../sessao/armazenamento'
import { enfileirarGesto, guardarRetrato, lerRetrato, listarGestos, type GestoNaFila } from './fila'

type CaixaRemoto = typeof caixa
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

function centavos(valor: number, campo: string): number {
  const resultado = Math.round(valor * 100)
  if (!Number.isFinite(valor) || valor < 0 || !Number.isSafeInteger(resultado)
    || Math.abs(resultado / 100 - valor) > 1e-8 || resultado > 999999999999) {
    throw new Error(`${campo} deve ser um valor não negativo com até duas casas decimais.`)
  }
  return resultado
}

function valor(centavos: number): number { return centavos / 100 }

function versaoLida(sessao: SessaoCaixa): number {
  if (typeof sessao.versao !== 'number') {
    throw new Error('A revisão desta SessaoCaixa precisa ser carregada online antes da alteração.')
  }
  return sessao.versao
}

function ordenarGestos(gestos: GestoNaFila[]): GestoNaFila[] {
  const restantes = [...gestos]
  const ordenados: GestoNaFila[] = []
  while (restantes.length) {
    const indice = restantes.findIndex((gesto) => gesto.dependeDe.every((id) =>
      !restantes.some((outro) => outro.operacaoId === id)))
    if (indice < 0) throw new Error('Dependências cíclicas na SessaoCaixa local.')
    ordenados.push(restantes.splice(indice, 1)[0])
  }
  return ordenados
}

function gestosDaSessao(gestos: GestoNaFila[], id: string): GestoNaFila[] {
  return ordenarGestos(gestos.filter((gesto) => gesto.tipo.startsWith(prefixo) && gesto.registroId === id
    && (gesto.estado !== 'needs_review' || gesto.resultado?.aplicada)))
}

function pendente(gestos: GestoNaFila[]): boolean {
  return gestos.some((gesto) => gesto.tipo.startsWith(prefixo) && gesto.estado !== 'sent')
}

async function retratos(): Promise<SessaoCaixa[]> {
  return await lerRetrato<SessaoCaixa[]>(tipoDoRetrato()) ?? []
}

async function guardar(sessoes: SessaoCaixa[]): Promise<void> {
  const atuais = new Map((await retratos()).map((sessao) => [sessao.id, sessao]))
  for (const sessao of sessoes) {
    if (sessao.usuarioId !== usuarioAtual()) continue
    const anterior = atuais.get(sessao.id)
    // O resumo do histórico não pode descartar o extrato que já foi carregado.
    atuais.set(sessao.id, { ...anterior, ...sessao,
      movimentos: sessao.movimentos ?? anterior?.movimentos })
  }
  await guardarRetrato(tipoDoRetrato(), [...atuais.values()])
}

function projetar(base: SessaoCaixa | undefined, gestos: GestoNaFila[], id: string): SessaoCaixa | undefined {
  let sessao = base ? { ...base, movimentos: [...(base.movimentos ?? [])] } : undefined
  for (const gesto of gestosDaSessao(gestos, id)) {
    if (gesto.tipo === 'caixa.abrir') {
      const dados = gesto.payload as { valorAbertura: number; abertaEm: string }
      sessao = { id, usuarioId: usuarioAtual(), versao: 0, valorAbertura: dados.valorAbertura,
        valorFechamentoEsperado: dados.valorAbertura, valorFechamentoContado: null,
        diferenca: null, abertaEm: dados.abertaEm, fechadaEm: null, status: 'ABERTA', movimentos: [] }
    }
    if (!sessao) continue
    if (gesto.tipo === 'caixa.sangrar' || gesto.tipo === 'caixa.suprir') {
      const dados = gesto.payload as { valor: number; motivo: string; criadoEm: string }
      const tipo = gesto.tipo === 'caixa.sangrar' ? 'SANGRIA' : 'SUPRIMENTO'
      const movimento: MovimentoCaixa = { id: gesto.operacaoId, tipo, valor: dados.valor,
        motivo: dados.motivo, vendaId: null, recebimentoId: null, criadoEm: dados.criadoEm }
      const esperado = centavos(sessao.valorFechamentoEsperado, 'Saldo esperado')
      const quantia = centavos(dados.valor, 'Valor do movimento')
      sessao = { ...sessao, movimentos: [...(sessao.movimentos ?? []), movimento],
        versao: versaoLida(sessao) + 1,
        valorFechamentoEsperado: valor(esperado + (tipo === 'SUPRIMENTO' ? quantia : -quantia)) }
    }
    if (gesto.tipo === 'caixa.fechar') {
      const dados = gesto.payload as { valorContado: number; fechadaEm: string }
      sessao = { ...sessao, status: 'FECHADA', fechadaEm: dados.fechadaEm,
        versao: versaoLida(sessao) + 1,
        valorFechamentoContado: dados.valorContado,
        diferenca: valor(centavos(sessao.valorFechamentoEsperado, 'Saldo esperado')
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

async function ultimoGestoDaSessao(id: string): Promise<GestoNaFila | undefined> {
  return gestosDaSessao(await listarGestos(), id).at(-1)
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
    const sessao = await remoto.consultar(id)
    await guardar([sessao])
    return sessao
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
          const recebida = await remoto.abertaDoOperadorAtual()
          if (recebida) await guardar([recebida])
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
          const recebidas = await remoto.historico(dia, operadorId)
          await guardar(recebidas)
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
      if (navigator.onLine && !pendente(gestos)) {
        return remoto.abrir(valorAbertura)
      }
      if ((await locais()).some((sessao) => sessao.status === 'ABERTA')) {
        throw new Error('Já existe uma SessaoCaixa aberta neste dispositivo.')
      }
      const id = crypto.randomUUID()
      const ultimo = ordenarGestos(gestos.filter((gesto) => gesto.tipo.startsWith(prefixo))).at(-1)
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
      // Todas as operações conhecidas desta sessão precisam preceder o fechamento.
      await enfileirar('caixa.fechar', id, { valorContado, fechadaEm: new Date().toISOString() },
        gestos.map((gesto) => gesto.operacaoId), versaoLida(sessao))
      return { diferenca: valor(centavos(sessao.valorFechamentoEsperado, 'Saldo esperado') - contado) }
    },
  }
}

async function movimentar(remoto: CaixaRemoto, id: string, quantia: number, motivo: string,
  tipo: 'caixa.sangrar' | 'caixa.suprir'): Promise<void> {
  const cent = centavos(quantia, 'Valor do movimento')
  const sessao = await local(id)
  if (sessao.status !== 'ABERTA') throw new Error('SessaoCaixa fechada não aceita movimento.')
  if (!motivo.trim()) throw new Error('Motivo é obrigatório para sangria e suprimento (RF14).')
  if (tipo === 'caixa.sangrar' && cent > centavos(sessao.valorFechamentoEsperado, 'Saldo esperado')) {
    throw new Error('Sangria maior que o saldo esperado da gaveta.')
  }
  const gestos = gestosDaSessao(await listarGestos(), id)
  if (navigator.onLine && !pendente(gestos)) {
    if (tipo === 'caixa.sangrar') return remoto.sangrar(id, quantia, motivo)
    return remoto.suprir(id, quantia, motivo)
  }
  const ultimo = await ultimoGestoDaSessao(id)
  await enfileirar(tipo, id, { valor: quantia, motivo: motivo.trim(), criadoEm: new Date().toISOString() },
    ultimo ? [ultimo.operacaoId] : [], versaoLida(sessao))
}
