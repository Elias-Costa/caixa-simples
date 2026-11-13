package br.com.caixasimples.cadastro.web;

import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.ClienteNaoEncontradoException;
import br.com.caixasimples.cadastro.internal.ClienteService.ClienteComVersao;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.shared.EventoDeSeguranca;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cliente se cadastra no balcão: ADMIN e OPERADOR usam os mesmos casos de uso. */
@RestController
@RequestMapping("/api/clientes")
class ClienteController {

    private final ClienteService clientes;

    ClienteController(ClienteService clientes) {
        this.clientes = clientes;
    }

    @GetMapping
    List<ClienteComVersao> listar() {
        return clientes.listarAtivosComVersao();
    }

    @GetMapping("/inativos")
    List<ClienteComVersao> listarInativos() {
        return clientes.listarInativosComVersao();
    }

    /**
     * Com o id do aparelho, o reenvio igual responde 200 com o mesmo cliente; sem ele, o servidor
     * gera o id, e o reenvio grava outro cliente.
     */
    @PostMapping
    ResponseEntity<Criado> cadastrar(@Valid @RequestBody PedidoDeCliente pedido) {
        if (pedido.clienteId() == null) {
            UUID id = clientes.cadastrar(pedido.dados());
            return ResponseEntity.created(URI.create("/api/clientes/" + id)).body(new Criado(id));
        }
        var cadastrado = clientes.cadastrar(pedido.clienteId(), pedido.dados(), Instant.now());
        return ResponseEntity.status(cadastrado.repetido() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/clientes/" + cadastrado.id()))
                .body(new Criado(cadastrado.id()));
    }

    @PutMapping("/{id}")
    ResponseEntity<Void> editar(@PathVariable UUID id, @Valid @RequestBody PedidoDeCliente pedido) {
        clientes.editar(id, pedido.dados());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/inativar")
    ResponseEntity<Void> inativar(@PathVariable UUID id) {
        clientes.inativar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reativar")
    ResponseEntity<Void> reativar(@PathVariable UUID id) {
        clientes.reativar(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * A remoção apaga o nome e o contato e não se desfaz, por isso vai ao log como evento de
     * segurança, com quem removeu, o Cliente e a origem, sem nada do que foi apagado.
     */
    @PostMapping("/{id}/remover")
    ResponseEntity<Void> remover(@PathVariable UUID id, HttpServletRequest requisicao) {
        clientes.remover(id);
        EventoDeSeguranca.de("cliente.removido")
                .doContexto()
                .com("alvo", id)
                .daOrigem(requisicao.getRemoteAddr())
                .registrar();
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ClienteNaoEncontradoException.class)
    ProblemDetail naoEncontrado(ClienteNaoEncontradoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }

    /**
     * O clienteId é opcional porque o contrato com o aparelho só cresce: o aplicativo instalado que
     * não o manda continua cadastrando. A edição usa o mesmo pedido e ignora o id.
     */
    record PedidoDeCliente(UUID clienteId, @NotBlank String nome, String contato) {

        DadosDoCliente dados() {
            return new DadosDoCliente(nome, contato);
        }
    }

    record Criado(UUID id) {
    }
}
