package br.com.caixasimples.sincronizacao.internal;

import br.com.caixasimples.shared.ClienteEditado;
import br.com.caixasimples.shared.TenantContext;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * As cópias anteriores do cadastro saem junto com a edição, na transação dela.
 *
 * <p>Quando a edição chega pelo lote, o gesto dela só é gravado depois de aplicado, então ainda não
 * está entre as cópias encontradas aqui e fica com o conteúdo atual: o reenvio desse gesto continua
 * reconhecido, e o de um gesto anterior, com o conteúdo antigo, vira conflito.
 */
@Component
class ClienteEditadoListener {

    private final OperacaoSincronizadaRepository operacoes;

    ClienteEditadoListener(OperacaoSincronizadaRepository operacoes) {
        this.operacoes = operacoes;
    }

    @EventListener
    void aoEditar(ClienteEditado evento) {
        if (!TenantContext.exigirAtual().equals(evento.contaId())) {
            throw new IllegalStateException("a edicao pertence a outra conta");
        }
        List<OperacaoSincronizada> copias = operacoes.findByRegistroIdAndTipoIn(
                evento.clienteId(), List.of("cliente.criar", "cliente.editar"));
        copias.forEach(OperacaoSincronizada::esvaziarConteudoDoCliente);
        operacoes.saveAll(copias);
    }
}
