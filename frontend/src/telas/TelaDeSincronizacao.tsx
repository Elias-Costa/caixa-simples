import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { cadastro } from '../api/cadastro'
import { SemConexao } from '../api/cliente'
import { contas } from '../api/contas'
import { sincronizacao, type RevisaoDaConta, type RevisoesDaConta } from '../api/sincronizacao'
import { hojeNoBalcao } from '../dataDoBalcao'
import { dataHora } from '../formatos'
import { aoMudarFila, conferirGestos, gestosDosOutrosUsuarios, listarGestos, type GestoNaFila } from '../offline/fila'
import {
  contarPorUsuario, registrosDaConta, registrosDaFila, type ContagemDosUsuarios, type RegistroDaFila,
} from '../offline/registrosDaFila'
import { useSincronizacao } from '../shell/sincronizacao'
import { useConexao } from '../shell/useConexao'
import { useSessao } from '../sessao/useSessao'
import { nomesDosClientesNoAparelho } from './clientesNoAparelho'
import { erroDeCadastro } from './erroDeCadastro'
import { Mensagem } from './Mensagem'
import {
  conteudoDoPasso, linhasDoRegistro, quandoConferido, rotulo, situacaoDoPasso, tituloDoRegistro, type NomesDoCadastro,
} from './textosDoRegistro'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'

function semNome({ payload }: GestoNaFila): boolean {
  return !payload || typeof payload !== 'object' || Array.isArray(payload) || typeof payload.nome !== 'string'
}

/** Sem o produto no cadastro deste aparelho, a linha só não diz o nome. */
async function nomesDosProdutosNoAparelho(): Promise<NomesDoCadastro['produto']> {
  try {
    const [ativos, inativos] = await Promise.all([cadastro.produtos(), cadastro.produtosInativos()])
    const nomes = new Map([...ativos, ...inativos].map((produto) => [produto.id, produto.nome]))
    return (produtoId) => nomes.get(produtoId)
  } catch {
    return () => undefined
  }
}

/**
 * O nome do cliente e do produto de quem o gesto só guarda o id, como a venda com cliente e a
 * inativação. O cadastro só é lido quando algum gesto pede, e relido a cada rodada de envio.
 */
function useNomesDoCadastro(gestos: readonly GestoNaFila[], rodada: number): NomesDoCadastro {
  const precisaDeCliente = gestos.some((gesto) => gesto.tipo === 'venda.vincularCliente'
    || (gesto.tipo.startsWith('cliente.') && semNome(gesto)))
  const precisaDeProduto = gestos.some((gesto) => gesto.tipo.startsWith('produto.') && semNome(gesto))
  const [nomes, setNomes] = useState<NomesDoCadastro>({ cliente: () => undefined, produto: () => undefined })

  useEffect(() => {
    if (!precisaDeCliente) return
    queueMicrotask(() => void nomesDosClientesNoAparelho().then((cliente) =>
      setNomes((anteriores) => ({ ...anteriores, cliente }))))
  }, [precisaDeCliente, rodada])
  useEffect(() => {
    if (!precisaDeProduto) return
    queueMicrotask(() => void nomesDosProdutosNoAparelho().then((produto) =>
      setNomes((anteriores) => ({ ...anteriores, produto }))))
  }, [precisaDeProduto, rodada])

  return nomes
}

function Conteudo({ gesto, nomes }: { gesto: GestoNaFila; nomes: NomesDoCadastro }) {
  const conteudo = conteudoDoPasso(gesto.tipo, gesto.payload, nomes)
  return conteudo ? <p>{conteudo}</p> : null
}

function PassoDaFila({ gesto, nomes }: { gesto: GestoNaFila; nomes: NomesDoCadastro }) {
  return (
    <li>
      <strong>{rotulo(gesto.tipo)}</strong>
      <Conteudo gesto={gesto} nomes={nomes} />
      <p className="cadastro__apoio">
        {dataHora.format(new Date(gesto.criadoEm))} · {situacaoDoPasso(gesto)}{quandoConferido(gesto)}
      </p>
    </li>
  )
}

