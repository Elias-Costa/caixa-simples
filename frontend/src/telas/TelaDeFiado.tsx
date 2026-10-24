import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { intencaoOnline } from '../api/intencaoOnline'
import { fiado, type Divida } from '../api/fiado'
import type { ComprovanteDeRecebimento, FormaPagamento } from '../api/vendas'
import { dataHora, lerDinheiro, moeda } from '../formatos'
import { normalizarNsu } from '../offline/raizDaVenda'
import { criarVendaLocal } from '../offline/vendaLocal'
import { useSessao } from '../sessao/useSessao'
import { CampoDeDinheiro } from './CampoDeDinheiro'
import { erroDeCadastro } from './erroDeCadastro'
import { filtrarPorNome } from './filtroPorNome'
import { Mensagem } from './Mensagem'
import { PixManual } from './QrDoPix'
import { ROTULO_DA_FORMA } from './rotulos'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'

// O recebimento continua só com rede. Ele passa pelo aparelho para que o caixa guardado, usado sem
// rede, não fique com o saldo de antes do dinheiro recebido.
const vendas = criarVendaLocal()

/** Onde o erro aparece: na lista, no formulário do recebimento ou junto do comprovante. */
type LugarDoErro = 'tela' | 'recebimento' | 'comprovante'

function textoDoComprovante(c: ComprovanteDeRecebimento, negocio: string): string {
  return [negocio, 'Comprovante de recebimento não fiscal',
    `Cliente: ${c.nomeCliente}`, `Recebido em: ${dataHora.format(new Date(c.recebidoEm))}`,
    `Forma: ${ROTULO_DA_FORMA[c.forma]}`, `Valor recebido: ${moeda.format(c.valor)}`,
    `Saldo da Venda após recebimento: ${moeda.format(c.saldoApos)}`,
    `Venda: ${c.vendaId}`, `Recebimento: ${c.recebimentoId}`].join('\n')
}

/** As dívidas de um cliente numa linha só: quem deve em dez vendas aparecia dez vezes. */
type Devedor = { clienteId: string; nomeCliente: string; dividas: Divida[]; totalCentavos: number }

function agruparPorCliente(dividas: Divida[]): Devedor[] {
  const devedores = new Map<string, Devedor>()
  for (const divida of dividas) {
    const devedor = devedores.get(divida.clienteId)
      ?? { clienteId: divida.clienteId, nomeCliente: divida.nomeCliente, dividas: [], totalCentavos: 0 }
    devedor.dividas.push(divida)
    // Em centavos, para a soma de valores de duas casas não juntar o erro do ponto flutuante.
    devedor.totalCentavos += Math.round(divida.saldoDevedor * 100)
    devedores.set(divida.clienteId, devedor)
  }
  return [...devedores.values()]
}

