package br.com.caixasimples.caixa;

/**
 * Em que ponto do expediente a sessão de caixa está.
 *
 * <p>Enquanto <strong>ABERTA</strong>, três colunas de {@code sessao_caixa} ficam nulas de
 * propósito: {@code valor_fechamento_contado}, {@code diferenca} e {@code fechada_em}. As três
 * nascem juntas, no fechamento.
 *
 * <p>Se uma sessão FECHADA pode ou não voltar a ABERTA é uma decisão que <strong>ainda não
 * existe</strong>. Nenhum requisito fala em reabertura, e o código não a inventa: enquanto a
 * pergunta não for respondida, o fechamento é terminal.
 *
 * <p>Fica na raiz do módulo, como {@code cadastro.TipoProduto} e {@code contas.Plano}, porque é
 * vocabulário que {@code vendas} vai precisar nomear para saber se pode lançar uma venda.
 */
public enum StatusSessaoCaixa {
    ABERTA,
    FECHADA
}
