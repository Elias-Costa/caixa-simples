import type { FormaPagamento } from '../api/vendas'
import { diaNoBalcao } from '../dataDoBalcao'
import { dataHora, diaEMes, hora, moeda, quantidadeComUnidade } from '../formatos'
import { gestoRecusado, type GestoNaFila, type ValorJson } from '../offline/fila'
import type { RegistroDaFila } from '../offline/registrosDaFila'
import { quando } from '../shell/vendaRecusada'
import { ROTULO_DA_FORMA } from './rotulos'

/** Os nomes pelo cadastro deste aparelho, para o gesto que só guarda o id. */
export type NomesDoCadastro = {
  cliente: (clienteId: string) => string | undefined
  produto: (produtoId: string) => string | undefined
}

const ROTULOS: Record<string, string> = {
  'produto.criar': 'Produto cadastrado',
  'produto.editar': 'Produto editado',
  'produto.inativar': 'Produto inativado',
  'produto.reativar': 'Produto reativado',
  'cliente.criar': 'Cliente cadastrado',
  'cliente.editar': 'Cliente editado',
  'cliente.inativar': 'Cliente inativado',
  'cliente.reativar': 'Cliente reativado',
  'caixa.abrir': 'Caixa aberto',
  'caixa.sangrar': 'Sangria',
  'caixa.suprir': 'Suprimento',
  'caixa.fechar': 'Caixa fechado',
  'venda.iniciar': 'Venda iniciada',
  'venda.adicionarItem': 'Item na venda',
  'venda.removerItem': 'Item retirado da venda',
  'venda.aplicarDesconto': 'Desconto na venda',
  'venda.vincularCliente': 'Cliente na venda',
  'venda.desvincularCliente': 'Cliente retirado da venda',
  'venda.registrarPagamento': 'Pagamento da venda',
  'venda.concluir': 'Venda concluída',
  'venda.cancelar': 'Venda cancelada',
}

const listaDeFormas = new Intl.ListFormat('pt-BR', { style: 'long', type: 'conjunction' })

export function rotulo(tipo: string): string {
  return ROTULOS[tipo] ?? tipo
}

type Campos = Record<string, ValorJson>

function camposDe(payload: ValorJson): Campos {
  return payload && typeof payload === 'object' && !Array.isArray(payload) ? payload : {}
}

function texto(campos: Campos, campo: string): string | null {
  return typeof campos[campo] === 'string' ? campos[campo] as string : null
}

function dinheiro(campos: Campos, campo: string): string | null {
  return typeof campos[campo] === 'number' ? moeda.format(campos[campo] as number) : null
}

/** Só o que tem valor, porque o gesto antigo e o do cliente removido podem vir sem algum campo. */
function presentes(partes: readonly (string | null | undefined)[]): string[] {
  return partes.filter((parte): parte is string => !!parte)
}

/** Os campos que quem operou reconhece no balcão, sem os ids que só o sistema lê. */
function partesDoConteudo(tipo: string, campos: Campos, nomes: NomesDoCadastro): string[] {
  switch (tipo) {
    case 'produto.criar':
    case 'produto.editar':
      return presentes([texto(campos, 'nome'), dinheiro(campos, 'preco')])
    case 'cliente.criar':
    case 'cliente.editar':
      return presentes([texto(campos, 'nome'), texto(campos, 'contato')])
    case 'caixa.abrir':
      return presentes([dinheiro(campos, 'valorAbertura')])
    case 'caixa.sangrar':
    case 'caixa.suprir':
      return presentes([dinheiro(campos, 'valor'), texto(campos, 'motivo')])
    case 'caixa.fechar': {
      const contado = dinheiro(campos, 'valorContado')
      return contado ? [`contado ${contado}`] : []
    }
    case 'venda.adicionarItem': {
      const nome = texto(campos, 'nome')
      const item = typeof campos.quantidade === 'number'
        ? `${quantidadeComUnidade(campos.quantidade, texto(campos, 'unidade'))} × ${nome}` : nome
      return presentes([item, dinheiro(campos, 'precoUnitario')])
    }
    case 'venda.vincularCliente': {
      const clienteId = texto(campos, 'clienteId')
      return presentes([clienteId ? nomes.cliente(clienteId) : null])
    }
    case 'venda.registrarPagamento': {
      const forma = texto(campos, 'forma')
      return presentes([forma ? ROTULO_DA_FORMA[forma as FormaPagamento] ?? forma : null, dinheiro(campos, 'valor')])
    }
    case 'venda.aplicarDesconto':
      return presentes([dinheiro(campos, 'valor')])
    default:
      return []
  }
}