function PassoDaConta({ gesto, revisao, nomes, nome }: {
  gesto: GestoNaFila; revisao: RevisaoDaConta; nomes: NomesDoCadastro; nome: (id?: string) => string
}) {
  const desfecho = revisao.resultado === 'NAO_APLICADA' ? 'Não aplicado' : 'Aplicado, com revisão'
  return (
    <li>
      <strong>{rotulo(gesto.tipo)}</strong>
      <Conteudo gesto={gesto} nomes={nomes} />
      <p className="cadastro__apoio">
        {desfecho}: {revisao.detalhe ?? 'sem detalhe do servidor'} · feito em{' '}
        {dataHora.format(new Date(revisao.criadaEm))}, recebido em {dataHora.format(new Date(revisao.recebidaEm))}
      </p>
      {revisao.conferidaEm && (
        <p className="cadastro__apoio">
          Conferido por {nome(revisao.conferidaPor)} em {dataHora.format(new Date(revisao.conferidaEm))}
        </p>
      )}
    </li>
  )
}

type Conferencia = {
  conferir: () => void
  /** A conferência em curso é a desta linha. */
  conferindo: boolean
  /** Alguma conferência está em curso, nesta linha ou em outra. */
  desligado: boolean
  erro?: string
}

/**
 * Uma linha por registro. Com mais de um gesto, a própria linha abre os passos; o registro de um gesto
 * só já disse tudo. O nome do botão leva o registro, porque quem usa leitor de tela ouve um Conferir
 * depois do outro.
 */
function Linha({ titulo, autor, linhas, passos, quantosPassos, conferencia }: {
  titulo: string; autor?: string; linhas: readonly string[]; passos: ReactNode; quantosPassos: number
  conferencia?: Conferencia
}) {
  const resumo = <>
    <strong>{titulo}</strong>{autor && `, por ${autor}`}
    {linhas.map((linha) => <span key={linha} className="registro__linha">{linha}</span>)}
  </>
  return (
    <li className="cadastro__item">
      {quantosPassos > 1 ? (
        <details className="registro">
          <summary className="registro__resumo">{resumo}</summary>
          <ul className="registro__passos">{passos}</ul>
        </details>
      ) : <div className="registro registro--sem-passos">{resumo}</div>}
      {conferencia && (
        <button type="button" className="botao botao--secundario" onClick={conferencia.conferir}
          disabled={conferencia.desligado} aria-label={conferencia.conferindo ? undefined : `Conferir ${titulo}`}>
          {conferencia.conferindo ? 'Conferindo' : 'Conferir'}
        </button>
      )}
      <Mensagem tipo="erro" texto={conferencia?.erro} />
    </li>
  )
}

function LinhaDaFila({ registro, hoje, nomes, conferencia }: {
  registro: RegistroDaFila; hoje: string; nomes: NomesDoCadastro; conferencia?: Conferencia
}) {
  const linhas = linhasDoRegistro(registro, nomes)
  const conferido = registro.gestos.find((gesto) => gesto.conferidoEm)
  return (
    <Linha titulo={tituloDoRegistro(registro, hoje, nomes)} conferencia={conferencia} quantosPassos={registro.gestos.length}
      linhas={conferido ? [...linhas.slice(0, -1), `${linhas.at(-1)}${quandoConferido(conferido)}`] : linhas}
      passos={registro.gestos.map((gesto) => <PassoDaFila key={gesto.operacaoId} gesto={gesto} nomes={nomes} />)} />
  )
}

function LinhaDaConta({ registro, revisoes, hoje, nomes, nome, conferencia }: {
  registro: RegistroDaFila; revisoes: ReadonlyMap<string, RevisaoDaConta>; hoje: string; nomes: NomesDoCadastro
  nome: (id?: string) => string; conferencia?: Conferencia
}) {
  const daLinha = registro.gestos.map((gesto) => revisoes.get(gesto.operacaoId) as RevisaoDaConta)
  const linhas = linhasDoRegistro(registro, nomes)
  const recebido = dataHora.format(new Date(daLinha[0].recebidaEm))
  const comRecebimento = [...linhas.slice(0, -1), `${linhas.at(-1)} · recebido em ${recebido}`]
  const conferidas = daLinha.filter((revisao) => revisao.conferidaEm)
  const faltam = daLinha.length - conferidas.length
  const ultima = conferidas.at(-1)
  return (
    <Linha titulo={tituloDoRegistro(registro, hoje, nomes)} autor={nome(daLinha[0].usuarioId)}
      conferencia={conferencia} quantosPassos={registro.gestos.length}
      linhas={[
        ...comRecebimento,
        ...(conferidas.length > 0 && faltam > 0
          ? [`${faltam === 1 ? 'Falta' : 'Faltam'} ${faltam} de ${daLinha.length} passos por conferir.`] : []),
        ...(faltam === 0 && ultima?.conferidaEm
          ? [`Conferido por ${nome(ultima.conferidaPor)} em ${dataHora.format(new Date(ultima.conferidaEm))}`] : []),
      ]}
      passos={registro.gestos.map((gesto, indice) => (
        <PassoDaConta key={gesto.operacaoId} gesto={gesto} revisao={daLinha[indice]} nomes={nomes} nome={nome} />
      ))} />
  )
}

