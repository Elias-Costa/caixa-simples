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
            throw new ClienteInativoParaVendaException();
        }
    }

    public String nomeDe(UUID clienteId) {
        try {
            return clientes.consultar(clienteId).nome();
        } catch (ClienteNaoEncontradoException excecao) {
            throw new ClienteNaoEncontradoParaVendaException(clienteId);
        }
    }

    /**
     * O Cliente existe e está inativo. Com rede, é a recusa de sempre, com a saída de quem ainda
     * monta a venda. O tipo próprio existe porque o lote do dispositivo também a recebe, para uma
     * venda que já aconteceu no balcão, e escreve a frase dele.
     */
    public static class ClienteInativoParaVendaException extends IllegalStateException {
        public ClienteInativoParaVendaException() {
            super("Este cliente está inativo; reative-o em Clientes antes de vinculá-lo à venda.");
        }
    }

    public static class ClienteNaoEncontradoParaVendaException extends RuntimeException {
        public ClienteNaoEncontradoParaVendaException(UUID id) {
            super("Cliente não encontrado nesta Conta.");
        }
    }
}
