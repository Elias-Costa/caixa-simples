package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A mensalidade do plano pago e o segredo que assina os códigos de pedido, vindos do ambiente.
 *
 * <p><strong>Nenhum dos dois tem valor padrão</strong>, e a aplicação não sobe sem eles. Preço não
 * mora no código: muda sem commit e sem build, e o repositório não expõe valor comercial. Um
 * segredo padrão em código seria o mesmo em toda instalação, o que equivale a não ter segredo.
 *
 * <p>A regra abaixo recusa na subida o que daria pedido sem sentido depois: mensalidade negativa,
 * zerada ou com fração de centavo.
 *
 * @param segredo             a chave HMAC dos códigos, com ao menos 32 bytes
 * @param mensalidadeCompleto o valor mensal do plano completo
 */
@ConfigurationProperties(prefix = "fechadia.planos")
public record ConfiguracaoDosPlanos(String segredo, BigDecimal mensalidadeCompleto) {

    public ConfiguracaoDosPlanos {
        exigirMensalidade(mensalidadeCompleto, "FECHADIA_MENSALIDADE_COMPLETO");
    }

    private static void exigirMensalidade(BigDecimal valor, String variavel) {
        if (valor == null) {
            throw new IllegalStateException(variavel + " nao foi definida");
        }
        if (valor.signum() <= 0) {
            throw new IllegalStateException(variavel + " precisa ser positiva; recebeu " + valor);
        }
        if (valor.stripTrailingZeros().scale() > Money.ESCALA) {
            throw new IllegalStateException(variavel + " tem fracao de centavo: " + valor);
        }
    }

    /** A mensalidade do plano pago. */
    public Money mensalidade(Plano plano) {
        return switch (plano) {
            case COMPLETO -> Money.de(mensalidadeCompleto);
            case GRATIS -> throw new IllegalArgumentException("o plano gratuito nao tem mensalidade");
        };
    }
}
