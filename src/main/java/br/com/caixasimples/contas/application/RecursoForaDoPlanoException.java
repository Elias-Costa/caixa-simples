package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.RecursoDoPlano;

/**
 * O plano da Conta não inclui o recurso pedido.
 *
 * <p>Conflito com o estado da Conta, e não falta de permissão: quem chama tem o perfil para usar
 * o recurso, mas o plano contratado não o oferece, e a saída é a troca de plano (RF31). Por isso
 * estende {@link IllegalStateException}, que responde 409 com a mensagem.
 */
public class RecursoForaDoPlanoException extends IllegalStateException {

    public RecursoForaDoPlanoException(RecursoDoPlano recurso, Plano plano) {
        super(mensagem(recurso, plano));
    }

    private static String mensagem(RecursoDoPlano recurso, Plano plano) {
        String doRecurso = switch (recurso) {
            case RELATORIOS -> "os relatorios fazem parte dos planos Caixa Simples e Completo";
            case ESTOQUE -> "o controle de estoque faz parte do plano Completo";
            case MULTIUSUARIO -> "mais de um usuario na conta faz parte do plano Completo";
        };
        return doRecurso + ", e a conta esta no plano " + plano.nomeExibido();
    }
}
