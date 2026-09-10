package br.com.caixasimples.contas;

/**
 * Perfil de acesso de um usuário dentro de uma conta (RF29, RF30).
 */
public enum Perfil {

    /** Dono: cadastro, configuração, relatórios financeiros completos, usuários e plano. */
    ADMIN,

    /**
     * Caixa: tela de vendas e abertura ou fechamento do próprio caixa.
     *
     * <p>Sem acesso a relatório consolidado nem a configuração da conta (RF30). A restrição ainda
     * não está implementada; quando estiver, virá acompanhada de teste de autorização negativa.
     */
    OPERADOR
}
