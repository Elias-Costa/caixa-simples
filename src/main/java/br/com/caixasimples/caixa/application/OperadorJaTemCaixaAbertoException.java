package br.com.caixasimples.caixa.application;

import java.util.UUID;

/**
 * O operador já tem uma sessão de caixa ABERTA, e um operador só tem um caixa por vez.
 *
 * <p>A regra é por operador, não por conta: dois atendentes podem ter caixas simultâneos no mesmo
 * negócio, já que cada um responde pelo <em>próprio</em> caixa. O que não existe é o mesmo operador
 * com dois.
 *
 * <p>É situação de rotina, não defeito. Normalmente é o caixa de ontem que ficou sem fechar. Por
 * isso ela tem nome próprio, em vez de sair como violação de integridade do índice único da
 * migration V6: a tela precisa poder oferecer <em>fechar o caixa anterior</em>, e não uma mensagem
 * de erro genérica.
 */
public class OperadorJaTemCaixaAbertoException extends RuntimeException {

    public OperadorJaTemCaixaAbertoException(UUID usuarioId) {
        super("o operador " + usuarioId + " ja tem uma sessao de caixa aberta;"
                + " feche a anterior antes de abrir outra");
    }
}
