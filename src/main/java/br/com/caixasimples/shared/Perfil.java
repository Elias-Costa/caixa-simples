package br.com.caixasimples.shared;

/**
 * Perfil de acesso de um usuário dentro de uma conta (RF29, RF30).
 *
 * <p>Vive em {@code shared}, e não em {@code contas}, porque todo caso de uso restrito lê o perfil
 * de quem chama por {@link UsuarioContext}, e o contexto do usuário é transversal como o do tenant.
 * Quem decide o perfil de cada pessoa continua sendo o módulo de contas.
 */
public enum Perfil {

    /**
     * Dono: cadastro de produto, configuração, estoque, relatórios, usuários e plano. Faz tudo o que
     * o operador faz, em qualquer caixa da conta, porque no plano de um usuário só o dono também é
     * quem vende.
     */
    ADMIN,

    /**
     * Caixa: a venda inteira, o cadastro de cliente no balcão e a abertura, os lançamentos e o
     * fechamento do <strong>próprio</strong> caixa.
     *
     * <p>Sem acesso a relatório, a cadastro de produto, a estoque nem a gestão de usuários (RF30).
     * A restrição é verificada em cada caso de uso, e cada um tem teste de autorização negativa.
     */
    OPERADOR
}
