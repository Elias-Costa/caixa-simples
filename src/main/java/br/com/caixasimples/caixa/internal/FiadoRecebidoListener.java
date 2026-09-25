package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.FiadoRecebido;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve o recebimento de fiado e lança na gaveta o dinheiro físico que entrou (RF33). Pix e cartão
 * ficam registrados só na Venda: nunca estiveram na gaveta.
 *
 * <p>O movimento entra na sessão de quem recebeu, que vem no evento, e não na sessão em que a
 * Venda nasceu: é naquela gaveta que o dinheiro está.
 *
 * <h2>Dentro da transação do recebimento</h2>
 *
 * <p>Roda na thread e na transação de quem recebeu, e não depois do commit: o recebimento gravado
 * na Venda e o dinheiro no esperado confirmam juntos ou não confirmam. Se outra operação alterou a
 * sessão entre a leitura e a gravação, uma sangria, um suprimento ou o fechamento, a versão da
 * raiz recusa a gravação e o recebimento inteiro falha, para quem recebeu repetir. Depois do
 * commit, a mesma recusa deixaria a dívida quitada com o dinheiro fora do esperado.
 *
 * <p>Sem reentrega: o fato não passa pelo registro de publicação, então chega uma vez. A raiz
 * continua recusando o mesmo recebimento duas vezes, para qualquer chamador.
 *
 * <p>A conta é a de quem recebeu, a mesma que a transação já usa, e o ouvinte confere que o evento
 * é dela. A transação é a de quem publicou; o {@link TransactionTemplate} só a abre quando o evento
 * é publicado fora de uma.
 *
 * <p>Fica em {@code internal} porque é um adapter de entrada: nenhum outro módulo o nomeia.
 */
@Component
class FiadoRecebidoListener {

    private final SessaoCaixaRepository sessoes;
    private final TransactionTemplate transacao;

    FiadoRecebidoListener(SessaoCaixaRepository sessoes, TransactionTemplate transacao) {
        this.sessoes = sessoes;
        this.transacao = transacao;
    }

    /**
     * Roda quando o recebimento é gravado na Venda, antes do commit.
     *
     * @throws SessaoCaixaNaoEncontradaException se a sessão do evento não existe nesta conta; o
     *         recebimento falha junto
     * @throws IllegalStateException se a sessão já está FECHADA, se o recebimento já entrou nela ou
     *         se o evento é de outra conta; o recebimento falha junto
     */
    @EventListener
    public void lancarNoCaixa(FiadoRecebido evento) {
        if (evento.forma() != FormaPagamento.DINHEIRO) {
            // Pix e cartão não passam pela gaveta: nada a lançar.
            return;
        }
        if (!TenantContext.exigirAtual().equals(evento.contaId())) {
            throw new IllegalStateException("recebimento " + evento.recebimentoId()
                    + " de outra conta; o caixa nao lanca");
        }

        transacao.executeWithoutResult(status -> {
            SessaoCaixaEntity linha = sessoes.findById(evento.sessaoCaixaId())
                    .orElseThrow(() -> new SessaoCaixaNaoEncontradaException(
                            evento.sessaoCaixaId()));
            SessaoCaixa sessao = linha.paraDominio();

            sessao.registrarRecebimento(evento.vendaId(), evento.recebimentoId(),
                    evento.valor());

            linha.atualizarCom(sessao);
            sessoes.save(linha);
        });
    }
}
