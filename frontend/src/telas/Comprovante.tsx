import type { Comprovante as DadosDoComprovante } from '../api/vendas'
import { dataHora, moeda } from '../formatos'
import { erroDeCadastro } from './erroDeCadastro'
import { ROTULO_DA_FORMA } from './rotulos'

const PENDENTE_NO_COMPROVANTE = 'Pendente de sincronização com o servidor'

/**
 * As linhas que mudam conforme a venda, as mesmas no papel e no texto compartilhado: o troco só existe
 * no dinheiro, e o desconto da venda só quando houve. Uma linha de R$ 0,00 faz o cliente perguntar.
 */
function comTroco(comprovante: DadosDoComprovante): boolean {
  return comprovante.parcelas.some((parcela) => parcela.forma === 'DINHEIRO')
}

function linhaDaSegundaVia(segundaViaEm: Date): string {
  return `Segunda via, reimpressa em ${dataHora.format(segundaViaEm)}`
}

function textoDoComprovante(comprovante: DadosDoComprovante, negocio: string, operador: string,
  segundaViaEm: Date | undefined): string {
  return [
    negocio, `Operador: ${operador}`, 'Comprovante não fiscal',
    ...(segundaViaEm ? [linhaDaSegundaVia(segundaViaEm)] : []),
    ...(comprovante.pendenteSincronizacao ? [PENDENTE_NO_COMPROVANTE] : []),
    dataHora.format(new Date(comprovante.concluidoEm)),
    ...comprovante.linhas.map((linha) =>
      `${linha.quantidade} × ${linha.nome}: ${moeda.format(linha.subtotal)}`),
    ...(comprovante.descontoDaVenda > 0
      ? [`Desconto da venda: ${moeda.format(comprovante.descontoDaVenda)}`] : []),
    `Total: ${moeda.format(comprovante.valorTotal)}`,
    ...comprovante.parcelas.map((parcela) =>
      `${ROTULO_DA_FORMA[parcela.forma]}: ${moeda.format(parcela.valor)}`),
    ...(comprovante.nomeCliente ? [`Cliente: ${comprovante.nomeCliente}`] : []),
    ...(comprovante.valorFiado > 0 ? [
      `Valor fiado: ${moeda.format(comprovante.valorFiado)}`,
      `Valor pendente: ${moeda.format(comprovante.saldoDevedor)}`,
    ] : []),
    ...(comTroco(comprovante) ? [`Troco: ${moeda.format(comprovante.troco)}`] : []),
    `Venda: ${comprovante.vendaId}`,
  ].join('\n')
}

type Props = {
  comprovante: DadosDoComprovante
  negocio: string
  operador: string
  /**
   * Quando a venda é reaberta depois de concluída: o papel diz que é segunda via, e quando saiu.
   * Dois papéis iguais da venda fiada passariam por duas dívidas.
   */
  segundaViaEm?: Date
  /** A falha do compartilhamento; a mensagem fica fora do papel branco, onde se lê. */
  aoFalhar: (mensagem: string) => void
}

/**
 * O comprovante não fiscal como sai na impressora, branco em qualquer tema, com Imprimir e, onde o
 * navegador tem o recurso, Compartilhar. É o mesmo logo depois da venda e na reimpressão; só a
 * segunda via ganha a linha que a marca.
 */
export function Comprovante({ comprovante, negocio, operador, segundaViaEm, aoFalhar }: Props) {
  async function compartilhar() {
    try {
      await navigator.share({
        title: 'Comprovante não fiscal',
        text: textoDoComprovante(comprovante, negocio, operador, segundaViaEm),
      })
    } catch (falha) {
      if ((falha as Error).name !== 'AbortError') aoFalhar(erroDeCadastro(falha))
    }
  }

  return <section className="comprovante" aria-label="Comprovante não fiscal">
    <h3>{negocio}</h3>
    <p>Operador: {operador}</p>
    <p><strong>Comprovante não fiscal</strong></p>
    {segundaViaEm && <p><strong>{linhaDaSegundaVia(segundaViaEm)}</strong></p>}
    {comprovante.pendenteSincronizacao && <p><strong>{PENDENTE_NO_COMPROVANTE}</strong></p>}
    <p>{dataHora.format(new Date(comprovante.concluidoEm))}</p>
    <ul>{comprovante.linhas.map((linha, indice) => <li key={`${linha.produtoId}-${indice}`}>
      {linha.quantidade} × {linha.nome} · {moeda.format(linha.precoUnitario)} · {moeda.format(linha.subtotal)}
      {linha.desconto > 0 && ` · desconto ${moeda.format(linha.desconto)}`}
    </li>)}</ul>
    <p>Soma dos itens: {moeda.format(comprovante.somaDosItens)}</p>
    {comprovante.descontoDaVenda > 0 && <p>Desconto da venda: {moeda.format(comprovante.descontoDaVenda)}</p>}
    <p><strong>Total: {moeda.format(comprovante.valorTotal)}</strong></p>
    <ul>{comprovante.parcelas.map((parcela, indice) => <li key={indice}>
      {ROTULO_DA_FORMA[parcela.forma]}: {moeda.format(parcela.valor)}</li>)}</ul>
    {/* Só na venda fiada: o papel que prova a dívida diz de quem ela é. */}
    {comprovante.nomeCliente && <p>Cliente: {comprovante.nomeCliente}</p>}
    {comprovante.valorFiado > 0 && <>
      <p>Valor fiado: {moeda.format(comprovante.valorFiado)}</p>
      <p><strong>Valor pendente: {moeda.format(comprovante.saldoDevedor)}</strong></p>
    </>}
    {comTroco(comprovante) && <p>Troco: {moeda.format(comprovante.troco)}</p>}
    <p>Venda: {comprovante.vendaId}</p>
    <div className="comprovante__acoes">
      <button className="botao" type="button" onClick={() => window.print()}>Imprimir</button>
      {typeof navigator.share === 'function' && <button className="botao botao--secundario" type="button"
        onClick={() => void compartilhar()}>Compartilhar</button>}
    </div>
  </section>
}
