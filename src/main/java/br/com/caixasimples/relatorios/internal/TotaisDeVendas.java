package br.com.caixasimples.relatorios.internal;

import java.math.BigDecimal;

/**
 * O que a consulta agregada do faturamento devolve, com os <strong>tipos da coluna</strong>.
 *
 * <p>É o alvo do {@code select new} em {@link VendaParaRelatorioRepository}: o Hibernate
 * instancia este record por reflexão, pelo nome escrito na consulta, e por isso ele é público
 * apesar de nunca sair do pacote interno por conta própria. A tradução para {@code Money}, que é a
 * linguagem do resto do sistema, acontece uma vez só, em {@code FaturamentoService}.
 *
 * <p>Sem {@code contaId}, pelo mesmo motivo de toda projeção do projeto: quem filtra é o tenant
 * na entidade, e devolver a conta só daria a quem lê a ideia de conferi-la à mão (RNF05).
 *
 * @param total      a soma de {@code valor_total}; <strong>nulo quando nenhuma venda entrou</strong>,
 *                   porque a soma de zero linhas é nula em SQL, e é o serviço quem a traduz em zero
 * @param quantidade quantas vendas entraram na soma; zero, e não nulo, quando nenhuma
 */
public record TotaisDeVendas(BigDecimal total, Long quantidade) {
}
