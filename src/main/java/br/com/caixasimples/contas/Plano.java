package br.com.caixasimples.contas;

/**
 * Plano contratado pela conta.
 *
 * <p>Ordem crescente de abrangência: cada plano inclui o anterior. Os planos pagos têm mensalidade,
 * e o valor dela vem do ambiente, não do código.
 */
public enum Plano {

    /** Um usuário: cadastro, vendas e caixa básico. Sem mensalidade. */
    GRATIS,

    /** Tudo do grátis, mais relatórios completos e suporte prioritário. */
    CAIXA_SIMPLES,

    /**
     * Tudo do plano anterior, mais estoque e multiusuário. A emissão fiscal ainda não existe, e a
     * tela avisa isso antes de a Conta pedir o plano.
     */
    COMPLETO;

    /**
     * Se o plano dá direito ao recurso. O direito pode estar suspenso por falta de renovação; quem
     * responde se ele vale hoje é a Conta, que conhece o vencimento.
     *
     * <p>Multiusuário é um sim ou não, e não um número, porque a diferença entre os planos é
     * exatamente essa: um usuário ou vários. Um limite numérico por plano seria inventar tabela que
     * o produto não definiu.
     */
    public boolean inclui(RecursoDoPlano recurso) {
        return switch (recurso) {
            case RELATORIOS -> this == CAIXA_SIMPLES || this == COMPLETO;
            case ESTOQUE, MULTIUSUARIO -> this == COMPLETO;
        };
    }

    /** Como o plano aparece nas mensagens a quem usa o sistema. */
    public String nomeExibido() {
        return switch (this) {
            case GRATIS -> "Gratuito";
            case CAIXA_SIMPLES -> "Caixa Simples";
            case COMPLETO -> "Completo";
        };
    }
}
