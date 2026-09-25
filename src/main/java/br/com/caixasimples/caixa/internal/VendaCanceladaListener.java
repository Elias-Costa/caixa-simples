package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaCancelada;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve a venda cancelada e devolve da gaveta o dinheiro que ela tinha trazido (RF12). É o oposto
 * exato de {@link VendaConcluidaListener}, e segue o mesmo molde: a venda publica um fato, o caixa
 * reage, e vendas não conhece este módulo.
 *
 * <h2>O que sai da gaveta</h2>
 *
 * <p>O ESTORNO sai da sessão original da Venda, inclusive quando um recebimento de FIADO
 * entrou em outra sessão. A entrada da outra sessão permanece no extrato, mesmo se ela já fechou.
 * A parcela original em dinheiro é conferida pelo movimento VENDA; recebimentos em dinheiro vêm
 * como fatos imutáveis no evento. Se nada entrou em dinheiro, nada sai.
 *
 * <p><strong>O estorno pode deixar o esperado negativo</strong>, se houve sangria entre a venda e
 * o cancelamento. A raiz aceita, e a razão está escrita nela: o cancelamento já aconteceu no
 * balcão, e recusar o estorno só impediria o sistema de registrá-lo.
 *
 * <h2>Dentro da transação do cancelamento</h2>
 *
 * <p>Roda na thread e na transação de quem cancelou a venda, e não depois do commit. A venda
 * cancelada e o dinheiro fora da gaveta confirmam juntos ou não confirmam. Se outra operação
 * alterou a sessão entre a leitura e a gravação, uma sangria ou o fechamento, a versão da raiz
 * recusa a gravação e o cancelamento inteiro falha, para quem cancelou repetir. Depois do commit,
 * a mesma recusa derrubaria só o estorno, com a venda já cancelada e o dinheiro ainda contado no
 * esperado. Pelo mesmo motivo, a regra de só cancelar com o caixa aberto vale de fato: a
 * conferência da sessão e o estorno estão na mesma transação.
 *
 * <p>Sem reentrega: o fato não passa pelo registro de publicação, então chega uma vez. A raiz
 * continua recusando o estorno em dobro, para qualquer chamador.
 *
 * <p>A conta é a de quem cancelou, a mesma que a transação já usa; o ouvinte confere que o evento
 * é dela, porque trocar de conta no meio de uma transação não surtiria efeito sobre a sessão do
 * Hibernate já aberta. A transação é a de quem publicou, e o {@link TransactionTemplate} só a abre
 * quando o evento é publicado fora de uma.
 *
 * <p>Fica em {@code internal} porque é um adapter de entrada: nenhum outro módulo o nomeia.
 */
@Component
class VendaCanceladaListener {

    private final SessaoCaixaRepository sessoes;
    private final TransactionTemplate transacao;

    VendaCanceladaListener(SessaoCaixaRepository sessoes, TransactionTemplate transacao) {
        this.sessoes = sessoes;
        this.transacao = transacao;
    }

    /**
     * Roda quando a venda é cancelada, antes do commit.
     *
     * @throws SessaoCaixaNaoEncontradaException se a sessão do evento não existe nesta conta; o
     *         cancelamento falha junto
     * @throws IllegalStateException se a sessão já está FECHADA, se a venda tinha dinheiro e mesmo
     *         assim não entrou nesta sessão, se ela já foi estornada ou se o evento é de outra
     *         conta; o cancelamento falha junto
     */
    @EventListener
    public void devolverDoCaixa(VendaCancelada evento) {
        Money emDinheiro = evento.parcelas().stream()
                .filter(parcela -> parcela.forma() == FormaPagamento.DINHEIRO)
                .filter(parcela -> parcela.status() == StatusPagamento.CONFIRMADO)
                .map(VendaCancelada.Parcela::valor)
                .reduce(Money.ZERO, Money::somar);

        Money fiadoRecebidoEmDinheiro = evento.recebimentos().stream()
                .filter(recebimento -> recebimento.forma() == FormaPagamento.DINHEIRO)
                .map(VendaCancelada.Recebimento::valor)
                .reduce(Money.ZERO, Money::somar);

        if (emDinheiro.equals(Money.ZERO) && fiadoRecebidoEmDinheiro.equals(Money.ZERO)) {
            // A gaveta nunca mexeu por esta venda: nada a devolver.
            return;
        }
        if (!TenantContext.exigirAtual().equals(evento.contaId())) {
            throw new IllegalStateException(
                    "venda " + evento.vendaId() + " cancelada em outra conta; o caixa nao estorna");
        }

        // A devolução sai da sessão original, inclusive quando o fiado foi recebido em
        // outra sessão. A janela de cancelamento depende somente da sessão original aberta.
        transacao.executeWithoutResult(status -> {
            SessaoCaixaEntity linha = sessoes.findById(evento.sessaoCaixaId())
                    .orElseThrow(() -> new SessaoCaixaNaoEncontradaException(
                            evento.sessaoCaixaId()));
            SessaoCaixa sessao = linha.paraDominio();

            if (!emDinheiro.equals(Money.ZERO) && !sessao.jaRegistrouVenda(evento.vendaId())) {
                // Sem o movimento VENDA, a raiz estornaria só os recebimentos e deixaria a parcela
                // em dinheiro para trás.
                throw new IllegalStateException("venda " + evento.vendaId()
                        + " tinha dinheiro e nao entrou na sessao de caixa "
                        + evento.sessaoCaixaId() + "; o estorno nao sai pela metade");
            }
            sessao.estornarVenda(evento.vendaId(), fiadoRecebidoEmDinheiro);

            linha.atualizarCom(sessao);
            sessoes.save(linha);
        });
    }
}
