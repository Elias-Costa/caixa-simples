package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.EmailJaCadastradoException;
import br.com.caixasimples.contas.application.RedefinicaoRecusadaException;
import br.com.caixasimples.contas.application.UltimoAdministradorException;
import br.com.caixasimples.contas.application.UsuarioNaoEncontradoException;
import br.com.caixasimples.contas.application.UsuarioService;
import br.com.caixasimples.contas.application.UsuarioService.UsuarioDaConta;
import br.com.caixasimples.contas.internal.SenhaRecusadaException;
import br.com.caixasimples.shared.EventoDeSeguranca;
import br.com.caixasimples.shared.Perfil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestão de usuários da Conta autenticada, sem receber conta ou perfil de quem chama.
 *
 * <p>Cada mudança feita vai ao log como evento de segurança, com quem fez, o Usuário alcançado e a
 * origem: criar alguém, inativar e trocar a senha de outra pessoa são o que um token de
 * administrador furtado faria. O nome e o e-mail não vão.
 */
@RestController
@RequestMapping("/api/usuarios")
class UsuarioController {

    private final UsuarioService usuarios;

    UsuarioController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping
    List<UsuarioDaConta> listar() {
        return usuarios.listar();
    }

    @PostMapping
    ResponseEntity<Criado> criar(@Valid @RequestBody PedidoDeUsuario pedido,
            HttpServletRequest requisicao) {
        UUID id = usuarios.criar(pedido.nome(), pedido.perfil(), pedido.email(), pedido.senha());
        EventoDeSeguranca.de("usuario.criado")
                .doContexto()
                .com("alvo", id)
                .com("perfil", pedido.perfil())
                .daOrigem(requisicao.getRemoteAddr())
                .registrar();
        return ResponseEntity.created(URI.create("/api/usuarios/" + id)).body(new Criado(id));
    }

    @PostMapping("/{id}/inativar")
    ResponseEntity<Void> inativar(@PathVariable UUID id, HttpServletRequest requisicao) {
        usuarios.inativar(id);
        registrar("usuario.inativado", id, requisicao);
        return ResponseEntity.noContent().build();
    }

    /** A senha nova é provisória: o operador a troca na próxima entrada. */
    @PostMapping("/{id}/redefinir-senha")
    ResponseEntity<Void> redefinirSenha(@PathVariable UUID id,
            @Valid @RequestBody PedidoDeRedefinicao pedido, HttpServletRequest requisicao) {
        usuarios.redefinirSenha(id, pedido.senha());
        registrar("senha.redefinida", id, requisicao);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/anonimizar")
    ResponseEntity<Void> anonimizar(@PathVariable UUID id, HttpServletRequest requisicao) {
        usuarios.anonimizarNome(id);
        registrar("usuario.nome_removido", id, requisicao);
        return ResponseEntity.noContent().build();
    }

    private static void registrar(String evento, UUID alvo, HttpServletRequest requisicao) {
        EventoDeSeguranca.de(evento)
                .doContexto()
                .com("alvo", alvo)
                .daOrigem(requisicao.getRemoteAddr())
                .registrar();
    }

    @ExceptionHandler(UsuarioNaoEncontradoException.class)
    ProblemDetail naoEncontrado(UsuarioNaoEncontradoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }

    @ExceptionHandler({EmailJaCadastradoException.class, UltimoAdministradorException.class,
            RedefinicaoRecusadaException.class})
    ProblemDetail conflito(RuntimeException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, excecao.getMessage());
    }

    @ExceptionHandler(SenhaRecusadaException.class)
    ProblemDetail senhaRecusada(SenhaRecusadaException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, excecao.getMessage());
    }

    record PedidoDeUsuario(@NotBlank String nome, @NotNull Perfil perfil,
            @NotBlank @Email String email, @NotBlank String senha) {
    }

    record Criado(UUID id) {
    }

    record PedidoDeRedefinicao(@NotBlank String senha) {
    }
}
