import { caixa, type MovimentoCaixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { lerIdentidade, sessaoAtual, sessaoDaAba } from '../sessao/armazenamento'
import { centavos, centavosDoSaldo, reais } from './dinheiro'
import {
  atualizarRetratoDoCaixa, enfileirarGesto, gestoAplicavel, gestoPendente, jaEstaNoRetrato, lerCaixaNoAparelho,
  lerDoServidor, listarGestos, marcarCaixaDesatualizado, ordenarPorDependencia, versaoDoResultado,
  type GestoNaFila, type RetratoDoCaixa,
} from './fila'
import { dinheiroNaGaveta, projetarVendas, type DadosDoInicio } from './raizDaVenda'

type CaixaRemoto = typeof caixa
type CaixaNoDispositivo = Omit<CaixaRemoto, 'sangrar' | 'suprir' | 'fechar'> & {
  sangrar(id: string, valor: number, motivo: string, movimentoId?: string): Promise<void>
  suprir(id: string, valor: number, motivo: string, movimentoId?: string): Promise<void>
  /** Sem rede e com o saldo desatualizado, a diferença fica para o servidor calcular. */
  fechar(id: string, valorContado: number): Promise<{ diferenca: number | null }>
  /** Para a Venda e o fiado, que escrevem com rede na gaveta: ver escreverNaGavetaComRede. */
  escreverNaGavetaComRede<T>(id: string | undefined, escrever: () => Promise<T>): Promise<T>
}
/** Cada sessão guardada leva a ordem da leitura que a trouxe, porque cada uma é lida numa hora. */
type SessaoGuardada = SessaoCaixa & { ordemDaLeitura?: number }
const prefixo = 'caixa.'
const SANGRIA_ESPERA_A_LEITURA = 'Este caixa mudou com rede e o aparelho ainda não releu o saldo: '
  + 'a sangria espera a próxima leitura do caixa, com rede.'

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
  return (await lerCaixaNoAparelho<SessaoGuardada>()).sessoes
}

/**
 * As sessões lidas do servidor sobre as que o aparelho já guardava. A guardada só é trocada por uma
 * leitura que não seja mais antiga que ela: a ordem da leitura nunca recua, porque o descarte já tirou
 * da fila o que a leitura mais nova continha. E o fechamento é definitivo no servidor: a leitura que
 * ainda mostra ABERTA a sessão que o aparelho tem FECHADA saiu antes do fechamento. A sessão marcada
 * como desatualizada deixa de estar quando a leitura guardada foi pedida depois da marca.
 */
export function mesclarSessoesLidas(atual: RetratoDoCaixa<SessaoGuardada>, lidas: SessaoCaixa[],
  ordemDaLeitura: number, usuario: string): RetratoDoCaixa<SessaoGuardada> {
  const guardadas = new Map(atual.sessoes.map((sessao) => [sessao.id, sessao]))
  const guardadasAgora = new Set<string>()
  for (const lida of lidas) {
    if (lida.usuarioId !== usuario) continue
    const anterior = guardadas.get(lida.id)
    if (anterior && (anterior.ordemDaLeitura ?? 0) > ordemDaLeitura) continue
    if (anterior?.status === 'FECHADA' && lida.status === 'ABERTA') continue
    if (lida.movimentos) {
      guardadas.set(lida.id, { ...lida, ordemDaLeitura })
      guardadasAgora.add(lida.id)
    } else if (!anterior?.movimentos || anterior.versao === lida.versao) {
      // O resumo do histórico não traz o extrato; o guardado continua valendo porque é da mesma
      // revisão.
      guardadas.set(lida.id, { ...anterior, ...lida, movimentos: anterior?.movimentos, ordemDaLeitura })
      guardadasAgora.add(lida.id)
    }
    // O resumo de outra revisão não substitui um extrato guardado: ficariam os movimentos de uma
    // leitura com o esperado de outra. O extrato é trocado na próxima consulta da sessão.
  }
  const sessoes = [...guardadas.values()]
  // Com o fechamento lido, a marca de fechada no servidor não tem mais o que dizer.
  const fechamentoLido = new Set(sessoes.filter((sessao) => sessao.status === 'FECHADA').map((sessao) => sessao.id))
  return {
    sessoes,
    fechadasNoServidor: atual.fechadasNoServidor.filter((id) => !fechamentoLido.has(id)),
    // A leitura ignorada acima não diz nada do que a escrita com rede mudou, e a marca fica.
    desatualizadas: atual.desatualizadas.filter((marca) =>
      !guardadasAgora.has(marca.sessaoId) || marca.ordem > ordemDaLeitura),
  }
}

