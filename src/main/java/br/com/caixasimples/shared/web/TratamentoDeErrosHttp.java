package br.com.caixasimples.shared.web;

import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.TenantNaoResolvidoException;
import br.com.caixasimples.shared.UsuarioNaoResolvidoException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduz exceção em resposta HTTP, para toda a API, no formato Problem Details (RFC 9457).
 *
 * <p>Estende o tratador do próprio Spring MVC de propósito: a classe-base já responde nesse
 * formato para o que o framework recusa antes de chegar ao controller (corpo ilegível, tipo de
 * parâmetro errado, método não suportado, validação), e as exceções da aplicação entram aqui na
 * mesma forma. O cliente recebe uma forma só, venha o erro de onde vier.
 *
 * <p>Este advice só conhece as exceções de {@code shared} e as duas que os agregados lançam.
 * A exceção de não encontrado de cada módulo é traduzida em 404 no pacote web do próprio módulo,
 * onde ela é lançada, sem hierarquia de exceção para carregar o status.
 *
 * <p>A autorização não é decidida aqui: cada caso de uso restrito pergunta o perfil na primeira
 * linha e lança {@link AcessoNegadoException}. Aqui a recusa só vira 403.
 */
@RestControllerAdvice
class TratamentoDeErrosHttp extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TratamentoDeErrosHttp.class);

    /** Quem chama está autenticado, mas o perfil dele não alcança a operação (RF30). */
    @ExceptionHandler(AcessoNegadoException.class)
    ProblemDetail acessoNegado(AcessoNegadoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, excecao.getMessage());
    }

    /**
     * Caso de uso alcançado sem identidade ou sem conta no contexto.
     *
     * <p>Numa requisição normal isso não acontece, porque toda rota exige token e o filtro de
     * identidade recusa antes. Fica aqui como rede: se acontecer, a resposta é a mesma de quem
     * não se autenticou, sem detalhe, porque não há o que explicar a quem não se identificou.
     */
    @ExceptionHandler({UsuarioNaoResolvidoException.class, TenantNaoResolvidoException.class})
    ProblemDetail semIdentidade() {
        return ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Argumento que o domínio recusa (valor negativo, motivo vazio, quantidade com casas demais).
     *
     * <p>Mesmo status da validação de Bean Validation, porque nos dois casos é o pedido que está
     * errado, e a mensagem da exceção diz o quê.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail argumentoInvalido(IllegalArgumentException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, excecao.getMessage());
    }

    /**
     * Estado atual do agregado não permite a operação (caixa fechado, venda já concluída, já
     * existe caixa aberto para o operador).
     *
     * <p>É conflito com o estado atual do recurso, a definição do 409. A tela reage diferente
     * do 400: não é corrigir o que se digitou, é recarregar o que mudou.
     */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail estadoInvalido(IllegalStateException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, excecao.getMessage());
    }

    /**
     * Violação de Bean Validation num corpo anotado com {@code @Valid}.
     *
     * <p>A classe-base já responde 400 em Problem Details; o acréscimo é a propriedade
     * {@code campos}, com a mensagem por campo, para o formulário apontar o que corrigir.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException excecao, HttpHeaders cabecalhos, HttpStatusCode status,
            WebRequest requisicao) {

        Map<String, String> campos = new LinkedHashMap<>();
        for (FieldError erro : excecao.getBindingResult().getFieldErrors()) {
            campos.putIfAbsent(erro.getField(), erro.getDefaultMessage());
        }

        ProblemDetail corpo = excecao.getBody();
        corpo.setProperty("campos", campos);
        return handleExceptionInternal(excecao, corpo, cabecalhos, status, requisicao);
    }

    /**
     * Qualquer outra exceção é defeito, não regra: 500 sem detalhe, para a mensagem interna não
     * viajar ao cliente, e a pilha inteira no log, porque o Spring não registra o que um tratador
     * de exceção resolveu.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail inesperado(Exception excecao) {
        log.error("Erro inesperado ao atender a requisicao", excecao);
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
