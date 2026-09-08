package br.com.caixasimples.caixa;

/**
 * Em que ponto do expediente a sessao de caixa esta.
 *
 * <p>Enquanto <strong>ABERTA</strong>, tres colunas de {@code sessao_caixa} ficam nulas de
 * proposito — {@code valor_fechamento_contado}, {@code diferenca} e {@code fechada_em}; todas as
 * tres nascem juntas, no fechamento (R08).
 *
 * <p>Se uma sessao FECHADA pode ou nao voltar a ABERTA e uma decisao que <strong>ainda nao
 * existe</strong>: nenhum documento fala em reabertura, e este passo nao a inventa. A transicao
 * mora no R08, junto com a conferencia.
 *
 * <p>Fica na raiz do modulo, como {@code cadastro.TipoProduto} e {@code contas.Plano}: e
 * vocabulario que {@code vendas} vai precisar nomear para saber se pode lancar uma venda.
 */
public enum StatusSessaoCaixa {
    ABERTA,
    FECHADA
}
