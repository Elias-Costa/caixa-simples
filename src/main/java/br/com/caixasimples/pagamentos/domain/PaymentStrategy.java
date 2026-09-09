package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.pagamentos.FormaPagamento;

/**
 * Uma forma de pagamento (RF09). Existe uma implementação por forma, e é isso que faz acrescentar
 * uma forma nova ser criar uma classe, nunca editar um encadeamento de condições.
 *
 * <p>Cada implementação é um componente, e quem as reúne é {@code PaymentService}, que monta um
 * mapa de forma para estratégia e resolve por ele. Consequência prática, e é o ponto do desenho
 * inteiro: <strong>nenhuma decisão sobre forma de pagamento em código de negócio olha o enum</strong>
 * para escolher o que fazer. Quem escolhe é o mapa.
 *
 * <p>O nome em inglês é o do padrão, e a convenção vale só para as duas peças que o nomeiam, esta
 * e o serviço que a resolve. Todo o resto do módulo é português, como o restante do sistema.
 */
public interface PaymentStrategy {

    /** A forma que esta implementação atende. É a chave pela qual ela é encontrada. */
    FormaPagamento getTipo();

    /**
     * Registra o pagamento de uma parcela e devolve o que ficou registrado.
     *
     * <p>Cada implementação valida o que a sua forma exige da solicitação: dinheiro exige o valor
     * recebido, e as formas que não devolvem troco o ignoram. As guardas comuns a todas, valor
     * presente e positivo, já foram aplicadas por {@link SolicitacaoPagamento}.
     */
    ResultadoPagamento pagar(SolicitacaoPagamento solicitacao);
}
