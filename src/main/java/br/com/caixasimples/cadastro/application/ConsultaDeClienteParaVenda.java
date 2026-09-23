package br.com.caixasimples.cadastro.application;

import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.ClienteNaoEncontradoException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Pergunta pública do cadastro usada antes de vincular um Cliente a uma Venda. */
@Service
public class ConsultaDeClienteParaVenda {

    private final ClienteService clientes;

    ConsultaDeClienteParaVenda(ClienteService clientes) {
        this.clientes = clientes;
    }

    public void exigirAtivo(UUID clienteId) {
        boolean ativo;
        try {
            ativo = clientes.consultarSeAtivo(clienteId);
        } catch (ClienteNaoEncontradoException excecao) {
            throw new ClienteNaoEncontradoParaVendaException(clienteId);
        }
        if (!ativo) {
            throw new IllegalStateException("cliente inativo; reative antes de vincular a venda");
        }
    }

    public String nomeDe(UUID clienteId) {
        try {
            return clientes.consultar(clienteId).nome();
        } catch (ClienteNaoEncontradoException excecao) {
            throw new ClienteNaoEncontradoParaVendaException(clienteId);
        }
    }

    public static class ClienteNaoEncontradoParaVendaException extends RuntimeException {
        public ClienteNaoEncontradoParaVendaException(UUID id) {
            super("cliente nao encontrado nesta conta: " + id);
        }
    }
}
