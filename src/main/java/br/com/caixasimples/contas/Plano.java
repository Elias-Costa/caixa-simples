package br.com.caixasimples.contas;

/**
 * Plano contratado pela conta (escopo §10).
 *
 * <p>Ordem crescente de abrangencia: cada plano inclui o anterior.
 */
public enum Plano {

    /** 1 usuario, sem emissao fiscal: cadastro + vendas + caixa basico. */
    GRATIS,

    /** Tudo do gratis + relatorios completos + suporte prioritario. */
    CAIXA_SIMPLES,

    /** Tudo do Caixa Simples + NFC-e + estoque + multiusuario. */
    COMPLETO
}
