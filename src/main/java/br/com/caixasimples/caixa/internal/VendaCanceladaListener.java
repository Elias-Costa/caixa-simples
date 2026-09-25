package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaCancelada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve a venda cancelada e devolve da gaveta o dinheiro que ela tinha trazido (RF12). É o oposto
 * exato de {@link VendaConcluidaListener}, e segue o mesmo molde: a venda publica um fato, o caixa
 * reage, e vendas não conhece este módulo. O evento chega pelo registro de publicação, depois do
 * commit do cancelamento; se esta classe falhar, a publicação fica incompleta e pode ser
 * reprocessada, em vez de o dinheiro ficar na gaveta em silêncio.
 *
 * <h2>O que sai da gaveta</h2>
 *
 * <p>O ESTORNO sai da sessão original da Venda, inclusive quando um recebimento de FIADO
 * entrou em outra sessão. A entrada da outra sessão permanece no extrato, mesmo se ela já fechou.
 * A parcela original em dinheiro é conferida pelo movimento VENDA; recebimentos em dinheiro vêm
 * como fatos imutáveis no evento. Se nada entrou em dinheiro, nem se abre transação.
 *
 * <p><strong>A mesma venda sai uma vez.</strong> A entrega é garantida ao menos uma vez, então o
 * evento pode chegar de novo; a raiz sabe dizer se a venda já foi estornada, e a reentrega vira
 * um não fazer nada, registrado em log. A raiz também recusa o estorno em dobro por conta
 * própria, para que nenhum outro chamador devolva o dinheiro duas vezes.
 *
 * <p><strong>O estorno pode deixar o esperado negativo</strong>, se houve sangria entre a venda e
 * o cancelamento. A raiz aceita, e a razão está escrita nela: o cancelamento já aconteceu, e
 * recusar aqui só prenderia a publicação com o caixa sem refletir o fato.
 *
 * <h2>Por que não a anotação de listener do Modulith</h2>
 *
 * <p>Mesmo motivo do ouvinte da venda concluída: a anotação pronta abre uma transação antes do
 * corpo do método, e o Hibernate resolve o tenant na abertura da sessão, então ela nasceria presa
 * ao sentinela sem enxergar a conta do evento. Aqui as duas anotações estão por extenso, a conta
 * do evento entra no contexto primeiro e a transação é aberta depois, à mão. Tenant primeiro,
 * transação depois.
 *
 * <p>Fica em {@code internal} porque é um adapter de entrada: nenhum outro módulo o nomeia.
 */
@Component
class VendaCanceladaListener {

    private static final Logger log = LoggerFactory.getLogger(VendaCanceladaListener.class);

    private final SessaoCaixaRepository sessoes;
    private final TransactionTemplate transacao;

    VendaCanceladaListener(SessaoCaixaRepository sessoes, TransactionTemplate transacao) {
        this.sessoes = sessoes;
        this.transacao = transacao;
    }

    /**
     * Roda depois do commit da transação que cancelou a venda, em outra thread.
     *
     * @throws SessaoCaixaNaoEncontradaException se a sessão do evento não existe na conta do
     *         evento; a publicação fica incompleta no registro
     * @throws IllegalStateException se a sessão já está FECHADA, ou se a venda tinha dinheiro e
     *         mesmo assim não entrou nesta sessão; idem
     */
    @Async
    @TransactionalEventListener
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
            // A gaveta nunca mexeu por esta venda: nada a devolver, e nem se abre transação.
            return;
        }

        // A devolução sai da sessão original, inclusive quando o fiado foi recebido em
        // outra sessão. A janela de cancelamento depende somente da sessão original aberta.
        TenantContext.executarComo(evento.contaId(), () ->
                transacao.executeWithoutResult(status -> {
                    SessaoCaixaEntity linha = sessoes.findById(evento.sessaoCaixaId())
                            .orElseThrow(() -> new SessaoCaixaNaoEncontradaException(
                                    evento.sessaoCaixaId()));
                    SessaoCaixa sessao = linha.paraDominio();

                    if (sessao.jaEstornouVenda(evento.vendaId())) {
                        // Reentrega do registro de publicação: o dinheiro já saiu da gaveta.
                        log.info("venda {} ja estornada na sessao de caixa {}; reentrega ignorada",
                                evento.vendaId(), evento.sessaoCaixaId());
                        return;
                    }

                    if (!emDinheiro.equals(Money.ZERO)
                            && !sessao.jaRegistrouVenda(evento.vendaId())) {
                        throw new IllegalStateException("venda ainda nao entrou no caixa para estorno");
                    }
                    sessao.estornarVenda(evento.vendaId(), fiadoRecebidoEmDinheiro);

                    linha.atualizarCom(sessao);
                    sessoes.save(linha);
                }));
    }
}
