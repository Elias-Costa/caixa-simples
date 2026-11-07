import 'fake-indexeddb/auto'
import { deleteDB, openDB } from 'idb'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { gravarIdentidade, gravarToken, lerIdentidade, limparSessao } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import {
  aoMudarFila, apagarDadosDoAparelho, atualizarRetratoDoCaixa, conferirGestos, conferirRegistro, enfileirarGesto,
  gestosDeCadaUsuario, gestosDosOutrosUsuarios, guardarRetrato, guardarRetratoDaVenda, guardarRetratos, iniciarEnvio,
  jaEstaNoRetrato, lerCaixaNoAparelho, lerDoServidor, lerRetrato, listarGestos, mudarEstadoDoGesto, recuperarEnviosInterrompidos, registrarDesfechos,
  SessaoAbertaNoAparelho, type GestoNaFila,
} from './fila'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Café A',
  perfil: 'ADMIN', estoqueHabilitado: false,
}

function entrar(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

const produto = {
  tipo: 'produto.criar', registroId: '00000000-0000-4000-8000-000000000001',
  payload: { nome: 'Café', preco: 7 },
}

const sessaoId = '00000000-0000-4000-8000-000000000021'

/** O servidor aplica os gestos, na ordem dada; cada um recebe o número seguinte do contador. */
async function aplicados(...gestos: GestoNaFila[]) {
  await iniciarEnvio(gestos.map((gesto) => gesto.operacaoId))
  await registrarDesfechos(gestos.map((gesto) => ({
    operacaoId: gesto.operacaoId, estado: 'sent' as const, resultado: { aplicada: true },
  })))
}

/** Grava a sessão do caixa como lida do servidor com a ordem dada. */
async function caixaLido(ordemDaLeitura: number) {
  await atualizarRetratoDoCaixa((atual) => ({ ...atual, sessoes: [{ id: sessaoId, ordemDaLeitura }] }))
}

async function vendaConcluida(vendaId: string) {
  const agora = new Date().toISOString()
  const inicio = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: vendaId,
    payload: { sessaoCaixaId: sessaoId, criadoEm: agora } })
  const conclusao = await enfileirarGesto({ tipo: 'venda.concluir', registroId: vendaId,
    payload: { concluidoEm: agora }, dependeDe: [inicio.operacaoId] })
  return { inicio, conclusao }
}

beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
})