/** O conteúdo de um passo; a conclusão e o início da venda não têm o que resumir. */
export function conteudoDoPasso(tipo: string, payload: ValorJson, nomes: NomesDoCadastro): string {
  return partesDoConteudo(tipo, camposDe(payload), nomes).join(' · ')
}

function ehVenda(registro: RegistroDaFila): boolean {
  return registro.venda !== undefined
}

function primeiroTipo(registro: RegistroDaFila): string {
  return registro.gestos[0].tipo
}

/** A hora depois de um particípio, como em Caixa aberto às 23:35, com o dia quando é de outro dia. */
function noMomento(criadoEm: string, hoje: string): string {
  const instante = new Date(criadoEm)
  return diaNoBalcao(instante) === hoje ? `às ${hora.format(instante)}`
    : `em ${diaEMes.format(instante)} às ${hora.format(instante)}`
}

/** O nome mais recente que os gestos do cadastro trazem; a inativação só traz o id. */
function nomeDoCadastro(registro: RegistroDaFila, nomes: NomesDoCadastro): string | undefined {
  const nome = presentes(registro.gestos.map((gesto) => texto(camposDe(gesto.payload), 'nome'))).at(-1)
  if (nome) return nome
  const { tipo, registroId } = registro.gestos[0]
  return tipo.startsWith('cliente.') ? nomes.cliente(registroId)
    : tipo.startsWith('produto.') ? nomes.produto(registroId) : undefined
}

/** A primeira linha: qual registro é, pela hora e pelo valor que a pessoa viu no balcão. */
export function tituloDoRegistro(registro: RegistroDaFila, hoje: string, nomes: NomesDoCadastro): string {
  const tipo = primeiroTipo(registro)
  const campos = camposDe(registro.gestos[0].payload)
  if (registro.venda) {
    const total = registro.venda.totalCentavos
    return presentes([`Venda ${quando(registro.criadoEm, hoje)}`,
      total === undefined ? null : moeda.format(total / 100)]).join(', ')
  }
  switch (tipo) {
    case 'caixa.sangrar':
    case 'caixa.suprir':
      return presentes([`${rotulo(tipo)} ${quando(registro.criadoEm, hoje)}`, dinheiro(campos, 'valor')]).join(', ')
    case 'caixa.abrir':
      return presentes([`${rotulo(tipo)} ${noMomento(registro.criadoEm, hoje)}`, dinheiro(campos, 'valorAbertura')]).join(', ')
    case 'caixa.fechar': {
      const contado = dinheiro(campos, 'valorContado')
      return presentes([`${rotulo(tipo)} ${noMomento(registro.criadoEm, hoje)}`, contado && `contado ${contado}`]).join(', ')
    }
    default: {
      const nome = nomeDoCadastro(registro, nomes)
      return `${rotulo(tipo)} ${noMomento(registro.criadoEm, hoje)}${nome ? `: ${nome}` : ''}`
    }
  }
}

/**
 * O que completa o título. A venda diz as formas; sem total, com o valor, porque ele é o único dinheiro
 * que a lista traz. A venda de um gesto só diz o que ele tem, e os outros registros, o motivo ou o preço.
 */
