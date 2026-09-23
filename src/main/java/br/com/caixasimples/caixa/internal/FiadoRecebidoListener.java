package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.FiadoRecebido;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Lança na sessão de quem recebeu apenas o dinheiro físico de um fiado. */
@Component
class FiadoRecebidoListener {

    private final SessaoCaixaRepository sessoes;
    private final TransactionTemplate transacao;

    FiadoRecebidoListener(SessaoCaixaRepository sessoes, TransactionTemplate transacao) {
        this.sessoes = sessoes;
        this.transacao = transacao;
    }

    @Async
    @TransactionalEventListener
    public void lancarNoCaixa(FiadoRecebido evento) {
        if (evento.forma() != FormaPagamento.DINHEIRO) {
            return;
        }
        // O tenant precisa estar no contexto antes de abrir a sessão do Hibernate.
        TenantContext.executarComo(evento.contaId(), () ->
                transacao.executeWithoutResult(status -> {
                    SessaoCaixaEntity linha = sessoes.findById(evento.sessaoCaixaId())
                            .orElseThrow(() -> new SessaoCaixaNaoEncontradaException(
                                    evento.sessaoCaixaId()));
                    SessaoCaixa sessao = linha.paraDominio();
                    if (sessao.jaRegistrouRecebimento(evento.recebimentoId())) {
                        return;
                    }
                    sessao.registrarRecebimento(evento.vendaId(), evento.recebimentoId(),
                            evento.valor());
                    linha.atualizarCom(sessao);
                    sessoes.save(linha);
                }));
    }
}
