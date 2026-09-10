package br.com.caixasimples.cadastro.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório do catálogo sugerido por tipo de negócio (RF32).
 *
 * <p><strong>É o único repositório de {@code cadastro} cujas consultas não são filtradas por
 * conta</strong>, porque {@link ModeloProdutoEntity} não tem {@code @TenantId}: ele é dado de
 * referência da plataforma, não de conta nenhuma. Isso não é brecha de isolamento nem precedente
 * para mais nada, porque o que a conta enxerga são as <em>cópias</em> em {@code produto}, essas sim
 * filtradas.
 *
 * <p>Não existe método de escrita além do {@code save} herdado, e ninguém em produção o chama: as
 * linhas vêm da migration.
 */
public interface ModeloProdutoRepository extends JpaRepository<ModeloProdutoEntity, UUID> {

    /**
     * Ignora maiúscula e minúscula, o mesmo tratamento que o sistema dá ao e-mail e ao código do
     * produto: {@code Conta.tipo_negocio} aceita texto livre, e {@code Cafeteria} não pode deixar
     * de casar com {@code cafeteria}. Sustentado pelo índice sobre {@code lower(tipo_negocio)} da
     * migration V4.
     */
    List<ModeloProdutoEntity> findByTipoNegocioIgnoreCase(String tipoNegocio);
}
