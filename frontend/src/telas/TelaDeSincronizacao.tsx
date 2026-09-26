import { useCallback, useEffect, useState } from 'react'
import { contas } from '../api/contas'
import { sincronizacao, type RevisaoDaConta, type RevisoesDaConta } from '../api/sincronizacao'
import { hojeNoBalcao } from '../dataDoBalcao'
import {
  aoMudarFila, conferirGesto, gestoPendente, listarGestos, type GestoNaFila, type ValorJson,
} from '../offline/fila'
import { useSincronizacao } from '../shell/sincronizacao'
import { useOnline } from '../shell/useOnline'
import { useSessao } from '../sessao/useSessao'
import { erroDeCadastro } from './erroDeCadastro'

const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
const dataHora = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short', timeStyle: 'short', timeZone: 'America/Bahia',
})

const ROTULOS: Record<string, string> = {
  'produto.criar': 'Produto cadastrado',
  'produto.editar': 'Produto editado',
  'produto.inativar': 'Produto inativado',
  'cliente.criar': 'Cliente cadastrado',
  'cliente.editar': 'Cliente editado',
  'cliente.inativar': 'Cliente inativado',
  'cliente.reativar': 'Cliente reativado',
  'caixa.abrir': 'Caixa aberto',
  'caixa.sangrar': 'Sangria',
  'caixa.suprir': 'Suprimento',
  'caixa.fechar': 'Caixa fechado',
  'venda.iniciar': 'Venda iniciada',
  'venda.adicionarItem': 'Item na Venda',
  'venda.removerItem': 'Item retirado da Venda',
  'venda.aplicarDesconto': 'Desconto na Venda',
  'venda.vincularCliente': 'Cliente na Venda',
  'venda.registrarPagamento': 'Pagamento da Venda',
  'venda.concluir': 'Venda concluída',
  'venda.cancelar': 'Venda cancelada',
}

function rotulo(tipo: string): string {
  return ROTULOS[tipo] ?? tipo
}

/** Os campos que quem operou reconhece no balcão, sem os ids que só o sistema lê. */
function partesDoConteudo(tipo: string, dados: Record<string, ValorJson>): (string | null)[] {
  const texto = (campo: string) => typeof dados[campo] === 'string' ? dados[campo] as string : null
  const dinheiro = (campo: string) => typeof dados[campo] === 'number' ? moeda.format(dados[campo] as number) : null
  switch (tipo) {
    case 'produto.criar':
    case 'produto.editar':
      return [texto('nome'), dinheiro('preco')]
    case 'cliente.criar':
    case 'cliente.editar':
      return [texto('nome'), texto('contato')]
    case 'caixa.abrir':
      return [dinheiro('valorAbertura')]
    case 'caixa.sangrar':
    case 'caixa.suprir':
      return [dinheiro('valor'), texto('motivo')]
    case 'caixa.fechar':
      return [dinheiro('valorContado') ? `contado ${dinheiro('valorContado')}` : null]
    case 'venda.adicionarItem':
      return [typeof dados.quantidade === 'number' ? `${dados.quantidade} × ${texto('nome')}` : texto('nome'),
        dinheiro('precoUnitario')]
    case 'venda.registrarPagamento':
      return [texto('forma'), dinheiro('valor')]
    case 'venda.aplicarDesconto':
      return [dinheiro('valor')]
    default:
      return []
  }
}

function resumoDoConteudo(tipo: string, payload: ValorJson): string {
  const dados = payload && typeof payload === 'object' && !Array.isArray(payload) ? payload : {}
  return partesDoConteudo(tipo, dados).filter((parte) => !!parte).join(' · ')
}

/** A conclusão e o início da Venda não têm o que resumir; aí a linha fica de fora. */
function Conteudo({ tipo, payload }: { tipo: string; payload: ValorJson }) {
  const resumo = resumoDoConteudo(tipo, payload)
  return resumo ? <p>{resumo}</p> : null
}

