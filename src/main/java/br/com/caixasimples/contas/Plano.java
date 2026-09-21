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
    COMPLETO;

    /**
     * Se a conta pode ter mais de um usuário (RF29). Multiusuário é recurso do plano mais alto;
     * nos outros dois o dono é o único usuário, e ele vende, abre o caixa e faz o resto.
     *
     * <p>É um sim ou não, e não um número, porque a diferença entre os planos é exatamente essa:
     * um usuário ou vários. Um limite numérico por plano seria inventar tabela que o produto não
     * definiu.
     */
    public boolean permiteMultiusuario() {
        return this == COMPLETO;
    }
}