async function guardar(sessoes: SessaoCaixa[], ordemDaLeitura: number): Promise<void> {
  const usuario = usuarioAtual()
  await atualizarRetratoDoCaixa<SessaoGuardada>((atual) =>
    mesclarSessoesLidas(atual, sessoes, ordemDaLeitura, usuario))
}

/**
 * Guarda a resposta de qual sessão do operador está aberta no servidor e marca como fechada no
 * servidor cada outra sessão dele que a resposta já conhecia: o servidor tem no máximo uma sessão
 * aberta por operador, e sessão fechada não reabre. A resposta conhecia a sessão guardada por uma
 * leitura de ordem igual ou anterior à dela e a aberta neste aparelho cuja abertura já tinha
 * resultado quando a pergunta saiu. A abertura ainda sem resultado não é marcada: o servidor não
 * sabia dela.
 */
async function guardarSessaoAberta(recebida: SessaoCaixa | undefined, ordemDaLeitura: number): Promise<void> {
  const usuario = usuarioAtual()
  await atualizarRetratoDoCaixa<SessaoGuardada>((atual, gestos) => {
    const mesclado = mesclarSessoesLidas(atual, recebida ? [recebida] : [], ordemDaLeitura, usuario)
    const conhecidas = [
      ...atual.sessoes.filter((sessao) => sessao.status === 'ABERTA'
        && (sessao.ordemDaLeitura ?? 0) <= ordemDaLeitura).map((sessao) => sessao.id),
      ...gestos.filter((gesto) => gesto.tipo === 'caixa.abrir' && gestoAplicavel(gesto)
        && jaEstaNoRetrato(gesto, { ordemDaLeitura })).map((gesto) => gesto.registroId),
    ]
    const fechadas = new Set(mesclado.fechadasNoServidor)
    for (const id of conhecidas) if (id !== recebida?.id) fechadas.add(id)
    return { ...mesclado, fechadasNoServidor: [...fechadas] }
  })
}

/** O servidor confirmou o fechamento; os valores dele chegam na próxima leitura da sessão. */
async function marcarFechadaNoServidor(id: string): Promise<void> {
  await atualizarRetratoDoCaixa<SessaoGuardada>((atual) => ({ ...atual,
    fechadasNoServidor: [...new Set([...atual.fechadasNoServidor, id])] }))
}

/**
 * A sessão guardada mais os gestos da gaveta que ela ainda não contém. O gesto cujo resultado
 * chegou antes da leitura já está no esperado do servidor, e somá-lo de novo dobraria a sangria ou
 * a Venda. O confirmado depois da leitura entra com a revisão que o servidor devolveu.
 */
function projetar(base: SessaoGuardada | undefined, gestos: GestoNaFila[], id: string,
  fechadaNoServidor: boolean, desatualizada: boolean): SessaoCaixa | undefined {
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
  if (!sessao) return undefined
  // O servidor já disse que a sessão não está aberta, e o aparelho não leu o fechamento: ela deixa de
  // aceitar operação, sem hora, valor contado ou diferença inventados. O fechamento feito aqui e
  // ainda não enviado tem os próprios valores e prevalece.
  if (fechadaNoServidor && sessao.status === 'ABERTA') {
    sessao = { ...sessao, status: 'FECHADA', fechadaEm: null, valorFechamentoContado: null, diferenca: null,
      fechamentoSemValores: true }
  }
  // A sessão mudou com rede depois da leitura guardada: o esperado dela ficou para trás, e a
  // diferença do fechamento feito aqui sairia dele. O servidor a calcula quando o gesto chegar.
  if (desatualizada) sessao = { ...sessao, saldoDesatualizado: true, diferenca: null }
  return { ...sessao, pendenteSincronizacao: pendente(gestosDaSessao(gestos, id)) }
}

