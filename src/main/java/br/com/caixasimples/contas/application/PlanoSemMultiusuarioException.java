package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.Plano;

/**
 * A conta tentou criar um segundo usuário num plano que só admite um (RF29).
 *
 * <p>Multiusuário é recurso do plano mais alto; nos outros, o dono é o único usuário. Não é erro
 * de quem digitou: é o limite do plano contratado, e a saída é a troca de plano (RF31).
 */
public class PlanoSemMultiusuarioException extends RuntimeException {

    public PlanoSemMultiusuarioException(Plano plano) {
        super("o plano " + plano + " admite um unico usuario; mais usuarios exigem o plano "
                + Plano.COMPLETO);
    }
}
