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
import { ROTULO_DA_FORMA } from './rotulos'

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

export function TelaDeFiado() {
  const { identidade } = useSessao()
  const [sessao, setSessao] = useState<SessaoCaixa>()
  const [dividas, setDividas] = useState<Divida[]>([])
  const [busca, setBusca] = useState('')
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
  const encontradas = filtrarPorNome(dividas, busca, (divida) => divida.nomeCliente)

  return <section className="cadastro">
    <div className="cadastro__topo"><div><h2 className="titulo">Fiado</h2>
      <p>Vendas com saldo devedor. Cada recebimento entra no caixa de quem o registra.</p>
    </div></div>
    <Mensagem tipo="erro" texto={erroEm('tela')} />
    {carregando ? <p>Carregando...</p> : <>
      {!sessao && <p>Abra o seu caixa para registrar recebimentos. <Link to="/caixa">Ir ao caixa</Link></p>}
      {dividas.length === 0 ? <p>Nenhuma Venda com saldo devedor.</p> : <>
        <label className="cadastro__busca">Buscar pelo nome do cliente
          <input type="search" value={busca} onChange={(evento) => setBusca(evento.target.value)} />
        </label>
        {encontradas.length === 0 && <p role="status">Nenhuma Venda encontrada para {busca.trim()}.</p>}
        <ul className="cadastro__lista">{encontradas.map((divida) => <li className="cadastro__item" key={divida.vendaId}>
          <div><strong>{divida.nomeCliente}</strong>
            <p>Venda {divida.vendaId.slice(0, 8)} · {dataHora.format(new Date(divida.concluidoEm))}</p>
            <p>Saldo: {moeda.format(divida.saldoDevedor)}</p></div>
          {sessao && <button type="button" className="botao botao--secundario"
            onClick={() => { setSelecionada(divida); setComprovante(undefined); setValor(''); setNsu(''); setErro(undefined) }}>
            Receber</button>}
        </li>)}</ul>
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
