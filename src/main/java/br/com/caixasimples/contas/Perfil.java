package br.com.caixasimples.contas;

/**
 * Perfil de acesso de um usuario dentro de uma conta (escopo §5, RF29/RF30).
 */
public enum Perfil {

    /** Dono: cadastro, configuracao, relatorios financeiros completos, usuarios e plano. */
    ADMIN,

    /**
     * Caixa: tela de vendas e abertura/fechamento do proprio caixa.
     *
     * <p>Sem acesso a relatorio consolidado nem a configuracao da conta (RF30) — restricao
     * implementada na etapa 1.11 e coberta por teste de autorizacao negativa.
     */
    OPERADOR
}
