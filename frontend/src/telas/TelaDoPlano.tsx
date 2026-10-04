import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { SemConexao } from '../api/cliente'
import {
  contas, type EstadoDoPlano, type PedidoDePlano, type PropostaDePlano, type TipoDePedido,
} from '../api/contas'
import { sessaoAtual } from '../sessao/armazenamento'
import type { Plano, SituacaoDoPlano } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'
import { useOnline } from '../shell/useOnline'
import { erroDeCadastro } from './erroDeCadastro'

const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

const NOME_DO_PLANO: Record<Plano, string> = {
  GRATIS: 'Gratuito',
  CAIXA_SIMPLES: 'Caixa Simples',
  COMPLETO: 'Completo',
}

const O_QUE_INCLUI: Record<Plano, string> = {
  GRATIS: 'Cadastro, vendas e caixa, com um usuário.',
  CAIXA_SIMPLES: 'Tudo do Gratuito, mais os relatórios de faturamento, mais vendidos e fluxo de caixa.',
  COMPLETO: 'Tudo do Caixa Simples, mais o controle de estoque e mais de um usuário.',
}

const NOME_DO_TIPO: Record<TipoDePedido, string> = {
  ADESAO: 'Adesão',
  UPGRADE: 'Upgrade',
  RENOVACAO: 'Renovação',
}

const NOME_DA_SITUACAO: Record<SituacaoDoPlano, string> = {
  SEM_MENSALIDADE: 'Sem mensalidade',
  EM_DIA: 'Em dia',
  A_VENCER: 'Vence nos próximos dias',
  VENCIDO: 'Vencido, na tolerância',
  SUSPENSO: 'Suspenso',
}

/**
 * A troca de plano pela própria Conta (RF31). O administrador pede o plano, manda o texto do
 * pedido pelo canal de atendimento e faz o Pix; depois de conferir o Pix, o mantenedor entrega um
 * código, que o administrador aplica aqui. O pedido sozinho não muda nada: o plano só vale com o
 * código.
 *
 * Tudo aqui pede conexão, porque o pedido e o código são conferidos no servidor. Aplicado o
 * código, a tela pergunta de novo quem está operando, para o menu mostrar os recursos novos.
 */