function situacao(gesto: GestoNaFila): string {
  if (gesto.estado === 'queued') return 'Na fila'
  if (gesto.estado === 'syncing') return 'Enviando'
  if (gesto.estado === 'failed') return `Com falha, será repetido: ${gesto.falha ?? 'sem resposta do servidor'}`
  const detalhe = gesto.resultado?.detalhe ?? 'sem detalhe do servidor'
  return gesto.resultado?.aplicada ? `Aplicado no servidor, com revisão: ${detalhe}` : `Não aplicado: ${detalhe}`
}

function ItemDaFila({ gesto, conferir }: { gesto: GestoNaFila; conferir?: () => void }) {
  return (
    <li className="cadastro__item">
      <div>
        <strong>{rotulo(gesto.tipo)}</strong>
        <Conteudo tipo={gesto.tipo} payload={gesto.payload} />
        <p className="cadastro__apoio">
          {dataHora.format(new Date(gesto.criadoEm))} · {situacao(gesto)}
          {gesto.conferidoEm ? ` · conferido em ${dataHora.format(new Date(gesto.conferidoEm))}` : ''}
        </p>
      </div>
      {conferir && <button type="button" className="botao botao--secundario" onClick={conferir}>Conferir</button>}
    </li>
  )
}

function ItemDaConta({ revisao, nome, conferir }: {
  revisao: RevisaoDaConta; nome: (id?: string) => string; conferir?: () => void
}) {
  const desfecho = revisao.resultado === 'NAO_APLICADA' ? 'Não aplicado' : 'Aplicado, com revisão'
  return (
    <li className="cadastro__item">
      <div>
        <strong>{rotulo(revisao.tipo)}</strong>, por {nome(revisao.usuarioId)}
        <Conteudo tipo={revisao.tipo} payload={revisao.payload} />
        <p className="cadastro__apoio">
          {desfecho}: {revisao.detalhe ?? 'sem detalhe do servidor'} · feito em{' '}
          {dataHora.format(new Date(revisao.criadaEm))}, recebido em {dataHora.format(new Date(revisao.recebidaEm))}
        </p>
        {revisao.conferidaEm && (
          <p className="cadastro__apoio">
            Conferido por {nome(revisao.conferidaPor)} em {dataHora.format(new Date(revisao.conferidaEm))}
          </p>
        )}
      </div>
      {conferir && <button type="button" className="botao botao--secundario" onClick={conferir}>Conferir</button>}
    </li>
  )
}

/**
 * O que saiu deste aparelho e o que o servidor disse de cada gesto (RNF02). Quem operou vê o que
 * ainda não foi, o que falhou e será repetido, e o que foi revisado ou recusado, e confere cada
 * revisão aqui. O administrador vê também as revisões de todos os aparelhos da Conta, lidas do
 * servidor, e a conferência dele fica gravada lá, com quem e quando.
 */
