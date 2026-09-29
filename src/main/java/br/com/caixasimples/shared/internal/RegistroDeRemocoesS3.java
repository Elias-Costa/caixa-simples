package br.com.caixasimples.shared.internal;

import br.com.caixasimples.shared.RegistroDeRemocoes;
import br.com.caixasimples.shared.RegistroDeRemocoesIndisponivelException;
import br.com.caixasimples.shared.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import tools.jackson.databind.ObjectMapper;

/** Grava só identificadores no bucket durável antes de alterar o banco. */
@Component
class RegistroDeRemocoesS3 implements RegistroDeRemocoes {

    private final S3Client s3;
    private final ObjectMapper json;
    private final String bucket;

    RegistroDeRemocoesS3(S3Client s3, ObjectMapper json,
            @Value("${CAIXA_SIMPLES_REMOCOES_BUCKET:}") String bucket) {
        this.s3 = s3;
        this.json = json;
        this.bucket = bucket;
    }

    @Override
    public void registrar(Tipo tipo, UUID registroId, Instant instante, UUID solicitadoPor) {
        UUID contaId = TenantContext.exigirAtual().valor();
        if (bucket.isBlank()) {
            throw new RegistroDeRemocoesIndisponivelException();
        }
        String chave = "remocoes/" + contaId + "/" + tipo + "/" + registroId + "/"
                + instante.toEpochMilli() + "-" + UUID.randomUUID() + ".json";
        byte[] corpo = json.writeValueAsBytes(Map.of(
                "contaId", contaId.toString(),
                "tipo", tipo.name(),
                "registroId", registroId.toString(),
                "instante", instante.toString(),
                "solicitadoPor", solicitadoPor.toString()));
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(chave)
                    .contentType("application/json")
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .ifNoneMatch("*").build(), RequestBody.fromBytes(corpo));
        } catch (SdkException falha) {
            throw new RegistroDeRemocoesIndisponivelException();
        }
    }
}

@Configuration(proxyBeanMethods = false)
class ConfiguracaoDoRegistroDeRemocoes {

    @Bean
    S3Client clienteS3DeRemocoes() {
        return S3Client.builder().region(Region.SA_EAST_1).build();
    }
}
