package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaConcluida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve a venda concluída e lança na gaveta o dinheiro que ela trouxe.
 *
 * <p>É o efeito colateral entre módulos feito do jeito que a arquitetura pede: a venda publica um
 * fato, o caixa reage. Vendas não chama este módulo; este módulo não é chamado por ninguém. O
 * evento chega pelo registro de publicação, gravado na mesma transação que concluiu a venda, e
 * só depois do commit dela; se esta classe falhar, a publicação fica incompleta e pode ser
 * reprocessada, em vez de o dinheiro sumir do caixa em silêncio.
 *
 * <h2>O que entra na gaveta</h2>
 *
 * <p><strong>Só a soma das parcelas em DINHEIRO confirmadas.</strong> O que foi pago em Pix ou
 * cartão nunca esteve na gaveta, e o esperado da sessão é o que deveria haver nela; contar o
 * resto faria a conferência do fechamento (RF15) acusar falta em toda venda que não fosse em
 * espécie. Uma venda sem parcela em dinheiro não gera movimento nenhum, porque a gaveta não
 * mexeu; a venda continua ligada à sessão pelo próprio id da sessão, gravado nela.
 *
 * <p><strong>A mesma venda entra uma vez.</strong> A entrega é garantida ao menos uma vez, e não
 * exatamente uma, então o evento pode chegar de novo; a raiz sabe dizer se a venda já foi lançada,
 * e a reentrega vira um não fazer nada, registrado em log. A raiz também recusa a duplicata por
 * conta própria, para que nenhum outro chamador conte o dinheiro em dobro.
 *
 * <h2>Por que não a anotação de listener do Modulith</h2>
 *
 * <p>A anotação pronta do Modulith para listener de módulo é a soma de {@link Async},
 * {@link TransactionalEventListener} e uma transação nova aberta <em>antes</em> do corpo do
 * método. Este projeto não pode usá-la: o Hibernate resolve o tenant na abertura da sessão, e
 * uma transação aberta antes de a conta estar no contexto nasceria presa ao tenant sentinela,
 * sem enxergar a sessão de caixa. Por isso as duas primeiras anotações estão aqui por extenso, e
 * a transação é aberta à mão, com {@link TransactionTemplate}, <strong>depois</strong> de a conta
 * do evento entrar no contexto. A ordem é a regra inteira: tenant primeiro, transação depois.
 *
 * <p>A conta vem do evento, e não de quem publicou: esta thread não é a da requisição, e uma
 * reentrega horas depois não tem requisição nenhuma. O tenant é restaurado ao fim, como em todo
 * uso de {@code executarComo}.
 *
 * <p>Fica em {@code internal} porque é um adapter de entrada: nenhum outro módulo o nomeia, e ele
 * carrega e grava a sessão pelo repositório, na mesma sequência dos casos de uso do caixa.
 */
@Component
class VendaConcluidaListener {

    private static final Logger log = LoggerFactory.getLogger(VendaConcluidaListener.class);

    private final SessaoCaixaRepository sessoes;
    private final TransactionTemplate transacao;

    VendaConcluidaListener(SessaoCaixaRepository sessoes, TransactionTemplate transacao) {
        this.sessoes = sessoes;
        this.transacao = transacao;
    }

    /**
     * Roda depois do commit da transação que concluiu a venda, em outra thread.
     *
     * @throws SessaoCaixaNaoEncontradaException se a sessão do evento não existe na conta do
     *         evento; a publicação fica incompleta no registro
     * @throws IllegalStateException se a sessão já está FECHADA; idem
     */
    @Async
    @TransactionalEventListener
    public void lancarNoCaixa(VendaConcluida evento) {
        Money emDinheiro = evento.parcelas().stream()
                .filter(parcela -> parcela.forma() == FormaPagamento.DINHEIRO)
                .filter(parcela -> parcela.status() == StatusPagamento.CONFIRMADO)
                .map(VendaConcluida.Parcela::valor)
                .reduce(Money.ZERO, Money::somar);

        if (emDinheiro.equals(Money.ZERO)) {
            // A gaveta não mexeu: nada a lançar, e nem se abre transação.
            return;
        }

        // Tenant primeiro, transação depois. Ver o javadoc da classe.
        TenantContext.executarComo(evento.contaId(), () ->
                transacao.executeWithoutResult(status -> {
                    SessaoCaixaEntity linha = sessoes.findById(evento.sessaoCaixaId())
                            .orElseThrow(() -> new SessaoCaixaNaoEncontradaException(
                                    evento.sessaoCaixaId()));
                    SessaoCaixa sessao = linha.paraDominio();

                    if (sessao.jaRegistrouVenda(evento.vendaId())) {
                        // Reentrega do registro de publicação: o dinheiro já está na gaveta.
                        log.info("venda {} ja lancada na sessao de caixa {}; reentrega ignorada",
                                evento.vendaId(), evento.sessaoCaixaId());
                        return;
                    }

                    sessao.registrarVenda(emDinheiro, evento.vendaId());

                    linha.atualizarCom(sessao);
                    sessoes.save(linha);
                }));
    }
}
