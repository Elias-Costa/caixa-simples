import { caixa, type MovimentoCaixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { comServidor } from '../api/conexao'
import { lerIdentidade, sessaoAtual, sessaoDaAba } from '../sessao/armazenamento'
import { centavos, centavosDoSaldo, reais } from './dinheiro'
import {
  atualizarRetratoDoCaixa, enfileirarGesto, gestoAplicavel, gestoPendente, jaEstaNoRetrato, lerCaixaNoAparelho,
  lerDoServidor, listarGestos, marcarCaixaDesatualizado, ordenarPorDependencia, versaoDoResultado,
  type FechamentoDoAparelho, type GestoNaFila, type RetratoDoCaixa,
} from './fila'
import { dinheiroNaGaveta, projetarVendas, type DadosDoInicio } from './raizDaVenda'

type CaixaRemoto = typeof caixa
// As sessões abertas da Conta são pergunta do administrador com rede: o aparelho guarda só o caixa
// de quem está nele, e a tela as pede direto à API.
type CaixaNoDispositivo = Omit<CaixaRemoto, 'abrir' | 'sangrar' | 'suprir' | 'fechar' | 'abertas'> & {
  /** O id do caixa nasce no aparelho, com ou sem rede. */
  abrir(valorAbertura: number): Promise<{ id: string }>
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
  if (!comServidor()) throw new Error('O armazenamento local não está disponível para operar sem rede.')
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
    throw new Error('Abra este caixa com internet antes de alterá-lo sem rede.')
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
    } else if (lida.status === 'FECHADA') {
      // O fechamento lido vale mais que o extrato guardado: sem ele, a sessão continuaria ABERTA no
      // aparelho, e sem rede voltaria a aparecer fechada sem valores. O extrato de outra revisão sai,
      // para não somar os movimentos de uma leitura ao esperado de outra; a próxima consulta o traz.
      guardadas.set(lida.id, { ...lida, movimentos: undefined, ordemDaLeitura })
      guardadasAgora.add(lida.id)
    }
    // O resumo de outra revisão de uma sessão ABERTA não substitui um extrato guardado, pelo mesmo
    // motivo. O extrato é trocado na próxima consulta da sessão.
  }
  const sessoes = [...guardadas.values()]
  // Com o fechamento lido, a marca de fechada no servidor não tem mais o que dizer, nem os valores que o
  // fechamento feito aqui devolveu.
  const fechamentoLido = new Set(sessoes.filter((sessao) => sessao.status === 'FECHADA').map((sessao) => sessao.id))
  return {
    sessoes,
    fechadasNoServidor: atual.fechadasNoServidor.filter((id) => !fechamentoLido.has(id)),
    fechamentosDoAparelho: atual.fechamentosDoAparelho.filter((fechamento) => !fechamentoLido.has(fechamento.sessaoId)),
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

/**
 * O servidor confirmou o fechamento feito aqui e devolveu a diferença: quem acabou de contar a gaveta
 * a vê mesmo que a rede caia antes de a sessão ser lida de novo. A hora chega com essa leitura.
 */
async function guardarFechamentoDoAparelho(fechamento: FechamentoDoAparelho): Promise<void> {
  await atualizarRetratoDoCaixa<SessaoGuardada>((atual) => ({ ...atual,
    fechadasNoServidor: [...new Set([...atual.fechadasNoServidor, fechamento.sessaoId])],
    fechamentosDoAparelho: [...atual.fechamentosDoAparelho.filter((outro) => outro.sessaoId !== fechamento.sessaoId),
      fechamento] }))
}

/**
 * A sessão guardada mais os gestos da gaveta que ela ainda não contém. O gesto cujo resultado
 * chegou antes da leitura já está no esperado do servidor, e somá-lo de novo dobraria a sangria ou
 * a Venda. O confirmado depois da leitura entra com a revisão que o servidor devolveu.
 */
function projetar(base: SessaoGuardada | undefined, gestos: GestoNaFila[], id: string,
  fechadaNoServidor: boolean, doAparelho: FechamentoDoAparelho | undefined,
  desatualizada: boolean): SessaoCaixa | undefined {
  let sessao: SessaoCaixa | undefined
  if (base) {
    const { ordemDaLeitura: _ordem, ...guardada } = base
    // A sessão fechada lida só pelo resumo não tem extrato no aparelho: uma lista vazia diria que o caixa
    // não teve movimento, e o gesto que ainda chegasse a ela não faria do extrato uma lista completa.
    sessao = guardada.status === 'FECHADA' && !guardada.movimentos ? { ...guardada, movimentos: undefined }
      : { ...guardada, movimentos: [...(guardada.movimentos ?? [])] }
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
      const dados = gesto.payload as { valor: number; motivo: string; criadoEm: string; movimentoId?: string }
      // O gesto gravado antes de levar o id do movimento usa o da operação, só para a lista.
      const idDoMovimento = dados.movimentoId ?? gesto.operacaoId
      // A chamada com rede que ficou sem resposta pode ter gravado este mesmo movimento, e uma
      // leitura feita depois dela já o traz: contá-lo de novo dobraria a sangria.
      if (sessao.movimentos?.some((existente) => existente.id === idDoMovimento)) continue
      const tipo = gesto.tipo === 'caixa.sangrar' ? 'SANGRIA' : 'SUPRIMENTO'
      const movimento: MovimentoCaixa = { id: idDoMovimento, tipo, valor: dados.valor,
        motivo: dados.motivo, vendaId: null, recebimentoId: null, criadoEm: dados.criadoEm }
      const esperado = centavosDoSaldo(sessao.valorFechamentoEsperado)
      const quantia = centavos(dados.valor, 'Valor do movimento')
      sessao = { ...sessao, movimentos: sessao.movimentos && [...sessao.movimentos, movimento],
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
      sessao = { ...sessao, movimentos: sessao.movimentos && [...sessao.movimentos, movimento],
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
  // ainda não enviado tem os próprios valores e prevalece. O feito aqui com rede tem o valor contado e a
  // diferença que o servidor devolveu, e só a hora espera a leitura.
  if (fechadaNoServidor && sessao.status === 'ABERTA') {
    sessao = doAparelho
      ? { ...sessao, status: 'FECHADA', fechadaEm: null, valorFechamentoContado: doAparelho.valorContado,
        diferenca: doAparelho.diferenca }
      : { ...sessao, status: 'FECHADA', fechadaEm: null, valorFechamentoContado: null, diferenca: null,
        fechamentoSemValores: true }
  }
  // A sessão mudou com rede depois da leitura guardada: o esperado dela ficou para trás, e a
  // diferença do fechamento feito aqui sairia dele. O servidor a calcula quando o gesto chegar.
  if (desatualizada) sessao = { ...sessao, saldoDesatualizado: true, diferenca: null }
  return { ...sessao, pendenteSincronizacao: pendente(gestosDaSessao(gestos, id)) }
}

async function locais(): Promise<SessaoCaixa[]> {
  const { sessoes: base, fechadasNoServidor, fechamentosDoAparelho, desatualizadas, gestos } =
    await lerCaixaNoAparelho<SessaoGuardada>()
  const ids = new Set([...base.map((sessao) => sessao.id),
    ...gestos.filter((gesto) => gesto.tipo === 'caixa.abrir').map((gesto) => gesto.registroId)])
  return [...ids].map((id) => projetar(base.find((sessao) => sessao.id === id), gestos, id,
    fechadasNoServidor.includes(id), fechamentosDoAparelho.find((fechamento) => fechamento.sessaoId === id),
    desatualizadas.some((marca) => marca.sessaoId === id)))
    .filter((sessao): sessao is SessaoCaixa => !!sessao)
}

async function local(id: string): Promise<SessaoCaixa> {
  const sessao = (await locais()).find((item) => item.id === id)
  if (!sessao) throw new Error('Caixa não encontrado neste aparelho.')
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
  if (!comServidor() || pendente(gestos)) return local(id)
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
      if (comServidor()) {
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
      if (comServidor()) {
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
      // Na ordem da abertura, como o servidor responde: a do aparelho é a ordem em que cada sessão foi
      // guardada, e o caixa lido aberto viria antes dos fechados do mesmo dia.
      return (await locais()).filter((sessao) => diaNoBalcao(sessao.abertaEm) === dia
        && (!operadorId || operadorId === usuarioAtual()))
        .sort((uma, outra) => Date.parse(uma.abertaEm) - Date.parse(outra.abertaEm))
    },
    async abrir(valorAbertura) {
      // O id nasce aqui também com rede: sem resposta, a abertura vai à fila com ele, e o servidor a
      // reconhece como a mesma se a chamada perdida já a tinha gravado. Com outro id, ela seria um
      // segundo caixa aberto, recusado na volta junto com as vendas feitas nele.
      const id = crypto.randomUUID()
      if (semBanco()) return remoto.abrir(valorAbertura, id)
      centavos(valorAbertura, 'Valor de abertura')
      const gestos = await listarGestos()
      if (comServidor() && !algumGestoDeCaixaPendente(gestos)) {
        try {
          return await remoto.abrir(valorAbertura, id)
        } catch (falha) {
          if (!(falha instanceof SemConexao)) throw falha
        }
      }
      if ((await locais()).some((sessao) => sessao.status === 'ABERTA')) {
        throw new Error('Você já tem um caixa aberto neste aparelho; feche o anterior antes de abrir outro.')
      }
      const ultimo = ordenarPorDependencia(gestos.filter((gesto) => gesto.tipo.startsWith(prefixo))).at(-1)
      await enfileirar('caixa.abrir', id, { valorAbertura,
        abertaEm: new Date().toISOString() }, ultimo ? [ultimo.operacaoId] : [])
      return { id }
    },
    async sangrar(id, valorMovimento, motivo, movimentoId?: string) {
      if (semBanco()) return remoto.sangrar(id, valorMovimento, motivo,
        movimentoId ?? crypto.randomUUID())
      if (comServidor() && (await retratos()).every((sessao) => sessao.id !== id)
        && lerIdentidade()?.perfil === 'ADMIN') return remoto.sangrar(id, valorMovimento, motivo,
          movimentoId ?? crypto.randomUUID())
      return movimentar(remoto, id, valorMovimento, motivo, 'caixa.sangrar', movimentoId)
    },
    async suprir(id, valorMovimento, motivo, movimentoId?: string) {
      if (semBanco()) return remoto.suprir(id, valorMovimento, motivo,
        movimentoId ?? crypto.randomUUID())
      if (comServidor() && (await retratos()).every((sessao) => sessao.id !== id)
        && lerIdentidade()?.perfil === 'ADMIN') return remoto.suprir(id, valorMovimento, motivo,
          movimentoId ?? crypto.randomUUID())
      return movimentar(remoto, id, valorMovimento, motivo, 'caixa.suprir', movimentoId)
    },
    async fechar(id, valorContado) {
      if (semBanco()) return remoto.fechar(id, valorContado)
      const contado = centavos(valorContado, 'Valor contado')
      if (comServidor() && (await retratos()).every((sessao) => sessao.id !== id)
        && lerIdentidade()?.perfil === 'ADMIN') return remoto.fechar(id, valorContado)
      const sessao = await local(id)
      if (sessao.status !== 'ABERTA') throw new Error('Este caixa já foi fechado e não fecha de novo.')
      const gestos = gestosDaSessao(await listarGestos(), id)
      if (comServidor() && !pendente(gestos)) {
        try {
          const fechada = await remoto.fechar(id, valorContado)
          // Se a rede cair antes de a sessão ser lida de novo, a guardada ainda diria ABERTA.
          await guardarFechamentoDoAparelho({ sessaoId: id, valorContado, diferenca: fechada.diferenca })
          return fechada
        } catch (falha) {
          // Sem resposta, o fechamento vai à fila. Se a chamada perdida já tinha fechado, o gesto
          // volta em revisão dizendo que o caixa já foi fechado: nada depende dele, e nenhum
          // dinheiro muda.
          if (!(falha instanceof SemConexao)) throw falha
        }
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
  if (sessao.status !== 'ABERTA') throw new Error('Este caixa já foi fechado e não aceita movimento.')
  if (!motivo.trim()) {
    throw new Error(tipo === 'caixa.sangrar' ? 'Informe o motivo da sangria.' : 'Informe o motivo do suprimento.')
  }
  const gestos = await listarGestos()
  const comRede = comServidor() && !pendente(gestosDaSessao(gestos, id))
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
  // O mesmo id vai ao servidor e, sem resposta, à fila: se a chamada perdida tinha gravado, o
  // servidor não grava de novo.
  const movimento = movimentoId ?? crypto.randomUUID()
  if (comRede) {
    try {
      return await escreverNaGavetaComRede(remoto, id, () => tipo === 'caixa.sangrar'
        ? remoto.sangrar(id, quantia, motivo, movimento) : remoto.suprir(id, quantia, motivo, movimento))
    } catch (falha) {
      if (!(falha instanceof SemConexao)) throw falha
      // Com o saldo já desatualizado antes, só o servidor sabia se a sangria cabia, e ela não vai
      // à fila sem essa conta. A intenção guarda o id, e repetir quando ele responder não dobra.
      if (tipo === 'caixa.sangrar' && sessao.saldoDesatualizado) throw falha
    }
  }
  // Depois de uma escrita sem resposta, o caixa fica marcado como desatualizado: o saldo aparece
  // indisponível e a próxima sangria sem servidor espera a releitura, porque a chamada perdida pode
  // ter gravado.
  const ultimo = ultimoGestoDaGaveta(await listarGestos(), id)
  await enfileirar(tipo, id, { valor: quantia, motivo: motivo.trim(), criadoEm: new Date().toISOString(),
    movimentoId: movimento }, ultimo ? [ultimo.operacaoId] : [], versaoLida(sessao))
}