export function TelaDeSincronizacao() {
  const { identidade } = useSessao()
  const { rodada, enviando, enviarAgora, atualizar } = useSincronizacao()
  const online = useOnline()
  const administrador = identidade?.perfil === 'ADMIN'
  const [gestos, setGestos] = useState<GestoNaFila[]>([])
  const [dia, setDia] = useState(hojeNoBalcao)
  const [daConta, setDaConta] = useState<RevisoesDaConta>()
  const [nomes, setNomes] = useState<Map<string, string>>(() => new Map())
  const [erro, setErro] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)

  const lerAparelho = useCallback(async () => {
    try {
      setGestos(await listarGestos())
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }, [])

  const lerDaConta = useCallback(async () => {
    if (!administrador || !online) return
    try {
      const [revisoes, usuarios] = await Promise.all([sincronizacao.revisoes(dia), contas.usuarios()])
      setDaConta(revisoes)
      setNomes(new Map(usuarios.map((usuario) => [usuario.id, usuario.nome])))
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }, [administrador, online, dia])

  useEffect(() => { queueMicrotask(() => void lerAparelho()) }, [lerAparelho, rodada])
  useEffect(() => aoMudarFila(() => { void lerAparelho() }), [lerAparelho])
  useEffect(() => { queueMicrotask(() => void lerDaConta()) }, [lerDaConta, rodada])

  async function conferirNoAparelho(operacaoId: string) {
    setOcupado(true)
    try {
      await conferirGesto(operacaoId)
      setErro(null)
      atualizar()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setOcupado(false)
    }
  }

  async function conferirNaConta(operacaoId: string) {
    setOcupado(true)
    try {
      await sincronizacao.conferir(operacaoId)
      // O gesto que saiu deste aparelho sai também do aviso daqui.
      if (gestos.some((gesto) => gesto.operacaoId === operacaoId && gesto.estado === 'needs_review'
        && !gesto.conferidoEm)) {
        await conferirGesto(operacaoId)
      }
      setErro(null)
      await lerDaConta()
      atualizar()
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setOcupado(false)
    }
  }

  const nome = (id?: string) => (id && nomes.get(id)) || 'usuário não identificado'
  const porEnviar = gestos.filter(gestoPendente)
  const emRevisao = gestos.filter((gesto) => gesto.estado === 'needs_review' && !gesto.conferidoEm)
  const conferidos = gestos.filter((gesto) => gesto.estado === 'needs_review' && gesto.conferidoEm)

  return (
    <section className="caixa">
      <h2 className="titulo">Sincronização</h2>
      {erro && <p className="mensagem-erro" role="alert">{erro}</p>}

      <div className="caixa__cartao">
        <div className="cadastro__topo">
          <h3>Este aparelho</h3>
          <button type="button" className="botao" onClick={enviarAgora}
            disabled={!online || enviando || porEnviar.length === 0}>
            {enviando ? 'Enviando' : 'Enviar agora'}
          </button>
        </div>
        {!online && porEnviar.length > 0 && (
          <p className="cadastro__apoio">Sem conexão: o envio recomeça sozinho quando a rede voltar.</p>
        )}

        <h4>Por enviar</h4>
        {porEnviar.length ? (
          <ul className="cadastro__lista">
            {porEnviar.map((gesto) => <ItemDaFila key={gesto.operacaoId} gesto={gesto} />)}
          </ul>
        ) : (
          <p className="cadastro__apoio">Nada por enviar: o que foi feito neste aparelho chegou ao servidor.</p>
        )}

        <h4>Em revisão</h4>
        {emRevisao.length ? (
          <ul className="cadastro__lista">
            {emRevisao.map((gesto) => (
              <ItemDaFila key={gesto.operacaoId} gesto={gesto}
                conferir={ocupado ? undefined : () => void conferirNoAparelho(gesto.operacaoId)} />
            ))}
          </ul>
        ) : (
          <p className="cadastro__apoio">Nenhuma revisão por conferir neste aparelho.</p>
        )}

        {conferidos.length > 0 && (
          <details>
            <summary>Conferidos neste aparelho ({conferidos.length})</summary>
            <ul className="cadastro__lista">
              {conferidos.map((gesto) => <ItemDaFila key={gesto.operacaoId} gesto={gesto} />)}
            </ul>
          </details>
        )}
      </div>

      {administrador && (
        <div className="caixa__cartao">
          <h3>Revisões da Conta</h3>
          {!online ? (
            <p className="cadastro__apoio">Sem conexão: a lista da Conta aparece quando a rede voltar.</p>
          ) : (
            <>
              {daConta?.pendentes.length ? (
                <ul className="cadastro__lista">
                  {daConta.pendentes.map((revisao) => (
                    <ItemDaConta key={revisao.operacaoId} revisao={revisao} nome={nome}
                      conferir={ocupado ? undefined : () => void conferirNaConta(revisao.operacaoId)} />
                  ))}
                </ul>
              ) : (
                <p className="cadastro__apoio">Nenhuma revisão da Conta por conferir.</p>
              )}
              <label className="relatorios__formulario">
                Conferidas no dia
                <input type="date" value={dia} onChange={(evento) => setDia(evento.target.value)} />
              </label>
              {daConta?.conferidas.length ? (
                <ul className="cadastro__lista">
                  {daConta.conferidas.map((revisao) => (
                    <ItemDaConta key={revisao.operacaoId} revisao={revisao} nome={nome} />
                  ))}
                </ul>
              ) : (
                <p className="cadastro__apoio">Nenhuma revisão conferida neste dia.</p>
              )}
            </>
          )}
        </div>
      )}
    </section>
  )
}
