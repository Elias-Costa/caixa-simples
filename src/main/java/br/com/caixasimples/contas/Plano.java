package br.com.caixasimples.contas;

/**
 * Plano contratado pela conta.
 *
 * <p>Ordem crescente de abrangência: cada plano inclui o anterior.
 */
public enum Plano {

    /** Um usuário, sem emissão fiscal: cadastro, vendas e caixa básico. */
    GRATIS,

    /** Tudo do grátis, mais relatórios completos e suporte prioritário. */
    CAIXA_SIMPLES,

    /** Tudo do plano anterior, mais emissão fiscal, estoque e multiusuário. */
    COMPLETO
}
