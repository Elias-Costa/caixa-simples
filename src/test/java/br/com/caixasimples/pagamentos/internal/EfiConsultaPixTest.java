package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EfiConsultaPixTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void soAceitaCobrancaConcluidaComPixRecebidoCorrespondente() {
        var resposta = EfiPixGateway.interpretarConsulta(JSON.readTree("""
                {"txid":"12345678901234567890123456789012","chave":"chave-a",
                 "valor":{"original":"10.00"},"status":"CONCLUIDA",
                 "pix":[{"txid":"12345678901234567890123456789012","valor":"10.00"}]}
                """));
        assertThat(resposta.pago()).isTrue();
        assertThat(resposta.valor()).isEqualTo(Money.de("10.00"));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                EfiPixGateway.interpretarConsulta(JSON.readTree("""
                        {"txid":"12345678901234567890123456789012","chave":"chave-a",
                         "valor":{"original":"10.00"},"status":"CONCLUIDA","pix":[]}
                        """)));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                EfiPixGateway.interpretarConsulta(JSON.readTree("""
                        {"txid":"12345678901234567890123456789012","chave":"chave-a",
                         "valor":{"original":"10.00"},"status":"CONCLUIDA",
                         "pix":[{"txid":"outro","valor":"10.00"}]}
                        """)));
    }

    @Test
    void configuracaoDoWebhookExigeIdentificadorESegredoDaMesmaConta() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String prefixoA = "CAIXA_SIMPLES_EFI_" + a.toString().replace("-", "").toUpperCase()
                + "_WEBHOOK_";
        String prefixoB = "CAIXA_SIMPLES_EFI_" + b.toString().replace("-", "").toUpperCase()
                + "_WEBHOOK_";
        var autenticador = new EfiWebhookPixAutenticador(Map.of(
                prefixoA + "ID", "id-a", prefixoA + "SECRET", "segredo-a",
                prefixoB + "ID", "id-b", prefixoB + "SECRET", "segredo-b"));
        assertThat(autenticador.autenticar("id-a", "segredo-a")).isEqualTo(ContaId.de(a));
        assertThat(autenticador.autenticar("id-a", "segredo-b")).isNull();
        assertThat(autenticador.autenticar("id-inexistente", "segredo-a")).isNull();
    }
}
