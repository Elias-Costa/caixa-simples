package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.domain.WebhookPixAutenticador;
import br.com.caixasimples.shared.ContaId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Configuração opaca da URL, com segredo por Conta fora do banco e do Git. */
@Component
class EfiWebhookPixAutenticador implements WebhookPixAutenticador {
    private static final Pattern NOME = Pattern.compile(
            "CAIXA_SIMPLES_EFI_([0-9A-F]{32})_WEBHOOK_ID");
    private final Map<String, String> ambiente;

    EfiWebhookPixAutenticador() {
        this(System.getenv());
    }

    EfiWebhookPixAutenticador(Map<String, String> ambiente) {
        this.ambiente = ambiente;
    }

    @Override
    public ContaId autenticar(String identificador, String segredo) {
        if (identificador == null || identificador.isBlank() || segredo == null
                || segredo.isBlank()) {
            return null;
        }
        ContaId encontrada = null;
        for (var entrada : ambiente.entrySet()) {
            Matcher nome = NOME.matcher(entrada.getKey());
            if (!nome.matches() || !seguro(identificador, entrada.getValue())) {
                continue;
            }
            String prefixo = entrada.getKey().substring(0, entrada.getKey().length() - 2);
            String esperado = ambiente.get(prefixo + "SECRET");
            if (esperado == null || esperado.isBlank() || !seguro(segredo, esperado)
                    || encontrada != null) {
                return null;
            }
            String hex = nome.group(1).toLowerCase();
            encontrada = ContaId.de(UUID.fromString(hex.substring(0, 8) + "-"
                    + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                    + hex.substring(16, 20) + "-" + hex.substring(20)));
        }
        return encontrada;
    }

    private static boolean seguro(String recebido, String esperado) {
        return MessageDigest.isEqual(recebido.getBytes(StandardCharsets.UTF_8),
                esperado.getBytes(StandardCharsets.UTF_8));
    }
}
