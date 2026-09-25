package br.com.caixasimples.shared.web;

import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.TenantNaoResolvidoException;
import br.com.caixasimples.shared.UsuarioNaoResolvidoException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas que só existem na suíte, uma por exceção que o tratador transversal traduz.
 *
 * <p>O primeiro endpoint de negócio chega depois deste passo; até lá, é por aqui que se prova,
 * por HTTP, que a recusa por perfil vira 403, que argumento inválido vira 400 e estado inválido
 * 409, e que erro inesperado sai sem a mensagem interna. Fica atrás da autenticação como
 * qualquer rota, então o 403 só se vê com token válido.
 *
 * <p><strong>Descoberto por varredura, e não declarado em {@code ConfiguracaoDeTeste}</strong>
 * como as outras fixtures: só a anotação de controller faz do bean um handler de rota, e
 * declará-lo também como bean o registraria duas vezes.
 */
@RestController
@RequestMapping("/api/teste/erros")
class ControllerDeErrosDeTeste {

    static final String MENSAGEM_DE_ARGUMENTO = "valor nao pode ser negativo";
    static final String MENSAGEM_DE_ESTADO = "sessao FECHADA nao aceita movimento";
    static final String MENSAGEM_INTERNA = "detalhe interno que nao pode vazar";

    @GetMapping("/acesso-negado")
    void acessoNegado() {
        throw new AcessoNegadoException("operacao restrita ao administrador");
    }

    @GetMapping("/sem-usuario")
    void semUsuario() {
        throw new UsuarioNaoResolvidoException();
    }

    @GetMapping("/sem-tenant")
    void semTenant() {
        throw new TenantNaoResolvidoException();
    }

    @GetMapping("/argumento")
    void argumento() {
        throw new IllegalArgumentException(MENSAGEM_DE_ARGUMENTO);
    }

    @GetMapping("/estado")
    void estado() {
        throw new IllegalStateException(MENSAGEM_DE_ESTADO);
    }

    @GetMapping("/alterado-ao-mesmo-tempo")
    void alteradoAoMesmoTempo() {
        throw new OptimisticLockingFailureException(MENSAGEM_INTERNA);
    }

    @GetMapping("/inesperado")
    void inesperado() {
        throw new RuntimeException(MENSAGEM_INTERNA);
    }

    @PostMapping("/validacao")
    Pedido validacao(@Valid @RequestBody Pedido pedido) {
        return pedido;
    }

    @GetMapping("/nao-encontrado")
    void naoEncontrado() {
        throw new RecursoDeTesteNaoEncontradoException();
    }

    /**
     * O molde do 404 de cada módulo: a exceção de não encontrado é traduzida no pacote web do
     * módulo que a lança, e não no tratador transversal.
     */
    @ExceptionHandler(RecursoDeTesteNaoEncontradoException.class)
    ProblemDetail naoEncontrado(RecursoDeTesteNaoEncontradoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }

    record Pedido(@NotBlank String nome, @NotNull @DecimalMin("0") BigDecimal valor) {
    }

    static class RecursoDeTesteNaoEncontradoException extends RuntimeException {
        RecursoDeTesteNaoEncontradoException() {
            super("recurso de teste nao encontrado");
        }
    }
}
