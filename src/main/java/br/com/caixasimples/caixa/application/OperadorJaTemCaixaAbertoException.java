package br.com.caixasimples.caixa.application;

import java.util.UUID;

/**
 * D22a — o operador ja tem uma sessao de caixa ABERTA, e um operador so tem um caixa por vez.
 *
 * <p>A regra e por operador, nao por conta: dois atendentes podem ter caixas simultaneos no mesmo
 * negocio (escopo §5 fala do <em>proprio</em> caixa de cada um). O que nao existe e o mesmo
 * operador com dois.
 *
 * <p>E situacao de rotina, nao defeito — normalmente e o caixa de ontem que ficou sem fechar. Por
 * isso ela tem nome proprio, em vez de sair como violacao de integridade do indice unico da V6: a
 * tela do R23 precisa oferecer <em>fechar o caixa anterior</em>, e nao uma mensagem de erro
 * generica.
 */
public class OperadorJaTemCaixaAbertoException extends RuntimeException {

    public OperadorJaTemCaixaAbertoException(UUID usuarioId) {
        super("o operador " + usuarioId + " ja tem uma sessao de caixa aberta;"
                + " feche a anterior antes de abrir outra (D22a)");
    }
}
