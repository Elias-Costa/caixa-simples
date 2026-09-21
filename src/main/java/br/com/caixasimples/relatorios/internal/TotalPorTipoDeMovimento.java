package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import java.math.BigDecimal;

/**
 * A soma dos movimentos de um tipo no período, com os <strong>tipos da coluna</strong>.
 *
 * <p>É o alvo do {@code select new} em {@link MovimentoCaixaParaRelatorioRepository}: público
 * porque o Hibernate o instancia por reflexão, e traduzido para {@code Money} uma vez só, em
 * {@code FluxoDeCaixaService}. A consulta devolve uma linha por tipo que teve movimento; tipo sem
 * movimento não vem, e é o serviço quem o traduz em zero.
 *
 * @param tipo  quem carrega o sinal do movimento
 * @param total a soma dos valores, sempre positivos, daquele tipo; nunca nulo, porque só existe
 *              linha quando houve movimento
 */
public record TotalPorTipoDeMovimento(TipoMovimentoCaixa tipo, BigDecimal total) {
}