/** Onde o foco fica depois de uma conferência: no botão desta posição da lista, ou no título, se ela acabou. */
type FocoDepois = {
  lista: RefObject<HTMLUListElement | null>
  titulo: RefObject<HTMLHeadingElement | null>
  indice: number
}

/**
 * A fila de outra pessoa só sai quando ela entra, e o aparelho não guarda o nome de quem saiu. Sem esta
 * frase, a venda do turno anterior ficaria parada aqui sem ninguém saber, nem o administrador, porque o
 * servidor não soube dela.
 */
function fraseDosOutros({ porEnviar, usuariosComPorEnviar }: ContagemDosUsuarios): string {
  if (usuariosComPorEnviar > 1) {
    return `Há ${porEnviar} registros de ${usuariosComPorEnviar} outros usuários neste aparelho. Cada um só é `
      + 'enviado quando a pessoa que o fez entrar.'
  }
  return porEnviar === 1
    ? 'Há 1 registro de outro usuário neste aparelho. Ele só é enviado quando essa pessoa entrar.'
    : `Há ${porEnviar} registros de outro usuário neste aparelho. Eles só são enviados quando essa pessoa entrar.`
}

/** A conferência parou no meio: a linha continua, e repetir não dá erro, porque o servidor mantém a primeira. */
function falhaNaConta(falha: unknown, conferidos: number, total: number): string {
  const motivo = falha instanceof SemConexao ? 'Sem resposta do servidor.' : erroDeCadastro(falha)
  const quantos = conferidos === 0 ? ''
    : ` ${conferidos === 1 ? 'Foi conferido' : 'Foram conferidos'} ${conferidos} de ${total} passos.`
  return `${motivo}${quantos} Toque em Conferir de novo.`
}

/**
 * O que saiu deste aparelho e o que o servidor disse de cada registro (RNF02), uma linha por venda,
 * movimento de caixa, cliente ou produto, com os gestos recolhidos. O agrupamento é só da tela: o
 * servidor continua gravando e conferindo por operação. Quem opera vê aqui o que ainda não foi, o que
 * falhou e será repetido, e o que foi revisado ou recusado, e confere no aparelho, porque não tem a
 * lista da Conta. O administrador vê aqui só o que falta enviar: toda revisão, de qualquer aparelho, fica
 * na lista da Conta, e a conferência dele fica gravada no servidor, com quem e quando. O que outro usuário
 * deixou por enviar neste aparelho aparece só contado, para os dois perfis: a fila é de quem a fez.
 */
