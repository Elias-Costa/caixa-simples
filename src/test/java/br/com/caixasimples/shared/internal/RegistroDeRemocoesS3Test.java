package br.com.caixasimples.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.RegistroDeRemocoes.Tipo;
import br.com.caixasimples.shared.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import tools.jackson.databind.ObjectMapper;

class RegistroDeRemocoesS3Test {

    @Test
    void gravaIdentificadoresNoBucketSeparadoComCifraDoS3() throws Exception {
        S3Client s3 = mock(S3Client.class);
        ContaId conta = ContaId.nova();
        UUID clienteId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Instant instante = Instant.parse("2026-09-29T12:00:00Z");
        RegistroDeRemocoesS3 registro = new RegistroDeRemocoesS3(s3,
                new ObjectMapper(), "bucket-remocoes");

        TenantContext.executarComo(conta, () ->
                registro.registrar(Tipo.CLIENTE, clienteId, instante, adminId));

        ArgumentCaptor<PutObjectRequest> pedido = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> corpo = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(pedido.capture(), corpo.capture());
        assertThat(pedido.getValue().bucket()).isEqualTo("bucket-remocoes");
        assertThat(pedido.getValue().key())
                .startsWith("remocoes/" + conta + "/CLIENTE/" + clienteId + "/");
        assertThat(pedido.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        String conteudo = new String(corpo.getValue().contentStreamProvider().newStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        assertThat(conteudo).contains(conta.toString(), clienteId.toString(), adminId.toString(),
                instante.toString());
        assertThat(conteudo).doesNotContain("nome", "contato", "email");
    }
}
