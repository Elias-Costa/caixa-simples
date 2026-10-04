package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * As mensalidades dos planos pagos e o segredo que assina os códigos de pedido, vindos do ambiente.
 *
 * <p><strong>Nenhum dos três tem valor padrão</strong>, e a aplicação não sobe sem eles. Preço não
 * mora no código: muda sem commit e sem build, e o repositório não expõe valor comercial. Um
 * segredo padrão em código seria o mesmo em toda instalação, o que equivale a não ter segredo.
 *
 * <p>As regras abaixo recusam na subida o que daria pedido sem sentido depois: mensalidade
 * negativa, zerada ou com fração de centavo, e um plano completo que não custasse mais que o
 * anterior, o que tornaria o upgrade gratuito ou negativo.
 *
 * @param segredo                 a chave HMAC dos códigos, com ao menos 32 bytes
 * @param mensalidadeCaixaSimples o valor mensal do plano intermediário
 * @param mensalidadeCompleto     o valor mensal do plano mais alto
 */
@ConfigurationProperties(prefix = "caixa-simples.planos")
public record ConfiguracaoDosPlanos(
        String segredo,
        BigDecimal mensalidadeCaixaSimples,
        BigDecimal mensalidadeCompleto) {

    public ConfiguracaoDosPlanos {
        exigirMensalidade(mensalidadeCaixaSimples, "CAIXA_SIMPLES_MENSALIDADE_CAIXA_SIMPLES");
        exigirMensalidade(mensalidadeCompleto, "CAIXA_SIMPLES_MENSALIDADE_COMPLETO");
        if (mensalidadeCompleto.compareTo(mensalidadeCaixaSimples) <= 0) {
            throw new IllegalStateException("a mensalidade do plano Completo precisa ser maior que a"
                    + " do plano Caixa Simples, ou o upgrade nao custaria nada");
        }
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

    /** A mensalidade de um plano pago. */
    public Money mensalidade(Plano plano) {
        return switch (plano) {
            case CAIXA_SIMPLES -> Money.de(mensalidadeCaixaSimples);
            case COMPLETO -> Money.de(mensalidadeCompleto);
            case GRATIS -> throw new IllegalArgumentException("o plano gratuito nao tem mensalidade");
        };
    }
}
