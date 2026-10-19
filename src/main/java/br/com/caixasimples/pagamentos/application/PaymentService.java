package br.com.caixasimples.pagamentos.application;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.PaymentStrategy;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Porta única de entrada para pagar uma parcela de venda (RF09, RF10), qualquer que seja a forma.
 *
 * <p><strong>Esta classe não conhece nenhuma forma de pagamento.</strong> Ela recebe todas as
 * estratégias registradas, monta um mapa de forma para estratégia e encontra a certa pela chave.
 * É o ponto do desenho: acrescentar Pix, cartão ou o que vier depois é criar uma classe anotada
 * como componente, sem alterar uma linha daqui. Um teste guarda essa promessa.
 *
 * <p>Ela também não abre transação e não escreve em banco. Quem persiste o pagamento é o agregado
 * Venda, dono da parcela; aqui só se decide o que fica registrado nela.
 *
 * <p>O Pix tem duas portas, porque é a única forma que chega por dois caminhos. A estratégia do Pix,
 * por {@link #pagar}, reserva a cobrança integrada ao provedor, que nasce pendente e espera a
 * confirmação dele. O Pix que o operador lança à mão, depois de ver o dinheiro no extrato ou no
 * aplicativo do banco, entra por {@link #registrarPixAMao} e nasce confirmado, como o cartão
 * passado na maquininha. A forma é a mesma; muda quem confirmou, a pessoa ou o provedor.
 *
 * <p>Duas estratégias declarando a mesma forma <strong>quebram a aplicação na subida</strong>, em
 * vez de uma sobrescrever a outra em silêncio. O caso é raro e sempre acidental, e é justamente por
 * ser raro que passaria despercebido: o balcão continuaria vendendo, com metade das regras da forma
 * duplicada valendo e a outra metade não.
 */
@Service
public class PaymentService {

    private final Map<FormaPagamento, PaymentStrategy> porForma;

    PaymentService(List<PaymentStrategy> estrategias) {
        this.porForma = estrategias.stream().collect(Collectors.toMap(
                PaymentStrategy::getTipo,
                estrategia -> estrategia,
                (primeira, segunda) -> {
                    throw new IllegalStateException(
                            "duas estrategias declaram a forma de pagamento "
                                    + primeira.getTipo() + ": "
                                    + primeira.getClass().getSimpleName() + " e "
                                    + segunda.getClass().getSimpleName());
                }));
    }

    /**
     * Paga uma parcela pela forma que a solicitação indica.
     *
     * @throws FormaDePagamentoNaoSuportadaException se nenhuma estratégia atende a forma pedida
     * @throws IllegalArgumentException              se a solicitação não serve para a forma, como
     *                                               dinheiro recebido a menos que o valor a pagar
     */
    public ResultadoPagamento pagar(SolicitacaoPagamento solicitacao) {
        Objects.requireNonNull(solicitacao, "solicitacao de pagamento nao pode ser nula");

        PaymentStrategy estrategia = porForma.get(solicitacao.forma());
        if (estrategia == null) {
            throw new FormaDePagamentoNaoSuportadaException(solicitacao.forma());
        }

        return estrategia.pagar(solicitacao);
    }

    /**
     * Registra o Pix lançado à mão pelo operador, confirmado na hora, sem passar pela estratégia da
     * cobrança integrada.
     *
     * @throws IllegalArgumentException se a forma não é PIX ou se a solicitação traz valor recebido
     */
    public ResultadoPagamento registrarPixAMao(SolicitacaoPagamento solicitacao) {
        Objects.requireNonNull(solicitacao, "solicitacao de pagamento nao pode ser nula");
        if (solicitacao.forma() != FormaPagamento.PIX) {
            throw new IllegalArgumentException("o Pix lancado a mao exige a forma PIX, e nao "
                    + solicitacao.forma());
        }
        return ResultadoPagamento.registradoAMao(solicitacao);
    }
}
