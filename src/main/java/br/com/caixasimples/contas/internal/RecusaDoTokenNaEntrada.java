package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.EventoDeSeguranca;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * O 401 da cadeia de segurança: responde como o Spring Security já responde e registra o token
 * apresentado e recusado.
 *
 * <p>Só o token que chegou e não valeu vai ao log, porque mostra alguém tentando entrar com uma
 * credencial forjada ou vencida. O pedido sem token nenhum também cai aqui, mas é quase só robô
 * procurando rota, e registrá-lo encheria o log sem dizer nada.
 *
 * <p>O motivo é vencido quando a recusa veio da validação que roda depois da assinatura conferida:
 * num token assinado por esta aplicação, o que ela recusa é o prazo. Qualquer outra recusa, como a
 * assinatura que não confere ou o texto que nem é um token, é inválido.
 */
final class RecusaDoTokenNaEntrada implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint respostaPadrao =
            new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest requisicao, HttpServletResponse resposta,
            AuthenticationException recusa) throws IOException, ServletException {
        if (recusa instanceof OAuth2AuthenticationException) {
            String motivo = recusa.getCause() instanceof JwtValidationException
                    ? "vencido"
                    : "invalido";
            EventoDeSeguranca.de("token.recusado")
                    .com("motivo", motivo)
                    .com("metodo", requisicao.getMethod())
                    .com("caminho", requisicao.getRequestURI())
                    .daOrigem(requisicao.getRemoteAddr())
                    .registrarRecusa();
        }
        respostaPadrao.commence(requisicao, resposta, recusa);
    }
}