async function locais(): Promise<SessaoCaixa[]> {
  const { sessoes: base, fechadasNoServidor, desatualizadas, gestos } = await lerCaixaNoAparelho<SessaoGuardada>()
  const ids = new Set([...base.map((sessao) => sessao.id),
    ...gestos.filter((gesto) => gesto.tipo === 'caixa.abrir').map((gesto) => gesto.registroId)])
  return [...ids].map((id) => projetar(base.find((sessao) => sessao.id === id), gestos, id,
    fechadasNoServidor.includes(id), desatualizadas.some((marca) => marca.sessaoId === id)))
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

/**
 * Escreve com rede na gaveta de uma sessão deste usuário sem deixar no aparelho um saldo antigo tido
 * como certo. A sessão é marcada como desatualizada antes de a escrita sair e de novo quando ela
 * termina, com resposta ou sem, porque o servidor pode ter mudado a gaveta mesmo sem responder; em
 * seguida o extrato é relido, e só uma leitura pedida depois da última marca a tira. Se a rede cair
 * antes, a marca fica, e sem rede o saldo aparece como indisponível até a próxima leitura. Sem o id,
 * a sessão é a aberta de quem opera, onde o servidor lança o recebimento de fiado. A escrita pertence
 * à sessão de login em que começou: ver marcarERelerSemFalhar.
 */
async function escreverNaGavetaComRede<T>(remoto: CaixaRemoto, id: string | undefined,
  escrever: () => Promise<T>): Promise<T> {
  // Sem armazenamento local, não há caixa guardado que possa ficar para trás.
  if (!('indexedDB' in globalThis)) return escrever()
  const sessaoDoLogin = sessaoAtual()
  const sessoes = await locais()
  const alvo = id ?? sessoes.find((sessao) => sessao.status === 'ABERTA')?.id
  if (!alvo || !sessoes.some((sessao) => sessao.id === alvo)) return escrever()
  await marcarCaixaDesatualizado(alvo)
  try {
    return await escrever()
  } finally {
    await marcarERelerSemFalhar(remoto, alvo, sessaoDoLogin)
  }
}

/**
 * A escrita já terminou, e a falha daqui não a desfaz: a sessão só continua marcada. Se a sessão de
 * login da aba mudou enquanto a escrita esperava a resposta, nada é marcado nem relido: com outra
 * Conta, a marca levaria o id desta SessaoCaixa ao espaço dela no aparelho, e a releitura sairia com
 * o token dela. A marca feita antes da escrita continua no espaço de quem escreveu e o avisa no
 * próximo login.
 */
async function marcarERelerSemFalhar(remoto: CaixaRemoto, id: string,
  sessaoDoLogin: string | null): Promise<void> {
  if (sessaoDoLogin === null || sessaoAtual() !== sessaoDoLogin || sessaoDaAba() !== sessaoDoLogin) return
  try {
    await marcarCaixaDesatualizado(id)
    await consultarLocalOuRemoto(remoto, id)
  } catch {
    // Sem a releitura guardada, a marca espera a próxima leitura do caixa. A troca de login durante a
    // marca ou a releitura também para aqui: a fila recusa a sessão que mudou no meio da gravação.
  }
}

/** Projeta apenas o caixa do usuário autenticado; ADMIN consulta outros caixas pela API online. */
export function criarCaixaLocal(remoto: CaixaRemoto = caixa): CaixaNoDispositivo {
  return {
    async abertaDoOperadorAtual() {
      if (semBanco()) return remoto.abertaDoOperadorAtual()
      if (navigator.onLine) {
        try {
          const recebida = await lerDoServidor(() => remoto.abertaDoOperadorAtual(),
            (sessao, ordem) => guardarSessaoAberta(sessao, ordem))
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
    async sangrar(id, valorMovimento, motivo, movimentoId?: string) {
      if (semBanco()) return remoto.sangrar(id, valorMovimento, motivo,
        movimentoId ?? crypto.randomUUID())
      if (navigator.onLine && (await retratos()).every((sessao) => sessao.id !== id)
        && lerIdentidade()?.perfil === 'ADMIN') return remoto.sangrar(id, valorMovimento, motivo,
          movimentoId ?? crypto.randomUUID())
      return movimentar(remoto, id, valorMovimento, motivo, 'caixa.sangrar', movimentoId)
    },
    async suprir(id, valorMovimento, motivo, movimentoId?: string) {
      if (semBanco()) return remoto.suprir(id, valorMovimento, motivo,
        movimentoId ?? crypto.randomUUID())
      if (navigator.onLine && (await retratos()).every((sessao) => sessao.id !== id)
        && lerIdentidade()?.perfil === 'ADMIN') return remoto.suprir(id, valorMovimento, motivo,
          movimentoId ?? crypto.randomUUID())
      return movimentar(remoto, id, valorMovimento, motivo, 'caixa.suprir', movimentoId)
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
        const fechada = await remoto.fechar(id, valorContado)
        // Se a rede cair antes de a sessão ser lida de novo, a guardada ainda diria ABERTA.
        await marcarFechadaNoServidor(id)
        return fechada
      }
      // Todas as operações conhecidas desta sessão, inclusive cada gesto das Vendas registradas
      // nela, precisam chegar ao servidor antes do fechamento.
      await enfileirar('caixa.fechar', id, { valorContado, fechadaEm: new Date().toISOString() },
        gestos.map((gesto) => gesto.operacaoId), versaoLida(sessao))
      if (sessao.saldoDesatualizado) return { diferenca: null }
      return { diferenca: reais(centavosDoSaldo(sessao.valorFechamentoEsperado) - contado) }
    },
    async escreverNaGavetaComRede<T>(id: string | undefined, escrever: () => Promise<T>) {
      return escreverNaGavetaComRede(remoto, id, escrever)
    },
  }
}

async function movimentar(remoto: CaixaRemoto, id: string, quantia: number, motivo: string,
  tipo: 'caixa.sangrar' | 'caixa.suprir', movimentoId?: string): Promise<void> {
  const cent = centavos(quantia, 'Valor do movimento')
  const sessao = await local(id)
  if (sessao.status !== 'ABERTA') throw new Error('SessaoCaixa fechada não aceita movimento.')
  if (!motivo.trim()) throw new Error('Motivo é obrigatório para sangria e suprimento (RF14).')
  const gestos = await listarGestos()
  const comRede = navigator.onLine && !pendente(gestosDaSessao(gestos, id))
  if (tipo === 'caixa.sangrar') {
    // Com o saldo guardado desatualizado, só o servidor sabe se a sangria cabe na gaveta: com rede
    // e sem pendência ela vai direto a ele, e a que entraria na fila é recusada até a releitura.
    if (sessao.saldoDesatualizado && !comRede) throw new Error(SANGRIA_ESPERA_A_LEITURA)
    // Com o esperado já negativo toda sangria é recusada, como no servidor: não se tira da gaveta o
    // que não está lá, e o suprimento é que corrige o saldo.
    if (!sessao.saldoDesatualizado && cent > centavosDoSaldo(sessao.valorFechamentoEsperado)) {
      throw new Error('Sangria maior que o saldo esperado da gaveta.')
    }
  }
  if (comRede) {
    const movimento = movimentoId ?? crypto.randomUUID()
    return escreverNaGavetaComRede(remoto, id, () => tipo === 'caixa.sangrar'
      ? remoto.sangrar(id, quantia, motivo, movimento) : remoto.suprir(id, quantia, motivo, movimento))
  }
  const ultimo = ultimoGestoDaGaveta(gestos, id)
  await enfileirar(tipo, id, { valor: quantia, motivo: motivo.trim(), criadoEm: new Date().toISOString() },
    ultimo ? [ultimo.operacaoId] : [], versaoLida(sessao))
}
