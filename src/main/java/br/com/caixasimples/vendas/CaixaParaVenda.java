package br.com.caixasimples.vendas;

import java.util.UUID;

/**
 * A pergunta que a venda faz ao caixa: esta sessão existe nesta conta e está aberta?
 *
 * <p><strong>Declarada aqui, e implementada pelo módulo do caixa.</strong> A direção é
 * deliberada: o caixa já depende de vendas para ouvir {@link VendaConcluida}, e dois módulos não
 * podem depender um do outro, porque a verificação de fronteiras recusa ciclo. Se vendas chamasse
 * o caso de uso do caixa diretamente, o ciclo estaria fechado. Com a interface deste lado, vendas
 * não conhece o módulo do caixa; conhece só o id da sessão e esta pergunta.
 *
 * <p>Continua sendo chamada direta, e não evento, porque é pergunta: a venda precisa da resposta
 * antes de seguir. A regra de que venda só começa e só conclui em caixa aberto é da venda, e mora
 * em quem chama; aqui só se responde.
 */
public interface CaixaParaVenda {

    /**
     * @return se a sessão de caixa está ABERTA
     * @throws RuntimeException se a sessão não existe nesta conta, ou se quem pergunta é um
     *         operador e a sessão é de outro. A exceção é a do módulo dono e atravessa sem
     *         tradução: um id de outra conta é indistinguível de um id que nunca existiu (RNF05),
     *         e a regra do próprio caixa é do caixa, que a aplica ao responder
     */
    boolean estaAberto(UUID sessaoCaixaId);
}
