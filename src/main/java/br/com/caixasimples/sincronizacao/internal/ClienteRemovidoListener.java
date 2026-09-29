package br.com.caixasimples.sincronizacao.internal;

import br.com.caixasimples.shared.ClienteRemovido;
import br.com.caixasimples.shared.TenantContext;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A cópia do gesto sai junto com o nome do Cliente, na transação da remoção. */
@Component
class ClienteRemovidoListener {

    private final OperacaoSincronizadaRepository operacoes;

    ClienteRemovidoListener(OperacaoSincronizadaRepository operacoes) {
        this.operacoes = operacoes;
    }

    @EventListener
    void aoRemover(ClienteRemovido evento) {
        if (!TenantContext.exigirAtual().equals(evento.contaId())) {
            throw new IllegalStateException("a remocao pertence a outra conta");
        }
        List<OperacaoSincronizada> copias = operacoes.findByRegistroIdAndTipoIn(
                evento.clienteId(), List.of("cliente.criar", "cliente.editar"));
        copias.forEach(OperacaoSincronizada::anonimizarCliente);
        operacoes.saveAll(copias);
    }
}