describe('fila local de gestos', () => {
  it('descarta cada gesto do Caixa só depois de a própria SessaoCaixa ser lida com ele', async () => {
    entrar(ana)
    const primeira = await enfileirarGesto({ tipo: 'caixa.suprir',
      registroId: '00000000-0000-4000-8000-000000000011', payload: { valor: 10 } })
    const segunda = await enfileirarGesto({ tipo: 'caixa.suprir',
      registroId: '00000000-0000-4000-8000-000000000012', payload: { valor: 20 } })
    await aplicados(primeira, segunda)

    await atualizarRetratoDoCaixa((atual) => ({ ...atual, sessoes: [
      { id: primeira.registroId, ordemDaLeitura: 1 }, { id: segunda.registroId, ordemDaLeitura: 0 },
    ] }))
    expect((await listarGestos()).map((gesto) => gesto.operacaoId)).toEqual([segunda.operacaoId])
    await atualizarRetratoDoCaixa((atual) => ({ ...atual, sessoes: [
      { id: primeira.registroId, ordemDaLeitura: 1 }, { id: segunda.registroId, ordemDaLeitura: 2 },
    ] }))
    expect(await listarGestos()).toEqual([])
    expect(await lerCaixaNoAparelho()).toMatchObject({ fechadasNoServidor: [], gestos: [], sessoes: [
      { id: primeira.registroId, ordemDaLeitura: 1 }, { id: segunda.registroId, ordemDaLeitura: 2 },
    ] })
  })

  it('descarta os gestos da Venda só quando o retrato dela e o do caixa contêm a conclusão', async () => {
    entrar(ana)
    // A Venda é lida antes do caixa.
    const lidaAntes = await vendaConcluida('00000000-0000-4000-8000-000000000031')
    await aplicados(lidaAntes.inicio, lidaAntes.conclusao)
    await guardarRetratoDaVenda(lidaAntes.inicio.registroId, { id: lidaAntes.inicio.registroId })
    expect(await listarGestos()).toHaveLength(2)
    await caixaLido(1)
    expect(await listarGestos()).toHaveLength(2)
    await caixaLido(2)
    expect(await listarGestos()).toEqual([])

    // O caixa é lido antes da Venda.
    const lidaDepois = await vendaConcluida('00000000-0000-4000-8000-000000000032')
    await aplicados(lidaDepois.inicio, lidaDepois.conclusao)
    await caixaLido(4)
    expect(await listarGestos()).toHaveLength(2)
    await guardarRetratoDaVenda(lidaDepois.inicio.registroId, { id: lidaDepois.inicio.registroId })
    expect(await listarGestos()).toEqual([])
  })

  it('não descarta o gesto sem resultado, com falha ou em revisão, nem os da Venda que a revisão ainda usa', async () => {
    entrar(ana)
    const emRevisao = await vendaConcluida('00000000-0000-4000-8000-000000000033')
    await iniciarEnvio([emRevisao.inicio.operacaoId, emRevisao.conclusao.operacaoId])
    await registrarDesfechos([
      { operacaoId: emRevisao.inicio.operacaoId, estado: 'sent', resultado: { aplicada: true } },
      { operacaoId: emRevisao.conclusao.operacaoId, estado: 'needs_review',
        resultado: { aplicada: true, detalhe: 'preço divergente' } },
    ])
    const aberta = '00000000-0000-4000-8000-000000000034'
    const semResultado = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: aberta,
      payload: { sessaoCaixaId: sessaoId, criadoEm: new Date().toISOString() } })
    const comFalha = await enfileirarGesto({ tipo: 'caixa.suprir', registroId: sessaoId, payload: { valor: 5 } })
    await iniciarEnvio([comFalha.operacaoId])
    await registrarDesfechos([{ operacaoId: comFalha.operacaoId, estado: 'failed', falha: 'Sem conexão' }])

    await guardarRetratoDaVenda(emRevisao.inicio.registroId, { id: emRevisao.inicio.registroId })
    await guardarRetratoDaVenda(aberta, { id: aberta })
    // A conclusão em revisão foi aplicada e ainda soma na gaveta: o início dela continua.
    expect((await listarGestos()).map((gesto) => gesto.operacaoId).sort()).toEqual([
      emRevisao.inicio.operacaoId, emRevisao.conclusao.operacaoId, semResultado.operacaoId, comFalha.operacaoId,
    ].sort())
    await caixaLido(10)
    expect((await listarGestos()).map((gesto) => gesto.operacaoId).sort()).toEqual([
      emRevisao.conclusao.operacaoId, semResultado.operacaoId, comFalha.operacaoId,
    ].sort())
  })

  it('a Venda com gesto recusado guarda os enviados, que dizem a hora e o valor dela', async () => {
    entrar(ana)
    const recusada = await vendaConcluida('00000000-0000-4000-8000-000000000035')
    await iniciarEnvio([recusada.inicio.operacaoId, recusada.conclusao.operacaoId])
    await registrarDesfechos([
      { operacaoId: recusada.inicio.operacaoId, estado: 'sent', resultado: { aplicada: true } },
      { operacaoId: recusada.conclusao.operacaoId, estado: 'needs_review',
        resultado: { aplicada: false, detalhe: 'Esta venda já está cancelada.' } },
    ])

    await guardarRetratoDaVenda(recusada.inicio.registroId, { id: recusada.inicio.registroId })
    await caixaLido(10)

    expect((await listarGestos()).map((gesto) => gesto.operacaoId).sort())
      .toEqual([recusada.inicio.operacaoId, recusada.conclusao.operacaoId].sort())
  })

  it('confere de uma vez o que está em revisão no registro, sem tocar nos outros, e avisa quem conta', async () => {
    entrar(ana)
    const venda = await vendaConcluida('00000000-0000-4000-8000-000000000036')
    const outra = await vendaConcluida('00000000-0000-4000-8000-000000000037')
    const gestos = [venda.inicio, venda.conclusao, outra.inicio, outra.conclusao]
    await iniciarEnvio(gestos.map((gesto) => gesto.operacaoId))
    await registrarDesfechos([
      { operacaoId: venda.inicio.operacaoId, estado: 'sent', resultado: { aplicada: true } },
      { operacaoId: venda.conclusao.operacaoId, estado: 'needs_review', resultado: { aplicada: false } },
      { operacaoId: outra.inicio.operacaoId, estado: 'sent', resultado: { aplicada: true } },
      { operacaoId: outra.conclusao.operacaoId, estado: 'needs_review', resultado: { aplicada: false } },
    ])
    const mudancas: string[] = []
    const parar = aoMudarFila((mudanca) => mudancas.push(mudanca))

    await conferirRegistro(venda.inicio.registroId)
    parar()

    const porId = new Map((await listarGestos()).map((gesto) => [gesto.operacaoId, gesto]))
    const conferidoEm = porId.get(venda.conclusao.operacaoId)?.conferidoEm
    expect(conferidoEm).toEqual(expect.any(String))
    expect(porId.get(venda.conclusao.operacaoId)?.estado).toBe('needs_review')
    expect(porId.get(venda.inicio.operacaoId)?.conferidoEm).toBeUndefined()
    expect(porId.get(outra.conclusao.operacaoId)?.conferidoEm).toBeUndefined()
    expect(mudancas).toEqual(['conferencia'])

    // Conferir de novo mantém a primeira vez, e não há o que avisar.
    const denovo: string[] = []
    const pararDeNovo = aoMudarFila((mudanca) => denovo.push(mudanca))
    await conferirRegistro(venda.inicio.registroId)
    pararDeNovo()
    expect((await listarGestos()).find((gesto) => gesto.operacaoId === venda.conclusao.operacaoId)?.conferidoEm)
      .toBe(conferidoEm)
    expect(denovo).toEqual([])
  })

  it('o descarte de outra Conta ou de outro usuário não toca os gestos enviados da Ana', async () => {
    entrar(ana)
    const vendaId = '00000000-0000-4000-8000-000000000035'
    const suprimento = await enfileirarGesto({ tipo: 'caixa.suprir', registroId: sessaoId, payload: { valor: 5 } })
    const inicio = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: vendaId,
      payload: { sessaoCaixaId: sessaoId, criadoEm: new Date().toISOString() } })
    await aplicados(suprimento, inicio)
    const daAna = [suprimento.operacaoId, inicio.operacaoId].sort()

    // Outra Conta com o mesmo id de usuário, e outro usuário da mesma Conta, guardam depois dos
    // resultados da Ana uma leitura do mesmo caixa e a mesma Venda.
    for (const outro of [{ ...ana, contaId: 'conta-b' }, { ...ana, usuarioId: 'bia' }]) {
      entrar(outro)
      await caixaLido(99)
      await guardarRetratoDaVenda(vendaId, { id: vendaId })
    }

    entrar(ana)
    expect((await listarGestos()).map((gesto) => gesto.operacaoId).sort()).toEqual(daAna)
    // As leituras da própria Ana, sim, os descartam.
    await caixaLido(99)
    await guardarRetratoDaVenda(vendaId, { id: vendaId })
    expect(await listarGestos()).toEqual([])
  })

  it('atualiza o banco local existente sem perder gestos ao criar os retratos', async () => {
    const antigo = await openDB('ecrx-caixa-offline', 1, {
      upgrade(banco) {
        const gestos = banco.createObjectStore('gestos', { keyPath: 'chave' })
        gestos.createIndex('porDono', 'dono')
      },
    })
    const operacaoId = '00000000-0000-4000-8000-000000000009'
    await antigo.put('gestos', {
      chave: `conta-a\u0000ana\u0000${operacaoId}`, dono: 'conta-a\u0000ana', operacaoId,
      registroId: produto.registroId, tipo: produto.tipo, payload: produto.payload,
      dependeDe: [], estado: 'queued', criadoEm: new Date().toISOString(),
    })
    antigo.close()

    entrar(ana)
    expect(await listarGestos()).toEqual([expect.objectContaining({ operacaoId, estado: 'queued' })])
  })

  it('confirma cada gesto no IndexedDB e o recupera após novo login na mesma Conta', async () => {
    entrar(ana)
    const payload = { nome: 'Café', preco: 7 }
    const criado = await enfileirarGesto({ ...produto, payload })
    payload.nome = 'alterado depois'

    expect(criado.operacaoId).toMatch(/^[0-9a-f-]{36}$/)
    expect(criado.estado).toBe('queued')
    entrar(ana)
    expect(await listarGestos()).toEqual([expect.objectContaining({
      operacaoId: criado.operacaoId, payload: { nome: 'Café', preco: 7 },
    })])
  })

  it('conserva dependências e recusa referência a gesto de outra Conta', async () => {
    entrar(ana)
    const primeiro = await enfileirarGesto(produto)
    const segundo = await enfileirarGesto({
      tipo: 'venda.iniciar', registroId: '00000000-0000-4000-8000-000000000002',
      payload: {}, dependeDe: [primeiro.operacaoId],
    })
    expect(segundo.dependeDe).toEqual([primeiro.operacaoId])

    entrar({ ...ana, contaId: 'conta-b' })
    await expect(enfileirarGesto({
      tipo: 'venda.iniciar', registroId: '00000000-0000-4000-8000-000000000003',
      payload: {}, dependeDe: [primeiro.operacaoId],
    })).rejects.toThrow('Dependência fora da fila')
    expect(await listarGestos()).toEqual([])
  })

  it('isola a fila por Conta e usuário mesmo quando o token muda', async () => {
    entrar(ana)
    const gesto = await enfileirarGesto(produto)
    entrar({ ...ana, contaId: 'conta-b' })
    expect(await listarGestos()).toEqual([])
    await expect(mudarEstadoDoGesto(gesto.operacaoId, 'queued', 'syncing'))
      .rejects.toThrow('Gesto não encontrado')

    entrar({ ...ana, usuarioId: 'outro' })
    expect(await listarGestos()).toEqual([])
    entrar(ana)
    expect((await listarGestos()).map((item) => item.operacaoId)).toEqual([gesto.operacaoId])
  })

  it('recupera o envio interrompido como falha, sem trocar o UUID, e numera cada resultado', async () => {
    entrar(ana)
    const primeiro = await enfileirarGesto(produto)
    await mudarEstadoDoGesto(primeiro.operacaoId, 'queued', 'syncing')
    entrar(ana)
    // Sem resposta, o servidor pode ter aplicado: não volta à fila como se nunca tivesse saído.
    await recuperarEnviosInterrompidos()
    expect((await listarGestos())[0]).toMatchObject({
      operacaoId: primeiro.operacaoId, estado: 'failed', falha: expect.stringContaining('interrompido'),
    })
    await expect(mudarEstadoDoGesto(primeiro.operacaoId, 'failed', 'queued'))
      .rejects.toThrow('Transição de estado inválida')
    await mudarEstadoDoGesto(primeiro.operacaoId, 'failed', 'syncing')
    await mudarEstadoDoGesto(primeiro.operacaoId, 'syncing', 'needs_review', {
      aplicada: true, detalhe: 'conferir estoque',
    })
    expect((await listarGestos())[0]).toMatchObject({
      operacaoId: primeiro.operacaoId, estado: 'needs_review', ordemDoResultado: 1,
      resultado: { aplicada: true, detalhe: 'conferir estoque' },
    })
    expect((await listarGestos())[0].falha).toBeUndefined()
    await expect(mudarEstadoDoGesto(primeiro.operacaoId, 'needs_review', 'sent'))
      .rejects.toThrow('Transição de estado inválida')

    const segundo = await enfileirarGesto({ ...produto,
      registroId: '00000000-0000-4000-8000-000000000004' })
    await mudarEstadoDoGesto(segundo.operacaoId, 'queued', 'syncing')
    await mudarEstadoDoGesto(segundo.operacaoId, 'syncing', 'sent', { aplicada: true, versao: 3 })
    entrar(ana)
    expect((await listarGestos()).find((item) => item.operacaoId === segundo.operacaoId))
      .toMatchObject({ estado: 'sent', resultado: { aplicada: true, versao: 3 }, ordemDoResultado: 2 })
  })

  it('marca o lote em envio de uma vez e ignora o desfecho de quem já mudou', async () => {
    entrar(ana)
    const a = await enfileirarGesto(produto)
    const b = await enfileirarGesto({ ...produto, registroId: '00000000-0000-4000-8000-000000000005' })
    const c = await enfileirarGesto({ ...produto, registroId: '00000000-0000-4000-8000-000000000006' })
    expect((await iniciarEnvio([a.operacaoId, b.operacaoId])).map((gesto) => gesto.operacaoId))
      .toEqual([a.operacaoId, b.operacaoId])
    // O que já está em envio não é marcado de novo.
    expect(await iniciarEnvio([a.operacaoId])).toEqual([])

    await registrarDesfechos([
      { operacaoId: a.operacaoId, estado: 'sent', resultado: { aplicada: true } },
      { operacaoId: b.operacaoId, estado: 'failed', falha: 'Sem conexão' },
      { operacaoId: c.operacaoId, estado: 'sent', resultado: { aplicada: true } },
    ])
    const porId = new Map((await listarGestos()).map((gesto) => [gesto.operacaoId, gesto]))
    expect(porId.get(a.operacaoId)).toMatchObject({ estado: 'sent', ordemDoResultado: 1 })
    expect(porId.get(b.operacaoId)).toMatchObject({ estado: 'failed', falha: 'Sem conexão' })
    expect(porId.get(b.operacaoId)?.ordemDoResultado).toBeUndefined()
    expect(porId.get(c.operacaoId)).toMatchObject({ estado: 'queued' })
  })

  it('confere as revisões sem mudar o estado, mantém a primeira conferência e deixa de fora o resto', async () => {
    entrar(ana)
    const gesto = await enfileirarGesto(produto)
    const naFila = await enfileirarGesto({ ...produto, registroId: '00000000-0000-4000-8000-000000000055' })
    const mudancas: string[] = []
    const parar = aoMudarFila((mudanca) => mudancas.push(mudanca))

    // O id de outro aparelho e o gesto sem resultado não têm o que conferir aqui.
    await conferirGestos([gesto.operacaoId, 'de-outro-aparelho'])
    expect(mudancas).toEqual([])
    expect((await listarGestos()).every((item) => !item.conferidoEm)).toBe(true)

    await mudarEstadoDoGesto(gesto.operacaoId, 'queued', 'syncing')
    await mudarEstadoDoGesto(gesto.operacaoId, 'syncing', 'needs_review', {
      aplicada: false, detalhe: 'caixa fechado',
    })
    mudancas.length = 0
    await conferirGestos([gesto.operacaoId, naFila.operacaoId])
    parar()
    expect(mudancas).toEqual(['conferencia'])

    const lido = async () => (await listarGestos()).find((item) => item.operacaoId === gesto.operacaoId)
    const conferido = await lido()
    expect(conferido).toMatchObject({ estado: 'needs_review', conferidoEm: expect.any(String) })
    await conferirGestos([gesto.operacaoId])
    expect((await lido())?.conferidoEm).toBe(conferido?.conferidoEm)
    expect((await listarGestos()).find((item) => item.operacaoId === naFila.operacaoId)?.conferidoEm).toBeUndefined()
  })

  it('guarda a leitura só quando sabe quais resultados ela contém', async () => {
    entrar(ana)
    // As duas listas de produtos vão juntas: o gesto enviado só sai quando as duas o contêm.
    const guardar = (dados: string[], ordem: number) => guardarRetratos([
      { tipo: 'produtos', dados }, { tipo: 'produtosInativos', dados: [] },
    ], ordem)
    await lerDoServidor(async () => ['sem fila'], guardar)
    expect(await lerRetrato('produtos')).toEqual({ dados: ['sem fila'], ordemDaLeitura: 0 })

    // Gesto em envio: o servidor pode ter aplicado ou não, e a leitura não é guardada.
    const gesto = await enfileirarGesto(produto)
    await mudarEstadoDoGesto(gesto.operacaoId, 'queued', 'syncing')
    expect(await lerDoServidor(async () => ['incerta'], guardar)).toEqual(['incerta'])
    expect((await lerRetrato('produtos'))?.dados).toEqual(['sem fila'])

    // O resultado chega durante a leitura: ela pode conter o gesto ou não.
    await lerDoServidor(async () => {
      await mudarEstadoDoGesto(gesto.operacaoId, 'syncing', 'sent', { aplicada: true })
      return ['durante']
    }, guardar)
    expect((await lerRetrato('produtos'))?.dados).toEqual(['sem fila'])

    const [enviado] = await listarGestos()
    expect(jaEstaNoRetrato(enviado, { ordemDaLeitura: 0 })).toBe(false)
    await lerDoServidor(async () => ['depois'], guardar)
    const retrato = await lerRetrato('produtos')
    expect(retrato).toEqual({ dados: ['depois'], ordemDaLeitura: 1 })
    expect(jaEstaNoRetrato(enviado, retrato)).toBe(true)
    expect(await listarGestos()).toEqual([])
  })

  it('lê como anterior a todo resultado o retrato gravado antes da ordem da leitura', async () => {
    const antigo = await openDB('ecrx-caixa-offline', 2, {
      upgrade(banco) {
        const gestos = banco.createObjectStore('gestos', { keyPath: 'chave' })
        gestos.createIndex('porDono', 'dono')
        banco.createObjectStore('retratos', { keyPath: 'chave' })
      },
    })
    await antigo.put('retratos', { chave: 'conta-a\u0000produtos', conta: 'conta-a', tipo: 'produtos',
      dados: ['antigo'] })
    antigo.close()

    entrar(ana)
    expect(await lerRetrato('produtos')).toEqual({ dados: ['antigo'], ordemDaLeitura: 0 })
  })

  it('recusa gesto novo sem sessão válida e conserva o que já estava gravado', async () => {
    entrar(ana)
    const primeiro = await enfileirarGesto(produto)
    gravarToken(tokenComExpiracao(new Date(Date.now() - 1000)))
    gravarIdentidade(ana)
    await expect(enfileirarGesto(produto)).rejects.toThrow('Entre novamente')
    entrar(ana)
    expect((await listarGestos()).map((item) => item.operacaoId)).toEqual([primeiro.operacaoId])
  })
})

