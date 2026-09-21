package br.com.caixasimples.relatorios.internal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Uma linha do ranking dos mais vendidos, com os <strong>tipos da coluna</strong>.
 *
 * <p>É o alvo do {@code select new} em {@link ItemVendaParaRelatorioRepository}: o Hibernate
 * instancia este record por reflexão, pelo nome escrito na consulta, e por isso ele é público
 * apesar de nunca sair do pacote interno por conta própria. A tradução do valor para
 * {@code Money} acontece uma vez só, em {@code MaisVendidosService}; a quantidade não é dinheiro
 * e segue como {@link BigDecimal} de três casas até a resposta.
 *
 * <p>Sem {@code contaId}, pelo mesmo motivo de toda projeção do projeto: quem filtra é o tenant
 * na entidade (RNF05).
 *
 * @param produtoId  o produto, por id
 * @param nome       o nome atual do produto, mesmo que ele já esteja inativo
 * @param unidade    a unidade cadastrada, que dá sentido à quantidade; pode ser nula
 * @param quantidade a soma das quantidades vendidas no período; nunca nula, porque só existe
 *                   linha quando houve item
 * @param valor      a soma dos subtotais dos itens, cada um arredondado para centavos antes de
 *                   somar, como o domínio faz; nunca nulo, pelo mesmo motivo
 */
public record ProdutoVendido(UUID produtoId, String nome, String unidade, BigDecimal quantidade,
        BigDecimal valor) {
}
