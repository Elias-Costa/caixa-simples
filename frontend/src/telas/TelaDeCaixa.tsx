import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { contas } from '../api/contas'
import { intencaoOnline } from '../api/intencaoOnline'
import { hojeNoBalcao } from '../dataDoBalcao'
import { dataHora, moeda } from '../formatos'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { useSincronizacao } from '../shell/sincronizacao'
import { useOnline } from '../shell/useOnline'
import { useSessao } from '../sessao/useSessao'
import { erroDeCadastro } from './erroDeCadastro'
import { Mensagem } from './Mensagem'
import { ROTULO_DO_CAIXA, ROTULO_DO_MOVIMENTO } from './rotulos'

const caixa = criarCaixaLocal()

/** O cartão em que a resposta de uma ação aparece: o mesmo em que ela foi tocada. */
type LugarDaMensagem = 'tela' | 'abertura' | 'sessao' | 'movimento' | 'fechamento'
type MensagemNaTela = { tipo: 'erro' | 'sucesso'; texto: string; lugar: LugarDaMensagem }

function dinheiroDigitado(valor: string): number {
  return Number(valor.replace(',', '.'))
}

export function TelaDeCaixa() {
  const { identidade } = useSessao()
  const { rodada } = useSincronizacao()
  const online = useOnline()
  const administrador = identidade?.perfil === 'ADMIN'
  const [aberta, setAberta] = useState<SessaoCaixa | undefined>()
  const [selecionada, setSelecionada] = useState<SessaoCaixa | undefined>()
  const [historico, setHistorico] = useState<SessaoCaixa[]>([])
  const [nomes, setNomes] = useState<Map<string, string>>(() => new Map())
  const [dia, setDia] = useState(hojeNoBalcao)
  const [valorAbertura, setValorAbertura] = useState('0.00')
  const [tipoMovimento, setTipoMovimento] = useState<'SANGRIA' | 'SUPRIMENTO'>('SANGRIA')
  const [valorMovimento, setValorMovimento] = useState('')
  const [motivo, setMotivo] = useState('')
  const [valorContado, setValorContado] = useState('')
  const contado = valorContado.trim() ? dinheiroDigitado(valorContado) : null
  const diferencaPrevista = selecionada && !selecionada.saldoDesatualizado && contado !== null
    && Number.isFinite(contado) && contado >= 0
    ? Math.round((selecionada.valorFechamentoEsperado - contado) * 100) / 100 : null
  const [mensagem, setMensagem] = useState<MensagemNaTela>()
  // Qual ação está em curso: só o botão dela diz que está trabalhando.
  const [ocupado, setOcupado] = useState<'abrir' | 'lancar' | 'fechar' | null>(null)
  const [carregando, setCarregando] = useState(true)

  const carregar = useCallback(async () => {
    try {
      const [sessaoAberta, sessoesDoDia] = await Promise.all([
        caixa.abertaDoOperadorAtual(), caixa.historico(dia),
      ])
      setAberta(sessaoAberta)
      setHistorico(sessoesDoDia)
      setSelecionada((atual) => {
        if (atual) return sessoesDoDia.find((sessao) => sessao.id === atual.id)
          ?? (sessaoAberta?.id === atual.id ? sessaoAberta : atual)
        return sessaoAberta
      })
      // A releitura só apaga o erro da própria leitura: a resposta de uma ação continua à vista.
      setMensagem((atual) => atual?.lugar === 'tela' ? undefined : atual)
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'tela' })
    } finally {
      setCarregando(false)
    }
  }, [dia])

  // A rodada de envio que grava resultado muda o que o servidor sabe da sessão: a tela relê.
  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar, rodada])
  useEffect(() => {
    if (!selecionada || selecionada.movimentos) return
    let ativo = true
    caixa.consultar(selecionada.id).then((detalhes) => {
      if (ativo) setSelecionada(detalhes)
    }).catch((falha) => {
      if (ativo) setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'sessao' })
    })
    return () => { ativo = false }
  }, [selecionada])

  // O administrador vê os caixas de todos; o nome de quem abriu vem da lista de usuários da Conta.
  useEffect(() => {
    if (!administrador || !online) return
    let ativo = true
    contas.usuarios().then((lista) => {
      if (ativo) setNomes(new Map(lista.map((usuario) => [usuario.id, usuario.nome])))
    }).catch(() => { /* fica o começo do id, como nos relatórios */ })
    return () => { ativo = false }
  }, [administrador, online])

  function nomeDoOperador(usuarioId: string): string {
    if (identidade && usuarioId === identidade.usuarioId) return identidade.nome
    return nomes.get(usuarioId) ?? `operador ${usuarioId.slice(0, 8)}`
  }

  function mostrarEm(lugar: LugarDaMensagem) {
    return mensagem?.lugar === lugar ? <Mensagem tipo={mensagem.tipo} texto={mensagem.texto} /> : null
  }

  async function abrir(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    setOcupado('abrir')
    setMensagem(undefined)
    try {
      await caixa.abrir(dinheiroDigitado(valorAbertura))
      await carregar()
      setMensagem({ tipo: 'sucesso', texto: 'Caixa aberto.', lugar: 'abertura' })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'abertura' })
    } finally {
      setOcupado(null)
    }
  }

  async function lancar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!selecionada) return
    const tipo = tipoMovimento
    setOcupado('lancar')
    setMensagem(undefined)
    try {
      const valor = dinheiroDigitado(valorMovimento)
      const rota = `/api/caixa/sessoes/${selecionada.id}/`
        + (tipo === 'SANGRIA' ? 'sangrias' : 'suprimentos')
      const intencao = await intencaoOnline(rota, { valor, motivo: motivo.trim() }, identidade)
      if (tipo === 'SANGRIA') await caixa.sangrar(selecionada.id, valor, motivo, intencao.id)
      else await caixa.suprir(selecionada.id, valor, motivo, intencao.id)
      await carregar()
      setSelecionada(await caixa.consultar(selecionada.id))
      intencao.confirmar()
      setValorMovimento('')
      setMotivo('')
      setMensagem({ tipo: 'sucesso', lugar: 'movimento',
        texto: tipo === 'SANGRIA' ? 'Sangria registrada.' : 'Suprimento registrado.' })
    } catch (falha) {
      setMensagem({ tipo: 'erro', lugar: 'movimento', texto: falha instanceof SemConexao
        ? 'Sem conexão. Tente registrar o mesmo movimento quando a rede voltar.'
        : erroDeCadastro(falha) })
    } finally {
      setOcupado(null)
    }
  }

  async function fechar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!selecionada) return
    setOcupado('fechar')
    setMensagem(undefined)
    try {
      await caixa.fechar(selecionada.id, dinheiroDigitado(valorContado))
      setValorContado('')
      await carregar()
      setSelecionada(await caixa.consultar(selecionada.id))
      // Os formulários somem com o caixa fechado; o aviso vai para o alto do cartão da sessão.
      setMensagem({ tipo: 'sucesso', texto: 'Caixa fechado.', lugar: 'sessao' })
    } catch (falha) {
      setMensagem({ tipo: 'erro', texto: erroDeCadastro(falha), lugar: 'fechamento' })
    } finally {
      setOcupado(null)
    }
  }

  return <section className="caixa">
    <h2 className="titulo">Caixa</h2>
    {mostrarEm('tela')}
    {carregando ? <p>Carregando...</p> : <>
      <section className="caixa__cartao">
        <h3>Meu caixa</h3>
        {aberta ? <>
          <p>Aberto em {dataHora.format(new Date(aberta.abertaEm))}</p>
          <p>Valor inicial: <strong>{moeda.format(aberta.valorAbertura)}</strong></p>
          {aberta.pendenteSincronizacao && <p>Pendente de sincronização com o servidor.</p>}
          <button className="botao botao--secundario" type="button"
            onClick={() => setSelecionada(aberta)}>Ver meu caixa</button>
        </> : <form onSubmit={(evento) => void abrir(evento)}>
          <p>Nenhum caixa aberto para você.</p>
          <label>Valor inicial
            <input type="number" min="0" step="0.01" required value={valorAbertura}
              onChange={(evento) => setValorAbertura(evento.target.value)} />
          </label>
          <button className="botao" disabled={ocupado !== null}>
            {ocupado === 'abrir' ? 'Abrindo...' : 'Abrir caixa'}
          </button>
        </form>}
        {mostrarEm('abertura')}
      </section>

      <section className="caixa__cartao">
        <h3>Histórico do dia</h3>
        <label>Dia
          <input type="date" value={dia} onChange={(evento) => setDia(evento.target.value)} />
        </label>
        <p>{!online ? 'Sessões deste operador guardadas no dispositivo.'
          : administrador ? 'Sessões de todos os operadores.' : 'Suas sessões.'}</p>
        {historico.length === 0 ? <p>Nenhuma sessão neste dia.</p> : <ul className="caixa__lista">
          {historico.map((sessao) => <li key={sessao.id}>
            <button className="botao botao--secundario" type="button"
              onClick={() => setSelecionada(sessao)}>
              {dataHora.format(new Date(sessao.abertaEm))} · {ROTULO_DO_CAIXA[sessao.status]} · {moeda.format(sessao.valorAbertura)}
              {administrador && ` · ${nomeDoOperador(sessao.usuarioId)}`}
            </button>
          </li>)}
        </ul>}
      </section>

      {selecionada && <section className="caixa__cartao">
        <h3>Sessão selecionada</h3>
        {mostrarEm('sessao')}
        <p>Situação: <strong>{ROTULO_DO_CAIXA[selecionada.status]}</strong></p>
        {selecionada.pendenteSincronizacao && <p>Pendente de sincronização com o servidor.</p>}
        <p>Abertura: {dataHora.format(new Date(selecionada.abertaEm))}</p>
        <p>Valor inicial: {moeda.format(selecionada.valorAbertura)}</p>
        <p>Saldo esperado: <strong>{selecionada.saldoDesatualizado ? 'indisponível até a próxima leitura do caixa'
          : moeda.format(selecionada.valorFechamentoEsperado)}</strong></p>
        {selecionada.saldoDesatualizado && <p>Este caixa mudou com rede depois da última leitura feita neste
          aparelho. Saldo, diferença e extrato atualizados aparecem quando ele for lido de novo; até lá, a
          sangria sem rede espera.</p>}
        {selecionada.status === 'FECHADA' && (selecionada.fechamentoSemValores
          ? <p>Fechada no servidor; valor contado e diferença aparecem quando a rede voltar.</p>
          : <>
            <p>Valor contado: {moeda.format(selecionada.valorFechamentoContado ?? 0)}</p>
            {selecionada.saldoDesatualizado ? <p>Diferença: aparece quando o caixa for lido de novo.</p>
              : <p>Diferença: {moeda.format(selecionada.diferenca ?? 0)}
                {' '}(positivo = falta, negativo = sobra)</p>}
          </>)}

        {/* As ações vêm antes do extrato: cada venda acrescenta uma linha a ele, e o fechamento, no
            fim do dia, ficaria depois de todas. */}
        {selecionada.status === 'ABERTA' && <form className="caixa__formulario"
          onSubmit={(evento) => void lancar(evento)}>
          <h4>Lançar movimento</h4>
          <label>Tipo
            <select value={tipoMovimento}
              onChange={(evento) => setTipoMovimento(evento.target.value as 'SANGRIA' | 'SUPRIMENTO')}>
              <option value="SANGRIA">Sangria</option>
              <option value="SUPRIMENTO">Suprimento</option>
            </select>
          </label>
          <label>Valor
            <input type="number" min="0" step="0.01" required value={valorMovimento}
              onChange={(evento) => setValorMovimento(evento.target.value)} />
          </label>
          <label>Motivo
            <input required value={motivo} onChange={(evento) => setMotivo(evento.target.value)} />
          </label>
          <button className="botao" disabled={ocupado !== null}>
            {ocupado === 'lancar' ? 'Registrando...' : `Registrar ${tipoMovimento.toLowerCase()}`}
          </button>
          {mostrarEm('movimento')}
        </form>}
        {selecionada.status === 'ABERTA' && <form className="caixa__formulario"
          onSubmit={(evento) => void fechar(evento)}>
          <h4>Fechar caixa</h4>
          <p>Confira o dinheiro da gaveta contra o saldo esperado acima.</p>
          <label>Valor contado
            <input type="number" min="0" step="0.01" required value={valorContado}
              onChange={(evento) => setValorContado(evento.target.value)} />
          </label>
          {diferencaPrevista !== null && <p>Diferença prevista: {moeda.format(diferencaPrevista)}
            {' '}(positivo = falta, negativo = sobra)</p>}
          <button className="botao" disabled={ocupado !== null}>
            {ocupado === 'fechar' ? 'Fechando...' : 'Fechar e registrar diferença'}
          </button>
          {mostrarEm('fechamento')}
        </form>}

        <details className="caixa__extrato" open>
          <summary>Movimentos{selecionada.movimentos ? ` (${selecionada.movimentos.length})` : ''}</summary>
          {!selecionada.movimentos ? <p>Carregando extrato...</p>
            : selecionada.movimentos.length === 0 ? <p>Nenhum movimento nesta sessão.</p>
              : <ul className="caixa__movimentos">
                {selecionada.movimentos.map((movimento) => <li key={movimento.id}>
                  <strong>{ROTULO_DO_MOVIMENTO[movimento.tipo]}</strong> · {moeda.format(movimento.valor)}
                  {' · '}{dataHora.format(new Date(movimento.criadoEm))}
                  {movimento.motivo && <span> · {movimento.motivo}</span>}
                  {movimento.vendaId && <span> · Venda {movimento.vendaId.slice(0, 8)}</span>}
                </li>)}
              </ul>}
        </details>
      </section>}
    </>}
  </section>
}