export function TelaDoPlano() {
  const { atualizarIdentidade } = useSessao()
  const online = useOnline()
  const [estado, setEstado] = useState<EstadoDoPlano | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)
  const [codigo, setCodigo] = useState('')
  const [enviando, setEnviando] = useState(false)

  const carregar = useCallback(async () => {
    try {
      setEstado(await contas.plano())
      setErro(null)
    } catch (falha) {
      setErro(mensagemDe(falha))
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  async function pedir(proposta: PropostaDePlano) {
    if (!estado) return
    if (estado.pedidoAberto && !window.confirm('Já existe um pedido aberto. O novo pedido substitui'
      + ' o aberto, e o código dele deixa de valer. Continuar?')) return
    setEnviando(true)
    setErro(null)
    setAviso(null)
    try {
      await contas.pedirPlano(proposta.plano)
      await carregar()
      setAviso('Pedido registrado. Mande o texto abaixo pelo canal de atendimento e faça o Pix do'
        + ' valor; o código chega depois da conferência.')
    } catch (falha) {
      setErro(mensagemDe(falha))
    } finally {
      setEnviando(false)
    }
  }

  async function aplicar(evento: FormEvent) {
    evento.preventDefault()
    const pedido = estado?.pedidoAberto
    if (!pedido) return
    setEnviando(true)
    setErro(null)
    setAviso(null)
    try {
      setEstado(await contas.aplicarCodigo(pedido.id, codigo))
      setCodigo('')
      setAviso('Código aplicado. O plano já vale.')
      await lerIdentidadeDoServidor()
    } catch (falha) {
      setErro(mensagemDe(falha))
    } finally {
      setEnviando(false)
    }
  }

  async function lerIdentidadeDoServidor() {
    const sessao = sessaoAtual()
    if (!sessao) return
    try {
      const atual = await contas.identidade()
      if (sessaoAtual() === sessao) atualizarIdentidade(atual, sessao)
    } catch {
      // O plano já foi aplicado; o menu se atualiza na próxima abertura do aplicativo.
    }
  }

  async function copiar(texto: string) {
    try {
      await navigator.clipboard.writeText(texto)
      setAviso('Texto do pedido copiado.')
    } catch {
      setAviso('Não foi possível copiar. Selecione o texto do pedido e copie à mão.')
    }
  }

  async function compartilhar(texto: string) {
    try {
      await navigator.share({ text: texto })
    } catch {
      // Quem cancela o compartilhamento não precisa de aviso.
    }
  }

  const upgradeEsperaRenovacao = estado?.plano === 'CAIXA_SIMPLES'
    && (estado.situacao === 'VENCIDO' || estado.situacao === 'SUSPENSO')

  return <section className="cadastro">
    <h2 className="titulo">Plano</h2>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {aviso && <p role="status">{aviso}</p>}
    {!online && <p role="status">Pedir plano e aplicar código precisam de conexão.</p>}

    {estado && <>
      <section className="relatorios__cartao">
        <h3>Plano atual: {NOME_DO_PLANO[estado.plano]}</h3>
        <p>{NOME_DA_SITUACAO[estado.situacao]}</p>
        {estado.vencimento && <p>Vencimento: {mostrarData(estado.vencimento)}</p>}
        {estado.situacao === 'VENCIDO' && estado.inicioDaSuspensao &&
          <p>Sem a renovação, os recursos pagos param em {mostrarData(estado.inicioDaSuspensao)}.</p>}
        {estado.situacao === 'SUSPENSO' && estado.inicioDaSuspensao &&
          <p>Os recursos pagos estão suspensos desde {mostrarData(estado.inicioDaSuspensao)}. Venda,
            caixa e cadastro continuam.</p>}
      </section>

      <h3>O que cada plano inclui</h3>
      <ul className="cadastro__lista">
        <li className="cadastro__item"><div>
          <strong>{NOME_DO_PLANO.GRATIS}</strong>
          <p>{O_QUE_INCLUI.GRATIS} Sem mensalidade.</p>
        </div></li>
        <li className="cadastro__item"><div>
          <strong>{NOME_DO_PLANO.CAIXA_SIMPLES}</strong>
          <p>{O_QUE_INCLUI.CAIXA_SIMPLES} {moeda.format(estado.mensalidadeCaixaSimples)} por mês.</p>
        </div></li>
        <li className="cadastro__item"><div>
          <strong>{NOME_DO_PLANO.COMPLETO}</strong>
          <p>{O_QUE_INCLUI.COMPLETO} {moeda.format(estado.mensalidadeCompleto)} por mês.</p>
          <p>A emissão de NFC-e ainda não está disponível.</p>
        </div></li>
      </ul>

      <h3>Pedir</h3>
      <p className="cadastro__apoio">O pedido não muda o plano. Mande o texto do pedido pelo canal
        de atendimento e faça o Pix do valor; depois da conferência, você recebe um código e o
        aplica aqui.</p>
      {upgradeEsperaRenovacao &&
        <p>Para pedir o Completo, renove antes o Caixa Simples. Depois da renovação, o upgrade
          cobra só a diferença pelos dias que faltam.</p>}
      <div className="cadastro__acoes">
        {estado.propostas.map((proposta) =>
          <button className="botao" type="button" key={`${proposta.tipo}-${proposta.plano}`}
            disabled={enviando || !online} onClick={() => void pedir(proposta)}>
            {rotuloDaProposta(proposta)}
          </button>)}
      </div>

      {estado.pedidoAberto && <PedidoAberto pedido={estado.pedidoAberto} enviando={enviando}
        online={online} codigo={codigo} definirCodigo={setCodigo}
        aplicar={(evento) => void aplicar(evento)} copiar={(texto) => void copiar(texto)}
        compartilhar={(texto) => void compartilhar(texto)} />}
    </>}
  </section>
}

type PropsDoPedido = {
  pedido: PedidoDePlano
  enviando: boolean
  online: boolean
  codigo: string
  definirCodigo: (codigo: string) => void
  aplicar: (evento: FormEvent) => void
  copiar: (texto: string) => void
  compartilhar: (texto: string) => void
}

function PedidoAberto(props: PropsDoPedido) {
  const texto = textoDoPedido(props.pedido)
  const podeCompartilhar = typeof navigator.share === 'function'
  return <form className="cadastro__formulario" onSubmit={props.aplicar}>
    <h3>Pedido à espera do código</h3>
    <textarea className="campo__entrada" readOnly rows={6} value={texto}
      aria-label="Texto do pedido" />
    <div className="cadastro__acoes">
      <button className="botao botao--secundario" type="button"
        onClick={() => props.copiar(texto)}>Copiar texto</button>
      {podeCompartilhar && <button className="botao botao--secundario" type="button"
        onClick={() => props.compartilhar(texto)}>Compartilhar</button>}
    </div>
    <label className="campo"><span className="campo__rotulo">Código de ativação</span>
      <input className="campo__entrada" value={props.codigo} required autoComplete="off"
        placeholder="XXXX-XXXX-XXXX-XXXX"
        onChange={(evento) => props.definirCodigo(evento.target.value)} />
    </label>
    <button className="botao" disabled={props.enviando || !props.online}>
      {props.enviando ? 'Aplicando...' : 'Aplicar código'}
    </button>
  </form>
}

/**
 * O que o administrador manda pelo canal de atendimento: o bastante para o mantenedor achar o
 * pedido e conferir o Pix, e nada além disso. Sem nome do negócio nem de pessoa.
 */
function textoDoPedido(pedido: PedidoDePlano): string {
  const periodo = pedido.periodoInicio && pedido.periodoFim
    ? `de ${mostrarData(pedido.periodoInicio)} a ${mostrarData(diaAnterior(pedido.periodoFim))}`
    : 'começa no dia em que o código for aplicado'
  return [
    'Pedido de plano',
    `Pedido: ${pedido.id}`,
    `Tipo: ${NOME_DO_TIPO[pedido.tipo]}`,
    `Plano: ${NOME_DO_PLANO[pedido.plano]}`,
    `Valor: ${moeda.format(pedido.valor)}`,
    `Período: ${periodo}`,
  ].join('\n')
}

function rotuloDaProposta(proposta: PropostaDePlano): string {
  const valor = moeda.format(proposta.valor)
  const nome = NOME_DO_PLANO[proposta.plano]
  if (proposta.tipo === 'ADESAO') return `Pedir o ${nome} (${valor} por mês)`
  const ate = proposta.periodoFim ? mostrarData(diaAnterior(proposta.periodoFim)) : ''
  if (proposta.tipo === 'UPGRADE') return `Pedir o ${nome} (${valor} pela diferença até ${ate})`
  const de = proposta.periodoInicio ? mostrarData(proposta.periodoInicio) : ''
  return `Renovar o ${nome} de ${de} a ${ate} (${valor})`
}

function mensagemDe(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem conexão com o servidor. O plano precisa de conexão.'
  return erroDeCadastro(falha)
}

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}

/** O fim do período é exclusive, o próprio vencimento: o último dia pago é o anterior. */
function diaAnterior(dia: string): string {
  const [ano, mes, data] = dia.split('-').map(Number)
  return new Date(Date.UTC(ano, mes - 1, data - 1)).toISOString().slice(0, 10)
}