describe('dados do aparelho', () => {
  const bia: Identidade = { ...ana, usuarioId: 'bia', nome: 'Bia', perfil: 'OPERADOR' }
  const registro = (final: string) => `00000000-0000-4000-8000-0000000000${final}`

  it('lê, sem sessão, os gestos de todos os usuários, um grupo por usuário', async () => {
    entrar(ana)
    await enfileirarGesto(produto)
    const comFalha = await enfileirarGesto({ ...produto, registroId: registro('42') })
    const enviado = await enfileirarGesto({ ...produto, registroId: registro('43') })
    const emRevisao = await enfileirarGesto({ ...produto, registroId: registro('44') })
    await iniciarEnvio([comFalha.operacaoId, enviado.operacaoId, emRevisao.operacaoId])
    await registrarDesfechos([
      { operacaoId: comFalha.operacaoId, estado: 'failed', falha: 'Sem conexão' },
      { operacaoId: enviado.operacaoId, estado: 'sent', resultado: { aplicada: true } },
      { operacaoId: emRevisao.operacaoId, estado: 'needs_review', resultado: { aplicada: false } },
    ])
    entrar(bia)
    const emEnvio = await enfileirarGesto({ ...produto, registroId: registro('45') })
    await iniciarEnvio([emEnvio.operacaoId])
    limparSessao()

    const grupos = await gestosDeCadaUsuario()
    // Gestos feitos no mesmo milissegundo podem vir em qualquer ordem: compara-se o conjunto de cada grupo.
    expect(grupos.map((grupo) => grupo.map((gesto) => gesto.estado).sort()).sort((a, b) => a.length - b.length))
      .toEqual([['syncing'], ['failed', 'needs_review', 'queued', 'sent']])
  })

  it('dá a quem entrou os gestos dos outros usuários, sem os dele, e pede a sessão', async () => {
    entrar(ana)
    await enfileirarGesto(produto)
    entrar(bia)
    const daBia = await enfileirarGesto({ ...produto, registroId: registro('46') })
    entrar({ ...ana, contaId: 'conta-b', usuarioId: 'ana' })
    const deOutraConta = await enfileirarGesto({ ...produto, registroId: registro('47') })
    entrar(ana)

    const grupos = await gestosDosOutrosUsuarios()

    // A mesma pessoa em outra Conta é outro dono: a fila dela também só sai quando ela entra lá.
    expect(grupos.map((grupo) => grupo.map((gesto) => gesto.operacaoId)).sort())
      .toEqual([[daBia.operacaoId], [deOutraConta.operacaoId]].sort())
    limparSessao()
    await expect(gestosDosOutrosUsuarios()).rejects.toThrow()
  })

  it('apaga a fila, os retratos e as chaves do aplicativo, e o nome do Cliente não fica', async () => {
    entrar(ana)
    await guardarRetrato('clientesAtivos', [{ id: registro('31'), nome: 'Joana Lima',
      contato: 'joana@exemplo.test' }], 0)
    await enfileirarGesto({ tipo: 'cliente.criar', registroId: registro('31'),
      payload: { nome: 'Joana Lima', contato: 'joana@exemplo.test' } })
    limparSessao()
    localStorage.setItem('ecrx-caixa.token.de-uma-sessao-antiga', 'token')
    sessionStorage.setItem('ecrx-caixa.intencao-online.conta-a.ana.resumo', 'id')
    localStorage.setItem('outro-aplicativo', 'fica')

    await apagarDadosDoAparelho()

    expect(localStorage.getItem('ecrx-caixa.token.de-uma-sessao-antiga')).toBeNull()
    expect(sessionStorage.getItem('ecrx-caixa.intencao-online.conta-a.ana.resumo')).toBeNull()
    expect(localStorage.getItem('outro-aplicativo')).toBe('fica')
    expect(await gestosDeCadaUsuario()).toEqual([])
    entrar(ana)
    expect(await listarGestos()).toEqual([])
    expect(await lerRetrato('clientesAtivos')).toBeNull()
  })

  it('recusa apagar com uma sessão aberta e não apaga nada', async () => {
    entrar(ana)
    await enfileirarGesto(produto)

    await expect(apagarDadosDoAparelho()).rejects.toThrow(SessaoAbertaNoAparelho)

    expect(await listarGestos()).toHaveLength(1)
    expect(lerIdentidade()).toEqual(ana)
  })
})

describe('a fila entre as abas', () => {
  it('avisa as outras abas a cada mudança, sem nada do gesto', async () => {
    entrar(ana)
    const outra = new BroadcastChannel('ecrx-caixa-abas')
    const naOutra: unknown[] = []
    outra.addEventListener('message', (evento) => naOutra.push(evento.data))
    try {
      const gesto = await enfileirarGesto(produto)
      await aplicados(gesto)
      await new Promise((resolver) => setTimeout(resolver, 0))
      // O gesto novo, o envio e o resultado.
      expect(naOutra).toEqual([{ tipo: 'fila' }, { tipo: 'fila' }, { tipo: 'fila' }])
    } finally {
      outra.close()
    }
  })

  it('a mudança feita em outra aba chega aos ouvintes desta, sem dizer qual foi', async () => {
    const outra = new BroadcastChannel('ecrx-caixa-abas')
    const mudancas: string[] = []
    const parar = aoMudarFila((mudanca) => mudancas.push(mudanca))
    try {
      outra.postMessage({ tipo: 'fila' })
      await vi.waitFor(() => expect(mudancas).toEqual(['outra-aba']))
    } finally {
      parar()
      outra.close()
    }
  })
})
