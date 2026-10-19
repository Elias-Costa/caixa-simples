package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.SenhaAtualIncorretaException;
import br.com.caixasimples.contas.internal.LoginContidoException;
import br.com.caixasimples.shared.EventoDeSeguranca;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erros que só este módulo conhece e que mais de um controller dele lança: a senha atual errada e a
 * contenção das tentativas, que valem no login, nas trocas da senha e do e-mail e na troca da chave
 * Pix da Conta. O contrato transversal de Problem Details fica em shared.
 *
 * <p>Precedência máxima porque o tratador transversal responde a qualquer exceção, e entre os
 * advices o primeiro que sabe tratar a exceção é o que responde.
 */
@RestControllerAdvice(basePackageClasses = AutenticacaoController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ErrosDeContasHttp {

    /**
     * Tentativas demais da mesma origem, ou do mesmo e-mail vindo dela, viram 429 com a espera até a
     * janela acabar: em segundos no Retry-After e em minutos na mensagem, que a tela mostra. Os dois
     * arredondam para cima, para quem esperar o que foi dito não ser recusado de novo.
     */
    @ExceptionHandler(LoginContidoException.class)
    ResponseEntity<ProblemDetail> loginContido(LoginContidoException contido) {
        long segundos = (contido.espera().toMillis() + 999) / 1000;
        long minutos = (segundos + 59) / 60;
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Muitas tentativas de entrar. Tente de novo em " + minutos
                        + (minutos == 1 ? " minuto." : " minutos."));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(segundos))
                .body(problema);
    }

    /**
     * A senha atual errada responde 400, e não 401: quem pede a troca está autenticado, e o
     * aplicativo trata o 401 como sessão encerrada.
     *
     * <p>A recusa vai ao log com o nome da troca que tentou, e conta no alerta de rajada como um
     * login recusado, porque passa pela mesma contenção dele.
     */
    @ExceptionHandler(SenhaAtualIncorretaException.class)
    ProblemDetail senhaAtualIncorreta(SenhaAtualIncorretaException excecao,
            HttpServletRequest requisicao) {
        String evento = switch (excecao.troca()) {
            case SENHA -> "senha.troca_recusada";
            case EMAIL -> "email.troca_recusada";
            case CHAVE_PIX -> "chave_pix.troca_recusada";
        };
        EventoDeSeguranca.de(evento)
                .doContexto()
                .com("motivo", "senha_atual")
                .daOrigem(requisicao.getRemoteAddr())
                .registrarRecusa();
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, excecao.getMessage());
    }
}