export function TelaDeFiado() {
  const { identidade } = useSessao()
  const [sessao, setSessao] = useState<SessaoCaixa>()
  const [dividas, setDividas] = useState<Divida[]>([])
  const [busca, setBusca] = useState('')
  const [devedorAberto, setDevedorAberto] = useState<string | null>(null)
  const [selecionada, setSelecionada] = useState<Divida>()
  const [valor, setValor] = useState('')
  const [forma, setForma] = useState<FormaPagamento>('DINHEIRO')
  const [nsu, setNsu] = useState('')
  const [comprovante, setComprovante] = useState<ComprovanteDeRecebimento>()
  const [erro, setErro] = useState<{ texto: string; lugar: LugarDoErro }>()
  const [ocupado, setOcupado] = useState(false)
  const [carregando, setCarregando] = useState(true)
  const formularioRef = useRef<HTMLFormElement>(null)

  const carregar = useCallback(async () => {
    const [aberta, lista] = await Promise.all([caixa.abertaDoOperadorAtual(), fiado.dividas()])
    setSessao(aberta)
    setDividas(lista)
    setSelecionada((anterior) => lista.find((d) => d.vendaId === anterior?.vendaId))
  }, [])

  useEffect(() => {
    let vivo = true
    Promise.all([caixa.abertaDoOperadorAtual(), fiado.dividas()]).then(([aberta, lista]) => {
      if (vivo) { setSessao(aberta); setDividas(lista) }
    }).catch((falha) => { if (vivo) setErro({ texto: erroDeCadastro(falha), lugar: 'tela' }) })
      .finally(() => { if (vivo) setCarregando(false) })
    return () => { vivo = false }
  }, [])

  // O formulário abre depois da lista inteira: sem rolar até ele, tocar Receber no primeiro devedor
  // de uma lista longa não mudaria nada à vista.
  const vendaSelecionada = selecionada?.vendaId
  useEffect(() => {
    if (!vendaSelecionada) return
    formularioRef.current?.scrollIntoView?.({ block: 'start' })
    formularioRef.current?.querySelector('input')?.focus()
  }, [vendaSelecionada])

  async function receber(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!selecionada || !sessao) return
    // O zero e o valor acima do saldo o servidor também recusa; a tela diz antes, perto do botão.
    const quantia = lerDinheiro(valor)
    if (quantia === null) {
      setErro({ lugar: 'recebimento', texto: 'Informe o valor recebido com até duas casas decimais.' })
      return
    }
    if (quantia <= 0) {
      setErro({ lugar: 'recebimento', texto: 'Informe um valor recebido maior que zero.' })
      return
    }
    if (quantia > selecionada.saldoDevedor) {
      setErro({ lugar: 'recebimento', texto: 'O valor recebido passa do saldo desta Venda.' })
      return
    }
    setOcupado(true); setErro(undefined)
    try {
      // O NSU só vai no cartão, e já sem os espaços das pontas, como o servidor compara o reenvio.
      const nsuDoRecebimento = forma === 'CARTAO' ? nsu : undefined
      const intencao = await intencaoOnline(`/api/vendas/${selecionada.vendaId}/recebimentos`,
        { valor: quantia, forma, nsu: normalizarNsu(nsuDoRecebimento) }, identidade)
      const registrado = await vendas.receber(selecionada.vendaId, quantia, forma, intencao.id,
        nsuDoRecebimento)
      setComprovante(await vendas.comprovanteDeRecebimento(selecionada.vendaId, registrado.id))
      await carregar()
      intencao.confirmar()
      setValor(''); setNsu('')
    } catch (falha) {
      setErro({ lugar: 'recebimento', texto: falha instanceof SemConexao
        ? 'Sem conexão. Tente registrar o mesmo recebimento quando a rede voltar.'
        : erroDeCadastro(falha) })
    }
    finally { setOcupado(false) }
  }

  async function compartilhar() {
    if (!comprovante || !identidade || !navigator.share) return
    try {
      await navigator.share({ title: 'Comprovante de recebimento não fiscal',
        text: textoDoComprovante(comprovante, identidade.nomeNegocio) })
    } catch (falha) {
      if ((falha as Error).name !== 'AbortError') setErro({ texto: erroDeCadastro(falha), lugar: 'comprovante' })
    }
  }

  const erroEm = (lugar: LugarDoErro) => erro?.lugar === lugar ? erro.texto : undefined
  const encontrados = filtrarPorNome(agruparPorCliente(dividas), busca, (devedor) => devedor.nomeCliente)

  // O recebimento continua sendo de uma venda por vez, como a dívida é de cada venda.
  function receberDe(divida: Divida) {
    return sessao && <button type="button" className="botao botao--secundario"
      onClick={() => { setSelecionada(divida); setComprovante(undefined); setValor(''); setNsu(''); setErro(undefined) }}>
      Receber</button>
  }

  return <section className="cadastro">
    <div className="cadastro__topo"><div><TituloComAjuda titulo="Fiado" ajuda={AJUDA.fiado} />
      <p>Quem deve, com o saldo de cada venda. Cada recebimento entra no caixa de quem o registra.</p>
    </div></div>
    <Mensagem tipo="erro" texto={erroEm('tela')} />
    {carregando ? <p>Carregando...</p> : <>
      {!sessao && <p>Abra o seu caixa para registrar recebimentos. <Link to="/caixa">Ir ao caixa</Link></p>}
      {dividas.length === 0 ? <p>Nenhuma Venda com saldo devedor.</p> : <>
        <label className="cadastro__busca">Buscar pelo nome do cliente
          <input type="search" value={busca} onChange={(evento) => setBusca(evento.target.value)} />
        </label>
        {encontrados.length === 0 && <p role="status">Nenhum cliente com saldo encontrado para {busca.trim()}.</p>}
        <ul className="cadastro__lista">{encontrados.map((devedor) => {
          // Com uma venda só, o Receber fica na própria linha, sem um toque a mais para abrir a lista.
          const unica = devedor.dividas.length === 1 ? devedor.dividas[0] : undefined
          const aberto = devedorAberto === devedor.clienteId
          const idDasVendas = `fiado-vendas-${devedor.clienteId}`
          return <li className="cadastro__item" key={devedor.clienteId}>
            <div className="cadastro__dados"><strong>{devedor.nomeCliente}</strong>
              <p>Saldo: {moeda.format(devedor.totalCentavos / 100)}
                {unica ? '' : ` em ${devedor.dividas.length} vendas`}</p>
              {unica && <p>Venda {unica.vendaId.slice(0, 8)} · {dataHora.format(new Date(unica.concluidoEm))}</p>}
            </div>
            {unica ? receberDe(unica) : <>
              <button type="button" className="botao botao--secundario" aria-expanded={aberto}
                aria-controls={idDasVendas} onClick={() => setDevedorAberto(aberto ? null : devedor.clienteId)}>
                {aberto ? 'Esconder vendas' : 'Ver vendas'}</button>
              <ul id={idDasVendas} className="cadastro__lista" hidden={!aberto}>
                {devedor.dividas.map((divida) => <li className="cadastro__item" key={divida.vendaId}>
                  <div><p>Venda {divida.vendaId.slice(0, 8)} · {dataHora.format(new Date(divida.concluidoEm))}</p>
                    <p>Saldo: {moeda.format(divida.saldoDevedor)}</p></div>
                  {receberDe(divida)}
                </li>)}
              </ul>
            </>}
          </li>
        })}</ul>
      </>}
    </>}
    {selecionada && sessao && <form ref={formularioRef} className="cadastro__formulario" onSubmit={(evento) => void receber(evento)}>
      <h3>Receber de {selecionada.nomeCliente}</h3>
      <p>Saldo desta Venda: {moeda.format(selecionada.saldoDevedor)}</p>
      <CampoDeDinheiro rotulo="Valor recebido" required valor={valor} aoMudar={setValor} />
      <label>Forma
        <select value={forma} onChange={(evento) => setForma(evento.target.value as FormaPagamento)}>
          <option value="DINHEIRO">Dinheiro</option><option value="PIX">Pix manual</option>
          <option value="CARTAO">Cartão manual</option>
        </select></label>
      {forma === 'PIX' && <PixManual identidade={identidade} valor={valor ? lerDinheiro(valor) : null} />}
      {forma === 'CARTAO' && <label>
        {identidade?.nsuObrigatorio ? 'NSU do comprovante' : 'NSU do comprovante (opcional)'}
        <input value={nsu} autoComplete="off" required={identidade?.nsuObrigatorio === true}
          onChange={(evento) => setNsu(evento.target.value)} /></label>}
      <Mensagem tipo="erro" texto={erroEm('recebimento')} />
      <div className="cadastro__acoes"><button className="botao" disabled={ocupado}>
        {ocupado ? 'Registrando...' : 'Registrar recebimento'}</button>
        <button type="button" className="botao botao--secundario"
          onClick={() => setSelecionada(undefined)}>Cancelar</button></div>
    </form>}
    {comprovante && identidade && <section className="comprovante" aria-label="Comprovante de recebimento não fiscal">
      <h3>{identidade.nomeNegocio}</h3>
      <p><strong>Comprovante de recebimento não fiscal</strong></p>
      <p>Cliente: {comprovante.nomeCliente}</p>
      <p>Recebido em: {dataHora.format(new Date(comprovante.recebidoEm))}</p>
      <p>Forma: {ROTULO_DA_FORMA[comprovante.forma]}</p>
      <p>Valor recebido: {moeda.format(comprovante.valor)}</p>
      <p>Saldo da Venda após recebimento: {moeda.format(comprovante.saldoApos)}</p>
      <p>Venda: {comprovante.vendaId}</p><p>Recebimento: {comprovante.recebimentoId}</p>
      <div className="comprovante__acoes">
        <button className="botao" type="button" onClick={() => window.print()}>Imprimir</button>
        {typeof navigator.share === 'function' && <button className="botao botao--secundario"
          type="button" onClick={() => void compartilhar()}>Compartilhar</button>}
      </div>
    </section>}
    {/* Fora do papel branco do comprovante, onde o texto claro da mensagem não se leria. */}
    <Mensagem tipo="erro" texto={erroEm('comprovante')} />
  </section>
}