function apoioDoRegistro(registro: RegistroDaFila, nomes: NomesDoCadastro): string[] {
  const tipo = primeiroTipo(registro)
  if (registro.venda) {
    const { totalCentavos, formas } = registro.venda
    if (totalCentavos === undefined && registro.gestos.length === 1) {
      return partesDoConteudo(tipo, camposDe(registro.gestos[0].payload), nomes)
    }
    if (formas.length === 0) return []
    return [listaDeFormas.format(formas.map((forma) => totalCentavos === undefined
      ? `${ROTULO_DA_FORMA[forma.forma]} ${moeda.format(forma.valor)}` : ROTULO_DA_FORMA[forma.forma]))]
  }
  if (tipo === 'caixa.sangrar' || tipo === 'caixa.suprir') {
    return presentes([texto(camposDe(registro.gestos[0].payload), 'motivo')])
  }
  if (tipo.startsWith('produto.')) {
    return presentes(registro.gestos.map((gesto) => dinheiro(camposDe(gesto.payload), 'preco'))).slice(-1)
  }
  // Com mais de um gesto, o contato fica nos passos.
  if (tipo.startsWith('cliente.') && registro.gestos.length === 1) {
    return presentes([texto(camposDe(registro.gestos[0].payload), 'contato')])
  }
  return []
}

function passos(registro: RegistroDaFila): string[] {
  return registro.gestos.length > 1 ? [`${registro.gestos.length} passos`] : []
}

/** A sangria e a venda são femininas; o resto do que a fila guarda, masculino. */
function feminino(registro: RegistroDaFila): boolean {
  return ehVenda(registro) || primeiroTipo(registro) === 'caixa.sangrar'
}

/** Onde o registro está e o que o servidor disse dele, em uma frase. */
export function situacaoDaLinha(registro: RegistroDaFila): string {
  const a = feminino(registro)
  if (registro.situacao === 'por-enviar') {
    const comFalha = registro.gestos.find((gesto) => gesto.estado === 'failed')
    if (comFalha) return `Com falha, será ${a ? 'repetida' : 'repetido'}: ${comFalha.falha ?? 'Sem resposta do servidor.'}`
    return registro.gestos.some((gesto) => gesto.estado === 'syncing') ? 'Enviando' : 'Na fila'
  }
  const motivo = registro.motivo ?? 'sem detalhe do servidor'
  if (!registro.recusado) return `${a ? 'Aplicada' : 'Aplicado'}, com revisão: ${motivo}`
  // A venda cujo início valeu existe no servidor, aberta; recusado o início, ela nem existe lá.
  const noInicio = registro.gestos.find(gestoRecusado)?.tipo === 'venda.iniciar'
  if (ehVenda(registro) && !noInicio) return `Não concluída: ${motivo}`
  return `${a ? 'Recusada' : 'Recusado'}: ${motivo}`
}

/** As linhas abaixo do título. Por enviar cabe tudo numa só; em revisão, o motivo tem a linha dele. */
export function linhasDoRegistro(registro: RegistroDaFila, nomes: NomesDoCadastro): string[] {
  const apoio = apoioDoRegistro(registro, nomes)
  if (registro.situacao === 'por-enviar') {
    return [[...apoio, situacaoDaLinha(registro), ...passos(registro)].join(' · ')]
  }
  const dados = [...apoio, ...passos(registro)].join(' · ')
  return presentes([dados, situacaoDaLinha(registro)])
}

/** Em que pé está um gesto da fila, dentro dos passos do registro. */
export function situacaoDoPasso(gesto: GestoNaFila): string {
  if (gesto.estado === 'queued') return 'Na fila'
  if (gesto.estado === 'syncing') return 'Enviando'
  if (gesto.estado === 'sent') return 'Aplicado'
  if (gesto.estado === 'failed') return `Com falha, será repetido: ${gesto.falha ?? 'sem resposta do servidor'}`
  const detalhe = gesto.resultado?.detalhe ?? 'sem detalhe do servidor'
  return gesto.resultado?.aplicada ? `Aplicado no servidor, com revisão: ${detalhe}` : `Não aplicado: ${detalhe}`
}

export function quandoConferido(gesto: GestoNaFila): string {
  return gesto.conferidoEm ? ` · conferido em ${dataHora.format(new Date(gesto.conferidoEm))}` : ''
}
