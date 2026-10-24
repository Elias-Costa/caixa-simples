package br.com.caixasimples.shared.web;

import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.EventoDeSeguranca;
import br.com.caixasimples.shared.PilhaSemMensagens;
import br.com.caixasimples.shared.RegistroDeRemocoesIndisponivelException;
import br.com.caixasimples.shared.TenantNaoResolvidoException;
import br.com.caixasimples.shared.UsuarioNaoResolvidoException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /**
     * Quem chama está autenticado, mas o perfil dele não alcança a operação (RF30).
     *
     * <p>A recusa vai ao log como evento de segurança, com quem tentou, onde e de que origem: um
     * operador batendo em rota do administrador, ou um token furtado explorando a API, aparece aqui.
     * O caminho vai sem a query, que pode levar o que alguém digitou.
     */
    @ExceptionHandler(AcessoNegadoException.class)
    ProblemDetail acessoNegado(AcessoNegadoException excecao, HttpServletRequest requisicao) {
        EventoDeSeguranca.de("acesso.recusado")
                .doContexto()
                .com("metodo", requisicao.getMethod())
                .com("caminho", requisicao.getRequestURI())
                .daOrigem(requisicao.getRemoteAddr())
                .registrarRecusa();
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
     * O corpo enviado em partes passou do limite da rota enquanto era lido. É a mesma resposta que
     * {@link LimiteDoCorpoFilter} dá ao pedido que declara um tamanho acima do limite.
     */
    @ExceptionHandler(CorpoGrandeDemaisException.class)
    ProblemDetail corpoGrandeDemais(CorpoGrandeDemaisException excecao) {
        return LimiteDoCorpoFilter.recusa(excecao.limite());
    }

    @ExceptionHandler(RegistroDeRemocoesIndisponivelException.class)
    ProblemDetail registroDeRemocaoIndisponivel(RegistroDeRemocoesIndisponivelException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                excecao.getMessage());
    }

    /**
     * Duas operações alteraram a mesma raiz ao mesmo tempo, e a versão gravada recusou a segunda:
     * por exemplo, duas vendas concluídas juntas que mexem na mesma sessão de caixa ou no mesmo
     * produto, cujo dinheiro e cuja baixa entram na transação da conclusão.
     *
     * <p>É conflito com o estado atual, como o 409 acima, e a resposta para quem chamou é a mesma:
     * recarregar e repetir. Nada foi gravado pela operação recusada.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail alteradoAoMesmoTempo() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O registro foi alterado por outra operação ao mesmo tempo; recarregue e tente"
                        + " de novo.");
    }

    /**
     * O que o próprio Spring recusa antes do controller chega com o detalhe dele, em inglês, como
     * Failed to read request, e a tela mostra o detalhe como veio. Aqui ele vira uma frase em
     * português, escolhida pelo tipo da recusa e pelo status; o título e o status são os do Spring.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception excecao, Object corpo,
            HttpHeaders cabecalhos, HttpStatusCode status, WebRequest requisicao) {
        ResponseEntity<Object> resposta =
                super.handleExceptionInternal(excecao, corpo, cabecalhos, status, requisicao);
        if (resposta != null && resposta.getBody() instanceof ProblemDetail problema) {
            problema.setDetail(detalheDoFramework(excecao, status));
        }
        return resposta;
    }

    private static String detalheDoFramework(Exception excecao, HttpStatusCode status) {
        if (excecao instanceof MethodArgumentNotValidException) {
            return "Confira os campos destacados.";
        }
        if (excecao instanceof HttpMessageNotReadableException) {
            return "Não foi possível ler o pedido; confira o que foi enviado.";
        }
        return switch (status.value()) {
            case 400 -> "Falta um dado no pedido, ou ele veio num formato que o sistema não entende.";
            case 404 -> "Endereço não encontrado.";
            case 405 -> "Esta operação não existe neste endereço.";
            case 406, 415 -> "O pedido veio num formato que o sistema não aceita.";
            default -> "O pedido não pôde ser atendido.";
        };
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
     * viajar ao cliente.
     *
     * <p>O log recebe o método, o caminho e a pilha sem nenhuma mensagem, porque o Spring não registra
     * o que um tratador de exceção resolveu, e a mensagem pode trazer dado pessoal, como o valor
     * repetido que o banco cita na violação de unicidade. O caminho chega como o cliente o mandou,
     * ainda codificado, então não tem espaço nem quebra de linha. Sem Conta nem origem, porque o
     * defeito não é evento de segurança.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail inesperado(Exception excecao, HttpServletRequest requisicao) {
        log.error("Erro inesperado em {} {}{}{}", requisicao.getMethod(),
                requisicao.getRequestURI(), System.lineSeparator(), PilhaSemMensagens.de(excecao));
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
