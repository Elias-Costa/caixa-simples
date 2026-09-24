package br.com.caixasimples.vendas.web;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.WebhookPixAutenticador;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.vendas.application.VendaPixService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Callback sem JWT: a conexão mTLS e o segredo da URL identificam a Conta. */
@RestController
@RequestMapping("/api/webhooks/pix")
class WebhookPixController {
    private final WebhookPixAutenticador autenticador;
    private final VendaPixService pix;

    WebhookPixController(WebhookPixAutenticador autenticador, VendaPixService pix) {
        this.autenticador = autenticador;
        this.pix = pix;
    }

    @PostMapping({"/{identificador}", "/{identificador}/pix"})
    ResponseEntity<?> receber(@PathVariable String identificador, @RequestParam String hmac,
            @RequestBody JsonNode aviso, HttpServletRequest requisicao) {
        Object atributo = requisicao.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!requisicao.isSecure() || !(atributo instanceof X509Certificate[] certificados)
                || certificados.length == 0) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ProblemDetail.forStatus(HttpStatus.FORBIDDEN));
        }
        ContaId conta = autenticador.autenticar(identificador, hmac);
        if (conta == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ProblemDetail.forStatus(HttpStatus.FORBIDDEN));
        }
        JsonNode recebidos = aviso.path("pix");
        if (!recebidos.isArray() || recebidos.size() > 100) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
        }
        for (JsonNode recebido : recebidos) {
            String txid = recebido.path("txid").asText("");
            if (!txid.matches("[a-zA-Z0-9]{26,35}")) {
                return ResponseEntity.badRequest()
                        .body(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
            }
        }
        try {
            for (JsonNode recebido : recebidos) {
                pix.receberNotificacao(conta, recebido.path("txid").asText());
            }
            return ResponseEntity.noContent().build();
        } catch (PixIndisponivelException erro) {
            // A Efí pode reentregar; resultado incerto nunca vira confirmação local.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
    }
}
