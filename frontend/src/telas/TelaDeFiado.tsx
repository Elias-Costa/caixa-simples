import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { fiado, type Divida } from '../api/fiado'
import { vendas, type ComprovanteDeRecebimento, type FormaPagamento } from '../api/vendas'
import { useSessao } from '../sessao/useSessao'
import { erroDeCadastro } from './erroDeCadastro'

const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
const dataHora = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

function textoDoComprovante(c: ComprovanteDeRecebimento, negocio: string): string {
  return [negocio, 'Comprovante de recebimento não fiscal',
    `Cliente: ${c.nomeCliente}`, `Recebido em: ${dataHora.format(new Date(c.recebidoEm))}`,
    `Forma: ${c.forma}`, `Valor recebido: ${moeda.format(c.valor)}`,
    `Saldo da Venda após recebimento: ${moeda.format(c.saldoApos)}`,
    `Venda: ${c.vendaId}`, `Recebimento: ${c.recebimentoId}`].join('\n')
}

export function TelaDeFiado() {
  const { identidade } = useSessao()
  const [sessao, setSessao] = useState<SessaoCaixa>()
  const [dividas, setDividas] = useState<Divida[]>([])
  const [selecionada, setSelecionada] = useState<Divida>()
  const [valor, setValor] = useState('')
  const [forma, setForma] = useState<FormaPagamento>('DINHEIRO')
  const [comprovante, setComprovante] = useState<ComprovanteDeRecebimento>()
  const [erro, setErro] = useState<string>()
  const [ocupado, setOcupado] = useState(false)
  const [carregando, setCarregando] = useState(true)

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
    }).catch((falha) => { if (vivo) setErro(erroDeCadastro(falha)) })
      .finally(() => { if (vivo) setCarregando(false) })
    return () => { vivo = false }
  }, [])

  async function receber(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (!selecionada || !sessao) return
    setOcupado(true); setErro(undefined)
    try {
      const quantia = Number(valor.replace(',', '.'))
      const registrado = await vendas.receber(selecionada.vendaId, quantia, forma)
      setComprovante(await vendas.comprovanteDeRecebimento(selecionada.vendaId, registrado.id))
      setValor('')
      await carregar()
    } catch (falha) { setErro(erroDeCadastro(falha)) }
    finally { setOcupado(false) }
  }

  async function compartilhar() {
    if (!comprovante || !identidade || !navigator.share) return
    try {
      await navigator.share({ title: 'Comprovante de recebimento não fiscal',
        text: textoDoComprovante(comprovante, identidade.nomeNegocio) })
    } catch (falha) {
      if ((falha as Error).name !== 'AbortError') setErro(erroDeCadastro(falha))
    }
  }

  return <section className="cadastro">
    <div className="cadastro__topo"><div><h2 className="titulo">Fiado</h2>
      <p>Vendas com saldo devedor. Cada recebimento pertence à SessaoCaixa de quem o registra.</p>
    </div></div>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {carregando ? <p>Carregando...</p> : <>
      {!sessao && <p>Abra sua SessaoCaixa para registrar recebimentos. <Link to="/caixa">Ir ao caixa</Link></p>}
      {dividas.length === 0 ? <p>Nenhuma Venda com saldo devedor.</p> :
        <ul className="cadastro__lista">{dividas.map((divida) => <li className="cadastro__item" key={divida.vendaId}>
          <div><strong>{divida.nomeCliente}</strong>
            <p>Venda {divida.vendaId.slice(0, 8)} · {dataHora.format(new Date(divida.concluidoEm))}</p>
            <p>Saldo: {moeda.format(divida.saldoDevedor)}</p></div>
          {sessao && <button type="button" className="botao botao--secundario"
            onClick={() => { setSelecionada(divida); setComprovante(undefined); setValor('') }}>
            Receber</button>}
        </li>)}</ul>}
    </>}
    {selecionada && sessao && <form className="cadastro__formulario" onSubmit={(evento) => void receber(evento)}>
      <h3>Receber de {selecionada.nomeCliente}</h3>
      <p>Saldo desta Venda: {moeda.format(selecionada.saldoDevedor)}</p>
      <label>Valor recebido
        <input type="number" min="0.01" max={selecionada.saldoDevedor} step="0.01" required
          value={valor} onChange={(evento) => setValor(evento.target.value)} /></label>
      <label>Forma
        <select value={forma} onChange={(evento) => setForma(evento.target.value as FormaPagamento)}>
          <option value="DINHEIRO">Dinheiro</option><option value="PIX">Pix manual</option>
          <option value="CARTAO">Cartão manual</option>
        </select></label>
      <div className="cadastro__acoes"><button className="botao" disabled={ocupado}>Registrar recebimento</button>
        <button type="button" className="botao botao--secundario"
          onClick={() => setSelecionada(undefined)}>Cancelar</button></div>
    </form>}
    {comprovante && identidade && <section className="comprovante" aria-label="Comprovante de recebimento não fiscal">
      <h3>{identidade.nomeNegocio}</h3>
      <p><strong>Comprovante de recebimento não fiscal</strong></p>
      <p>Cliente: {comprovante.nomeCliente}</p>
      <p>Recebido em: {dataHora.format(new Date(comprovante.recebidoEm))}</p>
      <p>Forma: {comprovante.forma}</p>
      <p>Valor recebido: {moeda.format(comprovante.valor)}</p>
      <p>Saldo da Venda após recebimento: {moeda.format(comprovante.saldoApos)}</p>
      <p>Venda: {comprovante.vendaId}</p><p>Recebimento: {comprovante.recebimentoId}</p>
      <div className="comprovante__acoes">
        <button className="botao" type="button" onClick={() => window.print()}>Imprimir</button>
        {typeof navigator.share === 'function' && <button className="botao botao--secundario"
          type="button" onClick={() => void compartilhar()}>Compartilhar</button>}
      </div>
    </section>}
  </section>
}