export function TelaDeSincronizacao() {
  const { identidade } = useSessao()
  const { rodada, enviando, enviarAgora, atualizar } = useSincronizacao()
  const conexao = useConexao()
  // Enviar agora vale também sem resposta do servidor: é uma tentativa a mais, e não custa nada.
  const online = conexao !== 'sem-rede'
  const administrador = identidade?.perfil === 'ADMIN'
  const usuarioId = identidade?.usuarioId ?? ''
  const [gestos, setGestos] = useState<GestoNaFila[]>([])
  const [dosOutros, setDosOutros] = useState<ContagemDosUsuarios | null>(null)
  const [dia, setDia] = useState(hojeNoBalcao)
  const [daConta, setDaConta] = useState<RevisoesDaConta>()
  const [usuarios, setUsuarios] = useState<Map<string, string>>(() => new Map())
  const [erro, setErro] = useState<string | null>(null)
  const [conferindo, setConferindo] = useState<string | null>(null)
  const [falhaDaLinha, setFalhaDaLinha] = useState<{ chave: string; texto: string; semResposta: boolean } | null>(null)
  const listaDoAparelho = useRef<HTMLUListElement>(null)
  const tituloDoAparelho = useRef<HTMLHeadingElement>(null)
  const listaDaConta = useRef<HTMLUListElement>(null)
  const tituloDaConta = useRef<HTMLHeadingElement>(null)
  const focoDepois = useRef<FocoDepois | null>(null)

  const lerAparelho = useCallback(async () => {
    try {
      const [doUsuario, deOutros] = await Promise.all([listarGestos(), gestosDosOutrosUsuarios()])
      setGestos(doUsuario)
      setDosOutros(contarPorUsuario(deOutros))
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }, [])

  const lerDaConta = useCallback(async () => {
    if (!administrador || conexao !== 'com-servidor') return
    try {
      const [revisoes, daEquipe] = await Promise.all([sincronizacao.revisoes(dia), contas.usuarios()])
      setDaConta(revisoes)
      setUsuarios(new Map(daEquipe.map((usuario) => [usuario.id, usuario.nome])))
    } catch (falha) {
      // Sem resposta, a tela já diz que a lista espera o servidor; a frase da escrita sem resposta enganaria.
      if (!(falha instanceof SemConexao)) setErro(erroDeCadastro(falha))
    }
  }, [administrador, conexao, dia])

  useEffect(() => { queueMicrotask(() => void lerAparelho()) }, [lerAparelho, rodada])
  useEffect(() => aoMudarFila(() => { void lerAparelho() }), [lerAparelho])
  useEffect(() => { queueMicrotask(() => void lerDaConta()) }, [lerDaConta, rodada])

  // Sem isto, o botão some sob o dedo e o foco cai no corpo da página: com 20 revisões, quem usa teclado
  // recomeçaria do alto. A espera é pelo fim da conferência, porque botão desligado não recebe foco.
  useEffect(() => {
    const pedido = focoDepois.current
    if (!pedido || conferindo) return
    focoDepois.current = null
    const botoes = pedido.lista.current?.querySelectorAll<HTMLButtonElement>('li > button') ?? []
    const alvo = botoes[Math.min(pedido.indice, botoes.length - 1)]
    if (alvo) alvo.focus()
    else pedido.titulo.current?.focus()
  })

  async function conferirNoAparelho(registro: RegistroDaFila, indice: number) {
    setConferindo(registro.chave)
    setFalhaDaLinha(null)
    setErro(null)
    try {
      await conferirGestos(registro.gestos.map((gesto) => gesto.operacaoId))
      await lerAparelho()
      atualizar()
    } catch (falha) {
      setFalhaDaLinha({ chave: registro.chave, texto: erroDeCadastro(falha), semResposta: false })
    }
    focoDepois.current = { lista: listaDoAparelho, titulo: tituloDoAparelho, indice }
    setConferindo(null)
  }

  /** Uma chamada por operação, pela rota que já existe: o servidor grava e confere uma de cada vez. */
  async function conferirNaConta(registro: RegistroDaFila, indice: number) {
    const faltam = registro.gestos.filter((gesto) => !gesto.conferidoEm).map((gesto) => gesto.operacaoId)
    const feitas: string[] = []
    setConferindo(registro.chave)
    setFalhaDaLinha(null)
    setErro(null)
    try {
      for (const operacaoId of faltam) {
        await sincronizacao.conferir(operacaoId)
        feitas.push(operacaoId)
      }
    } catch (falha) {
      const conferidos = registro.gestos.length - faltam.length + feitas.length
      setFalhaDaLinha({ chave: registro.chave, texto: falhaNaConta(falha, conferidos, registro.gestos.length),
        semResposta: falha instanceof SemConexao })
    }
    try {
      // O que saiu deste aparelho e o servidor já conferiu sai também do aviso daqui.
      await conferirGestos(feitas)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
    await lerDaConta()
    atualizar()
    focoDepois.current = { lista: listaDaConta, titulo: tituloDaConta, indice }
    setConferindo(null)
  }

  /** O estado do botão de uma linha; a mensagem de sem resposta sai sozinha quando o servidor volta. */
  function estadoDaConferencia(registro: RegistroDaFila): Omit<Conferencia, 'conferir'> {
    const falha = falhaDaLinha?.chave === registro.chave
      && (!falhaDaLinha.semResposta || conexao !== 'com-servidor') ? falhaDaLinha.texto : undefined
    return { conferindo: conferindo === registro.chave, desligado: conferindo !== null, erro: falha }
  }

  const nomeDoUsuario = (id?: string) => (id && usuarios.get(id)) || 'usuário não identificado'
  const hoje = hojeNoBalcao()
  const registros = useMemo(() => registrosDaFila(gestos, usuarioId), [gestos, usuarioId])
  const porEnviar = registros.filter((registro) => registro.situacao === 'por-enviar')
  const emRevisao = registros.filter((registro) => registro.situacao === 'em-revisao')
  const conferidos = registros.filter((registro) => registro.situacao === 'conferido')
  const registrosDeConta = useMemo(() => daConta ? registrosDaConta(daConta, usuarioId) : [], [daConta, usuarioId])
  const pendentesDaConta = registrosDeConta.filter((registro) => registro.situacao === 'em-revisao')
  const conferidasDaConta = registrosDeConta.filter((registro) => registro.situacao === 'conferido')
  const revisoesDaConta = useMemo(() => new Map([...(daConta?.pendentes ?? []), ...(daConta?.conferidas ?? [])]
    .map((revisao) => [revisao.operacaoId, revisao])), [daConta])
  const todosOsGestos = useMemo(() => [...gestos, ...registrosDeConta.flatMap((registro) => registro.gestos)],
    [gestos, registrosDeConta])
  const nomes = useNomesDoCadastro(todosOsGestos, rodada)

  return (
    <section className="caixa">
      <TituloComAjuda titulo="Sincronização" ajuda={AJUDA.sincronizacao} />
      {erro && <p className="mensagem-erro" role="alert">{erro}</p>}

      <div className="caixa__cartao">
        <div className="cadastro__topo">
          <h3>Este aparelho</h3>
          <button type="button" className="botao" onClick={enviarAgora}
            disabled={!online || enviando || porEnviar.length === 0}>
            {enviando ? 'Enviando' : 'Enviar agora'}
          </button>
        </div>
        {conexao !== 'com-servidor' && porEnviar.length > 0 && (
          <p className="cadastro__apoio">
            {conexao === 'sem-rede' ? 'Sem conexão: o envio recomeça sozinho quando a rede voltar.'
              : 'Sem resposta do servidor: o envio recomeça sozinho quando ele responder.'}
          </p>
        )}

        <h4>Por enviar</h4>
        {porEnviar.length ? (
          <ul className="cadastro__lista">
            {porEnviar.map((registro) => (
              <LinhaDaFila key={registro.chave} registro={registro} hoje={hoje} nomes={nomes} />
            ))}
          </ul>
        ) : (
          <p className="cadastro__apoio">Nada por enviar: o que você registrou neste aparelho chegou ao servidor.</p>
        )}
        {dosOutros && dosOutros.porEnviar > 0 && <p className="mensagem-info">{fraseDosOutros(dosOutros)}</p>}

        {!administrador && <>
          <h4 ref={tituloDoAparelho} tabIndex={-1}>Em revisão</h4>
          {emRevisao.length ? (
            <ul ref={listaDoAparelho} className="cadastro__lista">
              {emRevisao.map((registro, indice) => (
                <LinhaDaFila key={registro.chave} registro={registro} hoje={hoje} nomes={nomes}
                  conferencia={{ ...estadoDaConferencia(registro), conferir: () => void conferirNoAparelho(registro, indice) }} />
              ))}
            </ul>
          ) : (
            <p className="cadastro__apoio">Nenhuma revisão por conferir neste aparelho.</p>
          )}

          {conferidos.length > 0 && (
            <details>
              <summary>Conferidos neste aparelho ({conferidos.length})</summary>
              <ul className="cadastro__lista">
                {conferidos.map((registro) => (
                  <LinhaDaFila key={registro.chave} registro={registro} hoje={hoje} nomes={nomes} />
                ))}
              </ul>
            </details>
          )}
        </>}
      </div>

      {administrador && (
        <div className="caixa__cartao">
          <h3 ref={tituloDaConta} tabIndex={-1}>Revisões da Conta</h3>
          {conexao === 'sem-rede' ? (
            <p className="cadastro__apoio">Sem conexão: a lista da Conta aparece quando a rede voltar.</p>
          ) : conexao === 'sem-servidor' && !daConta ? (
            <p className="cadastro__apoio">Sem resposta do servidor: a lista da Conta aparece quando ele responder.</p>
          ) : daConta && (
            <>
              {pendentesDaConta.length ? (
                <ul ref={listaDaConta} className="cadastro__lista">
                  {pendentesDaConta.map((registro, indice) => (
                    <LinhaDaConta key={registro.chave} registro={registro} revisoes={revisoesDaConta} hoje={hoje}
                      nomes={nomes} nome={nomeDoUsuario}
                      conferencia={{ ...estadoDaConferencia(registro), conferir: () => void conferirNaConta(registro, indice) }} />
                  ))}
                </ul>
              ) : (
                <p className="cadastro__apoio">Nenhuma revisão da Conta por conferir.</p>
              )}
              <label className="relatorios__formulario">
                Conferidas no dia
                <input type="date" value={dia} onChange={(evento) => setDia(evento.target.value)} />
              </label>
              {conferidasDaConta.length ? (
                <ul className="cadastro__lista">
                  {conferidasDaConta.map((registro) => (
                    <LinhaDaConta key={registro.chave} registro={registro} revisoes={revisoesDaConta} hoje={hoje}
                      nomes={nomes} nome={nomeDoUsuario} />
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
