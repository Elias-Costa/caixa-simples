package br.com.caixasimples.estoque.application;

import br.com.caixasimples.shared.ContaId;

/**
 * A conta em operação não ligou o controle de estoque (RF17), e o módulo de estoque não atende
 * conta com o controle desligado.
 *
 * <p>Toda conta nasce assim, porque negócio baseado em serviço não precisa de estoque. Não é
 * defeito: é a tela pedindo ajuste, mínimo ou alerta de estoque para um negócio que ainda não
 * ligou o módulo. Por isso tem nome próprio, em vez de sair como {@code IllegalStateException}: a
 * tela precisa poder oferecer <em>ligar o controle de estoque</em>, e não uma mensagem de erro
 * genérica.
 */
public class ControleDeEstoqueDesligadoException extends RuntimeException {

    public ControleDeEstoqueDesligadoException(ContaId contaId) {
        super("a conta " + contaId + " nao controla estoque;"
                + " ligue o controle de estoque antes de ajustar saldo ou consultar alerta");
    }
}
