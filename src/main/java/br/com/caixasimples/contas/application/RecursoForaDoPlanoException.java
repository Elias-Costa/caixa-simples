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
            case RELATORIOS -> "Os relatórios fazem parte do plano Completo";
            case ESTOQUE -> "O controle de estoque faz parte do plano Completo";
            case MULTIUSUARIO -> "Mais de um usuário na Conta faz parte do plano Completo";
            case PIX_INTEGRADO -> "O Pix integrado faz parte do plano Completo; o Pix manual"
                    + " vale em todo plano";
        };
        return doRecurso + ", e a Conta está no plano " + plano.nomeExibido() + ".";
    }
}
